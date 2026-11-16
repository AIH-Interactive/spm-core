package dev.spm.core.format.gltf;

import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.common.BoundedJson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Validated, quota-bounded glTF 2.0 bufferView and accessor readers. */
final class GltfAccessors {
    static final int BYTE = 5120;
    static final int UNSIGNED_BYTE = 5121;
    static final int SHORT = 5122;
    static final int UNSIGNED_SHORT = 5123;
    static final int UNSIGNED_INT = 5125;
    static final int FLOAT = 5126;

    private GltfAccessors() {}

    static void validateBufferViews(JsonObject root) throws IOException {
        for (JsonValue value : GltfModelImporter.arrayOptional(root.get("bufferViews"))) {
            var view = GltfModelImporter.obj(value, "bufferView");
            int offset = GltfModelImporter.integer(GltfModelImporter.optional(view.get("byteOffset"), 0), "bufferView.byteOffset");
            int length = GltfModelImporter.integer(view.get("byteLength"), "bufferView.byteLength");
            GltfModelImporter.require(offset >= 0 && length > 0, "Empty or invalid bufferView");
            if (view.get("byteStride") != null) {
                int stride = GltfModelImporter.integer(view.get("byteStride"), "bufferView.byteStride");
                GltfModelImporter.require(stride >= 4 && stride <= 252 && stride % 4 == 0, "Invalid bufferView.byteStride");
            }
            if (view.get("target") != null) {
                int target = GltfModelImporter.integer(view.get("target"), "bufferView.target");
                GltfModelImporter.require(target == 34962 || target == 34963, "Unsupported bufferView.target");
            }
        }
    }

    static void validateAllAccessors(List<JsonValue> accessors, List<JsonValue> views,
                                     List<byte[]> buffers, BoundedJson.Quota quota) throws IOException {
        for (JsonValue value : accessors) {
            var accessor = GltfModelImporter.obj(value, "accessor");
            GltfModelImporter.requireSupported(accessor.get("sparse") == null, "Sparse accessors are not supported");
            int component = GltfModelImporter.integer(accessor.get("componentType"), "accessor.componentType");
            int size = componentSize(component);
            String type = GltfModelImporter.string(accessor.get("type"), "accessor.type");
            int multiplier = componentMultiplier(type);
            int element = Math.multiplyExact(size, multiplier);
            int count = GltfModelImporter.integer(accessor.get("count"), "accessor.count");
            GltfModelImporter.require(count > 0 && count <= quota.policy.maxCollectionEntries(), "Invalid accessor count");
            JsonValue min = accessor.get("min"), max = accessor.get("max");
            GltfModelImporter.require((min == null) == (max == null), "Accessor min/max must be paired");
            if (min != null) {
                List<JsonValue> lower = GltfModelImporter.numberArray(min, "accessor.min");
                List<JsonValue> upper = GltfModelImporter.numberArray(max, "accessor.max");
                GltfModelImporter.require(lower.size() == multiplier && upper.size() == multiplier, "Accessor min/max arity mismatch");
                for (int i = 0; i < multiplier; i++) {
                    GltfModelImporter.require(GltfModelImporter.number(lower.get(i), "accessor.min")
                            <= GltfModelImporter.number(upper.get(i), "accessor.max"), "Reversed accessor bounds");
                }
            }
            if (accessor.get("bufferView") != null) {
                validateSliceBounds(accessor, views, buffers, element);
            }
        }
    }

    static float[] readVec3(JsonObject accessor, List<JsonValue> views, List<byte[]> buffers,
                            BoundedJson.Quota quota, String label) throws IOException {
        requireUnnormalized(accessor);
        GltfModelImporter.require(GltfModelImporter.integer(accessor.get("componentType"), "componentType") == FLOAT
                && "VEC3".equals(GltfModelImporter.string(accessor.get("type"), "accessor.type")),
                label + " must be float VEC3");
        int count = GltfModelImporter.integer(accessor.get("count"), "accessor.count");
        GltfModelImporter.require(count > 0 && count <= quota.policy.maxCollectionEntries(), "Invalid " + label + " count");
        Slice slice = validateSliceBounds(accessor, views, buffers, 12);
        quota.charge(Math.multiplyExact((long) count, 12L));
        float[] out = new float[Math.multiplyExact(count, 3)];
        ByteBuffer in = ByteBuffer.wrap(slice.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int base = Math.toIntExact(slice.absolute() + (long) i * slice.stride());
            for (int axis = 0; axis < 3; axis++) {
                float v = in.getFloat(base + axis * 4);
                GltfModelImporter.require(Float.isFinite(v), "Non-finite " + label);
                out[i * 3 + axis] = v;
            }
        }
        return out;
    }

    static float[] readVec2(JsonObject accessor, List<JsonValue> views, List<byte[]> buffers,
                            BoundedJson.Quota quota, int expected) throws IOException {
        int component = GltfModelImporter.integer(accessor.get("componentType"), "componentType");
        boolean normalized = isNormalized(accessor);
        GltfModelImporter.require("VEC2".equals(GltfModelImporter.string(accessor.get("type"), "accessor.type"))
                && GltfModelImporter.integer(accessor.get("count"), "accessor.count") == expected,
                "TEXCOORD_0 must be matching VEC2");
        GltfModelImporter.require(component == FLOAT && !normalized
                || (component == UNSIGNED_BYTE || component == UNSIGNED_SHORT) && normalized,
                "Unsupported TEXCOORD_0 component type");
        int elemSize = componentSize(component) * 2;
        Slice slice = validateSliceBounds(accessor, views, buffers, elemSize);
        quota.charge(Math.multiplyExact((long) expected, 8L));
        float[] out = new float[Math.multiplyExact(expected, 2)];
        ByteBuffer in = ByteBuffer.wrap(slice.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expected; i++) {
            int base = Math.toIntExact(slice.absolute() + (long) i * slice.stride());
            for (int axis = 0; axis < 2; axis++) {
                float v = switch (component) {
                    case FLOAT -> in.getFloat(base + axis * 4);
                    case UNSIGNED_BYTE -> Byte.toUnsignedInt(in.get(base + axis)) / 255.0f;
                    case UNSIGNED_SHORT -> Short.toUnsignedInt(in.getShort(base + axis * 2)) / 65535.0f;
                    default -> throw new IOException("Unsupported TEXCOORD_0 component");
                };
                GltfModelImporter.require(Float.isFinite(v), "Non-finite TEXCOORD_0");
                out[i * 2 + axis] = v;
            }
        }
        return out;
    }

    static int[] readColorsRgba(JsonObject accessor, List<JsonValue> views, List<byte[]> buffers,
                                BoundedJson.Quota quota, int expected) throws IOException {
        String type = GltfModelImporter.string(accessor.get("type"), "COLOR_0.type");
        int lanes = switch (type) { case "VEC3" -> 3; case "VEC4" -> 4; default -> throw new IOException("COLOR_0 must be VEC3 or VEC4"); };
        int component = GltfModelImporter.integer(accessor.get("componentType"), "COLOR_0.componentType");
        boolean normalized = isNormalized(accessor);
        GltfModelImporter.require(GltfModelImporter.integer(accessor.get("count"), "COLOR_0.count") == expected,
                "COLOR_0 count mismatch");
        GltfModelImporter.require(component == FLOAT && !normalized
                || (component == UNSIGNED_BYTE || component == UNSIGNED_SHORT) && normalized,
                "Unsupported COLOR_0 component type");
        int elemSize = componentSize(component) * lanes;
        Slice slice = validateSliceBounds(accessor, views, buffers, elemSize);
        quota.charge(Math.multiplyExact((long) expected, 4L));
        int[] out = new int[expected];
        ByteBuffer in = ByteBuffer.wrap(slice.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        int cSize = componentSize(component);
        for (int i = 0; i < expected; i++) {
            int base = Math.toIntExact(slice.absolute() + (long) i * slice.stride());
            int[] rgba = {255, 255, 255, 255};
            for (int c = 0; c < lanes; c++) {
                float f = switch (component) {
                    case FLOAT -> in.getFloat(base + c * 4);
                    case UNSIGNED_BYTE -> Byte.toUnsignedInt(in.get(base + c)) / 255.0f;
                    case UNSIGNED_SHORT -> Short.toUnsignedInt(in.getShort(base + c * cSize)) / 65535.0f;
                    default -> 1.0f;
                };
                GltfModelImporter.require(Float.isFinite(f), "Non-finite COLOR_0");
                rgba[c] = Math.max(0, Math.min(255, Math.round(f * 255.0f)));
            }
            out[i] = (rgba[3] << 24) | (rgba[2] << 16) | (rgba[1] << 8) | rgba[0];
        }
        return out;
    }

    static int[] readJoints(JsonObject accessor, List<JsonValue> views, List<byte[]> buffers,
                            BoundedJson.Quota quota, int expected) throws IOException {
        requireUnnormalized(accessor);
        GltfModelImporter.require("VEC4".equals(GltfModelImporter.string(accessor.get("type"), "JOINTS_0.type"))
                && GltfModelImporter.integer(accessor.get("count"), "JOINTS_0.count") == expected,
                "JOINTS_0 must be matching VEC4");
        int component = GltfModelImporter.integer(accessor.get("componentType"), "JOINTS_0.componentType");
        GltfModelImporter.require(component == UNSIGNED_BYTE || component == UNSIGNED_SHORT,
                "JOINTS_0 componentType must be UNSIGNED_BYTE or UNSIGNED_SHORT");
        int cSize = componentSize(component);
        Slice slice = validateSliceBounds(accessor, views, buffers, cSize * 4);
        quota.charge(Math.multiplyExact((long) expected, 16L));
        int[] out = new int[Math.multiplyExact(expected, 4)];
        ByteBuffer in = ByteBuffer.wrap(slice.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expected; i++) {
            int base = Math.toIntExact(slice.absolute() + (long) i * slice.stride());
            for (int slot = 0; slot < 4; slot++) {
                out[i * 4 + slot] = component == UNSIGNED_BYTE
                        ? Byte.toUnsignedInt(in.get(base + slot))
                        : Short.toUnsignedInt(in.getShort(base + slot * 2));
            }
        }
        return out;
    }

    static float[] readWeights(JsonObject accessor, List<JsonValue> views, List<byte[]> buffers,
                               BoundedJson.Quota quota, int expected) throws IOException {
        GltfModelImporter.require("VEC4".equals(GltfModelImporter.string(accessor.get("type"), "WEIGHTS_0.type"))
                && GltfModelImporter.integer(accessor.get("count"), "WEIGHTS_0.count") == expected,
                "WEIGHTS_0 must be matching VEC4");
        int component = GltfModelImporter.integer(accessor.get("componentType"), "WEIGHTS_0.componentType");
        boolean normalized = isNormalized(accessor);
        GltfModelImporter.require(component == FLOAT && !normalized
                || (component == UNSIGNED_BYTE || component == UNSIGNED_SHORT) && normalized,
                "Unsupported WEIGHTS_0 component type");
        int cSize = componentSize(component);
        Slice slice = validateSliceBounds(accessor, views, buffers, cSize * 4);
        quota.charge(Math.multiplyExact((long) expected, 16L));
        float[] out = new float[Math.multiplyExact(expected, 4)];
        ByteBuffer in = ByteBuffer.wrap(slice.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expected; i++) {
            int base = Math.toIntExact(slice.absolute() + (long) i * slice.stride());
            for (int slot = 0; slot < 4; slot++) {
                float w = switch (component) {
                    case FLOAT -> in.getFloat(base + slot * 4);
                    case UNSIGNED_BYTE -> Byte.toUnsignedInt(in.get(base + slot)) / 255.0f;
                    case UNSIGNED_SHORT -> Short.toUnsignedInt(in.getShort(base + slot * 2)) / 65535.0f;
                    default -> 0f;
                };
                GltfModelImporter.require(Float.isFinite(w) && w >= 0f, "Invalid skin weight");
                out[i * 4 + slot] = w;
            }
        }
        return out;
    }

    static float[] readMat4Array(JsonObject accessor, List<JsonValue> views, List<byte[]> buffers,
                                 BoundedJson.Quota quota, int expectedCount) throws IOException {
        requireUnnormalized(accessor);
        GltfModelImporter.require(GltfModelImporter.integer(accessor.get("componentType"), "componentType") == FLOAT
                && "MAT4".equals(GltfModelImporter.string(accessor.get("type"), "accessor.type"))
                && GltfModelImporter.integer(accessor.get("count"), "accessor.count") == expectedCount,
                "inverseBindMatrices must be matching float MAT4");
        Slice slice = validateSliceBounds(accessor, views, buffers, 64);
        quota.charge(Math.multiplyExact((long) expectedCount, 64L));
        float[] out = new float[Math.multiplyExact(expectedCount, 16)];
        ByteBuffer in = ByteBuffer.wrap(slice.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < expectedCount; i++) {
            int base = Math.toIntExact(slice.absolute() + (long) i * slice.stride());
            for (int k = 0; k < 16; k++) {
                float v = in.getFloat(base + k * 4);
                GltfModelImporter.require(Float.isFinite(v), "Non-finite inverse bind matrix");
                out[i * 16 + k] = v;
            }
        }
        return out;
    }

    static float[] readFloats(JsonObject accessor, List<JsonValue> views, List<byte[]> buffers,
                              BoundedJson.Quota quota, String expectedType, int lanes) throws IOException {
        int component = GltfModelImporter.integer(accessor.get("componentType"), "componentType");
        boolean normalized = isNormalized(accessor);
        GltfModelImporter.require(expectedType.equals(GltfModelImporter.string(accessor.get("type"), "accessor.type")),
                "Unexpected animation accessor type");
        GltfModelImporter.require(component == FLOAT && !normalized
                || (component == BYTE || component == UNSIGNED_BYTE || component == SHORT || component == UNSIGNED_SHORT) && normalized,
                "Unsupported animation accessor component type");
        int count = GltfModelImporter.integer(accessor.get("count"), "accessor.count");
        GltfModelImporter.require(count > 0 && count <= quota.policy.maxCollectionEntries(), "Invalid accessor count");
        int cSize = componentSize(component);
        Slice slice = validateSliceBounds(accessor, views, buffers, cSize * lanes);
        quota.charge(Math.multiplyExact((long) count * lanes, 4L));
        float[] out = new float[Math.multiplyExact(count, lanes)];
        ByteBuffer in = ByteBuffer.wrap(slice.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int base = Math.toIntExact(slice.absolute() + (long) i * slice.stride());
            for (int lane = 0; lane < lanes; lane++) {
                float v = switch (component) {
                    case FLOAT -> in.getFloat(base + lane * 4);
                    case BYTE -> Math.max(-1.0f, in.get(base + lane) / 127.0f);
                    case UNSIGNED_BYTE -> Byte.toUnsignedInt(in.get(base + lane)) / 255.0f;
                    case SHORT -> Math.max(-1.0f, in.getShort(base + lane * 2) / 32767.0f);
                    case UNSIGNED_SHORT -> Short.toUnsignedInt(in.getShort(base + lane * 2)) / 65535.0f;
                    default -> throw new IOException("Unsupported component type");
                };
                GltfModelImporter.require(Float.isFinite(v), "Non-finite accessor value");
                out[i * lanes + lane] = v;
            }
        }
        return out;
    }

    static int[] readIndices(JsonObject accessor, List<JsonValue> views, List<byte[]> buffers,
                             BoundedJson.Quota quota, int vertices) throws IOException {
        requireUnnormalized(accessor);
        GltfModelImporter.require("SCALAR".equals(GltfModelImporter.string(accessor.get("type"), "indices.type")),
                "Indices must be SCALAR");
        int component = GltfModelImporter.integer(accessor.get("componentType"), "indices.componentType");
        int size = switch (component) {
            case UNSIGNED_BYTE -> 1;
            case UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT -> 4;
            default -> throw new IOException("Unsupported index component type");
        };
        int count = GltfModelImporter.integer(accessor.get("count"), "indices.count");
        GltfModelImporter.require(count > 0 && count <= quota.policy.maxCollectionEntries(), "Invalid index count");
        Slice slice = validateSliceBounds(accessor, views, buffers, size);
        quota.charge(Math.multiplyExact((long) count, size));
        ByteBuffer in = ByteBuffer.wrap(slice.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int pos = Math.toIntExact(slice.absolute() + (long) i * slice.stride());
            long value = switch (size) {
                case 1 -> Byte.toUnsignedLong(in.get(pos));
                case 2 -> Short.toUnsignedLong(in.getShort(pos));
                default -> Integer.toUnsignedLong(in.getInt(pos));
            };
            GltfModelImporter.require(value < vertices, "Index outside POSITION accessor");
            out[i] = (int) value;
        }
        return out;
    }

    static int componentSize(int componentType) throws IOException {
        return switch (componentType) {
            case BYTE, UNSIGNED_BYTE -> 1;
            case SHORT, UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT, FLOAT -> 4;
            default -> throw new IOException("Unsupported accessor component type");
        };
    }

    static int componentMultiplier(String type) throws IOException {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new IOException("Unsupported accessor type");
        };
    }

    private static boolean isNormalized(JsonObject accessor) {
        return accessor.get("normalized") instanceof JsonBoolean b && b.value();
    }

    static void requireUnnormalized(JsonObject accessor) throws IOException {
        GltfModelImporter.require(!isNormalized(accessor), "Normalized accessors are not supported for this attribute");
    }

    record Slice(byte[] bytes, int count, long absolute, int stride) {}

    static Slice validateSliceBounds(JsonObject accessor, List<JsonValue> views,
                                     List<byte[]> buffers, int element) throws IOException {
        int viewIndex = GltfModelImporter.integer(accessor.get("bufferView"), "accessor.bufferView");
        GltfModelImporter.require(viewIndex >= 0 && viewIndex < views.size(), "Accessor bufferView out of range");
        var view = GltfModelImporter.obj(views.get(viewIndex), "bufferView");
        int bufferIndex = GltfModelImporter.integer(GltfModelImporter.optional(view.get("buffer"), 0), "bufferView.buffer");
        GltfModelImporter.require(bufferIndex >= 0 && bufferIndex < buffers.size(), "bufferView buffer out of range");
        int count = GltfModelImporter.integer(accessor.get("count"), "accessor.count");
        int offset = GltfModelImporter.integer(GltfModelImporter.optional(accessor.get("byteOffset"), 0), "accessor.byteOffset");
        int viewOffset = GltfModelImporter.integer(GltfModelImporter.optional(view.get("byteOffset"), 0), "bufferView.byteOffset");
        int viewLength = GltfModelImporter.integer(view.get("byteLength"), "bufferView.byteLength");
        int stride = view.get("byteStride") == null ? element : GltfModelImporter.integer(view.get("byteStride"), "bufferView.byteStride");
        GltfModelImporter.require(stride >= element, "bufferView.byteStride smaller than element size");
        int align = Math.min(4, element);
        GltfModelImporter.require(offset % align == 0 && viewOffset % 4 == 0, "Misaligned accessor");
        long end = count == 0 ? offset : Math.addExact((long) offset + Math.multiplyExact((long) count - 1, stride), element);
        GltfModelImporter.require(offset >= 0 && end <= viewLength, "Accessor exceeds bufferView");
        long absolute = (long) viewOffset + offset;
        long viewEnd = (long) viewOffset + viewLength;
        byte[] bytes = buffers.get(bufferIndex);
        GltfModelImporter.require(viewOffset >= 0 && viewEnd <= bytes.length && absolute >= 0, "bufferView exceeds buffer");
        return new Slice(bytes, count, absolute, stride);
    }
}
