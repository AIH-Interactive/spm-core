package dev.spm.core.acceleration;

import java.util.Objects;

/** Successful native result: exact count, actual ISA and owned logical positions. */
public record TransformResult(float[] positions,int completed,Isa isa) {
    public TransformResult {
        positions=positions.clone(); Objects.requireNonNull(isa);
        if(completed<0 || (long)completed*3!=positions.length || isa==Isa.AUTO) throw new IllegalArgumentException("Invalid compute outcome");
        for(float v:positions) if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite result");
    }
    @Override public float[] positions() { return positions.clone(); }
}
