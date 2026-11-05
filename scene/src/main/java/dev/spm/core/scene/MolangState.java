package dev.spm.core.scene;

import java.util.Map;

/** Owner-private complete script candidate. Copies isolate failed frames from committed frames. */
final class MolangState {
    final Map<String,MolangValue> variables;
    final MolangPhysics physics;
    MolangState() { this(Map.of(),new MolangPhysics()); }
    MolangState(Map<String,MolangValue> variables,MolangPhysics physics) {
        this.variables=Map.copyOf(variables); this.physics=physics.copy();
    }
}
