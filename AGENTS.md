# spm-core implementation constraints

- Java public API and baseline use Java17-compatible JDK types. Compile with the pinned JDK25 toolchain and `--release 17`.
- All explicit SIMD is Rust. Java scalar is mandatory; Rust scalar cannot substitute for native-free operation.
- Use original YesSteveModel and the explicitly allowed non-OpenYSM forks recorded in provenance. Never use OpenYSM or source-uncertified old SPM implementation as code, behavior oracle, fixture generator or fallback.
- Keep Minecraft, Loader, GPU resources, Cloud/P2P identity and Host effects outside this runtime.
- Freeze logical data, JNI, C ABI and Host-specific layouts separately. Reject incompatible libraries before use. Failed batches must not publish partial output.
- Public arrays/collections are immutable snapshots. Leases bind to an actual generation. A retired generation cannot debit a replacement.
- Test real behavior before implementation; run Java-only and native paths separately. Never claim ISA/OS/game/GPU support from source inspection or cross-compilation alone.
- Build artifacts are ignored. Essential source/dependency/license manifests are product files; task plans and work logs belong under D:/SparkleMorpher/docs.
- Do not create or update codegraph indexes automatically. Do not use the retired .worktrees folders.
