package dev.spm.core.lua;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Associative Lua table with insertion-ordered hash entries and sequence length helper. */
public final class LuaTable implements LuaValue {
    private static final long BYTES_PER_ENTRY = 48L;

    private final Map<LuaValue, LuaValue> entries = new LinkedHashMap<>();
    private final LuaBudget budget;
    private LuaTable metatable;

    public LuaTable() { this(null); }

    public LuaTable(LuaBudget budget) {
        this.budget = budget;
        if (budget != null) budget.chargeMemory(64L);
    }

    public void rawSet(LuaValue key, LuaValue value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (key instanceof Nil) throw new LuaException(LuaException.Reason.RUNTIME, "table index is nil");
        if (key instanceof Num n && Double.isNaN(n.value())) {
            throw new LuaException(LuaException.Reason.RUNTIME, "table index is NaN");
        }
        key = normalizeKey(key);
        if (value instanceof Nil) {
            entries.remove(key);
        } else {
            boolean isNew = !entries.containsKey(key);
            if (isNew && budget != null) budget.chargeMemory(BYTES_PER_ENTRY);
            entries.put(key, value);
        }
    }

    public void rawSet(String key, LuaValue value) {
        rawSet(LuaValue.of(key), value);
    }

    public void rawSet(int index, LuaValue value) {
        rawSet(LuaValue.of((long) index), value);
    }

    public LuaValue rawGet(LuaValue key) {
        Objects.requireNonNull(key);
        if (key instanceof Nil) return NIL;
        if (key instanceof Num n && Double.isNaN(n.value())) return NIL;
        return entries.getOrDefault(normalizeKey(key), NIL);
    }

    public LuaValue rawGet(String key) {
        return rawGet(LuaValue.of(key));
    }

    public LuaValue rawGet(int index) {
        return rawGet(LuaValue.of((long) index));
    }

    public int rawLen() {
        int hi = 1;
        while (rawGet(hi) != NIL) {
            int next = hi * 2;
            if (next < hi) break;
            hi = next;
        }
        int lo = hi / 2;
        if (lo > 0 && rawGet(lo) == NIL) lo = 0;
        while (lo + 1 < hi) {
            int mid = lo + (hi - lo) / 2;
            if (rawGet(mid) != NIL) lo = mid;
            else hi = mid;
        }
        return rawGet(1) == NIL ? 0 : lo;
    }

    public LuaValue[] nextPair(LuaValue key) {
        if (key == null || key instanceof Nil) {
            if (entries.isEmpty()) return new LuaValue[] {NIL};
            var first = entries.entrySet().iterator().next();
            return new LuaValue[] {first.getKey(), first.getValue()};
        }
        LuaValue norm = normalizeKey(key);
        Iterator<Map.Entry<LuaValue, LuaValue>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getKey().equals(norm)) {
                if (!it.hasNext()) return new LuaValue[] {NIL};
                var nxt = it.next();
                return new LuaValue[] {nxt.getKey(), nxt.getValue()};
            }
        }
        throw new LuaException(LuaException.Reason.RUNTIME, "invalid key to 'next'");
    }

    public List<Map.Entry<LuaValue, LuaValue>> snapshotEntries() {
        return List.copyOf(new ArrayList<>(entries.entrySet()));
    }

    public LuaTable metatable() { return metatable; }

    public void setMetatable(LuaTable metatable) { this.metatable = metatable; }

    public int size() { return entries.size(); }

    private static LuaValue normalizeKey(LuaValue key) {
        if (key instanceof Num n && n.value() == 0.0) return LuaValue.of(0L);
        return key;
    }

    @Override public String toString() { return "table: 0x" + Integer.toHexString(System.identityHashCode(this)); }
}
