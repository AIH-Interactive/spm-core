package dev.spm.core.lua;

import dev.spm.core.api.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LuaVmTest {
    @Test void closuresControlFlowVarargsAndMetatablesWork() {
        var prints = new ArrayList<String>();
        var vm = new LuaVm(LuaBudget.avatarDefault(new CancellationToken()), null, prints::add);
        LuaValue[] res = vm.execute("test.lua", """
            local function makeCounter(start)
                local n = start
                return function(step)
                    n = n + (step or 1)
                    return n
                end
            end
            local c = makeCounter(10)
            local a, b = c(), c(5)
            a, b = b, a

            local sum = 0
            for i = 1, 10, 2 do
                sum = sum + i
                if i == 7 then break end
            end

            local acc = 0
            local rep = 0
            repeat
                local inner = rep * 2
                acc = acc + inner
                rep = rep + 1
            until inner >= 6

            local mt = {
                __add = function(x, y) return setmetatable({v = x.v + y.v}, getmetatable(x)) end,
                __tostring = function(x) return "Vec(" .. x.v .. ")" end,
                __call = function(x, k) return x.v * k end
            }
            local obj = setmetatable({v = 3}, mt) + setmetatable({v = 4}, mt)
            print(tostring(obj), obj(10))

            local function collect(...)
                local n = select("#", ...)
                local t = table.pack(...)
                return n, t[2]
            end
            local cnt, second = collect("x", "y", "z")
            return a, b, sum, acc, cnt, second, obj(2)
            """);

        assertEquals(7, res.length);
        assertEquals(16.0, res[0].asDouble());
        assertEquals(11.0, res[1].asDouble());
        assertEquals(16.0, res[2].asDouble()); // 1+3+5+7
        assertEquals(12.0, res[3].asDouble()); // 0+2+4+6
        assertEquals(3.0, res[4].asDouble());
        assertEquals("y", res[5].asString());
        assertEquals(14.0, res[6].asDouble());
        assertEquals(List.of("Vec(7)\t70"), prints);
    }

    @Test void stringPatternsTableSortBit32AndRequireWork() {
        Map<String, String> modules = Map.of(
                "lib.vec", "return { add = function(a, b) return a + b end }",
                "a", "return require('b')",
                "b", "return require('a')"
        );
        var vm = new LuaVm(LuaBudget.avatarDefault(new CancellationToken()), modules::get, null);
        LuaValue[] res = vm.execute("patterns.lua", """
            local vec = require("lib.vec")
            local upper = ("hello"):upper()
            local k, v = string.match("key=42", "(%a+)=(%d+)")
            local replaced, n = string.gsub("a1 b2 c3", "(%a)(%d)", function(ch, d)
                return ch .. "[" .. d .. "]"
            end)
            local words = {}
            for w in string.gmatch("one two three", "%a+") do
                table.insert(words, w)
            end
            table.sort(words, function(x, y) return x > y end)
            local bits = bit32.bor(bit32.lshift(1, 4), 3)
            local okCycle = pcall(require, "a")
            return vec.add(2, 3), upper, k .. ":" .. v, replaced, n, table.concat(words, ","), bits, okCycle
            """);

        assertEquals(5.0, res[0].asDouble());
        assertEquals("HELLO", res[1].asString());
        assertEquals("key:42", res[2].asString());
        assertEquals("a[1] b[2] c[3]", res[3].asString());
        assertEquals(3.0, res[4].asDouble());
        assertEquals("two,three,one", res[5].asString());
        assertEquals(19.0, res[6].asDouble());
        assertFalse(res[7].isTruthy());
    }

    @Test void sandboxBlocksDangerousGlobalsAndEnforcesBudgetsThroughPcall() {
        var vm = new LuaVm(new LuaBudget(2_000, 16, 32 * 1024, new CancellationToken()));
        assertEquals(LuaValue.NIL, vm.globals().rawGet("io"));
        assertEquals(LuaValue.NIL, vm.globals().rawGet("os"));
        assertEquals(LuaValue.NIL, vm.globals().rawGet("debug"));
        assertEquals(LuaValue.NIL, vm.globals().rawGet("dofile"));
        assertEquals(LuaValue.NIL, vm.globals().rawGet("loadfile"));

        LuaException stepLimit = assertThrows(LuaException.class, () -> vm.execute("loop.lua", """
            pcall(function()
                while true do end
            end)
            """));
        assertEquals(LuaException.Reason.BUDGET, stepLimit.reason());

        var memVm = new LuaVm(new LuaBudget(100_000, 32, 32 * 1024, new CancellationToken()));
        LuaException memLimit = assertThrows(LuaException.class, () -> memVm.execute("oom.lua", """
            pcall(function()
                local s = "aaaaaaaa"
                while true do s = s .. s end
            end)
            """));
        assertEquals(LuaException.Reason.MEMORY, memLimit.reason());

        var token = new CancellationToken();
        token.cancel();
        var cancelVm = new LuaVm(LuaBudget.avatarDefault(token));
        assertThrows(CancellationException.class, () -> cancelVm.execute("cancel.lua", "return 1"));
    }
}
