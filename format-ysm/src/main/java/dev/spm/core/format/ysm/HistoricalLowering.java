package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.io.IOException;
import java.util.*;
import static dev.spm.core.format.ysm.HistoricalValue.*;

/** Allowed-source compiled quads are already render units; pivots alone use model units. */
final class HistoricalLowering {
    /**
     * Retained size of one array, derived from the count that actually gets allocated.
     *
     * <p>Charging a guessed per-quad constant is what made this budget wrong before: the previous
     * code charged 640 bytes per quad where the arrays genuinely total 152, a 4x over-count that
     * rejected models that would have fit. The figures below are computed from the same counts the
     * allocation expressions use, so they cannot drift apart again.
     *
     * @param count    elements allocated
     * @param element  bytes per element: 4 for {@code float}/{@code int}, 1 for {@code boolean}
     */
    private static long arrayBytes(long count, long element) {
        // Header plus payload, padded to the 8-byte alignment every HotSpot object honours.
        return (16L + count * element + 7L) & ~7L;
    }
    /** Bytes per retained {@link ModelDocument.Face}: header, three ints, a String ref, a boolean. */
    private static final long FACE_BYTES = 40L;
    /** Bytes per bone name: the reference in the name list. */
    private static final long BONE_NAME_REF = 4L;
    private final YsmHistoricalModel historical;
    private final DecodePolicy policy;
    private final ResourceBudget budget;
    private final CancellationToken token;
    private final List<ResourceBudget.Reservation> reservations=new ArrayList<>();
    private final Map<ResourceKey,byte[]> resources=new LinkedHashMap<>();
    private final List<YsmImportResult.Variant> variants=new ArrayList<>();
    // AST identity, not deep equals/hashCode: all player variants borrow the same immutable files.
    private final IdentityHashMap<HistoricalValue,IdentityHashMap<HistoricalValue,ModelDocument.JsonObject>> sceneMetadata=new IdentityHashMap<>();
    /**
     * Set when the decoder streamed animation clips into the projection instead of retaining them.
     * {@code streamedShared} then holds the one shared projection every variant borrows.
     */
    private final HistoricalSceneProjection.Collector streamed;
    private ModelDocument.JsonObject streamedShared;
    private long retained;
    private int resourceIndex;
    private HistoricalLowering(YsmHistoricalModel historical,DecodePolicy policy,ResourceBudget budget,CancellationToken token){
        this.historical=Objects.requireNonNull(historical);this.policy=Objects.requireNonNull(policy);
        this.streamed=historical.takeAnimationCollector();
        this.budget=Objects.requireNonNull(budget);this.token=Objects.requireNonNull(token);
    }
    static YsmImportResult lower(YsmHistoricalModel model,DecodePolicy policy,ResourceBudget budget,CancellationToken token)throws IOException {
        var work=new HistoricalLowering(model,policy,budget,token);
        try {return work.run();}
        // Error is included so a failure the caller cannot even catch cannot leak the shared budget.
        catch(IOException|RuntimeException|Error failure){work.reservations.forEach(ResourceBudget.Reservation::close);throw failure;}
    }
    private YsmImportResult run()throws IOException {
        token.check();Struct root=historical.root();
        String identity=historical.innerVersion()<19?text(root.field("identity")):text(struct(root.field("info")).field("hash"));
        if(historical.innerVersion()<19){
            List<ModelDocument.Material> materials=textures(root.field("textures"),true);
            for(Entry entry:mapping(root.field("geos")).entries()){
                HistoricalValue geo=unwrap(entry.value());if(geo==null)continue;
                long kind=uint(entry.key());String key=kind==1?"main":kind==2?"arm":"arrow";
                variant(kind==3?"projectile":"player",key,identity,struct(geo),materials,root.field("animations"),root.fields().get("controllers"));
            }
        }else{
            HistoricalValue player=unwrap(root.field("player"));
            if(player!=null){
                Struct p=struct(player);List<ModelDocument.Material> materials=textures(p.field("textures"),false);
                for(Entry entry:mapping(p.field("geos")).entries()){
                    long kind=uint(entry.key());String key=kind==1?"main":kind==2?"arm":"geo-"+kind;
                    variant("player",key,identity,struct(unwrap(entry.value())),materials,p.field("animations"),p.field("controllers"));
                }
            }
            for(String field:List.of("vehicles","projectiles")){
                HistoricalValue value=root.fields().get(field);if(value==null)continue;
                if(value instanceof Mapping map){for(Entry entry:map.entries())replacement(field,text(entry.key()),identity,struct(entry.value()));}
                else {int i=0;for(HistoricalValue item:sequence(value).values())replacement(field,Integer.toString(i++),identity,struct(item));}
            }
        }
        token.check();
        // Every variant is lowered by now, and a ModelDocument never aliases a HistoricalValue: its
        // JsonValue is a sealed interface over JSON scalars. Retiring the tree here releases the
        // reservations it holds on the caller's shared budget, so an open import owns only what it
        // can actually hand out instead of carrying a multi-hundred-megabyte generic tree that no
        // caller can reach.
        historical.close();
        var result=new YsmImportResult(variants,resources,reservations);reservations.clear();return result;
    }
    private void replacement(String type,String key,String identity,Struct source)throws IOException {
        Struct texture=struct(source.field("texture"));
        List<ModelDocument.Material> materials=List.of(material(key,texture,false));
        variant(type,key,identity,struct(unwrap(source.field("geo"))),materials,source.fields().get("animation"),source.fields().get("controller"));
    }
    private void retain(long bytes)throws IOException {
        token.check();if(bytes<0||bytes>policy.maxExpandedBytes()-retained)throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT,"YSM projection expanded-byte limit");
        YsmArchiveCodec.reserve(budget,bytes,reservations);retained+=bytes;
    }
    private List<ModelDocument.Material> textures(HistoricalValue source,boolean legacy)throws IOException {
        var result=new ArrayList<ModelDocument.Material>();
        for(Entry entry:mapping(source).entries()){
            HistoricalValue texture=unwrap(entry.value());if(texture==null)continue;
            result.add(material(text(entry.key()),struct(texture),legacy));
        }
        return List.copyOf(result);
    }
    private ModelDocument.Material material(String name,Struct texture,boolean legacy)throws IOException {
        boolean directImage=legacy&&historical.innerVersion()<3;
        Struct uv=directImage?texture:struct(unwrap(texture.field("uv")));
        var keys=new ArrayList<ResourceKey>();keys.add(image(uv));
        if(!directImage){for(Entry entry:mapping(texture.field("pbr")).entries())keys.add(image(struct(unwrap(entry.value()))));}
        return new ModelDocument.Material(name,Math.toIntExact(uint(uv.field("width"))),Math.toIntExact(uint(uv.field("height"))),keys);
    }
    private ResourceKey image(Struct image)throws IOException {
        Binary binary=(Binary)image.field("bytes");retain(binary.size());
        long encoding=image.fields().containsKey("encoding")?uint(image.field("encoding")):1;
        String extension=switch((int)encoding){case 1->"rgba";case 2->"png";case 3->"jpg";case 4->"webp";case 5->"avif";default->throw new IOException("Unknown image encoding");};
        ResourceKey key=new ResourceKey("historical/images/"+(resourceIndex++)+"."+extension);
        resources.put(key,binary.value());return key;
    }
    private void variant(String type,String key,String identity,Struct geo,List<ModelDocument.Material> materials,HistoricalValue animations,HistoricalValue controllers)throws IOException {
        token.check();
        if(variants.size()>=policy.maxEntries())throw new IOException("YSM variant limit");
        String limitation="";
        for(HistoricalValue item:sequence(geo.field("bones")).values()){
            Struct bone=struct(item);
            for(String flag:List.of("dont_render","hidden","cubes_hidden","hide_children","reset")){
                if(((Bool)bone.field(flag)).value())limitation="Historical authored bone flags retained; runtime mapping requires allowed-source semantic evidence";
            }
        }
        if(materials.isEmpty())limitation="Geometry retained without a texture material; Host material selection required";
        Optional<ModelDocument> document=limitation.isEmpty()?Optional.of(document(identity,type,key,geo,materials,animations,controllers)):Optional.empty();
        // Only the lowered result is retained. The geo/animations/controllers subtrees stay reachable
        // through the tree until run() retires it, and no caller could ever observe them.
        variants.add(new YsmImportResult.Variant(type,key,document,limitation));
    }
    private ModelDocument document(String identity,String type,String key,Struct geo,List<ModelDocument.Material> materials,HistoricalValue animations,HistoricalValue controllers)throws IOException {
        List<HistoricalValue> bones=sequence(geo.field("bones")).values();int n=bones.size();
        long quads=0;for(HistoricalValue item:bones){Struct b=struct(item);quads+=quads(b).size();}
        if(quads>policy.maxCollectionEntries()||quads>Integer.MAX_VALUE/24)throw new DecodeLimitException(ImportErrorCode.COLLECTION_LIMIT,"YSM projected geometry limit");
        // Charge what is actually allocated just below, from the same counts. The vertex batch holds
        // three float arrays and an int array of 4*quads elements (positions, normals, uv, bones),
        // the index array holds 6*quads ints, and each quad also retains one Face record.
        long vertexCount=Math.multiplyExact(quads,4L);
        retain(Math.addExact(
                Math.addExact(
                        Math.addExact(arrayBytes(vertexCount,4L),arrayBytes(vertexCount,4L)),  // positions, normals
                        Math.addExact(arrayBytes(Math.multiplyExact(vertexCount,2L),4L),      // uvs
                                arrayBytes(vertexCount,4L))),                                 // vertexBones
                Math.addExact(arrayBytes(Math.multiplyExact(quads,6L),4L),                 // indices
                        Math.multiplyExact(quads,FACE_BYTES))));                            // faces
        // Skeleton keeps and clones parents/pivots and derives three int[n] arrays; BonePose keeps and
        // clones three float[3n], two boolean[n] and two int[n]. Both are sized by the bone count.
        long components=Math.multiplyExact(n,3L);
        retain(Math.addExact(
                Math.addExact(
                        Math.addExact(
                                Math.addExact(arrayBytes(n,4L),arrayBytes(n,4L)),        // parents + clone
                                Math.addExact(arrayBytes(components,4L),arrayBytes(components,4L))), // pivots + clone
                        Math.addExact(Math.addExact(arrayBytes(n,4L),arrayBytes(n,4L)),arrayBytes(n,4L))), // order/firstChild/next
                Math.addExact(
                        Math.addExact(
                                Math.addExact(arrayBytes(components,4L),arrayBytes(components,4L)),
                                Math.addExact(arrayBytes(components,4L),arrayBytes(components,4L))),
                        Math.addExact(
                                Math.addExact(arrayBytes(components,4L),arrayBytes(components,4L)),
                                Math.addExact(
                                        Math.addExact(
                                                Math.addExact(arrayBytes(n,1L),arrayBytes(n,1L)),
                                                Math.addExact(arrayBytes(n,1L),arrayBytes(n,1L))),
                                        Math.addExact(
                                                Math.addExact(arrayBytes(n,4L),arrayBytes(n,4L)),
                                                Math.multiplyExact(n,BONE_NAME_REF)))))));
        var names=new ArrayList<String>();var byName=new HashMap<String,Integer>();
        for(int i=0;i<n;i++){String name=text(struct(bones.get(i)).field("name"));if(byName.put(name,i)!=null)throw new IOException("Duplicate historical bone");names.add(name);}
        int[] parents=new int[n],glow=new int[n];float[] pivots=new float[n*3],rotation=new float[n*3],scale=new float[n*3];Arrays.fill(scale,1);Arrays.fill(glow,255);
        for(int i=0;i<n;i++){
            token.check();Struct bone=struct(bones.get(i));String parent=text(bone.field("parent"));
            if(parent.isEmpty())parents[i]=-1;else {Integer index=byName.get(parent);if(index==null)throw new IOException("Unknown historical bone parent");parents[i]=index;}
            float[] pivot=vector(bone.field("pivot")),r=vector(bone.field("rotation"));
            for(int axis=0;axis<3;axis++){pivots[i*3+axis]=pivot[axis]/16;rotation[i*3+axis]=r[axis];}
            if(names.get(i).startsWith("ysmGlow"))glow[i]=15;
        }
        Skeleton skeleton;try{skeleton=new Skeleton(names,parents,pivots);}catch(IllegalArgumentException bad){throw new IOException("Invalid historical skeleton",bad);}
        int vertices=Math.toIntExact(quads*4);float[] positions=new float[vertices*3],normals=new float[vertices*3],uvs=new float[vertices*2];int[] vertexBones=new int[vertices],indices=new int[Math.toIntExact(quads*6)];
        var faces=new ArrayList<ModelDocument.Face>();float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},max={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};int vertex=0,face=0;
        for(int i=0;i<n;i++){
            List<HistoricalValue> boneQuads=quads(struct(bones.get(i)));int cube=0;
            for(HistoricalValue item:boneQuads){
                token.check();Struct quad=struct(item);float[] normal=vector(quad.field("normal"));int start=vertex;
                List<HistoricalValue> points=sequence(quad.field("vertices")).values();if(points.size()!=4)throw new IOException("Historical quad vertex count");
                for(HistoricalValue value:points){Struct point=struct(value);float[] p=vector(point.field("position"));List<HistoricalValue> uv=sequence(point.field("uv")).values();if(uv.size()!=2)throw new IOException("Historical UV size");
                    for(int axis=0;axis<3;axis++){positions[vertex*3+axis]=p[axis];normals[vertex*3+axis]=normal[axis];min[axis]=Math.min(min[axis],p[axis]);max[axis]=Math.max(max[axis],p[axis]);}
                    uvs[vertex*2]=number(uv.get(0));uvs[vertex*2+1]=number(uv.get(1));vertexBones[vertex++]=i;
                }
                int[] triangle={start,start+1,start+2,start,start+2,start+3};System.arraycopy(triangle,0,indices,face*6,6);face++;
                faces.add(new ModelDocument.Face(i,cube++,start,"historical-quad",false));
            }
        }
        if(vertices==0){Arrays.fill(min,0);Arrays.fill(max,0);}
        var mesh=new ModelDocument.Mesh(key,new VertexBatch(positions,normals,vertexBones),uvs,indices,faces,new ModelDocument.Bounds(min[0],min[1],min[2],max[0],max[1],max[2]),0);
        var metadata=sceneMetadata(type,key,animations,controllers);
        var pose=new BonePose(rotation,new float[n*3],scale,new boolean[n],new boolean[n],glow);
        return new ModelDocument(identity,"ysm-v3-inner-"+historical.innerVersion(),skeleton,List.of(mesh),pose,materials,List.of(),metadata);
    }
    private ModelDocument.JsonObject sceneMetadata(String type,String key,HistoricalValue animations,HistoricalValue controllers)throws IOException {
        token.check();
        // Streaming path: the clips were projected during decode and are no longer tree nodes, so the
        // tree's animation field holds keys only. Build the shared scene projection once from the
        // streamed result and reuse it for every variant, exactly as the in-memory path does.
        if(streamed!=null&&streamedShared==null){
            streamedShared=streamed.finish(controllers);
            // The projected scene is retained by the import, not by the decoder, so its credit is
            // taken from the collector: the reader's reservations are long gone by now, and charging
            // through them reported a few megabytes for a set that actually holds hundreds.
            long projected=streamed.takeProjectedBytes();
            if(projected>0)retain(projected);
            retain(96);
        }
        if(streamedShared!=null)return HistoricalSceneProjection.forVariant(streamedShared,type,key,policy,token,this::retain);
        var byController=sceneMetadata.get(animations);
        if(byController==null) {
            retain(256);byController=new IdentityHashMap<>();sceneMetadata.put(animations,byController);
        }
        var shared=byController.get(controllers);
        if(shared==null) {
            retain(96);
            shared=HistoricalSceneProjection.shared(historical.innerVersion(),animations,controllers,policy,token,this::retain);
            byController.put(controllers,shared);
        }
        return HistoricalSceneProjection.forVariant(shared,type,key,policy,token,this::retain);
    }
    private List<HistoricalValue> quads(Struct bone)throws IOException {
        if(bone.fields().containsKey("quads"))return sequence(bone.field("quads")).values();
        var result=new ArrayList<HistoricalValue>();for(HistoricalValue cube:sequence(bone.field("cubes")).values())result.addAll(sequence(struct(cube).field("quads")).values());return result;
    }
    private static HistoricalValue unwrap(HistoricalValue value){
        if(value instanceof Presence p)return p.present()?unwrap(p.value()):null;
        if(value instanceof Struct s&&s.fields().containsKey("hash")&&s.fields().containsKey("value"))return unwrap(s.field("value"));
        return value;
    }
    private static Struct struct(HistoricalValue value)throws IOException {if(value instanceof Struct s)return s;throw new IOException("Historical structure expected");}
    private static Sequence sequence(HistoricalValue value)throws IOException {if(value instanceof Sequence s)return s;throw new IOException("Historical list expected");}
    private static Mapping mapping(HistoricalValue value)throws IOException {if(value instanceof Mapping m)return m;throw new IOException("Historical map expected");}
    private static String text(HistoricalValue value)throws IOException {if(value instanceof Text t)return t.value();throw new IOException("Historical text expected");}
    private static long uint(HistoricalValue value)throws IOException {if(value instanceof Unsigned u)return u.bits();throw new IOException("Historical integer expected");}
    private static float number(HistoricalValue value)throws IOException {if(value instanceof Float32 f&&Float.isFinite(f.value()))return f.value();throw new IOException("Nonfinite historical runtime geometry");}
    private static float[] vector(HistoricalValue value)throws IOException {List<HistoricalValue> values=sequence(value).values();if(values.size()!=3)throw new IOException("Historical vector size");return new float[]{number(values.get(0)),number(values.get(1)),number(values.get(2))};}
}
