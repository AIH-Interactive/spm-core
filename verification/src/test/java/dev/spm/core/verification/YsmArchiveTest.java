package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.DeflaterOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fixtures independently write the documented V1/V2 layout using JDK primitives. */
final class YsmArchiveTest {
    private static final DecodePolicy POLICY = DecodePolicy.conservative();
    private static final ResourceKey KEY = new ResourceKey("models/main.json");

    @Test void writesBothVersionsWithDistinctRandomEncryptionAndBoundedOwnedOutput() throws Exception {
        var resources = Map.of(KEY, "independent writer payload".getBytes(StandardCharsets.UTF_8));
        var budget = new ResourceBudget(1 << 20);
        for (int version : new int[] {1, 2}) {
            try (var encoded = YsmArchiveCodec.encode(version, resources, POLICY, budget, new CancellationToken());
                 var second = YsmArchiveCodec.encode(version, resources, POLICY, budget, new CancellationToken())) {
                assertFalse(Arrays.equals(encoded.bytes(), second.bytes()));
                assertEquals(encoded.bytes().length + second.bytes().length, budget.used());
                byte[] bytes = encoded.bytes(); bytes[0] = 0;
                assertEquals('Y', encoded.bytes()[0]);
                try (var archive = YsmArchiveCodec.decode(encoded.bytes(), POLICY, budget, new CancellationToken())) {
                    assertArrayEquals(resources.get(KEY), archive.read(KEY));
                }
                assertArrayEquals(resources.get(KEY), readIndependentWrittenFile(encoded.bytes(), version));
            }
            assertEquals(0, budget.used());
        }
        var limit = new DecodePolicy(28, 100, 4, 64, 4);
        assertThrows(IOException.class, () -> YsmArchiveCodec.encode(1, resources, limit, budget, new CancellationToken()));
        assertEquals(0, budget.used());
        assertThrows(IOException.class, () -> YsmArchiveCodec.encode(3, resources, POLICY, budget, new CancellationToken()));
    }

    private static byte[] readIndependentWrittenFile(byte[] bytes, int version) throws Exception {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals(0x59534750, in.readInt()); assertEquals(version, in.readInt());
        byte[] expectedMd5 = in.readNBytes(16);
        assertArrayEquals(MessageDigest.getInstance("MD5").digest(Arrays.copyOfRange(bytes, 24, bytes.length)), expectedMd5);
        byte[] name = in.readNBytes(in.readInt());
        if (version == 2) name = Base64.getDecoder().decode(name);
        assertEquals(KEY.path(), new String(name, StandardCharsets.UTF_8));
        int length = in.readInt();
        byte[] key = in.readNBytes(version == 1 ? 16 : in.readInt());
        byte[] iv = in.readNBytes(16), ciphertext = in.readNBytes(length);
        if (version == 2) {
            long seed = 0;
            for (byte b : MessageDigest.getInstance("MD5").digest(ciphertext)) seed = (seed << 8) | (b & 255);
            byte[] wrapKey = new byte[16]; new Random(seed).nextBytes(wrapKey);
            var aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrapKey, "AES"), new IvParameterSpec(iv));
            key = aes.doFinal(key);
        }
        var aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        assertEquals(-1, in.read());
        try (var zlib = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(aes.doFinal(ciphertext)))) {
            return zlib.readAllBytes();
        }
    }

    @Test void decodesBothLegacyLayoutsAndOwnsDefensivePayloadsUntilClose() throws Exception {
        for (int version : new int[] {1, 2}) {
            byte[] fixture = fixture(version, List.of("models/main.json", "textures/猫.png"),
                    List.of("{\"bones\":[]}".getBytes(StandardCharsets.UTF_8), new byte[] {1, 2, 3}));
            var budget = new ResourceBudget(1 << 20);
            ModelArchive archive = YsmArchiveCodec.decode(fixture, POLICY, budget, new CancellationToken());
            assertEquals(version, archive.outerVersion());
            assertEquals(Set.of(KEY, new ResourceKey("textures/猫.png")), archive.entries());
            assertThrows(UnsupportedOperationException.class, () -> archive.entries().clear());
            Arrays.fill(fixture, (byte) 0);
            assertArrayEquals("{\"bones\":[]}".getBytes(StandardCharsets.UTF_8), archive.read(KEY));
            byte[] first = archive.read(KEY); first[0] = 0;
            assertEquals('{', archive.read(KEY)[0]);
            assertEquals(15, budget.used());
            archive.close(); archive.close();
            assertEquals(0, budget.used());
            assertThrows(IllegalStateException.class, () -> archive.read(KEY));
        }
    }

    @Test void rejectsChecksumTruncationDuplicateCollisionAndTraversalWithoutLeaks() throws Exception {
        byte[] valid = fixture(2, List.of("a"), List.of(new byte[] {9}));
        byte[] corrupt = valid.clone(); corrupt[corrupt.length - 1] ^= 1;
        var cases = new ArrayList<byte[]>();
        cases.add(corrupt); cases.add(Arrays.copyOf(valid, valid.length - 1));
        cases.add(fixture(1, List.of("../escape"), List.of(new byte[] {1})));
        cases.add(fixture(1, List.of("a", "a"), List.of(new byte[] {1}, new byte[] {2})));
        cases.add(fixture(2, List.of("a", "a/child"), List.of(new byte[] {1}, new byte[] {2})));
        for (byte[] bad : cases) {
            var budget = new ResourceBudget(1 << 20);
            assertThrows(IOException.class, () -> YsmArchiveCodec.decode(bad, POLICY, budget, new CancellationToken()));
            assertEquals(0, budget.used());
        }
    }

    @Test void enforcesAggregateExpansionNamesDepthEntryLimitsAndCancellation() throws Exception {
        byte[] source = fixture(1, List.of("a/b", "second"), List.of(new byte[8192], new byte[4096]));
        for (DecodePolicy limit : List.of(new DecodePolicy(source.length - 1, 20000, 8, 64, 4),
                new DecodePolicy(20000, 12000, 8, 64, 4), new DecodePolicy(20000, 20000, 1, 64, 4),
                new DecodePolicy(20000, 20000, 8, 2, 4), new DecodePolicy(20000, 20000, 8, 64, 1))) {
            var budget = new ResourceBudget(1 << 20);
            assertThrows(IOException.class, () -> YsmArchiveCodec.decode(source, limit, budget, new CancellationToken()));
            assertEquals(0, budget.used());
        }
        var token = new CancellationToken(); token.cancel();
        var budget = new ResourceBudget(1 << 20);
        assertThrows(CancellationException.class, () -> YsmArchiveCodec.decode(source, POLICY, budget, token));
        assertEquals(0, budget.used());
        var tight = new ResourceBudget(source.length + 100);
        assertThrows(IOException.class, () -> YsmArchiveCodec.decode(source, POLICY, tight, new CancellationToken()));
        assertEquals(0, tight.used());
    }

    @Test void rejectsWriterAncestorCollisionEvenWithLexicallyInterveningSibling() {
        var budget = new ResourceBudget(10000);
        var resources = Map.of(new ResourceKey("a"), new byte[0], new ResourceKey("a-other"), new byte[0],
                new ResourceKey("a/child"), new byte[0]);
        assertThrows(IOException.class, () -> YsmArchiveCodec.encode(1, resources, POLICY, budget, new CancellationToken()));
        assertEquals(0, budget.used());
    }

    static byte[] fixture(int version, List<String> names, List<byte[]> values) throws Exception {
        var body = new ByteArrayOutputStream();
        try (var fields = new DataOutputStream(body)) {
            for (int i = 0; i < names.size(); i++) {
                byte[] name = names.get(i).getBytes(StandardCharsets.UTF_8);
                if (version == 2) name = Base64.getEncoder().encode(name);
                fields.writeInt(name.length); fields.write(name);
                byte[] key = new byte[16], iv = new byte[16];
                Arrays.fill(key, (byte) 0x37); Arrays.fill(iv, (byte) 0x51);
                var compressed = new ByteArrayOutputStream();
                try (var zlib = new DeflaterOutputStream(compressed)) { zlib.write(values.get(i)); }
                byte[] encrypted = aes(compressed.toByteArray(), key, iv);
                fields.writeInt(encrypted.length);
                if (version == 1) fields.write(key);
                else {
                    byte[] digest = MessageDigest.getInstance("MD5").digest(encrypted);
                    long seed = 0;
                    for (byte b : digest) seed = (seed << 8) | (b & 255);
                    byte[] derived = new byte[16]; new Random(seed).nextBytes(derived);
                    byte[] wrapped = aes(key, derived, iv);
                    fields.writeInt(wrapped.length); fields.write(wrapped);
                }
                fields.write(iv); fields.write(encrypted);
            }
        }
        var result = new ByteArrayOutputStream();
        try (var header = new DataOutputStream(result)) {
            header.writeInt(0x59534750); header.writeInt(version);
            header.write(MessageDigest.getInstance("MD5").digest(body.toByteArray()));
            body.writeTo(header);
        }
        return result.toByteArray();
    }

    private static byte[] aes(byte[] data, byte[] key, byte[] iv) throws Exception {
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }
}
