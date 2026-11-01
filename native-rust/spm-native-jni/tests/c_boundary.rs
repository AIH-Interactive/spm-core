use spm_core_native::spm_transform_positions_v1;
use spm_native_abi::{Isa, Status};

/// The C boundary reports the *executed* tier, and the AVX-512 code is a real wire value.
///
/// This runs on every host: on an AVX-512 machine code 7 must be honoured; elsewhere it must be
/// rejected as `UnsupportedIsa` while never reporting a wider tier than the request.
#[test]
fn c_abi_reports_the_executed_tier_and_rejects_unknown_codes() {
    let mut matrix = [0u8; 64];
    for (i, v) in [
        2f32, 0., 0., 0., 0., 3., 0., 0., 0., 0., 4., 0., 5., 6., 7., 1.,
    ]
    .iter()
    .enumerate()
    {
        matrix[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
    }
    let input = [1f32, 2., 3.];
    let mut bytes = [0u8; 12];
    for (i, v) in input.iter().enumerate() {
        bytes[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
    }
    let mut output = [0u8; 12];

    for request in [
        Isa::Scalar,
        Isa::Sse2,
        Isa::Avx,
        Isa::Avx2,
        Isa::Avx2Fma,
        Isa::Avx512,
    ] {
        let outcome = unsafe {
            spm_transform_positions_v1(
                matrix.as_ptr(),
                64,
                bytes.as_ptr(),
                12,
                output.as_mut_ptr(),
                12,
                1,
                request as u32,
            )
        };
        if spm_render_simd::supported(request) {
            assert_eq!(outcome.status, 0, "{request:?} should be supported here");
            assert_eq!(outcome.completed, 1);
            assert_eq!(
                outcome.isa, request as u32,
                "{request:?} must report the tier that actually ran"
            );
            assert_eq!(&output[0..4], &7f32.to_le_bytes());
            assert_eq!(&output[4..8], &12f32.to_le_bytes());
            assert_eq!(&output[8..12], &19f32.to_le_bytes());
        } else {
            assert_eq!(
                outcome.status,
                Status::UnsupportedIsa as i32,
                "{request:?} is unsupported and must be rejected"
            );
            assert_eq!(outcome.completed, 0);
            assert_eq!(outcome.isa, 0, "a rejected call reports no ISA");
        }
    }

    // Codes beyond the defined set are rejected, not clamped to a neighbouring tier.
    for unknown in [8u32, 9, 255, u32::MAX] {
        let outcome = unsafe {
            spm_transform_positions_v1(
                matrix.as_ptr(),
                64,
                bytes.as_ptr(),
                12,
                output.as_mut_ptr(),
                12,
                1,
                unknown,
            )
        };
        assert_eq!(
            outcome.status,
            Status::UnsupportedIsa as i32,
            "code {unknown}"
        );
        assert_eq!(outcome.completed, 0);
    }
}

#[test]
fn c_abi_commits_only_complete_valid_outputs_and_allows_unaligned_storage() {
    let matrix = [
        1f32, 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 5., 6., 7., 1.,
    ];
    let mut raw = [0u8; 65];
    for (i, v) in matrix.iter().enumerate() {
        raw[1 + i * 4..1 + i * 4 + 4].copy_from_slice(&v.to_le_bytes());
    }
    let input = [1f32, 2., 3.];
    let mut bytes = [0u8; 12];
    for (i, v) in input.iter().enumerate() {
        bytes[i * 4..i * 4 + 4].copy_from_slice(&v.to_le_bytes());
    }
    let mut output = [0xabu8; 13];
    let outcome = unsafe {
        spm_transform_positions_v1(
            raw.as_ptr().add(1),
            64,
            bytes.as_ptr(),
            12,
            output.as_mut_ptr().add(1),
            12,
            1,
            Isa::Auto as u32,
        )
    };
    assert_eq!(outcome.status, 0);
    assert_eq!(outcome.completed, 1);
    assert_eq!(outcome.abi, 1);
    for (i, v) in [6f32, 8., 10.].iter().enumerate() {
        assert_eq!(&output[1 + i * 4..1 + i * 4 + 4], &v.to_le_bytes());
    }
    output.fill(0xab);
    let bad = unsafe {
        spm_transform_positions_v1(
            raw.as_ptr().add(1),
            64,
            bytes.as_ptr(),
            12,
            output.as_mut_ptr(),
            11,
            1,
            0,
        )
    };
    assert!(bad.status < 0);
    assert_eq!(bad.completed, 0);
    assert_eq!(output, [0xab; 13]);
    let null = unsafe {
        spm_transform_positions_v1(
            std::ptr::null(),
            64,
            bytes.as_ptr(),
            12,
            output.as_mut_ptr(),
            12,
            1,
            0,
        )
    };
    assert!(null.status < 0);
    assert_eq!(output, [0xab; 13]);
}
