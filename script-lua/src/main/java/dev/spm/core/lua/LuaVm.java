package dev.spm.core.lua;

import dev.spm.core.lua.LuaAst.Chunk;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Sandboxed pure-Java Lua 5.3 execution runtime backed by {@link LuaBudget}.
 */
public final class LuaVm {
    @FunctionalInterface
    public interface ModuleResolver {
        /** Returns Lua source for {@code moduleName}, or {@code null} when absent. */
        String resolve(String moduleName);
    }

    private final LuaBudget budget;
    private final LuaTable globals;
    private final LuaTable stringMetatable;
    private final LuaEvaluator evaluator;

    public LuaVm(LuaBudget budget) {
        this(budget, null, null);
    }

    public LuaVm(LuaBudget budget, ModuleResolver moduleResolver, Consumer<String> printSink) {
        this.budget = Objects.requireNonNull(budget);
        this.globals = new LuaTable(budget);
        this.stringMetatable = new LuaTable(budget);
        this.evaluator = new LuaEvaluator(this, budget);
        LuaStdLib.install(this, globals, stringMetatable, moduleResolver, printSink);
    }

    public LuaTable globals() { return globals; }

    public LuaBudget budget() { return budget; }

    LuaTable stringMetatable() { return stringMetatable; }

    LuaEvaluator evaluator() { return evaluator; }

    public LuaFunction compile(String chunkName, String source) {
        return compile(chunkName, source, null);
    }

    public LuaFunction compile(String chunkName, String source, LuaTable envOverride) {
        Objects.requireNonNull(chunkName);
        Objects.requireNonNull(source);
        budget.chargeMemory((long) source.length() * 2L);
        List<LuaToken> tokens = new LuaLexer(chunkName, source).tokenize();
        Chunk chunk = new LuaParser(chunkName, tokens).parse();
        return LuaFunction.nativeFn(chunkName, (vm, args) -> evaluator.executeChunk(chunk, envOverride));
    }

    public LuaValue[] execute(String chunkName, String source) {
        return compile(chunkName, source).call(this, LuaValues.empty());
    }

    public LuaValue[] invoke(LuaValue callee, LuaValue... args) {
        return evaluator.callValue(Objects.requireNonNull(callee), Objects.requireNonNull(args), 1);
    }

    public String toLuaString(LuaValue value) {
        return evaluator.toLuaString(Objects.requireNonNull(value));
    }

    public LuaValue getIndex(LuaValue target, LuaValue key) {
        return evaluator.getIndex(target, key, 1);
    }

    public void setIndex(LuaValue target, LuaValue key, LuaValue value) {
        evaluator.setIndex(target, key, value, 1);
    }
}
