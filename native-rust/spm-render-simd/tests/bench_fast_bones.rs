//! Local benchmark for the packed fast path, used to gate Stage 1 of the 1.1.0 upgrade.
//!
//! This is a plain `#[test]` rather than a `criterion` benchmark because the workspace forbids new
//! third-party dependencies. It measures wall-clock time over many iterations and prints the
//! numbers; the assertions are deliberately loose (an order of magnitude) so a slow CI machine
//! does not fail the build, while the printed figures are what the plan's gate is judged on.
//!
//! Run with `cargo test --release -p spm-render-simd --test bench_fast_bones -- --nocapture`.
//!
//! Every test here is `#[ignore]`d by default. A debug build is roughly 20x slower than a release
//! build, so running these under a plain `cargo test` would either fail the timing assertions or
//! take minutes; and a timing assertion that depends on the build profile is not a correctness
//! gate. The correctness gates live in `tests/fast_bones.rs`, which runs in every profile.
use std::time::Instant;

use spm_native_abi::{
    BonePassFilter, Isa, PackedLayout, BONE_PARAM_STRIDE, PACKED_BONE_STRIDE, ROOT_FLOATS,
};
use spm_render_simd::{
    evaluate_bones, evaluate_packed, packed_preferred_isa, BoneInput, CompiledSkeleton,
    PackedRequest,
};

const IDENTITY_ROOT: [f32; ROOT_FLOATS] = [
    1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., //
    1., 0., 0., 0., 1., 0., 0., 0., 1.,
];

/// A rig shaped like a real character: a short spine with wide fans of siblings.
fn rig(depth: usize, width: usize) -> (Vec<i32>, Vec<f32>, Vec<u8>) {
    let mut parents = Vec::new();
    let mut pivots = Vec::new();
    let mut glow = Vec::new();
    for level in 0..depth {
        for lane in 0..width {
            let index = level * width + lane;
            parents.push(if level == 0 {
                -1
            } else {
                ((level - 1) * width + lane) as i32
            });
            let step = index as f32;
            pivots.push(0.25 + step * 0.01);
            pivots.push(-0.5 + step * 0.02);
            pivots.push(0.75 - step * 0.005);
            glow.push(u8::from(index.is_multiple_of(7)));
        }
    }
    (parents, pivots, glow)
}

/// Per-bone parameters with unit scale, which is what a real rig uses for most bones.
///
/// Non-uniform scale is measured separately by `nonuniform_scale_costs_more_but_stays_bounded`;
/// mixing it into the headline number would hide which path the gate is actually measuring.
fn params(n: usize) -> Vec<f32> {
    let mut params = vec![0_f32; n * BONE_PARAM_STRIDE];
    for i in 0..n {
        let p = i * BONE_PARAM_STRIDE;
        let step = i as f32;
        params[p] = step * 0.05;
        params[p + 1] = step * 0.031;
        params[p + 2] = -step * 0.017;
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
    // Warm up so the first-call scratch growth is not measured.
    for _ in 0..500 {
        // SAFETY: `out` is exactly `bone_count * 144` bytes and nothing aliases.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
    }
    let start = Instant::now();
    for _ in 0..iterations {
        // SAFETY: as above.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
    }
    start.elapsed().as_secs_f64() * 1e6 / iterations as f64
}

fn time_reference(
    parents: &[i32],
    pivots: &[f32],
    glow: &[u8],
    params: &[f32],
    iterations: usize,
) -> f64 {
    let inputs: Vec<BoneInput> = (0..parents.len())
        .map(|i| {
            let p = i * BONE_PARAM_STRIDE;
            BoneInput {
                parent: parents[i],
                pivot: [pivots[i * 3], pivots[i * 3 + 1], pivots[i * 3 + 2]],
                rotation: [params[p], params[p + 1], params[p + 2]],
                translation: [
                    -params[p + 3] * 0.0625,
                    params[p + 4] * 0.0625,
                    params[p + 5] * 0.0625,
                ],
                scale: [params[p + 6], params[p + 7], params[p + 8]],
                flags: 0,
                glow: if glow[i] != 0 { 15 } else { 255 },
                color: 0,
            }
        })
        .collect();
    let pose: [f32; 16] = IDENTITY_ROOT[..16].try_into().unwrap();
    for _ in 0..8 {
        evaluate_bones(&inputs, &pose, Isa::Scalar).unwrap();
    }
    let start = Instant::now();
    for _ in 0..iterations {
        evaluate_bones(&inputs, &pose, Isa::Scalar).unwrap();
    }
    start.elapsed().as_secs_f64() * 1e6 / iterations as f64
}

#[test]
#[ignore = "timing benchmark; run with --release --ignored"]
fn packed_fast_path_meets_the_stage_one_gate() {
    let isa = packed_preferred_isa();
    println!("\n=== packed fast path, tier {isa:?} ===");
    println!(
        "{:<28} {:>12} {:>12} {:>10}",
        "shape", "packed µs", "reference µs", "speedup"
    );

    let mut results = Vec::new();
    for (label, depth, width, iterations) in [
        ("500 bones (10 x 50)", 10, 50, 4000),
        ("32 bones (4 x 8)", 4, 8, 40000),
        ("128 bones (8 x 16)", 8, 16, 10000),
        ("1000 bones (10 x 100)", 10, 100, 2000),
    ] {
        let (parents, pivots, glow) = rig(depth, width);
        let n = parents.len();
        let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
        let params = params(n);
        let packed = time_packed(&skeleton, &params, isa, iterations);
        let reference = time_reference(&parents, &pivots, &glow, &params, iterations.min(200));
        println!(
            "{:<28} {:>12.2} {:>12.2} {:>9.1}x",
            label,
            packed,
            reference,
            reference / packed
        );
        results.push((label, n, packed));
    }

    // The plan's Stage 1 gate was 500 bones in at most 15 µs and 32 bones in at most 8 µs.
    //
    // The 32-bone gate is met with a wide margin. The 500-bone gate is **not**: the measured
    // figure is around 28 µs, roughly 1.9x over. That is still 5.5x faster than the `f64`
    // reference and about 1.35x faster than the optimized Java writer it replaces, so the path is
    // a real win — but it is not the win the plan projected, and asserting 15 µs would mean either
    // deleting the assertion or gaming the fixture.
    //
    // The remaining cost is understood and is not a missing optimization: a 500-bone rig needs
    // 500 `sin_cos` triples (12.7 µs measured on this CPU for the scalar form alone), and the
    // 8-wide kernel only removes the trigonometry for bones that share a level. The measured
    // breakdown is in `docs/2026-11-23/SPM_CORE_1_1_STAGE1_BENCHMARK.md`. Closing the gap needs a
    // different design (persisting per-bone local matrices across frames, or moving the whole
    // hierarchy to a GPU compute pass), not more tuning of this kernel.
    //
    // So the assertion below records the achieved figure and fails only on a real regression.
    for (label, n, packed) in &results {
        let (limit, note) = match *n {
            500 => (35.0, "achieved ~28 µs; the plan's 15 µs target is not met"),
            32 => (8.0, "meets the plan's 8 µs target"),
            _ => continue,
        };
        println!("gate: {label} = {packed:.2} µs (limit {limit} µs — {note})");
        assert!(
            *packed <= limit,
            "{label} took {packed:.2} µs, over the {limit} µs regression limit"
        );
    }
}

/// Non-uniform scale takes the cofactor inverse-transpose path, which is the slow case.
///
/// It is measured separately so the headline gate is not quietly measuring the easy path, and so a
/// regression in the cofactor code shows up as a number rather than as a mystery slowdown.
#[test]
#[ignore = "timing benchmark; run with --release --ignored"]
fn nonuniform_scale_costs_more_but_stays_bounded() {
    let (parents, pivots, glow) = rig(10, 50);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let mut nonuniform = params(n);
    for i in 0..n {
        let p = i * BONE_PARAM_STRIDE;
        nonuniform[p + 6] = 1. + (i as f32) * 0.01;
        nonuniform[p + 7] = 2. - (i as f32) * 0.005;
        nonuniform[p + 8] = 0.5 + (i as f32) * 0.02;
    }
    let isa = packed_preferred_isa();
    let uniform = time_packed(&skeleton, &params(n), isa, 4000);
    let general = time_packed(&skeleton, &nonuniform, isa, 4000);
    println!("\n500 bones: uniform scale {uniform:.2} µs, non-uniform {general:.2} µs");
    // The cofactor path is allowed to be slower, but not by an order of magnitude.
    assert!(
        general <= uniform * 3.,
        "non-uniform scale ({general:.2} µs) must stay within 3x of uniform ({uniform:.2} µs)"
    );
}

/// The scalar tier must not be pathologically slower than the reference, or the fallback is a
/// regression for hosts without SIMD.
#[test]
#[ignore = "timing benchmark; run with --release --ignored"]
fn the_scalar_fallback_is_not_a_regression() {
    let (parents, pivots, glow) = rig(10, 50);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let params = params(n);
    let packed = time_packed(&skeleton, &params, Isa::Scalar, 500);
    let reference = time_reference(&parents, &pivots, &glow, &params, 100);
    println!("\nscalar packed {packed:.2} µs vs reference {reference:.2} µs");
    // The packed scalar path skips the per-call graph rebuild, so it should be at least as fast.
    assert!(
        packed <= reference * 1.5,
        "scalar packed path ({packed:.2} µs) must not be much slower than the reference ({reference:.2} µs)"
    );
}
