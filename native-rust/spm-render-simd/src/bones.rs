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
    let mut children = vec![Vec::new(); n];
    let mut order = Vec::with_capacity(n);
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
    let mut result = vec![BoneOutput::default(); n];
    let mut selected = vec![false; n];
    for i in 0..n {
        result[i].glow = bones[i].glow;
        result[i].color = bones[i].color;
    }
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
        let world = multiply(&parent, &local, isa)?;
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
    Ok((result, isa))
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

fn multiply(a: &[f32; 16], b: &[f32; 16], isa: Isa) -> Result<[f32; 16], Status> {
    let mut out = [0.; 16];
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    if matches!(isa, Isa::Sse2 | Isa::Avx | Isa::Avx2 | Isa::Avx2Fma) {
        // Packed double arithmetic keeps the Java scalar accumulation precision.
        unsafe {
            match isa {
                Isa::Avx2Fma => multiply_fma(a, b, &mut out),
                Isa::Avx | Isa::Avx2 => multiply_avx(a, b, &mut out),
                _ => multiply_sse2(a, b, &mut out),
            }
        }
        if out.iter().any(|v| !v.is_finite()) {
            return Err(Status::NonFinite);
        }
        return Ok(out);
    }
    let _ = isa;
    #[cfg(target_arch = "aarch64")]
    if isa == Isa::Neon {
        unsafe {
            multiply_neon(a, b, &mut out);
        }
        if out.iter().any(|v| !v.is_finite()) {
            return Err(Status::NonFinite);
        }
        return Ok(out);
    }
    for col in 0..4 {
        for row in 0..4 {
            let mut v = 0.;
            for k in 0..4 {
                v += a[k * 4 + row] as f64 * b[col * 4 + k] as f64;
            }
            out[col * 4 + row] = finite(v)?;
        }
    }
    Ok(out)
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
#[target_feature(enable = "sse2")]
unsafe fn multiply_sse2(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;
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

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
#[target_feature(enable = "avx")]
unsafe fn multiply_avx(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;
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

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
#[target_feature(enable = "avx2,fma")]
unsafe fn multiply_fma(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;
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

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
unsafe fn multiply_neon(a: &[f32; 16], b: &[f32; 16], out: &mut [f32; 16]) {
    use std::arch::aarch64::*;
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
