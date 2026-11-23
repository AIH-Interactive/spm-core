//! Generational handles for compiled skeletons, and the critical-array guard the packed entry uses.
//!
//! # Why a generation, not just a slot
//!
//! A compiled skeleton is expensive to build and is reused across frames, so it outlives a single
//! JNI call. That means the native side must hand Java a handle it can pass back later, and it must
//! be able to tell a live handle from one whose owner has already been closed. A bare slot index
//! cannot: after a free, the slot is reused, and a stale handle would silently address whatever
//! skeleton took its place — the caller would get another model's matrices with no error.
//!
//! So a handle is `(generation << 32) | slot`. Freeing bumps the slot's generation, which makes
//! every handle minted for the previous occupant stale forever. A stale handle is reported as
//! [`Status::InvalidHandle`], which is deliberately distinct from `InvalidInput`: it is a caller
//! lifetime defect, not malformed data.
//!
//! # Why the critical array guard
//!
//! `boneParams` is a Java `float[]` that the host rewrites every frame. Copying it into native
//! memory per evaluation would add a full array copy to a path whose whole point is to avoid
//! per-frame allocation, so the packed entry pins it with `GetPrimitiveArrayCritical` instead. The
//! JVM may hand back a pointer to the array's real storage, and while that pointer is held the
//! thread must not call back into the JVM or block. [`CriticalFloatSlice`] enforces the release on
//! every exit path, including a panic, so a rejected frame cannot leave the array pinned.
use jni::{
    objects::{JByteBuffer, JClass, JFloatArray, JIntArray, JPrimitiveArray},
    sys::{jarray, jboolean, jfloat, jint, jlong, JNIEnv as RawEnv},
    JNIEnv,
};
use spm_native_abi::{decode_packed_flags, Isa, Status, ROOT_FLOATS};
use spm_render_simd::{CompiledSkeleton, PackedOutcome, PackedRequest};
use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{Mutex, MutexGuard},
};

/// Bits of a handle that carry the slot index; the rest carry the generation.
const SLOT_BITS: u32 = 32;
const SLOT_MASK: u64 = (1 << SLOT_BITS) - 1;

/// One live compiled skeleton and the generation its handle was minted under.
struct Entry {
    generation: u32,
    skeleton: CompiledSkeleton,
}

/// A slot is either occupied or free; a free slot remembers the generation that must not match.
enum Slot {
    // Boxed because `CompiledSkeleton` is large and most slots are free; an unboxed enum would
    // size every slot for the live case.
    Live(Box<Entry>),
    /// The generation the next occupant will *not* use, so stale handles stay stale.
    Free {
        next_generation: u32,
    },
}

/// The process-wide table of compiled skeletons.
///
/// A `Mutex` rather than a lock-free structure: compilation and freeing happen at model load and
/// unload, not per frame, and evaluation only needs a short read of the skeleton it was handed.
struct SlotTable {
    slots: Vec<Slot>,
    live: usize,
}

impl SlotTable {
    const fn new() -> Self {
        Self {
            slots: Vec::new(),
            live: 0,
        }
    }

    /// Registers `skeleton` and returns its handle.
    fn insert(&mut self, skeleton: CompiledSkeleton) -> Result<u64, Status> {
        // Reuse a free slot before growing, so a load/unload cycle does not grow the table without
        // bound. The generation carried by the free slot is what makes the reuse safe.
        if let Some(index) = self
            .slots
            .iter()
            .position(|slot| matches!(slot, Slot::Free { .. }))
        {
            let generation = match &self.slots[index] {
                Slot::Free { next_generation } => *next_generation,
                Slot::Live(_) => unreachable!("position matched a free slot"),
            };
            self.slots[index] = Slot::Live(Box::new(Entry {
                generation,
                skeleton,
            }));
            self.live += 1;
            return Ok(encode(index, generation));
        }
        let index = self.slots.len();
        if index > SLOT_MASK as usize {
            // The slot index must fit in the low half of the handle.
            return Err(Status::Internal);
        }
        self.slots.try_reserve(1).map_err(|_| Status::Internal)?;
        // Generation 1 for a fresh slot: generation 0 is never issued, so a zero handle is always
        // invalid and cannot be confused with slot 0's first occupant.
        self.slots.push(Slot::Live(Box::new(Entry {
            generation: 1,
            skeleton,
        })));
        self.live += 1;
        Ok(encode(index, 1))
    }

    /// Resolves `handle` to its skeleton, or reports it stale.
    fn get(&self, handle: u64) -> Result<&CompiledSkeleton, Status> {
        let (index, generation) = decode(handle);
        match self.slots.get(index) {
            Some(Slot::Live(entry)) if entry.generation == generation => Ok(&entry.skeleton),
            _ => Err(Status::InvalidHandle),
        }
    }

    /// Frees `handle`, bumping the slot's generation so the handle can never resolve again.
    fn free(&mut self, handle: u64) -> Result<(), Status> {
        let (index, generation) = decode(handle);
        match self.slots.get_mut(index) {
            Some(slot @ Slot::Live(_)) => {
                let entry_generation = match &*slot {
                    Slot::Live(entry) => entry.generation,
                    Slot::Free { .. } => unreachable!("matched a live slot"),
                };
                if entry_generation != generation {
                    return Err(Status::InvalidHandle);
                }
                // The next occupant gets a generation that no issued handle carries. Wrapping is
                // acceptable: it would take 2^32 load/unload cycles at one slot to revisit a
                // generation, and by then no handle from the first cycle can still be held.
                let next_generation = generation.wrapping_add(1).max(1);
                *slot = Slot::Free { next_generation };
                self.live -= 1;
                Ok(())
            }
            _ => Err(Status::InvalidHandle),
        }
    }

    fn live(&self) -> usize {
        self.live
    }
}

/// Packs a slot index and generation into one handle.
fn encode(index: usize, generation: u32) -> u64 {
    (u64::from(generation) << SLOT_BITS) | (index as u64 & SLOT_MASK)
}

/// Splits a handle back into its slot index and generation.
fn decode(handle: u64) -> (usize, u32) {
    ((handle & SLOT_MASK) as usize, (handle >> SLOT_BITS) as u32)
}

static SKELETONS: Mutex<SlotTable> = Mutex::new(SlotTable::new());

fn skeletons() -> MutexGuard<'static, SlotTable> {
    // A caught panic must not prevent every later owner from freeing its skeleton.
    SKELETONS
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Copies a Java `int[]` into a `Vec<i32>`.
///
/// # Safety
///
/// `env` must be the `JNIEnv` of the thread currently executing the native method, and `array` must
/// be a valid local reference in that frame.
pub(crate) unsafe fn read_int_array(
    env: &mut JNIEnv,
    array: &JPrimitiveArray<'_, jint>,
) -> Result<Vec<i32>, Status> {
    let len = usize::try_from(
        env.get_array_length(array)
            .map_err(|_| Status::InvalidInput)?,
    )
    .map_err(|_| Status::InvalidInput)?;
    let mut values = Vec::new();
    values
        .try_reserve_exact(len)
        .map_err(|_| Status::Internal)?;
    if len == 0 {
        return Ok(values);
    }
    // SAFETY: `array` is a valid local reference in this frame. `JNI_ABORT` because the copy is
    // read-only, so there is nothing to write back.
    let elements =
        unsafe { env.get_array_elements_critical(array, jni::objects::ReleaseMode::NoCopyBack) }
            .map_err(|_| Status::Internal)?;
    // SAFETY: `elements` exposes exactly `len` initialised `jint`s.
    let slice = unsafe { std::slice::from_raw_parts(elements.as_ptr(), len) };
    values.extend_from_slice(slice);
    Ok(values)
}

/// Copies a Java `float[]` into a `Vec<f32>`.
///
/// # Safety
///
/// `env` must be the `JNIEnv` of the thread currently executing the native method, and `array` must
/// be a valid local reference in that frame.
pub(crate) unsafe fn read_float_array(
    env: &mut JNIEnv,
    array: &JPrimitiveArray<'_, jfloat>,
) -> Result<Vec<f32>, Status> {
    let len = usize::try_from(
        env.get_array_length(array)
            .map_err(|_| Status::InvalidInput)?,
    )
    .map_err(|_| Status::InvalidInput)?;
    let mut values = Vec::new();
    values
        .try_reserve_exact(len)
        .map_err(|_| Status::Internal)?;
    if len == 0 {
        return Ok(values);
    }
    // SAFETY: `array` is a valid local reference in this frame. `JNI_ABORT` because the copy is
    // read-only, so there is nothing to write back.
    let elements =
        unsafe { env.get_array_elements_critical(array, jni::objects::ReleaseMode::NoCopyBack) }
            .map_err(|_| Status::Internal)?;
    // SAFETY: `elements` exposes exactly `len` initialised `jfloat`s.
    let slice = unsafe { std::slice::from_raw_parts(elements.as_ptr(), len) };
    values.extend_from_slice(slice);
    Ok(values)
}

/// Pins a Java primitive array and returns a pointer to its elements.
///
/// # Safety
///
/// `env` must be the `JNIEnv` of the thread currently executing the native method, and `array` must
/// be a valid local reference in that frame. The caller must release the pointer with
/// [`release_primitive_array_critical`] before returning to the JVM.
unsafe fn get_primitive_array_critical(
    env: *mut RawEnv,
    array: jarray,
    is_copy: &mut jboolean,
) -> *mut std::ffi::c_void {
    // SAFETY: the caller guarantees `env` is this thread's environment.
    unsafe {
        let table = **env;
        // The JNI function table declares every entry as `Option<fn>` because a VM is allowed to
        // omit one. `GetPrimitiveArrayCritical` has been mandatory since JNI 1.2, so a missing
        // entry means the environment is not a JVM at all; returning null makes the caller report
        // `Internal` rather than calling through a null pointer.
        match table.GetPrimitiveArrayCritical {
            // The first parameter is the environment the VM handed us. `jni::sys::JNIEnv` is itself
            // a pointer to the function table, so the crate's own macros pass `self.internal` —
            // the *address of* that pointer — here. Passing the table pointer itself instead is an
            // access violation, which is exactly how this was first written.
            Some(pin) => pin(env, array, is_copy),
            None => std::ptr::null_mut(),
        }
    }
}

/// Releases a pointer obtained from [`get_primitive_array_critical`].
///
/// # Safety
///
/// `env`, `array` and `ptr` must be exactly what the matching pin returned, on the same thread.
unsafe fn release_primitive_array_critical(
    env: *mut RawEnv,
    array: jarray,
    ptr: *mut std::ffi::c_void,
) {
    // SAFETY: the caller guarantees these came from the matching pin on this thread.
    unsafe {
        let table = **env;
        // See `get_primitive_array_critical`: the entry is mandatory, and skipping the release
        // would leave the array pinned, so a missing entry is a hard error rather than a no-op.
        if let Some(release) = table.ReleasePrimitiveArrayCritical {
            release(env, array, ptr, jni::sys::JNI_ABORT);
        }
    }
}

/// A pinned Java `float[]`, released on every exit path.
///
/// `GetPrimitiveArrayCritical` may return a pointer to the array's real storage, and the JVM
/// requires that the thread neither calls back into it nor blocks until the matching release. This
/// guard makes that release unconditional: `Drop` runs on the success path, on an early `return`,
/// and while unwinding out of a caught panic.
///
/// The pointer is only ever read, never written, so the release mode is `JNI_ABORT` — there is
/// nothing to copy back, and asking the JVM to copy would be wasted work.
pub(crate) struct CriticalFloatSlice {
    /// The raw `JNIEnv` the array was pinned through. Held so the release goes to the same table.
    env: *mut RawEnv,
    array: jarray,
    ptr: *mut jfloat,
    len: usize,
}

impl CriticalFloatSlice {
    /// Pins `array` and returns a guard over its elements.
    ///
    /// # Safety
    ///
    /// `env` must be the `JNIEnv` of the thread currently executing the native method, and `array`
    /// must be a valid `float[]` local reference in that frame.
    pub(crate) unsafe fn new(
        env: &mut JNIEnv,
        array: &JPrimitiveArray<'_, jfloat>,
    ) -> Result<Self, Status> {
        let len = env
            .get_array_length(array)
            .map_err(|_| Status::InvalidInput)?;
        let len = usize::try_from(len).map_err(|_| Status::InvalidInput)?;
        // `get_raw` returns a pointer to the environment pointer; the JNI functions want the
        // environment pointer itself, which is the function table.
        // `get_raw` returns the address of the environment pointer, which is exactly what a JNI
        // function expects as its first argument; dereferencing it here was the defect.
        let raw = env.get_raw();
        let mut is_copy: jboolean = 0;
        // SAFETY: the caller guarantees `env` and `array` belong to this thread's frame.
        let ptr = unsafe { get_primitive_array_critical(raw, array.as_raw(), &mut is_copy) }
            .cast::<jfloat>();
        if ptr.is_null() {
            // A null result means the JVM could not pin the array, usually because it is out of
            // memory. Nothing was acquired, so there is nothing to release.
            return Err(Status::Internal);
        }
        Ok(Self {
            env: raw,
            array: array.as_raw(),
            ptr,
            len,
        })
    }

    /// The pinned elements. The length is the array's length at pin time.
    pub(crate) fn as_slice(&self) -> &[f32] {
        // SAFETY: `ptr` is non-null and points at `len` `jfloat`s, which are `f32` on every
        // supported platform, and the JVM guarantees the storage is stable until release.
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }

    pub(crate) fn len(&self) -> usize {
        self.len
    }
}

impl Drop for CriticalFloatSlice {
    fn drop(&mut self) {
        // SAFETY: `env`, `array` and `ptr` are exactly what the pin returned for this thread.
        unsafe {
            release_primitive_array_critical(self.env, self.array, self.ptr.cast());
        }
    }
}

/// Compiles a skeleton from three Java arrays and returns its handle.
///
/// # Safety
///
/// `env` must be the `JNIEnv` of the thread currently executing the native method.
pub(crate) unsafe fn compile_skeleton(
    env: &mut JNIEnv,
    parents: &JPrimitiveArray<'_, jint>,
    pivots: &JPrimitiveArray<'_, jfloat>,
    glow: &JPrimitiveArray<'_, jint>,
) -> Result<u64, Status> {
    let bone_count = usize::try_from(
        env.get_array_length(parents)
            .map_err(|_| Status::InvalidInput)?,
    )
    .map_err(|_| Status::InvalidInput)?;
    let pivot_len = usize::try_from(
        env.get_array_length(pivots)
            .map_err(|_| Status::InvalidInput)?,
    )
    .map_err(|_| Status::InvalidInput)?;
    let glow_len = usize::try_from(
        env.get_array_length(glow)
            .map_err(|_| Status::InvalidInput)?,
    )
    .map_err(|_| Status::InvalidInput)?;
    if pivot_len != bone_count.checked_mul(3).ok_or(Status::InvalidInput)? || glow_len != bone_count
    {
        return Err(Status::InvalidInput);
    }
    // Copy out of the Java arrays before compiling: `CompiledSkeleton::compile` validates and
    // builds its own storage, so nothing here outlives the call.
    // SAFETY: the arrays are valid local references in this frame.
    let parent_values = unsafe { read_int_array(env, parents)? };
    // SAFETY: as above.
    let pivot_values = unsafe { read_float_array(env, pivots)? };
    // SAFETY: as above.
    let glow_values = unsafe { read_int_array(env, glow)? };
    let glow_values = glow_values
        .into_iter()
        .map(|value| u8::try_from(value).map_err(|_| Status::InvalidInput))
        .collect::<Result<Vec<u8>, Status>>()?;
    let skeleton = CompiledSkeleton::compile(&parent_values, &pivot_values, &glow_values)?;
    skeletons().insert(skeleton)
}

/// Builds the `(isa << 32) | bones` outcome word the Java side decodes.
pub(crate) fn outcome_word(isa: u32, bones: usize) -> i64 {
    ((isa as u64) << 32 | (bones as u64 & 0xFFFF_FFFF)) as i64
}

/// Converts a `Status` into the negative `jlong` the Java side checks for.
pub(crate) fn status_word(status: Status) -> i64 {
    status as i32 as i64
}

// ---------------------------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------------------------

/// Compiles a skeleton and returns its handle, or a negative `Status` on rejection.
///
/// The handle is a `long` rather than an object so the caller can hold it in a plain field and
/// close it deterministically. Nothing is published until compilation succeeds, so a rejected
/// skeleton leaves no handle behind.
#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativePackedBoneEvaluator_nCompileSkeleton(
    mut env: JNIEnv,
    _class: JClass,
    parents: JIntArray,
    pivots: JFloatArray,
    glow: JIntArray,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| {
        if env.exception_check().map_err(|_| Status::Internal)? {
            return Err(Status::InvalidInput);
        }
        if cfg!(target_endian = "big") {
            return Err(Status::InvalidInput);
        }
        // SAFETY: the arrays are valid local references in this frame.
        let handle = unsafe { compile_skeleton(&mut env, &parents, &pivots, &glow)? };
        Ok::<_, Status>(handle as jlong)
    })) {
        Ok(Ok(value)) => value,
        Ok(Err(status)) => status_word(status),
        Err(_) => status_word(Status::Internal),
    }
}

/// Frees a skeleton handle. A stale or unknown handle is rejected, not ignored.
#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativePackedBoneEvaluator_nFreeSkeleton(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    match catch_unwind(AssertUnwindSafe(|| {
        skeletons().free(handle as u64).map(|()| 0_i32)
    })) {
        Ok(Ok(value)) => value,
        Ok(Err(status)) => status as jint,
        Err(_) => Status::Internal as jint,
    }
}

/// How many skeletons are currently registered, so a leak is observable without a GC.
#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativePackedBoneEvaluator_nOwnedSkeletons(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        jlong::try_from(skeletons().live()).unwrap_or(jlong::MAX)
    }))
    .unwrap_or(-1)
}

/// Evaluates one frame and writes `bone_count * 144` bytes into `out`.
///
/// `params` is pinned rather than copied: the host rewrites it every frame, and a copy would add a
/// full array traversal to a path whose purpose is to avoid per-frame allocation. `root` and `out`
/// are direct buffers, so they need no pinning.
///
/// Returns `(isa << 32) | bones` on success, or a negative `Status`. Nothing is written until the
/// whole frame has been evaluated and checked finite, so a rejected frame leaves `out` untouched.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativePackedBoneEvaluator_nEvaluatePacked(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    root: JFloatArray,
    params: JFloatArray,
    out: JByteBuffer,
    state: JByteBuffer,
    flags: jint,
    request: jint,
    persistent: jboolean,
    packed_light: jint,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| {
        evaluate_packed(
            &mut env,
            handle,
            root,
            params,
            out,
            state,
            flags,
            request,
            persistent,
            packed_light,
        )
    })) {
        Ok(Ok(value)) => value,
        Ok(Err(status)) => status_word(status),
        Err(_) => status_word(Status::Internal),
    }
}

#[allow(clippy::too_many_arguments)]
fn evaluate_packed(
    env: &mut JNIEnv,
    handle: jlong,
    root: JFloatArray,
    params: JFloatArray,
    out: JByteBuffer,
    state: JByteBuffer,
    flags: jint,
    request: jint,
    persistent: jboolean,
    packed_light: i32,
) -> Result<jlong, Status> {
    if cfg!(target_endian = "big") || env.exception_check().map_err(|_| Status::Internal)? {
        return Err(Status::InvalidInput);
    }
    let (layout, pass) =
        decode_packed_flags(u32::try_from(flags).map_err(|_| Status::InvalidInput)?)?;
    let isa = Isa::try_from(u32::try_from(request).map_err(|_| Status::UnsupportedIsa)?)?;
    // Select before touching any buffer; an unsupported forced request never executes.
    if let Err(e) = spm_render_simd::select(isa) {
        eprintln!("DBG fail: select {e:?}");
        return Err(e);
    }

    // Resolve the handle first: a stale one must be reported as such, before any buffer is
    // inspected, so the caller learns the real defect rather than a misleading buffer error.
    let table = skeletons();
    let skeleton = match table.get(handle as u64) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("DBG fail: handle {e:?}");
            return Err(e);
        }
    };
    let n = skeleton.bone_count();
    let param_floats = skeleton.param_floats().ok_or(Status::InvalidInput)?;
    let packed_bytes = skeleton.packed_bytes().ok_or(Status::InvalidInput)?;

    let root_len = usize::try_from(
        env.get_array_length(&root)
            .map_err(|_| Status::InvalidInput)?,
    )
    .map_err(|_| Status::InvalidInput)?;
    if root_len < ROOT_FLOATS {
        return Err(Status::BufferTooSmall);
    }
    if env
        .get_direct_buffer_capacity(&out)
        .map_err(|_| Status::InvalidInput)?
        < packed_bytes
    {
        return Err(Status::BufferTooSmall);
    }
    if env
        .call_method(&out, "isReadOnly", "()Z", &[])
        .and_then(|v| v.z())
        .map_err(|_| Status::InvalidInput)?
    {
        return Err(Status::InvalidInput);
    }
    // The state buffer is optional: a null reference means the caller does not want locators.
    let state_capacity = if state.is_null() {
        0
    } else {
        env.get_direct_buffer_capacity(&state)
            .map_err(|_| Status::InvalidInput)?
    };
    let wants_state = !state.is_null();
    if wants_state && state_capacity < n.checked_mul(16).ok_or(Status::InvalidInput)? {
        return Err(Status::BufferTooSmall);
    }

    // SAFETY: `params` is a valid local reference in this frame, and the guard releases it on
    // every exit path including a panic.
    let params = unsafe { CriticalFloatSlice::new(env, &params)? };
    if params.len() < param_floats {
        return Err(Status::BufferTooSmall);
    }
    // The root pose is a Java array rather than a direct buffer: it is 25 floats, and pinning it is
    // cheaper than making the caller allocate a buffer per frame.
    // SAFETY: `root` is a valid local reference in this frame, and the guard releases it on every
    // exit path.
    let root_slice = unsafe { CriticalFloatSlice::new(env, &root)? };
    let mut root_values = [0_f32; ROOT_FLOATS];
    root_values.copy_from_slice(&root_slice.as_slice()[..ROOT_FLOATS]);

    let out_ptr = env
        .get_direct_buffer_address(&out)
        .map_err(|_| Status::InvalidInput)?;

    // The locator write-back needs a `&mut [f32]` over the state buffer. It is built here and
    // dropped before the buffer is released, so no borrow outlives the call.
    let state_slice = if wants_state {
        let ptr = env
            .get_direct_buffer_address(&state)
            .map_err(|_| Status::InvalidInput)?;
        // SAFETY: the capacity check above guarantees `n * 4` floats are writable, and the caller
        // owns the buffer for the duration of the call.
        Some(unsafe { std::slice::from_raw_parts_mut(ptr.cast::<f32>(), n * 4) })
    } else {
        None
    };

    let packed_request = PackedRequest {
        skeleton,
        root: &root_values,
        params: params.as_slice(),
        layout,
        pass,
        packed_light,
        full_bright_light: FULL_BRIGHT_LIGHT,
        isa,
        persistent_output: persistent != 0,
        path: None,
    };
    // SAFETY: `out` has `n * 144` writable bytes, `params` is pinned for the call, and `state`
    // (when present) has `n * 4` writable floats. None of them alias: they are distinct Java
    // buffers, and the caller's contract forbids passing the same buffer twice.
    let outcome = unsafe { evaluate_packed_frame(&packed_request, out_ptr, state_slice) }?;
    Ok(outcome_word(outcome.isa as u32, outcome.bones))
}

/// The full-bright light value the host writes for glow bones.
///
/// This is part of the record layout rather than a rendering choice: a glow bone is always
/// full-bright, so the value is fixed here. The ordinary light is not fixed — the host passes it
/// per frame, because it depends on where the entity stands.
const FULL_BRIGHT_LIGHT: i32 = 0x00F0_00F0;

/// Calls the packed evaluator, keeping the `unsafe` contract in one place.
///
/// # Safety
///
/// `out` must be valid for `skeleton.bone_count() * 144` writable bytes, `state` for
/// `bone_count * 4` writable floats, and neither may alias `request.params` or `request.root`.
unsafe fn evaluate_packed_frame(
    request: &PackedRequest<'_>,
    out: *mut u8,
    state: Option<&mut [f32]>,
) -> Result<PackedOutcome, Status> {
    // SAFETY: the caller establishes every invariant.
    unsafe { spm_render_simd::evaluate_packed(request, out, state) }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn skeleton(bones: usize) -> CompiledSkeleton {
        let parents = vec![-1_i32; bones];
        let pivots = vec![0.5_f32; bones * 3];
        let glow = vec![0_u8; bones];
        CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap()
    }

    #[test]
    fn a_handle_resolves_until_it_is_freed() {
        let mut table = SlotTable::new();
        let handle = table.insert(skeleton(2)).unwrap();
        assert_eq!(table.get(handle).unwrap().bone_count(), 2);
        assert_eq!(table.live(), 1);
        table.free(handle).unwrap();
        assert_eq!(table.get(handle).unwrap_err(), Status::InvalidHandle);
        assert_eq!(table.live(), 0);
    }

    #[test]
    fn a_stale_handle_never_resolves_to_the_slot_s_next_occupant() {
        let mut table = SlotTable::new();
        let first = table.insert(skeleton(2)).unwrap();
        table.free(first).unwrap();
        let second = table.insert(skeleton(5)).unwrap();
        // The slot is reused, so the indices match; only the generation distinguishes them.
        assert_eq!(decode(first).0, decode(second).0);
        assert_ne!(first, second);
        assert_eq!(table.get(first).unwrap_err(), Status::InvalidHandle);
        assert_eq!(table.get(second).unwrap().bone_count(), 5);
    }

    #[test]
    fn freeing_twice_and_freeing_unknown_handles_is_rejected() {
        let mut table = SlotTable::new();
        let handle = table.insert(skeleton(1)).unwrap();
        table.free(handle).unwrap();
        assert_eq!(table.free(handle).unwrap_err(), Status::InvalidHandle);
        for invalid in [0, 1, u64::MAX, encode(99, 1)] {
            assert_eq!(table.free(invalid).unwrap_err(), Status::InvalidHandle);
        }
        assert_eq!(table.live(), 0);
    }

    #[test]
    fn a_zero_handle_is_never_valid() {
        let mut table = SlotTable::new();
        // Slot 0's first occupant uses generation 1, so handle 0 addresses nothing.
        let handle = table.insert(skeleton(1)).unwrap();
        assert_ne!(handle, 0);
        assert_eq!(table.get(0).unwrap_err(), Status::InvalidHandle);
    }

    #[test]
    fn handles_round_trip_through_their_parts() {
        for (index, generation) in [(0_usize, 1_u32), (7, 3), (1234, 65_535)] {
            let handle = encode(index, generation);
            assert_eq!(decode(handle), (index, generation));
        }
    }

    #[test]
    fn the_outcome_word_carries_the_isa_and_the_bone_count() {
        let word = outcome_word(4, 773);
        assert_eq!((word >> 32) as u32, 4);
        assert_eq!((word & 0xFFFF_FFFF) as usize, 773);
    }
}
