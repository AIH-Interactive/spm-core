package dev.spm.core.format.figura;

import dev.spm.core.api.DecodeLimitException;
import dev.spm.core.api.ImportErrorCode;
import dev.spm.core.format.common.BoundedJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Bounded pure-Java binary NBT reader (gzip, zlib, or uncompressed big-endian). */
final class NbtReader {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private NbtReader() {}

    static Map<String, Object> readRootCompound(byte[] raw, BoundedJson.Quota quota) throws IOException {
        byte[] uncompressed = decompressIfNeeded(raw, quota);
        var in = new DataInputStream(new ByteArrayInputStream(uncompressed));
        int rootType;
        try {
            rootType = in.readUnsignedByte();
        } catch (EOFException eof) {
            throw new IOException("Empty NBT stream", eof);
        }
        if (rootType != TAG_COMPOUND) throw new IOException("NBT root tag must be Compound (got " + rootType + ")");
        readString(in, quota); // root name
        return readCompound(in, quota, 0);
    }

    private static byte[] decompressIfNeeded(byte[] raw, BoundedJson.Quota quota) throws IOException {
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (var gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return readBounded(gz, quota);
            }
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0x78 && (((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)) % 31 == 0) {
            try (var inf = new InflaterInputStream(new ByteArrayInputStream(raw))) {
                return readBounded(inf, quota);
            }
        }
        return raw;
    }

    private static byte[] readBounded(InputStream in, BoundedJson.Quota quota) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        long limit = quota.policy.maxExpandedBytes();
        while ((n = in.read(buf)) != -1) {
            quota.cancellation.check();
            total = Math.addExact(total, n);
            if (total > limit) throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT, "NBT expanded size limit");
            quota.charge(n);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Map<String, Object> readCompound(DataInputStream in, BoundedJson.Quota quota, int depth)
            throws IOException {
        if (depth > Math.min(quota.policy.maxDepth(), 128)) {
            throw new IOException("NBT nesting depth limit exceeded");
        }
        var map = new LinkedHashMap<String, Object>();
        int count = 0;
        while (true) {
            quota.cancellation.check();
            int tag = in.readUnsignedByte();
            if (tag == TAG_END) return map;
            if (++count > quota.policy.maxCollectionEntries()) {
                throw new DecodeLimitException(ImportErrorCode.RESOURCE_LIMIT, "NBT compound entry limit");
            }
            String key = readString(in, quota);
            quota.charge(32L);
            map.put(key, readPayload(tag, in, quota, depth + 1));
        }
    }

    private static Object readPayload(int tag, DataInputStream in, BoundedJson.Quota quota, int depth)
            throws IOException {
        quota.cancellation.check();
        return switch (tag) {
            case TAG_BYTE -> in.readByte();
            case TAG_SHORT -> in.readShort();
            case TAG_INT -> in.readInt();
            case TAG_LONG -> in.readLong();
            case TAG_FLOAT -> {
                float f = in.readFloat();
                if (!Float.isFinite(f)) throw new IOException("Non-finite NBT float");
                yield f;
            }
            case TAG_DOUBLE -> {
                double d = in.readDouble();
                if (!Double.isFinite(d)) throw new IOException("Non-finite NBT double");
                yield d;
            }
            case TAG_BYTE_ARRAY -> {
                int len = checkCount(in.readInt(), 1, quota);
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield bytes;
            }
            case TAG_STRING -> readString(in, quota);
            case TAG_LIST -> {
                if (depth > Math.min(quota.policy.maxDepth(), 128)) throw new IOException("NBT nesting depth exceeded");
                int elemTag = in.readUnsignedByte();
                int len = checkCount(in.readInt(), 8, quota);
                if (elemTag == TAG_END && len > 0) throw new IOException("Non-empty NBT TAG_End list");
                var list = new ArrayList<Object>(Math.min(len, 64));
                for (int i = 0; i < len; i++) list.add(readPayload(elemTag, in, quota, depth + 1));
                yield list;
            }
            case TAG_COMPOUND -> readCompound(in, quota, depth + 1);
            case TAG_INT_ARRAY -> {
                int len = checkCount(in.readInt(), 4, quota);
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                yield arr;
            }
            case TAG_LONG_ARRAY -> {
                int len = checkCount(in.readInt(), 8, quota);
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                yield arr;
            }
            default -> throw new IOException("Unknown NBT tag id: " + tag);
        };
    }

    private static String readString(DataInputStream in, BoundedJson.Quota quota) throws IOException {
        int len = in.readUnsignedShort();
        if (len > quota.policy.maxStringBytes()) {
            throw new DecodeLimitException(ImportErrorCode.RESOURCE_LIMIT, "NBT string limit");
        }
        quota.charge(Math.max(16L, len));
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int checkCount(int len, int bytesPerElem, BoundedJson.Quota quota) throws IOException {
        if (len < 0 || len > quota.policy.maxCollectionEntries()) {
            throw new DecodeLimitException(ImportErrorCode.RESOURCE_LIMIT, "NBT array count limit: " + len);
        }
        quota.charge(Math.multiplyExact((long) len, bytesPerElem));
        return len;
    }
}
