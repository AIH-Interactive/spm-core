package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Resource writer; outer versions 1 and 2 only. No semantic conversion occurs here. */
final class LegacyArchiveWriter {
    private LegacyArchiveWriter() { }
    @SuppressWarnings("try")
    static YsmEncodedFile encode(int version, Map<ResourceKey, byte[]> resources, DecodePolicy policy,
                                  ResourceBudget budget, CancellationToken token) throws IOException {
        Objects.requireNonNull(resources); Objects.requireNonNull(policy);
        Objects.requireNonNull(budget); Objects.requireNonNull(token);
        token.check();
        if (version != 1 && version != 2) throw new IOException("Only V1/V2 resource writing is supported");
        if (resources.size() > policy.maxEntries() || policy.maxInputBytes() < 24) throw new IOException("Writer entry/output limit");
        var ordered = new TreeMap<String, byte[]>();
        long expanded = 0;
        for (var entry : resources.entrySet()) {
            String path = Objects.requireNonNull(entry.getKey()).path();
            byte[] value = Objects.requireNonNull(entry.getValue());
            if (path.getBytes(StandardCharsets.UTF_8).length > policy.maxStringBytes()
                    || path.split("/", -1).length > policy.maxDepth()) throw new IOException("Writer name/depth limit");
            if (value.length > policy.maxExpandedBytes() - expanded) throw new IOException("Writer aggregate input limit");
            expanded += value.length;
            ordered.put(path, value);
        }
        for (String name : ordered.keySet()) {
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                if (ordered.containsKey(name.substring(0, slash))) throw new IOException("Writer file/directory collision");
            }
        }
        var random = new SecureRandom();
        try (var body = new BoundedBytes(policy.maxInputBytes() - 24, budget, token);
             var fields = new DataOutputStream(body)) {
            for (var entry : ordered.entrySet()) {
                token.check();
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                if (version == 2) name = Base64.getEncoder().encode(name);
                fields.writeInt(name.length); fields.write(name);
                byte[] key = new byte[16], iv = new byte[16]; random.nextBytes(key); random.nextBytes(iv);
                byte[] encrypted;
                try (var sourceReservation = YsmArchiveCodec.reserve(budget, entry.getValue().length);
                     var zipped = new BoundedBytes(policy.maxInputBytes(), budget, token)) {
                    byte[] stable = entry.getValue().clone();
                    try (var deflater = new DeflaterOutputStream(zipped)) {
                        for (int p = 0; p < stable.length; p += 8192) {
                            token.check(); deflater.write(stable, p, Math.min(8192, stable.length - p));
                        }
                        deflater.finish();
                        // Charge the compressed copy and the ciphertext before either is allocated,
                        // then release them once the ciphertext is written into the body.
                        try (var plainReservation = YsmArchiveCodec.reserve(budget, zipped.size())) {
                            byte[] compressed = zipped.copy();
                            int ciphertextBytes = Math.addExact(compressed.length, 16 - compressed.length % 16);
                            try (var cipherReservation = YsmArchiveCodec.reserve(budget, ciphertextBytes)) {
                                encrypted = aes(compressed, key, iv);
                            }
                        }
                    }
                }
                fields.writeInt(encrypted.length);
                if (version == 1) fields.write(key);
                else {
                    try (var derivedReservation = YsmArchiveCodec.reserve(budget, 16);
                         var wrappedReservation = YsmArchiveCodec.reserve(budget, 32)) {
                        byte[] derived = new byte[16];
                        new Random(ByteBuffer.wrap(md5(encrypted)).getLong(8)).nextBytes(derived);
                        byte[] wrapped = aes(key, derived, iv);
                        fields.writeInt(wrapped.length); fields.write(wrapped);
                    }
                }
                fields.write(iv); fields.write(encrypted);
            }
            int size = Math.addExact(24, body.size());
            var owned = YsmArchiveCodec.reserve(budget, size);
            try {
                // The staging capacity is still charged here, so the retained body copy is charged
                // before it is allocated and only the final encoded array outlives this scope.
                try (var bodyReservation = YsmArchiveCodec.reserve(budget, body.size())) {
                    byte[] bodyBytes = body.copy();
                    byte[] encoded = ByteBuffer.allocate(size).putInt(0x59534750).putInt(version)
                            .put(md5(bodyBytes)).put(bodyBytes).array();
                    return new YsmEncodedFile(encoded, owned);
                }
            } catch (RuntimeException | Error failed) { owned.close(); throw failed; }
        }
    }
    private static byte[] md5(byte[] data) {
        try { return MessageDigest.getInstance("MD5").digest(data); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] aes(byte[] plain, byte[] key, byte[] iv) throws IOException {
        try {
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(plain);
        } catch (GeneralSecurityException failed) { throw new IOException("Writer AES failed", failed); }
    }
}
