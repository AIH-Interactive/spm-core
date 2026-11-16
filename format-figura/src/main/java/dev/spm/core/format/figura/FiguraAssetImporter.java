package dev.spm.core.format.figura;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.bbmodel.BbmodelJsonImporter;
import dev.spm.core.format.common.BoundedJson;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Independent Figura avatar archive/NBT importer (models, animations, embedded textures, .lua scripts, .ogg audio). */
public final class FiguraAssetImporter implements ModelFormatAdapter {
    @Override public String formatId() { return "figura"; }

    @Override public boolean supports(FormatHint hint) {
        return hint == FormatHint.FIGURA || hint == FormatHint.AUTO;
    }

    @Override public boolean accepts(FormatHint hint, ResourceKey entry, ByteBuffer prefix) {
        int p = prefix.position();
        if (prefix.remaining() < 2) return false;
        boolean zipMagic = prefix.remaining() >= 4
                && prefix.get(p) == 'P' && prefix.get(p + 1) == 'K'
                && prefix.get(p + 2) == 3 && prefix.get(p + 3) == 4;
        boolean gzipMagic = (prefix.get(p) & 0xFF) == 0x1F && (prefix.get(p + 1) & 0xFF) == 0x8B;
        boolean nbtRoot = prefix.get(p) == 0x0A && prefix.get(p + 1) == 0x00;
        if (hint == FormatHint.FIGURA) return zipMagic || gzipMagic || nbtRoot;
        if (hint == FormatHint.AUTO) {
            String path = entry.path().toLowerCase(Locale.ROOT);
            if (zipMagic && (path.endsWith(".figura.zip") || path.endsWith(".figura"))) return true;
            return (gzipMagic || nbtRoot) && path.endsWith(".nbt");
        }
        return false;
    }

    @Override public ImportResult importModel(ImportRequest request) throws ModelImportException {
        var quota = new BoundedJson.Quota(request.policy(), request.budget(), request.cancellation());
        var partial = new ArrayList<ModelAsset>();
        Domain domain = null;
        List<ResourceBudget.Reservation> owned = null;
        try {
            request.cancellation().check();
            byte[] inputBytes;
            try (InputStream in = request.source().resolver().open(request.source().entry())) {
                if (in == null) throw new IOException("Resolver returned null stream");
                inputBytes = read(in, request.policy().maxInputBytes(), ImportErrorCode.INPUT_LIMIT, request, quota);
            } catch (IOException sourceFailure) {
                if (sourceFailure instanceof DecodeLimitException || budgetFailure(sourceFailure)) throw sourceFailure;
                throw new SourceFailure(sourceFailure);
            }

            if (isStandaloneNbt(inputBytes)) {
                var files = new LinkedHashMap<ResourceKey, byte[]>();
                var decoded = FiguraNbtDecoder.decode(NbtReader.readRootCompound(inputBytes, quota),
                        request.source().identity(), quota, files);
                request.cancellation().check();
                owned = quota.transfer();
                domain = new Domain(files, owned);
                var models = new ArrayList<ImportResult.Model>();
                Domain retained = domain;
                for (int i = 0; i < decoded.documents().size(); i++) {
                    retained.retain();
                    models.add(new ImportResult.Model(decoded.modelKeys().get(i),
                            new ModelAsset(decoded.documents().get(i), List.of(), retained::release)));
                }
                return new ImportResult(models, List.of(), ResourceCatalog.of(files.keySet(), retained::open),
                        retained::closeRoot);
            }

            var centralNames = validateZip(inputBytes, request.policy());
            var files = new LinkedHashMap<ResourceKey, byte[]>();
            var names = new HashSet<String>();
            var exactNames = new HashSet<String>();
            int entries = 0;
            try (var in = new ZipInputStream(new ByteArrayInputStream(inputBytes))) {
                java.util.zip.ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    request.cancellation().check();
                    if (++entries > request.policy().maxEntries()) {
                        throw new DecodeLimitException(ImportErrorCode.RESOURCE_LIMIT, "Figura ZIP entry limit");
                    }
                    String name = entry.getName();
                    if (entry.isDirectory()) name = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    if (name.indexOf('%') >= 0) throw new IOException("Encoded Figura paths are not supported");
                    var key = new ResourceKey(name);
                    String folded = name.toLowerCase(Locale.ROOT);
                    if (!names.add(folded)) throw new IOException("Duplicate or case-colliding Figura path");
                    exactNames.add(name);
                    if (entry.isDirectory()) {
                        ZipInfo directory = centralNames.get(name);
                        if (directory == null || directory.size() != 0) {
                            throw new IOException("Figura directory entry contains payload data");
                        }
                        read(in, 0, ImportErrorCode.EXPANDED_LIMIT, request, quota);
                        in.closeEntry();
                        continue;
                    }
                    if (folded.endsWith(".zip") || folded.endsWith(".jar") || folded.endsWith(".class") || folded.endsWith(".exe")) {
                        throw new Unsupported("Nested archive or native binary is unsupported in Figura archive: " + name);
                    }
                    if (!name.equals("avatar.json") && !name.equals("avatar.nbt")
                            && !folded.endsWith(".bbmodel") && !folded.endsWith(".png")
                            && !folded.endsWith(".lua") && !folded.endsWith(".ogg")
                            && !folded.endsWith(".json") && !folded.endsWith(".txt")) {
                        throw new Unsupported("Unsupported Figura asset: " + name);
                    }
                    byte[] content = read(in, request.policy().maxExpandedBytes(), ImportErrorCode.EXPANDED_LIMIT, request, quota);
                    ZipInfo expected = centralNames.get(name);
                    if (expected == null || entry.getCrc() != expected.crc()
                            || entry.getSize() != expected.size() || entry.getMethod() != expected.method()) {
                        throw new IOException("Figura ZIP local and central metadata disagree");
                    }
                    files.put(key, content);
                    in.closeEntry();
                }
            }
            if (!exactNames.equals(centralNames.keySet())) {
                throw new IOException("Figura ZIP directory differs from local entries");
            }

            byte[] avatarJsonBytes = files.get(new ResourceKey("avatar.json"));
            byte[] avatarNbtBytes = files.get(new ResourceKey("avatar.nbt"));
            if (avatarJsonBytes == null && avatarNbtBytes == null) {
                throw new IOException("Figura avatar.json or avatar.nbt root marker is missing");
            }

            JsonObject avatarMeta = null;
            if (avatarJsonBytes != null && avatarJsonBytes.length > 0) {
                avatarMeta = BoundedJson.read(avatarJsonBytes, quota);
            }

            var keys = new ArrayList<String>();
            var embeddedResources = new LinkedHashMap<ResourceKey, byte[]>();
            for (var entry : files.entrySet()) {
                if (!entry.getKey().path().toLowerCase(Locale.ROOT).endsWith(".bbmodel")) continue;
                var project = BoundedJson.read(entry.getValue(), quota);
                var embeddedByIndex = new LinkedHashMap<Integer, ResourceKey>();
                var textures = project.get("textures");
                if (textures instanceof JsonArray list) {
                    int idx = 0;
                    for (var value : list.values()) {
                        if (!(value instanceof JsonObject texture)) throw new IOException("Invalid texture descriptor");
                        for (String field : List.of("path", "relative_path")) {
                            if (texture.get(field) != null) {
                                if (!(texture.get(field) instanceof JsonString path)) throw new IOException("Invalid texture path");
                                var textureKey = new ResourceKey(path.value());
                                if (path.value().contains("%") || !files.containsKey(textureKey)) {
                                    throw new IOException("Figura external texture is outside the archive");
                                }
                            }
                        }
                        if (texture.get("source") != null && texture.get("relative_path") == null && texture.get("path") == null) {
                            if (!(texture.get("source") instanceof JsonString src)) {
                                throw new IOException("Invalid embedded texture source");
                            }
                            byte[] png = decodeDataImage(src.value(), quota);
                            var embKey = new ResourceKey("embedded/" + entry.getKey().path().replace('/', '_') + "_" + idx + ".png");
                            embeddedResources.put(embKey, png);
                            embeddedByIndex.put(idx, embKey);
                        }
                        idx++;
                    }
                }
                var asset = BbmodelJsonImporter.importAsset(entry.getValue(), request.policy(), request.budget(), request.cancellation());
                ModelDocument doc = attachEmbeddedAndAvatarMeta(asset.document(), avatarMeta, embeddedByIndex);
                partial.add(new ModelAsset(doc, asset.skinningRig().orElse(null), asset.defaultSkinState(),
                        List.of(), asset::close));
                keys.add(entry.getKey().path());
            }
            files.putAll(embeddedResources);

            if (partial.isEmpty() && avatarNbtBytes != null) {
                var decoded = FiguraNbtDecoder.decode(NbtReader.readRootCompound(avatarNbtBytes, quota),
                        request.source().identity(), quota, files);
                for (int i = 0; i < decoded.documents().size(); i++) {
                    ModelDocument doc = avatarMeta == null ? decoded.documents().get(i)
                            : new ModelDocument(decoded.documents().get(i).identity(), decoded.documents().get(i).sourceFormat(),
                            decoded.documents().get(i).skeleton(), decoded.documents().get(i).meshes(),
                            decoded.documents().get(i).bindPose(), decoded.documents().get(i).materials(),
                            decoded.documents().get(i).locators(),
                            decoded.documents().get(i).metadata().with("figuraAvatar", avatarMeta));
                    partial.add(new ModelAsset(doc, List.of()));
                    keys.add(decoded.modelKeys().get(i));
                }
            }
            if (partial.isEmpty() && !hasLuaScript(files)) {
                throw new IOException("Figura ZIP has no supported model or script");
            }
            if (partial.isEmpty()) {
                // Script-only Figura avatar with no geometry cubes: synthesize an empty root document.
                var skel = new Skeleton(List.of("root"), new int[] {-1}, new float[3]);
                var meta = avatarMeta == null ? new JsonObject(Map.of()) : new JsonObject(Map.of("figuraAvatar", avatarMeta));
                var doc = new ModelDocument(request.source().identity(), "figura", skel, List.of(),
                        BonePose.identity(1), List.of(new Material("figura:empty", 16, 16, List.of())),
                        List.of(), meta);
                partial.add(new ModelAsset(doc, List.of()));
                keys.add("avatar");
            }

            request.cancellation().check();
            owned = quota.transfer();
            domain = new Domain(files, owned);
            var models = new ArrayList<ImportResult.Model>();
            Domain retained = domain;
            for (int i = 0; i < partial.size(); i++) {
                ModelAsset inner = partial.get(i);
                retained.retain();
                models.add(new ImportResult.Model(keys.get(i),
                        new ModelAsset(inner.document(), inner.skinningRig().orElse(null), inner.defaultSkinState(),
                                List.of(), () -> {
                            try { inner.close(); } finally { retained.release(); }
                        })));
            }
            return new ImportResult(models, List.of(), ResourceCatalog.of(files.keySet(), retained::open),
                    retained::closeRoot);
        } catch (Exception | Error failure) {
            partial.forEach(ModelAsset::close);
            if (domain != null) domain.abort();
            else if (owned != null) owned.forEach(ResourceBudget.Reservation::close);
            else quota.close();
            if (failure instanceof Error fatal) throw fatal;
            ImportErrorCode code = failure instanceof java.util.concurrent.CancellationException ? ImportErrorCode.CANCELLED
                    : failure instanceof DecodeLimitException limit ? limit.code()
                    : failure instanceof SourceFailure ? ImportErrorCode.SOURCE_IO
                    : failure instanceof Unsupported ? ImportErrorCode.UNSUPPORTED_FEATURE
                    : budgetFailure(failure) ? ImportErrorCode.BUDGET_EXCEEDED : ImportErrorCode.MALFORMED_INPUT;
            throw new ModelImportException(new ImportDiagnostic(
                    ImportDiagnostic.Severity.ERROR, code, request.source().entry(), -1, String.valueOf(failure.getMessage())),
                    failure);
        }
    }

    private static ModelDocument attachEmbeddedAndAvatarMeta(ModelDocument doc, JsonObject avatarMeta,
                                                             Map<Integer, ResourceKey> embeddedByIndex) {
        if (avatarMeta == null && embeddedByIndex.isEmpty()) return doc;
        List<Material> materials = doc.materials();
        if (!embeddedByIndex.isEmpty()) {
            var updatedMats = new ArrayList<Material>(materials.size());
            for (int i = 0; i < materials.size(); i++) {
                Material m = materials.get(i);
                // Material index 0 is bbmodel:untextured, textures follow at i = idx + 1.
                ResourceKey emb = i > 0 ? embeddedByIndex.get(i - 1) : null;
                if (emb != null && m.resources().isEmpty()) {
                    updatedMats.add(new Material(m.name(), m.textureWidth(), m.textureHeight(), List.of(emb)));
                } else {
                    updatedMats.add(m);
                }
            }
            materials = updatedMats;
        }
        JsonObject meta = avatarMeta == null ? doc.metadata() : doc.metadata().with("figuraAvatar", avatarMeta);
        return new ModelDocument(doc.identity(), doc.sourceFormat(), doc.skeleton(), doc.meshes(),
                doc.bindPose(), materials, doc.locators(), meta);
    }

    private static byte[] decodeDataImage(String uri, BoundedJson.Quota quota) throws IOException {
        if (!uri.startsWith("data:image/")) throw new IOException("Unsupported embedded texture URI");
        int comma = uri.indexOf(',');
        if (comma < 0 || !uri.substring(0, comma).contains(";base64")) {
            throw new IOException("Embedded texture must use base64 data URI");
        }
        byte[] decoded = Base64.getDecoder().decode(uri.substring(comma + 1));
        if (decoded.length > quota.policy.maxExpandedBytes()) {
            throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT, "Embedded texture exceeds limit");
        }
        quota.charge(decoded.length);
        return decoded;
    }

    private static boolean hasLuaScript(Map<ResourceKey, byte[]> files) {
        for (ResourceKey k : files.keySet()) {
            if (k.path().toLowerCase(Locale.ROOT).endsWith(".lua")) return true;
        }
        return false;
    }

    private static boolean isStandaloneNbt(byte[] bytes) {
        if (bytes.length < 2) return false;
        return ((bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B)
                || (bytes[0] == 0x0A && bytes[1] == 0x00);
    }

    private record ZipInfo(long crc, long size, int method) {}

    private static Map<String, ZipInfo> validateZip(byte[] bytes, DecodePolicy policy) throws IOException {
        var in = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int p = bytes.length - 22; p >= Math.max(0, bytes.length - 65557); p--) {
            if (in.getInt(p) == 0x06054b50 && p + 22 + Short.toUnsignedInt(in.getShort(p + 20)) == bytes.length) {
                end = p;
                break;
            }
        }
        if (end < 0) throw new IOException("Missing/truncated Figura ZIP central directory");
        int count = Short.toUnsignedInt(in.getShort(end + 10));
        long size = Integer.toUnsignedLong(in.getInt(end + 12));
        long offset = Integer.toUnsignedLong(in.getInt(end + 16));
        if (in.getShort(end + 4) != 0 || in.getShort(end + 6) != 0
                || Short.toUnsignedInt(in.getShort(end + 8)) != count || count == 65535
                || offset + size != end || count > policy.maxEntries()) {
            throw new IOException("Unsupported Figura ZIP directory");
        }
        int p = Math.toIntExact(offset);
        var names = new HashMap<String, ZipInfo>();
        var foldedNames = new HashSet<String>();
        for (int i = 0; i < count; i++) {
            if (p > end - 46 || in.getInt(p) != 0x02014b50) {
                throw new IOException("Invalid Figura ZIP directory entry");
            }
            int length = Short.toUnsignedInt(in.getShort(p + 28));
            int extra = Short.toUnsignedInt(in.getShort(p + 30));
            int comment = Short.toUnsignedInt(in.getShort(p + 32));
            if (length > policy.maxStringBytes() || (long) p + 46 + length + extra + comment > end
                    || (in.getShort(p + 8) & 1) != 0) {
                throw new IOException("Invalid/encrypted Figura ZIP directory entry");
            }
            String name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, p + 46, length)).toString();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.contains("%")) throw new IOException("Encoded Figura paths are not supported");
            name = new ResourceKey(name).path();
            if (!foldedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Duplicate Figura directory path");
            }
            names.put(name, new ZipInfo(
                    Integer.toUnsignedLong(in.getInt(p + 16)),
                    Integer.toUnsignedLong(in.getInt(p + 24)),
                    Short.toUnsignedInt(in.getShort(p + 10))));
            p += 46 + length + extra + comment;
        }
        if (p != end) throw new IOException("Unexpected Figura ZIP directory data");
        return names;
    }

    private static byte[] read(InputStream in, long limit, ImportErrorCode code, ImportRequest request,
                               BoundedJson.Quota quota) throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int count;
            long total = 0;
            while ((count = in.read(block)) != -1) {
                request.cancellation().check();
                total = Math.addExact(total, count);
                if (total > limit) throw new DecodeLimitException(code, "Figura byte limit");
                quota.charge(Math.multiplyExact(2L, count));
                out.write(block, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static boolean budgetFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ResourceBudgetExceededException) return true;
        }
        return false;
    }

    private static final class Unsupported extends IOException {
        private static final long serialVersionUID = 1L;
        Unsupported(String message) { super(message); }
    }

    private static final class SourceFailure extends IOException {
        private static final long serialVersionUID = 1L;
        SourceFailure(IOException cause) { super("Figura source could not be read", cause); }
    }

    private static final class Domain {
        private Map<ResourceKey, byte[]> files;
        private final List<ResourceBudget.Reservation> tickets;
        private int refs = 1;
        private boolean rootClosed;

        Domain(Map<ResourceKey, byte[]> files, List<ResourceBudget.Reservation> tickets) {
            this.files = Map.copyOf(files);
            this.tickets = tickets;
        }

        synchronized void retain() {
            if (refs == 0 || refs == Integer.MAX_VALUE) {
                throw new IllegalStateException("Figura domain closed or lease capacity exceeded");
            }
            refs++;
        }

        synchronized void release() {
            if (refs > 0 && --refs == 0) {
                files = Map.of();
                tickets.forEach(ResourceBudget.Reservation::close);
            }
        }

        synchronized void closeRoot() {
            if (!rootClosed) {
                rootClosed = true;
                release();
            }
        }

        synchronized void abort() {
            rootClosed = true;
            refs = 0;
            files = Map.of();
            tickets.forEach(ResourceBudget.Reservation::close);
        }

        synchronized InputStream open(ResourceKey key) throws IOException {
            if (rootClosed) throw new IOException("Figura import closed");
            byte[] bytes = files.get(key);
            if (bytes == null) throw new IOException("Unknown Figura resource: " + key.path());
            retain();
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                private final java.util.concurrent.atomic.AtomicBoolean returned = new java.util.concurrent.atomic.AtomicBoolean();
                @Override public void close() throws IOException {
                    if (returned.compareAndSet(false, true)) {
                        try { super.close(); } finally {
                            in = InputStream.nullInputStream();
                            release();
                        }
                    }
                }
            };
        }
    }
}
