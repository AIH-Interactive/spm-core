//! Versioned logical CPU compute contract; no game or GPU types.
pub const ABI_VERSION: u32 = 1;
/// JNI revision 4 adds deterministic call-scoped native arena ownership.
///
/// JNI revision 5 adds the compiled-skeleton handle family (`NativePackedBoneEvaluator`): a
/// persistent, generation-encoded skeleton lease plus a zero-copy packed 144-byte-per-bone
/// evaluation entry that writes straight into a caller-owned direct buffer. Every revision-4
/// entry point keeps its exact signature and semantics, so a revision-5 library still serves a
/// revision-4 caller that only uses the older functions.
pub const JNI_ABI_VERSION: u32 = 5;

/// Requested or observed instruction set. `Auto` is a request and is never observed.
///
/// A reported ISA is the tier whose instructions a kernel actually executed; it never exceeds
/// the selected request. Codes are a stable wire format and must match the Java enum exactly.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum Isa {
    Auto = 0,
    Scalar = 1,
    Sse2 = 2,
    Avx = 3,
    Avx2 = 4,
    Avx2Fma = 5,
    Neon = 6,
    /// x86 AVX-512 Foundation (`avx512f`). Code 7 continues the existing 0..=6 sequence.
    Avx512 = 7,
}

impl TryFrom<u32> for Isa {
    type Error = Status;
    fn try_from(value: u32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::Auto),
            1 => Ok(Self::Scalar),
            2 => Ok(Self::Sse2),
            3 => Ok(Self::Avx),
            4 => Ok(Self::Avx2),
            5 => Ok(Self::Avx2Fma),
            6 => Ok(Self::Neon),
            7 => Ok(Self::Avx512),
            _ => Err(Status::UnsupportedIsa),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum Status {
    InvalidInput = -1,
    UnsupportedIsa = -2,
    NonFinite = -3,
    BufferTooSmall = -4,
    Internal = -5,
    /// A compiled-skeleton handle is unknown, already freed, or from a retired generation.
    ///
    /// This is deliberately distinct from `InvalidInput`: a stale handle is a caller lifetime
    /// defect, not malformed data, and the two must not be conflated in diagnostics.
    InvalidHandle = -6,
}

/// Tail encoding of the packed 144-byte-per-bone record.
///
/// The record is nine `vec4` slots: a column-major `mat4` transform, a column-major `mat4`
/// normal matrix whose fourth row and column are `(0,0,0,1)`, and one `vec4` tail. Only the tail
/// differs between the two host layouts, so the layout is a pure numeric choice here and no
/// GPU, shader or host type is named.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum PackedLayout {
    /// Tail is four `f32`: `[light, hidden, 0, 0]`.
    FloatTail = 1,
    /// Tail is four `i32`: `[light, hidden, 0, 0]`.
    IntTail = 2,
}

impl TryFrom<u32> for PackedLayout {
    type Error = Status;
    fn try_from(value: u32) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::FloatTail),
            2 => Ok(Self::IntTail),
            _ => Err(Status::InvalidInput),
        }
    }
}

/// Which bones a packed evaluation writes as visible.
///
/// A filtered-out bone is written as the canonical hidden record (identity transform, identity
/// normal, hidden flag set) rather than being skipped, so the caller's buffer stays fully
/// defined for every bone index and a stale record can never survive from an earlier frame.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum BonePassFilter {
    /// Every bone is written with its own visibility.
    All = 0,
    /// Only non-glow bones stay visible; glow bones are written hidden.
    NonGlow = 1,
    /// Only glow bones stay visible; non-glow bones are written hidden.
    Glow = 2,
}

impl TryFrom<u32> for BonePassFilter {
    type Error = Status;
    fn try_from(value: u32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::All),
            1 => Ok(Self::NonGlow),
            2 => Ok(Self::Glow),
            _ => Err(Status::InvalidInput),
        }
    }
}

/// Bytes per packed bone record: nine `vec4` slots.
pub const PACKED_BONE_STRIDE: usize = 144;

/// Floats per bone in the caller's `boneParams` array, matching the host's existing layout.
pub const BONE_PARAM_STRIDE: usize = 12;

/// Floats of root data: a 4x4 pose followed by a 3x3 normal matrix.
pub const ROOT_FLOATS: usize = 25;

/// Bit 0 of the packed-evaluation flags selects the tail encoding; bits 1..2 select the pass.
pub const PACKED_FLAG_FLOAT_TAIL: u32 = 1;
pub const PACKED_FLAG_PASS_SHIFT: u32 = 1;
pub const PACKED_FLAG_PASS_MASK: u32 = 0b11;

/// Decodes the packed-evaluation flag word into its two independent choices.
pub fn decode_packed_flags(flags: u32) -> Result<(PackedLayout, BonePassFilter), Status> {
    let layout = if flags & PACKED_FLAG_FLOAT_TAIL != 0 {
        PackedLayout::FloatTail
    } else {
        PackedLayout::IntTail
    };
    let pass = BonePassFilter::try_from((flags >> PACKED_FLAG_PASS_SHIFT) & PACKED_FLAG_PASS_MASK)?;
    Ok((layout, pass))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(C)]
pub struct ComputeOutcome {
    pub status: i32,
    pub completed: u32,
    pub isa: u32,
    pub abi: u32,
}
