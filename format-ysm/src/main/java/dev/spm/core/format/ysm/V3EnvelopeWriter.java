package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import dev.spm.core.codec.legacy.*;
import io.airlift.compress.zstd.ZstdOutputStream;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.SecureRandom;
import java.util.*;

/** Real inverse envelope pipeline. Zero salt is valid; each file has a fresh 56-byte password. */
final class V3EnvelopeWriter {
    private V3EnvelopeWriter() { }
    @SuppressWarnings("try")
    static YsmEncodedFile encode(byte[] wire, String summary, DecodePolicy policy, ResourceBudget budget,
                                 CancellationToken token) throws IOException {
        Objects.requireNonNull(wire); Objects.requireNonNull(summary); Objects.requireNonNull(policy);
        Objects.requireNonNull(budget); Objects.requireNonNull(token); token.check();
        if (wire.length < 4 || wire.length > Math.min(256L << 20, Math.min(policy.maxExpandedBytes(), policy.maxInputBytes()))) {
            throw new IOException("V3 writer input limit");
        }
        int version = LegacyHash.littleInt(wire, 0);
        if (version < 1 || version > 32) throw new IOException("Unsupported historical wire version");
        if (summary.indexOf('\0') >= 0 || summary.length() > Math.min(policy.maxStringBytes(), 16_777_216)) {
            throw new IOException("Invalid V3 summary");
        }
        long sourceLimit = Math.min(69_206_016L, policy.maxInputBytes());
        try (var summaryScratch = YsmArchiveCodec.reserve(budget, 3L * summary.length());
             var sourceScratch = YsmArchiveCodec.reserve(budget, wire.length)) {
            var summaryBuffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(summary));
            if (summaryBuffer.remaining() > Math.min(policy.maxStringBytes(), 16_777_216)) throw new IOException("V3 summary byte limit");
            byte[] summaryBytes = new byte[summaryBuffer.remaining()]; summaryBuffer.get(summaryBytes);
            int bodyOffset = Math.addExact(12, summaryBytes.length);
            long frameLimit = sourceLimit - bodyOffset - 66;
            if (frameLimit < 1) throw new IOException("V3 encoded file limit");
            byte[] stableWire = wire.clone();
            try (var compressed = new BoundedBytes(frameLimit, budget, token)) {
                // 0.27 level-3 stream: 4 * 2 MiB history buffer plus tables/state;
                // reserve a conservative 16 MiB before invoking the external encoder.
                try (var workspace = YsmArchiveCodec.reserve(budget, 16L << 20);
                     var zstd = new ZstdOutputStream(new KeepOpen(compressed))) {
                    for (int p = 0; p < stableWire.length; p += 8192) {
                        token.check(); zstd.write(stableWire, p, Math.min(8192, stableWire.length - p));
                    }
                }
                try (var frameScratch = YsmArchiveCodec.reserve(budget, compressed.size())) {
                    byte[] frame = compressed.copy();
                    dirtyHeaders(frame, token);
                    int total = Math.addExact(bodyOffset + 66, frame.length);
                    if (total > sourceLimit) throw new IOException("V3 encoded file limit");
                    var owned = YsmArchiveCodec.reserve(budget, total);
                    try {
                        byte[] output = new byte[total];
                        var fields = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
                        fields.put(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'Y', 'S', 'G', 'P'});
                        fields.put(summaryBytes).put((byte) 0).putInt(3).putShort((short) 0).put(frame);
                        byte[] password = new byte[56]; new SecureRandom().nextBytes(password);
                        int footer = output.length - 64;
                        fields.put(password);
                        LegacyCipher.encrypt(output, bodyOffset, footer - bodyOffset, password, token);
                        fields.putLong(LegacyHash.seeded(output, 0, output.length - 8, 0x9e5599db80c67c29L));
                        try (var validated = YsmEnvelopeCodec.decode(output, policy, budget, token)) {
                            if (validated.innerVersion() != version || !Arrays.equals(stableWire, validated.bytes())) {
                                throw new IOException("V3 writer re-admission mismatch");
                            }
                        }
                        token.check();
                        return new YsmEncodedFile(output, owned);
                    } catch (IOException | RuntimeException | Error failed) { owned.close(); throw failed; }
                }
            } catch (RuntimeException malformed) {
                if (malformed instanceof java.util.concurrent.CancellationException) throw malformed;
                throw new IOException("V3 compressor failed", malformed);
            }
        }
    }
    /** Convert a normal zstd frame into the historical three-byte block-header representation. */
    private static void dirtyHeaders(byte[] frame, CancellationToken token) throws IOException {
        if (frame.length < 5 || LegacyHash.littleInt(frame, 0) != 0xfd2fb528) throw new IOException("Compressor emitted invalid zstd frame");
        int descriptor = frame[4] & 255, sizeFlag = descriptor >>> 6;
        boolean single = (descriptor & 32) != 0;
        int dict = switch (descriptor & 3) { case 0 -> 0; case 1 -> 1; case 2 -> 2; default -> 4; };
        int content = sizeFlag == 0 ? (single ? 1 : 0) : 1 << sizeFlag;
        int cursor = 5 + (single ? 0 : 1) + dict + content;
        if (cursor > frame.length) throw new IOException("Truncated encoded zstd header");
        boolean last;
        do {
            token.check();
            if (frame.length - cursor < 3) throw new IOException("Truncated encoded zstd block");
            int clean = (frame[cursor] & 255) | (frame[cursor + 1] & 255) << 8 | (frame[cursor + 2] & 255) << 16;
            int kind = (clean >>> 1) & 3, size = clean >>> 3;
            last = (clean & 1) != 0;
            if (kind == 3 || size > 131072) throw new IOException("Invalid encoded zstd block");
            int historicalKind = kind == 0 ? 3 : kind == 1 ? 1 : 0;
            int dirty = (size >>> 16) | (historicalKind << 5) | (last ? 128 : 0) | (((size & 65535) ^ 0xd4e9) << 8);
            for (int i = 0; i < 3; i++) frame[cursor + i] = (byte) (dirty >>> (8 * i));
            cursor += 3;
            int payload = kind == 1 ? 1 : size;
            if (payload > frame.length - cursor) throw new IOException("Truncated encoded zstd payload");
            cursor += payload;
        } while (!last);
        if ((descriptor & 4) != 0) cursor += 4;
        if (cursor != frame.length) throw new IOException("Encoded zstd has trailing data");
    }
    private static final class KeepOpen extends FilterOutputStream {
        KeepOpen(OutputStream output) { super(output); }
        @Override public void close() throws IOException { flush(); }
        @Override public void write(byte[] bytes, int offset, int size) throws IOException { out.write(bytes, offset, size); }
    }
}
