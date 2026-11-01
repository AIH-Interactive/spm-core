# spm-core

Independent, host-neutral model runtime in Java 17-compatible bytecode, with an optional Rust compute library. Explicit SIMD implementations are entirely Rust. The Java scalar implementation runs without a native library or the Vector incubator module.

The implementation follows fixed original YesSteveModel and OhMyYesSteveModel reference revisions. OpenYSM code, binaries, behavior oracles and fallbacks are excluded. Reference identities and dependency notices are part of the published resources.

## Build

Build with JDK 25. The API and implementation compile with `--release 17`; tests can run on Java 17, 21 or 25. `scripts/verify.ps1` also requires Python 3.11+ and runs the evidence-tool tests and source/bytecode/artifact isolation audit as mandatory checks.

```sh
./gradlew check
cd native-rust
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cargo build --release --locked
```

Native integration is explicit. Pass the library built from this checkout:

```sh
./gradlew :integration-tests:test -PspmNativeLibrary=/absolute/path/to/libspm_core_native.so
```

Use `spm_core_native.dll` on Windows and `libspm_core_native.dylib` on macOS. On Windows, `scripts/verify.ps1 -Native` also runs the native checks and integration. It supports a locally installed GNU toolchain when MSVC Build Tools are unavailable; published platform support requires execution on that platform.

`-PspmTestJavaVersion=17` selects a Java 17 test launcher. The compiler remains JDK 25. Gradle must be able to discover the requested JRE/JDK (`org.gradle.java.installations.paths` can supply its location).

## Use

The public model representation is `dev.spm.core.api.ModelDocument`: immutable skeleton, bind pose, geometry, material references and source metadata. Import functions require explicit `DecodePolicy`, shared `ResourceBudget` and `CancellationToken` values.

```java
var policy = DecodePolicy.conservative();
var budget = new ResourceBudget(256L << 20);
var cancel = new CancellationToken();
try (ModelAsset asset = BedrockJsonImporter.importAsset(jsonBytes, policy, budget, cancel);
     CpuFrame frame = ModelFrameRenderer.render(asset, asset.document().bindPose(), budget, cancel)) {
    // frame.meshes(): transformed positions/normals/colors, matching UVs and indices.
    // The Host owns texture loading, effects and actual graphics submission.
}
```

`BbmodelJsonImporter.importAsset` imports the supported Blockbench cube subset. `YsmArchiveCodec` reads/writes V1/V2 raw resource archives. `YsmEnvelopeCodec` handles the separate V3 encrypted envelope; `YsmHistoricalCodec` handles its versioned compiled model tree. `YsmModelImporter` projects representable historical variants into the same runtime model while retaining the original archival tree and resource bytes.

Owned archives, decoded payloads, import results, model assets and CPU frames must be closed. Asset retirement stops new leases; an owned render frame keeps its exact source asset charged until the frame closes. Borrowed documents and frame outputs must not outlive their owner/lease. Legacy `importJson` is a construction-only convenience and does not account for retained assets; use `importAsset` for caches and asynchronous rendering. Share the same budget across related work. `ResourceRegistry` binds leases to concrete generations so replacement and late asynchronous completion cannot retire a newer asset accidentally.

`PoseAnimationLibrary` and `PoseAnimationPlayer` evaluate supported Bedrock TRS animation trees into `BonePose`. `SceneInstance` supports explicit inputs, numeric Molang programs, scalar clips and controller graphs. Reusing a frame ID returns its immutable snapshot; effects carry owner/frame identity and are claimed separately by the Host.

`NativeTransforms.load(path, expectedSha256)` verifies explicit native content and the JNI ABI before enabling it. It never downloads code. A failed native load must leave the Java path selected; a failed load that has already entered the JVM may require a fresh process to replace its bindings. CPU/native computation and GPU submission are separate capabilities.

## Current boundaries

This is an implementation candidate, not a declaration of complete SPM migration or upstream feature parity. Current executable geometry is rigid per-bone geometry. Weighted BDEF/SDEF/QDEF, glTF/VRM/MMD, Figura Lua, complete typed Molang and every Host extension are not implemented by the existing rigid API. JSON metadata preservation does not imply animation/script execution.

Bedrock imports currently require one supported geometry per request. BBModel mesh/plugin element types are rejected when unsupported. Historical bone flags without an established allowed-source runtime mapping retain their exact archival values and return an explicit variant limitation instead of guessed rendered behavior. Full V3D filesystem cache management and online cache wire integration are separate work.

GPU buffers, render passes, shader layouts, resource reload fences, Minecraft/Loader adapters, Cloud/P2P policy and game effects belong to SPM. No successful CPU test establishes GPU pixels, game compatibility or unexecuted CPU/OS support.

The configured Maven publications have not been published. A release must freeze a source revision, artifact/native digests, dependency closure and tested capability/platform matrix before publication.

## License

Mixed-license, split by component:

- The independently written SPM Core sources — Java modules, the `native-rust` workspace, build logic and `scripts/` — are MIT. See [LICENSE](LICENSE).
- The historical wire/envelope fixtures under `verification/src/test/resources/ysm/` are verbatim Apache-2.0 upstream test vectors and stay Apache-2.0. See `verification/src/test/resources/ysm/LICENSE`.
- The historical CityHash and ChaCha notices ship inside the `codec` jar at `META-INF/spm-core/legacy-third-party.txt`.
- External dependencies such as `io.airlift:aircompressor` remain under their own licenses and are not relicensed.
- `provenance/allowed-sources.json` records the specification references. No upstream implementation source, binary or artwork is incorporated.

[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) is the authoritative component-by-component split.
