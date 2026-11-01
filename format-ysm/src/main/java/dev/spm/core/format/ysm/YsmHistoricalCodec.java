package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static dev.spm.core.format.ysm.HistoricalValue.*;

/** Reads historical compiled wire 1–32 without importing a host schema or transcoding media. */
public final class YsmHistoricalCodec {
    private YsmHistoricalCodec() { }
    public static YsmWirePayload encode(YsmHistoricalModel model, DecodePolicy policy, ResourceBudget budget,
                                        CancellationToken token) throws IOException {
        Objects.requireNonNull(model);
        return encodeRoot(model.innerVersion(), model.root(), policy, budget, token);
    }
    public static YsmWirePayload encodeRoot(int version, HistoricalValue.Struct root, DecodePolicy policy,
                                            ResourceBudget budget, CancellationToken token) throws IOException {
        return HistoricalWriter.encode(version, root, policy, budget, token);
    }
    public static YsmHistoricalModel decode(byte[] wire, DecodePolicy policy, ResourceBudget budget,
                                            CancellationToken token) throws IOException {
        Objects.requireNonNull(wire); Objects.requireNonNull(policy);
        Objects.requireNonNull(budget); Objects.requireNonNull(token);
        token.check();
        if (wire.length < 4 || wire.length > Math.min(256L << 20, Math.min(policy.maxExpandedBytes(), policy.maxInputBytes()))) {
            throw new IOException("Historical wire input limit");
        }
        var reader = new Reader(wire, policy, budget, token);
        try {
            return reader.decode();
        } catch (BufferUnderflowException | IllegalArgumentException malformed) {
            throw new IOException("Invalid historical wire at byte " + reader.input.position(), malformed);
        } finally { reader.reservations.forEach(ResourceBudget.Reservation::close); }
    }

    @FunctionalInterface private interface Read { HistoricalValue get() throws IOException; }
    @FunctionalInterface private interface Fields { void fill(Record record) throws IOException; }
    private static final class Record {
        final Map<String, HistoricalValue> fields = new LinkedHashMap<>();
        Record add(String name, Read value) throws IOException { fields.put(name, value.get()); return this; }
    }
    private static final class Reader {
        final ByteBuffer input;
        final DecodePolicy policy;
        final ResourceBudget budget;
        final CancellationToken token;
        final List<ResourceBudget.Reservation> reservations = new ArrayList<>();
        /** Decoded bytes retained by this reader, accumulated against maxExpandedBytes. */
        long retained;
        int version, depth, nodes;

        Reader(byte[] wire, DecodePolicy policy, ResourceBudget budget, CancellationToken token) throws IOException {
            this.policy = policy; this.budget = budget; this.token = token;
            // Snapshot is temporary; node-owned values below are retained independently.
            reservations.add(YsmArchiveCodec.reserve(budget, wire.length));
            input = ByteBuffer.wrap(wire.clone()).order(ByteOrder.LITTLE_ENDIAN);
        }
        YsmHistoricalModel decode() throws IOException {
            version = input.getInt();
            require(version >= 1 && version <= 32, "Unknown historical inner version");
            Struct root = version < 19 ? legacy() : modern();
            Text identity = (Text) (version < 19 ? root.field("identity") : ((Struct) root.field("info")).field("hash"));
            require(identity.value().matches("[0-9a-fA-F]{32}"), "Invalid historical identity");
            require(!input.hasRemaining(), "Trailing historical wire bytes");
            // The source snapshot is no longer part of the returned tree.
            reservations.remove(0).close();
            var model = new YsmHistoricalModel(version, root, reservations);
            reservations.clear();
            return model;
        }
        void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
        /**
         * Charges one retained node against both budgets. {@code maxExpandedBytes} is a per-import
         * limit on the decoded tree, so it is accumulated here independently from the shared budget:
         * a decode that fits the shared budget must still respect a stricter host expansion limit.
         */
        void node(long bytes) throws IOException {
            token.check();
            if(++nodes>policy.maxObjects())throw new DecodeLimitException(ImportErrorCode.OBJECT_LIMIT,"Historical aggregate node limit");
            long charge = Math.addExact(32L, bytes);
            if(charge>policy.maxExpandedBytes()-retained)throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT,"Historical expanded-byte limit");
            reservations.add(YsmArchiveCodec.reserve(budget, charge));
            retained += charge;
        }
        HistoricalValue nested(Read read) throws IOException {
            if(++depth>policy.maxDepth())throw new DecodeLimitException(ImportErrorCode.DEPTH_LIMIT,"Historical nesting limit");
            node(0);
            try { return read.get(); } finally { depth--; }
        }
        Struct struct(Fields fields) throws IOException {
            return (Struct) nested(() -> { var r = new Record(); fields.fill(r); return new Struct(r.fields); });
        }
        long varint(int width) throws IOException {
            long bits = 0;
            int max = width == 32 ? 5 : 10;
            for (int i = 0; i < max; i++) {
                token.check();
                int value = input.get() & 255;
                require(i != max - 1 || (value & (width == 32 ? 0xf0 : 0xfe)) == 0, "Historical varint overflow");
                bits |= (long) (value & 127) << (7 * i);
                if ((value & 128) == 0) {
                    require(i == 0 || value != 0, "Noncanonical historical varint");
                    return bits;
                }
            }
            throw new IOException("Unterminated historical varint");
        }
        Unsigned uint32() throws IOException { node(0); return new Unsigned(32, varint(32)); }
        Unsigned uint64() throws IOException { node(0); return new Unsigned(64, varint(64)); }
        Unsigned enumeration(int first, int last) throws IOException {
            Unsigned value = uint32(); require(value.bits() >= first && value.bits() <= last, "Invalid historical enum"); return value;
        }
        Bool bool() throws IOException {
            int value = input.get() & 255; require(value <= 1, "Invalid historical boolean"); node(0); return new Bool(value != 0);
        }
        Float32 number() throws IOException { node(0); return new Float32(input.getInt()); }
        int count(int max) throws IOException {
            long size = varint(32);
            if(size>max||size>policy.maxCollectionEntries())throw new DecodeLimitException(ImportErrorCode.COLLECTION_LIMIT,"Historical collection limit");
            require(size<=input.remaining(),"Truncated historical collection");
            if(size>policy.maxObjects()-nodes)throw new DecodeLimitException(ImportErrorCode.OBJECT_LIMIT,"Historical aggregate node limit");
            return (int) size;
        }
        Binary binary(int limit) throws IOException {
            long length = varint(32);
            require(length <= limit && length <= input.remaining(), "Historical byte field limit");
            node(length);
            byte[] bytes = new byte[(int) length]; input.get(bytes); return new Binary(bytes);
        }
        Text text() throws IOException { return text(false); }
        Text hash() throws IOException { return text(true); }
        Text text(boolean hash) throws IOException {
            long size = varint(32);
            require(size <= Math.min(16 << 20, policy.maxStringBytes()) && size <= input.remaining(), "Historical string limit");
            node(size * 2);
            var slice = input.slice(); slice.limit((int) size); input.position(input.position() + (int) size);
            String value = hash ? StandardCharsets.ISO_8859_1.decode(slice).toString()
                    : StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString();
            return new Text(value, hash);
        }
        Sequence list(Read item) throws IOException { return list(item, 1_048_576); }
        Sequence list(Read item, int limit) throws IOException {
            return (Sequence) nested(() -> {
                int n = count(limit); var values = new ArrayList<HistoricalValue>();
                for (int i = 0; i < n; i++) values.add(item.get());
                return new Sequence(values);
            });
        }
        Mapping map(Read key, Read value) throws IOException {
            return (Mapping) nested(() -> {
                int n = count(1_048_576); var entries = new ArrayList<Entry>(); var keys = new HashSet<HistoricalValue>();
                for (int i = 0; i < n; i++) {
                    HistoricalValue k = key.get(); require(keys.add(k), "Duplicate historical map key");
                    entries.add(new Entry(k, value.get()));
                }
                return new Mapping(entries);
            });
        }
        Presence optional(Read value) throws IOException {
            return (Presence) nested(() -> {
                long tag = varint(32); require(tag <= 1, "Invalid historical optional tag");
                return new Presence(tag == 1, tag == 1 ? value.get() : null);
            });
        }
        Sequence strings() throws IOException { return list(this::text); }
        Mapping stringMap() throws IOException { return map(this::text, this::text); }
        Struct wrap(Read value) throws IOException { return struct(r -> r.add("hash", this::hash).add("value", value)); }
        Sequence vector() throws IOException { return (Sequence) nested(() -> new Sequence(List.of(number(), number(), number()), false)); }
        Struct quad() throws IOException {
            return struct(r -> {
                r.add("normal", this::vector);
                r.add("vertices", () -> nested(() -> {
                    var vertices = new ArrayList<HistoricalValue>();
                    for (int i = 0; i < 4; i++) vertices.add(struct(v -> v.add("position", this::vector)
                            .add("uv", () -> nested(() -> new Sequence(List.of(number(), number()), false)))));
                    return new Sequence(vertices, false);
                }));
            });
        }
        Struct cube() throws IOException {
            return struct(r -> r.add("quads", () -> list(this::quad, 6))
                    .add("zero_size", () -> nested(() -> new Sequence(List.of(bool(), bool(), bool()), false))));
        }
        Struct bone() throws IOException {
            return struct(r -> r.add("parent", this::text)
                    .add(version == 5 ? "quads" : "cubes", () -> version == 5 ? list(this::quad) : list(this::cube))
                    .add("name", this::text).add("dont_render", this::bool).add("hidden", this::bool)
                    .add("cubes_hidden", this::bool).add("hide_children", this::bool).add("reset", this::bool)
                    .add("pivot", this::vector).add("rotation", this::vector));
        }
        Struct extra() throws IOException {
            return struct(r -> r.add("name", this::text).add("tips", this::text).add("extra", this::strings)
                    .add("authors", this::strings).add("license", this::text).add("free", this::bool));
        }
        Struct geometry() throws IOException {
            return struct(r -> {
                r.add("bones", () -> list(this::bone, 65536));
                r.add("properties", () -> struct(p -> p.add("identifier", this::text)
                        .add("height", this::number).add("width", this::number)
                        .add("visible_bounds_height", this::number).add("visible_bounds_width", this::number)
                        .add("visible_bounds_offset", () -> list(this::number))
                        .add("height_scale", this::number).add("width_scale", this::number)
                        .add("extra_info", () -> optional(this::extra)).add("variables", this::stringMap)
                        .add("initialize", this::strings).add("pre_animation", this::strings)));
                if (version == 5) r.add("global_cube_count", this::uint32);
            });
        }
        Struct molang() throws IOException {
            return struct(r -> {
                Unsigned type = enumeration(0, 2); r.fields.put("type", type);
                if (type.bits() == 1) r.add("number", this::number);
                if (type.bits() == 2) r.add("source", this::text);
            });
        }
        Sequence molangVector() throws IOException { return (Sequence) nested(() -> new Sequence(List.of(molang(), molang(), molang()), false)); }
        Struct keyframe() throws IOException {
            return struct(r -> r.add("tick", this::number).add("easing", this::uint32)
                    .add("pre", this::molangVector).add("post", () -> optional(this::molangVector)));
        }
        Struct animation() throws IOException {
            return struct(r -> {
                r.add("name", this::text).add("length", this::number).add("loop", this::uint32);
                if (version >= 10) r.add("start_delay", () -> optional(this::molang))
                        .add("loop_delay", () -> optional(this::molang)).add("weight", () -> optional(this::molang))
                        .add("override_previous", () -> optional(this::bool));
                r.add("bones", () -> list(() -> struct(b -> b.add("name", this::text)
                        .add("rotation", () -> list(this::keyframe)).add("position", () -> list(this::keyframe))
                        .add("scale", () -> list(this::keyframe)))))
                        .add("instructions", () -> list(() -> struct(i -> i.add("data", this::strings).add("tick", this::number))));
                if (version >= 11) r.add("sounds", () -> list(() -> struct(s -> s.add("data", this::text).add("tick", this::number))));
            });
        }
        Struct animations() throws IOException { return struct(r -> r.add("animations", () -> list(this::animation))); }
        Struct controllerState() throws IOException {
            return struct(r -> {
                Read named = () -> struct(a -> a.add("name", this::text).add("condition", this::text));
                r.add("animations", () -> list(named)).add("transitions", () -> list(named))
                        .add("entry", this::strings).add("exit", this::strings);
                if (version < 15) r.add("length", this::number);
                else {
                    Presence length = optional(this::number); r.fields.put("length", length);
                    if (!length.present()) r.add("points", () -> map(this::number, this::number));
                }
                r.add("blend_via_shortest_path", this::bool);
                if (version >= 28) r.add("sounds", this::strings);
            });
        }
        Struct controllers() throws IOException {
            return struct(r -> r.add("controllers", () -> map(this::text, () -> struct(c -> c.add("initial", this::text)
                    .add("states", () -> map(this::text, this::controllerState))))));
        }
        Struct image(boolean legacy) throws IOException {
            return struct(r -> {
                Binary bytes = binary(66 << 20); Unsigned width = uint32(), height = uint32();
                r.fields.put("bytes", bytes); r.fields.put("width", width); r.fields.put("height", height);
                long encoding = 1;
                if (!legacy && version >= 23) {
                    Unsigned format = enumeration(1, 5); encoding = format.bits(); r.fields.put("encoding", format);
                    Unsigned frames = uint32(); require(frames.bits() == 1, "Historical image frame count"); r.fields.put("frames", frames);
                }
                require(width.bits() >= 1 && width.bits() <= 4096 && height.bits() >= 1 && height.bits() <= 4096, "Historical image dimensions");
                if (encoding == 1) require(bytes.size() == width.bits() * height.bits() * 4, "Historical RGBA byte count");
            });
        }
        Struct texture() throws IOException {
            return struct(r -> r.add("uv", () -> wrap(() -> image(false)))
                    .add("pbr", () -> map(this::uint32, () -> wrap(() -> image(false)))));
        }
        Binary sound() throws IOException { return binary(4 << 20); }
        Struct metadata() throws IOException {
            return struct(r -> r.add("name", this::text).add("tips", this::text).add("license", this::text)
                    .add("description", this::text).add("authors", () -> list(() -> struct(a -> a.add("name", this::text)
                            .add("role", this::text).add("contact", this::stringMap).add("comment", this::text))))
                    .add("links", this::stringMap));
        }
        Struct form() throws IOException {
            return struct(r -> r.add("type", this::text).add("title", this::text).add("description", this::text)
                    .add("value", this::text).add("step", this::number).add("min", this::number).add("max", this::number)
                    .add("labels", this::stringMap));
        }
        Struct properties() throws IOException {
            return struct(r -> {
                r.add("height_scale", this::number).add("width_scale", this::number).add("extra", this::stringMap);
                if (version >= 12) r.add("buttons", () -> list(() -> struct(b -> b.add("id", this::text).add("name", this::text)
                                .add("sound", this::text).add("forms", () -> list(this::form)))))
                        .add("classify", () -> list(() -> struct(c -> c.add("id", this::text).add("extra", this::stringMap))));
                r.add("default_texture", this::text).add("preview", this::text).add("free", this::bool);
                if (version >= 8) r.add("layers", this::bool);
                if (version >= 13) r.add("culling", this::bool);
                if (version >= 14) r.add("no_rotation", this::bool);
                if (version >= 17) r.add("no_lighting", this::bool);
                if (version >= 32) r.add("merge", this::bool);
                if (version >= 20) r.add("gui_foreground", this::text).add("gui_background", this::text);
            });
        }
        Struct export() throws IOException {
            return struct(r -> r.add("random", this::text).add("timestamp", this::uint64).add("extra", this::text));
        }
        Struct common() throws IOException {
            return struct(r -> r.add("sounds", () -> map(this::text, () -> wrap(this::sound)))
                    .add("functions", () -> map(this::text, () -> wrap(this::text)))
                    .add("languages", () -> map(this::text, () -> wrap(this::stringMap))));
        }
        Struct player() throws IOException {
            return struct(r -> r.add("animations", () -> map(this::uint32, () -> wrap(this::animations)))
                    .add("controllers", () -> map(this::text, () -> wrap(this::controllers)))
                    .add("textures", () -> map(this::text, this::texture))
                    .add("geos", () -> map(this::uint32, () -> wrap(this::geometry))));
        }
        Struct replacement() throws IOException {
            return struct(r -> {
                r.add("animation", () -> optional(() -> wrap(this::animations)));
                if (version >= 22) r.add("controller", () -> optional(() -> wrap(this::controllers)));
                r.add("texture", this::texture).add("geo", () -> wrap(this::geometry));
                if (version >= 27) r.add("match", this::strings);
            });
        }
        Struct info() throws IOException {
            return struct(r -> {
                r.add("hash", this::hash).add("metadata", () -> optional(this::metadata)).add("properties", this::properties)
                        .add("avatars", () -> map(this::text, () -> image(false)));
                if (version >= 20) r.add("gui", () -> map(() -> {
                    Text key = text(); require(Set.of("gui_foreground", "gui_background", "thumb-button", "thumb-icon").contains(key.value()), "Unknown GUI image role");
                    return key;
                }, () -> image(false)));
                if (version >= 27) r.add("origin", this::uint32);
            });
        }
        Struct modern() throws IOException {
            return struct(r -> {
                r.add("common", this::common);
                if (version >= 27) r.add("vehicles", () -> list(this::replacement)).add("projectiles", () -> list(this::replacement));
                else {
                    r.add("projectiles", () -> map(this::text, this::replacement));
                    if (version >= 21) r.add("vehicles", () -> map(this::text, this::replacement));
                }
                r.add("player", () -> optional(this::player)).add("info", this::info)
                        .add("export", () -> optional(this::export)).add("order_info", this::text);
            });
        }
        Struct legacy() throws IOException {
            return struct(r -> {
                r.add("model_types", () -> list(() -> enumeration(1, 3)))
                        .add("geos", () -> map(() -> enumeration(1, 3), () -> optional(this::geometry)))
                        .add("animations", () -> map(() -> enumeration(1, 11), () -> optional(this::animations)));
                if (version >= 10) r.add("controllers", () -> list(() -> optional(this::controllers)))
                        .add("controller_hashes", () -> map(this::text, this::hash));
                r.add("textures", () -> map(this::text, () -> {
                    if (version < 3) return optional(() -> image(true));
                    return struct(t -> t.add("uv", () -> image(true)).add("pbr", () -> map(this::uint32, () -> image(true))));
                }));
                if (version >= 11) r.add("sounds", () -> map(this::text, this::sound)).add("sound_hashes", () -> map(this::text, this::hash));
                if (version >= 16) r.add("functions", this::stringMap).add("function_hashes", () -> map(this::text, this::hash));
                if (version >= 18) r.add("languages", () -> map(this::text, this::stringMap)).add("language_hashes", () -> map(this::text, this::hash));
                if (version >= 4) r.add("avatars", () -> map(this::text, () -> image(true)));
                r.add("geo_hashes", () -> map(() -> enumeration(1, 3), this::hash))
                        .add("animation_hashes", () -> map(() -> enumeration(1, 11), this::hash))
                        .add("texture_hashes", () -> map(this::text, () -> struct(t -> {
                            t.add("uv", this::hash);
                            if (version >= 3) t.add("pbr", () -> map(this::uint32, this::hash));
                        }))).add("identity", this::hash);
                if (version >= 2) r.add("info", () -> optional(() -> struct(i -> i.add("metadata", () -> optional(this::metadata)).add("properties", this::properties))));
                if (version >= 6) r.add("export", () -> optional(this::export));
            });
        }
    }
}
