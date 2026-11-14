package dev.spm.core.format.gltf;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.common.BoundedJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded static glTF 2.0 importer. Skinning, morphs and animation are explicitly rejected. */
public final class GltfModelImporter implements ModelFormatAdapter {
    private static final int GLB_MAGIC=0x46546c67, JSON_CHUNK=0x4e4f534a, BIN_CHUNK=0x004e4942;
    @Override public String formatId(){return "gltf2-static";}
    @Override public boolean supports(FormatHint hint){return hint==FormatHint.GLTF||hint==FormatHint.GLB;}
    @Override public boolean accepts(FormatHint hint,ResourceKey entry,ByteBuffer prefix){
        if(!supports(hint))return false;
        if(hint==FormatHint.GLB){int p=prefix.position();return prefix.remaining()>=4&&prefix.get(p)=='g'&&prefix.get(p+1)=='l'&&prefix.get(p+2)=='T'&&prefix.get(p+3)=='F';}
        return entry.path().toLowerCase(java.util.Locale.ROOT).endsWith(".gltf")||prefix.hasRemaining()&&prefix.get(prefix.position())=='{';
    }
    @Override public ImportResult importModel(ImportRequest request)throws ModelImportException{
        Objects.requireNonNull(request);
        var quota=new BoundedJson.Quota(request.policy(),request.budget(),request.cancellation());
        boolean transferred=false;
        long[] inputBytes={0};
        try {
            byte[] source=read(request.source().resolver(),request.source().entry(),request,quota,inputBytes);
            boolean glb=request.hint()==FormatHint.GLB||request.hint()==FormatHint.AUTO&&request.source().entry().path().toLowerCase(java.util.Locale.ROOT).endsWith(".glb");
            byte[] json,embedded=null;
            if(glb){var parts=glb(source);json=parts.json();embedded=parts.binary();}
            else json=source;
            quota.charge(json.length);
            var root=BoundedJson.read(json,quota);
            require("2.0".equals(string(obj(root.get("asset"),"asset").get("version"),"asset.version")),"Only glTF 2.0 is supported");
            rejectUnsupported(root);
            validateBufferViews(root);
            List<byte[]> buffers=loadBuffers(root,request,quota,embedded,inputBytes);
            for(byte[] buffer:buffers)quota.charge(buffer.length);
            List<JsonValue> views=array(root.get("bufferViews"),"bufferViews");
            List<JsonValue> accessors=array(root.get("accessors"),"accessors");
            List<JsonValue> meshValues=array(root.get("meshes"),"meshes");
            validateAllAccessors(accessors,views,buffers,quota);
            int modelIndex=0;
            List<ModelDocument> documents=new ArrayList<>();
            List<String> modelNames=new ArrayList<>();
            for(JsonValue meshValue:meshValues){
                request.cancellation().check();var meshObject=obj(meshValue,"mesh");String meshName=optionalString(meshObject.get("name"),"mesh-"+modelIndex);
                List<JsonValue> primitives=array(meshObject.get("primitives"),"primitives");
                List<Mesh> meshes=new ArrayList<>();int primitiveIndex=0;
                for(JsonValue primitiveValue:primitives){
                    request.cancellation().check();var primitive=obj(primitiveValue,"primitive");
                    require(integer(optional(primitive.get("mode"),4),"primitive.mode")==4,"Only TRIANGLES primitives are supported");
                    var attributes=obj(primitive.get("attributes"),"primitive.attributes");
                    requireSupported(attributes.values().keySet().stream().allMatch(key->key.equals("POSITION")||key.equals("NORMAL")||key.equals("TEXCOORD_0")),"Unsupported vertex attribute");
                    requireSupported(primitive.get("material")==null,"glTF materials and textures are not supported by this importer");
                    int positionAccessor=integer(attributes.get("POSITION"),"POSITION accessor");
                    require(positionAccessor>=0&&positionAccessor<accessors.size(),"POSITION accessor index out of range");
                    int vertices=integer(obj(accessors.get(positionAccessor),"POSITION accessor").get("count"),"POSITION count");
                    JsonValue indexValue=primitive.get("indices");
                    int indexCount=indexValue==null?vertices:integer(accessor(accessors,integer(indexValue,"indices")).get("count"),"indices.count");
                    require(indexCount%3==0,"Triangle index count is not divisible by three");
                    quota.charge(modelAllocationBytes(vertices,indexCount));
                    float[] positions=readVec3(obj(accessors.get(positionAccessor),"POSITION accessor"),views,buffers,quota);
                    require(positions.length/3==vertices,"POSITION count changed during decoding");
                    JsonValue uv=attributes.get("TEXCOORD_0");
                    float[] uvs=uv==null?new float[Math.multiplyExact(vertices,2)]:readVec2(accessor(accessors,integer(uv,"TEXCOORD_0")),views,buffers,quota,vertices);
                    int[] indices=indexValue==null?sequence(vertices):readIndices(accessor(accessors,integer(indexValue,"indices")),views,buffers,quota,vertices);
                    require(indices.length==indexCount,"Index count changed during decoding");
                    for(int index:indices)require(index>=0&&index<vertices,"Index outside POSITION accessor");
                    JsonValue normalAccessor=attributes.get("NORMAL");
                    float[] normals=normalAccessor==null?generatedNormals(positions,indices,quota):readVec3(accessor(accessors,integer(normalAccessor,"NORMAL")),views,buffers,quota);
                    require(normals.length==positions.length,"NORMAL count must match POSITION count");
                    int[] bones=new int[vertices],glow=new int[vertices],colors=new int[vertices];Arrays.fill(glow,255);Arrays.fill(colors,0xffffffff);
                    float[] min={0,0,0},max={0,0,0};if(vertices>0){Arrays.fill(min,Float.POSITIVE_INFINITY);Arrays.fill(max,Float.NEGATIVE_INFINITY);for(int i=0;i<positions.length;i++){int axis=i%3;min[axis]=Math.min(min[axis],positions[i]);max[axis]=Math.max(max[axis],positions[i]);}}
                    meshes.add(new Mesh(primitiveIndex==0?meshName:meshName+"-"+primitiveIndex,new VertexBatch(positions,normals,bones,glow,colors),uvs,indices,List.of(),new Bounds(min[0],min[1],min[2],max[0],max[1],max[2]),0));primitiveIndex++;
                }
                if(!meshes.isEmpty()){
                    var material=new Material("default",1,1,List.of());
                    var document=new ModelDocument(request.source().identity()+"#"+meshName,"gltf2-static",new Skeleton(List.of("root"),new int[]{-1},new float[3]),meshes,BonePose.identity(1),List.of(material),List.of(),new JsonObject(Map.of("extensionsUsed",new JsonArray(jsonStrings(strings(root.get("extensionsUsed")))))));
                    documents.add(document);modelNames.add(meshName);
                }
                modelIndex++;
            }
            if(documents.isEmpty())throw new IOException("No supported triangle meshes");
            List<ImportResult.Model> models=new ArrayList<>();
            var retained=quota.transfer();transferred=true;
            SharedRetention shared=null;
            int assigned=0;
            try {
                shared=new SharedRetention(retained,documents.size());
                for(int i=0;i<documents.size();i++) {
                    models.add(new ImportResult.Model(modelNames.get(i),new ModelAsset(documents.get(i),List.of(),shared::releaseOne)));
                    assigned++;
                }
                return new ImportResult(models,List.of(),key->{throw new IOException("glTF static importer has no retained texture resources: "+key);},()->{});
            } catch(RuntimeException|Error construction) {
                if(shared==null)retained.forEach(ResourceBudget.Reservation::close);
                else {models.forEach(model->model.asset().close());shared.closeUnassigned(documents.size()-assigned);}
                throw construction;
            }
        }catch(Exception failure){if(!transferred)quota.close();throw error(request,failure);}
        catch(Error failure){if(!transferred)quota.close();throw failure;}
    }
    private record Glb(byte[] json,byte[] binary){}
    private static Glb glb(byte[] bytes)throws IOException{
        if(bytes.length<20)throw new IOException("Truncated GLB header");var in=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(in.getInt()==GLB_MAGIC&&in.getInt()==2,"Invalid GLB magic or version");require(Integer.toUnsignedLong(in.getInt())==bytes.length,"GLB declared length mismatch");
        byte[] json=null,bin=null;while(in.remaining()>=8){int length=in.getInt(),type=in.getInt();require(length>=0&&length<=in.remaining()&&length%4==0,"Invalid GLB chunk length");byte[] chunk=new byte[length];in.get(chunk);if(type==JSON_CHUNK){require(json==null&&bin==null,"Duplicate or misplaced GLB JSON chunk");int end=chunk.length;while(end>0&&chunk[end-1]==0x20)end--;json=Arrays.copyOf(chunk,end);}else if(type==BIN_CHUNK){require(json!=null&&bin==null,"Duplicate or misplaced GLB BIN chunk");bin=chunk;}else throw new IOException("Unsupported GLB chunk");}
        require(!in.hasRemaining()&&json!=null,"Missing or truncated GLB JSON chunk");return new Glb(json,bin);
    }
    private static void rejectUnsupported(JsonObject root)throws IOException{
        for(String key:List.of("skins","animations"))if(root.get(key)!=null&&!array(root.get(key),key).isEmpty())throw new UnsupportedFeatureException("Unsupported glTF feature: "+key);
        requireSupported(root.get("materials")==null&&root.get("images")==null&&root.get("textures")==null,"glTF material and image resources are not supported by this importer");
        for(JsonValue nodeValue:arrayOptional(root.get("nodes"))){var node=obj(nodeValue,"node");requireSupported(node.get("skin")==null,"Unsupported glTF feature: skin");requireSupported(node.get("matrix")==null&&node.get("translation")==null&&node.get("rotation")==null&&node.get("scale")==null,"Non-identity glTF node transforms are not supported");requireSupported(node.get("camera")==null&&node.get("children")==null,"glTF node hierarchy and cameras are not supported");}
        requireSupported(arrayOptional(root.get("extensionsRequired")).isEmpty()&&arrayOptional(root.get("extensionsUsed")).isEmpty(),"Unsupported glTF extension");
        for(JsonValue mesh:arrayOptional(root.get("meshes")))for(JsonValue primitive:array(obj(mesh,"mesh").get("primitives"),"primitives")){var p=obj(primitive,"primitive");if(p.get("targets")!=null)throw new UnsupportedFeatureException("Morph targets are not supported");requireSupported(p.get("extensions")==null,"Unsupported primitive extension");}
        requireSupported(root.get("extensions") == null,"Unsupported glTF extension");
        requireSupported(root.get("cameras")==null,"glTF cameras are not supported");
        require(root.get("accessors")==null||array(root.get("accessors"),"accessors").stream().allMatch(value->value instanceof JsonObject),"Invalid accessors array");
    }
    private static void validateBufferViews(JsonObject root)throws IOException{
        for(JsonValue value:arrayOptional(root.get("bufferViews"))){var view=obj(value,"bufferView");integer(optional(view.get("byteOffset"),0),"bufferView.byteOffset");int length=integer(view.get("byteLength"),"bufferView.byteLength");require(length>0,"Empty bufferView");require(view.get("byteStride")==null,"Interleaved bufferViews are not supported");require(view.get("target")==null,"Explicit bufferView targets are not supported");}
    }
    private static void validateAllAccessors(List<JsonValue> accessors,List<JsonValue> views,List<byte[]> buffers,BoundedJson.Quota quota)throws IOException{
        for(JsonValue value:accessors){var accessor=obj(value,"accessor");validateAccessor(accessor);int component=integer(accessor.get("componentType"),"accessor.componentType");int size=switch(component){case 5121->1;case 5123->2;case 5125,5126->4;default->throw new IOException("Unsupported accessor component type");};String type=string(accessor.get("type"),"accessor.type");int multiplier=switch(type){case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;default->throw new IOException("Unsupported accessor type");};int element=Math.multiplyExact(size,multiplier);int count=integer(accessor.get("count"),"accessor.count");require(count>0&&count<=quota.policy.maxCollectionEntries(),"Invalid accessor count");
            JsonValue min=accessor.get("min"),max=accessor.get("max");require((min==null)==(max==null),"Accessor min/max must be paired");if(min!=null){List<JsonValue> lower=numberArray(min,"accessor.min"),upper=numberArray(max,"accessor.max");require(lower.size()==multiplier&&upper.size()==multiplier,"Accessor min/max arity mismatch");for(int i=0;i<multiplier;i++)require(number(lower.get(i),"accessor.min")<=number(upper.get(i),"accessor.max"),"Reversed accessor bounds");}validateSliceBounds(accessor,views,buffers,element);}
    }
    private static List<byte[]> loadBuffers(JsonObject root,ImportRequest request,BoundedJson.Quota quota,byte[] embedded,long[] inputBytes)throws IOException{
        List<JsonValue> declared=arrayOptional(root.get("buffers"));List<byte[]> result=new ArrayList<>();long total=0;
        for(int i=0;i<declared.size();i++){
            var object=obj(declared.get(i),"buffer");int length=integer(object.get("byteLength"),"buffer.byteLength");total=Math.addExact(total,length);require(total<=quota.policy.maxExpandedBytes(),"Aggregate glTF buffer limit");byte[] data;
            JsonValue uri=object.get("uri");if(uri==null){require(i==0&&embedded!=null,"Missing embedded GLB buffer");data=embedded;}
            else{String text=string(uri,"buffer.uri");data=text.startsWith("data:")?decodeDataUri(text,length,quota):read(request.source().resolver(),resolve(request.source().entry(),text),request,quota,inputBytes);}
            require(data.length>=length,"Buffer shorter than declared byteLength");if(uri==null)require(data.length-length<=3,"GLB BIN padding exceeds three bytes");
            result.add(data.length==length?data:Arrays.copyOf(data,length));
        }
        for(JsonValue viewValue:arrayOptional(root.get("bufferViews"))){var view=obj(viewValue,"bufferView");int index=integer(optional(view.get("buffer"),0),"bufferView.buffer");require(index>=0&&index<result.size(),"bufferView buffer out of range");}
        require(embedded==null||declared.stream().anyMatch(value->value instanceof JsonObject object&&object.get("uri")==null),"Unused GLB BIN chunk");
        return result;
    }
    private static ResourceKey resolve(ResourceKey base,String uri)throws IOException{
        final java.net.URI parsed;try{parsed=new java.net.URI(uri);}catch(java.net.URISyntaxException invalid){throw new IOException("Malformed buffer URI",invalid);}require(!parsed.isAbsolute()&&parsed.getRawAuthority()==null&&parsed.getRawQuery()==null&&parsed.getRawFragment()==null,"Unsafe buffer URI");String decoded=parsed.getPath();require(decoded!=null&&!decoded.startsWith("/")&&!decoded.contains(":")&&!decoded.contains("\\"),"Unsafe buffer URI");for(String segment:decoded.split("/",-1))require(!segment.equals(".")&&!segment.equals("..")&&!segment.isEmpty(),"Unsafe buffer URI");String parent=base.path().contains("/")?base.path().substring(0,base.path().lastIndexOf('/')+1):"";try{return new ResourceKey(parent+decoded);}catch(IllegalArgumentException invalid){throw new IOException("Unsafe buffer URI",invalid);}
    }
    private static byte[] read(ResourceResolver resolver,ResourceKey key,ImportRequest request,BoundedJson.Quota quota,long[] inputBytes)throws IOException{
        try(InputStream in=resolver.open(key);var out=new ByteArrayOutputStream()){if(in==null)throw new IOException("Resolver returned null stream");byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){request.cancellation().check();inputBytes[0]=Math.addExact(inputBytes[0],n);if(inputBytes[0]>request.policy().maxInputBytes())throw new IOException("aggregate glTF input limit exceeded");out.write(block,0,n);}byte[] bytes=out.toByteArray();quota.charge(bytes.length);return bytes;}
    }
    private static float[] readVec3(JsonObject accessor,List<JsonValue> views,List<byte[]> buffers,BoundedJson.Quota quota)throws IOException{
        validateAccessor(accessor);
        require(integer(accessor.get("componentType"),"componentType")==5126&&"VEC3".equals(string(accessor.get("type"),"accessor.type")),"POSITION must be float VEC3");int count=integer(accessor.get("count"),"accessor.count");require(count>0&&count<=quota.policy.maxCollectionEntries(),"Invalid POSITION count");ByteBuffer data=slice(accessor,views,buffers,12,quota);float[] out=new float[Math.multiplyExact(count,3)];for(int i=0;i<out.length;i++){float v=data.getFloat();require(Float.isFinite(v),"Non-finite POSITION");out[i]=v;}return out;
    }
    private static float[] readVec2(JsonObject accessor,List<JsonValue> views,List<byte[]> buffers,BoundedJson.Quota quota,int expected)throws IOException{
        validateAccessor(accessor);
        require(integer(accessor.get("componentType"),"componentType")==5126&&"VEC2".equals(string(accessor.get("type"),"accessor.type"))&&integer(accessor.get("count"),"accessor.count")==expected,"TEXCOORD_0 must be matching float VEC2");ByteBuffer data=slice(accessor,views,buffers,8,quota);float[] out=new float[Math.multiplyExact(expected,2)];for(int i=0;i<out.length;i++){out[i]=data.getFloat();require(Float.isFinite(out[i]),"Non-finite TEXCOORD_0");}return out;
    }
    private static int[] readIndices(JsonObject accessor,List<JsonValue> views,List<byte[]> buffers,BoundedJson.Quota quota,int vertices)throws IOException{
        validateAccessor(accessor);
        require("SCALAR".equals(string(accessor.get("type"),"indices.type")),"Indices must be SCALAR");int component=integer(accessor.get("componentType"),"indices.componentType");int size=switch(component){case 5121->1;case 5123->2;case 5125->4;default->throw new IOException("Unsupported index component type");};int count=integer(accessor.get("count"),"indices.count");require(count>0&&count<=quota.policy.maxCollectionEntries(),"Invalid index count");ByteBuffer data=slice(accessor,views,buffers,size,quota);int[] out=new int[count];for(int i=0;i<count;i++){long value=switch(size){case 1->Byte.toUnsignedLong(data.get());case 2->Short.toUnsignedLong(data.getShort());default->Integer.toUnsignedLong(data.getInt());};require(value<vertices,"Index outside POSITION accessor");out[i]=(int)value;}return out;
    }
    private static ByteBuffer slice(JsonObject accessor,List<JsonValue> views,List<byte[]> buffers,int element,BoundedJson.Quota quota)throws IOException{
        Slice source=validateSliceBounds(accessor,views,buffers,element);
        quota.charge(Math.multiplyExact((long)source.count(),element));
        var in=ByteBuffer.wrap(source.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        in.position(Math.toIntExact(source.absolute()));
        byte[] packed=new byte[Math.multiplyExact(source.count(),element)];in.get(packed);
        return ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
    }
    private record Slice(byte[] bytes,int count,long absolute){}
    private static Slice validateSliceBounds(JsonObject accessor,List<JsonValue> views,List<byte[]> buffers,int element)throws IOException{
        int viewIndex=integer(accessor.get("bufferView"),"accessor.bufferView");require(viewIndex>=0&&viewIndex<views.size(),"Accessor bufferView out of range");
        var view=obj(views.get(viewIndex),"bufferView");int bufferIndex=integer(optional(view.get("buffer"),0),"bufferView.buffer");require(bufferIndex>=0&&bufferIndex<buffers.size(),"bufferView buffer out of range");
        int count=integer(accessor.get("count"),"accessor.count"),offset=integer(optional(accessor.get("byteOffset"),0),"accessor.byteOffset"),viewOffset=integer(optional(view.get("byteOffset"),0),"bufferView.byteOffset"),viewLength=integer(view.get("byteLength"),"bufferView.byteLength");
        require(offset%element==0&&viewOffset%4==0,"Misaligned accessor");long end=count==0?offset:Math.addExact((long)offset+Math.multiplyExact((long)count-1,element),element);require(offset>=0&&end<=viewLength,"Accessor exceeds bufferView");
        long absolute=(long)viewOffset+offset,viewEnd=(long)viewOffset+viewLength;byte[] bytes=buffers.get(bufferIndex);require(viewOffset>=0&&viewEnd<=bytes.length&&absolute>=0,"bufferView exceeds buffer");return new Slice(bytes,count,absolute);
    }
    private static void validateAccessor(JsonObject accessor)throws IOException{require(accessor.get("sparse")==null,"Sparse accessors are not supported");JsonValue normalized=accessor.get("normalized");require(!(normalized instanceof JsonBoolean b)||!b.value(),"Normalized accessors are not supported");}
    private static int[] sequence(int n){int[] r=new int[n];for(int i=0;i<n;i++)r[i]=i;return r;}
    private static long modelAllocationBytes(int vertices,int indices){
        long vertexFloats=arrayAllocationBytes(Math.multiplyExact((long)vertices,3L),4L);
        long vertexInts=arrayAllocationBytes(vertices,4L);
        long uvFloats=arrayAllocationBytes(Math.multiplyExact((long)vertices,2L),4L);
        long indexInts=arrayAllocationBytes(indices,4L);
        return Math.addExact(256L,Math.addExact(
                Math.addExact(Math.multiplyExact(4L,vertexFloats),Math.multiplyExact(6L,vertexInts)),
                Math.addExact(Math.addExact(Math.multiplyExact(2L,uvFloats),Math.multiplyExact(2L,indexInts)),
                        Math.multiplyExact(2L,arrayAllocationBytes(3L,4L)))));
    }
    private static long arrayAllocationBytes(long count,long elementBytes){
        long payload=Math.multiplyExact(count,elementBytes);return Math.addExact(16L,Math.addExact(payload,7L))&~7L;
    }
    /** Area-weighted smooth normals for legal static geometry that omits the optional NORMAL attribute. */
    private static float[] generatedNormals(float[] positions,int[] indices,BoundedJson.Quota quota)throws IOException{
        int vertices=positions.length/3;quota.charge(arrayAllocationBytes(positions.length,8L));
        double[] accumulated=new double[positions.length];
        for(int triangle=0;triangle<indices.length;triangle+=3){
            int a=indices[triangle]*3,b=indices[triangle+1]*3,c=indices[triangle+2]*3;
            double abx=(double)positions[b]-positions[a],aby=(double)positions[b+1]-positions[a+1],abz=(double)positions[b+2]-positions[a+2];
            double acx=(double)positions[c]-positions[a],acy=(double)positions[c+1]-positions[a+1],acz=(double)positions[c+2]-positions[a+2];
            double nx=aby*acz-abz*acy,ny=abz*acx-abx*acz,nz=abx*acy-aby*acx;
            accumulated[a]+=nx;accumulated[a+1]+=ny;accumulated[a+2]+=nz;
            accumulated[b]+=nx;accumulated[b+1]+=ny;accumulated[b+2]+=nz;
            accumulated[c]+=nx;accumulated[c+1]+=ny;accumulated[c+2]+=nz;
        }
        float[] normals=new float[positions.length];
        for(int vertex=0;vertex<vertices;vertex++){
            int i=vertex*3;double length=Math.hypot(Math.hypot(accumulated[i],accumulated[i+1]),accumulated[i+2]);
            if(length==0){normals[i+2]=1;continue;}
            require(Double.isFinite(length),"Unrepresentable generated normal");
            normals[i]=(float)(accumulated[i]/length);normals[i+1]=(float)(accumulated[i+1]/length);normals[i+2]=(float)(accumulated[i+2]/length);
        }
        return normals;
    }
    private static JsonObject accessor(List<JsonValue> values,int index)throws IOException{require(index>=0&&index<values.size(),"Accessor index out of range");return obj(values.get(index),"accessor");}
    private static List<String> strings(JsonValue value)throws IOException{List<String> result=new ArrayList<>();for(JsonValue item:arrayOptional(value))result.add(string(item,"extension"));return result;}
    private static List<JsonValue> jsonStrings(List<String> values){List<JsonValue> out=new ArrayList<>();for(String value:values)out.add(new JsonString(value));return out;}
    private static JsonValue optional(JsonValue value,int fallback){return value==null?new JsonNumber(fallback):value;}
    private static int integer(JsonValue value,String label)throws IOException{double d=number(value,label);require(d>=0&&d<=Integer.MAX_VALUE&&d==Math.rint(d),"Invalid "+label);return (int)d;}
    private static double number(JsonValue value,String label)throws IOException{if(!(value instanceof JsonNumber n))throw new IOException("Expected number: "+label);return n.value();}
    private static String string(JsonValue value,String label)throws IOException{if(!(value instanceof JsonString s))throw new IOException("Expected string: "+label);return s.value();}
    private static String optionalString(JsonValue value,String fallback)throws IOException{return value==null?fallback:string(value,"name");}
    private static JsonObject obj(JsonValue value,String label)throws IOException{if(!(value instanceof JsonObject o))throw new IOException("Expected object: "+label);return o;}
    private static List<JsonValue> array(JsonValue value,String label)throws IOException{if(!(value instanceof JsonArray a))throw new IOException("Expected array: "+label);return a.values();}
    private static List<JsonValue> arrayOptional(JsonValue value)throws IOException{return value==null?List.of():array(value,"array");}
    private static List<JsonValue> numberArray(JsonValue value,String label)throws IOException{if(value instanceof JsonVector3 v)return List.of(new JsonNumber(v.x()),new JsonNumber(v.y()),new JsonNumber(v.z()));return array(value,label);}
    private static byte[] decodeDataUri(String uri,int expected,BoundedJson.Quota quota)throws IOException{
        int comma=uri.indexOf(',');require(comma>5,"Malformed data URI");String metadata=uri.substring(5,comma);
        require(metadata.equalsIgnoreCase("application/octet-stream;base64")||metadata.equalsIgnoreCase("application/gltf-buffer;base64"),"Unsupported data URI media type");
        String payload=uri.substring(comma+1);long maximum=((long)expected+2)/3*4;require(payload.length()<=maximum+4,"Data URI exceeds declared buffer length");
        byte[] decoded;try{decoded=java.util.Base64.getDecoder().decode(payload);}catch(IllegalArgumentException malformed){throw new IOException("Malformed base64 data URI",malformed);}
        require(decoded.length==expected,"Data URI length differs from declared buffer length");return decoded;
    }
    private static final class SharedRetention{
        private List<ResourceBudget.Reservation> reservations;private int remaining;
        SharedRetention(List<ResourceBudget.Reservation> reservations,int owners){this.reservations=List.copyOf(reservations);remaining=owners;}
        synchronized void releaseOne(){if(remaining<=0)return;if(--remaining==0)release();}
        synchronized void closeUnassigned(int count){remaining-=count;if(remaining<=0)release();}
        private void release(){if(reservations==null)return;for(int i=reservations.size()-1;i>=0;i--)reservations.get(i).close();reservations=null;}
    }
    private static void require(boolean condition,String message)throws IOException{if(!condition)throw new IOException(message);}
    private static void requireSupported(boolean condition,String message)throws IOException{if(!condition)throw new UnsupportedFeatureException(message);}
    private static ModelImportException error(ImportRequest request,Exception failure){
        ImportErrorCode code=failure instanceof java.util.concurrent.CancellationException?ImportErrorCode.CANCELLED:
                failure instanceof DecodeLimitException limit?limit.code():
                failure instanceof IOException io&&hasCause(io,ResourceBudgetExceededException.class)?ImportErrorCode.BUDGET_EXCEEDED:
                failure instanceof UnsupportedFeatureException?ImportErrorCode.UNSUPPORTED_FEATURE:
                failure instanceof IOException||failure instanceof IllegalArgumentException?ImportErrorCode.MALFORMED_INPUT:ImportErrorCode.INTERNAL;
        return new ModelImportException(new ImportDiagnostic(ImportDiagnostic.Severity.ERROR,code,request.source().entry(),-1,failure.getMessage()==null?"glTF import failed":failure.getMessage()),failure);
    }
    private static boolean hasCause(Throwable value,Class<? extends Throwable> type){for(Throwable cursor=value;cursor!=null;cursor=cursor.getCause())if(type.isInstance(cursor))return true;return false;}
    private static final class UnsupportedFeatureException extends IOException{private static final long serialVersionUID=1L;UnsupportedFeatureException(String message){super(message);}}
}





