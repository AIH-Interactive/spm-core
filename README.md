# spm-core

Independent, host-neutral 3D model runtime in Java 17-compatible bytecode (`dev.spm.core:*`, version `1.0.0`), with an optional Rust SIMD compute library (`native-rust/`).

- **Java scalar baseline is mandatory:** the pure-Java path runs everywhere without native binaries or `jdk.incubator.vector`.
- **Explicit SIMD is Rust-only:** `native-rust/` provides optional JNI/C ABI acceleration (`SSE2`, `AVX`, `AVX2`, `AVX2+FMA`, `AVX-512` on x86/x86_64; `NEON` on aarch64) verified by explicit path, SHA-256 digest, and ABI handshake before loading.
- **Host-neutral by design:** Minecraft, mod loaders, GPU buffers/shaders, Cloud/P2P transport, and game effects live outside `spm-core` in the host application (such as Sparkle's Morpher).

---

## Upstream References & Provenance

`spm-core` is an independent clean-room runtime. Every upstream reference and redistributed fixture is tracked explicitly in [`provenance/allowed-sources.json`](provenance/allowed-sources.json), [`NOTICE`](NOTICE), and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

### Reference & Attribution Matrix

| Upstream Project | Pinned Revision | Upstream License | How It Is Used in `spm-core` | Source / Assets Incorporated? |
|---|---|---|---|---|
| **[YesSteveModel/YesSteveModel](https://github.com/YesSteveModel/YesSteveModel)** | `c0fff0bc99c58ff623e2f2acc2545521bdd8909c` | Apache-2.0 | Model, material, animation, and runtime behavior specification only. | **No.** No upstream source code, binary, or artwork is included. |
| **[AndreaFrederica/OhMyYesSteveModel](https://github.com/AndreaFrederica/OhMyYesSteveModel)** | `d2536542747a1aa5c02594827f579171c525843c` | Apache-2.0 | 1) Historical YSM format specifications (V1/V2 raw archives, V3 envelopes, compiled payloads 1–32) and independent Java runtime semantics.<br>2) Historical wire primitives (`ModifiedCityHash`, `HistoricalChaCha`, `Mt19937` in `runtime/java-v3/.../legacy/java/`) independently reimplemented in `codec` (`LegacyHash`, `LegacyCipher`).<br>3) **Verbatim synthetic test vectors** under `verification/src/test/resources/ysm/`. | **Test vectors only** (`verification/src/test/resources/ysm/`), redistributed unmodified under **Apache-2.0**. No upstream Java/native implementation source or artwork is included. |
| **[JannisX11/blockbench](https://github.com/JannisX11/blockbench)** | `ec0b2827104d0799c153bfb5f867579c0c44652f` (historical `813379d114aab14a2ff1e40e4bba6985dfa6844e`) | GPL-3.0 | `.bbmodel` file-format and transform-order (`ZYX`/`XYZ`) specification for `format-bbmodel`. | **No.** No Blockbench JS/TS source code or assets are included. |
| **[KhronosGroup/glTF](https://github.com/KhronosGroup/glTF)** | `60d514b8079f19230a2f49ef5884dd09fa3feb6d` | CC-BY-4.0 | glTF 2.0 / GLB container, accessor, buffer, node transform, skinning, morph target, PBR material, and animation specification for `format-gltf` and `GltfAnimationLibrary`. | **No.** Independently written importer and evaluator; no upstream code or fixtures copied. |
| **[FiguraMC/Figura](https://github.com/FiguraMC/Figura)** | `7afeb6a2f7635cfbad87ec65fe116cd3cfd4cdaa` | LGPL-2.1 *(pinned historical commit only)* | Avatar container, NBT/BBModel layout, and Lua API contract specification for `format-figura` and `script-lua`. | **No.** Independently written pure-Java adapter, NBT reader, and bounded Lua 5.3 VM; no Figura code, artwork, or later PolyForm releases used. |

### Historical Wire Primitive & Dependency Notices

- **CityHash** (Copyright (c) 2011 Google, Inc. — MIT): `codec` (`dev.spm.core.codec.legacy.LegacyHash`) implements the historical modified-constant / reversed-reduction wire hash documented in OhMyYesSteveModel. The MIT permission and warranty notice is bundled in [`codec/src/main/resources/META-INF/spm-core/legacy-third-party.txt`](codec/src/main/resources/META-INF/spm-core/legacy-third-party.txt) and inside the published `codec` JAR.
- **ChaCha / HChaCha** (`floodyberry/chacha-opt` — Public Domain / MIT): Attribution for the dynamic ChaCha stream semantics in `dev.spm.core.codec.legacy.LegacyCipher` is retained in [`legacy-third-party.txt`](codec/src/main/resources/META-INF/spm-core/legacy-third-party.txt).
- **Verifiable Test Vectors (Apache-2.0)**: The 34 historical wire vectors (`verification/src/test/resources/ysm/historical/v1.wire`–`v32.wire`, including `v5-face.wire` and `v32-infinite-animation.wire`) and `legacy_v3_dynamic_vector.ysm` are verbatim copies from `AndreaFrederica/OhMyYesSteveModel` (`d2536542747a1aa5c02594827f579171c525843c`). Per-file SHA-256 digests and provenance notes live in [`verification/src/test/resources/ysm/historical/hashes.json`](verification/src/test/resources/ysm/historical/hashes.json), [`PROVENANCE.md`](verification/src/test/resources/ysm/PROVENANCE.md), and [`LICENSE`](verification/src/test/resources/ysm/LICENSE).
- **External Runtime & Build Dependencies (Not Relicensed)**:
  - `io.airlift:aircompressor:0.27` (Apache-2.0) — Zstd compression/decompression consumed by `format-ysm` as an ordinary dependency.
  - `com.google.code.gson:gson:2.10.1` (Apache-2.0) — JSON support consumed by `format-v3d`.
  - Gradle Wrapper (`gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`) — unmodified Apache-2.0 build tooling.

### Strict Exclusions

By repository policy (enforced by [`scripts/audit_core.py`](scripts/audit_core.py) and [`provenance/allowed-sources.json`](provenance/allowed-sources.json)):

- **OpenYSM** source code, native binaries (`ysm-core.dll`, `libysm-core.so`, `libysm-core.dylib`, `com.elfmcys.yesstevemodel.*`, `com.ysm.parser.*`), behavior oracles, and fallbacks are **strictly excluded**.
- Unreviewed legacy Sparkle Morpher algorithms, post-LGPL PolyForm Figura releases, and upstream model artwork are excluded.

---

## Architecture & Modules

### Java Product Modules (`dev.spm.core:*`)

| Module | Responsibility |
|---|---|
| `api` | Public host-neutral contracts (`ModelDocument`, `ModelAsset`, `Skeleton`, `BonePose`, `MeshSkin`, `SkinningMethod`, `SdefParameters`, `MorphTarget`, `DecodePolicy`, `ResourceBudget`, `CancellationToken`, `ImportRequest`, `ImportResult`, `ModelFormatAdapter`). Strictly restricted to `java.*` and `dev.spm.core.api.*` imports. |
| `acceleration-api` | Compute SPI (`CpuComputeProvider`, `Isa`, `ScalarTransforms`, `TransformResult`). |
| `codec` | `CacheEnvelopeCodec` and `dev.spm.core.codec.legacy` (`LegacyHash`, `LegacyCipher`). |
| `format-common` | Shared bounded JSON reader and quota accounting (`BoundedJson`). |
| `format-ysm` | YSM V1/V2 raw archives (`YsmArchiveCodec`), V3 encrypted envelopes (`YsmEnvelopeCodec`), historical compiled payloads 1–32 (`YsmHistoricalCodec`, `YsmModelImporter`). |
| `format-bedrock` | Bounded Bedrock geometry (`minecraft:geometry` `1.12.0`/`1.16.0`) importer (`BedrockJsonImporter`). |
| `format-bbmodel` | Bounded Blockbench `.bbmodel` cube-project importer (`BbmodelJsonImporter`, `ZYX` / `XYZ` rotation order). |
| `format-v3d` | Verified V3D package codec, manifest, metadata, and package validation (`V3dCodec`, `V3dManifest`, `V3dMetadata`, `V3dPackage`). |
| `format-gltf` | Bounded glTF 2.0 and GLB v2 importer (`GltfModelImporter`, `GltfAccessors`, `GltfTransforms`). |
| `format-figura` | Bounded `.figura`/`.figura.zip` and `avatar.nbt` importer (`FiguraAssetImporter`), NBT reader (`FiguraNbtDecoder`, `NbtReader`), vector math (`FiguraVectorApi`), and stateful avatar runtime (`FiguraAvatarRuntime`). |
| `script-lua` | Independently written pure-Java bounded Lua 5.3 runtime (`LuaVm`, `LuaLexer`, `LuaParser`, `LuaEvaluator`, `LuaStdLib`, `LuaPatterns`, `LuaBudget`). |
| `scene` | Animation players, controllers, Molang compiler/evaluator, and lifecycle hooks (`SceneInstance`, `PoseAnimationLibrary`, `PoseAnimationPlayer`, `PoseAnimationController`, `GltfAnimationLibrary`, `ControllerRuntime`, `MolangProgram`, `MolangFunctionLibrary`, `MolangBindings`, `MolangHooks`, `EffectTimeline`, `EffectCursor`, `AnimationDecodeLimits`, `PeerScriptPolicy`). |
| `render-cpu` | Mandatory Java scalar compute backend (`JavaComputeProvider`, `JavaCpuRenderer`, `BoneEvaluator`, `SkinningRenderer`, `MorphApplicator`). |
| `native-jni` | Optional JNI bridge (`NativeComputeProvider`, `NativeTransforms`, `NativeBoneEvaluator`, `NativeRigidRenderer`, `NativeArena`). |
| `runtime` | Frame composition (`ModelFrameRenderer`, `WeightedFrameRenderer`, `CpuFrame`, `BoneWirePacker`, `ResourceRegistry`). |
| `parser` | Unified model import entry point (`SpmModelParser`, `RawYsmParser`, `V3dModelAdapter`). |

### Verification & Native Workspace

- `verification` / `integration-tests` — Non-published test suites covering cross-format equivalence, cache wire compatibility, skinning math, weighted frames, glTF/Figura pipelines, retained asset budgets, Apache-2.0 historical YSM vectors, and explicit native JNI integration.
- `native-rust/` — Rust (`1.98`) workspace containing `spm-native-abi`, `spm-render-scalar`, `spm-render-simd`, and `spm-native-jni` (producing `spm_core_native.dll` / `libspm_core_native.so` / `libspm_core_native.dylib`).

---

## Capabilities & Use

### Unified Model Import (`parser`)

`dev.spm.core.parser.SpmModelParser` is the unified import entry point—no separate native parser binary is required. It handles YSM V1/V2 archives, V3 envelopes, compiled historical payloads 1–32, verified V3D packages, Bedrock/BBModel geometry subsets, glTF 2.0 / GLB, and Figura archives. `FormatHint.AUTO` detects file domains automatically; headerless compiled payloads require `FormatHint.YSM_PAYLOAD`.

```java
var policy = DecodePolicy.localModel(); // or DecodePolicy.conservative() for untrusted/remote input
var budget = new ResourceBudget(256L << 20);
var cancel = new CancellationToken();

var request = new ImportRequest(
    new ModelSource("local-model", new ResourceKey("avatar.ysm"), authorizedResolver),
    FormatHint.AUTO, policy, budget, cancel);

try (ImportResult imported = new SpmModelParser().importModel(request)) {
    for (var model : imported.models()) {
        try (CpuFrame frame = ModelFrameRenderer.render(
                model.asset(), model.asset().document().bindPose(), budget, cancel)) {
            // Submit frame.meshes() through the Host's graphics implementation.
        }
    }
}
```

Key format notes:

- **Extensions:** Additional formats register via `new SpmModelParser(List<ModelFormatAdapter>)` with unique `formatId`s, bounded read-only probing, and exact root snapshot replay.
- **YSM:** Raw YSM authoring JSON accepts C/Java comments while generic JSON remains strict. `YsmModelImporter` projects representable historical variants into `ModelDocument` while retaining archival tree/resource metadata.
- **glTF 2.0 / GLB (`gltf2`):** Maps node TRS/matrix hierarchies, `JOINTS_0`/`WEIGHTS_0`/inverse bind matrices, morph targets & weights, PBR materials & texture references, and `LINEAR`/`STEP`/`CUBICSPLINE` animations (`GltfAnimationLibrary`). Bounded external buffers/images resolve relative to the logical entry; base64 data URIs are supported. Sparse accessors and unsupported required extensions are explicitly rejected.
- **Figura (`figura`):** Reads `.figura` / `.figura.zip` archives and `avatar.nbt` bundles, Blockbench models and animations, embedded/archive PNG textures, `.ogg` sound assets, and `.lua` scripts executed in the bounded pure-Java Lua 5.3 runtime (`FiguraAvatarRuntime`). Nested archives and unsafe path traversal remain rejected.
- **V3D:** Uses a logical `v3d.json` entry and authorized sibling resources, validating decrypted sources against declared wire & hashes; filesystem cache publication remains Host-owned.
- **Skinning & Frames (`runtime`):**
  - Rigid per-bone meshes run through `CpuComputeProvider` (`JavaComputeProvider` by default, or an explicitly loaded `NativeComputeProvider.load(path, expectedSha256, Isa.AUTO)`).
  - Weighted meshes (`ModelFrameRenderer.render(..., SkinningRig, SkinState, ...)`) run `BDEF`, `SDEF`, and `QDEF` skinning plus additive `MorphTarget` deltas through `WeightedFrameRenderer` and `SkinningRenderer`.
- **Ownership & Budgets:** `ImportResult`, `ModelAsset`, decoded payloads, and `CpuFrame` instances are `AutoCloseable` and must be closed. `ResourceRegistry` binds leases to concrete generations so replacement or late asynchronous completion cannot retire a newer asset. `DecodePolicy` distinguishes strict limits (`conservative()`) from local workloads (`localModel()`), and `ResourceBudget.peakUsed()` tracks admitted allocation credits rather than JVM heap size.

### Scene, Animation & Molang (`scene`)

- `PoseAnimationLibrary`, `PoseAnimationPlayer`, `PoseAnimationController`, `GltfAnimationLibrary`, and `ControllerRuntime` evaluate TRS animation trees, clip delays/weights, channel overrides, and shortest-axis blending into `BonePose`.
- `SceneInstance` evaluates numeric and typed Molang (`MolangProgram.evaluateValue` / `evaluate`), `MolangFunctionLibrary`, `MolangHooks` (transactional lifecycle & reverse-order defer flushing), and `EffectTimeline` / `EffectCursor.claim(frame, owner.generation())`.
- `SceneInputs` accepts immutable `MolangBindings` snapshots (no executable host callbacks or game objects). Unsampled queries fail explicitly with `MolangException.bindingRequest()` so the Host can retry with an enriched snapshot.
- `AnimationDecodeLimits` and `PeerScriptPolicy` separate local-asset budgets from shared per-frame Peer budgets (with a 512 active AST depth ceiling).

---

## Build & Verification

Build with **JDK 25** (product modules target `--release 17` with `-Xlint:all -Werror`). Tests can run on Java 17, 21, or 25. Optional native builds require **Rust `1.98.1`** (see `native-rust/rust-toolchain.toml`). Full verification scripts require **Python 3.11+**.

```sh
# Java build & unit/verification suites
./gradlew check

# Select a different test runtime JVM (e.g., Java 17)
./gradlew check -PspmTestJavaVersion=17

# Rust native checks & release build
cd native-rust
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cargo build --release --locked
```

Run explicit native JNI integration against a library built from this checkout (`spm_core_native.dll` on Windows, `libspm_core_native.so` on Linux, `libspm_core_native.dylib` on macOS):

```sh
./gradlew :integration-tests:test -PspmNativeLibrary=/absolute/path/to/libspm_core_native.so
```

On Windows, [`scripts/verify.ps1`](scripts/verify.ps1) runs Gradle verification, Python tooling tests, source/bytecode/JAR isolation auditing (`scripts/audit_core.py`), and platform matrix generation:

```powershell
.\scripts\verify.ps1 -Native -TestJavaVersion 25
```

Additional release/evidence tasks:

- `./gradlew bundleArtifacts` — builds binary, sources, javadoc JARs, POMs, and dependency inventories.
- `./gradlew sbom` — emits an SPDX 2.3 JSON SBOM (`build/reports/sbom.spdx.json`) for the exact Git `HEAD` and resolved Gradle closure.
- `./gradlew publishCandidate` + `scripts/package_candidate.py` — stages an immutable local Maven repository under `build/candidate-dist/` (verified by `scripts/validate_candidate_consumer.py` on JRE 17).

---

## Current Boundaries

- **Host responsibilities:** GPU vertex/bone buffers, render passes, shader layouts, resource reload fences, Minecraft/Loader adapters, Cloud/P2P identity & networking, and game-side effect execution belong to the Host (SPM), not `spm-core`.
- **Native acceleration scope:** `NativeTransforms.load(path, expectedSha256)` never downloads binaries and rejects incompatible ABIs before activation. If a native load fails before binding, execution stays on the Java path; if a broken native library has already entered the JVM, a fresh process may be needed to replace bindings. `NativeComputeProvider` accelerates rigid bone/vertex evaluation; weighted `BDEF`/`SDEF`/`QDEF` + morph skinning executes on the Core scalar path.
- **Format boundaries:** MMD (`PMX`/`PMD`/`VMD`), VRM/VRMA, FBX, and BVH are outside `spm-core`. Bedrock imports currently require one supported geometry per request. Unsupported BBModel mesh/plugin element types are rejected. Historical bone flags without an established allowed-source runtime mapping preserve exact archival values and report an explicit variant limitation rather than guessed behavior.
- **Verification honesty:** Core unit tests do not certify Minecraft/Loader compatibility-mod behavior or real Host Peer counting. Cross-compiled native targets (such as `aarch64-linux-android`) and unexecuted OS/ISA targets are recorded honestly in `scripts/platform_matrix.py` rather than claimed as executed support.

---

## License

`spm-core` uses a **component-split mixed license**:

- **Independently written `spm-core` sources** (Java modules, `native-rust/`, Gradle build logic, and `scripts/`): **MIT License** — see [`LICENSE`](LICENSE).
- **Redistributed historical YSM test vectors** (`verification/src/test/resources/ysm/`): **Apache License 2.0** (verbatim copies from `AndreaFrederica/OhMyYesSteveModel`) — see [`verification/src/test/resources/ysm/LICENSE`](verification/src/test/resources/ysm/LICENSE).
- **Historical algorithm notices** (CityHash MIT, ChaCha/HChaCha Public Domain/MIT): shipped in [`codec/src/main/resources/META-INF/spm-core/legacy-third-party.txt`](codec/src/main/resources/META-INF/spm-core/legacy-third-party.txt).
- **External dependencies & Gradle wrapper**: remain under their own licenses (see `NOTICE` and `THIRD_PARTY_NOTICES.md`).

[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) is the authoritative component-by-component split.
