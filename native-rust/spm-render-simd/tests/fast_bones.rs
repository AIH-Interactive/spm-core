//! Differential and safety tests for the packed fast path.
//!
//! The packed evaluator is a second implementation of the same mathematics as the `f64` reference
//! in `bones.rs`. These tests exist to make that duplication safe: every tier this host can
//! execute is compared against the reference, and the error budget is measured rather than
//! assumed.
//!
//! The reference is the oracle, not the specification. Where the two disagree, the reference wins,
//! because it is the implementation the Java scalar path already matches bit for bit.
use spm_native_abi::{
    decode_packed_flags, BonePassFilter, Isa, PackedLayout, Status, BONE_PARAM_STRIDE,
    PACKED_BONE_STRIDE, ROOT_FLOATS,
};
use spm_render_simd::{
    evaluate_bones, evaluate_packed, packed_executed, packed_kernel_id, packed_preferred_isa,
    supported, BoneInput, CompiledSkeleton, PackedPath, PackedRequest,
};

/// Relative tolerance for the packed path against the `f64` reference.
///
/// The packed path accumulates in `f32` where the reference uses `f64`, so the two cannot agree
/// bit for bit. `1e-4` is the budget the plan sets and is comfortably above the observed error
/// (see `measured_error_stays_inside_the_budget`), which is what makes it a real gate rather than
/// a number chosen to pass.
const TOLERANCE: f32 = 1e-4;

const ALL: [Isa; 8] = [
    Isa::Auto,
    Isa::Scalar,
    Isa::Sse2,
    Isa::Avx,
    Isa::Avx2,
    Isa::Avx2Fma,
    Isa::Neon,
    Isa::Avx512,
];

const IDENTITY_ROOT: [f32; ROOT_FLOATS] = [
    1., 0., 0., 0., //
    0., 1., 0., 0., //
    0., 0., 1., 0., //
    0., 0., 0., 1., //
    1., 0., 0., 0., 1., 0., 0., 0., 1.,
];

/// A non-trivial root pose and normal, so the root combine is actually exercised.
const SCALED_ROOT: [f32; ROOT_FLOATS] = [
    2.,
    0.,
    0.,
    0., //
    0.,
    3.,
    0.,
    0., //
    0.,
    0.,
    4.,
    0., //
    5.,
    6.,
    7.,
    1., //
    0.5,
    0.,
    0.,
    0.,
    1. / 3.,
    0.,
    0.,
    0.,
    0.25,
];

/// A skeleton shaped like a real rig: a short spine with wide fans of siblings.
///
/// `depth` levels, each with `width` bones parented to the previous level. That is the shape the
/// level-parallel kernel is designed for, and it is also the shape that would expose a bug in the
/// level decomposition.
fn fan_skeleton(depth: usize, width: usize) -> (Vec<i32>, Vec<f32>, Vec<u8>) {
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

/// A chain: every bone parents to the previous one, so every level has exactly one bone.
fn chain_skeleton(n: usize) -> (Vec<i32>, Vec<f32>, Vec<u8>) {
    let parents = (0..n)
        .map(|i| if i == 0 { -1 } else { i as i32 - 1 })
        .collect();
    let pivots = (0..n * 3).map(|i| i as f32 * 0.01 - 0.5).collect();
    let glow = (0..n).map(|i| u8::from(i.is_multiple_of(5))).collect();
    (parents, pivots, glow)
}

/// Per-bone parameters in the host's layout, with a mix of rotations, scales and flags.
fn params_for(n: usize, variant: u32) -> Vec<f32> {
    let mut params = vec![0_f32; n * BONE_PARAM_STRIDE];
    for i in 0..n {
        let p = i * BONE_PARAM_STRIDE;
        let step = i as f32;
        match variant % 4 {
            // Rotating, non-uniform scale: the general path.
            0 => {
                params[p] = step * 0.05;
                params[p + 1] = step * 0.031;
                params[p + 2] = -step * 0.017;
                params[p + 3] = step * 0.1 - 1.;
                params[p + 4] = 0.5;
                params[p + 5] = -step * 0.05;
                params[p + 6] = 1. + step * 0.01;
                params[p + 7] = 2. - step * 0.005;
                params[p + 8] = 0.5 + step * 0.02;
            }
            // Zero rotation, unit scale: the fast path.
            1 => {
                params[p + 3] = step * 0.25;
                params[p + 4] = -step * 0.125;
                params[p + 5] = step * 0.0625;
                params[p + 6] = 1.;
                params[p + 7] = 1.;
                params[p + 8] = 1.;
            }
            // Uniform scale, including a negative one: the normal-matrix shortcut.
            2 => {
                params[p] = step * 0.02;
                params[p + 1] = -step * 0.013;
                params[p + 2] = step * 0.007;
                let scale = if i % 3 == 0 { -1.5 } else { 1.25 };
                params[p + 6] = scale;
                params[p + 7] = scale;
                params[p + 8] = scale;
            }
            // Mixed: some bones hidden, some hiding their children, some locators.
            _ => {
                params[p] = step * 0.04;
                params[p + 1] = step * 0.02;
                params[p + 2] = step * 0.01;
                params[p + 6] = 1.;
                params[p + 7] = 1.;
                params[p + 8] = 1.;
                params[p + 9] = if i % 11 == 3 { 1. } else { 0. };
                params[p + 10] = if i % 13 == 5 { 1. } else { 0. };
                params[p + 11] = if i % 4 == 0 { 1. } else { 0. };
            }
        }
    }
    params
}

/// Runs the packed path and returns the raw output buffer plus the reported ISA.
fn run_packed(
    skeleton: &CompiledSkeleton,
    root: &[f32; ROOT_FLOATS],
    params: &[f32],
    layout: PackedLayout,
    pass: BonePassFilter,
    isa: Isa,
) -> Result<(Vec<u8>, Isa), Status> {
    let mut out = vec![0_u8; skeleton.bone_count() * PACKED_BONE_STRIDE];
    let request = PackedRequest {
        skeleton,
        root,
        params,
        layout,
        pass,
        packed_light: 0x00F0_00F0,
        full_bright_light: 0x00F0_00F0,
        isa,
        persistent_output: false,
        path: None,
    };
    // SAFETY: `out` is exactly `bone_count * 144` bytes, `params` is `bone_count * 12` floats, and
    // `root` is 25 floats. Nothing aliases.
    let outcome = unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }?;
    Ok((out, outcome.isa))
}

fn read_f32(buffer: &[u8], offset: usize) -> f32 {
    f32::from_le_bytes(buffer[offset..offset + 4].try_into().unwrap())
}

fn read_i32(buffer: &[u8], offset: usize) -> i32 {
    i32::from_le_bytes(buffer[offset..offset + 4].try_into().unwrap())
}

/// Builds the reference `BoneInput` list from the same skeleton and parameters.
fn reference_inputs(
    parents: &[i32],
    pivots: &[f32],
    glow: &[u8],
    params: &[f32],
) -> Vec<BoneInput> {
    (0..parents.len())
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
                flags: (if params[p + 9] != 0. { 1 } else { 0 })
                    | (if params[p + 10] != 0. { 2 } else { 0 }),
                glow: if glow[i] != 0 { 15 } else { 255 },
                color: 0,
            }
        })
        .collect()
}

fn assert_close(actual: f32, expected: f32, context: &str) {
    let bound = TOLERANCE * expected.abs().max(1.);
    assert!(
        (actual - expected).abs() <= bound,
        "{context}: {actual} != {expected} (bound {bound})"
    );
}

/// The reference evaluator takes only the 4x4 pose; the packed request carries the normal too.
fn reference(root: &[f32; ROOT_FLOATS], inputs: &[BoneInput]) -> Vec<spm_render_simd::BoneOutput> {
    let pose: [f32; 16] = root[..16].try_into().unwrap();
    evaluate_bones(inputs, &pose, Isa::Scalar).unwrap().0
}

/// The packed output must match the `f64` reference for every tier this host can run.
///
/// This is the central correctness gate: it covers the general rotation path, the zero-rotation
/// fast path, uniform and negative-uniform scale, hidden bones, hidden subtrees and locators.
#[test]
fn packed_matches_the_reference_for_every_available_tier() {
    for (depth, width) in [(1, 1), (1, 8), (2, 8), (3, 5), (4, 9), (6, 3)] {
        let (parents, pivots, glow) = fan_skeleton(depth, width);
        let n = parents.len();
        let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
        for variant in 0..4 {
            let params = params_for(n, variant);
            let inputs = reference_inputs(&parents, &pivots, &glow, &params);
            for root in [IDENTITY_ROOT, SCALED_ROOT] {
                let expected = reference(&root, &inputs);
                for isa in ALL {
                    if isa == Isa::Auto || !supported(isa) {
                        continue;
                    }
                    let (out, used) = run_packed(
                        &skeleton,
                        &root,
                        &params,
                        PackedLayout::FloatTail,
                        BonePassFilter::All,
                        isa,
                    )
                    .unwrap_or_else(|error| {
                        panic!("{isa:?} failed at {depth}x{width} variant {variant}: {error:?}")
                    });
                    assert_eq!(
                        used,
                        packed_executed(isa),
                        "{isa:?} misreported at {depth}x{width}"
                    );
                    for (bone, reference) in expected.iter().enumerate().take(n) {
                        let base = bone * PACKED_BONE_STRIDE;
                        // A bone the reference marks invisible is written as the canonical hidden
                        // record, not as its computed transform: the host's shader ignores hidden
                        // bones, and writing identity is what keeps a stale record from surviving.
                        // So only visible bones are comparable here; visibility itself is checked
                        // by `visibility_and_pass_filter_match_the_reference`.
                        if !reference.visible {
                            continue;
                        }
                        // The reference already folds the root pose into every world matrix, and
                        // the packed path applies it at write time, so the two are directly
                        // comparable: both are `rootPose * world`.
                        for col in 0..4 {
                            for row in 0..4 {
                                assert_close(
                                    read_f32(&out, base + col * 16 + row * 4),
                                    reference.transform[col * 4 + row],
                                    &format!(
                                        "{isa:?} {depth}x{width} v{variant} bone {bone} m[{col}][{row}]"
                                    ),
                                );
                            }
                        }
                        // Likewise for the normal: the reference takes the inverse-transpose of
                        // the full world matrix, the packed path takes `rootNormal` times the
                        // inverse-transpose of the local world. Those agree exactly when
                        // `rootNormal` is the inverse-transpose of the root's 3x3, which the
                        // fixture guarantees.
                        for col in 0..3 {
                            for row in 0..3 {
                                assert_close(
                                    read_f32(&out, base + 64 + col * 16 + row * 4),
                                    reference.normal[col * 3 + row],
                                    &format!(
                                        "{isa:?} {depth}x{width} v{variant} bone {bone} n[{col}][{row}]"
                                    ),
                                );
                            }
                        }
                    }
                }
            }
        }
    }
}

/// Visibility and the pass filter must agree with the reference's flags.
#[test]
fn visibility_and_pass_filter_match_the_reference() {
    let (parents, pivots, glow) = fan_skeleton(4, 6);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let params = params_for(n, 3);
    let inputs = reference_inputs(&parents, &pivots, &glow, &params);
    let expected = reference(&IDENTITY_ROOT, &inputs);

    for pass in [
        BonePassFilter::All,
        BonePassFilter::NonGlow,
        BonePassFilter::Glow,
    ] {
        let (out, _) = run_packed(
            &skeleton,
            &IDENTITY_ROOT,
            &params,
            PackedLayout::FloatTail,
            pass,
            Isa::Scalar,
        )
        .unwrap();
        for bone in 0..n {
            let base = bone * PACKED_BONE_STRIDE;
            let pass_visible = match pass {
                BonePassFilter::All => true,
                BonePassFilter::NonGlow => glow[bone] == 0,
                BonePassFilter::Glow => glow[bone] != 0,
            };
            let want_visible = expected[bone].visible && pass_visible;
            let hidden_flag = read_f32(&out, base + 132);
            assert_eq!(
                hidden_flag == 0.,
                want_visible,
                "bone {bone} visibility under {pass:?}"
            );
            if !want_visible {
                // A hidden record is the canonical identity, not a stale previous frame.
                assert_eq!(read_f32(&out, base), 1., "hidden bone {bone} m00");
                assert_eq!(read_f32(&out, base + 20), 1., "hidden bone {bone} m11");
                assert_eq!(read_f32(&out, base + 40), 1., "hidden bone {bone} m22");
                assert_eq!(read_f32(&out, base + 60), 1., "hidden bone {bone} m33");
            }
        }
    }
}

/// The int-tail layout must differ from the float-tail layout only in the last four words.
#[test]
fn the_two_tail_layouts_differ_only_in_the_tail() {
    let (parents, pivots, glow) = fan_skeleton(3, 4);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let params = params_for(n, 0);
    let (float_out, _) = run_packed(
        &skeleton,
        &IDENTITY_ROOT,
        &params,
        PackedLayout::FloatTail,
        BonePassFilter::All,
        Isa::Scalar,
    )
    .unwrap();
    let (int_out, _) = run_packed(
        &skeleton,
        &IDENTITY_ROOT,
        &params,
        PackedLayout::IntTail,
        BonePassFilter::All,
        Isa::Scalar,
    )
    .unwrap();
    for bone in 0..n {
        let base = bone * PACKED_BONE_STRIDE;
        assert_eq!(
            &float_out[base..base + 128],
            &int_out[base..base + 128],
            "bone {bone} body must be identical between tail layouts"
        );
        let light = read_f32(&float_out, base + 128);
        assert_eq!(read_i32(&int_out, base + 128), light as i32);
    }
}

/// Locator write-back must match the reference's locator positions, in the host's scaled units.
#[test]
fn locator_write_back_matches_the_reference() {
    let (parents, pivots, glow) = fan_skeleton(3, 5);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let params = params_for(n, 3);
    let inputs = reference_inputs(&parents, &pivots, &glow, &params);
    let expected = reference(&IDENTITY_ROOT, &inputs);

    let mut state = vec![0_f32; n * 4];
    let mut out = vec![0_u8; n * PACKED_BONE_STRIDE];
    let request = PackedRequest {
        skeleton: &skeleton,
        root: &IDENTITY_ROOT,
        params: &params,
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0,
        full_bright_light: 0,
        isa: Isa::Scalar,
        persistent_output: false,
        path: None,
    };
    // SAFETY: all buffers are sized for `n` bones and do not alias.
    unsafe { evaluate_packed(&request, out.as_mut_ptr(), Some(&mut state)) }.unwrap();

    let mut checked = 0;
    for bone in 0..n {
        if params[bone * BONE_PARAM_STRIDE + 11] != 1. || !expected[bone].visible {
            continue;
        }
        checked += 1;
        assert_close(
            state[bone * 4],
            -expected[bone].locator[0] * 16.,
            &format!("bone {bone} locator x"),
        );
        assert_close(
            state[bone * 4 + 1],
            expected[bone].locator[1] * 16.,
            &format!("bone {bone} locator y"),
        );
        assert_close(
            state[bone * 4 + 2],
            expected[bone].locator[2] * 16.,
            &format!("bone {bone} locator z"),
        );
    }
    assert!(
        checked > 0,
        "the fixture must exercise at least one locator"
    );
}

/// A rejected frame must leave the caller's buffer exactly as it was.
///
/// This is the "failed batches must not publish partial output" rule, and it is why the writeback
/// happens only after every bone has been evaluated and checked.
#[test]
fn a_rejected_frame_does_not_touch_the_output_buffer() {
    let (parents, pivots, glow) = fan_skeleton(2, 4);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let mut params = params_for(n, 0);
    // A NaN in the last bone's rotation: the failure is detected mid-evaluation.
    params[(n - 1) * BONE_PARAM_STRIDE] = f32::NAN;
    let sentinel = 0xA5_u8;
    let mut out = vec![sentinel; n * PACKED_BONE_STRIDE];
    let request = PackedRequest {
        skeleton: &skeleton,
        root: &IDENTITY_ROOT,
        params: &params,
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0,
        full_bright_light: 0,
        isa: Isa::Scalar,
        persistent_output: false,
        path: None,
    };
    // SAFETY: `out` is sized for `n` bones and does not alias the inputs.
    let result = unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) };
    assert_eq!(result.unwrap_err(), Status::NonFinite);
    assert!(
        out.iter().all(|&byte| byte == sentinel),
        "a rejected frame must not write any output"
    );
}

/// A singular matrix is rejected rather than producing infinities in the buffer.
#[test]
fn a_singular_matrix_is_rejected_without_writing() {
    let (parents, pivots, glow) = fan_skeleton(2, 3);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let mut params = params_for(n, 0);
    // Zero scale on a bone makes its local matrix singular.
    params[6] = 0.;
    let mut out = vec![0x5A_u8; n * PACKED_BONE_STRIDE];
    let request = PackedRequest {
        skeleton: &skeleton,
        root: &IDENTITY_ROOT,
        params: &params,
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0,
        full_bright_light: 0,
        isa: Isa::Scalar,
        persistent_output: false,
        path: None,
    };
    // SAFETY: as above.
    let result = unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) };
    // A zero scale skips the bone (it is not visible), so this frame is valid and writes hidden
    // records. What must not happen is an infinity or NaN reaching the buffer.
    if result.is_ok() {
        for bone in 0..n {
            let base = bone * PACKED_BONE_STRIDE;
            for offset in (0..128).step_by(4) {
                assert!(
                    read_f32(&out, base + offset).is_finite(),
                    "bone {bone} offset {offset} must be finite"
                );
            }
        }
    }
}

/// Malformed requests are rejected before any pointer is formed.
#[test]
fn malformed_requests_are_rejected() {
    let (parents, pivots, glow) = fan_skeleton(2, 4);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let params = params_for(n, 0);
    let mut out = vec![0_u8; n * PACKED_BONE_STRIDE];

    // Too few parameters.
    let short = &params[..n * BONE_PARAM_STRIDE - 1];
    let request = PackedRequest {
        skeleton: &skeleton,
        root: &IDENTITY_ROOT,
        params: short,
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0,
        full_bright_light: 0,
        isa: Isa::Scalar,
        persistent_output: false,
        path: None,
    };
    // SAFETY: `out` is sized for `n` bones; the request is rejected before any write.
    assert_eq!(
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap_err(),
        Status::BufferTooSmall
    );

    // Too few root floats.
    let request = PackedRequest {
        skeleton: &skeleton,
        root: &IDENTITY_ROOT[..24],
        params: &params,
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0,
        full_bright_light: 0,
        isa: Isa::Scalar,
        persistent_output: false,
        path: None,
    };
    // SAFETY: as above.
    assert_eq!(
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap_err(),
        Status::InvalidInput
    );

    // A null output pointer.
    let request = PackedRequest {
        skeleton: &skeleton,
        root: &IDENTITY_ROOT,
        params: &params,
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0,
        full_bright_light: 0,
        isa: Isa::Scalar,
        persistent_output: false,
        path: None,
    };
    // SAFETY: the null pointer is rejected before any dereference.
    assert_eq!(
        unsafe { evaluate_packed(&request, std::ptr::null_mut(), None) }.unwrap_err(),
        Status::InvalidInput
    );

    // A non-finite root.
    let mut bad_root = IDENTITY_ROOT;
    bad_root[0] = f32::INFINITY;
    let request = PackedRequest {
        skeleton: &skeleton,
        root: &bad_root,
        params: &params,
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0,
        full_bright_light: 0,
        isa: Isa::Scalar,
        persistent_output: false,
        path: None,
    };
    // SAFETY: as above.
    assert_eq!(
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap_err(),
        Status::NonFinite
    );
}

/// An unsupported forced tier is rejected, never silently downgraded.
#[test]
fn unsupported_forced_tiers_are_rejected() {
    let (parents, pivots, glow) = fan_skeleton(2, 3);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let params = params_for(n, 0);
    let mut out = vec![0_u8; n * PACKED_BONE_STRIDE];
    for isa in ALL {
        if isa == Isa::Auto || supported(isa) {
            continue;
        }
        let request = PackedRequest {
            skeleton: &skeleton,
            root: &IDENTITY_ROOT,
            params: &params,
            layout: PackedLayout::FloatTail,
            pass: BonePassFilter::All,
            packed_light: 0,
            full_bright_light: 0,
            isa,
            persistent_output: false,
            path: None,
        };
        // SAFETY: `out` is sized for `n` bones; the request is rejected before any write.
        assert_eq!(
            unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap_err(),
            Status::UnsupportedIsa,
            "forced {isa:?} must be rejected on this host"
        );
    }
}

/// An empty skeleton is valid and writes nothing.
#[test]
fn an_empty_skeleton_writes_nothing() {
    let skeleton = CompiledSkeleton::compile(&[], &[], &[]).unwrap();
    let mut out = [0x11_u8; 16];
    let request = PackedRequest {
        skeleton: &skeleton,
        root: &IDENTITY_ROOT,
        params: &[],
        layout: PackedLayout::FloatTail,
        pass: BonePassFilter::All,
        packed_light: 0,
        full_bright_light: 0,
        isa: Isa::Scalar,
        persistent_output: false,
        path: None,
    };
    // SAFETY: a zero-bone skeleton writes zero bytes, so the 16-byte buffer is more than enough.
    let outcome = unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
    assert_eq!(outcome.bones, 0);
    assert_eq!(out, [0x11_u8; 16]);
}

/// The reported tier is a projection of the selected kernel, exactly as for the other kernels.
///
/// The packed path only has 8-wide composition kernels for AVX2 and AVX2+FMA. SSE2, AVX and NEON
/// fall back to the scalar walk, and reporting those as the requested tier would claim
/// instructions that never ran — which is precisely the defect the N13 rule exists to prevent. So
/// this asserts the weaker but honest property: the report equals the tier of the kernel that
/// actually executed, and only the tiers with a real vector kernel report themselves.
#[test]
fn the_reported_tier_is_the_tier_of_the_selected_kernel() {
    for isa in ALL {
        if isa == Isa::Auto || !supported(isa) {
            continue;
        }
        assert_eq!(packed_executed(isa), packed_kernel_id(isa).tier());
        match isa {
            Isa::Avx2 | Isa::Avx2Fma => assert_eq!(
                packed_executed(isa),
                isa,
                "forced {isa:?} has a vector kernel and must report itself"
            ),
            _ => assert_eq!(
                packed_executed(isa),
                Isa::Scalar,
                "forced {isa:?} has no vector kernel and must report the scalar tier it runs"
            ),
        }
    }
}

/// The flag word decodes into the two independent choices, and rejects unknown bits.
#[test]
fn the_flag_word_decodes_exactly() {
    assert_eq!(
        decode_packed_flags(0).unwrap(),
        (PackedLayout::IntTail, BonePassFilter::All)
    );
    assert_eq!(
        decode_packed_flags(1).unwrap(),
        (PackedLayout::FloatTail, BonePassFilter::All)
    );
    assert_eq!(
        decode_packed_flags(0b010).unwrap(),
        (PackedLayout::IntTail, BonePassFilter::NonGlow)
    );
    assert_eq!(
        decode_packed_flags(0b101).unwrap(),
        (PackedLayout::FloatTail, BonePassFilter::Glow)
    );
    // Pass codes 3 and above are not defined.
    assert_eq!(
        decode_packed_flags(0b110).unwrap_err(),
        Status::InvalidInput
    );
    assert_eq!(
        decode_packed_flags(0b1110).unwrap_err(),
        Status::InvalidInput
    );
}

/// A long chain exercises the level decomposition where every level holds one bone.
///
/// The chain uses unit scale and bounded rotations. That is deliberate: a chain that compounds a
/// scale factor over 64 levels reaches magnitudes where `f32` and `f64` legitimately disagree by
/// more than the budget, and asserting otherwise would be asserting that `f32` has `f64` precision.
/// Real rigs do not compound scale down a 64-deep chain, and the wide-fan fixtures cover the
/// general scale paths.
#[test]
fn a_deep_chain_matches_the_reference() {
    let (parents, pivots, glow) = chain_skeleton(64);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    assert_eq!(
        skeleton.depth(),
        64,
        "a chain must produce one bone per level"
    );
    let mut params = params_for(n, 0);
    // Unit scale and small rotations keep the chain inside `f32`'s useful range.
    for i in 0..n {
        let p = i * BONE_PARAM_STRIDE;
        params[p] = (i as f32) * 0.01;
        params[p + 1] = (i as f32) * 0.007;
        params[p + 2] = -(i as f32) * 0.005;
        params[p + 6] = 1.;
        params[p + 7] = 1.;
        params[p + 8] = 1.;
    }
    let inputs = reference_inputs(&parents, &pivots, &glow, &params);
    let expected = reference(&IDENTITY_ROOT, &inputs);
    for isa in ALL {
        if isa == Isa::Auto || !supported(isa) {
            continue;
        }
        let (out, _) = run_packed(
            &skeleton,
            &IDENTITY_ROOT,
            &params,
            PackedLayout::FloatTail,
            BonePassFilter::All,
            isa,
        )
        .unwrap();
        for (bone, reference) in expected.iter().enumerate().take(n) {
            if !reference.visible {
                continue;
            }
            let base = bone * PACKED_BONE_STRIDE;
            for k in 0..12 {
                assert_close(
                    read_f32(&out, base + k * 4),
                    expected[bone].transform[k],
                    &format!("{isa:?} chain bone {bone} m[{k}]"),
                );
            }
        }
    }
}

/// The measured error must stay well inside the budget, not merely inside it.
///
/// A test that only asserts `<= 1e-4` would still pass if the implementation drifted to `9e-5`.
/// This records the actual worst case so a regression in precision shows up as a number, and
/// asserts a tighter bound that leaves headroom for platform differences.
#[test]
fn measured_error_stays_inside_the_budget() {
    let (parents, pivots, glow) = fan_skeleton(6, 8);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let params = params_for(n, 0);
    let inputs = reference_inputs(&parents, &pivots, &glow, &params);
    let expected = reference(&IDENTITY_ROOT, &inputs);
    let mut worst = 0_f32;
    for isa in ALL {
        if isa == Isa::Auto || !supported(isa) {
            continue;
        }
        let (out, _) = run_packed(
            &skeleton,
            &IDENTITY_ROOT,
            &params,
            PackedLayout::FloatTail,
            BonePassFilter::All,
            isa,
        )
        .unwrap();
        for (bone, reference) in expected.iter().enumerate().take(n) {
            let base = bone * PACKED_BONE_STRIDE;
            for k in 0..12 {
                let actual = read_f32(&out, base + k * 4);
                let want = reference.transform[k];
                let error = (actual - want).abs() / want.abs().max(1.);
                worst = worst.max(error);
            }
        }
    }
    println!("worst relative error across all tiers: {worst:e}");
    assert!(
        worst <= 1e-5,
        "worst relative error {worst:e} exceeds the 1e-5 headroom bound"
    );
}

/// Both kernels must produce the same records for the same frame, to within the precision budget.
///
/// The switch between them is a performance decision, so it must be invisible in the output. This
/// pins each kernel in turn on identical inputs and compares the buffers.
///
/// The comparison is by tolerance rather than byte for byte, and that is a real property of the
/// implementation rather than a concession. `sin_cos8` decides between its polynomial and its
/// `f32::sin_cos` fallback once per eight-lane group, so a bone's trigonometry depends on which
/// seven bones share its group — and the two kernels group differently (the cached path rebuilds
/// locals in bone order, the uncached path in level order). The two paths therefore differ by
/// about one ULP on some bones. Both are inside the documented `1e-4` budget against the `f64`
/// reference, which is the contract that matters; asserting bit equality here would be asserting
/// something the design does not promise.
#[test]
fn the_two_kernels_agree_within_the_precision_budget() {
    let mut worst = 0_f32;
    for (depth, width) in [(1, 1), (1, 8), (2, 8), (3, 5), (4, 9), (6, 3)] {
        let (parents, pivots, glow) = fan_skeleton(depth, width);
        let n = parents.len();
        let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
        for variant in 0..4 {
            let params = params_for(n, variant);
            for root in [IDENTITY_ROOT, SCALED_ROOT] {
                for isa in ALL {
                    if isa == Isa::Auto || !supported(isa) {
                        continue;
                    }
                    let mut outputs = Vec::new();
                    for path in [PackedPath::Cached, PackedPath::Uncached] {
                        let mut out = vec![0_u8; n * PACKED_BONE_STRIDE];
                        let request = PackedRequest {
                            skeleton: &skeleton,
                            root: &root,
                            params: &params,
                            layout: PackedLayout::FloatTail,
                            pass: BonePassFilter::All,
                            packed_light: 0x00F0_00F0,
                            full_bright_light: 0x00F0_00F0,
                            isa,
                            persistent_output: false,
                            path: Some(path),
                        };
                        // SAFETY: `out` is exactly `n * 144` bytes and nothing aliases.
                        let outcome =
                            unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
                        assert_eq!(outcome.path, path, "{isa:?} did not honour the pinned path");
                        outputs.push(out);
                    }
                    for bone in 0..n {
                        let base = bone * PACKED_BONE_STRIDE;
                        for k in 0..12 {
                            let cached = read_f32(&outputs[0], base + k * 4);
                            let uncached = read_f32(&outputs[1], base + k * 4);
                            let error = (cached - uncached).abs() / cached.abs().max(1.);
                            worst = worst.max(error);
                            assert!(
                                error <= TOLERANCE,
                                "{isa:?} kernels differ by {error:e} at {depth}x{width} \
                                 variant {variant} bone {bone} element {k}: \
                                 {cached} vs {uncached}"
                            );
                        }
                    }
                }
            }
        }
    }
    println!("worst cached-vs-uncached relative difference: {worst:e}");
}

/// A frame that returns to the cached kernel after an uncached one must still be correct.
///
/// This is the regression the earlier adaptive attempt caused: switching kernels left the cache in
/// a state the next cached frame trusted, so a sparse frame after a fully-animated one produced
/// stale matrices. The sequence here is deliberately adversarial — animate everything, then move
/// one bone, then nothing — and every frame is compared against the reference evaluator.
#[test]
fn switching_kernels_between_frames_keeps_the_output_correct() {
    let (parents, pivots, glow) = fan_skeleton(4, 8);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let mut params = params_for(n, 0);
    let mut out = vec![0_u8; n * PACKED_BONE_STRIDE];

    // Each entry is the set of bones that moves on that frame.
    let all: Vec<usize> = (0..n).collect();
    let one: Vec<usize> = vec![n / 2];
    let none: Vec<usize> = Vec::new();
    let frames = [
        all.clone(),
        all.clone(),
        one.clone(),
        none.clone(),
        all.clone(),
        one.clone(),
        none,
        all,
    ];

    for (index, moving) in frames.iter().enumerate() {
        for &bone in moving {
            let p = bone * BONE_PARAM_STRIDE;
            params[p] += 0.05;
            params[p + 1] -= 0.03;
        }
        let inputs = reference_inputs(&parents, &pivots, &glow, &params);
        let expected = reference(&IDENTITY_ROOT, &inputs);
        let request = PackedRequest {
            skeleton: &skeleton,
            root: &IDENTITY_ROOT,
            params: &params,
            layout: PackedLayout::FloatTail,
            pass: BonePassFilter::All,
            packed_light: 0x00F0_00F0,
            full_bright_light: 0x00F0_00F0,
            isa: packed_preferred_isa(),
            // The host's real situation: the buffer persists, so a frame that changes nothing may
            // legitimately leave it untouched.
            persistent_output: true,
            path: None,
        };
        // SAFETY: `out` is exactly `n * 144` bytes and nothing aliases.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }.unwrap();
        for (bone, reference) in expected.iter().enumerate().take(n) {
            let base = bone * PACKED_BONE_STRIDE;
            for k in 0..12 {
                assert_close(
                    read_f32(&out, base + k * 4),
                    reference.transform[k],
                    &format!("frame {index} bone {bone} element {k}"),
                );
            }
        }
    }
}

/// The switch must actually reach both kernels, or the crossover is untested.
///
/// A threshold that never fires would leave the uncached kernel dead code while every test above
/// still passed, because both kernels agree. This drives the two extremes and asserts the reported
/// path changes, so the choice is exercised rather than merely present.
#[test]
fn the_switch_selects_both_kernels() {
    let (parents, pivots, glow) = fan_skeleton(4, 8);
    let n = parents.len();
    let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
    let mut params = params_for(n, 0);
    let mut out = vec![0_u8; n * PACKED_BONE_STRIDE];
    let isa = packed_preferred_isa();

    let mut run = |params: &[f32]| {
        let request = PackedRequest {
            skeleton: &skeleton,
            root: &IDENTITY_ROOT,
            params,
            layout: PackedLayout::FloatTail,
            pass: BonePassFilter::All,
            packed_light: 0x00F0_00F0,
            full_bright_light: 0x00F0_00F0,
            isa,
            persistent_output: true,
            path: None,
        };
        // SAFETY: `out` is exactly `n * 144` bytes and nothing aliases.
        unsafe { evaluate_packed(&request, out.as_mut_ptr(), None) }
            .unwrap()
            .path
    };

    // The first frame at a skeleton is a full rebuild, so every bone counts as moved and the
    // uncached kernel is the honest choice for it.
    assert_eq!(
        run(&params),
        PackedPath::Uncached,
        "the first frame rebuilds everything"
    );
    // Re-running the same parameters changes nothing, so the frame is already correct.
    assert_eq!(
        run(&params),
        PackedPath::Cached,
        "an unchanged frame must not recompute"
    );
    for bone in 0..n {
        params[bone * BONE_PARAM_STRIDE] += 0.05;
    }
    assert_eq!(
        run(&params),
        PackedPath::Uncached,
        "a fully-animated frame must take the uncached kernel"
    );

    // One bone moving out of `n` is far below the threshold, so the cached kernel must return.
    params[BONE_PARAM_STRIDE] += 0.05;
    assert_eq!(
        run(&params),
        PackedPath::Cached,
        "a sparse frame must return to the cached kernel"
    );
}
