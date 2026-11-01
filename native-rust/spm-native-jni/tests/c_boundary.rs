use spm_core_native::spm_transform_positions_v1;
use spm_native_abi::Isa;

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
