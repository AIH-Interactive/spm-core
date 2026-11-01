use spm_render_scalar::{transform_positions, transform_positions_into};

#[test]
fn column_major_transform_preserves_tail_and_translation() {
    let m = [
        2., 0., 0., 0., 0., 3., 0., 0., 0., 0., 4., 0., 5., 6., 7., 1.,
    ];
    let input = [1., 2., 3., -1., -2., -3., 0., 0., 0.];
    assert_eq!(
        transform_positions(&m, &input).expect("small batch allocates"),
        vec![7., 12., 19., 3., 0., -5., 5., 6., 7.]
    );
}

/// The in-place entry and the allocating wrapper must agree bit for bit; the SIMD layer relies on
/// `_into` being the same arithmetic as the oracle.
#[test]
fn in_place_entry_matches_the_allocating_wrapper() {
    let m = [
        2., 0.25, 0., 0., 0., 3., 0.125, 0., 0.5, 0., 4., 0., 5., 6., 7., 1.,
    ];
    for count in [0usize, 1, 2, 3, 7, 8, 9, 16, 17, 65] {
        let input: Vec<f32> = (0..count * 3).map(|i| i as f32 * 0.03125 - 2.).collect();
        let expected = transform_positions(&m, &input).unwrap();
        let mut actual = vec![f32::NAN; input.len()];
        transform_positions_into(&m, &input, &mut actual);
        assert_eq!(
            actual.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
            expected.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
            "count {count}"
        );
    }
}

/// A partial trailing position is ignored rather than read past the end.
///
/// The allocating wrapper sizes its buffer to `input.len()`, so the leftover slots stay zero; the
/// batch boundary rejects a non-multiple-of-three input before it ever reaches here, so this only
/// documents what the two entry points do with a shape they are not given in production.
#[test]
fn a_partial_trailing_position_is_ignored() {
    let m = [
        1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    let full = transform_positions(&m, &[1., 2., 3.]).unwrap();
    let partial = transform_positions(&m, &[1., 2., 3., 9., 9.]).unwrap();
    assert_eq!(
        full,
        partial[..3],
        "the complete position must still transform"
    );
    assert_eq!(
        partial[3..],
        [0., 0.],
        "the incomplete position must not contribute any value"
    );
}
