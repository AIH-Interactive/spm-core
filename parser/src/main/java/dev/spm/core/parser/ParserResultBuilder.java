package dev.spm.core.parser;

import dev.spm.core.api.*;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Transactional ownership glue shared by format adapters; never copies a model or texture payload. */
final class ParserResultBuilder implements AutoCloseable {
    private final SharedState state=new SharedState();
    private final List<ImportResult.Model> models=new ArrayList<>();
    private final List<ImportDiagnostic> diagnostics=new ArrayList<>();
    private ResourceResolver resolver=key->{throw new IOException("Unknown imported resource: "+key.path());};
    private boolean published;
    void retain(AutoCloseable owner){
        Objects.requireNonNull(owner);
        try{state.retain(owner);}catch(RuntimeException|Error failure){
            try{owner.close();}catch(Exception|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    void add(String key,ModelDocument document){
        Runnable release=state.pin();
        try{models.add(new ImportResult.Model(key,new ModelAsset(document,List.of(),release)));}
        catch(RuntimeException|Error failure){release.run();throw failure;}
    }
    void diagnostic(ImportDiagnostic value){diagnostics.add(Objects.requireNonNull(value));}
    void resources(ResourceResolver resolver){this.resolver=Objects.requireNonNull(resolver);}
    ImportResult finish(){
        var resourceSource=resolver;
        ResourceResolver guarded=key->{
            Runnable release=state.pin();
            try{
                InputStream input=Objects.requireNonNull(resourceSource.open(key));
                return new FilterInputStream(input){
                    private boolean closed;
                    @Override public synchronized void close() throws IOException{
                        if(closed)return;closed=true;
                        try{super.close();}finally{release.run();}
                    }
                };
            }catch(IOException|RuntimeException|Error failure){release.run();throw failure;}
        };
        var result=new ImportResult(models,diagnostics,guarded,state::release);
        published=true;return result;
    }
    @Override public void close(){
        if(published)return;
        try{for(var model:models)model.asset().close();}finally{state.release();}
        published=true;
    }
    private static final class SharedState {
        private List<AutoCloseable> owners=new ArrayList<>();
        private int references=1;
        synchronized void retain(AutoCloseable owner){if(owners==null)throw new IllegalStateException("Import retired");owners.add(Objects.requireNonNull(owner));}
        synchronized Runnable pin(){
            if(references==0)throw new IllegalStateException("Import retired");
            if(references==Integer.MAX_VALUE)throw new IllegalStateException("Too many import leases");
            var once=new java.util.concurrent.atomic.AtomicBoolean();
            Runnable release=()->{if(once.compareAndSet(false,true))release();};references++;return release;
        }
        void release(){
            List<AutoCloseable> retired;
            synchronized(this){if(references==0)return;if(--references!=0)return;retired=owners;owners=null;}
            Throwable failure=null;
            for(int i=retired.size()-1;i>=0;i--)try{retired.get(i).close();}catch(Exception|Error problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
            if(failure instanceof Error fatal)throw fatal;
            if(failure!=null)throw new IllegalStateException("Imported resource cleanup failed",failure);
        }
    }
}
