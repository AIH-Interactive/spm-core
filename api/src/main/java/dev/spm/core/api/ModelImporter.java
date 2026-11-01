package dev.spm.core.api;

/** Host-neutral parser/import transaction, independent of JNI, loaders and GPU submission. */
public interface ModelImporter {
    boolean supports(FormatHint hint);
    ImportResult importModel(ImportRequest request) throws ModelImportException;
}
