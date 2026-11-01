use jni::{
    objects::{JByteBuffer, JClass},
    sys::{jint, jlong},
    JNIEnv,
};
use spm_native_abi::{Isa, Status};
use spm_render_simd::{evaluate_bones, evaluate_bones_subtree, BoneInput};
use std::panic::{catch_unwind, AssertUnwindSafe};

#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativeBoneEvaluator_nEvaluate(
    mut env: JNIEnv,
    _class: JClass,
    parents: JByteBuffer,
    pivots: JByteBuffer,
    channels: JByteBuffer,
    root: JByteBuffer,
    output: JByteBuffer,
    count: jint,
    request: jint,
    subtree: jint,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| {
        evaluate(
            &mut env, parents, pivots, channels, root, output, count, request, subtree,
        )
    })) {
        Ok(Ok(value)) => value,
        Ok(Err(status)) => status as i32 as jlong,
        Err(_) => Status::Internal as i32 as jlong,
    }
}

#[allow(clippy::too_many_arguments)]
fn evaluate(
    env: &mut JNIEnv,
    parents: JByteBuffer,
    pivots: JByteBuffer,
    channels: JByteBuffer,
    root: JByteBuffer,
    output: JByteBuffer,
    count: jint,
    request: jint,
    subtree: jint,
) -> Result<jlong, Status> {
    if cfg!(target_endian = "big") || env.exception_check().map_err(|_| Status::Internal)? {
        return Err(Status::InvalidInput);
    }
    let n = usize::try_from(count).map_err(|_| Status::InvalidInput)?;
    let isa = Isa::try_from(u32::try_from(request).map_err(|_| Status::UnsupportedIsa)?)?;
    spm_render_simd::select(isa)?;
    for (buffer, stride) in [
        (&parents, 4usize),
        (&pivots, 12),
        (&channels, 48),
        (&output, 128),
    ] {
        if env
            .get_direct_buffer_capacity(buffer)
            .map_err(|_| Status::InvalidInput)?
            < n.checked_mul(stride).ok_or(Status::InvalidInput)?
        {
            return Err(Status::BufferTooSmall);
        }
    }
    if env
        .get_direct_buffer_capacity(&root)
        .map_err(|_| Status::InvalidInput)?
        < 64
    {
        return Err(Status::BufferTooSmall);
    }
    if env
        .call_method(&output, "isReadOnly", "()Z", &[])
        .and_then(|v| v.z())
        .map_err(|_| Status::InvalidInput)?
    {
        return Err(Status::InvalidInput);
    }
    let rp = env
        .get_direct_buffer_address(&root)
        .map_err(|_| Status::InvalidInput)?;
    let mut outer = [0.; 16];
    for (i, v) in outer.iter_mut().enumerate() {
        *v = unsafe { read_f32(rp, i * 4) };
    }
    let mut input = Vec::new();
    input.try_reserve_exact(n).map_err(|_| Status::Internal)?;
    if n > 0 {
        let pp = env
            .get_direct_buffer_address(&parents)
            .map_err(|_| Status::InvalidInput)?;
        let pv = env
            .get_direct_buffer_address(&pivots)
            .map_err(|_| Status::InvalidInput)?;
        let pc = env
            .get_direct_buffer_address(&channels)
            .map_err(|_| Status::InvalidInput)?;
        for i in 0..n {
            unsafe {
                let mut pivot = [0.; 3];
                let mut rotation = [0.; 3];
                let mut translation = [0.; 3];
                let mut scale = [0.; 3];
                for axis in 0..3 {
                    pivot[axis] = read_f32(pv, i * 12 + axis * 4);
                    rotation[axis] = read_f32(pc, i * 48 + axis * 4);
                    translation[axis] = read_f32(pc, i * 48 + 12 + axis * 4);
                    scale[axis] = read_f32(pc, i * 48 + 24 + axis * 4);
                }
                input.push(BoneInput {
                    parent: std::ptr::read_unaligned(pp.add(i * 4).cast::<i32>()),
                    pivot,
                    rotation,
                    translation,
                    scale,
                    flags: std::ptr::read_unaligned(pc.add(i * 48 + 36).cast::<u32>()),
                    glow: std::ptr::read_unaligned(pc.add(i * 48 + 40).cast::<u32>()),
                    color: std::ptr::read_unaligned(pc.add(i * 48 + 44).cast::<u32>()),
                });
            }
        }
    }
    let (result, used) = if subtree == -1 {
        evaluate_bones(&input, &outer, isa)?
    } else {
        evaluate_bones_subtree(
            &input,
            isa,
            usize::try_from(subtree).map_err(|_| Status::InvalidInput)?,
        )?
    };
    let mut staged = Vec::new();
    staged
        .try_reserve_exact(n.checked_mul(128).ok_or(Status::InvalidInput)?)
        .map_err(|_| Status::Internal)?;
    for b in result {
        for v in b.transform.into_iter().chain(b.normal).chain(b.locator) {
            staged.extend_from_slice(&v.to_le_bytes());
        }
        staged.extend_from_slice(&(b.visible as u32).to_le_bytes());
        staged.extend_from_slice(&(b.descendants as u32).to_le_bytes());
        staged.extend_from_slice(&b.glow.to_le_bytes());
        staged.extend_from_slice(&b.color.to_le_bytes());
    }
    if !staged.is_empty() {
        let ptr = env
            .get_direct_buffer_address(&output)
            .map_err(|_| Status::InvalidInput)?;
        unsafe {
            std::ptr::copy_nonoverlapping(staged.as_ptr(), ptr, staged.len());
        }
    }
    Ok(((used as u32 as jlong) << 32) | (n as jlong))
}

unsafe fn read_f32(ptr: *const u8, offset: usize) -> f32 {
    std::ptr::read_unaligned(ptr.add(offset).cast::<f32>())
}
