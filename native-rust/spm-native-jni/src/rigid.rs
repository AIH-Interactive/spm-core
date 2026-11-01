use jni::{
    objects::{JByteBuffer, JClass},
    sys::{jint, jlong},
    JNIEnv,
};
use spm_native_abi::{Isa, Status};
use spm_render_simd::{render_rigid, BoneOutput, RigidVertex};
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Logical little-endian input/output buffers. No host object/address escapes the call.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativeRigidRenderer_nTransform(
    mut env: JNIEnv,
    _class: JClass,
    bones: JByteBuffer,
    input: JByteBuffer,
    output: JByteBuffer,
    bone_count: jint,
    vertex_count: jint,
    request: jint,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| {
        transform(
            &mut env,
            bones,
            input,
            output,
            bone_count,
            vertex_count,
            request,
        )
    })) {
        Ok(Ok(value)) => value,
        Ok(Err(status)) => status as i32 as jlong,
        Err(_) => Status::Internal as i32 as jlong,
    }
}

#[allow(clippy::too_many_arguments)]
fn transform(
    env: &mut JNIEnv,
    bones: JByteBuffer,
    input: JByteBuffer,
    output: JByteBuffer,
    bone_count: jint,
    vertex_count: jint,
    request: jint,
) -> Result<jlong, Status> {
    if cfg!(target_endian = "big") || env.exception_check().map_err(|_| Status::Internal)? {
        return Err(Status::InvalidInput);
    }
    let bone_count = usize::try_from(bone_count).map_err(|_| Status::InvalidInput)?;
    let vertex_count = usize::try_from(vertex_count).map_err(|_| Status::InvalidInput)?;
    let isa = Isa::try_from(u32::try_from(request).map_err(|_| Status::UnsupportedIsa)?)?;
    spm_render_simd::select(isa)?;
    for (buffer, count, stride) in [(&bones, bone_count, 128usize), (&input, vertex_count, 32)] {
        let required = count.checked_mul(stride).ok_or(Status::InvalidInput)?;
        if env
            .get_direct_buffer_capacity(buffer)
            .map_err(|_| Status::InvalidInput)?
            < required
        {
            return Err(Status::BufferTooSmall);
        }
    }
    let output_capacity = env
        .get_direct_buffer_capacity(&output)
        .map_err(|_| Status::InvalidInput)?;
    if env
        .call_method(&output, "isReadOnly", "()Z", &[])
        .and_then(|value| value.z())
        .map_err(|_| Status::InvalidInput)?
    {
        return Err(Status::InvalidInput);
    }
    let mut bone_data = Vec::new();
    bone_data
        .try_reserve_exact(bone_count)
        .map_err(|_| Status::Internal)?;
    if bone_count != 0 {
        let base = env
            .get_direct_buffer_address(&bones)
            .map_err(|_| Status::InvalidInput)?;
        for index in 0..bone_count {
            let ptr = unsafe { base.add(index * 128) };
            let (visible, descendants) = unsafe { (read_u32(ptr, 112), read_u32(ptr, 116)) };
            if visible > 1 || descendants > 1 {
                return Err(Status::InvalidInput);
            }
            let mut transform = [0.; 16];
            let mut normal = [0.; 9];
            let mut locator = [0.; 3];
            unsafe {
                for (i, value) in transform.iter_mut().enumerate() {
                    *value = read_f32(ptr, i * 4);
                }
                for (i, value) in normal.iter_mut().enumerate() {
                    *value = read_f32(ptr, 64 + i * 4);
                }
                for (i, value) in locator.iter_mut().enumerate() {
                    *value = read_f32(ptr, 100 + i * 4);
                }
                bone_data.push(BoneOutput {
                    transform,
                    normal,
                    locator,
                    visible: visible != 0,
                    descendants: descendants != 0,
                    glow: read_u32(ptr, 120),
                    color: read_u32(ptr, 124),
                });
            }
        }
    }
    let mut vertices = Vec::new();
    vertices
        .try_reserve_exact(vertex_count)
        .map_err(|_| Status::Internal)?;
    if vertex_count != 0 {
        let base = env
            .get_direct_buffer_address(&input)
            .map_err(|_| Status::InvalidInput)?;
        for index in 0..vertex_count {
            let ptr = unsafe { base.add(index * 32) };
            let mut position = [0.; 3];
            let mut normal = [0.; 3];
            unsafe {
                for axis in 0..3 {
                    position[axis] = read_f32(ptr, axis * 4);
                    normal[axis] = read_f32(ptr, 12 + axis * 4);
                }
                vertices.push(RigidVertex {
                    position,
                    normal,
                    bone: read_u32(ptr, 24),
                    color: read_u32(ptr, 28),
                });
            }
        }
    }
    // Every input byte has been copied to native-owned state. Input, bone and
    // output Java buffers may overlap, including unaligned direct slices.
    let result = render_rigid(&vertices, &bone_data, isa)?;
    let required = result
        .vertices
        .len()
        .checked_mul(40)
        .ok_or(Status::InvalidInput)?;
    if output_capacity < required {
        return Err(Status::BufferTooSmall);
    }
    let completed = u32::try_from(result.vertices.len()).map_err(|_| Status::InvalidInput)?;
    let mut staged = Vec::new();
    staged
        .try_reserve_exact(required)
        .map_err(|_| Status::Internal)?;
    for vertex in result.vertices {
        for value in vertex.position.into_iter().chain(vertex.normal) {
            staged.extend_from_slice(&value.to_le_bytes());
        }
        staged.extend_from_slice(&vertex.bone.to_le_bytes());
        staged.extend_from_slice(&vertex.glow.to_le_bytes());
        staged.extend_from_slice(&vertex.color.to_le_bytes());
        staged.extend_from_slice(&0u32.to_le_bytes());
    }
    if required != 0 {
        let destination = env
            .get_direct_buffer_address(&output)
            .map_err(|_| Status::InvalidInput)?;
        // Staging is disjoint from the Java buffer and all fallible work is over.
        unsafe {
            std::ptr::copy_nonoverlapping(staged.as_ptr(), destination, required);
        }
    }
    Ok(((result.isa as u32 as jlong) << 32) | completed as jlong)
}

unsafe fn read_f32(ptr: *const u8, offset: usize) -> f32 {
    std::ptr::read_unaligned(ptr.add(offset).cast::<f32>())
}
unsafe fn read_u32(ptr: *const u8, offset: usize) -> u32 {
    std::ptr::read_unaligned(ptr.add(offset).cast::<u32>())
}
