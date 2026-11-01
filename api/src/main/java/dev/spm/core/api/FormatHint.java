package dev.spm.core.api;

/** File/payload domains are distinct. AUTO never guesses a headerless compiled payload. */
public enum FormatHint { AUTO, YSM, YSM_PAYLOAD, BEDROCK, BBMODEL, V3D, GLTF, GLB }
