use spm_native_abi::{Isa, Status};
use spm_render_simd::{render_rigid, supported, BoneOutput, RigidVertex};

fn bone() -> BoneOutput {
    BoneOutput {
        transform: [
            -2., 0., 0., 0., 0., 3., 0., 0., 0., 0., 4., 0., 5., 6., 7., 1.,
        ],
        normal: [-0.5, 0., 0., 0., 1. / 3., 0., 0., 0., 0.25],
        locator: [0.; 3],
        visible: true,
        descendants: true,
        glow: 7,
        color: 0x80402010,
    }
}
fn vertex() -> RigidVertex {
    RigidVertex {
        position: [1., 2., 3.],
        normal: [1., 1., 0.],
        bone: 0,
        color: 0x80ff8080,
    }
}
fn requests() -> impl Iterator<Item = Isa> {
    [
        Isa::Scalar,
        Isa::Sse2,
        Isa::Avx,
        Isa::Avx2,
        Isa::Avx2Fma,
        Isa::Neon,
        Isa::Auto,
    ]
    .into_iter()
    .filter(|isa| supported(*isa))
}

#[test]
fn transforms_normals_tints_and_compacts_in_input_order_for_every_supported_isa() {
    let mut hidden = bone();
    hidden.visible = false;
    let mut hidden_vertex = vertex();
    hidden_vertex.bone = 1;
    for request in requests() {
        for count in [0, 1, 2, 3, 4, 7, 8, 15, 16, 17, 33] {
            let mut input = vec![vertex(); count];
            if count > 1 {
                input[1] = hidden_vertex;
            }
            let result = render_rigid(&input, &[bone(), hidden], request).unwrap();
            assert_eq!(result.vertices.len(), count - usize::from(count > 1));
            assert!(supported(result.isa));
            assert_ne!(result.isa, Isa::Auto);
            if request != Isa::Auto {
                assert_eq!(result.isa, request);
            }
            for out in result.vertices {
                assert_eq!(out.position, [3., 12., 19.]);
                assert!((out.normal[0] + 0.8320503).abs() < 0.00001);
                assert!((out.normal[1] - 0.5547002).abs() < 0.00001);
                assert_eq!(out.normal[2], 0.);
                assert_eq!(out.bone, 0);
                assert_eq!(out.glow, 7);
                assert_eq!(out.color, 0x40401008);
            }
        }
    }
}

#[test]
fn rejects_nonfinite_invalid_material_and_all_invalid_indices_even_when_hidden() {
    for request in requests() {
        let mut invalid = vertex();
        invalid.bone = 1;
        assert_eq!(
            render_rigid(&[invalid], &[bone()], request).unwrap_err(),
            Status::InvalidInput
        );
        let mut hidden = bone();
        hidden.visible = false;
        invalid = vertex();
        invalid.position[0] = f32::NAN;
        assert_eq!(
            render_rigid(&[invalid], &[hidden], request).unwrap_err(),
            Status::NonFinite
        );
        let mut invalid_bone = bone();
        invalid_bone.glow = 16;
        assert_eq!(
            render_rigid(&[], &[invalid_bone], request).unwrap_err(),
            Status::InvalidInput
        );
        invalid_bone = bone();
        invalid_bone.normal[0] = f32::INFINITY;
        assert_eq!(
            render_rigid(&[], &[invalid_bone], request).unwrap_err(),
            Status::NonFinite
        );
        let mut overflow = vertex();
        overflow.position[0] = f32::MAX;
        assert_eq!(
            render_rigid(&[overflow], &[bone()], request).unwrap_err(),
            Status::NonFinite
        );
    }
}

#[test]
fn zero_normals_and_all_hidden_inputs_are_valid_empty_results() {
    for request in requests() {
        let mut input = vertex();
        input.normal = [0.; 3];
        let result = render_rigid(&[input], &[bone()], request).unwrap();
        assert_eq!(result.vertices[0].normal, [0.; 3]);
        let mut hidden = bone();
        hidden.visible = false;
        assert!(render_rigid(&[input], &[hidden], request)
            .unwrap()
            .vertices
            .is_empty());
    }
}

#[test]
fn every_byte_tint_pair_uses_exact_rounded_integer_multiplication() {
    for request in requests() {
        for bone_channel in 0..=255u32 {
            let mut b = bone();
            b.color = bone_channel * 0x01010101;
            let input: Vec<_> = (0..=255u32)
                .map(|channel| {
                    let mut v = vertex();
                    v.color = channel * 0x01010101;
                    v
                })
                .collect();
            let result = render_rigid(&input, &[b], request).unwrap();
            for (channel, out) in result.vertices.into_iter().enumerate() {
                assert_eq!(
                    out.color,
                    ((channel as u32 * bone_channel + 127) / 255) * 0x01010101
                );
            }
        }
    }
}

#[test]
fn finite_extreme_inputs_use_double_precision_products_without_spurious_float_overflow() {
    let mut b = bone();
    b.transform = [
        f32::MAX,
        0.,
        0.,
        0.,
        -f32::MAX,
        1.,
        0.,
        0.,
        0.,
        0.,
        1.,
        0.,
        0.,
        0.,
        0.,
        1.,
    ];
    b.normal = [1. / f32::MAX, 1., 0., 0., 1., 0., 0., 0., 1.];
    let input = RigidVertex {
        position: [f32::MAX, f32::MAX, 0.],
        normal: [f32::MAX, f32::MAX, 0.],
        bone: 0,
        color: 0xffffffff,
    };
    for request in requests() {
        let result = render_rigid(&[input], &[b], request).unwrap();
        assert_eq!(result.vertices[0].position, [0., f32::MAX, 0.]);
        assert!(result.vertices[0].normal[0].abs() < 0.00001);
        assert_eq!(result.vertices[0].normal[1], 1.);
    }
}
