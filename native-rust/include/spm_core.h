#ifndef SPM_CORE_H
#define SPM_CORE_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct SpmComputeOutcome {
    int32_t status;
    uint32_t completed;
    uint32_t isa;
    uint32_t abi;
} SpmComputeOutcome;
/* ABI1: matrix16 float32, positions count*3 float32, all little endian.
 * Unaligned and overlapping buffers allowed. Caller owns valid allocations,
 * supplies actual capacities, and prevents concurrent access. Failure writes
 * no output and completed=0.
 * Request/observed ISA wire codes:
 *   auto0 scalar1 sse2=2 avx3 avx2=4 avx2+fma=5 neon6 avx512f=7
 * The observed code is the tier whose instructions the kernel executed and is
 * never wider than the request; auto0 is never observed. */
SpmComputeOutcome spm_transform_positions_v1(const uint8_t *matrix, size_t matrix_bytes,
    const uint8_t *input, size_t input_bytes, uint8_t *output, size_t output_bytes,
    uint32_t count, uint32_t requested_isa);
#ifdef __cplusplus
}
#endif
#endif
