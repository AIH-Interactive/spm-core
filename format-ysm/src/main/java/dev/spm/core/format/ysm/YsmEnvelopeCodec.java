package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import dev.spm.core.codec.legacy.LegacyHash;
import dev.spm.core.codec.legacy.LegacyCipher;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.*;
import java.util.*;

/** V3 encrypted-envelope endpoint; result is historical compiled wire, never raw authoring files. */
public final class YsmEnvelopeCodec {
    private YsmEnvelopeCodec() { }
    public static YsmEncodedFile encode(byte[] wire, String summary, DecodePolicy policy, ResourceBudget budget,
                                         CancellationToken token) throws IOException {
        return V3EnvelopeWriter.encode(wire, summary, policy, budget, token);
    }
    @SuppressWarnings("try") // Scope-bound reservation owns the mutable source snapshot.
    public static YsmWirePayload decode(byte[] source, DecodePolicy policy, ResourceBudget budget,
                                        CancellationToken token) throws IOException {
        Objects.requireNonNull(source); Objects.requireNonNull(policy);
        Objects.requireNonNull(budget); Objects.requireNonNull(token);
        token.check();
        if (source.length < 76 || source.length > Math.min(69_206_016, policy.maxInputBytes())) throw new IOException("V3 input limit/header");
        var retained = new ArrayList<ResourceBudget.Reservation>();
        try (var snapshot = YsmArchiveCodec.reserve(budget, source.length)) {
            byte[] bytes = source.clone();
            byte[] prefix = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'Y', 'S', 'G', 'P'};
            for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) throw new IOException("Not a V3 envelope");
            int body = 7;
            int footer = bytes.length - 64;
            int maximumSummary = Math.min(policy.maxStringBytes(), 16_777_216);
            while (body < footer && bytes[body] != 0) {
                if (body - 7 >= maximumSummary) throw new IOException("V3 summary limit");
                if ((body & 8191) == 0) token.check();
                body++;
            }
            if (footer - body < 6) throw new IOException("Truncated V3 summary/version/body");
            body++;
            if (LegacyHash.littleInt(bytes, body) != 3) throw new IOException("Unsupported envelope version");
            body += 4;
            if (LegacyHash.seeded(bytes, 0, bytes.length - 8, 0x9e5599db80c67c29L)
                    != LegacyHash.littleLong(bytes, bytes.length - 8)) throw new IOException("V3 checksum mismatch");
            token.check();
            // The 56-byte password copy is a real allocation; charge it while it is live.
            try (var passwordReservation = YsmArchiveCodec.reserve(budget, 56)) {
                byte[] password = Arrays.copyOfRange(bytes, footer, footer + 56);
                LegacyCipher.decrypt(bytes, body, footer - body, password, token);
            }
            if (footer - body < 2) throw new IOException("Truncated V3 salt");
            int salt = ((bytes[body] & 255) | (bytes[body + 1] & 255) << 8) & 1023;
            int frame = body + 2 + salt;
            long limit = Math.min(256L << 20, policy.maxExpandedBytes());
            long workspaceBytes = cleanZstdHeaders(bytes, frame, footer, limit, token);
            byte[] plain;
            try (var workspace = YsmArchiveCodec.reserve(budget, workspaceBytes);
                 var zstd = new ZstdInputStream(new ByteArrayInputStream(bytes, frame, footer - frame));
                 // BoundedBytes charges real backing capacity, so growth itself is budget-bound.
                 var staging = new BoundedBytes(limit, budget, token)) {
                byte[] block = new byte[8192];
                int count;
                while ((count = zstd.read(block)) != -1) {
                    token.check();
                    if (count > limit - staging.size()) throw new IOException("V3 expanded-byte limit");
                    staging.write(block, 0, count);
                }
                YsmArchiveCodec.reserve(budget, staging.size(),retained);
                plain = staging.copy();
            } catch (RuntimeException invalid) {
                if (invalid instanceof java.util.concurrent.CancellationException) throw invalid;
                throw new IOException("Malformed V3 zstd stream", invalid);
            }
            if (plain.length < 4) throw new IOException("Truncated historical wire version");
            int version = LegacyHash.littleInt(plain, 0);
            if (version < 1 || version > 32) throw new IOException("Unsupported historical wire version");
            var result = new YsmWirePayload(version, plain, retained);
            retained.clear();
            return result;
        } finally { retained.forEach(ResourceBudget.Reservation::close); }
    }

    private static long cleanZstdHeaders(byte[] bytes, int start, int end, long limit, CancellationToken token) throws IOException {
        if (end - start < 5 || LegacyHash.littleInt(bytes, start) != 0xfd2fb528) throw new IOException("Invalid V3 zstd magic");
        int descriptor = bytes[start + 4] & 255;
        if ((descriptor & 8) != 0) throw new IOException("Reserved zstd descriptor bit");
        boolean single = (descriptor & 32) != 0;
        int sizeFlag = descriptor >>> 6;
        int dictionaryBytes = switch (descriptor & 3) { case 0 -> 0; case 1 -> 1; case 2 -> 2; default -> 4; };
        int contentBytes = sizeFlag == 0 ? (single ? 1 : 0) : 1 << sizeFlag;
        int cursor = start + 5 + (single ? 0 : 1) + dictionaryBytes + contentBytes;
        if (cursor > end) throw new IOException("Truncated zstd frame header");
        long contentSize = -1;
        if (contentBytes != 0) {
            int contentOffset = cursor - contentBytes;
            contentSize = 0;
            for (int i = 0; i < contentBytes; i++) contentSize |= (bytes[contentOffset + i] & 255L) << (i * 8);
            if (contentBytes == 2) contentSize += 256;
            if (contentSize < 0 || contentSize > limit) throw new IOException("Advertised zstd content exceeds expansion limit");
        }
        long window = contentSize;
        if (!single) {
            int windowDescriptor = bytes[start + 5] & 255;
            long base = 1L << (10 + (windowDescriptor >>> 3));
            window = base + (base >>> 3) * (windowDescriptor & 7);
        }
        // Aircompressor 0.27 supports an 8 MiB match window. Reserve its bounded
        // four-window growth envelope plus streaming input/state before construction.
        if (window < 0 || window > (8L << 20) || window > limit) throw new IOException("Unsupported/budgeted zstd window");
        long workspace = (192L << 10) + Math.min(contentSize < 0 ? limit : contentSize,
                Math.max(window, 128L << 10) * 4);
        boolean last;
        do {
            token.check();
            if (end - cursor < 3) throw new IOException("Truncated dirty block header");
            int dirty = (bytes[cursor] & 255) | (bytes[cursor + 1] & 255) << 8 | (bytes[cursor + 2] & 255) << 16;
            int kind = (dirty >>> 5) & 3;
            last = (dirty & 128) != 0;
            int size = ((dirty & 31) << 16) | ((dirty >>> 8) ^ 0xd4e9);
            if (kind == 2 || size > 131072) throw new IOException("Invalid dirty zstd block");
            int cleanKind = kind == 0 ? 2 : kind == 1 ? 1 : 0;
            int header = (size << 3) | (cleanKind << 1) | (last ? 1 : 0);
            for (int i = 0; i < 3; i++) bytes[cursor + i] = (byte) (header >>> (i * 8));
            cursor += 3;
            int payload = kind == 1 ? 1 : size;
            if (payload > end - cursor) throw new IOException("Truncated dirty zstd block");
            cursor += payload;
        } while (!last);
        if ((descriptor & 4) != 0) cursor += 4;
        if (cursor != end) throw new IOException("V3 frame has trailing bytes or missing checksum");
        return workspace;
    }
}
