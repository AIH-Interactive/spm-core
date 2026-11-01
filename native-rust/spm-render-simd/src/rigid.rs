use crate::BoneOutput;
use spm_native_abi::{Isa, Status};

#[derive(Clone, Copy, Debug)]
pub struct RigidVertex {
    pub position: [f32; 3],
    pub normal: [f32; 3],
    pub bone: u32,
    pub color: u32,
}

#[derive(Clone, Copy, Debug)]
pub struct RenderedVertex {
    pub position: [f32; 3],
    pub normal: [f32; 3],
    pub bone: u32,
    pub glow: u32,
    pub color: u32,
}

#[derive(Debug)]
pub struct RenderResult {
    pub vertices: Vec<RenderedVertex>,
    pub isa: Isa,
}

/// Pure rigid rendering. All input validation precedes allocation and computation.
/// Output is staged in native-owned memory and only returned after every vertex succeeds.
pub fn render_rigid(
    input: &[RigidVertex],
    bones: &[BoneOutput],
    request: Isa,
) -> Result<RenderResult, Status> {
    let isa = crate::select(request)?;
    for bone in bones {
        if bone.glow > 15 && bone.glow != 255 {
            return Err(Status::InvalidInput);
        }
        if bone
            .transform
            .iter()
            .chain(&bone.normal)
            .chain(&bone.locator)
            .any(|v| !v.is_finite())
        {
            return Err(Status::NonFinite);
        }
    }
    let mut count = 0;
    for vertex in input {
        let bone = bones
            .get(vertex.bone as usize)
            .ok_or(Status::InvalidInput)?;
        if vertex
            .position
            .iter()
            .chain(&vertex.normal)
            .any(|v| !v.is_finite())
        {
            return Err(Status::NonFinite);
        }
        count += usize::from(bone.visible);
    }
    // The kernel and the tier it reports come from one resolution, so the reported ISA is the one
    // the executing code really uses (defect N13).
    let (kernel, id) = kernel(isa);
    let mut output = Vec::new();
    output
        .try_reserve_exact(count)
        .map_err(|_| Status::Internal)?;
    for vertex in input {
        let bone = &bones[vertex.bone as usize];
        if !bone.visible {
            continue;
        }
        // SAFETY: select() has checked every feature required by the selected kernel.
        let value = unsafe { kernel(vertex, bone) };
        let mut position = [0.; 3];
        for (dst, src) in position.iter_mut().zip(value.position) {
            *dst = finite(src)?;
        }
        let length = value.normal[0]
            .hypot(value.normal[1])
            .hypot(value.normal[2]);
        if !length.is_finite() {
            return Err(Status::NonFinite);
        }
        let mut normal = [0.; 3];
        if length != 0. {
            for (dst, src) in normal.iter_mut().zip(value.normal) {
                *dst = finite(src / length)?;
            }
        }
        output.push(RenderedVertex {
            position,
            normal,
            bone: vertex.bone,
            glow: bone.glow,
            color: value.color,
        });
    }
    Ok(RenderResult {
        vertices: output,
        isa: id.tier(),
    })
}

struct Intermediate {
    position: [f64; 3],
    normal: [f64; 3],
    color: u32,
}

fn finite(value: f64) -> Result<f32, Status> {
    let value = value as f32;
    if value.is_finite() {
        Ok(value)
    } else {
        Err(Status::NonFinite)
    }
}

fn column(matrix: &[f32], stride: usize, index: usize) -> [f64; 4] {
    let mut result = [0.; 4];
    for row in 0..stride {
        result[row] = matrix[index * stride + row] as f64;
    }
    result
}

unsafe fn scalar(vertex: &RigidVertex, bone: &BoneOutput) -> Intermediate {
    let mut position = [0.; 3];
    let mut normal = [0.; 3];
    for row in 0..3 {
        position[row] = bone.transform[12 + row] as f64;
        for axis in 0..3 {
            position[row] += bone.transform[axis * 4 + row] as f64 * vertex.position[axis] as f64;
            normal[row] += bone.normal[axis * 3 + row] as f64 * vertex.normal[axis] as f64;
        }
    }
    let mut color = 0;
    for shift in [0, 8, 16, 24] {
        let value = (((vertex.color >> shift) & 255) * ((bone.color >> shift) & 255) + 127) / 255;
        color |= value << shift;
    }
    Intermediate {
        position,
        normal,
        color,
    }
}

type VertexKernel = unsafe fn(&RigidVertex, &BoneOutput) -> Intermediate;

/// Resolves a tier to the kernel that serves it **and** the identity of that kernel.
fn kernel(isa: Isa) -> (VertexKernel, crate::KernelId) {
    use crate::KernelId;
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        match isa {
            Isa::Sse2 => return (x86::sse2, KernelId::Sse2),
            Isa::Avx => return (x86::avx, KernelId::Avx),
            Isa::Avx2 => return (x86::avx2, KernelId::Avx2),
            Isa::Avx2Fma => return (x86::fma, KernelId::Avx2Fma),
            #[cfg(target_arch = "x86_64")]
            Isa::Avx512 => return (x86::avx512, KernelId::Avx512),
            _ => {}
        }
    }
    #[cfg(target_arch = "aarch64")]
    if isa == Isa::Neon {
        return (neon::render, KernelId::Neon);
    }
    (scalar, KernelId::Scalar)
}

/// The tier this module reports for `isa` without needing a batch.
pub(crate) fn executed(isa: Isa) -> Isa {
    kernel(isa).1.tier()
}

/// Identity of the kernel that serves `isa`; see `crate::KernelId`.
pub fn kernel_id(isa: Isa) -> crate::KernelId {
    kernel(isa).1
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
mod x86 {
    use super::{column, BoneOutput, Intermediate, RigidVertex};
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;

    #[target_feature(enable = "sse2")]
    unsafe fn dot2(matrix: &[f32], stride: usize, point: [f32; 3]) -> [f64; 3] {
        let a = column(matrix, stride, 0);
        let b = column(matrix, stride, 1);
        let c = column(matrix, stride, 2);
        let translation = if stride == 4 {
            column(matrix, stride, 3)
        } else {
            [0.; 4]
        };
        let mut result = [0.; 4];
        for row in [0, 2] {
            let mut sum = _mm_loadu_pd(translation.as_ptr().add(row));
            sum = _mm_add_pd(
                sum,
                _mm_mul_pd(
                    _mm_loadu_pd(a.as_ptr().add(row)),
                    _mm_set1_pd(point[0] as f64),
                ),
            );
            sum = _mm_add_pd(
                sum,
                _mm_mul_pd(
                    _mm_loadu_pd(b.as_ptr().add(row)),
                    _mm_set1_pd(point[1] as f64),
                ),
            );
            sum = _mm_add_pd(
                sum,
                _mm_mul_pd(
                    _mm_loadu_pd(c.as_ptr().add(row)),
                    _mm_set1_pd(point[2] as f64),
                ),
            );
            _mm_storeu_pd(result.as_mut_ptr().add(row), sum);
        }
        [result[0], result[1], result[2]]
    }

    #[target_feature(enable = "sse2")]
    unsafe fn tint_sse2(vertex: u32, bone: u32) -> u32 {
        let zero = _mm_setzero_si128();
        let a = _mm_unpacklo_epi8(_mm_cvtsi32_si128(vertex as i32), zero);
        let b = _mm_unpacklo_epi8(_mm_cvtsi32_si128(bone as i32), zero);
        let value = _mm_add_epi16(_mm_mullo_epi16(a, b), _mm_set1_epi16(127));
        // Exact unsigned division by255 in the bounded range0..65152.
        let corrected = _mm_add_epi16(
            _mm_add_epi16(value, _mm_set1_epi16(1)),
            _mm_srli_epi16::<8>(value),
        );
        _mm_cvtsi128_si32(_mm_packus_epi16(_mm_srli_epi16::<8>(corrected), zero)) as u32
    }

    #[target_feature(enable = "sse2")]
    pub(super) unsafe fn sse2(vertex: &RigidVertex, bone: &BoneOutput) -> Intermediate {
        Intermediate {
            position: dot2(&bone.transform, 4, vertex.position),
            normal: dot2(&bone.normal, 3, vertex.normal),
            color: tint_sse2(vertex.color, bone.color),
        }
    }

    #[target_feature(enable = "avx")]
    unsafe fn dot4(matrix: &[f32], stride: usize, point: [f32; 3]) -> [f64; 3] {
        let a = column(matrix, stride, 0);
        let b = column(matrix, stride, 1);
        let c = column(matrix, stride, 2);
        let translation = if stride == 4 {
            column(matrix, stride, 3)
        } else {
            [0.; 4]
        };
        let mut sum = _mm256_loadu_pd(translation.as_ptr());
        sum = _mm256_add_pd(
            sum,
            _mm256_mul_pd(_mm256_loadu_pd(a.as_ptr()), _mm256_set1_pd(point[0] as f64)),
        );
        sum = _mm256_add_pd(
            sum,
            _mm256_mul_pd(_mm256_loadu_pd(b.as_ptr()), _mm256_set1_pd(point[1] as f64)),
        );
        sum = _mm256_add_pd(
            sum,
            _mm256_mul_pd(_mm256_loadu_pd(c.as_ptr()), _mm256_set1_pd(point[2] as f64)),
        );
        let mut result = [0.; 4];
        _mm256_storeu_pd(result.as_mut_ptr(), sum);
        [result[0], result[1], result[2]]
    }

    #[target_feature(enable = "avx")]
    pub(super) unsafe fn avx(vertex: &RigidVertex, bone: &BoneOutput) -> Intermediate {
        Intermediate {
            position: dot4(&bone.transform, 4, vertex.position),
            normal: dot4(&bone.normal, 3, vertex.normal),
            color: tint_sse2(vertex.color, bone.color),
        }
    }

    #[target_feature(enable = "avx2")]
    unsafe fn tint_avx2(vertex: u32, bone: u32) -> u32 {
        let a = _mm256_cvtepu8_epi32(_mm_cvtsi32_si128(vertex as i32));
        let b = _mm256_cvtepu8_epi32(_mm_cvtsi32_si128(bone as i32));
        let value = _mm256_add_epi32(_mm256_mullo_epi32(a, b), _mm256_set1_epi32(128));
        let quotient = _mm256_srli_epi32::<16>(_mm256_mullo_epi32(value, _mm256_set1_epi32(257)));
        let mut channels = [0u32; 8];
        _mm256_storeu_si256(channels.as_mut_ptr().cast(), quotient);
        channels[0] | channels[1] << 8 | channels[2] << 16 | channels[3] << 24
    }

    #[target_feature(enable = "avx2")]
    pub(super) unsafe fn avx2(vertex: &RigidVertex, bone: &BoneOutput) -> Intermediate {
        Intermediate {
            position: dot4(&bone.transform, 4, vertex.position),
            normal: dot4(&bone.normal, 3, vertex.normal),
            color: tint_avx2(vertex.color, bone.color),
        }
    }

    #[target_feature(enable = "avx2,fma")]
    unsafe fn dot_fma(matrix: &[f32], stride: usize, point: [f32; 3]) -> [f64; 3] {
        let a = column(matrix, stride, 0);
        let b = column(matrix, stride, 1);
        let c = column(matrix, stride, 2);
        let translation = if stride == 4 {
            column(matrix, stride, 3)
        } else {
            [0.; 4]
        };
        let mut sum = _mm256_loadu_pd(translation.as_ptr());
        // Products of two finite f32 operands are exact in f64. Sequential FMA
        // therefore preserves Java's separate exact-product/rounded-add order.
        sum = _mm256_fmadd_pd(
            _mm256_loadu_pd(a.as_ptr()),
            _mm256_set1_pd(point[0] as f64),
            sum,
        );
        sum = _mm256_fmadd_pd(
            _mm256_loadu_pd(b.as_ptr()),
            _mm256_set1_pd(point[1] as f64),
            sum,
        );
        sum = _mm256_fmadd_pd(
            _mm256_loadu_pd(c.as_ptr()),
            _mm256_set1_pd(point[2] as f64),
            sum,
        );
        let mut result = [0.; 4];
        _mm256_storeu_pd(result.as_mut_ptr(), sum);
        [result[0], result[1], result[2]]
    }

    #[target_feature(enable = "avx2,fma")]
    pub(super) unsafe fn fma(vertex: &RigidVertex, bone: &BoneOutput) -> Intermediate {
        Intermediate {
            position: dot_fma(&bone.transform, 4, vertex.position),
            normal: dot_fma(&bone.normal, 3, vertex.normal),
            color: tint_avx2(vertex.color, bone.color),
        }
    }

    /// AVX-512 tier: the dot products use 8-wide packed doubles with a masked load that leaves the
    /// fourth lane at zero, so the unused homogeneous column never enters the sum.
    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "avx512f")]
    unsafe fn dot8(matrix: &[f32], stride: usize, point: [f32; 3]) -> [f64; 3] {
        let a = column(matrix, stride, 0);
        let b = column(matrix, stride, 1);
        let c = column(matrix, stride, 2);
        let translation = if stride == 4 {
            column(matrix, stride, 3)
        } else {
            [0.; 4]
        };
        let mut sum = _mm512_maskz_loadu_pd(0b1111, translation.as_ptr());
        sum = _mm512_add_pd(
            sum,
            _mm512_mul_pd(
                _mm512_maskz_loadu_pd(0b1111, a.as_ptr()),
                _mm512_set1_pd(point[0] as f64),
            ),
        );
        sum = _mm512_add_pd(
            sum,
            _mm512_mul_pd(
                _mm512_maskz_loadu_pd(0b1111, b.as_ptr()),
                _mm512_set1_pd(point[1] as f64),
            ),
        );
        sum = _mm512_add_pd(
            sum,
            _mm512_mul_pd(
                _mm512_maskz_loadu_pd(0b1111, c.as_ptr()),
                _mm512_set1_pd(point[2] as f64),
            ),
        );
        let mut result = [0.; 8];
        _mm512_mask_storeu_pd(result.as_mut_ptr(), 0b1111, sum);
        [result[0], result[1], result[2]]
    }

    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "avx512f")]
    unsafe fn tint_avx512(vertex: u32, bone: u32) -> u32 {
        let a = _mm512_cvtepu8_epi32(_mm_cvtsi32_si128(vertex as i32));
        let b = _mm512_cvtepu8_epi32(_mm_cvtsi32_si128(bone as i32));
        let value = _mm512_add_epi32(_mm512_mullo_epi32(a, b), _mm512_set1_epi32(128));
        let quotient = _mm512_srli_epi32::<16>(_mm512_mullo_epi32(value, _mm512_set1_epi32(257)));
        let mut channels = [0u32; 16];
        _mm512_storeu_si512(channels.as_mut_ptr().cast(), quotient);
        channels[0] | channels[1] << 8 | channels[2] << 16 | channels[3] << 24
    }

    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "avx512f")]
    pub(super) unsafe fn avx512(vertex: &RigidVertex, bone: &BoneOutput) -> Intermediate {
        Intermediate {
            position: dot8(&bone.transform, 4, vertex.position),
            normal: dot8(&bone.normal, 3, vertex.normal),
            color: tint_avx512(vertex.color, bone.color),
        }
    }
}

#[cfg(target_arch = "aarch64")]
mod neon {
    use super::{column, BoneOutput, Intermediate, RigidVertex};
    use std::arch::aarch64::*;

    #[target_feature(enable = "neon")]
    unsafe fn dot(matrix: &[f32], stride: usize, point: [f32; 3]) -> [f64; 3] {
        let a = column(matrix, stride, 0);
        let b = column(matrix, stride, 1);
        let c = column(matrix, stride, 2);
        let translation = if stride == 4 {
            column(matrix, stride, 3)
        } else {
            [0.; 4]
        };
        let mut result = [0.; 4];
        for row in [0, 2] {
            let mut sum = vld1q_f64(translation.as_ptr().add(row));
            sum = vaddq_f64(
                sum,
                vmulq_n_f64(vld1q_f64(a.as_ptr().add(row)), point[0] as f64),
            );
            sum = vaddq_f64(
                sum,
                vmulq_n_f64(vld1q_f64(b.as_ptr().add(row)), point[1] as f64),
            );
            sum = vaddq_f64(
                sum,
                vmulq_n_f64(vld1q_f64(c.as_ptr().add(row)), point[2] as f64),
            );
            vst1q_f64(result.as_mut_ptr().add(row), sum);
        }
        [result[0], result[1], result[2]]
    }

    #[target_feature(enable = "neon")]
    unsafe fn tint(vertex: u32, bone: u32) -> u32 {
        let mut a = [0u8; 8];
        a[..4].copy_from_slice(&vertex.to_le_bytes());
        let mut b = [0u8; 8];
        b[..4].copy_from_slice(&bone.to_le_bytes());
        let x = vget_low_u16(vmovl_u8(vld1_u8(a.as_ptr())));
        let y = vget_low_u16(vmovl_u8(vld1_u8(b.as_ptr())));
        let value = vaddq_u32(vmull_u16(x, y), vdupq_n_u32(128));
        let quotient = vshrq_n_u32::<16>(vmulq_n_u32(value, 257));
        let mut channels = [0u32; 4];
        vst1q_u32(channels.as_mut_ptr(), quotient);
        channels[0] | channels[1] << 8 | channels[2] << 16 | channels[3] << 24
    }

    #[target_feature(enable = "neon")]
    pub(super) unsafe fn render(vertex: &RigidVertex, bone: &BoneOutput) -> Intermediate {
        Intermediate {
            position: dot(&bone.transform, 4, vertex.position),
            normal: dot(&bone.normal, 3, vertex.normal),
            color: tint(vertex.color, bone.color),
        }
    }
}
