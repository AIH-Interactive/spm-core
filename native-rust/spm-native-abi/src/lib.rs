//! Versioned logical CPU compute contract; no game or GPU types.
pub const ABI_VERSION: u32 = 1;
/// JNI revision 3 includes RGBA, subtree extraction and complete rigid vertex batches.
pub const JNI_ABI_VERSION: u32 = 3;

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
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(C)]
pub struct ComputeOutcome {
    pub status: i32,
    pub completed: u32,
    pub isa: u32,
    pub abi: u32,
}
