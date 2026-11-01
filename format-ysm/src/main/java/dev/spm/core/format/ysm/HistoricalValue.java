package dev.spm.core.format.ysm;

import java.util.*;

/** Immutable, archival historical values. Field presence and raw float bits are never normalized. */
public sealed interface HistoricalValue {
    /** hashEncoding retains a historical byte-string's ISO-8859-1 mapping; other strings use strict UTF-8. */
    record Text(String value, boolean hashEncoding) implements HistoricalValue {
        public Text(String value) { this(value, false); }
        public Text { Objects.requireNonNull(value); }
    }
    /** Width distinguishes wire uint32 and uint64; a negative long stores uint64's same bit pattern. */
    record Unsigned(int width, long bits) implements HistoricalValue {
        public Unsigned {
            if (width != 32 && width != 64 || width == 32 && (bits < 0 || bits > 0xffffffffL)) {
                throw new IllegalArgumentException("Invalid unsigned historical integer");
            }
        }
    }
    record Float32(int rawBits) implements HistoricalValue {
        public float value() { return Float.intBitsToFloat(rawBits); }
    }
    record Bool(boolean value) implements HistoricalValue { }
    record Binary(byte[] value) implements HistoricalValue {
        public Binary { value = value.clone(); }
        public int size() { return value.length; }
        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) { return other instanceof Binary binary && Arrays.equals(value, binary.value); }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
    /** counted adds a varint length; fixed schema vectors/quad vertices have no length prefix. */
    record Sequence(List<HistoricalValue> values, boolean counted) implements HistoricalValue {
        public Sequence(List<HistoricalValue> values) { this(values, true); }
        public Sequence { values = List.copyOf(values); }
    }
    record Entry(HistoricalValue key, HistoricalValue value) {
        public Entry { Objects.requireNonNull(key); Objects.requireNonNull(value); }
    }
    record Mapping(List<Entry> entries) implements HistoricalValue {
        public Mapping { entries = List.copyOf(entries); }
    }
    record Struct(Map<String, HistoricalValue> fields) implements HistoricalValue {
        public Struct { fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
        public HistoricalValue field(String name) {
            HistoricalValue value = fields.get(name);
            if (value == null) throw new IllegalArgumentException("Absent schema field: " + name);
            return value;
        }
    }
    record Presence(boolean present, HistoricalValue value) implements HistoricalValue {
        public Presence {
            if (present != (value != null)) throw new IllegalArgumentException("Invalid wire optional");
        }
    }
}
