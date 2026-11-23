//! Packed fast-path bone evaluation: level-parallel `f32` SIMD with cached local matrices.
//!
//! # Relationship to the reference evaluator
//!
//! [`crate::bones`] is the reference implementation. It accumulates in `f64`, rebuilds the graph
//! per call, and returns one `BoneOutput` per bone. This module is the real-time path: it consumes
//! a pre-compiled [`CompiledSkeleton`], accumulates in `f32`, and writes the host's packed record
//! straight into caller-owned memory. The two must agree to within `1e-4` relative, which the
//! differential tests in `tests/fast_bones.rs` assert for every tier this host can execute.
//!
//! # Where the time goes
//!
//! Measured on the real rigs in `D:\SparkleMorpher\TestModel` (577 and 773 bones, 25 and 26 levels
//! deep), the cost splits roughly evenly between three parts: three `sin_cos` calls per bone, the
//! hierarchy composition, and the assembly of the host's 144-byte-per-bone record. All three are
//! addressed here:
//!
//! * **Local matrices are cached.** A bone's local matrix depends only on its own rotation,
//!   translation and scale. On a real rig most bones do not move between frames, so the three
//!   `sin_cos` calls are skipped for the ones that did not. This is the "design C" path.
//! * **Trigonometry is evaluated eight bones at a time** for the ones that did move. Siblings at the
//!   same tree depth have no dependency on each other, so one 8-wide `sin_cos` serves eight bones.
//! * **The 144-byte record is assembled with vector stores.** The host's Java writer calls
//!   `ByteBuffer.putFloat` 36 times per bone, which measured at 98 µs for a 773-bone rig — more than
//!   half its total cost. Writing the record as nine 16-byte stores removes that.
//!
//! # Why `f32` is safe here
//!
//! The reference accumulates in `f64` because it must match the Java scalar implementation bit for
//! bit. This path instead targets the GPU buffer, whose consumer is a shader that already works in
//! `f32`. The differential tests record the actual worst case rather than assuming it; against the
//! Java writer it is `6e-7` over seven real models.
//!
//! # Safety model
//!
//! Every `unsafe` block in this module is one of exactly three kinds, and each carries a `SAFETY:`
//! note naming its invariant:
//!
//! 1. **SIMD intrinsics**, reachable only through [`compose_kernel`], which is reached only through
//!    [`evaluate_packed`] after [`crate::select`] verified the CPU features the chosen kernel
//!    requires. The kernel functions are `#[target_feature]` and never called from safe code.
//! 2. **Unchecked scratch access**, justified by [`ScratchView`] being sized to exactly
//!    `skeleton.bone_count()` and by every index coming from the skeleton's own level plan.
//! 3. **Raw output writes**, reachable only through [`evaluate_packed`], which checks
//!    `out_cap >= bone_count * 144` and `params_len >= bone_count * 12` before any pointer is
//!    formed.
use spm_native_abi::{
    BonePassFilter, Isa, PackedLayout, Status, BONE_PARAM_STRIDE, PACKED_BONE_STRIDE, ROOT_FLOATS,
};
use std::cell::RefCell;

use crate::compiled_skeleton::CompiledSkeleton;

/// Per-thread scratch, so a hot evaluation allocates nothing after the first frame at a given size.
///
/// `RefCell` is enough because a single evaluation never re-enters itself; a nested call would
/// panic on the borrow rather than silently corrupt the scratch, which is the failure mode we want.
#[derive(Default)]
struct Scratch {
    /// World transforms, 16 floats per bone, column-major, in level order.
    transforms: Vec<f32>,
    /// World normal matrices, 9 floats per bone, column-major, in level order.
    normals: Vec<f32>,
    /// Per-bone visibility.
    visible: Vec<u8>,
    /// Per-bone "children may render" flag, from `boneParams[i*12+10]`.
    descendants: Vec<u8>,
    /// Per-bone uniform world scale; `0` means "not uniform", which forces the cofactor path.
    uniform_scale: Vec<f32>,
    /// Locator write-back values, 4 floats per bone (`x`, `y`, `z`, unused).
    locators: Vec<f32>,
    /// Per-bone flag: this bone queued a locator.
    locator_mask: Vec<u8>,

    // ---- cached local matrices and change tracking ----
    /// Cached local matrix per bone, 16 floats, column-major, indexed by bone (not level order).
    ///
    /// This is the whole point of the cache: a bone's local matrix depends only on its own
    /// parameters, so when those do not change the matrix does not change either. Recomputing it
    /// every frame costs three `sin_cos` calls per bone, which on the real rigs measured here is
    /// the single largest cost.
    locals: Vec<f32>,
    /// A bit-identical copy of the whole parameter array from the last frame.
    ///
    /// Comparing this first turns a static frame into one contiguous comparison instead of a
    /// strided nine-of-twelve scan over every bone, which was measured at 16 µs for 773 bones.
    params_mirror: Vec<f32>,
    /// Per-bone flag: this bone's own local matrix changed this frame.
    dirty: Vec<u8>,
    /// Per-bone flag: this bone or one of its ancestors changed, so its world matrix must be
    /// recomputed. This is what makes a fully-dirty frame no worse than the uncached path.
    subtree_dirty: Vec<u8>,
    /// How many bones moved in the last [`detect_changes`], and how many that reached.
    ///
    /// Kept so the ratio that selects between the cached and uncached kernels is the one the
    /// detection pass actually measured, rather than a second estimate that could disagree with it.
    moved: usize,
    reached: usize,
    /// Which skeleton the caches belong to. A different skeleton invalidates everything, because
    /// bone indices and pivots would no longer line up.
    cached_bones: usize,
    /// A cheap identity for the cached skeleton, so two skeletons of the same size do not share
    /// stale caches.
    cached_identity: u64,
    /// The pass filter, light and layout the current records were written with.
    ///
    /// A change to any of these forces a full rewrite even when no bone moved, because the record
    /// contents depend on them and not only on the matrices.
    cached_pass: u8,
    cached_light: i32,
    cached_full_bright: i32,
    cached_layout: u8,
    /// True once a frame has been written, so the first frame always rewrites everything.
    frame_valid: bool,
    /// Output buffers that currently hold correct records for the mirrored parameters.
    ///
    /// `frame_valid` alone says "the records are up to date" without saying *which* buffer they are
    /// up to date in. With two output buffers alternating, that let the second one keep records
    /// from an older frame — so the host had to pass `persistent_output = false` and pay a full
    /// rewrite on every static frame. Tracking addresses makes the cheap early-out safe for the
    /// real callers: a static scene settles as soon as every buffer has been written once.
    valid_outputs: [usize; MAX_VALID_OUTPUTS],
    /// Number of entries in [`Scratch::valid_outputs`].
    valid_output_count: u8,
    /// True when `locals` no longer describes the last frame's parameters.
    ///
    /// The uncached kernel does not maintain the local-matrix cache — storing into it is exactly
    /// the cost it exists to avoid — so after it runs `locals` is stale. The next cached frame
    /// then rebuilds every local matrix once and clears this flag. That is a clean switch: it
    /// costs one full rebuild on the frame that returns to the cached path, and nothing after it.
    ///
    /// This is deliberately *not* done by poisoning a parameter array. An earlier attempt filled
    /// the cache's provenance array with `NaN` while leaving `locals` populated, so the following
    /// sparse frame saw every bone as dirty, took the uncached path again, and every scenario
    /// regressed. Dirtiness is now derived from `params_mirror`, which always describes the
    /// previous frame, so this flag cannot feed back into the choice.
    locals_stale: bool,
}

impl Scratch {
    fn ensure(&mut self, bone_count: usize) -> Result<(), Status> {
        let transforms = bone_count.checked_mul(16).ok_or(Status::InvalidInput)?;
        let normals = bone_count.checked_mul(9).ok_or(Status::InvalidInput)?;
        let locators = bone_count.checked_mul(4).ok_or(Status::InvalidInput)?;
        grow(&mut self.transforms, transforms)?;
        grow(&mut self.normals, normals)?;
        grow(&mut self.locators, locators)?;
        grow(&mut self.visible, bone_count)?;
        grow(&mut self.descendants, bone_count)?;
        grow(&mut self.locator_mask, bone_count)?;
        grow(&mut self.uniform_scale, bone_count)?;
        grow(&mut self.locals, transforms)?;
        grow(&mut self.params_mirror, bone_count * BONE_PARAM_STRIDE)?;
        grow(&mut self.dirty, bone_count)?;
        grow(&mut self.subtree_dirty, bone_count)?;
        Ok(())
    }

    /// Splits the scratch into the disjoint views the record writer and the writeback need.
    fn view(&mut self, bone_count: usize) -> ScratchView<'_> {
        ScratchView {
            visible: &mut self.visible[..bone_count],
            locators: &mut self.locators[..bone_count * 4],
            locator_mask: &mut self.locator_mask[..bone_count],
        }
    }

    /// Whether `out` already holds records written for the mirrored parameters.
    fn output_is_valid(&self, out: usize) -> bool {
        self.valid_outputs[..self.valid_output_count as usize].contains(&out)
    }

    /// Forgets every buffer, used when an input that shapes every record changed.
    fn invalidate_outputs(&mut self) {
        self.valid_output_count = 0;
    }

    /// Forgets every buffer except `keep`.
    ///
    /// A parameter change leaves the other buffers holding records for the previous frame, so they
    /// must be rewritten wholesale when they come around. The buffer being written now is different:
    /// only the bones that moved are stale in it, and the caller's dirty-only write repairs exactly
    /// those. Dropping its entry too would force a full record rewrite on every animated frame.
    fn invalidate_other_outputs(&mut self, keep: usize) {
        let count = self.valid_output_count as usize;
        if !self.valid_outputs[..count].contains(&keep) {
            self.valid_output_count = 0;
            return;
        }
        let mut next = 0;
        for index in 0..count {
            let candidate = self.valid_outputs[index];
            if candidate == keep {
                self.valid_outputs[next] = candidate;
                next += 1;
            }
        }
        self.valid_output_count = next as u8;
    }

    /// Records that `out` now holds correct records, evicting the oldest entry when full.
    fn mark_output(&mut self, out: usize) {
        if self.output_is_valid(out) {
            return;
        }
        if (self.valid_output_count as usize) >= MAX_VALID_OUTPUTS {
            self.valid_outputs.rotate_left(1);
            self.valid_outputs[MAX_VALID_OUTPUTS - 1] = out;
        } else {
            self.valid_outputs[self.valid_output_count as usize] = out;
            self.valid_output_count += 1;
        }
    }
}

/// Grows `buffer` to at least `len` elements, preserving nothing and allocating fallibly.
fn grow<T: Copy + Default>(buffer: &mut Vec<T>, len: usize) -> Result<(), Status> {
    if buffer.len() >= len {
        return Ok(());
    }
    buffer.clear();
    buffer
        .try_reserve_exact(len)
        .map_err(|_| Status::Internal)?;
    buffer.resize(len, T::default());
    Ok(())
}

/// The disjoint scratch views the caller needs after an evaluation.
struct ScratchView<'a> {
    visible: &'a mut [u8],
    locators: &'a mut [f32],
    locator_mask: &'a mut [u8],
}

thread_local! {
    static SCRATCH: RefCell<Scratch> = RefCell::new(Scratch::default());
}

/// A packed evaluation request, borrowing every input for the duration of the call.
pub struct PackedRequest<'a> {
    pub skeleton: &'a CompiledSkeleton,
    /// `[0..16)` root pose (column-major), `[16..25)` root normal (column-major 3x3).
    pub root: &'a [f32],
    /// `bone_count * 12` floats, the host's existing per-bone parameter layout.
    pub params: &'a [f32],
    pub layout: PackedLayout,
    pub pass: BonePassFilter,
    pub packed_light: i32,
    pub full_bright_light: i32,
    pub isa: Isa,
    /// True when `out` still holds the previous frame's records and the caller will keep it.
    ///
    /// This is the host's real situation: `mesh.perFrameBoneBuffer` is a per-mesh direct buffer
    /// that is overwritten in place each frame. When the flag is set and nothing that feeds a
    /// bone's record changed, that bone's record is left untouched — which is the difference
    /// between a static frame costing a full rewrite and costing nothing.
    ///
    /// When the flag is false (the default a caller should use unless it owns a persistent
    /// buffer) every record is written, so a freshly allocated or zeroed buffer still comes out
    /// correct.
    pub persistent_output: bool,
    /// Forces a kernel instead of letting the dirty ratio choose one.
    ///
    /// `None` is what a caller should use: the choice is then made from how much of the rig
    /// actually moved. `Some` exists so the two kernels can be measured against each other on the
    /// same frame — a benchmark that could only observe the automatic choice could not tell which
    /// side of the crossover a scenario fell on, or whether the crossover is in the right place.
    pub path: Option<PackedPath>,
}

/// Which of the two evaluation kernels served a frame.
///
/// The two are not interchangeable in cost: the cached kernel pays a per-bone cache store and a
/// nine-float parameter copy to skip trigonometry it would otherwise repeat, which is a large win
/// while few bones move and a pure loss when most do. Reporting the choice lets a caller (and the
/// benchmark) see which path a frame actually took instead of inferring it from the timing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PackedPath {
    /// Local matrices were reused from the cache and only the affected subtrees recomposed.
    Cached,
    /// Every local matrix and world matrix was rebuilt; the cache was not consulted or updated.
    Uncached,
}

/// Result of a packed evaluation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PackedOutcome {
    /// The tier whose instructions actually ran; never wider than the request.
    pub isa: Isa,
    /// Bones written, which is always the skeleton's bone count.
    pub bones: usize,
    /// Which kernel served this frame.
    pub path: PackedPath,
}

/// Evaluates `request` and writes `bone_count * 144` bytes into `out`.
///
/// `out` must be at least `bone_count * 144` bytes. When `state` is `Some`, locator positions for
/// bones whose `boneParams[i*12+11] == 1` are written to `state[i*4 .. i*4+3]`, matching the host's
/// existing state buffer layout.
///
/// Nothing is written until every bone that needed work has been evaluated and checked finite, so
/// a rejected frame leaves the caller's buffers untouched — the "failed batches must not publish
/// partial output" rule.
///
/// # Safety
///
/// `out` must be valid for writes of `bone_count * 144` bytes for the duration of the call, and
/// `state`, when present, for `bone_count * 4` floats. Neither may alias `request.params` or
/// `request.root`. The JNI layer establishes all of this: the output is a direct `ByteBuffer`
/// whose capacity it checked, and the inputs are a Java array or a distinct direct buffer.
pub unsafe fn evaluate_packed(
    request: &PackedRequest<'_>,
    out: *mut u8,
    state: Option<&mut [f32]>,
) -> Result<PackedOutcome, Status> {
    let skeleton = request.skeleton;
    let n = skeleton.bone_count();
    let param_floats = skeleton.param_floats().ok_or(Status::InvalidInput)?;
    if out.is_null() {
        return Err(Status::InvalidInput);
    }
    if request.root.len() < ROOT_FLOATS {
        return Err(Status::InvalidInput);
    }
    if request.params.len() < param_floats {
        return Err(Status::BufferTooSmall);
    }
    if let Some(state) = state.as_deref() {
        if state.len() < n.checked_mul(4).ok_or(Status::InvalidInput)? {
            return Err(Status::BufferTooSmall);
        }
    }
    if request.root[..ROOT_FLOATS].iter().any(|v| !v.is_finite()) {
        return Err(Status::NonFinite);
    }
    if request.params[..param_floats]
        .iter()
        .any(|v| !v.is_finite())
    {
        return Err(Status::NonFinite);
    }
    let isa = crate::select(request.isa)?;
    let id = step_tier(isa);

    let mut root = [0_f32; ROOT_FLOATS];
    root.copy_from_slice(&request.root[..ROOT_FLOATS]);

    SCRATCH.with(|cell| {
        let mut scratch = cell.borrow_mut();
        scratch.ensure(n)?;
        let param_floats = skeleton.param_floats().ok_or(Status::InvalidInput)?;
        // A different skeleton invalidates every cache: bone indices, pivots and the level plan
        // would no longer line up with what was cached.
        let identity = skeleton.identity();
        if scratch.cached_identity != identity || scratch.cached_bones != n {
            scratch.cached_identity = identity;
            scratch.cached_bones = n;
            scratch.frame_valid = false;
            scratch.locals_stale = true;
            // A different skeleton means the remembered output addresses belong to a previous
            // model's buffers; they must not be treated as current for this one.
            scratch.invalidate_outputs();
            // Force every bone dirty so the first frame at this skeleton rebuilds everything.
            scratch.dirty[..n].fill(1);
            scratch.subtree_dirty[..n].fill(1);
            scratch.params_mirror[..param_floats].fill(f32::NAN);
        }
        // SAFETY: `scratch` is sized for `n` bones by `Scratch::ensure`, and the cache identity
        // above guarantees it belongs to this skeleton.
        let path =
            unsafe { evaluate_cached(skeleton, request.params, request, &mut scratch, out, isa)? };
        let view = scratch.view(n);
        if let Some(state) = state {
            for bone in 0..n {
                if view.locator_mask[bone] == 0 {
                    continue;
                }
                let q = bone * 4;
                state[q] = view.locators[q];
                state[q + 1] = view.locators[q + 1];
                state[q + 2] = view.locators[q + 2];
            }
        }
        let _ = view.visible;
        Ok(PackedOutcome {
            isa: id,
            bones: n,
            path,
        })
    })
}

/// The canonical hidden record: identity transform, identity normal, hidden flag set.
///
/// Written for every bone the pass filter hides, so the caller's buffer is fully defined for all
/// bone indices and no stale record can survive from a previous frame.
fn hidden_record(layout: PackedLayout) -> [u8; PACKED_BONE_STRIDE] {
    let mut record = [0_u8; PACKED_BONE_STRIDE];
    let identity = [
        1_f32, 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    for (slot, value) in identity.iter().chain(identity.iter()).enumerate() {
        record[slot * 4..slot * 4 + 4].copy_from_slice(&value.to_le_bytes());
    }
    match layout {
        PackedLayout::FloatTail => {
            record[128..132].copy_from_slice(&0_f32.to_le_bytes());
            record[132..136].copy_from_slice(&1_f32.to_le_bytes());
        }
        PackedLayout::IntTail => {
            record[128..132].copy_from_slice(&0_i32.to_le_bytes());
            record[132..136].copy_from_slice(&1_i32.to_le_bytes());
        }
    }
    record
}

/// The root pose and normal, held as 4-wide columns.
///
/// Folding the root into the record assembly rather than into the hierarchy keeps the normal
/// matrix derivation local (the host's Java writer does the same), and doing it with 4-wide column
/// vectors turns 64 scalar multiplies and 16 scalar stores per bone into 16 multiplies and 4
/// stores.
struct RootColumns {
    /// Four columns of the 4x4 pose, each four floats.
    pose: [[f32; 4]; 4],
    /// Three columns of the 3x3 normal, each padded to four floats.
    normal: [[f32; 4]; 3],
}

impl RootColumns {
    fn new(root: &[f32]) -> Self {
        let mut pose = [[0_f32; 4]; 4];
        for (col, slot) in pose.iter_mut().enumerate() {
            slot.copy_from_slice(&root[col * 4..col * 4 + 4]);
        }
        let mut normal = [[0_f32; 4]; 3];
        for (col, slot) in normal.iter_mut().enumerate() {
            slot[..3].copy_from_slice(&root[16 + col * 3..16 + col * 3 + 3]);
        }
        Self { pose, normal }
    }

    /// Writes one visible record: `pose * world`, `normal * worldNormal`, then the tail.
    ///
    /// # Safety
    ///
    /// `record` must be valid for 144 writable bytes.
    #[inline]
    unsafe fn write_record(
        &self,
        record: *mut u8,
        m: &[f32],
        nm: &[f32],
        light: i32,
        layout: PackedLayout,
    ) {
        #[cfg(target_arch = "x86_64")]
        // SAFETY: SSE2 is part of the x86_64 baseline, so it is always available here.
        unsafe {
            self.write_record_sse2(record, m, nm, light, layout)
        };
        #[cfg(target_arch = "aarch64")]
        // SAFETY: NEON is part of the aarch64 baseline, so it is always available here.
        unsafe {
            self.write_record_neon(record, m, nm, light, layout)
        };
        #[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
        // SAFETY: the caller guarantees 144 writable bytes at `record`.
        unsafe {
            self.write_record_scalar(record, m, nm, light, layout)
        };
    }

    /// The portable form, used on targets without a baseline SIMD width.
    ///
    /// # Safety
    ///
    /// `record` must be valid for 144 writable bytes.
    #[allow(dead_code)]
    unsafe fn write_record_scalar(
        &self,
        record: *mut u8,
        m: &[f32],
        nm: &[f32],
        light: i32,
        layout: PackedLayout,
    ) {
        for col in 0..4 {
            for row in 0..4 {
                let value = self.pose[0][row] * m[col * 4]
                    + self.pose[1][row] * m[col * 4 + 1]
                    + self.pose[2][row] * m[col * 4 + 2]
                    + self.pose[3][row] * m[col * 4 + 3];
                // SAFETY: `col * 16 + row * 4` is at most 60, inside the 144-byte slot.
                unsafe {
                    std::ptr::write_unaligned(record.add(col * 16 + row * 4).cast::<f32>(), value);
                }
            }
        }
        for col in 0..3 {
            for row in 0..3 {
                let value = self.normal[0][row] * nm[col * 3]
                    + self.normal[1][row] * nm[col * 3 + 1]
                    + self.normal[2][row] * nm[col * 3 + 2];
                // SAFETY: `64 + col * 16 + row * 4` is at most 120, inside the slot.
                unsafe {
                    std::ptr::write_unaligned(
                        record.add(64 + col * 16 + row * 4).cast::<f32>(),
                        value,
                    );
                }
            }
            // SAFETY: the fourth row of each of the first three columns is at most 124.
            unsafe {
                std::ptr::write_unaligned(record.add(64 + col * 16 + 12).cast::<f32>(), 0_f32);
            }
        }
        // SAFETY: the fourth column occupies bytes 112..128 of the slot.
        unsafe {
            std::ptr::write_unaligned(record.add(112).cast::<f32>(), 0_f32);
            std::ptr::write_unaligned(record.add(116).cast::<f32>(), 0_f32);
            std::ptr::write_unaligned(record.add(120).cast::<f32>(), 0_f32);
            std::ptr::write_unaligned(record.add(124).cast::<f32>(), 1_f32);
        }
        // SAFETY: the tail occupies bytes 128..144 of the same slot.
        unsafe { write_tail(record, light, layout) };
    }

    /// SSE2 form: one 4-wide multiply-add chain per output column.
    ///
    /// # Safety
    ///
    /// `record` must be valid for 144 writable bytes.
    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "sse2")]
    unsafe fn write_record_sse2(
        &self,
        record: *mut u8,
        m: &[f32],
        nm: &[f32],
        light: i32,
        layout: PackedLayout,
    ) {
        use std::arch::x86_64::*;
        let r0 = _mm_loadu_ps(self.pose[0].as_ptr());
        let r1 = _mm_loadu_ps(self.pose[1].as_ptr());
        let r2 = _mm_loadu_ps(self.pose[2].as_ptr());
        let r3 = _mm_loadu_ps(self.pose[3].as_ptr());
        for col in 0..4 {
            // `m[col*4 + k]` is broadcast and multiplied by root column `k`, so the result column
            // is `sum_k root[k] * m[col][k]` — the same order the scalar form uses.
            let mut acc = _mm_mul_ps(r0, _mm_set1_ps(m[col * 4]));
            acc = _mm_add_ps(acc, _mm_mul_ps(r1, _mm_set1_ps(m[col * 4 + 1])));
            acc = _mm_add_ps(acc, _mm_mul_ps(r2, _mm_set1_ps(m[col * 4 + 2])));
            acc = _mm_add_ps(acc, _mm_mul_ps(r3, _mm_set1_ps(m[col * 4 + 3])));
            // SAFETY: `col * 16` is at most 48, so the 16-byte store stays inside the slot.
            unsafe { _mm_storeu_ps(record.add(col * 16).cast::<f32>(), acc) };
        }
        let n0 = _mm_loadu_ps(self.normal[0].as_ptr());
        let n1 = _mm_loadu_ps(self.normal[1].as_ptr());
        let n2 = _mm_loadu_ps(self.normal[2].as_ptr());
        // The fourth lane of each normal column is the homogeneous row, which must be zero.
        let keep_xyz = _mm_castsi128_ps(_mm_setr_epi32(-1, -1, -1, 0));
        for col in 0..3 {
            let mut acc = _mm_mul_ps(n0, _mm_set1_ps(nm[col * 3]));
            acc = _mm_add_ps(acc, _mm_mul_ps(n1, _mm_set1_ps(nm[col * 3 + 1])));
            acc = _mm_add_ps(acc, _mm_mul_ps(n2, _mm_set1_ps(nm[col * 3 + 2])));
            acc = _mm_and_ps(acc, keep_xyz);
            // SAFETY: `64 + col * 16` is at most 96, so the 16-byte store stays inside the slot.
            unsafe { _mm_storeu_ps(record.add(64 + col * 16).cast::<f32>(), acc) };
        }
        // SAFETY: the fourth column occupies bytes 112..128 of the slot.
        unsafe {
            _mm_storeu_ps(record.add(112).cast::<f32>(), _mm_setr_ps(0., 0., 0., 1.));
        }
        // SAFETY: the tail occupies bytes 128..144 of the same slot.
        unsafe { write_tail(record, light, layout) };
    }

    /// NEON form, the aarch64 counterpart of the SSE2 path.
    ///
    /// # Safety
    ///
    /// `record` must be valid for 144 writable bytes.
    #[cfg(target_arch = "aarch64")]
    #[target_feature(enable = "neon")]
    unsafe fn write_record_neon(
        &self,
        record: *mut u8,
        m: &[f32],
        nm: &[f32],
        light: i32,
        layout: PackedLayout,
    ) {
        use std::arch::aarch64::*;
        let r0 = vld1q_f32(self.pose[0].as_ptr());
        let r1 = vld1q_f32(self.pose[1].as_ptr());
        let r2 = vld1q_f32(self.pose[2].as_ptr());
        let r3 = vld1q_f32(self.pose[3].as_ptr());
        for col in 0..4 {
            let mut acc = vmulq_n_f32(r0, m[col * 4]);
            acc = vaddq_f32(acc, vmulq_n_f32(r1, m[col * 4 + 1]));
            acc = vaddq_f32(acc, vmulq_n_f32(r2, m[col * 4 + 2]));
            acc = vaddq_f32(acc, vmulq_n_f32(r3, m[col * 4 + 3]));
            // SAFETY: `col * 16` is at most 48, so the 16-byte store stays inside the slot.
            unsafe { vst1q_f32(record.add(col * 16).cast::<f32>(), acc) };
        }
        let n0 = vld1q_f32(self.normal[0].as_ptr());
        let n1 = vld1q_f32(self.normal[1].as_ptr());
        let n2 = vld1q_f32(self.normal[2].as_ptr());
        for col in 0..3 {
            let mut acc = vmulq_n_f32(n0, nm[col * 3]);
            acc = vaddq_f32(acc, vmulq_n_f32(n1, nm[col * 3 + 1]));
            acc = vaddq_f32(acc, vmulq_n_f32(n2, nm[col * 3 + 2]));
            // The fourth lane of the normal columns is the homogeneous row, which must be zero.
            acc = vsetq_lane_f32(0., acc, 3);
            // SAFETY: `64 + col * 16` is at most 96, so the 16-byte store stays inside the slot.
            unsafe { vst1q_f32(record.add(64 + col * 16).cast::<f32>(), acc) };
        }
        // SAFETY: the fourth column occupies bytes 112..128 of the slot.
        unsafe {
            vst1q_f32(
                record.add(112).cast::<f32>(),
                vld1q_f32([0_f32, 0., 0., 1.].as_ptr()),
            );
        }
        // SAFETY: the tail occupies bytes 128..144 of the same slot.
        unsafe { write_tail(record, light, layout) };
    }
}

/// Writes the four-word tail of a record: `[light, hidden, 0, 0]`.
///
/// # Safety
///
/// `record` must be valid for 144 writable bytes.
#[inline]
unsafe fn write_tail(record: *mut u8, light: i32, layout: PackedLayout) {
    // SAFETY: the tail occupies bytes 128..144 of the same slot.
    unsafe {
        match layout {
            PackedLayout::FloatTail => {
                std::ptr::write_unaligned(record.add(128).cast::<f32>(), light as f32);
                std::ptr::write_unaligned(record.add(132).cast::<f32>(), 0_f32);
                std::ptr::write_unaligned(record.add(136).cast::<f32>(), 0_f32);
                std::ptr::write_unaligned(record.add(140).cast::<f32>(), 0_f32);
            }
            PackedLayout::IntTail => {
                std::ptr::write_unaligned(record.add(128).cast::<i32>(), light);
                std::ptr::write_unaligned(record.add(132).cast::<i32>(), 0_i32);
                std::ptr::write_unaligned(record.add(136).cast::<i32>(), 0_i32);
                std::ptr::write_unaligned(record.add(140).cast::<i32>(), 0_i32);
            }
        }
    }
}

/// The parameters that feed a bone's local matrix, in the order they are compared.
const CACHED_PARAM_FLOATS: usize = 9;

/// How many output buffers one compiled skeleton remembers as holding correct records.
///
/// The host writes the same model into more than one buffer per frame — Blaze3D's SSBO and the
/// OpenGL GPU path each own one — so "some buffer is up to date" is not a usable predicate. Four
/// slots cover the two real callers plus room for a preview path, and the set is rebuilt in a few
/// frames after a parameter change, so it does not need to be larger.
const MAX_VALID_OUTPUTS: usize = 4;

/// Fills `dirty` and `subtree_dirty` and returns how many bones moved and how many were reached.
///
/// This is the cheap phase: it reads nine floats per bone and compares them bitwise against the
/// previous frame's parameters, then propagates the result down the tree in level order. It touches
/// no matrices and allocates nothing, so it can run on every frame including the ones that then
/// skip everything else.
///
/// The comparison is against `params_mirror` — the whole parameter array as it was last frame —
/// rather than against a record of what the cached `locals` were built from. That distinction is
/// what makes the switch between the two kernels clean: the mirror always describes the previous
/// frame, whichever kernel served it, so a bone is dirty exactly when its parameters really
/// changed. Keying the comparison on the cache's own provenance instead would mark every bone
/// dirty after an uncached frame and lock the rig onto the uncached path forever — the failure
/// that made an earlier attempt regress every scenario.
///
/// A bitwise comparison is deliberate. The cache is only valid when the inputs are literally the
/// same values, and an epsilon comparison would let a slow animation drift go unnoticed, leaving
/// a bone frozen at a stale matrix.
///
/// The two counts are returned separately because they answer different questions. `moved` is how
/// much trigonometry the cache can skip, which is what decides whether the cached kernel is worth
/// its bookkeeping. `reached` is how much composition either kernel must do, which is the same for
/// both. Only `moved` can distinguish "one limb is animating" from "the whole rig is animating" —
/// both reach every bone, but only the second has nothing for the cache to save.
fn detect_changes(
    skeleton: &CompiledSkeleton,
    params: &[f32],
    scratch: &mut Scratch,
    force_all: bool,
) -> (usize, usize) {
    let order = skeleton.level_order();
    let level_parents = skeleton.level_parents();
    let mut moved = 0;
    let mut reached = 0;
    for (slot, &bone) in order.iter().enumerate() {
        let bone = bone as usize;
        let p = bone * BONE_PARAM_STRIDE;
        let mirror = &scratch.params_mirror[p..p + CACHED_PARAM_FLOATS];
        let really_changed =
            (0..CACHED_PARAM_FLOATS).any(|k| params[p + k].to_bits() != mirror[k].to_bits());
        // `force_all` is how a stale cache is rebuilt: every bone is marked dirty so the refresh
        // rebuilds every local matrix. Folding it in here rather than filling the flag array first
        // keeps this to one pass over the rig — a separate fill measured at 4 µs for 773 bones,
        // which is most of a static frame.
        //
        // It deliberately does **not** inflate `moved`. That count decides which kernel to use, and
        // a stale cache is not evidence that the rig is animating: counting it would send the
        // frame after an uncached one back down the uncached path, which is exactly the lock-in
        // this design exists to avoid.
        let changed = force_all || really_changed;
        let parent = level_parents[slot];
        let inherited = parent >= 0 && scratch.subtree_dirty[parent as usize] != 0;
        let subtree_dirty = changed || inherited;
        scratch.dirty[bone] = u8::from(changed);
        scratch.subtree_dirty[bone] = u8::from(subtree_dirty);
        if really_changed {
            moved += 1;
        }
        if subtree_dirty {
            reached += 1;
        }
    }
    (moved, reached)
}

/// A local-rebuild kernel: recomputes `locals` for the bones marked `dirty`, in bone order.
///
/// Bone order rather than level order because a local matrix depends only on its own bone, so
/// there is no ordering constraint — and grouping by bone index gives the widest possible vector
/// groups with a single tail lane. Grouping by level instead would leave every level narrower than
/// eight bones on the scalar path, which on a real 25-level rig is most of them.
type RefreshKernel = unsafe fn(&CompiledSkeleton, &[f32], &mut Scratch) -> Result<(), Status>;

/// An uncached kernel: rebuilds every local matrix, composes every world matrix and writes every
/// packed record in one pass.
///
/// This is the "design A" shape. It shares no state with the cache, which is what makes switching
/// to it safe: it cannot leave a half-updated cache behind for the next frame to trust.
type UncachedKernel = unsafe fn(
    &CompiledSkeleton,
    &[f32],
    &mut Scratch,
    *mut u8,
    &PackedRequest<'_>,
) -> Result<(), Status>;

/// Resolves a tier to the uncached kernel that serves it.
///
/// Tiers without an 8-wide kernel fall back to the scalar walk and report `Scalar`, for the same
/// reason [`step_kernel`] does: the report must name the code that ran.
fn uncached_kernel(isa: Isa) -> (UncachedKernel, crate::KernelId) {
    use crate::KernelId;
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        match isa {
            Isa::Avx2 => return (x86::evaluate_avx2, KernelId::Avx2),
            Isa::Avx2Fma => return (x86::evaluate_fma, KernelId::Avx2Fma),
            #[cfg(target_arch = "x86_64")]
            Isa::Avx512 => return (x86::evaluate_avx512, KernelId::Avx2),
            _ => {}
        }
    }
    // No dedicated kernel exists on this architecture, so there is nothing for `isa` to select and
    // the portable walk serves every tier. That is also the tier reported, because naming the
    // requested tier would claim instructions that never ran.
    #[cfg(not(any(target_arch = "x86", target_arch = "x86_64")))]
    let _ = isa;
    (evaluate_scalar, KernelId::Scalar)
}

/// The portable uncached walk: rebuild, compose and write one bone at a time, in level order.
///
/// Level order is required here, unlike the cached path's bone-ordered rebuild: with no cache to
/// hold a finished local matrix, each bone's local must be built immediately before its world
/// matrix is composed, and the composition needs the parent's world matrix to be final.
///
/// # Safety
///
/// `scratch` must be sized for `skeleton.bone_count()` bones and `out` must be valid for
/// `bone_count * 144` writable bytes.
unsafe fn evaluate_scalar(
    skeleton: &CompiledSkeleton,
    params: &[f32],
    scratch: &mut Scratch,
    out: *mut u8,
    request: &PackedRequest<'_>,
) -> Result<(), Status> {
    let order = skeleton.level_order();
    let level_parents = skeleton.level_parents();
    let hidden = hidden_record(request.layout);
    let glow = skeleton.glow_mask();
    let root_cols = RootColumns::new(request.root);
    for (slot, &bone) in order.iter().enumerate() {
        let bone = bone as usize;
        // SAFETY: `refresh_one` re-derives everything from the bone's own parameters, and `bone`
        // and `slot` come from the skeleton's own level plan.
        unsafe {
            refresh_one(skeleton, params, scratch, bone)?;
            compose_one(skeleton, params, scratch, bone, level_parents[slot], slot)?;
        }
        // SAFETY: `bone < bone_count`, so this is this bone's 144-byte slot.
        let record = unsafe { out.add(bone * PACKED_BONE_STRIDE) };
        let pass_visible = match request.pass {
            BonePassFilter::All => true,
            BonePassFilter::NonGlow => glow[bone] == 0,
            BonePassFilter::Glow => glow[bone] != 0,
        };
        if scratch.visible[bone] == 0 || !pass_visible {
            // SAFETY: `record` is this bone's slot and `hidden` is exactly one record.
            unsafe {
                std::ptr::copy_nonoverlapping(hidden.as_ptr(), record, PACKED_BONE_STRIDE);
            }
            continue;
        }
        let light = if glow[bone] != 0 {
            request.full_bright_light
        } else {
            request.packed_light
        };
        // SAFETY: `record` is this bone's slot; the slices are this bone's matrices.
        unsafe {
            root_cols.write_record(
                record,
                &scratch.transforms[slot * 16..slot * 16 + 16],
                &scratch.normals[slot * 9..slot * 9 + 9],
                light,
                request.layout,
            );
        }
    }
    Ok(())
}

/// Resolves a tier to the local-rebuild kernel that serves it.
fn refresh_kernel(isa: Isa) -> (RefreshKernel, crate::KernelId) {
    use crate::KernelId;
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        match isa {
            Isa::Avx2 => return (x86::refresh_avx2, KernelId::Avx2),
            Isa::Avx2Fma => return (x86::refresh_fma, KernelId::Avx2Fma),
            #[cfg(target_arch = "x86_64")]
            Isa::Avx512 => return (x86::refresh_avx2, KernelId::Avx2),
            _ => {}
        }
    }
    // See `uncached_kernel`: on an architecture with no vector rebuild the portable one serves
    // every tier and is the tier reported.
    #[cfg(not(any(target_arch = "x86", target_arch = "x86_64")))]
    let _ = isa;
    (refresh_scalar, KernelId::Scalar)
}

/// The portable local rebuild: one bone at a time.
///
/// # Safety
///
/// `scratch` must be sized for `skeleton.bone_count()` bones.
unsafe fn refresh_scalar(
    skeleton: &CompiledSkeleton,
    params: &[f32],
    scratch: &mut Scratch,
) -> Result<(), Status> {
    for bone in 0..skeleton.bone_count() {
        if scratch.dirty[bone] == 0 {
            continue;
        }
        // SAFETY: `refresh_one` re-derives everything from the bone's own parameters, and the
        // caller sized `scratch` for this skeleton.
        unsafe { refresh_one(skeleton, params, scratch, bone)? };
    }
    Ok(())
}

/// Rebuilds one bone's cached local matrix from its parameters.
///
/// This is the single definition of the local-matrix arithmetic; the 8-wide rebuild and the
/// scalar walk both reduce to it for the cases they do not vectorise.
///
/// # Safety
///
/// `bone` must be a valid bone index and `scratch` must be sized for `skeleton.bone_count()` bones.
unsafe fn refresh_one(
    skeleton: &CompiledSkeleton,
    params: &[f32],
    scratch: &mut Scratch,
    bone: usize,
) -> Result<(), Status> {
    let p = bone * BONE_PARAM_STRIDE;
    let sx = params[p + 6];
    let sy = params[p + 7];
    let sz = params[p + 8];
    let base = bone * 16;
    if sx == 0. || sy == 0. || sz == 0. {
        // A zero scale makes the bone invisible; the matrix is never read, but it must not carry
        // a stale value either.
        scratch.locals[base..base + 16].fill(0.);
    } else {
        let rx = params[p];
        let ry = params[p + 1];
        let rz = params[p + 2];
        let dx = -params[p + 3] * 0.0625;
        let dy = params[p + 4] * 0.0625;
        let dz = params[p + 5] * 0.0625;
        let (sin_x, cos_x) = if rx == 0. { (0., 1.) } else { rx.sin_cos() };
        let (sin_y, cos_y) = if ry == 0. { (0., 1.) } else { ry.sin_cos() };
        let (sin_z, cos_z) = if rz == 0. { (0., 1.) } else { rz.sin_cos() };
        let local = local_linear([sin_x, sin_y, sin_z], [cos_x, cos_y, cos_z], [sx, sy, sz]);
        let piv_x = skeleton.pivots_x()[bone];
        let piv_y = skeleton.pivots_y()[bone];
        let piv_z = skeleton.pivots_z()[bone];
        let l03 = piv_x + dx - (local[0] * piv_x + local[3] * piv_y + local[6] * piv_z);
        let l13 = piv_y + dy - (local[1] * piv_x + local[4] * piv_y + local[7] * piv_z);
        let l23 = piv_z + dz - (local[2] * piv_x + local[5] * piv_y + local[8] * piv_z);
        let m = &mut scratch.locals[base..base + 16];
        m[0] = local[0];
        m[1] = local[1];
        m[2] = local[2];
        m[3] = 0.;
        m[4] = local[3];
        m[5] = local[4];
        m[6] = local[5];
        m[7] = 0.;
        m[8] = local[6];
        m[9] = local[7];
        m[10] = local[8];
        m[11] = 0.;
        m[12] = l03;
        m[13] = l13;
        m[14] = l23;
        m[15] = 1.;
        if !m.iter().all(|v| v.is_finite()) {
            return Err(Status::NonFinite);
        }
    }
    Ok(())
}

/// A step kernel: composes the affected world matrices from the local matrices.
///
/// The cached step is fused with the local rebuild into one level-ordered walk. Splitting them was
/// measurably worse on a fully-animated frame — the extra traversal of the parameter and local
/// buffers cost more than the cached trigonometry saved — so the cached path keeps the fused form.
///
/// The uncached path does **not** use this family: it has its own dedicated kernel
/// ([`evaluate_body`]) that rebuilds and composes in one pass without any cache bookkeeping. That
/// separation is deliberate; see [`evaluate_cached`] for why fusing the two into one function lost
/// 22% on a fully-animated frame.
type StepKernel = unsafe fn(&CompiledSkeleton, &[f32], &mut Scratch) -> Result<(), Status>;

/// Resolves a tier to the fused cached step kernel that serves it.
///
/// Tiers without an 8-wide kernel report `Scalar`, because that is the code they actually execute:
/// SSE2, AVX and NEON fall back to the scalar walk. Reporting the requested tier there would claim
/// instructions that never ran, which is exactly what the N13 rule forbids.
fn step_kernel(isa: Isa) -> (StepKernel, crate::KernelId) {
    use crate::KernelId;
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    {
        match isa {
            Isa::Avx2 => return (x86::step_avx2, KernelId::Avx2),
            Isa::Avx2Fma => return (x86::step_fma, KernelId::Avx2Fma),
            #[cfg(target_arch = "x86_64")]
            Isa::Avx512 => return (x86::step_avx512, KernelId::Avx2),
            _ => {}
        }
    }
    // See `uncached_kernel`: the portable fused walk serves every tier on an architecture without
    // a vector kernel, and reports itself rather than the requested tier.
    #[cfg(not(any(target_arch = "x86", target_arch = "x86_64")))]
    let _ = isa;
    (step_scalar, KernelId::Scalar)
}

/// The tier whose instructions the step kernel for `isa` actually executes.
fn step_tier(isa: Isa) -> Isa {
    step_kernel(isa).1.tier()
}

/// The portable fused walk, and the fallback for every tier without a vector kernel.
///
/// Walking `level_order` means a parent's local matrix and world matrix are both final before any
/// child is visited, which is what lets the two stages share one loop.
///
/// # Safety
///
/// `scratch` must be sized for `skeleton.bone_count()` bones. That holds because the only caller
/// ran `Scratch::ensure` first.
unsafe fn step_scalar(
    skeleton: &CompiledSkeleton,
    params: &[f32],
    scratch: &mut Scratch,
) -> Result<(), Status> {
    let order = skeleton.level_order();
    let level_parents = skeleton.level_parents();
    for (slot, &bone) in order.iter().enumerate() {
        let bone = bone as usize;
        if scratch.dirty[bone] != 0 {
            // SAFETY: `refresh_one` re-derives everything from the bone's own parameters.
            unsafe { refresh_one(skeleton, params, scratch, bone)? };
        }
        if scratch.subtree_dirty[bone] != 0 {
            // SAFETY: `bone` and `slot` come from the skeleton's own level plan.
            unsafe { compose_one(skeleton, params, scratch, bone, level_parents[slot], slot)? };
        }
    }
    Ok(())
}

/// Composes one bone's world matrix and normal from its cached local matrix.
///
/// This is the single definition of the composition arithmetic. The scalar walk, the eight-wide
/// level tail and the vector body all end here, so the operation order cannot drift between them.
///
/// # Safety
///
/// `bone` and `slot` must come from the skeleton's level plan, and `scratch` must be sized for
/// `skeleton.bone_count()` bones.
unsafe fn compose_one(
    skeleton: &CompiledSkeleton,
    params: &[f32],
    scratch: &mut Scratch,
    bone: usize,
    parent: i32,
    slot: usize,
) -> Result<(), Status> {
    // SAFETY: the caller guarantees `bone` is a valid index into the cache.
    let local = unsafe { *scratch.locals.as_ptr().add(bone * 16).cast::<[f32; 16]>() };
    // SAFETY: as above; `compose_one_with` re-derives everything from `local` and the parameters.
    unsafe { compose_one_with(skeleton, params, scratch, bone, parent, slot, &local) }
}

/// Composes one bone's world matrix and normal from a local matrix supplied by the caller.
///
/// Splitting this out of [`compose_one`] lets the uncached kernel pass the local matrix it just
/// built in registers instead of storing it to the cache and reading it straight back. The store
/// is a 64-byte write per bone that nothing on that path would read.
///
/// # Safety
///
/// `bone` and `slot` must come from the skeleton's level plan, and `scratch` must be sized for
/// `skeleton.bone_count()` bones.
unsafe fn compose_one_with(
    skeleton: &CompiledSkeleton,
    params: &[f32],
    scratch: &mut Scratch,
    bone: usize,
    parent: i32,
    slot: usize,
    local: &[f32; 16],
) -> Result<(), Status> {
    let level_position = skeleton.level_position();
    let p = bone * BONE_PARAM_STRIDE;
    let sx = params[p + 6];
    let sy = params[p + 7];
    let sz = params[p + 8];
    let m_off = slot * 16;
    let n_off = slot * 9;
    scratch.visible[bone] = 0;
    scratch.descendants[bone] = 0;
    scratch.uniform_scale[bone] = 0.;
    scratch.locator_mask[bone] = 0;
    let parent_hidden = parent >= 0 && scratch.descendants[parent as usize] == 0;
    if parent_hidden || sx == 0. || sy == 0. || sz == 0. {
        scratch.transforms[m_off..m_off + 16].fill(0.);
        scratch.normals[n_off..n_off + 9].fill(0.);
        return Ok(());
    }
    let w = if parent < 0 {
        [
            local[0], local[1], local[2], local[4], local[5], local[6], local[8], local[9],
            local[10], local[12], local[13], local[14],
        ]
    } else {
        let po = level_position[parent as usize] as usize * 16;
        let t = &scratch.transforms[po..po + 16];
        let (p00, p10, p20) = (t[0], t[1], t[2]);
        let (p01, p11, p21) = (t[4], t[5], t[6]);
        let (p02, p12, p22) = (t[8], t[9], t[10]);
        let (p03, p13, p23) = (t[12], t[13], t[14]);
        [
            p00 * local[0] + p01 * local[1] + p02 * local[2],
            p10 * local[0] + p11 * local[1] + p12 * local[2],
            p20 * local[0] + p21 * local[1] + p22 * local[2],
            p00 * local[4] + p01 * local[5] + p02 * local[6],
            p10 * local[4] + p11 * local[5] + p12 * local[6],
            p20 * local[4] + p21 * local[5] + p22 * local[6],
            p00 * local[8] + p01 * local[9] + p02 * local[10],
            p10 * local[8] + p11 * local[9] + p12 * local[10],
            p20 * local[8] + p21 * local[9] + p22 * local[10],
            p00 * local[12] + p01 * local[13] + p02 * local[14] + p03,
            p10 * local[12] + p11 * local[13] + p12 * local[14] + p13,
            p20 * local[12] + p21 * local[13] + p22 * local[14] + p23,
        ]
    };
    if !w.iter().all(|v| v.is_finite()) {
        return Err(Status::NonFinite);
    }
    let m = &mut scratch.transforms[m_off..m_off + 16];
    m[0] = w[0];
    m[1] = w[1];
    m[2] = w[2];
    m[3] = 0.;
    m[4] = w[3];
    m[5] = w[4];
    m[6] = w[5];
    m[7] = 0.;
    m[8] = w[6];
    m[9] = w[7];
    m[10] = w[8];
    m[11] = 0.;
    m[12] = w[9];
    m[13] = w[10];
    m[14] = w[11];
    m[15] = 1.;

    let uniform_local = sx == sy && sy == sz;
    let mut world_uniform = 0_f32;
    if uniform_local {
        let base = if parent < 0 {
            1.
        } else {
            scratch.uniform_scale[parent as usize]
        };
        if base != 0. {
            let candidate = base * sx;
            let abs = candidate.abs();
            if candidate.is_finite() && (1e-6..=1e6).contains(&abs) {
                world_uniform = candidate;
            }
        }
    }
    scratch.uniform_scale[bone] = world_uniform;

    if world_uniform == 1. || world_uniform == -1. {
        scratch.normals[n_off..n_off + 9].copy_from_slice(&w[..9]);
    } else if world_uniform != 0. {
        let inv = 1. / (world_uniform * world_uniform);
        for (value_out, value_in) in scratch.normals[n_off..n_off + 9].iter_mut().zip(&w[..9]) {
            *value_out = value_in * inv;
        }
    } else {
        // Non-uniform scale: the inverse-transpose is the cofactor matrix over the determinant.
        let (a, b, c) = (w[0], w[3], w[6]);
        let (d, e, f) = (w[1], w[4], w[7]);
        let (g, h, i) = (w[2], w[5], w[8]);
        let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if det == 0. || !det.is_finite() {
            return Err(Status::InvalidInput);
        }
        let inv_det = 1. / det;
        let values = [
            (e * i - f * h) * inv_det,
            (c * h - b * i) * inv_det,
            (b * f - c * e) * inv_det,
            (f * g - d * i) * inv_det,
            (a * i - c * g) * inv_det,
            (c * d - a * f) * inv_det,
            (d * h - e * g) * inv_det,
            (b * g - a * h) * inv_det,
            (a * e - b * d) * inv_det,
        ];
        if values.iter().any(|v| !v.is_finite()) {
            return Err(Status::NonFinite);
        }
        scratch.normals[n_off..n_off + 9].copy_from_slice(&values);
    }

    let own_visible = params[p + 9] == 0.;
    scratch.visible[bone] = u8::from(own_visible);
    scratch.descendants[bone] = u8::from(params[p + 10] == 0.);
    if own_visible && params[p + 11] == 1. {
        let piv_x = skeleton.pivots_x()[bone];
        let piv_y = skeleton.pivots_y()[bone];
        let piv_z = skeleton.pivots_z()[bone];
        let q = bone * 4;
        scratch.locators[q] = -(w[9] + w[0] * piv_x + w[3] * piv_y + w[6] * piv_z) * 16.;
        scratch.locators[q + 1] = (w[10] + w[1] * piv_x + w[4] * piv_y + w[7] * piv_z) * 16.;
        scratch.locators[q + 2] = (w[11] + w[2] * piv_x + w[5] * piv_y + w[8] * piv_z) * 16.;
        scratch.locator_mask[bone] = 1;
    }
    Ok(())
}

/// Writes the packed records for the bones whose record can have changed.
///
/// # Safety
///
/// `out` must be valid for `skeleton.bone_count() * 144` writable bytes and `scratch` must hold
/// data for exactly that many bones.
unsafe fn write_records(
    skeleton: &CompiledSkeleton,
    request: &PackedRequest<'_>,
    scratch: &Scratch,
    out: *mut u8,
    only_dirty: bool,
) {
    let n = skeleton.bone_count();
    let hidden = hidden_record(request.layout);
    let glow = skeleton.glow_mask();
    let position = skeleton.level_position();
    let root = RootColumns::new(request.root);
    for bone in 0..n {
        if only_dirty && scratch.subtree_dirty[bone] == 0 {
            continue;
        }
        // SAFETY: `bone < n`, and the caller guaranteed `n * 144` writable bytes at `out`.
        let record = unsafe { out.add(bone * PACKED_BONE_STRIDE) };
        let pass_visible = match request.pass {
            BonePassFilter::All => true,
            BonePassFilter::NonGlow => glow[bone] == 0,
            BonePassFilter::Glow => glow[bone] != 0,
        };
        if scratch.visible[bone] == 0 || !pass_visible {
            // SAFETY: `record` is this bone's 144-byte slot and `hidden` is exactly 144 bytes.
            unsafe {
                std::ptr::copy_nonoverlapping(hidden.as_ptr(), record, PACKED_BONE_STRIDE);
            }
            continue;
        }
        let light = if glow[bone] != 0 {
            request.full_bright_light
        } else {
            request.packed_light
        };
        let slot = position[bone] as usize;
        // SAFETY: `slot` is a valid level position, so both slices are in range.
        unsafe {
            root.write_record(
                record,
                &scratch.transforms[slot * 16..slot * 16 + 16],
                &scratch.normals[slot * 9..slot * 9 + 9],
                light,
                request.layout,
            );
        }
    }
}

/// The fraction of bones that must have *moved* before the uncached kernel is the cheaper choice.
///
/// This is a measured crossover, not a guess. The cached kernel's advantage is the trigonometry it
/// skips; its cost is one 16-float local store per dirty bone plus the branch pressure that
/// bookkeeping puts on the hot loop. Once nearly every bone has moved there is no trigonometry
/// left to skip, so only the cost remains.
///
/// The signal is the fraction that moved, **not** the fraction whose world matrix must be
/// recomputed. A single animating limb drags its whole subtree dirty, so the recompute fraction is
/// near 100% for almost every real animation — it cannot tell "one limb" from "everything", and
/// keying on it would send every frame down the uncached path. The moved fraction can.
///
/// Measured on the real rigs (`design_c_crossover_on_real_rigs`), the two kernels cross between
/// 20% and 50% moved: at 20% the cached kernel is still ahead by 0.4–2.3 µs, and by 50% the
/// uncached kernel is ahead by 1.1–1.8 µs. The threshold sits at the point where the uncached
/// kernel starts winning on both rigs rather than at the midpoint of the bracket, because a frame
/// that is genuinely half-animated is the case the uncached kernel was built for and the margin
/// there is small either way.
const UNCACHED_MOVED_RATIO: f32 = 0.5;

/// Chooses the kernel for a frame from how much of the rig actually moved.
fn prefer_uncached(moved: usize, bones: usize) -> bool {
    if bones == 0 {
        return false;
    }
    moved as f32 > bones as f32 * UNCACHED_MOVED_RATIO
}

/// Runs the four phases: detect, refresh, compose, write.
///
/// The phases are separate, rather than fused, because that is what lets a static frame stop after
/// the first one. Detection costs a couple of microseconds and touches no matrices; everything
/// after it costs the rest. Fusing them would force every frame to pay for the output rewrite even
/// when not one byte of it could have changed.
///
/// # Why there are two kernels
///
/// The cached kernel is the right shape while few bones move, and the wrong shape when most do: it
/// carries cache maintenance (a conditional lane load, a local store and a dirty-flag scan) that a
/// fully-animated frame gets nothing back for. An earlier attempt to fuse the uncached data flow
/// into it made a fully-animated frame 22% slower, because the bookkeeping stayed in the hot loop
/// even when every branch was taken the same way.
///
/// So the uncached path is a separate kernel with its own body. The switch between them is clean
/// because the two share no state that can go stale: the uncached kernel rebuilds every local
/// matrix, so `locals` is correct after it runs, and dirtiness is always derived from
/// `params_mirror` — the previous frame's parameters — rather than from anything the cache
/// recorded. The earlier attempt instead filled a cache-provenance array with `NaN` while leaving
/// `locals` populated, so the following sparse frame saw every bone as dirty, took the uncached
/// path again, and every scenario regressed.
///
/// # Safety
///
/// `out` must be valid for `skeleton.bone_count() * 144` writable bytes and `scratch` must be
/// sized for that many bones. Both hold at the only call site.
unsafe fn evaluate_cached(
    skeleton: &CompiledSkeleton,
    params: &[f32],
    request: &PackedRequest<'_>,
    scratch: &mut Scratch,
    out: *mut u8,
    isa: Isa,
) -> Result<PackedPath, Status> {
    // An input that shapes every record changed, so no buffer can still be current.
    let settings_changed = !scratch.frame_valid
        || scratch.cached_pass != request.pass as u8
        || scratch.cached_light != request.packed_light
        || scratch.cached_full_bright != request.full_bright_light
        || scratch.cached_layout != request.layout as u8;
    if settings_changed {
        scratch.invalidate_outputs();
    }
    let out_addr = out as usize;
    // A full rewrite is needed when the caller cannot guarantee *this* buffer still holds last
    // frame's records, or when an input that shapes every record changed. "This buffer" is the
    // important part: the host alternates between two output buffers for the same model, so a
    // blanket "the records are current" flag would let the second buffer keep older records.
    let full_write =
        !request.persistent_output || settings_changed || !scratch.output_is_valid(out_addr);

    // Fast path: if the whole parameter array is bit-identical to last frame's, nothing can have
    // changed, so the per-bone scan is unnecessary. This one contiguous comparison replaces a
    // strided nine-of-twelve scan over every bone, which measured at 16 µs for 773 bones — the
    // entire cost of a static frame.
    //
    // The comparison runs over bytes rather than `f32`s because slice equality on floats is
    // element-wise and cannot use `memcmp`: the compiler cannot see that these values are never
    // NaN, so it has to respect `NaN != NaN`. Comparing bytes is both faster and stricter, which
    // is the right direction — a differing sign of zero simply causes a recompute.
    let param_floats = skeleton.param_floats().ok_or(Status::InvalidInput)?;
    let param_bytes = param_floats.checked_mul(4).ok_or(Status::InvalidInput)?;
    // SAFETY: both slices are exactly `param_bytes` long and the reads stay inside them.
    let current = unsafe { std::slice::from_raw_parts(params.as_ptr().cast::<u8>(), param_bytes) };
    // SAFETY: as above, over the mirror buffer.
    let previous = unsafe {
        std::slice::from_raw_parts(scratch.params_mirror.as_ptr().cast::<u8>(), param_bytes)
    };
    // A pinned path means the caller asked for that kernel specifically, so neither shortcut below
    // may answer for it. Without that, a pinned request would silently report the automatic choice
    // and a benchmark comparing the two kernels would be comparing nothing.
    let pinned = request.path.is_some();
    if current == previous && !pinned {
        if !full_write {
            // Nothing moved and the buffer is still valid: the frame is already correct.
            //
            // This holds even when the local-matrix cache is stale. Staleness only means `locals`
            // does not describe the current parameters; it says nothing about the output buffer,
            // which the previous frame filled correctly. The flag is deliberately left set, so the
            // next frame that does need the cache rebuilds it then instead of paying for it here.
            return Ok(PackedPath::Cached);
        }
        scratch.cached_pass = request.pass as u8;
        scratch.cached_light = request.packed_light;
        scratch.cached_full_bright = request.full_bright_light;
        scratch.cached_layout = request.layout as u8;
        scratch.frame_valid = true;
        // The matrices are unchanged, but the records depend on the pass and light, so they must
        // be rewritten. Nothing needs recomposing.
        // SAFETY: `out` is valid for the whole buffer and `scratch` holds `n` bones of data.
        unsafe { write_records(skeleton, request, scratch, out, false) };
        scratch.mark_output(out_addr);
        return Ok(PackedPath::Cached);
    }

    // The parameters changed, so every buffer except this one now holds records for an older frame.
    // This buffer is repaired below by the dirty-only write, so keeping it valid is what lets an
    // animated frame rewrite only the bones that actually moved.
    scratch.invalidate_other_outputs(out_addr);

    let n = skeleton.bone_count();
    // A stale cache means every local matrix must be rebuilt, because the uncached kernel does not
    // maintain `locals` at all. It is read and cleared here, where the rebuild actually happens.
    let force_all = scratch.locals_stale;
    scratch.locals_stale = false;
    let (moved, reached) = detect_changes(skeleton, params, scratch, force_all);
    scratch.moved = moved;
    scratch.reached = reached;
    scratch.params_mirror[..param_floats].copy_from_slice(&params[..param_floats]);
    if reached == 0 && !full_write && !pinned {
        return Ok(PackedPath::Cached);
    }
    scratch.cached_pass = request.pass as u8;
    scratch.cached_light = request.packed_light;
    scratch.cached_full_bright = request.full_bright_light;
    scratch.cached_layout = request.layout as u8;
    scratch.frame_valid = true;

    let path = if request.path == Some(PackedPath::Uncached)
        || (request.path.is_none() && prefer_uncached(moved, n))
    {
        // The uncached kernel rebuilds every local matrix and every world matrix in one pass and
        // does not maintain the cache. It is the cheaper shape once most of the rig is dirty,
        // because there is no trigonometry left for the cache to skip and only its bookkeeping
        // would remain.
        let (run, _) = uncached_kernel(isa);
        // SAFETY: `scratch` is sized for `n` bones, `out` holds `n * 144` bytes, and the kernel
        // only touches those buffers.
        unsafe { run(skeleton, params, scratch, out, request)? };
        // `locals` now describes this frame only for the bones the kernel happened to visit in a
        // vector group; the scalar tail and the zero-scale lanes leave it as it was. Rather than
        // reason about which entries survived, the whole cache is declared stale: the next cached
        // frame rebuilds it once. That is cheaper than maintaining it here, and it cannot feed
        // back into the choice because dirtiness comes from `params_mirror`.
        scratch.locals_stale = true;
        PackedPath::Uncached
    } else {
        // Rebuild the local matrices first, in bone order rather than level order.
        //
        // This looks like the pass the fusion was supposed to remove, and for wide levels it is
        // redundant — but real rigs are 25 levels deep with a mean width under 30, so many levels
        // are narrower than eight bones. Fusing the rebuild into the level walk therefore pushed
        // most of the trigonometry onto the scalar tail, which measured at 78 µs for a 773-bone
        // fully-animated rig against 61 µs when the rebuild ran once over bone order with a single
        // tail. The rebuild has no parent dependency, so nothing forces it into the level walk.
        //
        // A stale cache means every local matrix must be rebuilt, because the uncached kernel
        // does not maintain `locals` at all. `detect_changes` already marked every bone dirty for
        // that case, so the refresh below rebuilds all of them.
        let (refresh, _) = refresh_kernel(isa);
        // SAFETY: `scratch` is sized for `n` bones and the kernel only touches those buffers.
        unsafe { refresh(skeleton, params, scratch)? };
        // The locals are now final, so the composition below must not rebuild them.
        scratch.dirty[..n].fill(0);
        let (run, _) = step_kernel(isa);
        // SAFETY: as above; the kernel only touches buffers sized for this skeleton.
        unsafe { run(skeleton, params, scratch)? };
        PackedPath::Cached
    };
    if path == PackedPath::Cached {
        // SAFETY: as above; `out` is valid for the whole buffer.
        unsafe { write_records(skeleton, request, scratch, out, !full_write) };
    }
    // The uncached kernel already wrote every record while its matrices were in registers, so
    // there is nothing left to emit here. Either way this buffer now holds this frame's records.
    scratch.mark_output(out_addr);
    Ok(path)
}

/// The fraction of bones that moved in the most recent evaluation on this thread.
///
/// `None` before any evaluation has run on this thread. This exists so a caller can see the
/// quantity the kernel switch keys on without re-deriving it: a second implementation of the
/// change detection could disagree with the kernel, and then a benchmark would be placing the
/// crossover against a number the kernel never used.
pub fn last_moved_ratio() -> Option<f32> {
    SCRATCH.with(|cell| {
        let scratch = cell.borrow();
        if scratch.cached_bones == 0 {
            return None;
        }
        Some(scratch.moved as f32 / scratch.cached_bones as f32)
    })
}

/// The fraction of bones whose world matrix the most recent evaluation had to recompute.
///
/// Always at least [`last_moved_ratio`], and usually much larger: a bone that moves drags its whole
/// subtree with it. Reported separately because the two answer different questions — this one is
/// the work both kernels share, the other is the trigonometry only the cache can skip.
pub fn last_reached_ratio() -> Option<f32> {
    SCRATCH.with(|cell| {
        let scratch = cell.borrow();
        if scratch.cached_bones == 0 {
            return None;
        }
        Some(scratch.reached as f32 / scratch.cached_bones as f32)
    })
}

/// The tier this module reports for `isa` without needing a batch.
pub fn executed(isa: Isa) -> Isa {
    step_tier(isa)
}

/// The fastest tier this host can run for the packed path.
///
/// This is deliberately **not** the same order [`crate::select`] uses. `select` is shared with the
/// reference kernels and prefers FMA, which is right for them: they accumulate in `f64` and the
/// fused form is both faster and more accurate there. The packed path is different — it is
/// `f32`-only and its hot loop is already register-tight, so enabling FMA there was measured
/// *slower* than plain AVX2 on the real rigs. `Auto` therefore resolves to AVX2 on x86-64, and FMA
/// remains available to a caller that asks for it explicitly.
pub fn preferred_isa() -> Isa {
    for candidate in [Isa::Avx2, Isa::Avx, Isa::Sse2, Isa::Neon, Isa::Scalar] {
        if crate::supported(candidate) {
            return candidate;
        }
    }
    Isa::Scalar
}

/// Identity of the kernel that serves `isa`; see `crate::KernelId`.
pub fn kernel_id(isa: Isa) -> crate::KernelId {
    step_kernel(isa).1
}

/// The column-major 3x3 linear part of a local matrix: `(Z * Y * X) * diag(sx, sy, sz)`.
#[inline]
fn local_linear(sin: [f32; 3], cos: [f32; 3], scale: [f32; 3]) -> [f32; 9] {
    let (sin_x, sin_y, sin_z) = (sin[0], sin[1], sin[2]);
    let (cos_x, cos_y, cos_z) = (cos[0], cos[1], cos[2]);
    let (sx, sy, sz) = (scale[0], scale[1], scale[2]);
    [
        cos_z * cos_y * sx,
        sin_z * cos_y * sx,
        -sin_y * sx,
        (cos_z * sin_y * sin_x - sin_z * cos_x) * sy,
        (sin_z * sin_y * sin_x + cos_z * cos_x) * sy,
        cos_y * sin_x * sy,
        (cos_z * sin_y * cos_x + sin_z * sin_x) * sz,
        (sin_z * sin_y * cos_x - cos_z * sin_x) * sz,
        cos_y * cos_x * sz,
    ]
}

#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
mod x86 {
    #[cfg(target_arch = "x86")]
    use std::arch::x86::*;
    #[cfg(target_arch = "x86_64")]
    use std::arch::x86_64::*;

    use super::*;

    /// `a - b * c`, fused when the caller's tier has FMA.
    ///
    /// The `$fma` argument is always a literal `true` or `false` at the expansion site, so the
    /// branch is resolved during macro expansion and only one intrinsic survives. That matters:
    /// `_mm256_fmadd_ps` needs the `fma` target feature enabled on the enclosing function, and a
    /// branch the compiler cannot fold leaves the intrinsic as a real `callq` into `core_arch`.
    macro_rules! sub_mul {
        ($fma:expr, $a:expr, $b:expr, $c:expr) => {{
            #[allow(unused_unsafe)]
            // SAFETY: the caller is the FMA kernel, whose `#[target_feature]` includes `fma`.
            let fused = unsafe { _mm256_fnmadd_ps($b, $c, $a) };
            if $fma {
                fused
            } else {
                _mm256_sub_ps($a, _mm256_mul_ps($b, $c))
            }
        }};
    }

    /// `a * b + c`, fused when the caller's tier has FMA.
    macro_rules! add_mul {
        ($fma:expr, $a:expr, $b:expr, $c:expr) => {{
            #[allow(unused_unsafe)]
            // SAFETY: the caller is the FMA kernel, whose `#[target_feature]` includes `fma`.
            let fused = unsafe { _mm256_fmadd_ps($a, $b, $c) };
            if $fma {
                fused
            } else {
                _mm256_add_ps(_mm256_mul_ps($a, $b), $c)
            }
        }};
    }

    /// `a + b + c`, fused when the caller's tier has FMA.
    macro_rules! add3 {
        ($fma:expr, $a:expr, $b:expr, $c:expr) => {{
            #[allow(unused_unsafe)]
            // SAFETY: the caller is the FMA kernel, whose `#[target_feature]` includes `fma`.
            let fused = unsafe { _mm256_fmadd_ps(_mm256_set1_ps(1.), $c, _mm256_add_ps($a, $b)) };
            if $fma {
                fused
            } else {
                _mm256_add_ps(_mm256_add_ps($a, $b), $c)
            }
        }};
    }

    /// `a + b + c + d`, fused when the caller's tier has FMA.
    macro_rules! add4 {
        ($fma:expr, $a:expr, $b:expr, $c:expr, $d:expr) => {{
            #[allow(unused_unsafe)]
            // SAFETY: the caller is the FMA kernel, whose `#[target_feature]` includes `fma`.
            let fused = unsafe {
                _mm256_fmadd_ps(
                    _mm256_set1_ps(1.),
                    $d,
                    _mm256_add_ps(_mm256_add_ps($a, $b), $c),
                )
            };
            if $fma {
                fused
            } else {
                _mm256_add_ps(_mm256_add_ps(_mm256_add_ps($a, $b), $c), $d)
            }
        }};
    }

    /// Eight angles at once, returning `(sin, cos)` per lane.
    ///
    /// Range reduction is Cody-Waite with a three-part `pi/2` split, then Taylor polynomials on
    /// the reduced argument. For `|r| <= pi/4` the truncation error is below `2e-9` for `sin` and
    /// `3e-8` for `cos`, far inside the `1e-4` budget. Lanes outside `|x| <= 8192` radians fall
    /// back to the platform `sin_cos`, so an extreme input is slow but never wrong.
    #[inline]
    #[target_feature(enable = "avx2")]
    unsafe fn sin_cos8<const FMA: bool>(angles: __m256) -> (__m256, __m256) {
        // `pi/2` split into three `f32` parts. `PI_2_HI` is the correctly rounded `f32` value of
        // `pi/2`; the two corrections carry the bits it dropped. The signs matter: `pi/2` is
        // slightly *larger* than `PI_2_HI`, so both corrections are negative.
        const PI_2_HI: f32 = std::f32::consts::FRAC_PI_2;
        const PI_2_MID: f32 = -4.371_139e-8;
        const PI_2_LO: f32 = -1.715_124_5e-15;
        let sign_mask = _mm256_set1_ps(-0.0);
        let abs = _mm256_andnot_ps(sign_mask, angles);
        let in_range = _mm256_cmp_ps::<{ _CMP_LE_OQ }>(abs, _mm256_set1_ps(8192.0));
        if _mm256_movemask_ps(in_range) != 0xFF {
            // SAFETY: this function's `#[target_feature]` includes `avx2`, which is all the slow
            // path needs.
            return unsafe { sin_cos8_slow(angles) };
        }
        // q = round(x * 2/pi), then r = x - q * (pi/2) in three parts for accuracy.
        let q = _mm256_round_ps::<{ _MM_FROUND_TO_NEAREST_INT | _MM_FROUND_NO_EXC }>(
            _mm256_mul_ps(angles, _mm256_set1_ps(std::f32::consts::FRAC_2_PI)),
        );
        let mut r = sub_mul!(FMA, angles, q, _mm256_set1_ps(PI_2_HI));
        r = sub_mul!(FMA, r, q, _mm256_set1_ps(PI_2_MID));
        r = sub_mul!(FMA, r, q, _mm256_set1_ps(PI_2_LO));

        let r2 = _mm256_mul_ps(r, r);
        // sin(r) = r + r^3 * (s1 + r^2*(s2 + r^2*(s3 + r^2*s4))), Taylor coefficients.
        let mut sin_poly = _mm256_set1_ps(1.0 / 362_880.0);
        sin_poly = add_mul!(FMA, sin_poly, r2, _mm256_set1_ps(-1.0 / 5_040.0));
        sin_poly = add_mul!(FMA, sin_poly, r2, _mm256_set1_ps(1.0 / 120.0));
        sin_poly = add_mul!(FMA, sin_poly, r2, _mm256_set1_ps(-1.0 / 6.0));
        let sin_r = add_mul!(FMA, _mm256_mul_ps(r2, r), sin_poly, r);

        // cos(r) = 1 + r^2 * (c1 + r^2*(c2 + r^2*(c3 + r^2*c4))), Taylor coefficients.
        let mut cos_poly = _mm256_set1_ps(1.0 / 40_320.0);
        cos_poly = add_mul!(FMA, cos_poly, r2, _mm256_set1_ps(-1.0 / 720.0));
        cos_poly = add_mul!(FMA, cos_poly, r2, _mm256_set1_ps(1.0 / 24.0));
        cos_poly = add_mul!(FMA, cos_poly, r2, _mm256_set1_ps(-0.5));
        let cos_r = add_mul!(FMA, r2, cos_poly, _mm256_set1_ps(1.0));

        // Quadrant selection, with k = q mod 4:
        //   sin(x) = [+sin_r, +cos_r, -sin_r, -cos_r]
        //   cos(x) = [+cos_r, -sin_r, -cos_r, +sin_r]
        let quadrant = _mm256_and_si256(_mm256_cvtps_epi32(q), _mm256_set1_epi32(3));
        let swap = _mm256_castsi256_ps(_mm256_slli_epi32(
            _mm256_and_si256(quadrant, _mm256_set1_epi32(1)),
            31,
        ));
        let sin_base = _mm256_blendv_ps(sin_r, cos_r, swap);
        let cos_base = _mm256_blendv_ps(cos_r, sin_r, swap);
        // sin is negated for k >= 2, which is bit 1 of k.
        let sin_neg = _mm256_castsi256_ps(_mm256_slli_epi32(
            _mm256_and_si256(_mm256_srli_epi32(quadrant, 1), _mm256_set1_epi32(1)),
            31,
        ));
        // cos is negated for k == 1 and k == 2, which is bit 0 of ((k + 1) >> 1).
        let cos_neg = _mm256_castsi256_ps(_mm256_slli_epi32(
            _mm256_and_si256(
                _mm256_srli_epi32(_mm256_add_epi32(quadrant, _mm256_set1_epi32(1)), 1),
                _mm256_set1_epi32(1),
            ),
            31,
        ));
        (
            _mm256_xor_ps(sin_base, sin_neg),
            _mm256_xor_ps(cos_base, cos_neg),
        )
    }

    /// The out-of-range fallback for [`sin_cos8`], kept out of line.
    ///
    /// `#[inline(never)]` matters: when this body was inlined into `sin_cos8`, the eight
    /// `sincosf` calls and their spills bloated the hot loop enough to cost several microseconds
    /// per frame, even though the branch is never taken for real rotations.
    #[inline(never)]
    #[target_feature(enable = "avx2")]
    unsafe fn sin_cos8_slow(angles: __m256) -> (__m256, __m256) {
        let mut lanes = [0_f32; 8];
        let mut sin_lanes = [0_f32; 8];
        let mut cos_lanes = [0_f32; 8];
        _mm256_storeu_ps(lanes.as_mut_ptr(), angles);
        for lane in 0..8 {
            let (s, c) = lanes[lane].sin_cos();
            sin_lanes[lane] = s;
            cos_lanes[lane] = c;
        }
        (
            _mm256_loadu_ps(sin_lanes.as_ptr()),
            _mm256_loadu_ps(cos_lanes.as_ptr()),
        )
    }

    /// The fused eight-wide step: rebuild the dirty local matrices and compose the affected world
    /// matrices in one level-ordered walk.
    ///
    /// # Why one walk instead of two
    ///
    /// The first implementation rebuilt locals in a separate pass over bone order, then composed in
    /// a level-ordered pass. On a fully-animated frame that was ~20% slower than the uncached
    /// kernel: it walked the parameter and local buffers twice and paid a full extra sweep of cache
    /// misses, which more than cancelled the trigonometry the cache saved. Fusing them means a
    /// group of eight siblings reads its parameters once, builds its locals once, and composes them
    /// immediately while they are still hot. Level order is what makes this legal — a parent's
    /// world matrix is final before any child in a later level is visited.
    ///
    /// A group in which no bone is subtree-dirty is skipped whole, which is what keeps a static
    /// frame cheap.
    ///
    /// This is the cached kernel only. The fully-dirty case has its own body in
    /// [`evaluate_body`], because carrying this function's cache maintenance through a frame that
    /// cannot benefit from it measured 22% slower than the dedicated kernel.
    #[inline]
    #[target_feature(enable = "avx2")]
    unsafe fn step_body<const FMA: bool>(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
    ) -> Result<(), Status> {
        let order = skeleton.level_order();
        let level_parents = skeleton.level_parents();
        let level_position = skeleton.level_position();
        for level in skeleton.levels() {
            let mut slot = level.start;
            while slot + 8 <= level.end() {
                let mut any_dirty = false;
                let mut any_subtree = false;
                for lane in 0..8 {
                    let bone = order[slot + lane] as usize;
                    any_dirty |= scratch.dirty[bone] != 0;
                    any_subtree |= scratch.subtree_dirty[bone] != 0;
                }
                // `dirty` implies `subtree_dirty`, so this single check covers both.
                if !any_subtree {
                    slot += 8;
                    continue;
                }

                // ---- gather this group's parameters ----
                let mut rx = [0_f32; 8];
                let mut ry = [0_f32; 8];
                let mut rz = [0_f32; 8];
                let mut vdx = [0_f32; 8];
                let mut vdy = [0_f32; 8];
                let mut vdz = [0_f32; 8];
                let mut sx = [0_f32; 8];
                let mut sy = [0_f32; 8];
                let mut sz = [0_f32; 8];
                let mut piv_x = [0_f32; 8];
                let mut piv_y = [0_f32; 8];
                let mut piv_z = [0_f32; 8];
                let mut bone_lane = [0_usize; 8];
                let mut parent_lane = [0_i32; 8];
                let mut any_rotation = false;
                for lane in 0..8 {
                    let bone = order[slot + lane] as usize;
                    bone_lane[lane] = bone;
                    parent_lane[lane] = level_parents[slot + lane];
                    let p = bone * BONE_PARAM_STRIDE;
                    rx[lane] = params[p];
                    ry[lane] = params[p + 1];
                    rz[lane] = params[p + 2];
                    vdx[lane] = -params[p + 3] * 0.0625;
                    vdy[lane] = params[p + 4] * 0.0625;
                    vdz[lane] = params[p + 5] * 0.0625;
                    sx[lane] = params[p + 6];
                    sy[lane] = params[p + 7];
                    sz[lane] = params[p + 8];
                    piv_x[lane] = skeleton.pivots_x()[bone];
                    piv_y[lane] = skeleton.pivots_y()[bone];
                    piv_z[lane] = skeleton.pivots_z()[bone];
                    any_rotation |= rx[lane] != 0. || ry[lane] != 0. || rz[lane] != 0.;
                }

                // ---- local matrices: cached for clean lanes, rebuilt 8-wide for the group ----
                let mut local_cols = [[0_f32; 8]; 12];
                for lane in 0..8 {
                    if scratch.dirty[bone_lane[lane]] != 0 {
                        continue;
                    }
                    let l = &scratch.locals[bone_lane[lane] * 16..bone_lane[lane] * 16 + 16];
                    local_cols[0][lane] = l[0];
                    local_cols[1][lane] = l[1];
                    local_cols[2][lane] = l[2];
                    local_cols[3][lane] = l[4];
                    local_cols[4][lane] = l[5];
                    local_cols[5][lane] = l[6];
                    local_cols[6][lane] = l[8];
                    local_cols[7][lane] = l[9];
                    local_cols[8][lane] = l[10];
                    local_cols[9][lane] = l[12];
                    local_cols[10][lane] = l[13];
                    local_cols[11][lane] = l[14];
                }
                if any_dirty {
                    let vsx = _mm256_loadu_ps(sx.as_ptr());
                    let vsy = _mm256_loadu_ps(sy.as_ptr());
                    let vsz = _mm256_loadu_ps(sz.as_ptr());
                    let zero = _mm256_setzero_ps();
                    // SAFETY: this function's `#[target_feature]` includes `avx2`.
                    let (sin_x, cos_x, sin_y, cos_y, sin_z, cos_z) = if any_rotation {
                        // SAFETY: as above.
                        unsafe {
                            let (sx_, cx_) = sin_cos8::<FMA>(_mm256_loadu_ps(rx.as_ptr()));
                            let (sy_, cy_) = sin_cos8::<FMA>(_mm256_loadu_ps(ry.as_ptr()));
                            let (sz_, cz_) = sin_cos8::<FMA>(_mm256_loadu_ps(rz.as_ptr()));
                            (sx_, cx_, sy_, cy_, sz_, cz_)
                        }
                    } else {
                        let one = _mm256_set1_ps(1.);
                        (zero, one, zero, one, zero, one)
                    };
                    let l00 = _mm256_mul_ps(_mm256_mul_ps(cos_z, cos_y), vsx);
                    let l10 = _mm256_mul_ps(_mm256_mul_ps(sin_z, cos_y), vsx);
                    let l20 = _mm256_mul_ps(_mm256_sub_ps(zero, sin_y), vsx);
                    let l01 = _mm256_mul_ps(
                        _mm256_sub_ps(
                            _mm256_mul_ps(_mm256_mul_ps(cos_z, sin_y), sin_x),
                            _mm256_mul_ps(sin_z, cos_x),
                        ),
                        vsy,
                    );
                    let l11 = _mm256_mul_ps(
                        _mm256_add_ps(
                            _mm256_mul_ps(_mm256_mul_ps(sin_z, sin_y), sin_x),
                            _mm256_mul_ps(cos_z, cos_x),
                        ),
                        vsy,
                    );
                    let l21 = _mm256_mul_ps(_mm256_mul_ps(cos_y, sin_x), vsy);
                    let l02 = _mm256_mul_ps(
                        _mm256_add_ps(
                            _mm256_mul_ps(_mm256_mul_ps(cos_z, sin_y), cos_x),
                            _mm256_mul_ps(sin_z, sin_x),
                        ),
                        vsz,
                    );
                    let l12 = _mm256_mul_ps(
                        _mm256_sub_ps(
                            _mm256_mul_ps(_mm256_mul_ps(sin_z, sin_y), cos_x),
                            _mm256_mul_ps(cos_z, sin_x),
                        ),
                        vsz,
                    );
                    let l22 = _mm256_mul_ps(_mm256_mul_ps(cos_y, cos_x), vsz);
                    let tpx = _mm256_loadu_ps(piv_x.as_ptr());
                    let tpy = _mm256_loadu_ps(piv_y.as_ptr());
                    let tpz = _mm256_loadu_ps(piv_z.as_ptr());
                    let tdx = _mm256_loadu_ps(vdx.as_ptr());
                    let tdy = _mm256_loadu_ps(vdy.as_ptr());
                    let tdz = _mm256_loadu_ps(vdz.as_ptr());
                    let l03 = _mm256_sub_ps(
                        _mm256_add_ps(tpx, tdx),
                        _mm256_add_ps(
                            _mm256_add_ps(_mm256_mul_ps(l00, tpx), _mm256_mul_ps(l01, tpy)),
                            _mm256_mul_ps(l02, tpz),
                        ),
                    );
                    let l13 = _mm256_sub_ps(
                        _mm256_add_ps(tpy, tdy),
                        _mm256_add_ps(
                            _mm256_add_ps(_mm256_mul_ps(l10, tpx), _mm256_mul_ps(l11, tpy)),
                            _mm256_mul_ps(l12, tpz),
                        ),
                    );
                    let l23 = _mm256_sub_ps(
                        _mm256_add_ps(tpz, tdz),
                        _mm256_add_ps(
                            _mm256_add_ps(_mm256_mul_ps(l20, tpx), _mm256_mul_ps(l21, tpy)),
                            _mm256_mul_ps(l22, tpz),
                        ),
                    );
                    let mut built = [[0_f32; 8]; 12];
                    for (index, vector) in
                        [l00, l10, l20, l01, l11, l21, l02, l12, l22, l03, l13, l23]
                            .into_iter()
                            .enumerate()
                    {
                        // SAFETY: `built[index]` is eight floats and the vector is eight lanes.
                        unsafe { _mm256_storeu_ps(built[index].as_mut_ptr(), vector) };
                    }
                    for lane in 0..8 {
                        let bone = bone_lane[lane];
                        if scratch.dirty[bone] == 0 {
                            continue;
                        }
                        let base = bone * 16;
                        if sx[lane] == 0. || sy[lane] == 0. || sz[lane] == 0. {
                            // A zero scale makes the bone invisible; the matrix is never read, but
                            // it must not carry a stale value either.
                            scratch.locals[base..base + 16].fill(0.);
                            local_cols.iter_mut().for_each(|column| column[lane] = 0.);
                            continue;
                        }
                        let m = &mut scratch.locals[base..base + 16];
                        m[0] = built[0][lane];
                        m[1] = built[1][lane];
                        m[2] = built[2][lane];
                        m[3] = 0.;
                        m[4] = built[3][lane];
                        m[5] = built[4][lane];
                        m[6] = built[5][lane];
                        m[7] = 0.;
                        m[8] = built[6][lane];
                        m[9] = built[7][lane];
                        m[10] = built[8][lane];
                        m[11] = 0.;
                        m[12] = built[9][lane];
                        m[13] = built[10][lane];
                        m[14] = built[11][lane];
                        m[15] = 1.;
                        if !m.iter().all(|v| v.is_finite()) {
                            return Err(Status::NonFinite);
                        }
                        for (index, column) in local_cols.iter_mut().enumerate() {
                            column[lane] = built[index][lane];
                        }
                    }
                }

                // ---- gather the parent world matrices into eight-lane columns ----
                let mut parent_cols = [[0_f32; 8]; 12];
                for lane in 0..8 {
                    let parent = parent_lane[lane];
                    if parent < 0 {
                        parent_cols[0][lane] = 1.;
                        parent_cols[4][lane] = 1.;
                        parent_cols[8][lane] = 1.;
                        continue;
                    }
                    let po = level_position[parent as usize] as usize * 16;
                    let t = &scratch.transforms[po..po + 16];
                    parent_cols[0][lane] = t[0];
                    parent_cols[1][lane] = t[1];
                    parent_cols[2][lane] = t[2];
                    parent_cols[3][lane] = t[4];
                    parent_cols[4][lane] = t[5];
                    parent_cols[5][lane] = t[6];
                    parent_cols[6][lane] = t[8];
                    parent_cols[7][lane] = t[9];
                    parent_cols[8][lane] = t[10];
                    parent_cols[9][lane] = t[12];
                    parent_cols[10][lane] = t[13];
                    parent_cols[11][lane] = t[14];
                }

                // ---- world = parent * local, eight lanes at a time ----
                let l00 = _mm256_loadu_ps(local_cols[0].as_ptr());
                let l10 = _mm256_loadu_ps(local_cols[1].as_ptr());
                let l20 = _mm256_loadu_ps(local_cols[2].as_ptr());
                let l01 = _mm256_loadu_ps(local_cols[3].as_ptr());
                let l11 = _mm256_loadu_ps(local_cols[4].as_ptr());
                let l21 = _mm256_loadu_ps(local_cols[5].as_ptr());
                let l02 = _mm256_loadu_ps(local_cols[6].as_ptr());
                let l12 = _mm256_loadu_ps(local_cols[7].as_ptr());
                let l22 = _mm256_loadu_ps(local_cols[8].as_ptr());
                let l03 = _mm256_loadu_ps(local_cols[9].as_ptr());
                let l13 = _mm256_loadu_ps(local_cols[10].as_ptr());
                let l23 = _mm256_loadu_ps(local_cols[11].as_ptr());
                let p00 = _mm256_loadu_ps(parent_cols[0].as_ptr());
                let p10 = _mm256_loadu_ps(parent_cols[1].as_ptr());
                let p20 = _mm256_loadu_ps(parent_cols[2].as_ptr());
                let p01 = _mm256_loadu_ps(parent_cols[3].as_ptr());
                let p11 = _mm256_loadu_ps(parent_cols[4].as_ptr());
                let p21 = _mm256_loadu_ps(parent_cols[5].as_ptr());
                let p02 = _mm256_loadu_ps(parent_cols[6].as_ptr());
                let p12 = _mm256_loadu_ps(parent_cols[7].as_ptr());
                let p22 = _mm256_loadu_ps(parent_cols[8].as_ptr());
                let p03 = _mm256_loadu_ps(parent_cols[9].as_ptr());
                let p13 = _mm256_loadu_ps(parent_cols[10].as_ptr());
                let p23 = _mm256_loadu_ps(parent_cols[11].as_ptr());
                let w00 = add3!(
                    FMA,
                    _mm256_mul_ps(p00, l00),
                    _mm256_mul_ps(p01, l10),
                    _mm256_mul_ps(p02, l20)
                );
                let w10 = add3!(
                    FMA,
                    _mm256_mul_ps(p10, l00),
                    _mm256_mul_ps(p11, l10),
                    _mm256_mul_ps(p12, l20)
                );
                let w20 = add3!(
                    FMA,
                    _mm256_mul_ps(p20, l00),
                    _mm256_mul_ps(p21, l10),
                    _mm256_mul_ps(p22, l20)
                );
                let w01 = add3!(
                    FMA,
                    _mm256_mul_ps(p00, l01),
                    _mm256_mul_ps(p01, l11),
                    _mm256_mul_ps(p02, l21)
                );
                let w11 = add3!(
                    FMA,
                    _mm256_mul_ps(p10, l01),
                    _mm256_mul_ps(p11, l11),
                    _mm256_mul_ps(p12, l21)
                );
                let w21 = add3!(
                    FMA,
                    _mm256_mul_ps(p20, l01),
                    _mm256_mul_ps(p21, l11),
                    _mm256_mul_ps(p22, l21)
                );
                let w02 = add3!(
                    FMA,
                    _mm256_mul_ps(p00, l02),
                    _mm256_mul_ps(p01, l12),
                    _mm256_mul_ps(p02, l22)
                );
                let w12 = add3!(
                    FMA,
                    _mm256_mul_ps(p10, l02),
                    _mm256_mul_ps(p11, l12),
                    _mm256_mul_ps(p12, l22)
                );
                let w22 = add3!(
                    FMA,
                    _mm256_mul_ps(p20, l02),
                    _mm256_mul_ps(p21, l12),
                    _mm256_mul_ps(p22, l22)
                );
                let w03 = add4!(
                    FMA,
                    _mm256_mul_ps(p00, l03),
                    _mm256_mul_ps(p01, l13),
                    _mm256_mul_ps(p02, l23),
                    p03
                );
                let w13 = add4!(
                    FMA,
                    _mm256_mul_ps(p10, l03),
                    _mm256_mul_ps(p11, l13),
                    _mm256_mul_ps(p12, l23),
                    p13
                );
                let w23 = add4!(
                    FMA,
                    _mm256_mul_ps(p20, l03),
                    _mm256_mul_ps(p21, l13),
                    _mm256_mul_ps(p22, l23),
                    p23
                );
                let mut world = [[0_f32; 8]; 12];
                for (index, vector) in [w00, w10, w20, w01, w11, w21, w02, w12, w22, w03, w13, w23]
                    .into_iter()
                    .enumerate()
                {
                    // SAFETY: `world[index]` is eight floats and the vector is eight lanes.
                    unsafe { _mm256_storeu_ps(world[index].as_mut_ptr(), vector) };
                }

                // ---- per lane: store the world matrix, derive the normal, set the flags ----
                for lane in 0..8 {
                    let bone = bone_lane[lane];
                    if scratch.subtree_dirty[bone] == 0 {
                        continue;
                    }
                    let parent = parent_lane[lane];
                    let p = bone * BONE_PARAM_STRIDE;
                    let lsx = sx[lane];
                    let lsy = sy[lane];
                    let lsz = sz[lane];
                    let s = slot + lane;
                    let m_off = s * 16;
                    let n_off = s * 9;
                    scratch.visible[bone] = 0;
                    scratch.descendants[bone] = 0;
                    scratch.uniform_scale[bone] = 0.;
                    scratch.locator_mask[bone] = 0;
                    let parent_hidden = parent >= 0 && scratch.descendants[parent as usize] == 0;
                    if parent_hidden || lsx == 0. || lsy == 0. || lsz == 0. {
                        scratch.transforms[m_off..m_off + 16].fill(0.);
                        scratch.normals[n_off..n_off + 9].fill(0.);
                        continue;
                    }
                    let w: [f32; 12] = [
                        world[0][lane],
                        world[1][lane],
                        world[2][lane],
                        world[3][lane],
                        world[4][lane],
                        world[5][lane],
                        world[6][lane],
                        world[7][lane],
                        world[8][lane],
                        world[9][lane],
                        world[10][lane],
                        world[11][lane],
                    ];
                    if !w.iter().all(|v| v.is_finite()) {
                        return Err(Status::NonFinite);
                    }
                    let m = &mut scratch.transforms[m_off..m_off + 16];
                    m[0] = w[0];
                    m[1] = w[1];
                    m[2] = w[2];
                    m[3] = 0.;
                    m[4] = w[3];
                    m[5] = w[4];
                    m[6] = w[5];
                    m[7] = 0.;
                    m[8] = w[6];
                    m[9] = w[7];
                    m[10] = w[8];
                    m[11] = 0.;
                    m[12] = w[9];
                    m[13] = w[10];
                    m[14] = w[11];
                    m[15] = 1.;
                    let uniform_local = lsx == lsy && lsy == lsz;
                    let mut world_uniform = 0_f32;
                    if uniform_local {
                        let base = if parent < 0 {
                            1.
                        } else {
                            scratch.uniform_scale[parent as usize]
                        };
                        if base != 0. {
                            let candidate = base * lsx;
                            let abs = candidate.abs();
                            if candidate.is_finite() && (1e-6..=1e6).contains(&abs) {
                                world_uniform = candidate;
                            }
                        }
                    }
                    scratch.uniform_scale[bone] = world_uniform;
                    if world_uniform == 1. || world_uniform == -1. {
                        scratch.normals[n_off..n_off + 9].copy_from_slice(&w[..9]);
                    } else if world_uniform != 0. {
                        let inv = 1. / (world_uniform * world_uniform);
                        for (value_out, value_in) in
                            scratch.normals[n_off..n_off + 9].iter_mut().zip(&w[..9])
                        {
                            *value_out = value_in * inv;
                        }
                    } else {
                        // Non-uniform scale: the cofactor path is rare, and keeping one definition
                        // of it in `compose_one` is worth the call.
                        // SAFETY: `compose_one` re-derives everything from the bone's parameters.
                        unsafe {
                            super::compose_one(skeleton, params, scratch, bone, parent, s)?;
                        }
                        continue;
                    }
                    let own_visible = params[p + 9] == 0.;
                    scratch.visible[bone] = u8::from(own_visible);
                    scratch.descendants[bone] = u8::from(params[p + 10] == 0.);
                    if own_visible && params[p + 11] == 1. {
                        let px = piv_x[lane];
                        let py = piv_y[lane];
                        let pz = piv_z[lane];
                        let q = bone * 4;
                        scratch.locators[q] = -(w[9] + w[0] * px + w[3] * py + w[6] * pz) * 16.;
                        scratch.locators[q + 1] = (w[10] + w[1] * px + w[4] * py + w[7] * pz) * 16.;
                        scratch.locators[q + 2] = (w[11] + w[2] * px + w[5] * py + w[8] * pz) * 16.;
                        scratch.locator_mask[bone] = 1;
                    }
                }
                slot += 8;
            }
            // Tail lanes stay scalar, so a level whose width is not a multiple of eight is not
            // silently truncated.
            while slot < level.end() {
                let bone = order[slot] as usize;
                if scratch.dirty[bone] != 0 {
                    // SAFETY: `refresh_one` re-derives everything from the bone's parameters.
                    unsafe { super::refresh_one(skeleton, params, scratch, bone)? };
                }
                if scratch.subtree_dirty[bone] != 0 {
                    // SAFETY: `bone` and `slot` come from the skeleton's own level plan.
                    unsafe {
                        super::compose_one(
                            skeleton,
                            params,
                            scratch,
                            bone,
                            level_parents[slot],
                            slot,
                        )?;
                    }
                }
                slot += 1;
            }
        }
        Ok(())
    }

    /// Eight-wide local-matrix rebuild over bone order.
    ///
    /// A bone's local matrix depends only on that bone, so there is no ordering constraint and the
    /// grouping can follow bone index — which gives full eight-lane groups with one tail lane.
    /// Grouping by level instead (as the composition must) would leave every narrow level on the
    /// scalar path, and a real 25-level rig has many narrow levels.
    #[inline]
    #[target_feature(enable = "avx2")]
    unsafe fn refresh_body<const FMA: bool>(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
    ) -> Result<(), Status> {
        let n = skeleton.bone_count();
        let mut base_bone = 0;
        while base_bone + 8 <= n {
            let mut any_dirty = false;
            for lane in 0..8 {
                any_dirty |= scratch.dirty[base_bone + lane] != 0;
            }
            if !any_dirty {
                base_bone += 8;
                continue;
            }
            let mut rx = [0_f32; 8];
            let mut ry = [0_f32; 8];
            let mut rz = [0_f32; 8];
            let mut sx = [0_f32; 8];
            let mut sy = [0_f32; 8];
            let mut sz = [0_f32; 8];
            let mut tdx = [0_f32; 8];
            let mut tdy = [0_f32; 8];
            let mut tdz = [0_f32; 8];
            let mut piv_x = [0_f32; 8];
            let mut piv_y = [0_f32; 8];
            let mut piv_z = [0_f32; 8];
            let mut any_rotation = false;
            for lane in 0..8 {
                let bone = base_bone + lane;
                let p = bone * BONE_PARAM_STRIDE;
                rx[lane] = params[p];
                ry[lane] = params[p + 1];
                rz[lane] = params[p + 2];
                tdx[lane] = -params[p + 3] * 0.0625;
                tdy[lane] = params[p + 4] * 0.0625;
                tdz[lane] = params[p + 5] * 0.0625;
                sx[lane] = params[p + 6];
                sy[lane] = params[p + 7];
                sz[lane] = params[p + 8];
                piv_x[lane] = skeleton.pivots_x()[bone];
                piv_y[lane] = skeleton.pivots_y()[bone];
                piv_z[lane] = skeleton.pivots_z()[bone];
                any_rotation |= rx[lane] != 0. || ry[lane] != 0. || rz[lane] != 0.;
            }
            let vsx = _mm256_loadu_ps(sx.as_ptr());
            let vsy = _mm256_loadu_ps(sy.as_ptr());
            let vsz = _mm256_loadu_ps(sz.as_ptr());
            let zero = _mm256_setzero_ps();
            // SAFETY: this function's `#[target_feature]` includes `avx2`, which is all `sin_cos8`
            // requires; the fused form is only selected by the FMA kernel.
            let (sin_x, cos_x, sin_y, cos_y, sin_z, cos_z) = if any_rotation {
                // SAFETY: as above.
                unsafe {
                    let (sx_, cx_) = sin_cos8::<FMA>(_mm256_loadu_ps(rx.as_ptr()));
                    let (sy_, cy_) = sin_cos8::<FMA>(_mm256_loadu_ps(ry.as_ptr()));
                    let (sz_, cz_) = sin_cos8::<FMA>(_mm256_loadu_ps(rz.as_ptr()));
                    (sx_, cx_, sy_, cy_, sz_, cz_)
                }
            } else {
                let one = _mm256_set1_ps(1.);
                (zero, one, zero, one, zero, one)
            };
            let l00 = _mm256_mul_ps(_mm256_mul_ps(cos_z, cos_y), vsx);
            let l10 = _mm256_mul_ps(_mm256_mul_ps(sin_z, cos_y), vsx);
            let l20 = _mm256_mul_ps(_mm256_sub_ps(zero, sin_y), vsx);
            let l01 = _mm256_mul_ps(
                _mm256_sub_ps(
                    _mm256_mul_ps(_mm256_mul_ps(cos_z, sin_y), sin_x),
                    _mm256_mul_ps(sin_z, cos_x),
                ),
                vsy,
            );
            let l11 = _mm256_mul_ps(
                _mm256_add_ps(
                    _mm256_mul_ps(_mm256_mul_ps(sin_z, sin_y), sin_x),
                    _mm256_mul_ps(cos_z, cos_x),
                ),
                vsy,
            );
            let l21 = _mm256_mul_ps(_mm256_mul_ps(cos_y, sin_x), vsy);
            let l02 = _mm256_mul_ps(
                _mm256_add_ps(
                    _mm256_mul_ps(_mm256_mul_ps(cos_z, sin_y), cos_x),
                    _mm256_mul_ps(sin_z, sin_x),
                ),
                vsz,
            );
            let l12 = _mm256_mul_ps(
                _mm256_sub_ps(
                    _mm256_mul_ps(_mm256_mul_ps(sin_z, sin_y), cos_x),
                    _mm256_mul_ps(cos_z, sin_x),
                ),
                vsz,
            );
            let l22 = _mm256_mul_ps(_mm256_mul_ps(cos_y, cos_x), vsz);
            let vpx = _mm256_loadu_ps(piv_x.as_ptr());
            let vpy = _mm256_loadu_ps(piv_y.as_ptr());
            let vpz = _mm256_loadu_ps(piv_z.as_ptr());
            let vdx = _mm256_loadu_ps(tdx.as_ptr());
            let vdy = _mm256_loadu_ps(tdy.as_ptr());
            let vdz = _mm256_loadu_ps(tdz.as_ptr());
            let l03 = _mm256_sub_ps(
                _mm256_add_ps(vpx, vdx),
                _mm256_add_ps(
                    _mm256_add_ps(_mm256_mul_ps(l00, vpx), _mm256_mul_ps(l01, vpy)),
                    _mm256_mul_ps(l02, vpz),
                ),
            );
            let l13 = _mm256_sub_ps(
                _mm256_add_ps(vpy, vdy),
                _mm256_add_ps(
                    _mm256_add_ps(_mm256_mul_ps(l10, vpx), _mm256_mul_ps(l11, vpy)),
                    _mm256_mul_ps(l12, vpz),
                ),
            );
            let l23 = _mm256_sub_ps(
                _mm256_add_ps(vpz, vdz),
                _mm256_add_ps(
                    _mm256_add_ps(_mm256_mul_ps(l20, vpx), _mm256_mul_ps(l21, vpy)),
                    _mm256_mul_ps(l22, vpz),
                ),
            );
            let mut built = [[0_f32; 8]; 12];
            for (index, vector) in [l00, l10, l20, l01, l11, l21, l02, l12, l22, l03, l13, l23]
                .into_iter()
                .enumerate()
            {
                // SAFETY: `built[index]` is eight floats and the vector is eight lanes.
                unsafe { _mm256_storeu_ps(built[index].as_mut_ptr(), vector) };
            }
            for lane in 0..8 {
                let bone = base_bone + lane;
                if scratch.dirty[bone] == 0 {
                    continue;
                }
                let base = bone * 16;
                if sx[lane] == 0. || sy[lane] == 0. || sz[lane] == 0. {
                    // A zero scale makes the bone invisible; the matrix is never read, but it must
                    // not carry a stale value either.
                    scratch.locals[base..base + 16].fill(0.);
                    continue;
                }
                let m = &mut scratch.locals[base..base + 16];
                m[0] = built[0][lane];
                m[1] = built[1][lane];
                m[2] = built[2][lane];
                m[3] = 0.;
                m[4] = built[3][lane];
                m[5] = built[4][lane];
                m[6] = built[5][lane];
                m[7] = 0.;
                m[8] = built[6][lane];
                m[9] = built[7][lane];
                m[10] = built[8][lane];
                m[11] = 0.;
                m[12] = built[9][lane];
                m[13] = built[10][lane];
                m[14] = built[11][lane];
                m[15] = 1.;
                if !m.iter().all(|v| v.is_finite()) {
                    return Err(Status::NonFinite);
                }
            }
            base_bone += 8;
        }
        // Tail: scalar, so a bone count that is not a multiple of eight is not truncated.
        while base_bone < n {
            if scratch.dirty[base_bone] != 0 {
                // SAFETY: `refresh_one` re-derives everything from the bone's own parameters.
                unsafe { super::refresh_one(skeleton, params, scratch, base_bone)? };
            }
            base_bone += 1;
        }
        Ok(())
    }

    /// Local rebuild, AVX2 tier.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones.
    #[target_feature(enable = "avx2")]
    pub(super) unsafe fn refresh_avx2(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
    ) -> Result<(), Status> {
        // SAFETY: the caller sized `scratch` for this skeleton.
        unsafe { refresh_body::<false>(skeleton, params, scratch) }
    }

    /// Local rebuild, AVX2+FMA tier.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones.
    #[target_feature(enable = "avx2,fma")]
    pub(super) unsafe fn refresh_fma(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
    ) -> Result<(), Status> {
        // SAFETY: as above.
        unsafe { refresh_body::<true>(skeleton, params, scratch) }
    }

    /// The fused step, AVX2 tier, maintaining the local-matrix cache.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones.
    #[target_feature(enable = "avx2")]
    pub(super) unsafe fn step_avx2(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
    ) -> Result<(), Status> {
        // SAFETY: the caller sized `scratch` for this skeleton.
        unsafe { step_body::<false>(skeleton, params, scratch) }
    }

    /// The fused step, AVX2+FMA tier, maintaining the cache.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones.
    #[target_feature(enable = "avx2,fma")]
    pub(super) unsafe fn step_fma(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
    ) -> Result<(), Status> {
        // SAFETY: as above.
        unsafe { step_body::<true>(skeleton, params, scratch) }
    }

    /// AVX-512 exposes the eight-wide step by delegating to the AVX2 body.
    ///
    /// It reports AVX2 because that is the code it executes; claiming AVX-512 would name
    /// instructions this path does not issue.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones.
    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "avx512f,avx2,avx")]
    pub(super) unsafe fn step_avx512(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
    ) -> Result<(), Status> {
        // SAFETY: as above.
        unsafe { step_body::<false>(skeleton, params, scratch) }
    }

    /// One eight-bone group of the uncached kernel: rebuild the locals, compose the worlds, and
    /// write the packed records.
    ///
    /// This is the body the cached kernel cannot share. It reads no dirty flags and writes no
    /// cache entries, so a fully-animated frame pays for exactly the arithmetic it needs.
    ///
    /// The record write is fused in here rather than left to [`write_records`]. On a frame where
    /// every bone is dirty that separate pass re-reads every world matrix and normal from memory
    /// for no reason, which measured at ~10 µs for 773 bones — a sixth of the frame. Writing each
    /// record while its matrix is still in registers removes that read entirely.
    ///
    /// # Safety
    ///
    /// `slot + 8 <= skeleton.level_order().len()`, `scratch` must be sized for
    /// `skeleton.bone_count()` bones, and `out` must be valid for `bone_count * 144` writable
    /// bytes.
    // The eight arguments are deliberate. Bundling the record-writing context into a struct was
    // measured at 6 µs slower on a fully-animated 773-bone rig: the indirection stopped the
    // compiler keeping the root columns and the hidden record in registers across the group.
    #[allow(clippy::too_many_arguments)]
    #[inline]
    #[target_feature(enable = "avx2")]
    unsafe fn evaluate_group<const FMA: bool>(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
        slot: usize,
        out: *mut u8,
        request: &PackedRequest<'_>,
        hidden: &[u8; PACKED_BONE_STRIDE],
        root_cols: &RootColumns,
    ) -> Result<(), Status> {
        let order = skeleton.level_order();
        let level_parents = skeleton.level_parents();
        let level_position = skeleton.level_position();

        // ---- gather this group's parameters ----
        let mut rx = [0_f32; 8];
        let mut ry = [0_f32; 8];
        let mut rz = [0_f32; 8];
        let mut vdx = [0_f32; 8];
        let mut vdy = [0_f32; 8];
        let mut vdz = [0_f32; 8];
        let mut sx = [0_f32; 8];
        let mut sy = [0_f32; 8];
        let mut sz = [0_f32; 8];
        let mut piv_x = [0_f32; 8];
        let mut piv_y = [0_f32; 8];
        let mut piv_z = [0_f32; 8];
        let mut bone_lane = [0_usize; 8];
        let mut parent_lane = [0_i32; 8];
        let mut any_rotation = false;
        for lane in 0..8 {
            let bone = order[slot + lane] as usize;
            bone_lane[lane] = bone;
            parent_lane[lane] = level_parents[slot + lane];
            let p = bone * BONE_PARAM_STRIDE;
            rx[lane] = params[p];
            ry[lane] = params[p + 1];
            rz[lane] = params[p + 2];
            vdx[lane] = -params[p + 3] * 0.0625;
            vdy[lane] = params[p + 4] * 0.0625;
            vdz[lane] = params[p + 5] * 0.0625;
            sx[lane] = params[p + 6];
            sy[lane] = params[p + 7];
            sz[lane] = params[p + 8];
            piv_x[lane] = skeleton.pivots_x()[bone];
            piv_y[lane] = skeleton.pivots_y()[bone];
            piv_z[lane] = skeleton.pivots_z()[bone];
            any_rotation |= rx[lane] != 0. || ry[lane] != 0. || rz[lane] != 0.;
        }

        // ---- local matrices, eight lanes at a time ----
        let vsx = _mm256_loadu_ps(sx.as_ptr());
        let vsy = _mm256_loadu_ps(sy.as_ptr());
        let vsz = _mm256_loadu_ps(sz.as_ptr());
        let zero = _mm256_setzero_ps();
        // SAFETY: this function's `#[target_feature]` includes `avx2`, which is all `sin_cos8`
        // requires; the fused form is only selected by the FMA kernel.
        let (sin_x, cos_x, sin_y, cos_y, sin_z, cos_z) = if any_rotation {
            // SAFETY: as above.
            unsafe {
                let (sx_, cx_) = sin_cos8::<FMA>(_mm256_loadu_ps(rx.as_ptr()));
                let (sy_, cy_) = sin_cos8::<FMA>(_mm256_loadu_ps(ry.as_ptr()));
                let (sz_, cz_) = sin_cos8::<FMA>(_mm256_loadu_ps(rz.as_ptr()));
                (sx_, cx_, sy_, cy_, sz_, cz_)
            }
        } else {
            let one = _mm256_set1_ps(1.);
            (zero, one, zero, one, zero, one)
        };
        let l00 = _mm256_mul_ps(_mm256_mul_ps(cos_z, cos_y), vsx);
        let l10 = _mm256_mul_ps(_mm256_mul_ps(sin_z, cos_y), vsx);
        let l20 = _mm256_mul_ps(_mm256_sub_ps(zero, sin_y), vsx);
        let l01 = _mm256_mul_ps(
            _mm256_sub_ps(
                _mm256_mul_ps(_mm256_mul_ps(cos_z, sin_y), sin_x),
                _mm256_mul_ps(sin_z, cos_x),
            ),
            vsy,
        );
        let l11 = _mm256_mul_ps(
            _mm256_add_ps(
                _mm256_mul_ps(_mm256_mul_ps(sin_z, sin_y), sin_x),
                _mm256_mul_ps(cos_z, cos_x),
            ),
            vsy,
        );
        let l21 = _mm256_mul_ps(_mm256_mul_ps(cos_y, sin_x), vsy);
        let l02 = _mm256_mul_ps(
            _mm256_add_ps(
                _mm256_mul_ps(_mm256_mul_ps(cos_z, sin_y), cos_x),
                _mm256_mul_ps(sin_z, sin_x),
            ),
            vsz,
        );
        let l12 = _mm256_mul_ps(
            _mm256_sub_ps(
                _mm256_mul_ps(_mm256_mul_ps(sin_z, sin_y), cos_x),
                _mm256_mul_ps(cos_z, sin_x),
            ),
            vsz,
        );
        let l22 = _mm256_mul_ps(_mm256_mul_ps(cos_y, cos_x), vsz);
        let vpx = _mm256_loadu_ps(piv_x.as_ptr());
        let vpy = _mm256_loadu_ps(piv_y.as_ptr());
        let vpz = _mm256_loadu_ps(piv_z.as_ptr());
        let vdxv = _mm256_loadu_ps(vdx.as_ptr());
        let vdyv = _mm256_loadu_ps(vdy.as_ptr());
        let vdzv = _mm256_loadu_ps(vdz.as_ptr());
        let l03 = _mm256_sub_ps(
            _mm256_add_ps(vpx, vdxv),
            _mm256_add_ps(
                _mm256_add_ps(_mm256_mul_ps(l00, vpx), _mm256_mul_ps(l01, vpy)),
                _mm256_mul_ps(l02, vpz),
            ),
        );
        let l13 = _mm256_sub_ps(
            _mm256_add_ps(vpy, vdyv),
            _mm256_add_ps(
                _mm256_add_ps(_mm256_mul_ps(l10, vpx), _mm256_mul_ps(l11, vpy)),
                _mm256_mul_ps(l12, vpz),
            ),
        );
        let l23 = _mm256_sub_ps(
            _mm256_add_ps(vpz, vdzv),
            _mm256_add_ps(
                _mm256_add_ps(_mm256_mul_ps(l20, vpx), _mm256_mul_ps(l21, vpy)),
                _mm256_mul_ps(l22, vpz),
            ),
        );
        let mut local_cols = [[0_f32; 8]; 12];
        for (index, vector) in [l00, l10, l20, l01, l11, l21, l02, l12, l22, l03, l13, l23]
            .into_iter()
            .enumerate()
        {
            // SAFETY: `local_cols[index]` is eight floats and the vector is eight lanes.
            unsafe { _mm256_storeu_ps(local_cols[index].as_mut_ptr(), vector) };
        }
        for lane in 0..8 {
            if sx[lane] == 0. || sy[lane] == 0. || sz[lane] == 0. {
                // A zero scale makes the bone invisible; the composition below zeroes its
                // matrices, so nothing needs to be stored here.
                local_cols.iter_mut().for_each(|column| column[lane] = 0.);
                continue;
            }
            // The local matrix stays in `local_cols` and is handed to the composition directly.
            // Storing it to `scratch.locals` would be a 64-byte write per bone that nothing on
            // this path reads — the cache is declared stale instead.
            if !local_cols.iter().all(|column| column[lane].is_finite()) {
                return Err(Status::NonFinite);
            }
        }

        // ---- gather the parent world matrices into eight-lane columns ----
        let mut parent_cols = [[0_f32; 8]; 12];
        for lane in 0..8 {
            let parent = parent_lane[lane];
            if parent < 0 {
                parent_cols[0][lane] = 1.;
                parent_cols[4][lane] = 1.;
                parent_cols[8][lane] = 1.;
                continue;
            }
            let po = level_position[parent as usize] as usize * 16;
            let t = &scratch.transforms[po..po + 16];
            parent_cols[0][lane] = t[0];
            parent_cols[1][lane] = t[1];
            parent_cols[2][lane] = t[2];
            parent_cols[3][lane] = t[4];
            parent_cols[4][lane] = t[5];
            parent_cols[5][lane] = t[6];
            parent_cols[6][lane] = t[8];
            parent_cols[7][lane] = t[9];
            parent_cols[8][lane] = t[10];
            parent_cols[9][lane] = t[12];
            parent_cols[10][lane] = t[13];
            parent_cols[11][lane] = t[14];
        }

        // ---- world = parent * local, eight lanes at a time ----
        let l00 = _mm256_loadu_ps(local_cols[0].as_ptr());
        let l10 = _mm256_loadu_ps(local_cols[1].as_ptr());
        let l20 = _mm256_loadu_ps(local_cols[2].as_ptr());
        let l01 = _mm256_loadu_ps(local_cols[3].as_ptr());
        let l11 = _mm256_loadu_ps(local_cols[4].as_ptr());
        let l21 = _mm256_loadu_ps(local_cols[5].as_ptr());
        let l02 = _mm256_loadu_ps(local_cols[6].as_ptr());
        let l12 = _mm256_loadu_ps(local_cols[7].as_ptr());
        let l22 = _mm256_loadu_ps(local_cols[8].as_ptr());
        let l03 = _mm256_loadu_ps(local_cols[9].as_ptr());
        let l13 = _mm256_loadu_ps(local_cols[10].as_ptr());
        let l23 = _mm256_loadu_ps(local_cols[11].as_ptr());
        let p00 = _mm256_loadu_ps(parent_cols[0].as_ptr());
        let p10 = _mm256_loadu_ps(parent_cols[1].as_ptr());
        let p20 = _mm256_loadu_ps(parent_cols[2].as_ptr());
        let p01 = _mm256_loadu_ps(parent_cols[3].as_ptr());
        let p11 = _mm256_loadu_ps(parent_cols[4].as_ptr());
        let p21 = _mm256_loadu_ps(parent_cols[5].as_ptr());
        let p02 = _mm256_loadu_ps(parent_cols[6].as_ptr());
        let p12 = _mm256_loadu_ps(parent_cols[7].as_ptr());
        let p22 = _mm256_loadu_ps(parent_cols[8].as_ptr());
        let p03 = _mm256_loadu_ps(parent_cols[9].as_ptr());
        let p13 = _mm256_loadu_ps(parent_cols[10].as_ptr());
        let p23 = _mm256_loadu_ps(parent_cols[11].as_ptr());
        let w00 = add3!(
            FMA,
            _mm256_mul_ps(p00, l00),
            _mm256_mul_ps(p01, l10),
            _mm256_mul_ps(p02, l20)
        );
        let w10 = add3!(
            FMA,
            _mm256_mul_ps(p10, l00),
            _mm256_mul_ps(p11, l10),
            _mm256_mul_ps(p12, l20)
        );
        let w20 = add3!(
            FMA,
            _mm256_mul_ps(p20, l00),
            _mm256_mul_ps(p21, l10),
            _mm256_mul_ps(p22, l20)
        );
        let w01 = add3!(
            FMA,
            _mm256_mul_ps(p00, l01),
            _mm256_mul_ps(p01, l11),
            _mm256_mul_ps(p02, l21)
        );
        let w11 = add3!(
            FMA,
            _mm256_mul_ps(p10, l01),
            _mm256_mul_ps(p11, l11),
            _mm256_mul_ps(p12, l21)
        );
        let w21 = add3!(
            FMA,
            _mm256_mul_ps(p20, l01),
            _mm256_mul_ps(p21, l11),
            _mm256_mul_ps(p22, l21)
        );
        let w02 = add3!(
            FMA,
            _mm256_mul_ps(p00, l02),
            _mm256_mul_ps(p01, l12),
            _mm256_mul_ps(p02, l22)
        );
        let w12 = add3!(
            FMA,
            _mm256_mul_ps(p10, l02),
            _mm256_mul_ps(p11, l12),
            _mm256_mul_ps(p12, l22)
        );
        let w22 = add3!(
            FMA,
            _mm256_mul_ps(p20, l02),
            _mm256_mul_ps(p21, l12),
            _mm256_mul_ps(p22, l22)
        );
        let w03 = add4!(
            FMA,
            _mm256_mul_ps(p00, l03),
            _mm256_mul_ps(p01, l13),
            _mm256_mul_ps(p02, l23),
            p03
        );
        let w13 = add4!(
            FMA,
            _mm256_mul_ps(p10, l03),
            _mm256_mul_ps(p11, l13),
            _mm256_mul_ps(p12, l23),
            p13
        );
        let w23 = add4!(
            FMA,
            _mm256_mul_ps(p20, l03),
            _mm256_mul_ps(p21, l13),
            _mm256_mul_ps(p22, l23),
            p23
        );
        let mut world = [[0_f32; 8]; 12];
        for (index, vector) in [w00, w10, w20, w01, w11, w21, w02, w12, w22, w03, w13, w23]
            .into_iter()
            .enumerate()
        {
            // SAFETY: `world[index]` is eight floats and the vector is eight lanes.
            unsafe { _mm256_storeu_ps(world[index].as_mut_ptr(), vector) };
        }

        // ---- per lane: store the world matrix, derive the normal, set the flags, write the record
        // ----
        let glow = skeleton.glow_mask();
        for lane in 0..8 {
            let bone = bone_lane[lane];
            let parent = parent_lane[lane];
            let p = bone * BONE_PARAM_STRIDE;
            let lsx = sx[lane];
            let lsy = sy[lane];
            let lsz = sz[lane];
            let s = slot + lane;
            let m_off = s * 16;
            let n_off = s * 9;
            // SAFETY: `bone < bone_count`, so this is this bone's 144-byte slot.
            let record = unsafe { out.add(bone * PACKED_BONE_STRIDE) };
            let pass_visible = match request.pass {
                BonePassFilter::All => true,
                BonePassFilter::NonGlow => glow[bone] == 0,
                BonePassFilter::Glow => glow[bone] != 0,
            };
            scratch.visible[bone] = 0;
            scratch.descendants[bone] = 0;
            scratch.uniform_scale[bone] = 0.;
            scratch.locator_mask[bone] = 0;
            let parent_hidden = parent >= 0 && scratch.descendants[parent as usize] == 0;
            if parent_hidden || lsx == 0. || lsy == 0. || lsz == 0. || !pass_visible {
                scratch.transforms[m_off..m_off + 16].fill(0.);
                scratch.normals[n_off..n_off + 9].fill(0.);
                // SAFETY: `record` is this bone's slot and `hidden` is exactly one record.
                unsafe {
                    std::ptr::copy_nonoverlapping(hidden.as_ptr(), record, PACKED_BONE_STRIDE);
                }
                continue;
            }
            let w: [f32; 12] = [
                world[0][lane],
                world[1][lane],
                world[2][lane],
                world[3][lane],
                world[4][lane],
                world[5][lane],
                world[6][lane],
                world[7][lane],
                world[8][lane],
                world[9][lane],
                world[10][lane],
                world[11][lane],
            ];
            if !w.iter().all(|v| v.is_finite()) {
                return Err(Status::NonFinite);
            }
            let m = &mut scratch.transforms[m_off..m_off + 16];
            m[0] = w[0];
            m[1] = w[1];
            m[2] = w[2];
            m[3] = 0.;
            m[4] = w[3];
            m[5] = w[4];
            m[6] = w[5];
            m[7] = 0.;
            m[8] = w[6];
            m[9] = w[7];
            m[10] = w[8];
            m[11] = 0.;
            m[12] = w[9];
            m[13] = w[10];
            m[14] = w[11];
            m[15] = 1.;
            let uniform_local = lsx == lsy && lsy == lsz;
            let mut world_uniform = 0_f32;
            if uniform_local {
                let base = if parent < 0 {
                    1.
                } else {
                    scratch.uniform_scale[parent as usize]
                };
                if base != 0. {
                    let candidate = base * lsx;
                    let abs = candidate.abs();
                    if candidate.is_finite() && (1e-6..=1e6).contains(&abs) {
                        world_uniform = candidate;
                    }
                }
            }
            scratch.uniform_scale[bone] = world_uniform;
            // The normal matrix is derived into a local array rather than into `scratch.normals`.
            // On a fully-animated frame nothing reads `scratch.normals` afterwards — the record is
            // the only consumer — so storing it and reading it straight back is a 36-byte round
            // trip per bone for nothing. The non-uniform branch below still fills it, because
            // `compose_one_with` reads it.
            let mut normal = [0_f32; 9];
            if world_uniform == 1. || world_uniform == -1. {
                normal.copy_from_slice(&w[..9]);
            } else if world_uniform != 0. {
                let inv = 1. / (world_uniform * world_uniform);
                for (value_out, value_in) in normal.iter_mut().zip(&w[..9]) {
                    *value_out = value_in * inv;
                }
            } else {
                // Non-uniform scale: the cofactor path is rare, and keeping one definition of it
                // in `compose_one_with` is worth the call.
                let local: [f32; 16] = [
                    local_cols[0][lane],
                    local_cols[1][lane],
                    local_cols[2][lane],
                    0.,
                    local_cols[3][lane],
                    local_cols[4][lane],
                    local_cols[5][lane],
                    0.,
                    local_cols[6][lane],
                    local_cols[7][lane],
                    local_cols[8][lane],
                    0.,
                    local_cols[9][lane],
                    local_cols[10][lane],
                    local_cols[11][lane],
                    1.,
                ];
                // SAFETY: `compose_one_with` re-derives everything from `local` and the
                // parameters, and `bone` and `s` come from the skeleton's own level plan.
                unsafe {
                    super::compose_one_with(skeleton, params, scratch, bone, parent, s, &local)?;
                }
                let light = if glow[bone] != 0 {
                    request.full_bright_light
                } else {
                    request.packed_light
                };
                // SAFETY: `record` is this bone's slot; the slices are this bone's matrices.
                unsafe {
                    root_cols.write_record(
                        record,
                        &scratch.transforms[m_off..m_off + 16],
                        &scratch.normals[n_off..n_off + 9],
                        light,
                        request.layout,
                    );
                }
                continue;
            }
            scratch.normals[n_off..n_off + 9].copy_from_slice(&normal);
            let own_visible = params[p + 9] == 0.;
            scratch.visible[bone] = u8::from(own_visible);
            scratch.descendants[bone] = u8::from(params[p + 10] == 0.);
            if own_visible && params[p + 11] == 1. {
                let px = piv_x[lane];
                let py = piv_y[lane];
                let pz = piv_z[lane];
                let q = bone * 4;
                scratch.locators[q] = -(w[9] + w[0] * px + w[3] * py + w[6] * pz) * 16.;
                scratch.locators[q + 1] = (w[10] + w[1] * px + w[4] * py + w[7] * pz) * 16.;
                scratch.locators[q + 2] = (w[11] + w[2] * px + w[5] * py + w[8] * pz) * 16.;
                scratch.locator_mask[bone] = 1;
            }
            // A bone that is itself hidden still gets its matrices composed — its children may be
            // visible — but its record must be the canonical hidden one, exactly as
            // `write_records` would emit. Writing a visible record here would make the two
            // kernels disagree on any rig with a hidden bone.
            if !own_visible {
                // SAFETY: `record` is this bone's slot and `hidden` is exactly one record.
                unsafe {
                    std::ptr::copy_nonoverlapping(hidden.as_ptr(), record, PACKED_BONE_STRIDE);
                }
                continue;
            }
            let light = if glow[bone] != 0 {
                request.full_bright_light
            } else {
                request.packed_light
            };
            // SAFETY: `record` is this bone's slot; the slices are this bone's matrices.
            unsafe {
                root_cols.write_record(
                    record,
                    &scratch.transforms[m_off..m_off + 16],
                    &scratch.normals[n_off..n_off + 9],
                    light,
                    request.layout,
                );
            }
        }
        Ok(())
    }

    /// The uncached kernel: every local matrix, every world matrix and every packed record, in one
    /// level-ordered pass.
    ///
    /// This is the "design A" body. It is a separate function from [`step_body`] on purpose: the
    /// cached kernel's cache maintenance is a real cost on a fully-animated frame, and an earlier
    /// attempt to fold both data flows into one function made that frame 22% slower. Keeping them
    /// apart lets each be compiled for the case it actually serves.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones and `out` must be valid for
    /// `bone_count * 144` writable bytes.
    #[inline]
    #[target_feature(enable = "avx2")]
    unsafe fn evaluate_body<const FMA: bool>(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
        out: *mut u8,
        request: &PackedRequest<'_>,
    ) -> Result<(), Status> {
        let order = skeleton.level_order();
        let level_parents = skeleton.level_parents();
        let hidden = super::hidden_record(request.layout);
        let root_cols = RootColumns::new(request.root);
        let glow = skeleton.glow_mask();
        for level in skeleton.levels() {
            let mut slot = level.start;
            while slot + 8 <= level.end() {
                // SAFETY: `slot + 8 <= level.end() <= order.len()`, `scratch` is sized for this
                // skeleton, and `out` holds `bone_count * 144` bytes.
                unsafe {
                    evaluate_group::<FMA>(
                        skeleton, params, scratch, slot, out, request, &hidden, &root_cols,
                    )?
                };
                slot += 8;
            }
            // Tail lanes stay scalar, so a level whose width is not a multiple of eight is not
            // silently truncated.
            while slot < level.end() {
                let bone = order[slot] as usize;
                // SAFETY: `bone` and `slot` come from the skeleton's own level plan.
                unsafe {
                    super::refresh_one(skeleton, params, scratch, bone)?;
                    super::compose_one(skeleton, params, scratch, bone, level_parents[slot], slot)?;
                }
                // SAFETY: `bone < bone_count`, so this is this bone's 144-byte slot.
                let record = unsafe { out.add(bone * PACKED_BONE_STRIDE) };
                let pass_visible = match request.pass {
                    BonePassFilter::All => true,
                    BonePassFilter::NonGlow => glow[bone] == 0,
                    BonePassFilter::Glow => glow[bone] != 0,
                };
                if scratch.visible[bone] == 0 || !pass_visible {
                    // SAFETY: `record` is this bone's slot and `hidden` is exactly one record.
                    unsafe {
                        std::ptr::copy_nonoverlapping(hidden.as_ptr(), record, PACKED_BONE_STRIDE);
                    }
                } else {
                    let light = if glow[bone] != 0 {
                        request.full_bright_light
                    } else {
                        request.packed_light
                    };
                    // SAFETY: `record` is this bone's slot; the slices are this bone's matrices.
                    unsafe {
                        root_cols.write_record(
                            record,
                            &scratch.transforms[slot * 16..slot * 16 + 16],
                            &scratch.normals[slot * 9..slot * 9 + 9],
                            light,
                            request.layout,
                        );
                    }
                }
                slot += 1;
            }
        }
        Ok(())
    }

    /// The uncached kernel, AVX2 tier.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones.
    #[target_feature(enable = "avx2")]
    pub(super) unsafe fn evaluate_avx2(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
        out: *mut u8,
        request: &PackedRequest<'_>,
    ) -> Result<(), Status> {
        // SAFETY: the caller sized `scratch` for this skeleton.
        unsafe { evaluate_body::<false>(skeleton, params, scratch, out, request) }
    }

    /// The uncached kernel, AVX2+FMA tier.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones.
    #[target_feature(enable = "avx2,fma")]
    pub(super) unsafe fn evaluate_fma(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
        out: *mut u8,
        request: &PackedRequest<'_>,
    ) -> Result<(), Status> {
        // SAFETY: as above.
        unsafe { evaluate_body::<true>(skeleton, params, scratch, out, request) }
    }

    /// AVX-512 exposes the eight-wide uncached kernel by delegating to the AVX2 body, and reports
    /// AVX2 because that is the code it executes.
    ///
    /// # Safety
    ///
    /// `scratch` must be sized for `skeleton.bone_count()` bones.
    #[cfg(target_arch = "x86_64")]
    #[target_feature(enable = "avx512f,avx2,avx")]
    pub(super) unsafe fn evaluate_avx512(
        skeleton: &CompiledSkeleton,
        params: &[f32],
        scratch: &mut Scratch,
        out: *mut u8,
        request: &PackedRequest<'_>,
    ) -> Result<(), Status> {
        // SAFETY: as above.
        unsafe { evaluate_body::<false>(skeleton, params, scratch, out, request) }
    }
}

// There is deliberately no `neon` module here. NEON has no dedicated 8-wide packed kernel, so every
// aarch64 tier resolves to the portable walk, and that walk reports `Scalar` because that is the
// code it executes. A module that only forwards to the portable walk would add a name without
// changing which instructions run, and reporting `Neon` for it would be exactly the N13 defect the
// tier report exists to prevent.
