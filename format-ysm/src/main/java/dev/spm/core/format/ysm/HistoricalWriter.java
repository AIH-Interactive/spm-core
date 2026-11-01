package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.CharBuffer;
import java.util.*;
import static dev.spm.core.format.ysm.HistoricalValue.*;

/** Field writer for the lossless typed wire tree, followed by independent schema re-admission. */
final class HistoricalWriter {
    private HistoricalWriter() { }
    static YsmWirePayload encode(int version, Struct root, DecodePolicy policy, ResourceBudget budget,
                                  CancellationToken token) throws IOException {
        Objects.requireNonNull(root); Objects.requireNonNull(policy); Objects.requireNonNull(budget); Objects.requireNonNull(token);
        token.check();
        if (version < 1 || version > 32) throw new IOException("Unsupported historical wire version");
        long limit = Math.min(256L << 20, Math.min(policy.maxInputBytes(), policy.maxExpandedBytes()));
        try (var output = new BoundedBytes(limit, budget, token)) {
            output.littleInt(version);
            new Visitor(output, policy, budget, token).value(root, 0);
            var owned = YsmArchiveCodec.reserve(budget, output.size());
            try {
                byte[] wire = output.copy();
                try (var validated = YsmHistoricalCodec.decode(wire, policy, budget, token)) {
                    if (!validated.root().equals(root)) throw new IOException("Tree encoding does not match the declared historical schema");
                }
                token.check();
                return new YsmWirePayload(version, wire, List.of(owned));
            } catch (IOException | RuntimeException | Error failed) { owned.close(); throw failed; }
        }
    }
    private static final class Visitor {
        final BoundedBytes output;
        final DecodePolicy policy;
        final ResourceBudget budget;
        final CancellationToken token;
        int nodes;
        Visitor(BoundedBytes output, DecodePolicy policy, ResourceBudget budget, CancellationToken token) {
            this.output = output; this.policy = policy; this.budget = budget; this.token = token;
        }
        @SuppressWarnings("try")
        void value(HistoricalValue value, int depth) throws IOException {
            token.check();
            if (++nodes > policy.maxObjects()) throw new DecodeLimitException(ImportErrorCode.OBJECT_LIMIT,"Historical writer aggregate node limit");
            boolean container = value instanceof Struct || value instanceof Sequence || value instanceof Mapping || value instanceof Presence;
            int nested = depth + (container ? 1 : 0);
            if (nested > Math.min(512, policy.maxDepth())) throw new IOException("Historical writer depth limit");
            if (value instanceof Struct record) {
                for (HistoricalValue field : record.fields().values()) value(field, nested);
            } else if (value instanceof Sequence sequence) {
                if (sequence.counted()) output.varint(sequence.values().size());
                for (HistoricalValue item : sequence.values()) value(item, nested);
            } else if (value instanceof Mapping mapping) {
                output.varint(mapping.entries().size());
                var keys = new HashSet<HistoricalValue>();
                for (Entry entry : mapping.entries()) {
                    if (!keys.add(entry.key())) throw new IOException("Duplicate historical map key");
                    value(entry.key(), nested); value(entry.value(), nested);
                }
            } else if (value instanceof Presence optional) {
                output.varint(optional.present() ? 1 : 0);
                if (optional.present()) value(optional.value(), nested);
            } else if (value instanceof Text text) writeText(text);
            else if (value instanceof Binary bytes) {
                try (var scratch = YsmArchiveCodec.reserve(budget, bytes.size())) {
                    output.varint(bytes.size()); output.write(bytes.value());
                }
            } else if (value instanceof Float32 number) output.littleInt(number.rawBits());
            else if (value instanceof Unsigned number) output.varint(number.bits());
            else if (value instanceof Bool flag) output.write(flag.value() ? 1 : 0);
            else throw new IOException("Unknown historical value type");
        }
        @SuppressWarnings("try")
        void writeText(Text text) throws IOException {
            if (text.value().length() > Math.min(16 << 20, policy.maxStringBytes())) throw new IOException("Historical writer string limit");
            long encodingCapacity = (long) text.value().length() * (text.hashEncoding() ? 1 : 3);
            try (var scratch = YsmArchiveCodec.reserve(budget, encodingCapacity)) {
                var encoder = (text.hashEncoding() ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8).newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                var encoded = encoder.encode(CharBuffer.wrap(text.value()));
                if (encoded.remaining() > Math.min(16 << 20, policy.maxStringBytes())) throw new IOException("Historical writer encoded string limit");
                output.varint(encoded.remaining());
                while (encoded.hasRemaining()) output.write(encoded.get());
            }
        }
    }
}
