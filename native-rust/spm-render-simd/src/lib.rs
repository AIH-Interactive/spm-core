//! Explicit SIMD is Rust-only. Dispatch once per batch, from a portable entry.
//!
//! # Reported ISA
//!
//! Every kernel returns the ISA tier whose instructions it *actually executed*, which is never
//! stricter than the requested tier. This matters because a tier can be requested without having
//! a kernel of its own (see `bones::executed`), and reporting the request instead of the executed
//! tier would let a caller believe wider instructions ran than really did.
use spm_native_abi::{Isa, Status};
mod bones;
mod rigid;
pub use bones::{evaluate_bones, evaluate_bones_subtree, BoneInput, BoneOutput};
pub use rigid::{render_rigid, RenderResult, RenderedVertex, RigidVertex};

/// Identity of the code that serves an ISA tier.
///
/// This exists so the "which tier really ran?" question has a single, explicit answer that does
/// **not** depend on comparing function addresses. Address comparison is defeated by
/// identical-code folding, which may give two distinct kernels one address and would hide exactly
/// the defect these kernels must not have. `KernelId` is a value the compiler cannot merge.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KernelId {
    Scalar,
    Sse2,
    Avx,
    Avx2,
    Avx2Fma,
    Neon,
    Avx512,
}

impl KernelId {
    /// The tier this kernel executes. A kernel assumes no instructions beyond this tier.
    pub const fn tier(self) -> Isa {
        match self {
            Self::Scalar => Isa::Scalar,
            Self::Sse2 => Isa::Sse2,
            Self::Avx => Isa::Avx,
            Self::Avx2 => Isa::Avx2,
            Self::Avx2Fma => Isa::Avx2Fma,
            Self::Neon => Isa::Neon,
            Self::Avx512 => Isa::Avx512,
        }
    }
}

/// The kernel identity each of the three kernels uses for `isa`, for the N13 regression test.
pub fn kernel_ids(isa: Isa) -> (KernelId, KernelId, KernelId) {
    (kernel(isa).1, bones::kernel_id(isa), rigid::kernel_id(isa))
}

/// The tier each kernel reports for a given request, without needing a batch.
///
/// This is defined as `kernel(...).1.tier()`, so the reported ISA is *derived from* the kernel
/// selection rather than asserted alongside it. That is the structural form of the N13 fix: there
/// is no second place to update, so a report cannot drift from the code that ran.
///
/// `Auto` is a request, not a tier, so it is resolved through the same `select` order a real call
/// uses; the answer is therefore the tier `Auto` would actually execute rather than a guess.
pub fn executed(isa: Isa) -> Isa {
    let concrete = select(isa).unwrap_or(Isa::Scalar);
    let resolved = kernel(concrete).1.tier();
    debug_assert_eq!(resolved, bones::executed(concrete));
    debug_assert_eq!(resolved, rigid::executed(concrete));
    resolved
}

pub fn supported(isa: Isa) -> bool {
    match isa {
        Isa::Auto | Isa::Scalar => true,
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        Isa::Sse2 => std::is_x86_feature_detected!("sse2"),
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        Isa::Avx => std::is_x86_feature_detected!("avx"),
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        Isa::Avx2 => std::is_x86_feature_detected!("avx2"),
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        Isa::Avx2Fma => {
            std::is_x86_feature_detected!("avx2") && std::is_x86_feature_detected!("fma")
        }
        // AVX-512 is x86_64-only in this crate: 32-bit x86 has no enabled avx512 target feature.
        #[cfg(target_arch = "x86_64")]
        Isa::Avx512 => std::is_x86_feature_detected!("avx512f"),
        #[cfg(target_arch = "aarch64")]
        Isa::Neon => std::arch::is_aarch64_feature_detected!("neon"),
        _ => false,
    }
}

pub fn select(request: Isa) -> Result<Isa, Status> {
    if request != Isa::Auto {
        return if supported(request) {
            Ok(request)
        } else {
            Err(Status::UnsupportedIsa)
        };
    }
    // Conservative widest available non-FMA path; FMA and AVX-512 remain explicitly selectable.
    // AVX-512 is deliberately excluded: it is a wider tier with a frequency/power cost on the
    // parts that have it, so `Auto` must not adopt it without a caller-visible decision.
    for candidate in [Isa::Avx2, Isa::Avx, Isa::Sse2, Isa::Neon, Isa::Scalar] {
        if supported(candidate) {
            return Ok(candidate);
        }
    }
    Ok(Isa::Scalar)
}

/// Affine transform of `input.len() / 3` positions, writing `input.len()` values into `output`.
///
/// The output buffer is allocated once, fallibly, by the caller-facing entry; every kernel writes
/// into it and none of them allocate.
pub fn transform_positions(
    m: &[f32; 16],
    input: &[f32],
    request: Isa,
) -> Result<(Vec<f32>, Isa), Status> {
    if !input.len().is_multiple_of(3) {
        return Err(Status::InvalidInput);
    }
    if m.iter().chain(input).any(|v| !v.is_finite()) {
        return Err(Status::NonFinite);
    }
    let isa = select(request)?;
    let mut output = Vec::new();
    output
        .try_reserve_exact(input.len())
        .map_err(|_| Status::Internal)?;
    output.resize(input.len(), 0.);
    let (run, id) = kernel(isa);
    // SAFETY: `select` verified every feature the resolved kernel requires.
    unsafe { run(m, input, &mut output) };
    if output.iter().any(|v| !v.is_finite()) {
        return Err(Status::NonFinite);
    }
    Ok((output, id.tier()))
}

/// A transform kernel writing `input.len()` values into an exactly-sized `output`.
type TransformKernel = unsafe fn(&[f32; 16], &[f32], &mut [f32]);

/// Resolves a tier to the kernel that serves it **and** the identity of that kernel.
///
/// Declaring both halves in one place is what keeps the reported ISA honest: a caller cannot use
/// the kernel without also receiving the identity it actually runs under (defect N13). The
/// reported tier is then `KernelId::tier()`, a projection of this one decision.
fn kernel(isa: Isa) -> (TransformKernel, crate::KernelId) {
    use crate::KernelId;
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        match isa {
            Isa::Sse2 => return (x86::sse2, KernelId::Sse2),
            Isa::Avx => return (x86::avx, KernelId::Avx),
            // A distinct AVX2 kernel deinterleaves with `vgatherdps` on ymm registers, an
            // AVX2-only instruction, so an AVX2 request really is served at AVX2 here.
            Isa::Avx2 => return (x86::avx2, KernelId::Avx2),
            Isa::Avx2Fma => return (x86::fma, KernelId::Avx2Fma),
            #[cfg(target_arch = "x86_64")]
            Isa::Avx512 => return (x86::avx512, KernelId::Avx512),
            _ => {}
        }
    }
    #[cfg(target_arch = "aarch64")]
    if isa == Isa::Neon {
        return (neon, KernelId::Neon);
    }
    (scalar, KernelId::Scalar)
}

/// Portable fallback and the differential reference for every instruction-set tier.
unsafe fn scalar(m: &[f32; 16], input: &[f32], output: &mut [f32]) {
    spm_render_scalar::transform_positions_into(m, input, output);
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
mod x86 {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;

    /// Arithmetic order matches the scalar oracle exactly: `((c0*x + c1*y) + c2*z) + c3`.
    #[target_feature(enable = "sse2")]
    pub unsafe fn sse2(m: &[f32; 16], input: &[f32], output: &mut [f32]) {
        let c0 = _mm_loadu_ps(m.as_ptr());
        let c1 = _mm_loadu_ps(m.as_ptr().add(4));
        let c2 = _mm_loadu_ps(m.as_ptr().add(8));
        let c3 = _mm_loadu_ps(m.as_ptr().add(12));
        for (index, p) in input.as_chunks::<3>().0.iter().enumerate() {
            let v = _mm_add_ps(
                _mm_add_ps(
                    _mm_add_ps(
                        _mm_mul_ps(c0, _mm_set1_ps(p[0])),
                        _mm_mul_ps(c1, _mm_set1_ps(p[1])),
                    ),
                    _mm_mul_ps(c2, _mm_set1_ps(p[2])),
                ),
                c3,
            );
            let mut lanes = [0.; 4];
            _mm_storeu_ps(lanes.as_mut_ptr(), v);
            output[index * 3..index * 3 + 3].copy_from_slice(&lanes[..3]);
        }
    }

    #[target_feature(enable = "avx")]
    pub unsafe fn avx(m: &[f32; 16], input: &[f32], output: &mut [f32]) {
        let vertices = input.len() / 3;
        let mut offset = 0;
        while offset + 8 <= vertices {
            let base = offset * 3;
            let mut axes = [[0.; 8]; 3];
            for lane in 0..8 {
                for axis in 0..3 {
                    axes[axis][lane] = input[base + lane * 3 + axis];
                }
            }
            let x = _mm256_loadu_ps(axes[0].as_ptr());
            let y = _mm256_loadu_ps(axes[1].as_ptr());
            let z = _mm256_loadu_ps(axes[2].as_ptr());
            for row in 0..3 {
                let v = _mm256_add_ps(
                    _mm256_add_ps(
                        _mm256_add_ps(
                            _mm256_mul_ps(_mm256_set1_ps(m[row]), x),
                            _mm256_mul_ps(_mm256_set1_ps(m[4 + row]), y),
                        ),
                        _mm256_mul_ps(_mm256_set1_ps(m[8 + row]), z),
                    ),
                    _mm256_set1_ps(m[12 + row]),
                );
                let mut lanes = [0.; 8];
                _mm256_storeu_ps(lanes.as_mut_ptr(), v);
                for lane in 0..8 {
                    output[(offset + lane) * 3 + row] = lanes[lane];
                }
            }
            offset += 8;
        }
        super::spm_tail(m, offset, input, output);
    }

    /// AVX2 tier: 8 stationary interleaved positions are deinterleaved by hardware gather instead
    /// of by the scalar transpose the AVX1 path needs. `vgatherdps` does not exist before AVX2.
    #[target_feature(enable = "avx2")]
    pub unsafe fn avx2(m: &[f32; 16], input: &[f32], output: &mut [f32]) {
        let vertices = input.len() / 3;
        let mut offset = 0;
        let index = _mm256_setr_epi32(0, 3, 6, 9, 12, 15, 18, 21);
        while offset + 8 <= vertices {
            let base = input.as_ptr().add(offset * 3);
            let x = _mm256_i32gather_ps::<4>(base, index);
            let y = _mm256_i32gather_ps::<4>(base.add(1), index);
            let z = _mm256_i32gather_ps::<4>(base.add(2), index);
            for row in 0..3 {
                let v = _mm256_add_ps(
                    _mm256_add_ps(
                        _mm256_add_ps(
                            _mm256_mul_ps(_mm256_set1_ps(m[row]), x),
                            _mm256_mul_ps(_mm256_set1_ps(m[4 + row]), y),
                        ),
                        _mm256_mul_ps(_mm256_set1_ps(m[8 + row]), z),
                    ),
                    _mm256_set1_ps(m[12 + row]),
                );
                let mut lanes = [0.; 8];
                _mm256_storeu_ps(lanes.as_mut_ptr(), v);
                for lane in 0..8 {
                    output[(offset + lane) * 3 + row] = lanes[lane];
                }
            }
            offset += 8;
        }
        super::spm_tail(m, offset, input, output);
    }

    /// FMA tier. Fused multiply-add changes rounding versus the unfused tiers; that is why FMA is
    /// only ever reached through an explicit request and is never selected by `Auto`.
    #[target_feature(enable = "avx2,fma")]
    pub unsafe fn fma(m: &[f32; 16], input: &[f32], output: &mut [f32]) {
        let vertices = input.len() / 3;
        let mut offset = 0;
        while offset + 8 <= vertices {
            let base = offset * 3;
            let mut axes = [[0.; 8]; 3];
            for lane in 0..8 {
                for axis in 0..3 {
                    axes[axis][lane] = input[base + lane * 3 + axis];
                }
            }
            let x = _mm256_loadu_ps(axes[0].as_ptr());
            let y = _mm256_loadu_ps(axes[1].as_ptr());
            let z = _mm256_loadu_ps(axes[2].as_ptr());
            for row in 0..3 {
                let first = _mm256_fmadd_ps(
                    _mm256_set1_ps(m[4 + row]),
                    y,
                    _mm256_mul_ps(_mm256_set1_ps(m[row]), x),
                );
                let v = _mm256_add_ps(
                    _mm256_fmadd_ps(_mm256_set1_ps(m[8 + row]), z, first),
                    _mm256_set1_ps(m[12 + row]),
                );
                let mut lanes = [0.; 8];
                _mm256_storeu_ps(lanes.as_mut_ptr(), v);
                for lane in 0..8 {
                    output[(offset + lane) * 3 + row] = lanes[lane];
                }
            }
            offset += 8;
        }
        super::spm_tail(m, offset, input, output);
    }

    /// AVX-512 tier: 16 positions per iteration, both deinterleaved and stored by index.
    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "avx512f")]
    pub unsafe fn avx512(m: &[f32; 16], input: &[f32], output: &mut [f32]) {
        let vertices = input.len() / 3;
        let mut offset = 0;
        let index = _mm512_setr_epi32(0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36, 39, 42, 45);
        while offset + 16 <= vertices {
            let base = input.as_ptr().add(offset * 3);
            let x = _mm512_i32gather_ps(index, base, 4);
            let y = _mm512_i32gather_ps(index, base.add(1), 4);
            let z = _mm512_i32gather_ps(index, base.add(2), 4);
            for row in 0..3 {
                let v = _mm512_add_ps(
                    _mm512_add_ps(
                        _mm512_add_ps(
                            _mm512_mul_ps(_mm512_set1_ps(m[row]), x),
                            _mm512_mul_ps(_mm512_set1_ps(m[4 + row]), y),
                        ),
                        _mm512_mul_ps(_mm512_set1_ps(m[8 + row]), z),
                    ),
                    _mm512_set1_ps(m[12 + row]),
                );
                let mut lanes = [0.; 16];
                _mm512_storeu_ps(lanes.as_mut_ptr(), v);
                for lane in 0..16 {
                    output[(offset + lane) * 3 + row] = lanes[lane];
                }
            }
            offset += 16;
        }
        super::spm_tail(m, offset, input, output);
    }
}

/// The tail below the widest vector is always delegated to the scalar oracle, so every tier shares
/// one rounding rule for the leftover positions.
#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
fn spm_tail(m: &[f32; 16], offset: usize, input: &[f32], output: &mut [f32]) {
    spm_render_scalar::transform_positions_into(m, &input[offset * 3..], &mut output[offset * 3..]);
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn neon(m: &[f32; 16], input: &[f32], output: &mut [f32]) {
    use std::arch::aarch64::*;
    let a = vld1q_f32(m.as_ptr());
    let b = vld1q_f32(m.as_ptr().add(4));
    let c = vld1q_f32(m.as_ptr().add(8));
    let d = vld1q_f32(m.as_ptr().add(12));
    for (index, p) in input.as_chunks::<3>().0.iter().enumerate() {
        let v = vaddq_f32(
            vaddq_f32(
                vaddq_f32(vmulq_n_f32(a, p[0]), vmulq_n_f32(b, p[1])),
                vmulq_n_f32(c, p[2]),
            ),
            d,
        );
        let mut lanes = [0.; 4];
        vst1q_f32(lanes.as_mut_ptr(), v);
        output[index * 3..index * 3 + 3].copy_from_slice(&lanes[..3]);
    }
}
