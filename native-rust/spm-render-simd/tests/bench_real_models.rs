//! Benchmark against the real community models in `D:\SparkleMorpher\TestModel`.
//!
//! The synthetic fixtures in `bench_fast_bones.rs` assumed a 10-level, 50-wide rig. Measuring the
//! actual models showed that assumption was wrong: the real rigs are 11–26 levels deep with a mean
//! level width of 8.4 bones, and the largest is 773 bones. A benchmark built on the wrong shape
//! answers the wrong question, so this one reads the skeletons the Java harness dumped.
//!
//! The skeleton files live under `integration-tests/build/real-skeletons/` and are produced by
//! `RealModelHierarchyTest.dumpRealSkeletonsForBenchmark`. When they are absent this test reports
//! that and does nothing, so a checkout without the models still builds.
//!
//! Run with:
//! `cargo test --release -p spm-render-simd --test bench_real_models -- --ignored --nocapture`
use std::hint::black_box;
use std::path::{Path, PathBuf};
use std::time::Instant;

use spm_native_abi::{
    BonePassFilter, Isa, PackedLayout, Status, BONE_PARAM_STRIDE, PACKED_BONE_STRIDE, ROOT_FLOATS,
};
use spm_render_simd::{
    evaluate_packed, packed_preferred_isa, CompiledSkeleton, PackedPath, PackedRequest,
};

const IDENTITY_ROOT: [f32; ROOT_FLOATS] = [
    1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., //
    1., 0., 0., 0., 1., 0., 0., 0., 1.,
];

/// One real skeleton, as dumped by the Java harness.
struct RealSkeleton {
    name: String,
    parents: Vec<i32>,
    pivots: Vec<f32>,
    depth: usize,
}

impl RealSkeleton {
    fn bone_count(&self) -> usize {
        self.parents.len()
    }

    /// Mean number of bones per tree level, which is what decides whether the 8-wide kernel can be
    /// fed from siblings alone.
    fn mean_level_width(&self) -> f64 {
        if self.depth == 0 {
            return 0.;
        }
        self.bone_count() as f64 / self.depth as f64
    }
}

/// Locates the dumped skeletons, walking up from the crate directory.
fn skeleton_dir() -> Option<PathBuf> {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    for _ in 0..4 {
        let candidate = dir.join("integration-tests/build/real-skeletons");
        if candidate.is_dir() {
            return Some(candidate);
        }
        dir = dir.parent()?.to_path_buf();
    }
    None
}

fn load(path: &Path) -> Option<RealSkeleton> {
    let text = std::fs::read_to_string(path).ok()?;
    let mut lines = text.lines();
    let header: Vec<&str> = lines.next()?.split_whitespace().collect();
    let bones: usize = header.first()?.parse().ok()?;
    let depth: usize = header.get(1)?.parse().ok()?;
    let mut parents = Vec::with_capacity(bones);
    let mut pivots = Vec::with_capacity(bones * 3);
    for line in lines {
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() != 4 {
            continue;
        }
        parents.push(fields[0].parse().ok()?);
        for field in &fields[1..4] {
            pivots.push(field.parse().ok()?);
        }
    }
    if parents.len() != bones || pivots.len() != bones * 3 {
        return None;
    }
    Some(RealSkeleton {
        name: path
            .file_name()?
            .to_string_lossy()
            .trim_end_matches(".skeleton.txt")
            .to_string(),
        parents,
        pivots,
        depth,
    })
}

/// Per-bone parameters with unit scale and a rotation that varies per bone.
fn params(n: usize, rotating: bool) -> Vec<f32> {
    let mut params = vec![0_f32; n * BONE_PARAM_STRIDE];
    for i in 0..n {
        let p = i * BONE_PARAM_STRIDE;
        let step = i as f32;
        if rotating {
            params[p] = step * 0.05;
            params[p + 1] = step * 0.031;
            params[p + 2] = -step * 0.017;
        }
        params[p + 3] = step * 0.1 - 1.;
        params[p + 4] = 0.5;
        params[p + 5] = -step * 0.05;
        params[p + 6] = 1.;
        params[p + 7] = 1.;
        params[p + 8] = 1.;
        params[p + 11] = if i % 4 == 0 { 1. } else { 0. };
    }
    params
}

fn time_packed(skeleton: &CompiledSkeleton, params: &[f32], isa: Isa, iterations: usize) -> f64 {
    let mut out = vec![0_u8; skeleton.bone_count() * PACKED_BONE_STRIDE];
    let request = PackedRequest {
        skeleton,
        root: &IDENTITY_ROOT,
        params,
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0x00F0_00F0,
        full_bright_light: 0x00F0_00F0,
        isa,
        persistent_output: false,
        path: None,
    };
    for _ in 0..500 {
        // SAFETY: `out` is exactly `bone_count * 144` bytes and nothing aliases.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
    }
    let start = Instant::now();
    for _ in 0..iterations {
        // SAFETY: as above.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
    }
    black_box(&out);
    start.elapsed().as_secs_f64() * 1e6 / iterations as f64
}

/// Which bones an animation moves, which decides how much of the tree design C must recompute.
#[derive(Clone, Copy, Debug)]
enum Motion {
    /// Nothing moves: every local matrix is cached and the frame is pure composition.
    Static,
    /// Only leaves move — facial bones, props, cloth tips. Their subtrees are empty, so the dirty
    /// set stays exactly as large as the moving set.
    Leaves,
    /// Bones spread evenly through the tree, one in every `n`. A moderate, realistic mix.
    Spread(usize),
    /// A root chain moves, which is the worst case: every descendant is dragged dirty.
    RootChain,
    /// One limb moves: a single subtree, which is what a real animation clip usually drives.
    OneSubtree,
    /// Everything moves, which is the pathological case design C cannot beat design A on.
    Everything,
}

/// Child lists for a skeleton, so motions can walk the tree.
fn children_of(skeleton: &CompiledSkeleton) -> (Vec<bool>, Vec<usize>, Vec<usize>) {
    let n = skeleton.bone_count();
    let mut has_child = vec![false; n];
    let mut first_child = vec![usize::MAX; n];
    let mut next_sibling = vec![usize::MAX; n];
    for (bone, &parent) in skeleton.parents().iter().enumerate() {
        if parent >= 0 {
            has_child[parent as usize] = true;
            // Prepend, so the list is built without a second pass.
            next_sibling[bone] = first_child[parent as usize];
            first_child[parent as usize] = bone;
        }
    }
    (has_child, first_child, next_sibling)
}

impl Motion {
    /// The bone indices this motion moves.
    fn bones(self, skeleton: &CompiledSkeleton) -> Vec<usize> {
        let n = skeleton.bone_count();
        match self {
            Self::Static => Vec::new(),
            Self::Everything => (0..n).collect(),
            Self::Spread(step) => (0..n).step_by(step.max(1)).collect(),
            Self::Leaves => {
                let (has_child, _, _) = children_of(skeleton);
                (0..n).filter(|&i| !has_child[i]).collect()
            }
            Self::RootChain => {
                let (_, first_child, _) = children_of(skeleton);
                // Start at a real root: a bone with no parent.
                let mut chain = Vec::new();
                let mut current = (0..n).find(|&i| skeleton.parents()[i] < 0);
                while let Some(bone) = current {
                    chain.push(bone);
                    let next = first_child[bone];
                    current = if next == usize::MAX { None } else { Some(next) };
                }
                chain
            }
            Self::OneSubtree => {
                // Pick the bone nearest a third of the way down the tree and move its whole
                // subtree. That is the shape of "one limb is animating".
                let (_, first_child, next_sibling) = children_of(skeleton);
                let mut depth = vec![0_usize; n];
                for &bone in skeleton.level_order() {
                    let bone = bone as usize;
                    let parent = skeleton.parents()[bone];
                    depth[bone] = if parent < 0 {
                        0
                    } else {
                        depth[parent as usize] + 1
                    };
                }
                let target_depth = depth.iter().copied().max().unwrap_or(0) / 3;
                let start =
                    (0..n).find(|&i| depth[i] == target_depth && first_child[i] != usize::MAX);
                let Some(start) = start else {
                    return Vec::new();
                };
                let mut subtree = Vec::new();
                let mut stack = vec![start];
                while let Some(bone) = stack.pop() {
                    subtree.push(bone);
                    let mut child = first_child[bone];
                    while child != usize::MAX {
                        stack.push(child);
                        child = next_sibling[child];
                    }
                }
                subtree
            }
        }
    }
}

/// Times a frame in which `motion` describes which bones actually moved.
///
/// This is the number that matters for design C. Timing a static parameter array measures the
/// best case — every local matrix is cached and the frame is pure composition — which is real but
/// not representative. Real animation moves a subset of bones each frame, and the cost scales with
/// how much of the tree that subset drags along.
fn time_packed_animated(
    skeleton: &CompiledSkeleton,
    base: &[f32],
    motion: Motion,
    isa: Isa,
    iterations: usize,
    persistent: bool,
) -> f64 {
    time_packed_animated_path(skeleton, base, motion, isa, iterations, persistent, None)
}

/// As [`time_packed_animated`], but able to pin the kernel so the two can be compared directly.
///
/// The warm-up runs with the *same* pinned kernel as the measurement. That matters: the evaluator
/// keeps per-thread state, and a warm-up on the other kernel would leave a cache the measured one
/// then has to rebuild — which would charge the rebuild to whichever kernel ran second and make
/// the comparison meaningless.
fn time_packed_animated_path(
    skeleton: &CompiledSkeleton,
    base: &[f32],
    motion: Motion,
    isa: Isa,
    iterations: usize,
    persistent: bool,
    path: Option<PackedPath>,
) -> f64 {
    let n = skeleton.bone_count();
    let mut params = base.to_vec();
    let mut out = vec![0_u8; n * PACKED_BONE_STRIDE];
    let moving = motion.bones(skeleton);
    let mut frame = 0_u32;
    let step = |params: &mut [f32], frame: u32| {
        for &bone in &moving {
            let p = bone * BONE_PARAM_STRIDE;
            // A small, continuous change: exactly what an animation curve produces.
            params[p] += 0.001 * (frame as f32).sin();
            params[p + 1] += 0.001 * (frame as f32 * 0.7).cos();
        }
    };
    for _ in 0..200 {
        frame = frame.wrapping_add(1);
        step(&mut params, frame);
        let request = PackedRequest {
            skeleton,
            root: &IDENTITY_ROOT,
            params: &params,
            layout: PackedLayout::FloatTail,
            pass: BonePassFilter::All,
            packed_light: 0x00F0_00F0,
            full_bright_light: 0x00F0_00F0,
            isa,
            persistent_output: persistent,
            path,
        };
        // SAFETY: `out` is exactly `n * 144` bytes and nothing aliases.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
    }
    let start = Instant::now();
    for _ in 0..iterations {
        frame = frame.wrapping_add(1);
        step(&mut params, frame);
        let request = PackedRequest {
            skeleton,
            root: &IDENTITY_ROOT,
            params: &params,
            layout: PackedLayout::FloatTail,
            pass: BonePassFilter::All,
            packed_light: 0x00F0_00F0,
            full_bright_light: 0x00F0_00F0,
            isa,
            persistent_output: persistent,
            path,
        };
        // SAFETY: as above.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
    }
    black_box(&out);
    start.elapsed().as_secs_f64() * 1e6 / iterations as f64
}

#[test]
#[ignore = "timing benchmark; run with --release --ignored"]
fn real_models_measure_the_current_kernel() {
    let Some(dir) = skeleton_dir() else {
        println!("[real] no dumped skeletons found; run the Java harness first");
        return;
    };
    let mut paths: Vec<PathBuf> = std::fs::read_dir(&dir)
        .expect("skeleton directory must be readable")
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|path| path.to_string_lossy().ends_with(".skeleton.txt"))
        .collect();
    paths.sort();
    if paths.is_empty() {
        println!("[real] no skeletons in {}", dir.display());
        return;
    }

    let isa = packed_preferred_isa();
    println!("\n=== real models, tier {isa:?} ===");
    println!(
        "{:<30} {:>5} {:>5} {:>6} {:>8} {:>8} {:>8} {:>8} {:>8} {:>8}",
        "model", "bones", "depth", "width", "static", "leaves", "1/10", "limb", "chain", "all"
    );
    let mut rows = Vec::new();
    for path in &paths {
        let Some(real) = load(path) else {
            println!("[real] could not parse {}", path.display());
            continue;
        };
        let glow = vec![0_u8; real.bone_count()];
        let skeleton = match CompiledSkeleton::compile(&real.parents, &real.pivots, &glow) {
            Ok(skeleton) => skeleton,
            Err(status) => {
                println!("[real] {} failed to compile: {status:?}", real.name);
                continue;
            }
        };
        // Iterations scale down with bone count so the whole run stays a few seconds.
        let iterations = (1_000_000 / real.bone_count().max(1)).clamp(300, 20_000);
        let base = params(real.bone_count(), true);
        let mut times = Vec::new();
        for motion in [
            Motion::Static,
            Motion::Leaves,
            Motion::Spread(10),
            Motion::OneSubtree,
            Motion::RootChain,
            Motion::Everything,
        ] {
            // The host's real situation: `mesh.perFrameBoneBuffer` persists across frames, so a
            // frame with no movement can leave every record untouched.
            times.push(time_packed_animated(
                &skeleton, &base, motion, isa, iterations, true,
            ));
        }
        println!(
            "{:<30} {:>5} {:>5} {:>6.1} {:>8.2} {:>8.2} {:>8.2} {:>8.2} {:>8.2} {:>8.2}",
            real.name,
            real.bone_count(),
            real.depth,
            real.mean_level_width(),
            times[0],
            times[1],
            times[2],
            times[3],
            times[4],
            times[5]
        );
        rows.push((real.name.clone(), real.bone_count(), times));
    }

    println!();
    println!("=== against the plan's 15 µs gate (500+ bones) ===");
    for (name, bones, times) in &rows {
        if *bones < 500 {
            continue;
        }
        println!(
            "{name}: {bones} bones — static {:.2}, leaves {:.2}, 1/10 {:.2}, limb {:.2}, chain {:.2}, all {:.2} µs",
            times[0], times[1], times[2], times[3], times[4], times[5]
        );
    }
    assert!(!rows.is_empty(), "at least one real model must be measured");
}

/// The real rigs are much deeper than the synthetic fixture assumed, which changes which tier wins.
///
/// This records the comparison rather than asserting a winner: the point is that the choice is
/// measured on real shapes, not on a fixture that happened to suit one kernel.
#[test]
#[ignore = "timing benchmark; run with --release --ignored"]
fn real_models_compare_tiers() {
    let Some(dir) = skeleton_dir() else {
        println!("[real] no dumped skeletons found; run the Java harness first");
        return;
    };
    let mut paths: Vec<PathBuf> = std::fs::read_dir(&dir)
        .expect("skeleton directory must be readable")
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|path| path.to_string_lossy().ends_with(".skeleton.txt"))
        .collect();
    paths.sort();
    // Only the two largest rigs, to keep the run short.
    let mut loaded: Vec<RealSkeleton> = paths.iter().filter_map(|p| load(p)).collect();
    loaded.sort_by_key(|real| std::cmp::Reverse(real.bone_count()));
    loaded.truncate(2);

    println!("\n=== tier comparison on the largest real rigs ===");
    for real in &loaded {
        let glow = vec![0_u8; real.bone_count()];
        let skeleton = CompiledSkeleton::compile(&real.parents, &real.pivots, &glow).unwrap();
        let params = params(real.bone_count(), true);
        let iterations = (1_000_000 / real.bone_count().max(1)).clamp(300, 20_000);
        println!(
            "\n{} ({} bones, {} levels, {:.1} wide)",
            real.name,
            real.bone_count(),
            real.depth,
            real.mean_level_width()
        );
        for isa in [Isa::Scalar, Isa::Avx2, Isa::Avx2Fma] {
            if !spm_render_simd::supported(isa) {
                continue;
            }
            let us = time_packed(&skeleton, &params, isa, iterations);
            println!("  {isa:?}: {us:.2} µs");
        }
    }
}

/// A skeleton the compiler rejects must not be silently accepted.
#[test]
fn a_cyclic_real_skeleton_is_rejected() {
    // Two bones pointing at each other, which no real model has but a corrupt file could.
    let status = CompiledSkeleton::compile(&[1, 0], &[0.; 6], &[0, 0]).unwrap_err();
    assert_eq!(status, Status::InvalidInput);
}

/// Measures both kernels on identical frames, which is what places the crossover.
///
/// The automatic choice is only as good as the ratio it is given, and a ratio can only be trusted
/// if the two kernels have been compared on the same work. This pins each kernel in turn over a
/// sweep of dirty ratios, so the crossover is read off the data instead of assumed. The `dirty`
/// column is the fraction of bones the detection pass marked subtree-dirty — the quantity the
/// switch actually keys on — not the fraction that moved.
#[test]
#[ignore = "timing benchmark; run with --release --ignored"]
fn design_c_crossover_on_real_rigs() {
    let Some(dir) = skeleton_dir() else {
        println!("[real] no dumped skeletons found; run the Java harness first");
        return;
    };
    let mut paths: Vec<PathBuf> = std::fs::read_dir(&dir)
        .expect("skeleton directory must be readable")
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|path| path.to_string_lossy().ends_with(".skeleton.txt"))
        .collect();
    paths.sort();
    let mut loaded: Vec<RealSkeleton> = paths.iter().filter_map(|p| load(p)).collect();
    loaded.sort_by_key(|real| std::cmp::Reverse(real.bone_count()));
    loaded.truncate(2);

    let isa = packed_preferred_isa();
    for real in &loaded {
        let glow = vec![0_u8; real.bone_count()];
        let skeleton = CompiledSkeleton::compile(&real.parents, &real.pivots, &glow).unwrap();
        let base = params(real.bone_count(), true);
        let iterations = (600_000 / real.bone_count().max(1)).clamp(200, 10_000);
        println!(
            "\n{} ({} bones, {} levels)",
            real.name,
            real.bone_count(),
            real.depth
        );
        println!(
            "  {:<10} {:>8} {:>10} {:>10} {:>8}",
            "moving", "dirty", "cached µs", "uncached µs", "winner"
        );
        for step in [0_usize, 100, 50, 20, 10, 5, 2, 1] {
            let motion = if step == 0 {
                Motion::Static
            } else {
                Motion::Spread(step)
            };
            let cached = time_packed_animated_path(
                &skeleton,
                &base,
                motion,
                isa,
                iterations,
                true,
                Some(PackedPath::Cached),
            );
            let uncached = time_packed_animated_path(
                &skeleton,
                &base,
                motion,
                isa,
                iterations,
                true,
                Some(PackedPath::Uncached),
            );
            let dirty = dirty_ratio(&skeleton, &base, motion);
            let label = if step == 0 {
                "none".to_string()
            } else {
                format!("1/{step}")
            };
            let winner = if cached <= uncached {
                "cached"
            } else {
                "uncached"
            };
            println!("  {label:<10} {dirty:>7.1}% {cached:>10.2} {uncached:>10.2} {winner:>8}");
        }
    }
}

/// The fraction of bones the detection pass marks subtree-dirty for `motion`.
///
/// This is measured by running the real evaluator and reading back what it decided, rather than
/// re-deriving the propagation here: a second implementation could disagree with the kernel, and
/// then the crossover would be placed against a number the kernel never sees.
fn dirty_ratio(skeleton: &CompiledSkeleton, base: &[f32], motion: Motion) -> f64 {
    let n = skeleton.bone_count();
    if n == 0 {
        return 0.;
    }
    let mut params = base.to_vec();
    let mut out = vec![0_u8; n * PACKED_BONE_STRIDE];
    let moving = motion.bones(skeleton);
    // One frame to establish the mirror, then one frame that moves `motion`'s bones.
    for frame in 0..2_u32 {
        if frame == 1 {
            for &bone in &moving {
                let p = bone * BONE_PARAM_STRIDE;
                params[p] += 0.001;
            }
        }
        let request = PackedRequest {
            skeleton,
            root: &IDENTITY_ROOT,
            params: &params,
            layout: PackedLayout::FloatTail,
            pass: BonePassFilter::All,
            packed_light: 0x00F0_00F0,
            full_bright_light: 0x00F0_00F0,
            isa: packed_preferred_isa(),
            persistent_output: true,
            path: None,
        };
        // SAFETY: `out` is exactly `n * 144` bytes and nothing aliases.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
    }
    // The evaluator reports the ratio it acted on, so no second derivation is needed.
    f64::from(spm_render_simd::last_moved_ratio().unwrap_or(0.)) * 100.
}
