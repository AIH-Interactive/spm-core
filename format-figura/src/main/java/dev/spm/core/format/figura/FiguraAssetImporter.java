package dev.spm.core.format.figura;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.bbmodel.BbmodelJsonImporter;
import dev.spm.core.format.common.BoundedJson;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Independent static Figura ZIP assets. Lua, native NBT and behavioral metadata are rejected. */
public final class FiguraAssetImporter implements ModelFormatAdapter {
    @Override public String formatId(){return "figura-static";}
    @Override public boolean supports(FormatHint hint){return hint==FormatHint.FIGURA||hint==FormatHint.AUTO;}
    @Override public boolean accepts(FormatHint hint,ResourceKey entry,ByteBuffer prefix){
        int p=prefix.position();
        return (hint==FormatHint.FIGURA||hint==FormatHint.AUTO&&entry.path().endsWith(".figura.zip"))
                &&prefix.remaining()>=4&&prefix.get(p)=='P'&&prefix.get(p+1)=='K'&&prefix.get(p+2)==3&&prefix.get(p+3)==4;
    }
    @Override public ImportResult importModel(ImportRequest request)throws ModelImportException {
        var quota=new BoundedJson.Quota(request.policy(),request.budget(),request.cancellation());
        var partial=new ArrayList<ModelAsset>();Domain domain=null;List<ResourceBudget.Reservation> owned=null;
        try {
            request.cancellation().check();
            byte[] zip;
            try(InputStream in=request.source().resolver().open(request.source().entry())) {
                if(in==null)throw new IOException("Resolver returned null stream");
                zip=read(in,request.policy().maxInputBytes(),ImportErrorCode.INPUT_LIMIT,request,quota);
            } catch(IOException sourceFailure) {
                if(sourceFailure instanceof DecodeLimitException||budgetFailure(sourceFailure))throw sourceFailure;
                throw new SourceFailure(sourceFailure);
            }
            var centralNames=validateZip(zip,request.policy());
            var files=new LinkedHashMap<ResourceKey,byte[]>();var names=new HashSet<String>();var exactNames=new HashSet<String>();int entries=0;
            try(var in=new ZipInputStream(new ByteArrayInputStream(zip))) {
                java.util.zip.ZipEntry entry;
                while((entry=in.getNextEntry())!=null) {
                    request.cancellation().check();if(++entries>request.policy().maxEntries())throw new DecodeLimitException(ImportErrorCode.RESOURCE_LIMIT,"Figura ZIP entry limit");
                    String name=entry.getName();
                    if(entry.isDirectory())name=name.endsWith("/")?name.substring(0,name.length()-1):name;
                    if(name.indexOf('%')>=0)throw new IOException("Encoded Figura paths are not supported");
                    var key=new ResourceKey(name);String folded=name.toLowerCase(Locale.ROOT);
                    if(!names.add(folded))throw new IOException("Duplicate or case-colliding Figura path");
                    exactNames.add(name);
                    if(entry.isDirectory()) {
                        ZipInfo directory=centralNames.get(name);
                        if(directory==null||directory.size()!=0)throw new IOException("Figura directory entry contains payload data");
                        read(in,0,ImportErrorCode.EXPANDED_LIMIT,request,quota);in.closeEntry();continue;
                    }
                    if(folded.endsWith(".lua")||folded.endsWith(".zip")||folded.endsWith(".nbt"))throw new Unsupported("Executable/nested/native Figura asset is unsupported");
                    if(!name.equals("avatar.json")&&!folded.endsWith(".bbmodel")&&!folded.endsWith(".png"))throw new Unsupported("Unsupported Figura asset: "+name);
                    byte[] content=read(in,request.policy().maxExpandedBytes(),ImportErrorCode.EXPANDED_LIMIT,request,quota);
                    ZipInfo expected=centralNames.get(name);
                    if(expected==null||entry.getCrc()!=expected.crc()||entry.getSize()!=expected.size()||entry.getMethod()!=expected.method())throw new IOException("Figura ZIP local and central metadata disagree");
                    files.put(key,content);
                    in.closeEntry();
                }
            }
            if(!exactNames.equals(centralNames.keySet()))throw new IOException("Figura ZIP directory differs from local entries");
            byte[] avatar=files.get(new ResourceKey("avatar.json"));
            if(avatar==null)throw new IOException("Figura avatar.json root marker is missing");
            if(avatar.length>0) {
                var metadata=BoundedJson.read(avatar,quota);
                for(String key:metadata.values().keySet())if(!Set.of("name","description","authors","version","color").contains(key))throw new Unsupported("Unsupported Figura metadata: "+key);
            }
            var keys=new ArrayList<ResourceKey>();
            for(var entry:files.entrySet())if(entry.getKey().path().endsWith(".bbmodel")) {
                var project=BoundedJson.read(entry.getValue(),quota);
                var animations=project.get("animations");
                if(animations!=null&&(!(animations instanceof JsonArray a)||!a.values().isEmpty()))throw new Unsupported("Figura animation assets are not in the static subset");
                // Embedded texture data stays owned by the existing importer. External textures may
                // only refer to literal resources present in this authorized archive.
                var textures=project.get("textures");
                if(textures instanceof JsonArray list)for(var value:list.values()) {
                    if(!(value instanceof JsonObject texture))throw new IOException("Invalid texture descriptor");
                    if(texture.get("source")!=null)throw new Unsupported("Embedded Figura texture data is outside the literal-resource subset");
                    for(String field:List.of("path","relative_path"))if(texture.get(field)!=null) {
                        if(!(texture.get(field) instanceof JsonString path))throw new IOException("Invalid texture path");
                        var textureKey=new ResourceKey(path.value());
                        if(path.value().contains("%")||!files.containsKey(textureKey))throw new IOException("Figura external texture is outside the archive");
                    }
                }
                var asset=BbmodelJsonImporter.importAsset(entry.getValue(),request.policy(),request.budget(),request.cancellation());
                partial.add(asset);keys.add(entry.getKey());
            }
            if(partial.isEmpty())throw new IOException("Figura ZIP has no supported model");
            request.cancellation().check();owned=quota.transfer();domain=new Domain(files,owned);
            var models=new ArrayList<ImportResult.Model>();Domain retained=domain;
            for(int i=0;i<partial.size();i++) {
                ModelAsset inner=partial.get(i);retained.retain();
                models.add(new ImportResult.Model(keys.get(i).path(),new ModelAsset(inner.document(),List.of(),()->{try{inner.close();}finally{retained.release();}})));
            }
            return new ImportResult(models,List.of(),retained::open,retained::closeRoot);
        } catch(Exception|Error failure) {
            partial.forEach(ModelAsset::close);
            if(domain!=null)domain.abort();else if(owned!=null)owned.forEach(ResourceBudget.Reservation::close);else quota.close();
            if(failure instanceof Error fatal)throw fatal;
            ImportErrorCode code=failure instanceof java.util.concurrent.CancellationException?ImportErrorCode.CANCELLED:
                    failure instanceof DecodeLimitException limit?limit.code():
                    failure instanceof SourceFailure?ImportErrorCode.SOURCE_IO:
                    failure instanceof Unsupported?ImportErrorCode.UNSUPPORTED_FEATURE:
                    budgetFailure(failure)?ImportErrorCode.BUDGET_EXCEEDED:ImportErrorCode.MALFORMED_INPUT;
            throw new ModelImportException(new ImportDiagnostic(ImportDiagnostic.Severity.ERROR,code,request.source().entry(),-1,String.valueOf(failure.getMessage())),failure);
        }
    }
    private record ZipInfo(long crc,long size,int method) { }
    private static Map<String,ZipInfo> validateZip(byte[] bytes,DecodePolicy policy)throws IOException {
        var in=ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);int end=-1;
        for(int p=bytes.length-22;p>=Math.max(0,bytes.length-65557);p--)if(in.getInt(p)==0x06054b50&&p+22+Short.toUnsignedInt(in.getShort(p+20))==bytes.length){end=p;break;}
        if(end<0)throw new IOException("Missing/truncated Figura ZIP central directory");
        int count=Short.toUnsignedInt(in.getShort(end+10));
        long size=Integer.toUnsignedLong(in.getInt(end+12)),offset=Integer.toUnsignedLong(in.getInt(end+16));
        if(in.getShort(end+4)!=0||in.getShort(end+6)!=0||Short.toUnsignedInt(in.getShort(end+8))!=count||count==65535||offset+size!=end||count>policy.maxEntries())throw new IOException("Unsupported Figura ZIP directory");
        int p=Math.toIntExact(offset);var names=new HashMap<String,ZipInfo>();var foldedNames=new HashSet<String>();
        for(int i=0;i<count;i++) {
            if(p>end-46||in.getInt(p)!=0x02014b50)throw new IOException("Invalid Figura ZIP directory entry");
            int length=Short.toUnsignedInt(in.getShort(p+28)),extra=Short.toUnsignedInt(in.getShort(p+30)),comment=Short.toUnsignedInt(in.getShort(p+32));
            if(length>policy.maxStringBytes()||(long)p+46+length+extra+comment>end||(in.getShort(p+8)&1)!=0)throw new IOException("Invalid/encrypted Figura ZIP directory entry");
            String name=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes,p+46,length)).toString();
            if(name.endsWith("/"))name=name.substring(0,name.length()-1);
            if(name.contains("%"))throw new IOException("Encoded Figura paths are not supported");
            name=new ResourceKey(name).path();
            if(!foldedNames.add(name.toLowerCase(Locale.ROOT)))throw new IOException("Duplicate Figura directory path");
            names.put(name,new ZipInfo(Integer.toUnsignedLong(in.getInt(p+16)),Integer.toUnsignedLong(in.getInt(p+24)),Short.toUnsignedInt(in.getShort(p+10))));
            p+=46+length+extra+comment;
        }
        if(p!=end)throw new IOException("Unexpected Figura ZIP directory data");return names;
    }
    private static byte[] read(InputStream in,long limit,ImportErrorCode code,ImportRequest request,BoundedJson.Quota quota)throws IOException {
        try(var out=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192];int count;long total=0;
            while((count=in.read(block))!=-1){request.cancellation().check();total=Math.addExact(total,count);if(total>limit)throw new DecodeLimitException(code,"Figura byte limit");quota.charge(Math.multiplyExact(2L,count));out.write(block,0,count);}
            return out.toByteArray();
        }
    }
    private static boolean budgetFailure(Throwable failure){for(Throwable t=failure;t!=null;t=t.getCause())if(t instanceof ResourceBudgetExceededException)return true;return false;}
    private static final class Unsupported extends IOException {private static final long serialVersionUID=1L;Unsupported(String message){super(message);}}
    private static final class SourceFailure extends IOException {private static final long serialVersionUID=1L;SourceFailure(IOException cause){super("Figura source could not be read",cause);}}
    private static final class Domain {
        private Map<ResourceKey,byte[]> files;private final List<ResourceBudget.Reservation> tickets;private int refs=1;private boolean rootClosed;
        Domain(Map<ResourceKey,byte[]> files,List<ResourceBudget.Reservation> tickets){this.files=Map.copyOf(files);this.tickets=tickets;}
        synchronized void retain(){if(refs==0||refs==Integer.MAX_VALUE)throw new IllegalStateException("Figura domain closed or lease capacity exceeded");refs++;}
        synchronized void release(){if(refs>0&&--refs==0){files=Map.of();tickets.forEach(ResourceBudget.Reservation::close);}}
        synchronized void closeRoot(){if(!rootClosed){rootClosed=true;release();}}
        synchronized void abort(){rootClosed=true;refs=0;files=Map.of();tickets.forEach(ResourceBudget.Reservation::close);}
        synchronized InputStream open(ResourceKey key)throws IOException {
            if(rootClosed)throw new IOException("Figura import closed");byte[] bytes=files.get(key);if(bytes==null)throw new IOException("Unknown Figura resource");retain();
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {private final java.util.concurrent.atomic.AtomicBoolean returned=new java.util.concurrent.atomic.AtomicBoolean();@Override public void close()throws IOException{if(returned.compareAndSet(false,true)){try{super.close();}finally{in=InputStream.nullInputStream();release();}}}};
        }
    }
}
