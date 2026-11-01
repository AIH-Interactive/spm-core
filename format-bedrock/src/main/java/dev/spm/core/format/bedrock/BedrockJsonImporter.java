package dev.spm.core.format.bedrock;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.common.BoundedJson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded raw geometry import. Animation data is preserved as archival metadata. */
public final class BedrockJsonImporter {
    private BedrockJsonImporter() {}
    /**
     * Imports a model whose retained allocations are <em>not</em> kept charged: the construction quota
     * is released on return. Use {@link #importAsset} when the document will be cached or outlives the
     * call, otherwise repeated imports reset their own charge to zero.
     */
    public static ModelDocument importJson(byte[] bytes,DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation) throws IOException {
        Objects.requireNonNull(bytes); Objects.requireNonNull(policy); Objects.requireNonNull(budget); Objects.requireNonNull(cancellation);
        try (var quota=new BoundedJson.Quota(policy,budget,cancellation)) {
            return model(BoundedJson.read(bytes,quota),quota);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IOException("Invalid Bedrock model",exception);
        }
    }
    /**
     * Imports a model whose retained allocations stay charged to {@code budget} until the returned
     * asset is closed, so a host cache cannot consume unbounded aggregate memory.
     */
    public static ModelAsset importAsset(byte[] bytes,DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation) throws IOException {
        Objects.requireNonNull(bytes); Objects.requireNonNull(policy); Objects.requireNonNull(budget); Objects.requireNonNull(cancellation);
        try (var quota=new BoundedJson.Quota(policy,budget,cancellation)) {
            ModelDocument document;
            try { document=model(BoundedJson.read(bytes,quota),quota); }
            catch (IllegalArgumentException | ArithmeticException exception) { throw new IOException("Invalid Bedrock model",exception); }
            return new ModelAsset(document,quota.transfer());
        }
    }

    private static ModelDocument model(JsonObject root,BoundedJson.Quota quota) throws IOException {
        String version=string(required(root,"format_version"),"format_version");
        if (!Set.of("1.12.0","1.16.0").contains(version)) throw new IOException("Unsupported Bedrock format version: "+version);
        var geometries=array(required(root,"minecraft:geometry"),"minecraft:geometry").values();
        if (geometries.size()!=1) throw new IOException("Exactly one geometry is supported per import");
        var geometry=object(geometries.get(0),"geometry");
        var description=optionalObject(geometry,"description");
        String identity=string(required(description,"identifier"),"identifier");
        if (identity.isBlank()) throw new IOException("Missing geometry identifier");
        int width=dimension(description,"texture_width"),height=dimension(description,"texture_height");
        if (Math.multiplyExact(Math.multiplyExact((long)width,height),4)>quota.policy.maxExpandedBytes())
            throw new IOException("Texture dimensions exceed expanded limit");
        List<JsonValue> rawBones=array(required(geometry,"bones"),"bones").values();
        quota.charge(Math.multiplyExact((long)rawBones.size(),512));
        int n=rawBones.size();
        var names=new ArrayList<String>(n); var index=new LinkedHashMap<String,Integer>();
        var bones=new ArrayList<JsonObject>(n);
        for (JsonValue raw : rawBones) {
            quota.cancellation.check(); JsonObject bone=object(raw,"bone");
            String name=string(required(bone,"name"),"bone name");
            if (name.isBlank() || index.putIfAbsent(name,names.size())!=null) throw new IOException("Duplicate or blank bone name");
            names.add(name); bones.add(bone);
        }
        int[] parents=new int[n]; Arrays.fill(parents,-1);
        float[] pivots=new float[Math.multiplyExact(n,3)],rotations=new float[pivots.length];
        float[] translations=new float[pivots.length],scales=new float[pivots.length]; Arrays.fill(scales,1);
        boolean[] ownHidden=new boolean[n],childrenHidden=new boolean[n]; int[] glow=new int[n];
        var locators=new ArrayList<Locator>(); var mesh=new MeshBuilder(quota);
        for (int boneIndex=0;boneIndex<n;boneIndex++) {
            quota.cancellation.check(); var bone=bones.get(boneIndex);
            if (bone.values().containsKey("parent")) {
                String name=string(required(bone,"parent"),"parent"); Integer parent=index.get(name);
                if (parent==null) throw new IOException("Unknown bone parent: "+name);
                parents[boneIndex]=parent;
            }
            float[] pivot=vector(bone,"pivot",new float[3]),rotation=vector(bone,"rotation",new float[3]);
            for (int axis=0;axis<3;axis++) {
                pivots[boneIndex*3+axis]=finite(pivot[axis]*(axis==0?-1.0/16:1.0/16));
                rotations[boneIndex*3+axis]=finite(rotation[axis]*(Math.PI/180)*(axis==2?1:-1));
            }
            ownHidden[boneIndex]=bool(bone,"cubes_hidden",false);
            childrenHidden[boneIndex]=bool(bone,"children_hidden",false);
            glow[boneIndex]=names.get(boneIndex).startsWith("ysmGlow")?15:255;
            if (bone.values().containsKey("locators")) {
                var declared=object(required(bone,"locators"),"locators");
                for (var entry : declared.values().entrySet()) {
                    quota.charge(512); float[] offset,angles=new float[3]; boolean ignore=false;
                    if (entry.getValue() instanceof JsonArray) offset=vector(entry.getValue(),3,"locator");
                    else {
                        var locator=object(entry.getValue(),"locator"); offset=vector(locator,"offset",new float[3]);
                        angles=vector(locator,"rotation",new float[3]); ignore=bool(locator,"ignore_inherited_scale",false);
                    }
                    for (int axis=0;axis<3;axis++) {
                        offset[axis]=finite(offset[axis]*(axis==0?-1.0/16:1.0/16));
                        angles[axis]=finite(angles[axis]*(Math.PI/180)*(axis==2?1:-1));
                    }
                    locators.add(new Locator(entry.getKey(),boneIndex,offset,angles,ignore));
                }
            }
            if (bone.values().containsKey("cubes")) {
                float inflate=number(bone,"inflate",0); boolean mirror=bool(bone,"mirror",false);
                var cubes=array(required(bone,"cubes"),"cubes").values();
                for (int cubeIndex=0;cubeIndex<cubes.size();cubeIndex++)
                    cube(mesh,object(cubes.get(cubeIndex),"cube"),boneIndex,cubeIndex,inflate,mirror,width,height);
            }
        }
        var skeleton=new Skeleton(names,parents,pivots);
        var bindPose=new BonePose(rotations,translations,scales,ownHidden,childrenHidden,glow);
        var resources=new ArrayList<ResourceKey>();
        if (root.values().containsKey("textures")) {
            for (JsonValue resource : object(required(root,"textures"),"textures").values().values())
                resources.add(new ResourceKey(string(resource,"texture reference")));
        }
        quota.cancellation.check();
        var material=new Material("bedrock:default",width,height,resources);
        return new ModelDocument(identity,"bedrock-json",skeleton,List.of(mesh.finish(identity)),bindPose,List.of(material),locators,root);
    }

    private static final String[] DIRECTIONS={"west","east","north","south","up","down"};
    private static final int[][] CORNERS={{3,2,0,1},{6,7,5,4},{2,6,4,0},{7,3,1,5},{3,7,6,2},{0,4,5,1}};
    private static final float[][] NORMALS={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};

    private static void cube(MeshBuilder mesh,JsonObject cube,int bone,int cubeIndex,float boneInflate,boolean boneMirror,int width,int height) throws IOException {
        mesh.quota.cancellation.check();
        float[] origin=vector(cube,"origin",new float[3]),size=vector(cube,"size",new float[] {1,1,1});
        float inflate=number(cube,"inflate",boneInflate)/16;
        double[] min={-((double)origin[0]+size[0])/16-inflate,(double)origin[1]/16-inflate,(double)origin[2]/16-inflate};
        double[] max={-((double)origin[0]+size[0])/16+size[0]/16.0+inflate,(double)origin[1]/16+size[1]/16.0+inflate,(double)origin[2]/16+size[2]/16.0+inflate};
        float[][] corners=new float[8][3];
        for (int corner=0;corner<8;corner++) for (int axis=0;axis<3;axis++)
            corners[corner][axis]=finite((corner&(4>>axis))==0?min[axis]:max[axis]);
        float[] rotation=vector(cube,"rotation",new float[3]),pivot=vector(cube,"pivot",new float[3]);
        for (int axis=0;axis<3;axis++) {
            rotation[axis]=finite(rotation[axis]*(Math.PI/180)*(axis==2?1:-1));
            pivot[axis]=finite(pivot[axis]*(axis==0?-1.0/16:1.0/16));
        }
        for (float[] corner : corners) rotate(corner,rotation,pivot);
        boolean cubeMirror=bool(cube,"mirror",false),layout=boneMirror||cubeMirror;
        JsonValue uv=cube.values().get("uv");
        if (uv==null || uv==JsonNull.INSTANCE) return;
        if (uv instanceof JsonArray) {
            float[] box=vector(uv,2,"box UV");
            double x=Math.floor(size[0]),y=Math.floor(size[1]),z=Math.floor(size[2]),u=box[0],v=box[1];
            double[][] rectangles={{u+z+x,v+z,z,y},{u,v+z,z,y},{u+z,v+z,x,y},{u+z+x+z,v+z,x,y},{u+z,v,x,z},{u+z+x,v+z,x,-z}};
            for (int direction=0;direction<6;direction++) {
                int cornerDirection=layout&&direction<2?1-direction:direction;
                mesh.face(bone,cubeIndex,direction,corners[CORNER_INDEX(cornerDirection,0)],corners[CORNER_INDEX(cornerDirection,1)],
                        corners[CORNER_INDEX(cornerDirection,2)],corners[CORNER_INDEX(cornerDirection,3)],rectangles[direction],width,height,cubeMirror,layout,rotation);
            }
        } else {
            var faces=object(uv,"per-face UV");
            for (String direction : faces.values().keySet()) if (!Arrays.asList(DIRECTIONS).contains(direction)) throw new IOException("Unknown UV face: "+direction);
            for (int direction=0;direction<6;direction++) {
                JsonValue faceValue=faces.values().get(DIRECTIONS[direction]); if (faceValue==null) continue;
                var face=object(faceValue,"face UV");
                float[] start=vector(required(face,"uv"),2,"face UV"),extent=vector(required(face,"uv_size"),2,"face UV size");
                int cornerDirection=layout&&direction<2?1-direction:layout&&direction>=4?9-direction:direction;
                mesh.face(bone,cubeIndex,direction,corners[CORNER_INDEX(cornerDirection,0)],corners[CORNER_INDEX(cornerDirection,1)],
                        corners[CORNER_INDEX(cornerDirection,2)],corners[CORNER_INDEX(cornerDirection,3)],
                        new double[] {start[0],start[1],extent[0],extent[1]},width,height,cubeMirror,layout,rotation);
            }
        }
    }

    private static int CORNER_INDEX(int direction,int vertex) { return CORNERS[direction][vertex]; }

    private static void rotate(float[] point,float[] angles,float[] pivot) throws IOException {
        double x=(double)point[0]-pivot[0],y=(double)point[1]-pivot[1],z=(double)point[2]-pivot[2];
        double cx=Math.cos(angles[0]),sx=Math.sin(angles[0]),cy=Math.cos(angles[1]),sy=Math.sin(angles[1]);
        double cz=Math.cos(angles[2]),sz=Math.sin(angles[2]);
        double yy=y*cx-z*sx,zz=y*sx+z*cx,xx=x*cy+zz*sy;
        double finalZ=-x*sy+zz*cy;
        point[0]=finite(xx*cz-yy*sz+pivot[0]); point[1]=finite(xx*sz+yy*cz+pivot[1]); point[2]=finite(finalZ+pivot[2]);
    }

    private static final class MeshBuilder {
        final BoundedJson.Quota quota;
        final List<Float> positions=new ArrayList<>(),normals=new ArrayList<>(),uvs=new ArrayList<>();
        final List<Integer> boneIndices=new ArrayList<>(),indices=new ArrayList<>();
        final List<Face> faces=new ArrayList<>();
        MeshBuilder(BoundedJson.Quota quota) { this.quota=quota; }
        void face(int bone,int cube,int direction,float[] a,float[] b,float[] c,float[] d,double[] rectangle,int width,int height,
                  boolean cubeMirror,boolean layout,float[] rotation) throws IOException {
            quota.cancellation.check();
            if (faces.size()>=quota.policy.maxEntries()) throw new IOException("Generated face count limit exceeded");
            quota.charge(2048);
            int first=boneIndices.size();
            float[] normal=NORMALS[direction].clone(); if (cubeMirror) normal[0]=-normal[0];
            rotate(normal,rotation,new float[3]);
            float u1=finite(rectangle[0]/width),u2=finite((rectangle[0]+rectangle[2])/width);
            float v1=finite(rectangle[1]/height),v2=finite((rectangle[1]+rectangle[3])/height);
            float[] faceUvs=cubeMirror?new float[] {u1,v1,u2,v1,u2,v2,u1,v2}:new float[] {u2,v1,u1,v1,u1,v2,u2,v2};
            for (float[] vertex : new float[][] {a,b,c,d}) {
                for (float coordinate : vertex) positions.add(coordinate);
                for (float coordinate : normal) normals.add(coordinate);
                boneIndices.add(bone);
            }
            for (float coordinate : faceUvs) uvs.add(coordinate);
            for (int index : new int[] {0,1,2,0,2,3}) indices.add(Math.addExact(first,index));
            faces.add(new Face(bone,cube,first,DIRECTIONS[direction],layout));
        }
        Mesh finish(String name) {
            float[] p=floats(positions),n=floats(normals),uv=floats(uvs);
            int[] bones=ints(boneIndices),triangles=ints(indices);
            float[] min={0,0,0},max={0,0,0};
            if (p.length!=0) { System.arraycopy(p,0,min,0,3); System.arraycopy(p,0,max,0,3); }
            for (int i=0;i<p.length;i++) { min[i%3]=Math.min(min[i%3],p[i]); max[i%3]=Math.max(max[i%3],p[i]); }
            return new Mesh(name,new VertexBatch(p,n,bones),uv,triangles,faces,new Bounds(min[0],min[1],min[2],max[0],max[1],max[2]),0);
        }
    }

    private static JsonValue required(JsonObject object,String name) throws IOException {
        JsonValue value=object.values().get(name); if (value==null || value==JsonNull.INSTANCE) throw new IOException("Missing field: "+name); return value;
    }
    private static JsonObject object(JsonValue value,String field) throws IOException {
        if (value instanceof JsonObject object) return object; throw new IOException("Expected object: "+field);
    }
    private static JsonArray array(JsonValue value,String field) throws IOException {
        if (value instanceof JsonArray array) return array; throw new IOException("Expected array: "+field);
    }
    private static JsonObject optionalObject(JsonObject object,String field) throws IOException {
        JsonValue value=object.values().get(field); return value==null?new JsonObject(Map.of()):object(value,field);
    }
    private static String string(JsonValue value,String field) throws IOException {
        if (value instanceof JsonString string) return string.value(); throw new IOException("Expected string: "+field);
    }
    private static float number(JsonObject object,String field,float fallback) throws IOException {
        return object.values().containsKey(field)?number(required(object,field),field):fallback;
    }
    private static float number(JsonValue value,String field) throws IOException {
        if (value instanceof JsonNumber number) return finite(number.value().doubleValue()); throw new IOException("Expected number: "+field);
    }
    private static boolean bool(JsonObject object,String field,boolean fallback) throws IOException {
        JsonValue value=object.values().get(field); if (value==null) return fallback;
        if (value instanceof JsonBoolean bool) return bool.value(); throw new IOException("Expected boolean: "+field);
    }
    private static int dimension(JsonObject description,String field) throws IOException {
        JsonValue value=description.values().get(field); if (value==null) return 64;
        if (!(value instanceof JsonNumber number)) throw new IOException("Expected integer dimension");
        int result=number.value().intValueExact(); if (result<1) throw new IOException("Nonpositive texture dimension"); return result;
    }
    private static float[] vector(JsonObject object,String field,float[] fallback) throws IOException {
        return object.values().containsKey(field)?vector(required(object,field),fallback.length,field):fallback;
    }
    private static float[] vector(JsonValue value,int size,String field) throws IOException {
        var values=array(value,field).values(); if (values.size()!=size) throw new IOException("Invalid vector size: "+field);
        float[] result=new float[size]; for (int i=0;i<size;i++) result[i]=number(values.get(i),field); return result;
    }
    private static float finite(double value) throws IOException {
        float result=(float)value; if (!Float.isFinite(result)) throw new IOException("Non-finite or overflowing geometry"); return result;
    }
    private static float[] floats(List<Float> values) {
        float[] result=new float[values.size()]; for (int i=0;i<result.length;i++) result[i]=values.get(i); return result;
    }
    private static int[] ints(List<Integer> values) {
        int[] result=new int[values.size()]; for (int i=0;i<result.length;i++) result[i]=values.get(i); return result;
    }
}
