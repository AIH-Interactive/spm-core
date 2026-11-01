package dev.spm.core.api;

import java.util.List;
import java.util.Objects;

/** Owns all imported variants and their resource domain. Close retires assets, not active frames. */
public final class ImportResult implements AutoCloseable {
    public record Model(String key,ModelAsset asset) {
        public Model {if(Objects.requireNonNull(key).isBlank())throw new IllegalArgumentException("Missing variant key");Objects.requireNonNull(asset);}
    }
    private List<Model> models;
    private final List<ImportDiagnostic> diagnostics;
    private final ResourceResolver resources;
    private final Runnable releaseResources;
    public ImportResult(List<Model> models,List<ImportDiagnostic> diagnostics,ResourceResolver resources,Runnable releaseResources) {
        this.models=List.copyOf(models);this.diagnostics=List.copyOf(diagnostics);
        this.resources=Objects.requireNonNull(resources);this.releaseResources=Objects.requireNonNull(releaseResources);
    }
    private void requireOpen(){if(models==null)throw new IllegalStateException("Import result closed");}
    public synchronized List<Model> models(){requireOpen();return models;}
    public synchronized List<ImportDiagnostic> diagnostics(){requireOpen();return diagnostics;}
    /** Resolver implementations retain their backing owner for every open stream. */
    public synchronized ResourceResolver resources(){requireOpen();return resources;}
    @Override public void close() {
        List<Model> retired;
        synchronized(this){if(models==null)return;retired=models;models=null;}
        Throwable failure=null;
        for(var model:retired)try{model.asset().close();}catch(RuntimeException|Error problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        try{releaseResources.run();}catch(RuntimeException|Error problem){if(failure==null)failure=problem;else failure.addSuppressed(problem);}
        if(failure instanceof Error fatal)throw fatal;
        if(failure instanceof RuntimeException problem)throw problem;
    }
}
