package dev.spm.core.api;

/** Stable machine-readable category; free-form decoder messages are not a protocol. */
public enum ImportErrorCode {
    SOURCE_IO, INPUT_LIMIT, EXPANDED_LIMIT, RESOURCE_LIMIT, OBJECT_LIMIT, COLLECTION_LIMIT, STRING_LIMIT, DEPTH_LIMIT, MALFORMED_INPUT,
    UNSUPPORTED_FORMAT, UNSUPPORTED_FEATURE, BUDGET_EXCEEDED, CANCELLED, INTERNAL
}
