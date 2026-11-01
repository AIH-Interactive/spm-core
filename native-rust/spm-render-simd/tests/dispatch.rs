use spm_native_abi::Isa;
use spm_render_simd::{supported, transform_positions};

#[test]
fn every_available_isa_matches_scalar_for_odd_batch_lengths() {
    let m = [
        2., 0.25, 0., 0., 0., 3., 0.125, 0., 0.5, 0., 4., 0., 5., 6., 7., 1.,
    ];
    for count in [0, 1, 2, 3, 7, 8, 9, 31, 65] {
        let input: Vec<f32> = (0..count * 3).map(|x| x as f32 * 0.03125 - 2.).collect();
        let (scalar, _) = transform_positions(&m, &input, Isa::Scalar).unwrap();
        for isa in [Isa::Sse2, Isa::Avx, Isa::Avx2, Isa::Avx2Fma, Isa::Neon] {
            if supported(isa) {
                let (actual, used) = transform_positions(&m, &input, isa).unwrap();
                assert_eq!(used, isa);
                for (a, b) in actual.iter().zip(&scalar) {
                    assert!((a - b).abs() <= 0.00001);
                }
                assert_eq!(actual.len(), scalar.len());
            } else {
                assert!(transform_positions(&m, &input, isa).is_err());
            }
        }
    }
}

#[test]
fn invalid_shape_and_nonfinite_inputs_are_rejected() {
    let mut m = [0.; 16];
    assert!(transform_positions(&m, &[1., 2.], Isa::Auto).is_err());
    m[0] = f32::NAN;
    assert!(transform_positions(&m, &[1., 2., 3.], Isa::Auto).is_err());
    m[0] = f32::MAX;
    assert!(transform_positions(&m, &[f32::MAX, 2., 3.], Isa::Scalar).is_err());
}
