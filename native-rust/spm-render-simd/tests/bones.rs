use spm_native_abi::Isa;
use spm_render_simd::{evaluate_bones, BoneInput};

#[test]
fn hierarchy_keeps_own_hide_separate_from_child_hide() {
    let root = [
        1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    let bones = [
        BoneInput {
            parent: -1,
            pivot: [1., 0., 0.],
            rotation: [0., 0., std::f32::consts::FRAC_PI_2],
            translation: [0.; 3],
            scale: [1.; 3],
            flags: 1,
            glow: 255,
            color: 0x80563412,
        },
        BoneInput {
            parent: 0,
            pivot: [0.; 3],
            rotation: [0.; 3],
            translation: [2., 0., 0.],
            scale: [1.; 3],
            flags: 0,
            glow: 15,
            color: 0xffffffff,
        },
    ];
    let (out, _) = evaluate_bones(&bones, &root, Isa::Scalar).unwrap();
    assert_eq!(out[0].color, 0x80563412);
    assert_eq!(out[1].color, 0xffffffff);
    assert!(!out[0].visible);
    assert!(out[0].descendants);
    assert!(out[1].visible);
    assert!((out[0].transform[12] - 1.).abs() < 0.00001);
    assert!((out[0].transform[13] + 1.).abs() < 0.00001);
    assert!((out[1].transform[12] - 1.).abs() < 0.00001);
    assert!((out[1].transform[13] - 1.).abs() < 0.00001);
    let mut cycle = bones;
    cycle[0].parent = 1;
    assert!(evaluate_bones(&cycle, &root, Isa::Auto).is_err());
}
