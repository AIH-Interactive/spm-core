package dev.spm.core.scene;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded script values; absence is distinct from numeric zero. */
public sealed interface MolangValue permits MolangValue.NumberValue, MolangValue.BooleanValue, MolangValue.TextValue,
        MolangValue.StructValue, MolangValue.Missing {
    static NumberValue number(double value) { return new NumberValue(value); }
    static BooleanValue bool(boolean value) { return new BooleanValue(value); }
    static TextValue text(String value) { return new TextValue(value); }
    static StructValue struct(Map<String, MolangValue> fields) { return new StructValue(fields); }
    static Missing missing() { return Missing.INSTANCE; }

    /** Numeric coercion at a numeric consumer; text and structures coerce to one. */
    default double number() {
        if (this instanceof NumberValue value) return value.value();
        if (this instanceof BooleanValue value) return value.value() ? 1 : 0;
        return this == Missing.INSTANCE ? 0 : 1;
    }

    default boolean truth() {
        if (this instanceof NumberValue value) return value.value() != 0;
        if (this instanceof BooleanValue value) return value.value();
        return this != Missing.INSTANCE;
    }

    /** Resolves a case-insensitive dotted property path without creating mutable state. */
    default MolangValue field(String path) {
        Objects.requireNonNull(path);
        MolangValue current = this;
        String normalized = path.toLowerCase(Locale.ROOT);
        int start = 0;
        while (true) {
            if (!(current instanceof StructValue structure)) return Missing.INSTANCE;
            int end = normalized.indexOf('.', start);
            String name = end < 0 ? normalized.substring(start) : normalized.substring(start, end);
            current = structure.fields().getOrDefault(name, Missing.INSTANCE);
            if (end < 0) return current;
            start = end + 1;
        }
    }

    record NumberValue(double value) implements MolangValue {
        public NumberValue {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Molang numbers must be finite");
        }
    }

    record BooleanValue(boolean value) implements MolangValue { }

    record TextValue(String value) implements MolangValue {
        public TextValue {
            Objects.requireNonNull(value);
            if (value.length() > 65_536) throw new IllegalArgumentException("Molang text exceeds 65536 characters");
        }
    }

    record StructValue(Map<String, MolangValue> fields) implements MolangValue {
        public StructValue {
            Objects.requireNonNull(fields);
            if (fields.size() > 4096) throw new IllegalArgumentException("Molang structure exceeds 4096 fields");
            Map<String, MolangValue> normalized = new HashMap<>();
            fields.forEach((name, value) -> {
                String key = Objects.requireNonNull(name).toLowerCase(Locale.ROOT);
                if (normalized.putIfAbsent(key, Objects.requireNonNull(value)) != null) {
                    throw new IllegalArgumentException("Duplicate case-insensitive Molang field: " + name);
                }
            });
            validateStructure(normalized);
            fields = Map.copyOf(normalized);
        }
    }

    enum Missing implements MolangValue { INSTANCE }

    private static void validateStructure(Map<String, MolangValue> root) {
        record Pending(Map<String, MolangValue> fields, int depth) { }
        ArrayDeque<Pending> pending = new ArrayDeque<>();
        pending.push(new Pending(root, 1));
        int totalFields = 0;
        while (!pending.isEmpty()) {
            Pending current = pending.pop();
            if (current.depth() > 32) throw new IllegalArgumentException("Molang structure exceeds depth 32");
            totalFields += current.fields().size();
            if (totalFields > 4096) throw new IllegalArgumentException("Molang structure exceeds 4096 total fields");
            for (MolangValue value : current.fields().values()) {
                if (value instanceof StructValue structure) {
                    pending.push(new Pending(structure.fields(), current.depth() + 1));
                }
            }
        }
    }
}
