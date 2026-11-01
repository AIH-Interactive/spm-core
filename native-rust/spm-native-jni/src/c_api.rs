use spm_native_abi::{ComputeOutcome, Isa, Status, ABI_VERSION};
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Compute affine column-major float32 positions. All bytes are little endian.
/// Input/output storage may overlap and need not be aligned. Failure does not write output.
///
/// # Safety
/// Non-null pointers must reference live readable/writable allocations of the declared
/// byte capacities for the duration of the call. Caller excludes concurrent mutation.
/// This API cannot validate forged addresses or recover process corruption.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub unsafe extern "C" fn spm_transform_positions_v1(
    matrix: *const u8,
    matrix_bytes: usize,
    input: *const u8,
    input_bytes: usize,
    output: *mut u8,
    output_bytes: usize,
    count: u32,
    request: u32,
) -> ComputeOutcome {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if cfg!(target_endian = "big") {
            return Err(Status::InvalidInput);
        }
        let bytes = (count as usize)
            .checked_mul(12)
            .ok_or(Status::InvalidInput)?;
        if matrix.is_null() || (bytes > 0 && (input.is_null() || output.is_null())) {
            return Err(Status::InvalidInput);
        }
        if matrix_bytes < 64 || input_bytes < bytes || output_bytes < bytes {
            return Err(Status::BufferTooSmall);
        }
        let isa = Isa::try_from(request)?;
        spm_render_simd::select(isa)?;
        let mut m = [0.; 16];
        for (i, v) in m.iter_mut().enumerate() {
            *v = std::ptr::read_unaligned(matrix.add(i * 4).cast::<f32>());
        }
        let mut p = Vec::new();
        p.try_reserve_exact(bytes / 4)
            .map_err(|_| Status::Internal)?;
        for i in 0..bytes / 4 {
            p.push(std::ptr::read_unaligned(input.add(i * 4).cast::<f32>()));
        }
        let (staged, used) = spm_render_simd::transform_positions(&m, &p, isa)?;
        if bytes > 0 {
            std::ptr::copy_nonoverlapping(staged.as_ptr().cast::<u8>(), output, bytes);
        }
        Ok(used)
    }));
    match result {
        Ok(Ok(isa)) => ComputeOutcome {
            status: 0,
            completed: count,
            isa: isa as u32,
            abi: ABI_VERSION,
        },
        Ok(Err(error)) => ComputeOutcome {
            status: error as i32,
            completed: 0,
            isa: 0,
            abi: ABI_VERSION,
        },
        Err(_) => ComputeOutcome {
            status: Status::Internal as i32,
            completed: 0,
            isa: 0,
            abi: ABI_VERSION,
        },
    }
}
