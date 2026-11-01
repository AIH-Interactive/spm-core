package dev.spm.core.acceleration;

/** Native ABI v1 feature requests. AUTO is a request, never an observed ISA. */
public enum Isa {
    AUTO(0), SCALAR(1), SSE2(2), AVX(3), AVX2(4), AVX2_FMA(5), NEON(6);
    private final int code;
    Isa(int code) { this.code=code; }
    public int code() { return code; }
    public static Isa fromCode(int code) {
        for(Isa isa:values()) if(isa.code==code) return isa;
        throw new IllegalArgumentException("Unknown ISA code: "+code);
    }
}
