package dev.spm.core.format.bbmodel;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.common.BoundedJson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded Blockbench cube-project importer; archival metadata does not imply animation support. */
public final class BbmodelJsonImporter {
    private BbmodelJsonImporter() {}
    /** Default supported Blockbench formats use ZYX. XYZ is an explicit host override for documented plugin sources. */
    public enum RotationOrder { ZYX, XYZ }
    /**
     * Imports a project whose retained allocations are <em>not</em> kept charged: the construction
     * quota is released on return. Use {@link #importAsset} for documents that outlive the call.
     */
    public static ModelDocument importJson(byte[] bytes,DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation) throws IOException {
        return importJson(bytes,policy,budget,cancellation,RotationOrder.ZYX);
    }
    /** Explicit authoring-order override; it never infers an undocumented order from project metadata. */
    public static ModelDocument importJson(byte[] bytes,DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation,RotationOrder order) throws IOException {
        Objects.requireNonNull(order);
        try (var quota=new BoundedJson.Quota(policy,budget,cancellation)) {
            return new Builder(BoundedJson.read(bytes,quota),quota,order).build();
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IOException("Invalid BBModel project",exception);
        }
    }
    /** Imports a project and keeps its retained allocations charged until the returned asset closes. */
    public static ModelAsset importAsset(byte[] bytes,DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation,RotationOrder order) throws IOException {
        Objects.requireNonNull(order);
        try (var quota=new BoundedJson.Quota(policy,budget,cancellation)) {
            ModelDocument document;
            try { document=new Builder(BoundedJson.read(bytes,quota),quota,order).build(); }
            catch (IllegalArgumentException | ArithmeticException exception) { throw new IOException("Invalid BBModel project",exception); }
            return new ModelAsset(document,quota.transfer());
        }
    }
    /** Imports a project with the documented ZYX order and keeps its allocations charged. */
    public static ModelAsset importAsset(byte[] bytes,DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation) throws IOException {
        return importAsset(bytes,policy,budget,cancellation,RotationOrder.ZYX);
    }

    private record Node(JsonObject data,int parent,String type,int ordinal) {}
    private static final String[] DIRECTIONS={"east","west","up","down","south","north"};
    // Perimeter order, outward for positive dimensions. Source strip vertices are reordered 1,0,2,3.
    private static final int[][] CORNERS={{6,7,5,4},{3,2,0,1},{6,2,3,7},{5,1,0,4},{7,3,1,5},{2,6,4,0}};
    private static final float[][] NORMALS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private static final class Builder {
        final JsonObject root,meta;
        final BoundedJson.Quota quota;
        final RotationOrder order;
        final Map<String,JsonObject> elements=new LinkedHashMap<>(),groups=new LinkedHashMap<>();
        final Map<String,Integer> ordinals=new HashMap<>(),textures=new HashMap<>();
        final Set<String> seen=new HashSet<>();
        final List<Node> nodes=new ArrayList<>();
        final List<Material> materials=new ArrayList<>();
        final Map<Integer,MeshBuilder> meshes=new LinkedHashMap<>();
        final List<Locator> locators=new ArrayList<>();
        final int width,height;
        final boolean modern,defaultTexture,defaultBox;
        Builder(JsonObject root,BoundedJson.Quota quota,RotationOrder order) throws IOException {
            this.root=root; this.quota=quota; this.order=order;
            meta=object(required(root,"meta"),"meta");
            String version=string(required(meta,"format_version"),"format_version");
            if (!Set.of("4.9","4.10","5.0").contains(version)) throw new IOException("Unsupported BBModel version: "+version);
            modern=version.equals("5.0");
            String format=string(required(meta,"model_format"),"model_format");
            if (!Set.of("free","bedrock","bedrock_block").contains(format)) throw new IOException("Unsupported BBModel project format: "+format);
            defaultTexture=!format.equals("free"); defaultBox=bool(meta,"box_uv",false);
            var resolution=optionalObject(root,"resolution"); width=dimension(resolution,"width",16); height=dimension(resolution,"height",16);
            dimensions(width,height);
        }
        ModelDocument build() throws IOException {
            quota.cancellation.check();
            loadTextures();
            int ordinal=0;
            for (JsonValue raw : optionalArray(root,"elements")) {
                var element=object(raw,"element"); String uuid=uuid(element);
                String type=optionalString(element,"type","cube");
                if (!Set.of("cube","locator").contains(type)) throw new IOException("Unsupported BBModel element: "+type);
                if (elements.putIfAbsent(uuid,element)!=null) throw new IOException("Duplicate element UUID");
                ordinals.put(uuid,ordinal++);
            }
            for (JsonValue raw : optionalArray(root,"groups")) {
                var group=object(raw,"group"); String uuid=uuid(group);
                if (elements.containsKey(uuid) || groups.putIfAbsent(uuid,group)!=null) throw new IOException("Duplicate group UUID");
            }
            JsonValue outliner=root.values().get("outliner");
            if (outliner==null) {
                if (!groups.isEmpty()) throw new IOException("Groups require an outliner");
                for (String uuid : elements.keySet()) visit(new JsonString(uuid),-1,0);
            } else for (JsonValue entry : array(outliner,"outliner").values()) visit(entry,-1,0);
            if (seen.size()!=elements.size()+groups.size()) throw new IOException("Unplaced element or group");
            int n=nodes.size(); quota.charge(Math.multiplyExact((long)n,1024));
            var names=new ArrayList<String>(n); var uniqueNames=new HashSet<String>();
            int[] parents=new int[n],glow=new int[n]; Arrays.fill(glow,255);
            float[] pivots=new float[Math.multiplyExact(n,3)],rotations=new float[pivots.length],scales=new float[pivots.length]; Arrays.fill(scales,1);
            boolean[] ownHidden=new boolean[n],childrenHidden=new boolean[n];
            for (int index=0;index<n;index++) {
                quota.cancellation.check(); var node=nodes.get(index); JsonObject data=node.data();
                String sourceName=optionalString(data,"name",node.type());
                if (sourceName.isBlank()) sourceName=node.type();
                String name=sourceName;
                if (!uniqueNames.add(name)) {
                    name=sourceName+"@"+uuid(data);
                    while (!uniqueNames.add(name)) name+="@";
                }
                names.add(name); parents[index]=node.parent();
                float[] pivot=vector(data,node.type().equals("locator")?"position":"origin",new float[3]);
                float[] angles=convertRotation(vector(data,"rotation",new float[3]),order);
                rejectScale(data);
                for (int axis=0;axis<3;axis++) { pivots[index*3+axis]=finite(pivot[axis]/16.0); rotations[index*3+axis]=angles[axis]; }
                boolean hidden=!bool(data,"visibility",true)||!bool(data,"export",true);
                ownHidden[index]=hidden; childrenHidden[index]=hidden;
                if (node.type().equals("cube")) cube(data,index,node.ordinal());
                else if (node.type().equals("locator")) {
                    quota.charge(512);
                    float[] offset={pivots[index*3],pivots[index*3+1],pivots[index*3+2]};
                    locators.add(new Locator(sourceName,index,offset,angles,bool(data,"ignore_inherited_scale",false)));
                }
            }
            String identity=optionalString(root,"model_identifier",optionalString(root,"name","bbmodel"));
            if (identity.isBlank()) identity="bbmodel";
            var resultMeshes=new ArrayList<Mesh>();
            for (var entry : meshes.entrySet()) resultMeshes.add(entry.getValue().finish(identity+"/material/"+entry.getKey()));
            quota.cancellation.check();
            return new ModelDocument(identity,"bbmodel",new Skeleton(names,parents,pivots),resultMeshes,
                    new BonePose(rotations,new float[pivots.length],scales,ownHidden,childrenHidden,glow),materials,locators,root);
        }
        void visit(JsonValue entry,int parent,int depth) throws IOException {
            quota.cancellation.check();
            if (depth>Math.min(quota.policy.maxDepth(),256)) throw new IOException("Outliner depth limit exceeded");
            if (entry instanceof JsonString reference) {
                String uuid=reference.value(); var data=elements.get(uuid);
                if (data==null || !seen.add(uuid)) throw new IOException("Unknown or repeated outliner element");
                quota.charge(512); nodes.add(new Node(data,parent,optionalString(data,"type","cube"),ordinals.get(uuid)));
            } else {
                var hierarchy=object(entry,"outliner group"); String uuid=uuid(hierarchy);
                if (elements.containsKey(uuid) || !seen.add(uuid)) throw new IOException("Repeated outliner group UUID");
                JsonObject data=modern?groups.get(uuid):hierarchy;
                if (data==null) throw new IOException("Missing v5 group definition");
                if (!modern && groups.putIfAbsent(uuid,data)!=null) throw new IOException("Repeated group definition");
                quota.charge(512); int index=nodes.size(); nodes.add(new Node(data,parent,"group",0));
                for (JsonValue child : optionalArray(hierarchy,"children")) visit(child,index,depth+1);
            }
        }
        void loadTextures() throws IOException {
            materials.add(new Material("bbmodel:untextured",width,height,List.of()));
            for (JsonValue raw : optionalArray(root,"textures")) {
                quota.cancellation.check(); quota.charge(512);
                var texture=object(raw,"texture");
                int uvWidth=dimension(texture,"uv_width",width),uvHeight=dimension(texture,"uv_height",height);
                if (defaultTexture) { uvWidth=width; uvHeight=height; }
                dimensions(uvWidth,uvHeight);
                int material=materials.size();
                String name=optionalString(texture,"uuid","texture-"+(material-1));
                if (name.isBlank() || textures.putIfAbsent(name,material)!=null) throw new IOException("Duplicate or blank texture UUID");
                var resources=new ArrayList<ResourceKey>();
                JsonValue reference=texture.values().get("relative_path");
                if (reference==null) reference=texture.values().get("path");
                if (reference!=null) {
                    String path=string(reference,"texture path");
                    // Raw editor paths remain archived data and never become filesystem authority.
                    try { resources.add(new ResourceKey(path)); } catch (IllegalArgumentException ignored) { /* archival only */ }
                }
                materials.add(new Material(name,uvWidth,uvHeight,resources));
            }
        }
        void dimensions(int w,int h) throws IOException {
            if (Math.multiplyExact(Math.multiplyExact((long)w,h),4)>quota.policy.maxExpandedBytes()) throw new IOException("Texture size limit exceeded");
        }
        int texture(JsonObject face) throws IOException {
            JsonValue reference=face.values().get("texture");
            if (reference==JsonNull.INSTANCE) return -1;
            if (reference==null || reference instanceof JsonBoolean b&&!b.value()) return defaultTexture&&materials.size()>1?1:0;
            if (reference instanceof JsonNumber number) {
                int index=number.value().intValueExact();
                if (index<0 || index>=materials.size()-1) throw new IOException("Unknown texture index"); return index+1;
            }
            if (reference instanceof JsonString text) {
                Integer material=textures.get(text.value()); if (material!=null) return material;
                try {
                    int index=Integer.parseInt(text.value());
                    if (index>=0 && index<materials.size()-1) return index+1;
                } catch (NumberFormatException ignored) { /* not a numeric reference */ }
            }
            throw new IOException("Unknown face texture reference");
        }
        void cube(JsonObject cube,int bone,int ordinal) throws IOException {
            if (bool(cube,"rescale",false)) throw new IOException("BBModel rescale cubes are not supported");
            float[] from=vector(cube,"from",new float[3]),to=vector(cube,"to",new float[] {1,1,1});
            float[] stretch=vector(cube,"stretch",new float[] {1,1,1}); float inflate=number(cube,"inflate",0);
            float[][] points=new float[8][3];
            for (int corner=0;corner<8;corner++) for (int axis=0;axis<3;axis++) {
                double center=((double)from[axis]+to[axis])/2,extent=(((double)to[axis]-from[axis])/2+inflate)*stretch[axis];
                points[corner][axis]=finite((center+((corner&(4>>axis))==0?-extent:extent))/16);
            }
            boolean box=bool(cube,"box_uv",defaultBox),mirror=bool(cube,"mirror_uv",false);
            var faces=optionalObject(cube,"faces");
            for (String name : faces.values().keySet()) if (!Arrays.asList(DIRECTIONS).contains(name)) throw new IOException("Unknown cube face");
            double[][] rectangles=null;
            if (box) {
                float[] offset=vector(cube,"uv_offset",new float[2]);
                double x=Math.floor((double)to[0]-from[0]+1e-7),y=Math.floor((double)to[1]-from[1]+1e-7),z=Math.floor((double)to[2]-from[2]+1e-7);
                rectangles=new double[][] {{0,z,z,y},{z+x,z,z,y},{z+x,z,-x,-z},{z+2*x,0,-x,z},{2*z+x,z,x,y},{z,z,x,y}};
                for (double[] rectangle : rectangles) {
                    if (mirror) { rectangle[0]+=rectangle[2]; rectangle[2]=-rectangle[2]; }
                    rectangle[0]+=offset[0]; rectangle[1]+=offset[1];
                }
                if (mirror) { double[] swap=rectangles[0]; rectangles[0]=rectangles[1]; rectangles[1]=swap; }
            }
            for (int direction=0;direction<6;direction++) {
                JsonValue raw=faces.values().get(DIRECTIONS[direction]); JsonObject face=raw==null?new JsonObject(Map.of()):object(raw,"face");
                int material=texture(face); if (material<0) continue;
                double[] rectangle;
                if (box) rectangle=rectangles[direction];
                else {
                    float[] uv=vector(face,"uv",new float[4]); rectangle=new double[] {uv[0],uv[1],(double)uv[2]-uv[0],(double)uv[3]-uv[1]};
                }
                int turn=integer(face,"rotation",0);
                if (turn%90!=0 || turn<0 || turn>=360) throw new IOException("Unsupported UV rotation");
                MeshBuilder mesh=meshes.get(material);
                if (mesh==null) { mesh=new MeshBuilder(material,quota); meshes.put(material,mesh); }
                var texture=materials.get(material);
                mesh.face(bone,ordinal,direction,points,rectangle,texture.textureWidth(),texture.textureHeight(),turn/90,mirror);
            }
        }
    }

    /** Matrix conversion is required for explicit XYZ sources; component swapping changes multi-axis poses. */
    private static float[] convertRotation(float[] degrees,RotationOrder order) throws IOException {
        double x=degrees[0]*Math.PI/180,y=degrees[1]*Math.PI/180,z=degrees[2]*Math.PI/180;
        if (order==RotationOrder.ZYX) return new float[] {finite(x),finite(y),finite(z)};
        double cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
        // Rows of Rx(x) * Ry(y) * Rz(z), independent of the Core extraction formula.
        double m00=cy*cz,m01=-cy*sz,m10=cx*sz+sx*sy*cz,m11=cx*cz-sx*sy*sz;
        double m20=sx*sz-cx*sy*cz,m21=sx*cz+cx*sy*sz,m22=cx*cy;
        double resultY=Math.asin(Math.max(-1,Math.min(1,-m20)));
        double resultX,resultZ;
        if (Math.abs(Math.cos(resultY))>1e-10) { resultX=Math.atan2(m21,m22); resultZ=Math.atan2(m10,m00); }
        else { resultX=0; resultZ=Math.atan2(-m01,m11); }
        return new float[] {finite(resultX),finite(resultY),finite(resultZ)};
    }

    private static final class MeshBuilder {
        final int material; final BoundedJson.Quota quota;
        final List<Float> positions=new ArrayList<>(),normals=new ArrayList<>(),uvs=new ArrayList<>();
        final List<Integer> bones=new ArrayList<>(),indices=new ArrayList<>(); final List<Face> faces=new ArrayList<>();
        MeshBuilder(int material,BoundedJson.Quota quota) { this.material=material; this.quota=quota; }
        void face(int bone,int ordinal,int direction,float[][] points,double[] uv,int width,int height,int turns,boolean mirror) throws IOException {
            quota.cancellation.check(); quota.charge(2048);
            if (faces.size()>=quota.policy.maxEntries()) throw new IOException("Generated face limit exceeded");
            int first=bones.size();
            float u0=finite(uv[0]/width),u1=finite((uv[0]+uv[2])/width),v0=finite(uv[1]/height),v1=finite((uv[1]+uv[3])/height);
            float[][] corners={{u1,v0},{u0,v0},{u0,v1},{u1,v1}};
            for (int vertex=0;vertex<4;vertex++) {
                for (float component : points[CORNERS[direction][vertex]]) positions.add(component);
                for (float component : NORMALS[direction]) normals.add(component);
                for (float component : corners[(vertex+turns)%4]) uvs.add(component);
                bones.add(bone);
            }
            for (int index : new int[] {0,1,2,0,2,3}) indices.add(Math.addExact(first,index));
            faces.add(new Face(bone,ordinal,first,DIRECTIONS[direction],mirror));
        }
        Mesh finish(String name) {
            float[] p=floats(positions),n=floats(normals),uv=floats(uvs),min={0,0,0},max={0,0,0};
            if (p.length!=0) { System.arraycopy(p,0,min,0,3); System.arraycopy(p,0,max,0,3); }
            for (int i=0;i<p.length;i++) { min[i%3]=Math.min(min[i%3],p[i]); max[i%3]=Math.max(max[i%3],p[i]); }
            return new Mesh(name,new VertexBatch(p,n,ints(bones)),uv,ints(indices),faces,new Bounds(min[0],min[1],min[2],max[0],max[1],max[2]),material);
        }
    }

    private static void rejectScale(JsonObject data) throws IOException {
        float[] scale=vector(data,"scale",new float[] {1,1,1});
        for (float value : scale) if (value!=1) throw new IOException("Nonidentity node scale is not supported");
    }
    private static String uuid(JsonObject object) throws IOException {
        String value=string(required(object,"uuid"),"uuid"); if (value.isBlank()) throw new IOException("Blank UUID"); return value;
    }
    private static JsonValue required(JsonObject object,String field) throws IOException {
        JsonValue value=object.values().get(field); if (value==null || value==JsonNull.INSTANCE) throw new IOException("Missing field: "+field); return value;
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
    private static List<JsonValue> optionalArray(JsonObject object,String field) throws IOException {
        JsonValue value=object.values().get(field); return value==null?List.of():array(value,field).values();
    }
    private static String string(JsonValue value,String field) throws IOException {
        if (value instanceof JsonString string) return string.value(); throw new IOException("Expected string: "+field);
    }
    private static String optionalString(JsonObject object,String field,String fallback) throws IOException {
        JsonValue value=object.values().get(field); return value==null?fallback:string(value,field);
    }
    private static boolean bool(JsonObject object,String field,boolean fallback) throws IOException {
        JsonValue value=object.values().get(field); if (value==null) return fallback;
        if (value instanceof JsonBoolean bool) return bool.value(); throw new IOException("Expected boolean: "+field);
    }
    private static int integer(JsonObject object,String field,int fallback) throws IOException {
        JsonValue value=object.values().get(field); if (value==null) return fallback;
        if (value instanceof JsonNumber number) return number.value().intValueExact(); throw new IOException("Expected integer: "+field);
    }
    private static int dimension(JsonObject object,String field,int fallback) throws IOException {
        int result=integer(object,field,fallback); if (result<1) throw new IOException("Nonpositive texture dimension"); return result;
    }
    private static float number(JsonObject object,String field,float fallback) throws IOException {
        JsonValue value=object.values().get(field); if (value==null) return fallback;
        if (value instanceof JsonNumber number) return finite(number.value().doubleValue()); throw new IOException("Expected number: "+field);
    }
    private static float[] vector(JsonObject object,String field,float[] fallback) throws IOException {
        JsonValue value=object.values().get(field); if (value==null) return fallback;
        var values=array(value,field).values(); if (values.size()!=fallback.length) throw new IOException("Invalid vector length: "+field);
        float[] result=new float[fallback.length];
        for (int i=0;i<result.length;i++) {
            if (!(values.get(i) instanceof JsonNumber number)) throw new IOException("Expected vector number");
            result[i]=finite(number.value().doubleValue());
        }
        return result;
    }
    private static float finite(double value) throws IOException {
        float result=(float)value; if (!Float.isFinite(result)) throw new IOException("Non-finite BBModel geometry"); return result;
    }
    private static float[] floats(List<Float> values) {
        float[] result=new float[values.size()]; for (int i=0;i<result.length;i++) result[i]=values.get(i); return result;
    }
    private static int[] ints(List<Integer> values) {
        int[] result=new int[values.size()]; for (int i=0;i<result.length;i++) result[i]=values.get(i); return result;
    }
}
