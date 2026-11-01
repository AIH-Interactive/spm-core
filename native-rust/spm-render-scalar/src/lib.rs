//! Scalar mathematical baseline. Validation belongs to the batch boundary.
//!
//! This crate is both the native-free implementation and the differential oracle for the SIMD
//! dispatch layer. It deliberately exposes only the allocation-free `_into` entry point: the caller
//! owns the output buffer and can size it fallibly, so no code path here can abort the process by
//! failing an infallible allocation for an attacker-sized batch.

/// Writes `input.len()` values into `output`, which must hold at least that many.
///
/// `input.len()` must be a multiple of three; a trailing partial position is ignored, exactly as
/// `Iterator::as_chunks` does. Callers at the batch boundary reject that shape before reaching here.
pub fn transform_positions_into(matrix: &[f32; 16], input: &[f32], output: &mut [f32]) {
    for (p, out) in input
        .as_chunks::<3>()
        .0
        .iter()
        .zip(output.as_chunks_mut::<3>().0)
    {
        for row in 0..3 {
            out[row] = ((matrix[row] * p[0] + matrix[4 + row] * p[1]) + matrix[8 + row] * p[2])
                + matrix[12 + row];
        }
    }
}

/// Convenience wrapper for tests and small fixtures; production code uses `_into`.
///
/// Returns `None` instead of aborting when the output cannot be allocated, so even this helper
/// cannot turn a huge input into a process-wide allocation failure.
pub fn transform_positions(matrix: &[f32; 16], input: &[f32]) -> Option<Vec<f32>> {
    let mut output = Vec::new();
    output.try_reserve_exact(input.len()).ok()?;
    output.resize(input.len(), 0.);
    transform_positions_into(matrix, input, &mut output);
    Some(output)
}
