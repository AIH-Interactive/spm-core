//! Immutable, pre-decoded skeleton topology for the packed fast path.
//!
//! The reference evaluator in [`crate::bones`] rebuilds its child lists, degree counts and
//! breadth-first order on every call. That is correct but it costs a full graph construction per
//! frame, which dominates a 500-bone model. A [`CompiledSkeleton`] pays that cost exactly once, at
//! model load, and then hands the hot path a flat, cache-friendly description that needs no
//! allocation and no traversal bookkeeping.
//!
//! # Why depth levels
//!
//! Bones at the same tree depth never depend on each other, so a whole level can be evaluated in
//! parallel lanes. Real rigs are shallow and wide (a 500-bone character is typically 6..12 levels
//! deep with 40..80 siblings per level), which is exactly the shape a wide SIMD kernel wants.
//!
//! # Invariants established here, relied on by the hot path
//!
//! Construction validates every index and rejects cycles, so the evaluator may index without
//! re-checking:
//!
//! * every `parents[i]` is `-1` or in `0..bone_count`, and never `i`;
//! * `level_order` is a permutation of `0..bone_count`;
//! * for every entry, the parent appears in a strictly earlier level, so a level-ordered pass
//!   always reads a finished parent;
//! * `level_parents[k]` is the parent of `level_order[k]`, pre-decoded so the hot path never
//!   performs a second lookup.
use spm_native_abi::Status;
use std::sync::atomic::{AtomicU64, Ordering};

/// One contiguous run of `level_order` holding bones of equal tree depth.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LevelSlice {
    pub start: usize,
    pub len: usize,
}

impl LevelSlice {
    pub fn end(&self) -> usize {
        self.start + self.len
    }
}

/// Hands out one identity per compiled skeleton; see [`CompiledSkeleton::identity`].
static NEXT_IDENTITY: AtomicU64 = AtomicU64::new(1);

/// Pre-decoded skeleton topology. Immutable once built.
#[derive(Clone, Debug)]
pub struct CompiledSkeleton {
    bone_count: usize,
    parents: Vec<i32>,
    pivots_x: Vec<f32>,
    pivots_y: Vec<f32>,
    pivots_z: Vec<f32>,
    glow_mask: Vec<u8>,
    levels: Vec<LevelSlice>,
    level_order: Vec<u32>,
    level_parents: Vec<i32>,
    /// `level_position[bone]` is where `bone` sits in `level_order`.
    ///
    /// The hot path stores its intermediate matrices in level-order, so a group of eight siblings
    /// occupies eight contiguous slots. Looking up a parent then needs this map, which is why it is
    /// precomputed here rather than derived per frame.
    level_position: Vec<u32>,
    /// `level_depth[k]` is the tree depth of `level_order[k]`; used only by tests and diagnostics.
    level_depth: Vec<u32>,
    /// A unique identity for this compiled instance, used to invalidate per-thread caches.
    ///
    /// This is a monotonic counter rather than a hash of the topology. A content hash made two
    /// separately compiled copies of the same rig share one identity, and the per-thread scratch
    /// also remembers which output buffers already hold current records. When one of those models
    /// was dropped and its output buffer's address was handed to the next model — which the 32-bit
    /// CI job does reliably, because its address space is small — the surviving entry said "this
    /// buffer is current" and the new model kept the old matrices. A counter can never repeat, so a
    /// fresh instance always starts from an empty cache.
    identity: u64,
}

impl CompiledSkeleton {
    /// Validates and compiles `parents`, `pivots` (three floats per bone) and `glow` (one byte per
    /// bone, `0` = normal, `1` = full-bright).
    ///
    /// Every rejection is `Status::InvalidInput`; nothing is partially published, so a failed
    /// compile leaves no handle behind.
    pub fn compile(parents: &[i32], pivots: &[f32], glow: &[u8]) -> Result<Self, Status> {
        let n = parents.len();
        if pivots.len() != n.checked_mul(3).ok_or(Status::InvalidInput)? || glow.len() != n {
            return Err(Status::InvalidInput);
        }
        // A skeleton with no bones is a valid, empty skeleton: it evaluates to nothing rather than
        // being an error, so a caller does not need a special case for degenerate models.
        for (i, &parent) in parents.iter().enumerate() {
            if parent < -1 || parent >= n as i32 || parent == i as i32 {
                return Err(Status::InvalidInput);
            }
        }
        if pivots.iter().any(|v| !v.is_finite()) {
            return Err(Status::NonFinite);
        }
        if glow.iter().any(|&g| g > 1) {
            return Err(Status::InvalidInput);
        }

        let mut pivots_x = Vec::new();
        let mut pivots_y = Vec::new();
        let mut pivots_z = Vec::new();
        for axis in [&mut pivots_x, &mut pivots_y, &mut pivots_z] {
            axis.try_reserve_exact(n).map_err(|_| Status::Internal)?;
        }
        for i in 0..n {
            pivots_x.push(pivots[i * 3]);
            pivots_y.push(pivots[i * 3 + 1]);
            pivots_z.push(pivots[i * 3 + 2]);
        }

        let (levels, level_order, level_parents, level_depth) = build_levels(parents)?;
        let mut level_position = Vec::new();
        level_position
            .try_reserve_exact(n)
            .map_err(|_| Status::Internal)?;
        level_position.resize(n, 0_u32);
        for (slot, &bone) in level_order.iter().enumerate() {
            level_position[bone as usize] = slot as u32;
        }
        // Unique per compiled instance; see the field's documentation for why this is a counter
        // and not a hash of the inputs.
        let identity = NEXT_IDENTITY.fetch_add(1, Ordering::Relaxed);

        Ok(Self {
            bone_count: n,
            parents: parents.to_vec(),
            pivots_x,
            pivots_y,
            pivots_z,
            glow_mask: glow.to_vec(),
            levels,
            level_order,
            level_parents,
            level_position,
            level_depth,
            identity,
        })
    }

    /// A cheap identity for this skeleton, used to invalidate per-thread caches.
    pub fn identity(&self) -> u64 {
        self.identity
    }

    pub fn bone_count(&self) -> usize {
        self.bone_count
    }

    pub fn parents(&self) -> &[i32] {
        &self.parents
    }

    pub fn pivots_x(&self) -> &[f32] {
        &self.pivots_x
    }

    pub fn pivots_y(&self) -> &[f32] {
        &self.pivots_y
    }

    pub fn pivots_z(&self) -> &[f32] {
        &self.pivots_z
    }

    pub fn glow_mask(&self) -> &[u8] {
        &self.glow_mask
    }

    pub fn levels(&self) -> &[LevelSlice] {
        &self.levels
    }

    pub fn level_order(&self) -> &[u32] {
        &self.level_order
    }

    pub fn level_parents(&self) -> &[i32] {
        &self.level_parents
    }

    /// Where `bone` sits in [`Self::level_order`].
    pub fn level_position(&self) -> &[u32] {
        &self.level_position
    }

    pub fn level_depth(&self) -> &[u32] {
        &self.level_depth
    }

    /// Deepest tree level plus one; `0` for an empty skeleton.
    pub fn depth(&self) -> usize {
        self.levels.len()
    }

    /// Bytes a packed evaluation writes for this skeleton.
    pub fn packed_bytes(&self) -> Option<usize> {
        self.bone_count
            .checked_mul(spm_native_abi::PACKED_BONE_STRIDE)
    }

    /// Floats a packed evaluation reads from `boneParams`.
    pub fn param_floats(&self) -> Option<usize> {
        self.bone_count
            .checked_mul(spm_native_abi::BONE_PARAM_STRIDE)
    }
}

/// The four parallel arrays that describe the level decomposition.
type LevelPlan = (Vec<LevelSlice>, Vec<u32>, Vec<i32>, Vec<u32>);

/// Groups bones by tree depth and flattens them into one level-ordered permutation.
///
/// The depth of a bone is `1 + depth(parent)`, with roots at depth `0`. Computing it by walking
/// each bone's ancestor chain would be quadratic on a deep chain, so this builds a compressed
/// adjacency list once and then peels the tree level by level: every bone is visited a constant
/// number of times, so the whole construction is `O(n)`. A bone that never becomes reachable is
/// part of a cycle, which is rejected.
fn build_levels(parents: &[i32]) -> Result<LevelPlan, Status> {
    let n = parents.len();
    // Compressed adjacency: `child_start[i]..child_start[i + 1]` indexes `child_list`.
    let mut child_start = Vec::new();
    child_start
        .try_reserve_exact(n + 1)
        .map_err(|_| Status::Internal)?;
    child_start.resize(n + 1, 0_u32);
    let mut roots = Vec::new();
    roots.try_reserve_exact(n).map_err(|_| Status::Internal)?;
    for (i, &parent) in parents.iter().enumerate() {
        if parent < 0 {
            roots.push(i as u32);
        } else {
            child_start[parent as usize + 1] += 1;
        }
    }
    for i in 0..n {
        child_start[i + 1] += child_start[i];
    }
    let mut cursor = Vec::new();
    cursor.try_reserve_exact(n).map_err(|_| Status::Internal)?;
    cursor.extend_from_slice(&child_start[..n]);
    let mut child_list = Vec::new();
    child_list
        .try_reserve_exact(n)
        .map_err(|_| Status::Internal)?;
    child_list.resize(n, 0_u32);
    for (i, &parent) in parents.iter().enumerate() {
        if parent >= 0 {
            let slot = &mut cursor[parent as usize];
            child_list[*slot as usize] = i as u32;
            *slot += 1;
        }
    }

    let mut level_order = Vec::new();
    level_order
        .try_reserve_exact(n)
        .map_err(|_| Status::Internal)?;
    let mut level_parents = Vec::new();
    level_parents
        .try_reserve_exact(n)
        .map_err(|_| Status::Internal)?;
    let mut level_depth = Vec::new();
    level_depth
        .try_reserve_exact(n)
        .map_err(|_| Status::Internal)?;
    let mut levels = Vec::new();
    levels.try_reserve_exact(n).map_err(|_| Status::Internal)?;

    // `frontier` holds the bones of the level being emitted; `next` collects their children.
    // Both are reused across levels, so no per-level allocation happens.
    let mut frontier = roots;
    let mut next = Vec::new();
    next.try_reserve_exact(n).map_err(|_| Status::Internal)?;
    let mut depth = 0_u32;
    while !frontier.is_empty() {
        let start = level_order.len();
        next.clear();
        for &bone in &frontier {
            level_order.push(bone);
            level_parents.push(parents[bone as usize]);
            level_depth.push(depth);
            let from = child_start[bone as usize] as usize;
            let to = child_start[bone as usize + 1] as usize;
            next.extend_from_slice(&child_list[from..to]);
        }
        levels.push(LevelSlice {
            start,
            len: frontier.len(),
        });
        std::mem::swap(&mut frontier, &mut next);
        depth = depth.checked_add(1).ok_or(Status::InvalidInput)?;
    }

    if level_order.len() != n {
        // Some bones were never reached: they are in, or downstream of, a cycle.
        return Err(Status::InvalidInput);
    }
    Ok((levels, level_order, level_parents, level_depth))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn compile(parents: &[i32]) -> Result<CompiledSkeleton, Status> {
        let pivots = vec![0.5_f32; parents.len() * 3];
        let glow = vec![0_u8; parents.len()];
        CompiledSkeleton::compile(parents, &pivots, &glow)
    }

    #[test]
    fn empty_skeleton_compiles_to_nothing() {
        let skeleton = compile(&[]).unwrap();
        assert_eq!(skeleton.bone_count(), 0);
        assert_eq!(skeleton.depth(), 0);
        assert!(skeleton.levels().is_empty());
        assert_eq!(skeleton.packed_bytes(), Some(0));
    }

    #[test]
    fn a_chain_becomes_one_bone_per_level() {
        let skeleton = compile(&[-1, 0, 1, 2]).unwrap();
        assert_eq!(skeleton.depth(), 4);
        assert_eq!(skeleton.level_order(), &[0, 1, 2, 3]);
        assert_eq!(skeleton.level_parents(), &[-1, 0, 1, 2]);
        for level in skeleton.levels() {
            assert_eq!(level.len, 1);
        }
    }

    #[test]
    fn a_star_becomes_one_root_level_and_one_wide_level() {
        let skeleton = compile(&[-1, 0, 0, 0, 0, 0, 0, 0, 0]).unwrap();
        assert_eq!(skeleton.depth(), 2);
        assert_eq!(skeleton.levels()[0], LevelSlice { start: 0, len: 1 });
        assert_eq!(skeleton.levels()[1], LevelSlice { start: 1, len: 8 });
    }

    #[test]
    fn every_parent_precedes_its_child_in_level_order() {
        // A deliberately irregular tree: mixed roots, a deep chain and wide fans.
        let parents = [-1, 0, 0, 1, 1, 3, 3, 3, 2, 8, -1, 10, 11, 5];
        let skeleton = compile(&parents).unwrap();
        let mut position = vec![usize::MAX; parents.len()];
        for (slot, &bone) in skeleton.level_order().iter().enumerate() {
            position[bone as usize] = slot;
        }
        for (bone, &parent) in parents.iter().enumerate() {
            if parent >= 0 {
                assert!(
                    position[parent as usize] < position[bone],
                    "parent {parent} must precede child {bone}"
                );
            }
        }
        assert_eq!(skeleton.level_order().len(), parents.len());
    }

    #[test]
    fn level_order_is_a_permutation() {
        let parents = [-1, 0, 1, 0, 3, 3, 2, 6];
        let skeleton = compile(&parents).unwrap();
        let mut seen = vec![false; parents.len()];
        for &bone in skeleton.level_order() {
            assert!(!seen[bone as usize], "bone {bone} appears twice");
            seen[bone as usize] = true;
        }
        assert!(seen.iter().all(|&s| s));
    }

    #[test]
    fn cycles_are_rejected() {
        // 0 -> 1 -> 2 -> 0, with no root at all.
        assert_eq!(compile(&[1, 2, 0]).unwrap_err(), Status::InvalidInput);
        // A cycle hanging off a valid root.
        assert_eq!(compile(&[-1, 0, 3, 2]).unwrap_err(), Status::InvalidInput);
        // A self-parent is rejected before the cycle check.
        assert_eq!(compile(&[0]).unwrap_err(), Status::InvalidInput);
    }

    #[test]
    fn out_of_range_and_malformed_inputs_are_rejected() {
        assert_eq!(compile(&[-2]).unwrap_err(), Status::InvalidInput);
        assert_eq!(compile(&[5]).unwrap_err(), Status::InvalidInput);
        assert_eq!(
            CompiledSkeleton::compile(&[-1], &[0., 0.], &[0]).unwrap_err(),
            Status::InvalidInput
        );
        assert_eq!(
            CompiledSkeleton::compile(&[-1], &[0., 0., 0.], &[0, 0]).unwrap_err(),
            Status::InvalidInput
        );
        assert_eq!(
            CompiledSkeleton::compile(&[-1], &[0., 0., 0.], &[2]).unwrap_err(),
            Status::InvalidInput
        );
        assert_eq!(
            CompiledSkeleton::compile(&[-1], &[f32::NAN, 0., 0.], &[0]).unwrap_err(),
            Status::NonFinite
        );
    }

    #[test]
    fn pivots_are_split_into_axis_planes() {
        let pivots = [1., 2., 3., 4., 5., 6.];
        let skeleton = CompiledSkeleton::compile(&[-1, 0], &pivots, &[0, 1]).unwrap();
        assert_eq!(skeleton.pivots_x(), &[1., 4.]);
        assert_eq!(skeleton.pivots_y(), &[2., 5.]);
        assert_eq!(skeleton.pivots_z(), &[3., 6.]);
        assert_eq!(skeleton.glow_mask(), &[0, 1]);
    }

    #[test]
    fn a_deep_chain_does_not_overflow_the_depth_counter() {
        // 4096 bones in one chain: depth 4096, well inside u16 but far past any real rig.
        let mut parents = vec![-1_i32];
        for i in 1..4096_i32 {
            parents.push(i - 1);
        }
        let skeleton = compile(&parents).unwrap();
        assert_eq!(skeleton.depth(), 4096);
        assert_eq!(skeleton.level_order().len(), 4096);
    }
}
