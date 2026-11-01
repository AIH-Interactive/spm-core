package dev.spm.core.format.ysm;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.DecodePolicy;
import dev.spm.core.api.ResourceBudget;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import dev.spm.core.api.ResourceKey;

/** Decoder for raw-file V1/V2 archives. V3 has a distinct compiled-wire endpoint. */
public final class YsmArchiveCodec {
    private YsmArchiveCodec() { }
    public static YsmEncodedFile encode(int version, Map<ResourceKey, byte[]> resources, DecodePolicy policy,
                                       ResourceBudget budget, CancellationToken token) throws IOException {
        return LegacyArchiveWriter.encode(version, resources, policy, budget, token);
    }
    @SuppressWarnings("try") // Reservations enforce ownership through their lifetime, not their value.
    public static ModelArchive decode(byte[] input, DecodePolicy policy, ResourceBudget budget,
                                      CancellationToken token) throws IOException {
        Objects.requireNonNull(input); Objects.requireNonNull(policy);
        Objects.requireNonNull(budget); Objects.requireNonNull(token);
        token.check();
        if (input.length < 24 || input.length > policy.maxInputBytes()) throw new IOException("Archive input limit/header");
        var retained = new ArrayList<ResourceBudget.Reservation>();
        try (var snapshotReservation = reserve(budget, input.length)) {
            byte[] snapshot = input.clone();
            var fields = ByteBuffer.wrap(snapshot);
            if (fields.getInt() != 0x59534750) throw new IOException("Not a YSM raw archive");
            int version = fields.getInt();
            if (version != 1 && version != 2) throw new IOException("Unsupported raw archive version; V3 uses compiled wire");
            byte[] expected = take(fields, 16);
            if (!MessageDigest.isEqual(expected, md5(snapshot, 24, snapshot.length - 24))) throw new IOException("Archive MD5 mismatch");
            var files = new LinkedHashMap<ResourceKey, byte[]>();
            long expanded = 0;
            int entries = 0;
            while (fields.hasRemaining()) {
                token.check();
                if (++entries > policy.maxEntries()) throw new IOException("Archive entry limit");
                long encodedLimit = version == 2 ? ((long) policy.maxStringBytes() + 2) / 3 * 4 : policy.maxStringBytes();
                byte[] name = take(fields, length(fields, encodedLimit));
                if (version == 2) {
                    try { name = Base64.getDecoder().decode(name); }
                    catch (IllegalArgumentException bad) { throw new IOException("Invalid encoded archive name", bad); }
                }
                if (name.length > policy.maxStringBytes()) throw new IOException("Archive name limit");
                String path = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
                // Historical archive paths permit one leading slash; logical ResourceKey does not.
                if (path.startsWith("/")) path = path.substring(1);
                if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                final ResourceKey key;
                try { key = new ResourceKey(path); }
                catch (IllegalArgumentException invalid) { throw new IOException("Unsafe archive path", invalid); }
                if (path.split("/", -1).length > policy.maxDepth()) throw new IOException("Archive depth limit");
                for (ResourceKey existing : files.keySet()) {
                    if (existing.equals(key) || existing.path().startsWith(path + "/") || path.startsWith(existing.path() + "/")) {
                        throw new IOException("Duplicate archive entry or file/directory collision");
                    }
                }
                int encryptedSize = length(fields, policy.maxInputBytes());
                if (encryptedSize == 0 || encryptedSize % 16 != 0) throw new IOException("Invalid encrypted payload length");
                byte[] keyBytes = take(fields, version == 1 ? 16 : length(fields, 64));
                byte[] iv = take(fields, 16);
                if (encryptedSize > fields.remaining()) throw new IOException("Truncated encrypted entry");
                int offset = fields.position();
                if (version == 2) {
                    if (keyBytes.length == 0 || keyBytes.length % 16 != 0) throw new IOException("Invalid wrapped key");
                    long seed = ByteBuffer.wrap(md5(snapshot, offset, encryptedSize)).getLong(8);
                    byte[] derived = new byte[16]; new Random(seed).nextBytes(derived);
                    keyBytes = decrypt(keyBytes, 0, keyBytes.length, derived, iv);
                    if (keyBytes.length != 16) throw new IOException("Wrapped key is not AES-128");
                }
                byte[] value;
                try (var compressedReservation = reserve(budget, encryptedSize)) {
                    byte[] compressed = decrypt(snapshot, offset, encryptedSize, keyBytes, iv);
                    value = inflate(compressed, policy.maxExpandedBytes() - expanded, budget, token, retained);
                }
                fields.position(offset + encryptedSize);
                expanded += value.length;
                files.put(key, value);
            }
            var archive = new ModelArchive(version, files, retained);
            retained.clear();
            return archive;
        } finally { retained.forEach(ResourceBudget.Reservation::close); }
    }

    private static byte[] inflate(byte[] compressed, long limit, ResourceBudget budget, CancellationToken token,
                                  List<ResourceBudget.Reservation> retained) throws IOException {
        var inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            // BoundedBytes charges the real backing-array capacity, so growth is bound by the shared
            // budget instead of a logical byte count that understates what is actually allocated.
            try (var staging = new BoundedBytes(limit, budget, token)) {
                byte[] chunk = new byte[8192];
                while (!inflater.finished()) {
                    token.check();
                    int n = inflater.inflate(chunk);
                    if (n > limit - staging.size()) throw new IOException("Aggregate expansion limit");
                    if (n > 0) staging.write(chunk, 0, n);
                    else if (!inflater.finished()) throw new IOException("Incomplete zlib stream");
                }
                if (inflater.getRemaining() != 0) throw new IOException("Trailing zlib stream data");
                // Charge the retained copy before it is allocated, then hand ownership to the caller.
                retained.add(reserve(budget, staging.size()));
                return staging.copy();
            }
        } catch (DataFormatException malformed) { throw new IOException("Malformed zlib stream", malformed); }
        finally { inflater.end(); }
    }

    static ResourceBudget.Reservation reserve(ResourceBudget budget, long bytes) throws IOException {
        try { return budget.reserve(bytes); }
        catch (IllegalStateException exhausted) { throw new IOException("Decode live-byte budget exceeded", exhausted); }
    }
    private static byte[] decrypt(byte[] input, int offset, int size, byte[] key, byte[] iv) throws IOException {
        try {
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input, offset, size);
        } catch (GeneralSecurityException invalid) { throw new IOException("Archive AES decryption failed", invalid); }
    }
    private static byte[] md5(byte[] input, int offset, int size) {
        try {
            var digest = MessageDigest.getInstance("MD5"); digest.update(input, offset, size); return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static int length(ByteBuffer fields, long max) throws IOException {
        if (fields.remaining() < 4) throw new IOException("Truncated archive length");
        long length = Integer.toUnsignedLong(fields.getInt());
        if (length > max || length > fields.remaining()) throw new IOException("Archive length exceeds bounds");
        return (int) length;
    }
    private static byte[] take(ByteBuffer fields, int size) throws IOException {
        if (size < 0 || size > fields.remaining()) throw new IOException("Truncated archive field");
        byte[] result = new byte[size]; fields.get(result); return result;
    }
}
