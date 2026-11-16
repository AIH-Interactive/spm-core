package dev.spm.core.codec;

import dev.spm.core.api.CacheWire;
import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.DecodeLimitException;
import dev.spm.core.api.DecodePolicy;
import dev.spm.core.api.ImportErrorCode;
import dev.spm.core.api.ResourceBudget;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Host-neutral bounded runtime cache codec binding {@link CacheWire} three-layer descriptors to a
 * compressed, SHA-256-verified payload envelope without legacy cipher or Minecraft dependencies.
 */
public final class CacheEnvelopeCodec {
    private static final int MAGIC = 0x53504D43; // 'SPMC'
    private static final int FORMAT_VERSION = 1;

    private CacheEnvelopeCodec() {}

    /** Encoded cache entry paired with its validated descriptor. */
    public record EncodedCache(CacheWire descriptor, byte[] encoded) {
        public EncodedCache {
            Objects.requireNonNull(descriptor);
            encoded = Objects.requireNonNull(encoded).clone();
        }
        @Override public byte[] encoded() { return encoded.clone(); }
    }

    /** Decoded cache entry paired with its validated descriptor and plain payload bytes. */
    public record DecodedCache(CacheWire descriptor, byte[] payload) {
        public DecodedCache {
            Objects.requireNonNull(descriptor);
            payload = Objects.requireNonNull(payload).clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    /** Encodes {@code payload} with its {@link CacheWire} metadata under bounded budget/limits. */
    public static EncodedCache encode(CacheWire descriptor, byte[] payload, DecodePolicy policy,
                                      ResourceBudget budget, CancellationToken token) throws IOException {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(token);
        token.check();
        if (payload.length > policy.maxExpandedBytes() || payload.length > CacheWire.MAX_PAYLOAD_BYTES) {
            throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT, "Cache payload exceeds expanded limit");
        }
        if (payload.length != descriptor.payloadBytes()) {
            throw new IllegalArgumentException("Cache payload length disagrees with CacheWire descriptor");
        }
        String actualSha = sha256Hex(payload);
        if (!actualSha.equalsIgnoreCase(descriptor.contentSha256())) {
            throw new IllegalArgumentException("Cache payload digest disagrees with CacheWire descriptor");
        }

        byte[] compressed = compress(payload, token);
        byte[] wireIdBytes = descriptor.wireId().getBytes(StandardCharsets.UTF_8);
        byte[] profileBytes = descriptor.profileFingerprint().getBytes(StandardCharsets.UTF_8);
        byte[] shaBytes = descriptor.contentSha256().getBytes(StandardCharsets.US_ASCII);
        long estimated = 4L + 4 + 4 + wireIdBytes.length + 4 + 4 + 4 + profileBytes.length + 4 + shaBytes.length + 8 + 4 + compressed.length;
        if (estimated > policy.maxInputBytes()) {
            throw new DecodeLimitException(ImportErrorCode.INPUT_LIMIT, "Encoded cache exceeds input limit");
        }
        var reservation = budget.reserve(estimated);
        try {
            var out = new ByteArrayOutputStream((int) estimated);
            var data = new DataOutputStream(out);
            data.writeInt(MAGIC);
            data.writeInt(FORMAT_VERSION);
            writeBytes(data, wireIdBytes);
            data.writeInt(descriptor.wireVersion());
            data.writeInt(descriptor.schemaVersion());
            writeBytes(data, profileBytes);
            writeBytes(data, shaBytes);
            data.writeLong(descriptor.payloadBytes());
            writeBytes(data, compressed);
            data.flush();
            byte[] encoded = out.toByteArray();
            return new EncodedCache(descriptor, encoded);
        } finally {
            reservation.close();
        }
    }

    /** Decodes and verifies a cache envelope, refusing oversized, corrupted or truncated entries. */
    public static DecodedCache decode(byte[] encoded, DecodePolicy policy, ResourceBudget budget,
                                      CancellationToken token) throws IOException {
        Objects.requireNonNull(encoded);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(token);
        token.check();
        if (encoded.length < 36 || encoded.length > policy.maxInputBytes()) {
            throw new DecodeLimitException(ImportErrorCode.INPUT_LIMIT, "Cache input exceeds limits or is truncated");
        }
        var inputReservation = budget.reserve(encoded.length);
        try {
            var in = new DataInputStream(new java.io.ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) throw new IOException("Invalid cache envelope magic");
            if (in.readInt() != FORMAT_VERSION) throw new IOException("Unsupported cache envelope version");
            String wireId = new String(readBounded(in, policy.maxStringBytes()), StandardCharsets.UTF_8);
            int wireVersion = in.readInt();
            int schemaVersion = in.readInt();
            String profile = new String(readBounded(in, policy.maxStringBytes()), StandardCharsets.UTF_8);
            String sha = new String(readBounded(in, 128), StandardCharsets.US_ASCII);
            long payloadBytes = in.readLong();
            if (payloadBytes < 0 || payloadBytes > policy.maxExpandedBytes() || payloadBytes > CacheWire.MAX_PAYLOAD_BYTES) {
                throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT, "Cache expanded payload exceeds limit");
            }
            byte[] compressed = readBounded(in, encoded.length);
            if (in.read() != -1) throw new IOException("Trailing bytes after cache envelope");
            CacheWire descriptor;
            try {
                descriptor = new CacheWire(wireId, wireVersion, schemaVersion, profile, sha, payloadBytes);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid CacheWire header", invalid);
            }
            var outputReservation = budget.reserve(Math.max(1L, payloadBytes));
            try {
                byte[] plain = decompress(compressed, (int) payloadBytes, token);
                if (!sha256Hex(plain).equalsIgnoreCase(descriptor.contentSha256())) {
                    throw new IOException("Cache payload SHA-256 verification failed");
                }
                return new DecodedCache(descriptor, plain);
            } finally {
                outputReservation.close();
            }
        } finally {
            inputReservation.close();
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBounded(DataInputStream in, int maxBytes) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > maxBytes || len > in.available()) {
            throw new IOException("Malformed or oversized cache field");
        }
        return in.readNBytes(len);
    }

    private static byte[] compress(byte[] plain, CancellationToken token) {
        var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(plain);
            deflater.finish();
            var out = new ByteArrayOutputStream(Math.max(32, plain.length / 2));
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                token.check();
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] decompress(byte[] compressed, int expectedLength, CancellationToken token) throws IOException {
        var inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] output = new byte[expectedLength];
            int offset = 0;
            while (!inflater.finished()) {
                token.check();
                if (offset == expectedLength) {
                    byte[] probe = new byte[1];
                    if (inflater.inflate(probe) > 0 || !inflater.finished()) {
                        throw new IOException("Cache payload exceeds declared size");
                    }
                    break;
                }
                int n = inflater.inflate(output, offset, expectedLength - offset);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("Truncated or dictionary-dependent cache payload");
                    }
                }
                offset += n;
            }
            if (offset != expectedLength) throw new IOException("Truncated cache payload");
            return Arrays.copyOf(output, offset);
        } catch (DataFormatException invalid) {
            throw new IOException("Corrupt compressed cache payload", invalid);
        } finally {
            inflater.end();
        }
    }
}
