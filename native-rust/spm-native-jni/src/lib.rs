//! Thin JNI adapter. All validation and staging completes before output commit.
use jni::{
    objects::{JByteBuffer, JClass},
    sys::{jboolean, jint, jlong},
    JNIEnv,
};
use spm_native_abi::{Isa, Status, JNI_ABI_VERSION};
use std::panic::{catch_unwind, AssertUnwindSafe};
mod bones;
mod c_api;
mod rigid;
pub use c_api::spm_transform_positions_v1;

#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativeTransforms_nAbiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    JNI_ABI_VERSION as jint
}

#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativeTransforms_nSupported(
    _env: JNIEnv,
    _class: JClass,
    request: jint,
) -> jboolean {
    u32::try_from(request)
        .ok()
        .and_then(|v| Isa::try_from(v).ok())
        .is_some_and(spm_render_simd::supported) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativeTransforms_nTransform(
    mut env: JNIEnv,
    _class: JClass,
    matrix: JByteBuffer,
    input: JByteBuffer,
    output: JByteBuffer,
    count: jint,
    request: jint,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| {
        transform(&mut env, matrix, input, output, count, request)
    })) {
        Ok(Ok(value)) => value,
        Ok(Err(status)) => status as i32 as jlong,
        Err(_) => Status::Internal as i32 as jlong,
    }
}

fn transform(
    env: &mut JNIEnv,
    matrix: JByteBuffer,
    input: JByteBuffer,
    output: JByteBuffer,
    count: jint,
    request: jint,
) -> Result<jlong, Status> {
    if cfg!(target_endian = "big") || env.exception_check().map_err(|_| Status::Internal)? {
        return Err(Status::InvalidInput);
    }
    let count = usize::try_from(count).map_err(|_| Status::InvalidInput)?;
    let elements = count.checked_mul(3).ok_or(Status::InvalidInput)?;
    let bytes = elements.checked_mul(4).ok_or(Status::InvalidInput)?;
    let isa = Isa::try_from(u32::try_from(request).map_err(|_| Status::UnsupportedIsa)?)?;
    // Select before allocating; unsupported forced requests are never executed.
    spm_render_simd::select(isa)?;
    if env
        .get_direct_buffer_capacity(&matrix)
        .map_err(|_| Status::InvalidInput)?
        < 64
        || env
            .get_direct_buffer_capacity(&input)
            .map_err(|_| Status::InvalidInput)?
            < bytes
        || env
            .get_direct_buffer_capacity(&output)
            .map_err(|_| Status::InvalidInput)?
            < bytes
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
    let mptr = env
        .get_direct_buffer_address(&matrix)
        .map_err(|_| Status::InvalidInput)?;
    let mut m = [0.; 16];
    for (i, v) in m.iter_mut().enumerate() {
        *v = unsafe { std::ptr::read_unaligned(mptr.add(i * 4).cast::<f32>()) };
    }
    let mut positions = Vec::new();
    positions
        .try_reserve_exact(elements)
        .map_err(|_| Status::Internal)?;
    if bytes > 0 {
        let ptr = env
            .get_direct_buffer_address(&input)
            .map_err(|_| Status::InvalidInput)?;
        for i in 0..elements {
            positions.push(unsafe { std::ptr::read_unaligned(ptr.add(i * 4).cast::<f32>()) });
        }
    }
    let (staged, used) = spm_render_simd::transform_positions(&m, &positions, isa)?;
    if bytes > 0 {
        let ptr = env
            .get_direct_buffer_address(&output)
            .map_err(|_| Status::InvalidInput)?;
        // Staging owns disjoint native memory; input/output Java slices may alias safely.
        unsafe {
            std::ptr::copy_nonoverlapping(staged.as_ptr().cast::<u8>(), ptr, bytes);
        }
    }
    Ok(((used as u32 as jlong) << 32) | (count as jlong))
}
