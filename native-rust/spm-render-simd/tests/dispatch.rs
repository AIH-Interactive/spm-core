//! Per-kernel, per-ISA differential matrix against the scalar oracle, plus the N13 regression.
//!
//! Every test here is portable: it discovers what the *host* supports at runtime and asserts the
//! differential property for exactly those tiers, while the remaining tiers are asserted to be
//! rejected. Tiers this CPU lacks are therefore never silently skipped.
use spm_native_abi::{Isa, Status};
use spm_render_simd::{
    evaluate_bones, executed, kernel_ids, render_rigid, supported, transform_positions, BoneInput,
    BoneOutput, KernelId, RigidVertex,
};

/// Absolute tolerance for `f32` results.
///
/// The scalar oracle and the non-FMA SIMD tiers perform the identical sequence of `f32`
/// operations, so they agree bit for bit. The FMA tiers fuse one multiply and add, which can move
/// a result by one unit in the last place; `1e-5` is comfortably above that for the magnitudes used
/// here (results are `O(10)`) and is the value the pre-existing suites already used.
const TOLERANCE: f32 = 1e-5;

/// Batch lengths covering empty, sub-vector, exactly-vector, and multi-vector-with-tail cases for
/// vector widths 4 (SSE2/NEON), 8 (AVX/AVX2) and 16 (AVX-512).
const LENGTHS: [usize; 19] = [
    0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127,
];

const MATRIX: [f32; 16] = [
    2., 0.25, 0., 0., 0., 3., 0.125, 0., 0.5, 0., 4., 0., 5., 6., 7., 1.,
];

/// Every ISA tier, so a newly added tier cannot be forgotten by these tests.
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

fn assert_close(actual: f32, expected: f32, context: &str) {
    let bound = TOLERANCE * expected.abs().max(1.);
    assert!(
        (actual - expected).abs() <= bound,
        "{context}: {actual} != {expected} (bound {bound})"
    );
}

// ---------------------------------------------------------------------------------------------
// N13: the reported ISA must be the ISA that actually executed.
// ---------------------------------------------------------------------------------------------

/// Regression for defect N13.
///
/// Before the fix, `Isa::Avx | Isa::Avx2` both dispatched to the single `avx` kernel while the
/// function still returned the *requested* tier, so an AVX2 request reported AVX2 instructions
/// after running AVX-only code. The invariant below fails on that code: two requests reporting
/// different executed ISAs while sharing one kernel.
///
/// The kernel identity is an explicit `KernelId`, not a function address, because identical-code
/// folding can give two distinct kernels a single address and would silently defuse this test.
#[test]
fn every_tier_that_reports_a_distinct_isa_is_served_by_a_distinct_kernel() {
    let mut rows: Vec<(Isa, Isa, (KernelId, KernelId, KernelId))> = Vec::new();
    for isa in ALL {
        if !supported(isa) || isa == Isa::Auto {
            continue;
        }
        rows.push((isa, executed(isa), kernel_ids(isa)));
    }
    assert!(
        rows.len() >= 2,
        "expected at least the scalar tier plus one SIMD tier on this host"
    );
    for (left_index, (_, left_executed, left_ids)) in rows.iter().enumerate() {
        for (right_index, (right_request, right_executed, right_ids)) in rows.iter().enumerate() {
            if left_index == right_index {
                continue;
            }
            // A tier may only claim a different executed ISA if a different kernel serves it, in
            // all three kernels. Sharing a kernel while reporting different ISAs is exactly N13.
            assert_eq!(
                left_executed == right_executed,
                left_ids == right_ids,
                "request {right_request:?} reports executed {right_executed:?} with kernels \
                 {right_ids:?}, but another tier reports {left_executed:?} with {left_ids:?}"
            );
        }
    }
}

/// The reported ISA is a projection of the selected kernel, not an independent claim.
///
/// This is the structural half of the N13 fix: `executed(isa)` is defined as
/// `kernel_ids(isa).tier()`, so the two can only agree.
#[test]
fn reported_isa_is_exactly_the_tier_of_the_selected_kernel() {
    for isa in ALL {
        if isa == Isa::Auto || !supported(isa) {
            continue;
        }
        let (transform, bones, rigid) = kernel_ids(isa);
        assert_eq!(executed(isa), transform.tier());
        assert_eq!(transform.tier(), bones.tier());
        assert_eq!(transform.tier(), rigid.tier());
        assert_eq!(transform.tier(), isa, "forced {isa:?} must run at {isa:?}");
    }
}

/// A supported forced request is served at exactly that tier, which is the property the Java guard
/// `requested != AUTO && used != requested` relies on.
#[test]
fn a_supported_forced_request_is_reported_as_the_tier_that_ran() {
    for isa in ALL {
        if isa == Isa::Auto || !supported(isa) {
            continue;
        }
        assert_eq!(executed(isa), isa, "forced {isa:?} must execute at {isa:?}");
    }
}

/// `Auto` resolves to a real tier and never reports itself.
#[test]
fn auto_resolves_to_a_concrete_tier_and_never_reports_itself() {
    let request = Isa::Auto;
    let input = [1., 2., 3.];
    let (_, used) = transform_positions(&MATRIX, &input, request).expect("auto must resolve");
    assert_ne!(used, Isa::Auto);
    assert!(supported(used));
    assert_eq!(used, executed(used));
}

// ---------------------------------------------------------------------------------------------
// Kernel 1: transform_positions
// ---------------------------------------------------------------------------------------------

#[test]
fn transform_matches_scalar_for_every_available_isa_and_batch_length() {
    for count in LENGTHS {
        let input: Vec<f32> = (0..count * 3).map(|x| x as f32 * 0.03125 - 2.).collect();
        let (expected, oracle) = transform_positions(&MATRIX, &input, Isa::Scalar).unwrap();
        assert_eq!(oracle, Isa::Scalar);
        assert_eq!(expected.len(), count * 3);
        for isa in ALL {
            if isa == Isa::Auto || !supported(isa) {
                continue;
            }
            let (actual, used) = transform_positions(&MATRIX, &input, isa)
                .unwrap_or_else(|error| panic!("{isa:?} failed at {count}: {error:?}"));
            assert_eq!(used, isa, "{isa:?} misreported at {count}");
            assert_eq!(actual.len(), expected.len(), "{isa:?} length at {count}");
            for (index, (a, b)) in actual.iter().zip(&expected).enumerate() {
                assert_close(
                    *a,
                    *b,
                    &format!("transform {isa:?} count {count} index {index}"),
                );
            }
        }
    }
}

#[test]
fn transform_rejects_unsupported_forced_isa_without_writing_a_result() {
    for isa in ALL {
        if isa == Isa::Auto || supported(isa) {
            continue;
        }
        assert_eq!(
            transform_positions(&MATRIX, &[1., 2., 3.], isa).unwrap_err(),
            Status::UnsupportedIsa,
            "forced {isa:?} must be rejected on this host"
        );
    }
}

// ---------------------------------------------------------------------------------------------
// Kernel 2: evaluate_bones (matrix multiply stage)
// ---------------------------------------------------------------------------------------------

/// A chain of `n` bones: bone 0 is a root, bone `i` parents to `i - 1`.
///
/// Rotations, translations and scales vary with the index so the multiply sees a non-degenerate
/// mix of values rather than one repeated matrix.
fn bone_chain(n: usize) -> Vec<BoneInput> {
    (0..n)
        .map(|i| {
            let step = i as f32;
            BoneInput {
                parent: if i == 0 { -1 } else { i as i32 - 1 },
                pivot: [0.25 + step * 0.01, -0.5 + step * 0.02, 0.75],
                rotation: [step * 0.05, step * 0.031, -step * 0.017],
                translation: [step * 0.1 - 1., 0.5, -step * 0.05],
                scale: [1. + step * 0.01, 2. - step * 0.005, 0.5 + step * 0.02],
                flags: 0,
                glow: 7,
                color: 0x8040_2010u32.wrapping_add(i as u32),
            }
        })
        .collect()
}

fn assert_bones_close(actual: &[BoneOutput], expected: &[BoneOutput], context: &str) {
    assert_eq!(actual.len(), expected.len(), "{context}: length");
    for (index, (a, b)) in actual.iter().zip(expected).enumerate() {
        assert_close(
            a.transform[12],
            b.transform[12],
            &format!("{context} t[{index}]"),
        );
        assert_close(
            a.transform[13],
            b.transform[13],
            &format!("{context} t[{index}]"),
        );
        assert_close(
            a.transform[14],
            b.transform[14],
            &format!("{context} t[{index}]"),
        );
        for k in 0..12 {
            assert_close(
                a.transform[k],
                b.transform[k],
                &format!("{context} m[{index}][{k}]"),
            );
        }
        for k in 0..9 {
            assert_close(
                a.normal[k],
                b.normal[k],
                &format!("{context} n[{index}][{k}]"),
            );
        }
        for k in 0..3 {
            assert_close(
                a.locator[k],
                b.locator[k],
                &format!("{context} loc[{index}][{k}]"),
            );
        }
        assert_eq!(a.visible, b.visible, "{context}: visibility[{index}]");
        assert_eq!(
            a.descendants, b.descendants,
            "{context}: descendants[{index}]"
        );
        assert_eq!(a.glow, b.glow, "{context}: glow[{index}]");
        assert_eq!(a.color, b.color, "{context}: color[{index}]");
    }
}

#[test]
fn bones_match_scalar_for_every_available_isa_and_bone_count() {
    let root = [
        1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    for n in [0, 1, 2, 3, 4, 5, 8, 9, 17] {
        let bones = bone_chain(n);
        let (expected, oracle) = evaluate_bones(&bones, &root, Isa::Scalar).unwrap();
        assert_eq!(oracle, Isa::Scalar);
        for isa in ALL {
            if isa == Isa::Auto || !supported(isa) {
                continue;
            }
            let (actual, used) = evaluate_bones(&bones, &root, isa)
                .unwrap_or_else(|error| panic!("{isa:?} failed at {n} bones: {error:?}"));
            assert_eq!(used, isa, "{isa:?} misreported at {n} bones");
            assert_bones_close(&actual, &expected, &format!("bones {isa:?} n={n}"));
        }
    }
}

#[test]
fn bones_reject_unsupported_forced_isa() {
    let root = [
        1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    let bones = bone_chain(4);
    for isa in ALL {
        if isa == Isa::Auto || supported(isa) {
            continue;
        }
        assert_eq!(
            evaluate_bones(&bones, &root, isa).unwrap_err(),
            Status::UnsupportedIsa,
            "forced {isa:?} must be rejected on this host"
        );
    }
}

// ---------------------------------------------------------------------------------------------
// Kernel 3: render_rigid (vertex transform, normal, tint)
// ---------------------------------------------------------------------------------------------

fn rigid_fixture() -> (Vec<RigidVertex>, Vec<BoneOutput>) {
    let bone = |mirror: f32, color: u32| BoneOutput {
        transform: [
            mirror, 0., 0., 0., 0., 3., 0., 0., 0., 0., 4., 0., 5., 6., 7., 1.,
        ],
        normal: [mirror * 0.5, 0., 0., 0., 1. / 3., 0., 0., 0., 0.25],
        locator: [0.; 3],
        visible: true,
        descendants: true,
        glow: 7,
        color,
    };
    let bones = vec![bone(-2., 0x8040_2010), bone(2., 0xffff_ffff)];
    (Vec::new(), bones)
}

fn rigid_vertices(count: usize, bones: usize) -> Vec<RigidVertex> {
    (0..count)
        .map(|i| RigidVertex {
            position: [i as f32 * 0.5 - 1., i as f32 * 0.25 + 2., 3.],
            normal: [1., 1., 0.],
            bone: (i % bones) as u32,
            color: 0x80ff_8080u32.wrapping_add(i as u32),
        })
        .collect()
}

#[test]
fn rigid_matches_scalar_for_every_available_isa_and_vertex_count() {
    let (_, bones) = rigid_fixture();
    for count in LENGTHS {
        let vertices = rigid_vertices(count, bones.len());
        let expected = render_rigid(&vertices, &bones, Isa::Scalar).unwrap();
        assert_eq!(expected.isa, Isa::Scalar);
        for isa in ALL {
            if isa == Isa::Auto || !supported(isa) {
                continue;
            }
            let actual = render_rigid(&vertices, &bones, isa)
                .unwrap_or_else(|error| panic!("{isa:?} failed at {count} vertices: {error:?}"));
            assert_eq!(actual.isa, isa, "{isa:?} misreported at {count} vertices");
            assert_eq!(actual.vertices.len(), expected.vertices.len());
            for (index, (a, b)) in actual.vertices.iter().zip(&expected.vertices).enumerate() {
                for axis in 0..3 {
                    assert_close(
                        a.position[axis],
                        b.position[axis],
                        &format!("rigid {isa:?} pos[{index}][{axis}]"),
                    );
                    assert_close(
                        a.normal[axis],
                        b.normal[axis],
                        &format!("rigid {isa:?} nrm[{index}][{axis}]"),
                    );
                }
                // The tint is exact integer arithmetic on every tier, not approximate.
                assert_eq!(a.color, b.color, "rigid {isa:?} color[{index}]");
                assert_eq!(a.bone, b.bone, "rigid {isa:?} bone[{index}]");
                assert_eq!(a.glow, b.glow, "rigid {isa:?} glow[{index}]");
            }
        }
    }
}

#[test]
fn rigid_rejects_unsupported_forced_isa() {
    let (_, bones) = rigid_fixture();
    let vertices = rigid_vertices(3, bones.len());
    for isa in ALL {
        if isa == Isa::Auto || supported(isa) {
            continue;
        }
        assert_eq!(
            render_rigid(&vertices, &bones, isa).unwrap_err(),
            Status::UnsupportedIsa,
            "forced {isa:?} must be rejected on this host"
        );
    }
}

// ---------------------------------------------------------------------------------------------
// Independent, host-independent rejection: an ISA from the other architecture family can never
// execute, regardless of which features this CPU happens to have.
// ---------------------------------------------------------------------------------------------

#[test]
fn a_foreign_architecture_isa_is_always_rejected() {
    // NEON is an ARM extension and AVX-family are x86 extensions, so at most one family can be
    // supported. Asserting both directions keeps this test meaningful on every CI platform.
    #[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
    let foreign = Isa::Neon;
    #[cfg(target_arch = "aarch64")]
    let foreign = Isa::Avx512;
    #[cfg(not(any(target_arch = "x86", target_arch = "x86_64", target_arch = "aarch64")))]
    let foreign = Isa::Avx512;

    assert!(!supported(foreign), "{foreign:?} cannot be supported here");
    assert_eq!(
        transform_positions(&MATRIX, &[1., 2., 3.], foreign).unwrap_err(),
        Status::UnsupportedIsa
    );
    let root = [
        1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    assert_eq!(
        evaluate_bones(&bone_chain(2), &root, foreign).unwrap_err(),
        Status::UnsupportedIsa
    );
    let (_, bones) = rigid_fixture();
    assert_eq!(
        render_rigid(&rigid_vertices(2, bones.len()), &bones, foreign).unwrap_err(),
        Status::UnsupportedIsa
    );
}

/// Unknown ABI codes are rejected rather than mapped onto a neighbouring tier.
#[test]
fn unknown_isa_codes_are_rejected() {
    for code in [8u32, 9, 100, u32::MAX] {
        assert_eq!(Isa::try_from(code).unwrap_err(), Status::UnsupportedIsa);
    }
    for code in 0u32..=7 {
        assert!(Isa::try_from(code).is_ok(), "code {code} must be known");
    }
}
