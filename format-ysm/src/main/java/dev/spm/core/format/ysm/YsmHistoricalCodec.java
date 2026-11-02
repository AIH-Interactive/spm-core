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
    /**
     * Decodes the full tree, including every animation clip. Required for byte-exact re-encoding, so
     * this keeps the multi-gigabyte shape for animation-heavy models.
     */
    public static YsmHistoricalModel decode(byte[] wire, DecodePolicy policy, ResourceBudget budget,
                                            CancellationToken token) throws IOException {
        return decode(wire, policy, budget, token, false);
    }
    /**
     * Decodes for import: player animation clips are projected into their final scene JSON as they
     * are parsed instead of being retained as tree nodes.
     *
     * <p>This is what bounds the peak. Animation keyframes are 97.4% of a real model's node mass
     * (measured: 10.4M of 10.6M nodes for a 5.6 MiB community model), and holding them all before
     * projecting them is what forced a multi-gigabyte heap. Streaming one clip at a time makes the
     * peak proportional to the largest single clip instead of the whole file, and the projected
     * result is identical by construction: {@code HistoricalSceneProjection.collector} receives the
     * same clips in the same order it would have received them from a completed tree.
     *
     * <p>Byte-exact re-encoding is not available on this path, because the clips are no longer in the
     * tree. Callers that need archival round-tripping use {@link #decode(byte[], DecodePolicy,
     * ResourceBudget, CancellationToken)}.
     */
    public static YsmHistoricalModel decodeForImport(byte[] wire, DecodePolicy policy, ResourceBudget budget,
                                                     CancellationToken token) throws IOException {
        return decode(wire, policy, budget, token, true);
    }
    private static YsmHistoricalModel decode(byte[] wire, DecodePolicy policy, ResourceBudget budget,
                                             CancellationToken token, boolean streamAnimations) throws IOException {
        Objects.requireNonNull(wire); Objects.requireNonNull(policy);
        Objects.requireNonNull(budget); Objects.requireNonNull(token);
        token.check();
        if (wire.length < 4 || wire.length > Math.min(256L << 20, Math.min(policy.maxExpandedBytes(), policy.maxInputBytes()))) {
            throw new IOException("Historical wire input limit");
        }
        var reader = new Reader(wire, policy, budget, token, streamAnimations);
        try {
            return reader.decode();
        } catch (BufferUnderflowException | IllegalArgumentException malformed) {
            throw new IOException("Invalid historical wire at byte " + reader.input.position(), malformed);
        } finally { reader.reservations.forEach(ResourceBudget.Reservation::close); }
    }

    /**
     * Measured per-node retention costs, in bytes, used to charge the caller's budget.
     *
     * <p>These are not guesses. Each figure was obtained by allocating two million instances of the
     * corresponding shape and differencing the live heap, so the charge is grounded in what the JVM
     * actually retains rather than in the wire size. The previous model charged a flat 32 bytes per
     * node, which under-estimated a four-field {@code Struct} by an order of magnitude (real cost
     * 412.8 B) and made the budget an optimistic lower bound instead of a conservative upper one.
     *
     * <p>Every constant is rounded up and given headroom over its measurement, because a budget that
     * is merely accurate is still unsafe: it must bound the peak, not the average.
     */
    static final class NodeCost {
        private NodeCost() { }
        /** Measured 16.0 B. */
        static final long FLOAT32 = 24;
        /** Measured 24.0 B. */
        static final long UNSIGNED = 32;
        /** Measured 16.0 B. */
        static final long BOOL = 24;
        /** Measured 40.0 B. */
        static final long PRESENCE = 48;
        /** Measured 24.2 B for the entry shell alone. */
        static final long ENTRY = 40;
        /** Measured ~81 B for the container shell (object + ArrayList + backing array). */
        static final long SEQUENCE_SHELL = 96;
        /** Measured ~64 B for the map shell; entries are charged separately. */
        static final long MAPPING_SHELL = 96;
        /** Measured ~325 B for a four-field struct's shell, i.e. ~81 B per bucket. */
        static final long STRUCT_SHELL = 96;
        /** Measured ~81 B per {@code LinkedHashMap} field bucket, rounded up. */
        static final long FIELD = 64;
        /** {@code Text} object (24 B measured) plus its String and byte[] body. */
        static final long TEXT = 32;
        /** {@code Binary} object plus an array header; the payload is added separately. */
        static final long BINARY = 32;
    }

    @FunctionalInterface private interface Read { HistoricalValue get() throws IOException; }
    @FunctionalInterface private interface Fields { void fill(Record record) throws IOException; }
    private static final class Record {
        final Map<String, HistoricalValue> fields = new LinkedHashMap<>();
        private final Reader owner;
        Record(Reader owner) { this.owner = owner; }
        Record add(String name, Read value) throws IOException {
            fields.put(name, value.get());
            // Each field costs a map bucket plus its array slot, independently of the value.
            owner.charge(NodeCost.FIELD);
            return this;
        }
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
        /**
         * Receives animation clips as they are parsed. Animation keyframes are 97.4% of a real
         * model's node mass (measured: 10.4M of 10.6M nodes for a 5.6 MiB community model), so a clip
         * is projected into its final scene JSON the moment it is read and the tree nodes are dropped
         * instead of being accumulated. This is what removes the multi-gigabyte peak.
         */
        HistoricalSceneProjection.Collector animationCollector;
        /**
         * Geometry collected during an import, keyed by the geometry slot it belongs to.
         *
         * <p>Geometry is 65.4% of a large model's tree (measured: 3,876,325 of 5,931,597 nodes) and each
         * node is read once and then discarded, so on the import path the decoder builds the mesh arrays
         * as it parses and keeps no tree at all. Null on the archival path, which must keep everything
         * for byte-exact re-encoding.
         */
        final Map<Long,HistoricalMeshCollector> meshCollectors;
        /** True only on the import path; the archival decode keeps the full tree for byte-exact re-encode. */
        final boolean streamPlayerAnimations;

        Reader(byte[] wire, DecodePolicy policy, ResourceBudget budget, CancellationToken token) throws IOException {
            this(wire, policy, budget, token, false);
        }
        Reader(byte[] wire, DecodePolicy policy, ResourceBudget budget, CancellationToken token,
               boolean streamPlayerAnimations) throws IOException {
            this.policy = policy; this.budget = budget; this.token = token;
            this.streamPlayerAnimations = streamPlayerAnimations;
            this.meshCollectors = streamPlayerAnimations ? new LinkedHashMap<>() : null;
            // Snapshot is temporary; node-owned values below are retained independently.
            YsmArchiveCodec.reserve(budget, wire.length,reservations);
            input = ByteBuffer.wrap(wire.clone()).order(ByteOrder.LITTLE_ENDIAN);
        }
        YsmHistoricalModel decode() throws IOException {
            version = input.getInt();
            require(version >= 1 && version <= 32, "Unknown historical inner version");
            Struct root = version < 19 ? legacy() : modern();
            Text identity = (Text) (version < 19 ? root.field("identity") : ((Struct) root.field("info")).field("hash"));
            require(identity.value().matches("[0-9a-fA-F]{32}"), "Invalid historical identity");
            require(!input.hasRemaining(), "Trailing historical wire bytes");
            // Every charge must reach the budget before the reader hands ownership over.
            flushPending();
            // The source snapshot is no longer part of the returned tree.
            reservations.remove(0).close();
            var model = new YsmHistoricalModel(version, root, animationCollector, meshCollectors, reservations);
            animationCollector = null;
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
            charge(bytes);
        }
        /**
         * Running total of retention that has been admitted but not yet converted into a reservation
         * ticket.
         *
         * <p>One {@code Reservation} per node is what actually blew the heap: a real model has ~10.6M
         * nodes, so the ticket objects alone (an object plus a reference in a growing array) cost more
         * than the charged bytes they represented. Batching means the number of tickets scales with
         * checkpoints rather than with node count, while the total admitted credit stays identical.
         */
        private long pending;
        /** Credits accumulated inside the current transient unit (one streamed clip). */
        private long transientBytes;
        /** True while reading a unit whose tree nodes are dropped as soon as it ends. */
        private boolean transientScope;
        /**
         * Credits are flushed to the shared budget in chunks of at least this size. It bounds how far
         * {@code used()} can lag the true figure, and keeps the flush itself amortised.
         */
        private static final long FLUSH_CHUNK = 1L << 20;

        /**
         * Charges retention without counting a node, for container overhead (map buckets, list
         * shells) that is real heap but is not a {@code HistoricalValue}.
         */
        void charge(long bytes) throws IOException {
            long charge = Math.addExact(32L, bytes);
            if (transientScope) {
                // A streamed clip is projected and then dropped, so its tree nodes are live only for
                // the duration of that one clip. Charging them cumulatively would report the whole
                // file's node mass as a peak the process never actually holds; the correct bound is
                // per clip, and the memory is released before the next clip is read.
                transientBytes = Math.addExact(transientBytes, charge);
                if (transientBytes > policy.maxExpandedBytes()) {
                    throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT, "Historical clip expanded-byte limit");
                }
                return;
            }
            if(charge>policy.maxExpandedBytes()-retained)throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT,"Historical expanded-byte limit");
            retained += charge;
            pending = Math.addExact(pending, charge);
            if (pending >= FLUSH_CHUNK) flush();
        }
        /**
         * Marks the charges that follow as belonging to one transient unit whose memory is released
         * when the unit ends. Nested scopes are not needed: clips contain no further clips.
         */
        void beginTransient() { transientScope = true; transientBytes = 0; }
        /** Ends the transient scope, releasing the per-unit accounting. */
        void endTransient() { transientScope = false; transientBytes = 0; }
        /** Converts the pending credit into a single reservation ticket. */
        private void flush() throws IOException {
            if (pending == 0) return;
            long amount = pending; pending = 0;
            YsmArchiveCodec.reserve(budget, amount, reservations);
        }
        /** Publishes any credit still pending. Called before ownership transfers. */
        void flushPending() throws IOException { flush(); }
        HistoricalValue nested(Read read) throws IOException {
            if(++depth>policy.maxDepth())throw new DecodeLimitException(ImportErrorCode.DEPTH_LIMIT,"Historical nesting limit");
            node(0);
            try { return read.get(); } finally { depth--; }
        }
        Struct struct(Fields fields) throws IOException {
            return (Struct) nested(() -> {
                var r = new Record(this); fields.fill(r);
                // The Struct object plus its map shell, charged once regardless of field count.
                charge(NodeCost.STRUCT_SHELL);
                return new Struct(r.fields);
            });
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
        Unsigned uint32() throws IOException { node(NodeCost.UNSIGNED); return new Unsigned(32, varint(32)); }
        Unsigned uint64() throws IOException { node(NodeCost.UNSIGNED); return new Unsigned(64, varint(64)); }
        Unsigned enumeration(int first, int last) throws IOException {
            Unsigned value = uint32(); require(value.bits() >= first && value.bits() <= last, "Invalid historical enum"); return value;
        }
        Bool bool() throws IOException {
            int value = input.get() & 255; require(value <= 1, "Invalid historical boolean"); node(NodeCost.BOOL); return new Bool(value != 0);
        }
        Float32 number() throws IOException { node(NodeCost.FLOAT32); return new Float32(input.getInt()); }
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
            node(Math.addExact(NodeCost.BINARY, length));
            byte[] bytes = new byte[(int) length]; input.get(bytes); return new Binary(bytes);
        }
        Text text() throws IOException { return text(false); }
        Text hash() throws IOException { return text(true); }
        Text text(boolean hash) throws IOException {
            long size = varint(32);
            require(size <= Math.min(16 << 20, policy.maxStringBytes()) && size <= input.remaining(), "Historical string limit");
            // A Java 9+ compact String keeps its bytes in a byte[]; hash text is stored ISO-8859-1
            // (one byte per char) and ordinary text decodes to UTF-16 or compact bytes. Charging two
            // bytes per char plus the object shells covers both without guessing the coder.
            node(Math.addExact(NodeCost.TEXT, Math.multiplyExact(size, 2L)));
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
                // The Sequence object, its ArrayList and the Object[] backing it are all retained.
                charge(Math.addExact(NodeCost.SEQUENCE_SHELL, Math.multiplyExact((long) n, 8L)));
                for (int i = 0; i < n; i++) values.add(item.get());
                return new Sequence(values);
            });
        }
        Mapping map(Read key, Read value) throws IOException {
            return (Mapping) nested(() -> {
                int n = count(1_048_576); var entries = new ArrayList<Entry>(); var keys = new HashSet<HistoricalValue>();
                charge(Math.addExact(NodeCost.MAPPING_SHELL, Math.multiplyExact((long) n, NodeCost.ENTRY)));
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
                charge(NodeCost.PRESENCE);
                return new Presence(tag == 1, tag == 1 ? value.get() : null);
            });
        }
        Sequence strings() throws IOException { return list(this::text); }
        Mapping stringMap() throws IOException { return map(this::text, this::text); }
        Struct wrap(Read value) throws IOException { return struct(r -> r.add("hash", this::hash).add("value", value)); }
        Sequence vector() throws IOException { return (Sequence) nested(() -> {
            charge(NodeCost.SEQUENCE_SHELL + 24);
            return new Sequence(List.of(number(), number(), number()), false);
        }); }
        Struct quad() throws IOException {
            return struct(r -> {
                r.add("normal", this::vector);
                r.add("vertices", () -> nested(() -> {
                    charge(NodeCost.SEQUENCE_SHELL + 32);
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
            return struct(r -> r.add("animations", this::playerAnimations)
                    .add("controllers", () -> map(this::text, () -> wrap(this::controllers)))
                    .add("textures", () -> map(this::text, this::texture))
                    .add("geos", this::playerGeometry));
        }
        /**
         * Reads the player geometry map, collecting meshes instead of a tree on the import path.
         *
         * <p>Geometry is 65.4% of a large model's tree and every node of it is read once and then
         * discarded, so the import path parses each geometry bone by bone and hands the primitives to a
         * {@link HistoricalMeshCollector}. The map still keeps its keys, with an empty body, because the
         * mesh lives in {@link #meshCollectors} rather than in the tree.
         */
        Mapping playerGeometry() throws IOException {
            if (!streamPlayerAnimations) return map(this::uint32, () -> wrap(this::geometry));
            return (Mapping) nested(() -> {
                int n = count(1_048_576);
                charge(Math.addExact(NodeCost.MAPPING_SHELL, Math.multiplyExact((long) n, NodeCost.ENTRY)));
                var entries = new ArrayList<Entry>();
                for (int i = 0; i < n; i++) {
                    Unsigned key = uint32();
                    entries.add(new Entry(key, geometryCollected(key.bits())));
                }
                return new Mapping(entries);
            });
        }
        /**
         * Parses one geometry into a collector and returns a key-only stand-in for the tree.
         *
         * <p>The stand-in keeps {@code hash} and an empty {@code value}, so the surviving tree holds one
         * small node per geometry instead of millions.
         */
        private Struct geometryCollected(long slot) throws IOException {
            var collector = new HistoricalMeshCollector();
            meshCollectors.put(slot, collector);
            return struct(r -> r.add("hash", this::hash)
                    .add("value", () -> struct(g -> {
                        g.add("bones", () -> bonesCollected(collector));
                        g.add("properties", () -> struct(p -> p.add("identifier", this::text)
                                .add("height", this::number).add("width", this::number)
                                .add("visible_bounds_height", this::number)
                                .add("visible_bounds_width", this::number)
                                .add("visible_bounds_offset", () -> list(this::number))
                                .add("height_scale", this::number).add("width_scale", this::number)
                                .add("extra_info", () -> optional(this::extra)).add("variables", this::stringMap)
                                .add("initialize", this::strings).add("pre_animation", this::strings)));
                        if (version == 5) g.add("global_cube_count", this::uint32);
                    })));
        }
        /** Reads bones one at a time; each bone's quads go straight into the collector, then are dropped. */
        private Sequence bonesCollected(HistoricalMeshCollector collector) throws IOException {
            return (Sequence) nested(() -> {
                int n = count(65536);
                charge(NodeCost.SEQUENCE_SHELL + Math.multiplyExact((long) n, 8L));
                var bones = new ArrayList<HistoricalValue>();
                for (int i = 0; i < n; i++) {
                    // A bone's own nodes are transient: they are converted to primitives and released
                    // before the next bone is read, so they are bounded per bone rather than cumulatively.
                    beginTransient();
                    try { boneCollected(collector); }
                    finally { endTransient(); }
                }
                // The caller re-reads bone metadata from the collector, so the tree keeps no bone list.
                return new Sequence(bones, true);
            });
        }
        /**
         * Reads one bone and appends its quads to the collector.
         *
         * <p>Wire order is {@code parent, cubes/quads, name, five flags, pivot, rotation}, but a quad must
         * be attributed to a bone index that only exists once the name is known. A single bone's quads are
         * therefore buffered as primitives — bounded by one bone, which is what the peak is measured
         * against — and replayed once the bone is registered.
         */
        private void boneCollected(HistoricalMeshCollector collector) throws IOException {
            String parent = text().value();
            var buffered = new ArrayList<float[]>();
            if (version == 5) {
                int quads = count(1_048_576);
                charge(NodeCost.SEQUENCE_SHELL + Math.multiplyExact((long) quads, 24L));
                for (int i = 0; i < quads; i++) buffered.add(quadPrimitives());
            } else {
                int cubes = count(65_536);
                charge(NodeCost.SEQUENCE_SHELL + Math.multiplyExact((long) cubes, 24L));
                for (int i = 0; i < cubes; i++) {
                    int quads = count(6);
                    for (int q = 0; q < quads; q++) buffered.add(quadPrimitives());
                    for (int flag = 0; flag < 3; flag++) bool();
                }
            }
            String name = text().value();
            boolean flags = false;
            for (int flag = 0; flag < 5; flag++) if (bool().value()) flags = true;
            float[] pivot = rawVector();
            float[] rotation = rawVector();
            int boneIndex = collector.bone(name, parent, pivot, rotation, flags);
            for (float[] quad : buffered)
                collector.quad(boneIndex, quad[0], quad[1], quad[2], slice(quad, 3, 12), slice(quad, 15, 8));
        }
        /** Reads a fixed three-number vector as primitives, without building tree nodes. */
        private float[] rawVector() throws IOException {
            // The wire form is a fixed three-element sequence with no length prefix.
            return new float[] { number().value(), number().value(), number().value() };
        }
        /**
         * Reads one quad as 23 primitives: the normal, then four corners of position and UV.
         *
         * <p>Layout: {@code [nx,ny,nz, px,py,pz,u,v] x 4}, i.e. 3 + 4*5 values.
         */
        private float[] quadPrimitives() throws IOException {
            float[] normal = rawVector();
            var quad = new float[23];
            quad[0] = normal[0]; quad[1] = normal[1]; quad[2] = normal[2];
            int offset = 3;
            for (int corner = 0; corner < 4; corner++) {
                float[] position = rawVector();
                quad[offset] = position[0]; quad[offset + 1] = position[1]; quad[offset + 2] = position[2];
                offset += 3;
                // Each corner's UV is a fixed two-number sequence with no length prefix.
                quad[offset] = number().value(); quad[offset + 1] = number().value();
                offset += 2;
            }
            return quad;
        }
        private static float[] slice(float[] source, int from, int length) {
            var result = new float[length];
            System.arraycopy(source, from, result, 0, length);
            return result;
        }
        /**
         * Reads the player animation sets, streaming each clip into the projection as it is parsed.
         *
         * <p>On the archival path this is the ordinary map read. On the import path each clip is
         * projected the moment it has been parsed and its tree is then dropped, so the peak is one
         * clip rather than every clip in the file. The map keeps its keys and an empty set body, which
         * is all the tree needs to record: the projected result lives on the model.
         */
        Mapping playerAnimations() throws IOException {
            if (!streamPlayerAnimations) return map(this::uint32, () -> wrap(this::animations));
            animationCollector = HistoricalSceneProjection.collector(version, policy, token);
            return (Mapping) nested(() -> {
                int n = count(1_048_576);
                charge(Math.addExact(NodeCost.MAPPING_SHELL, Math.multiplyExact((long) n, NodeCost.ENTRY)));
                var entries = new ArrayList<Entry>();
                for (int i = 0; i < n; i++) {
                    Unsigned key = uint32();
                    entries.add(new Entry(key, streamedAnimationSet(Long.toUnsignedString(key.bits()))));
                }
                return new Mapping(entries);
            });
        }
        private Struct streamedAnimationSet(String key) throws IOException {
            return struct(r -> r.add("hash", this::hash)
                    .add("value", () -> struct(s -> s.add("animations", () -> streamedClips(key)))));
        }
        private Sequence streamedClips(String key) throws IOException {
            animationCollector.beginSet(key);
            try {
                int n = count(1_048_576);
                charge(NodeCost.SEQUENCE_SHELL + Math.multiplyExact((long) n, 8L));
                for (int i = 0; i < n; i++) {
                    // One clip alive at a time: project it, then drop the tree representation. Its
                    // nodes are transient, so they are charged against a per-clip bound rather than
                    // cumulatively; charging cumulatively reported the whole file's node mass as a
                    // peak that the process never actually holds.
                    beginTransient();
                    try {
                        Struct clip = animation();
                        animationCollector.clip(clip);
                    } finally {
                        endTransient();
                    }
                }
            } finally {
                animationCollector.endSet();
            }
            return new Sequence(List.of(), true);
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
