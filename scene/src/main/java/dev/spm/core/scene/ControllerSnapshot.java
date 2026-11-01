package dev.spm.core.scene;

/** Committed controller state; time is seconds since the last admitted state transition. */
public record ControllerSnapshot(String state, double stateTimeSeconds, double blendProgress) { }
