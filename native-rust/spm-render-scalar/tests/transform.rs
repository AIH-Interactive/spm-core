use spm_render_scalar::transform_positions;

#[test]
fn column_major_transform_preserves_tail_and_translation() {
    let m = [
        2., 0., 0., 0., 0., 3., 0., 0., 0., 0., 4., 0., 5., 6., 7., 1.,
    ];
    let input = [1., 2., 3., -1., -2., -3., 0., 0., 0.];
    assert_eq!(
        transform_positions(&m, &input),
        vec![7., 12., 19., 3., 0., -5., 5., 6., 7.]
    );
}
