//! Scalar mathematical baseline. Validation belongs to the batch boundary.
pub fn transform_positions(matrix: &[f32; 16], input: &[f32]) -> Vec<f32> {
    let mut output = Vec::with_capacity(input.len());
    for p in input.as_chunks::<3>().0 {
        for row in 0..3 {
            output.push(
                ((matrix[row] * p[0] + matrix[4 + row] * p[1]) + matrix[8 + row] * p[2])
                    + matrix[12 + row],
            );
        }
    }
    output
}
