use spm_native_abi::{Isa, Status};

#[derive(Clone, Copy, Debug)]
pub struct BoneInput {
    pub parent: i32,
    pub pivot: [f32; 3],
    pub rotation: [f32; 3],
    pub translation: [f32; 3],
    pub scale: [f32; 3],
    pub flags: u32,
    pub glow: u32,
    pub color: u32,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct BoneOutput {
    pub transform: [f32; 16],
    pub normal: [f32; 9],
    pub locator: [f32; 3],
    pub visible: bool,
    pub descendants: bool,
    pub glow: u32,
    pub color: u32,
}

pub fn evaluate_bones(
    bones: &[BoneInput],
    root: &[f32; 16],
    request: Isa,
) -> Result<(Vec<BoneOutput>, Isa), Status> {
    extract(bones, root, request, None)
}

pub fn evaluate_bones_subtree(
    bones: &[BoneInput],
    request: Isa,
    root_index: usize,
) -> Result<(Vec<BoneOutput>, Isa), Status> {
    if root_index >= bones.len() {
        return Err(Status::InvalidInput);
    }
    let identity = [
        1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    extract(bones, &identity, request, Some(root_index))
}

fn extract(
    bones: &[BoneInput],
    root: &[f32; 16],
    request: Isa,
    subtree: Option<usize>,
) -> Result<(Vec<BoneOutput>, Isa), Status> {
    let isa = super::select(request)?;
    if root.iter().any(|v| !v.is_finite())
        || root[3] != 0.
        || root[7] != 0.
        || root[11] != 0.
        || root[15] != 1.
    {
        return Err(Status::InvalidInput);
    }
    normal(root)?;
    let n = bones.len();
    // Every stage below reserves fallibly before it can hold `n` items, so a huge batch fails
    // with `Status::Internal` instead of aborting the process in the allocator (defect N10).
    let mut degree = Vec::new();
    degree.try_reserve_exact(n).map_err(|_| Status::Internal)?;
    degree.resize(n, 0_usize);
    let mut children = Vec::new();
    children
        .try_reserve_exact(n)
        .map_err(|_| Status::Internal)?;
    children.resize_with(n, Vec::new);
    let mut order = Vec::new();
    order.try_reserve_exact(n).map_err(|_| Status::Internal)?;
    for (i, b) in bones.iter().enumerate() {
        if b.parent < -1
            || b.parent >= n as i32
            || b.parent == i as i32
            || b.flags & !3 != 0
            || (b.glow > 15 && b.glow != 255)
        {
            return Err(Status::InvalidInput);
        }
        if b.pivot
            .iter()
            .chain(&b.rotation)
            .chain(&b.translation)
            .chain(&b.scale)
            .any(|v| !v.is_finite())
        {
            return Err(Status::NonFinite);
        }
        if b.parent >= 0 {
            degree[b.parent as usize] += 1;
        }
    }
    // Reserve each child list from its counted degree, so the traversal pushes below cannot
    // allocate infallibly.
    for slot in 0..n {
        children[slot]
            .try_reserve_exact(degree[slot])
            .map_err(|_| Status::Internal)?;
    }
    for (i, b) in bones.iter().enumerate() {
        if b.parent == -1 {
            order.push(i);
        } else {
            children[b.parent as usize].push(i);
        }
    }
    let mut head = 0;
    while head < order.len() {
        let i = order[head];
        order.extend_from_slice(&children[i]);
        head += 1;
    }
    if order.len() != n {
        return Err(Status::InvalidInput);
    }
    let mut result = Vec::new();
    result.try_reserve_exact(n).map_err(|_| Status::Internal)?;
    result.resize(n, BoneOutput::default());
    let mut selected = Vec::new();
    selected
        .try_reserve_exact(n)
        .map_err(|_| Status::Internal)?;
    selected.resize(n, false);
    for i in 0..n {
        result[i].glow = bones[i].glow;
        result[i].color = bones[i].color;
    }
    // The multiply step is the only SIMD stage of bone evaluation, so its tier is the tier the
    // batch report may name. Reporting the *request* instead is defect N13: an AVX2 request that
    // is served by the AVX-era kernel would otherwise claim AVX2 instructions were executed.
    let (kernel, id) = kernel(isa);
    for i in order {
        let b = &bones[i];
        selected[i] = subtree.is_none()
            || subtree == Some(i)
            || (b.parent >= 0 && selected[b.parent as usize]);
        if !selected[i] {
            continue;
        }
        let local_root = subtree == Some(i);
        if !local_root && b.parent >= 0 && !result[b.parent as usize].descendants
            || b.scale.contains(&0.)
        {
            continue;
        }
        let local = local(b)?;
        let parent = if local_root || b.parent < 0 {
            *root
        } else {
            result[b.parent as usize].transform
        };
        let mut world = [0.; 16];
        // SAFETY: `select` verified every feature the chosen kernel requires.
        unsafe { kernel(&parent, &local, &mut world) };
        if world.iter().any(|v| !v.is_finite()) {
            return Err(Status::NonFinite);
        }
        let mut locator = [0.; 3];
        for row in 0..3 {
            let mut value = world[12 + row] as f64;
            for axis in 0..3 {
                value += world[axis * 4 + row] as f64 * b.pivot[axis] as f64;
            }
            locator[row] = finite(value)?;
        }
        result[i] = BoneOutput {
            transform: world,
            normal: normal(&world)?,
            locator,
            visible: b.flags & 1 == 0,
            descendants: b.flags & 2 == 0,
            glow: b.glow,
            color: b.color,
        };
    }
    Ok((result, id.tier()))
}

fn finite(value: f64) -> Result<f32, Status> {
    let value = value as f32;
    if value.is_finite() {
        Ok(value)
    } else {
        Err(Status::NonFinite)
    }
}

fn local(b: &BoneInput) -> Result<[f32; 16], Status> {
    let (sx, cx) = (b.rotation[0] as f64).sin_cos();
    let (sy, cy) = (b.rotation[1] as f64).sin_cos();
    let (sz, cz) = (b.rotation[2] as f64).sin_cos();
    let linear = [
        cz * cy,
        sz * cy,
        -sy,
        cz * sy * sx - sz * cx,
        sz * sy * sx + cz * cx,
        cy * sx,
        cz * sy * cx + sz * sx,
        sz * sy * cx - cz * sx,
        cy * cx,
    ];
    let mut m = [0.; 16];
    m[15] = 1.;
    for col in 0..3 {
        for row in 0..3 {
            m[col * 4 + row] = finite(linear[col * 3 + row] * b.scale[col] as f64)?;
        }
    }
    for row in 0..3 {
        let mut v = b.pivot[row] as f64 + b.translation[row] as f64;
        for axis in 0..3 {
            v -= m[axis * 4 + row] as f64 * b.pivot[axis] as f64;
        }
        m[12 + row] = finite(v)?;
    }
    Ok(m)
}

fn normal(m: &[f32; 16]) -> Result<[f32; 9], Status> {
    let (a, b, c, d, e, f, g, h, i) = (
        m[0] as f64,
        m[4] as f64,
        m[8] as f64,
        m[1] as f64,
        m[5] as f64,
        m[9] as f64,
        m[2] as f64,
        m[6] as f64,
        m[10] as f64,
    );
    let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if det == 0. || !det.is_finite() {
        return Err(Status::InvalidInput);
    }
    let values = [
        e * i - f * h,
        c * h - b * i,
        b * f - c * e,
        f * g - d * i,
        a * i - c * g,
        c * d - a * f,
        d * h - e * g,
        b * g - a * h,
        a * e - b * d,
    ];
    let mut out = [0.; 9];
    for k in 0..9 {
        out[k] = finite(values[k] / det)?;
    }
    Ok(out)
}

/// A 4x4 column-major multiply that writes its 16 results in place.
type MulKernel = unsafe fn(&[f32; 16], &[f32; 16], &mut [f32; 16]);

/// Resolves a tier to the kernel that serves it **and** the identity of that kernel.
///
/// This single function is the only place the mapping is declared, so a kernel and the ISA it
/// reports cannot drift apart: every caller uses both halves of the same answer.
fn kernel(isa: Isa) -> (MulKernel, crate::KernelId) {
    use crate::KernelId;
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        match isa {
            Isa::Sse2 => return (x86::multiply_sse2, KernelId::Sse2),
            Isa::Avx => return (x86::multiply_avx, KernelId::Avx),
            Isa::Avx2 => return (x86::multiply_avx2, KernelId::Avx2),
            Isa::Avx2Fma => return (x86::multiply_fma, KernelId::Avx2Fma),
            #[cfg(target_arch = "x86_64")]
            Isa::Avx512 => return (x86::multiply_avx512, KernelId::Avx512),
            _ => {}
        }
    }
    #[cfg(target_arch = "aarch64")]
    if isa == Isa::Neon {
        return (neon::multiply_neon, KernelId::Neon);
    }
    (scalar::multiply, KernelId::Scalar)
}

/// The tier this module reports for `isa` without needing a batch.
///
/// For an empty batch no kernel runs, so the report states the tier the dispatched kernel executes
/// for the requested tier rather than inventing a per-batch answer.
pub fn executed(isa: Isa) -> Isa {
    kernel(isa).1.tier()
}

/// Identity of the kernel that serves `isa`; see `crate::KernelId`.
pub fn kernel_id(isa: Isa) -> crate::KernelId {
    kernel(isa).1
}

mod scalar {
    /// Portable fallback and the differential reference for every instruction-set tier.
    ///
    /// Accumulation is f64 because the Java scalar implementation it must agree with accumulates
    /// in double. Non-finite results are rejected by the caller, uniformly with the SIMD tiers.
    pub(super) unsafe fn multiply(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
        for col in 0..4 {
            for row in 0..4 {
                let mut v = 0.;
                for k in 0..4 {
                    v += a[k * 4 + row] as f64 * b[col * 4 + k] as f64;
                }
                out[col * 4 + row] = v as f32;
            }
        }
    }
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
mod x86 {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;

    /// Packed double arithmetic keeps the Java scalar accumulation precision.
    #[target_feature(enable = "sse2")]
    pub(super) unsafe fn multiply_sse2(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
        for col in 0..4 {
            for row in [0, 2] {
                let mut accum = _mm_setzero_pd();
                for k in 0..4 {
                    accum = _mm_add_pd(
                        accum,
                        _mm_mul_pd(
                            _mm_set_pd(a[k * 4 + row + 1] as f64, a[k * 4 + row] as f64),
                            _mm_set1_pd(b[col * 4 + k] as f64),
                        ),
                    );
                }
                let mut lanes = [0.; 2];
                _mm_storeu_pd(lanes.as_mut_ptr(), accum);
                out[col * 4 + row] = lanes[0] as f32;
                out[col * 4 + row + 1] = lanes[1] as f32;
            }
        }
    }

    #[target_feature(enable = "avx")]
    pub(super) unsafe fn multiply_avx(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
        for col in 0..4 {
            let mut accum = _mm256_setzero_pd();
            for k in 0..4 {
                accum = _mm256_add_pd(
                    accum,
                    _mm256_mul_pd(
                        _mm256_cvtps_pd(_mm_loadu_ps(a.as_ptr().add(k * 4))),
                        _mm256_set1_pd(b[col * 4 + k] as f64),
                    ),
                );
            }
            _mm_storeu_ps(out.as_mut_ptr().add(col * 4), _mm256_cvtpd_ps(accum));
        }
    }

    /// Genuine AVX2 tier: one 256-bit row load plus `vpermps` replaces four 128-bit loads.
    /// The arithmetic order is identical to the AVX tier, so results are bit-identical.
    #[target_feature(enable = "avx2")]
    pub(super) unsafe fn multiply_avx2(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
        let lo = _mm256_loadu_ps(a.as_ptr());
        let hi = _mm256_loadu_ps(a.as_ptr().add(8));
        for col in 0..4 {
            let mut accum = _mm256_setzero_pd();
            for k in 0..4 {
                // `lo` holds a[0..8] and `hi` holds a[8..16], so `base` is where each begins.
                let (source, base) = if k < 2 { (lo, 0) } else { (hi, 8) };
                let start = (k * 4 - base) as i32;
                // Lanes 0..3 receive a[k*4..k*4+4]; lanes 4..7 are selected but discarded.
                let picked = _mm256_permutevar8x32_ps(
                    source,
                    _mm256_setr_epi32(
                        start,
                        start + 1,
                        start + 2,
                        start + 3,
                        start,
                        start,
                        start,
                        start,
                    ),
                );
                accum = _mm256_add_pd(
                    accum,
                    _mm256_mul_pd(
                        _mm256_cvtps_pd(_mm256_castps256_ps128(picked)),
                        _mm256_set1_pd(b[col * 4 + k] as f64),
                    ),
                );
            }
            _mm_storeu_ps(out.as_mut_ptr().add(col * 4), _mm256_cvtpd_ps(accum));
        }
    }

    #[target_feature(enable = "avx2,fma")]
    pub(super) unsafe fn multiply_fma(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
        for col in 0..4 {
            let mut accum = _mm256_setzero_pd();
            for k in 0..4 {
                accum = _mm256_fmadd_pd(
                    _mm256_cvtps_pd(_mm_loadu_ps(a.as_ptr().add(k * 4))),
                    _mm256_set1_pd(b[col * 4 + k] as f64),
                    accum,
                );
            }
            _mm_storeu_ps(out.as_mut_ptr().add(col * 4), _mm256_cvtpd_ps(accum));
        }
    }

    /// AVX-512 tier: two output columns share one 8-lane packed-double accumulator.
    ///
    /// A 4x4 multiply only needs four lanes per column, so the AVX tier leaves half of every
    /// 256-bit register idle. Here each accumulator holds columns `2k` and `2k+1` side by side:
    /// lanes 0..3 carry one column's four rows and lanes 4..7 the next column's. The source column
    /// is broadcast into both halves and the multiplier vector repeats one `b` coefficient per
    /// half, so two columns advance per iteration.
    ///
    /// Accumulation stays `acc = acc + a*b` in `k = 0,1,2,3` order, exactly like the scalar oracle,
    /// so results are bit-identical to the AVX tier rather than merely close.
    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "avx512f")]
    pub(super) unsafe fn multiply_avx512(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
        for pair in 0..2 {
            let (left, right) = (pair * 2, pair * 2 + 1);
            let mut accum = _mm512_setzero_pd();
            for k in 0..4 {
                // Both halves get the same source column a[k*4..k*4+4].
                let source =
                    _mm512_broadcast_f64x4(_mm256_cvtps_pd(_mm_loadu_ps(a.as_ptr().add(k * 4))));
                let lo = b[left * 4 + k] as f64;
                let hi = b[right * 4 + k] as f64;
                // `_mm512_set_pd` lists lanes from the highest index down, so lane 0 = `lo`.
                let factor = _mm512_set_pd(hi, hi, hi, hi, lo, lo, lo, lo);
                accum = _mm512_add_pd(accum, _mm512_mul_pd(source, factor));
            }
            let packed = _mm512_cvtpd_ps(accum);
            _mm_storeu_ps(
                out.as_mut_ptr().add(left * 4),
                _mm256_castps256_ps128(packed),
            );
            _mm_storeu_ps(
                out.as_mut_ptr().add(right * 4),
                _mm256_extractf128_ps(packed, 1),
            );
        }
    }
}

#[cfg(target_arch = "aarch64")]
mod neon {
    use std::arch::aarch64::*;

    #[target_feature(enable = "neon")]
    pub(super) unsafe fn multiply_neon(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
        for col in 0..4 {
            for row in [0, 2] {
                let mut accum = vdupq_n_f64(0.);
                for k in 0..4 {
                    let lanes = [a[k * 4 + row] as f64, a[k * 4 + row + 1] as f64];
                    accum = vaddq_f64(
                        accum,
                        vmulq_f64(
                            vld1q_f64(lanes.as_ptr()),
                            vdupq_n_f64(b[col * 4 + k] as f64),
                        ),
                    );
                }
                let mut lanes = [0.; 2];
                vst1q_f64(lanes.as_mut_ptr(), accum);
                out[col * 4 + row] = lanes[0] as f32;
                out[col * 4 + row + 1] = lanes[1] as f32;
            }
        }
    }
}
