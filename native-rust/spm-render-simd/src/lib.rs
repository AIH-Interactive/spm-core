//! Explicit SIMD is Rust-only. Dispatch once per batch, from a portable entry.
use spm_native_abi::{Isa, Status};
mod bones;
mod rigid;
pub use bones::{evaluate_bones, evaluate_bones_subtree, BoneInput, BoneOutput};
pub use rigid::{render_rigid, RenderResult, RenderedVertex, RigidVertex};

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
    // Conservative widest available non-FMA path; FMA remains explicitly selectable.
    for candidate in [Isa::Avx2, Isa::Avx, Isa::Sse2, Isa::Neon, Isa::Scalar] {
        if supported(candidate) {
            return Ok(candidate);
        }
    }
    Ok(Isa::Scalar)
}

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
    let output = match isa {
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        Isa::Sse2 => unsafe { x86::sse2(m, input) },
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        Isa::Avx | Isa::Avx2 => unsafe { x86::avx(m, input) },
        #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
        Isa::Avx2Fma => unsafe { x86::fma(m, input) },
        #[cfg(target_arch = "aarch64")]
        Isa::Neon => unsafe { neon(m, input) },
        _ => spm_render_scalar::transform_positions(m, input),
    };
    if output.iter().any(|v| !v.is_finite()) {
        return Err(Status::NonFinite);
    }
    Ok((output, isa))
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
mod x86 {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;

    #[target_feature(enable = "sse2")]
    pub unsafe fn sse2(m: &[f32; 16], input: &[f32]) -> Vec<f32> {
        let c0 = _mm_loadu_ps(m.as_ptr());
        let c1 = _mm_loadu_ps(m.as_ptr().add(4));
        let c2 = _mm_loadu_ps(m.as_ptr().add(8));
        let c3 = _mm_loadu_ps(m.as_ptr().add(12));
        let mut output = Vec::with_capacity(input.len());
        for p in input.as_chunks::<3>().0 {
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
            output.extend_from_slice(&lanes[..3]);
        }
        output
    }

    #[target_feature(enable = "avx")]
    pub unsafe fn avx(m: &[f32; 16], input: &[f32]) -> Vec<f32> {
        avx_impl(m, input, false)
    }
    #[target_feature(enable = "avx2,fma")]
    pub unsafe fn fma(m: &[f32; 16], input: &[f32]) -> Vec<f32> {
        avx_impl(m, input, true)
    }

    #[target_feature(enable = "avx")]
    unsafe fn avx_impl(m: &[f32; 16], input: &[f32], fused: bool) -> Vec<f32> {
        let mut output = vec![0.; input.len()];
        let vertices = input.len() / 3;
        let mut offset = 0;
        while offset + 8 <= vertices {
            let mut axes = [[0.; 8]; 3];
            for lane in 0..8 {
                for axis in 0..3 {
                    axes[axis][lane] = input[(offset + lane) * 3 + axis];
                }
            }
            let x = _mm256_loadu_ps(axes[0].as_ptr());
            let y = _mm256_loadu_ps(axes[1].as_ptr());
            let z = _mm256_loadu_ps(axes[2].as_ptr());
            for row in 0..3 {
                let v = if fused {
                    // Dispatch above checks both AVX2 and FMA before this branch.
                    fused_row(m, row, x, y, z)
                } else {
                    _mm256_add_ps(
                        _mm256_add_ps(
                            _mm256_add_ps(
                                _mm256_mul_ps(_mm256_set1_ps(m[row]), x),
                                _mm256_mul_ps(_mm256_set1_ps(m[4 + row]), y),
                            ),
                            _mm256_mul_ps(_mm256_set1_ps(m[8 + row]), z),
                        ),
                        _mm256_set1_ps(m[12 + row]),
                    )
                };
                let mut lanes = [0.; 8];
                _mm256_storeu_ps(lanes.as_mut_ptr(), v);
                for lane in 0..8 {
                    output[(offset + lane) * 3 + row] = lanes[lane];
                }
            }
            offset += 8;
        }
        let tail = super::spm_tail(m, &input[offset * 3..]);
        output[offset * 3..].copy_from_slice(&tail);
        output
    }

    #[target_feature(enable = "avx2,fma")]
    unsafe fn fused_row(m: &[f32; 16], row: usize, x: __m256, y: __m256, z: __m256) -> __m256 {
        let first = _mm256_fmadd_ps(
            _mm256_set1_ps(m[4 + row]),
            y,
            _mm256_mul_ps(_mm256_set1_ps(m[row]), x),
        );
        _mm256_add_ps(
            _mm256_fmadd_ps(_mm256_set1_ps(m[8 + row]), z, first),
            _mm256_set1_ps(m[12 + row]),
        )
    }
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
fn spm_tail(m: &[f32; 16], input: &[f32]) -> Vec<f32> {
    spm_render_scalar::transform_positions(m, input)
}

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn neon(m: &[f32; 16], input: &[f32]) -> Vec<f32> {
    use std::arch::aarch64::*;
    let a = vld1q_f32(m.as_ptr());
    let b = vld1q_f32(m.as_ptr().add(4));
    let c = vld1q_f32(m.as_ptr().add(8));
    let d = vld1q_f32(m.as_ptr().add(12));
    let mut output = Vec::with_capacity(input.len());
    for p in input.as_chunks::<3>().0 {
        let v = vaddq_f32(
            vaddq_f32(
                vaddq_f32(vmulq_n_f32(a, p[0]), vmulq_n_f32(b, p[1])),
                vmulq_n_f32(c, p[2]),
            ),
            d,
        );
        let mut lanes = [0.; 4];
        vst1q_f32(lanes.as_mut_ptr(), v);
        output.extend_from_slice(&lanes[..3]);
    }
    output
}
