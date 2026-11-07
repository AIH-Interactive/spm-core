# Third-party notices

SPM Core is a mixed-license repository. This file is the authoritative map of
which license applies to which part of the tree. It does not replace the full
text of any license; it tells you where each one lives.

## 1. Independently written SPM Core code — MIT

Applies to everything written independently for this project, including all
Java sources under the product modules, the Rust workspace under `native-rust/`,
the Gradle build logic, and the evidence tooling under `scripts/`.

License text: [LICENSE](LICENSE) (MIT).

## 2. Upstream test vectors — Apache License 2.0

The following binary fixtures are **verbatim copies** of files published by
[AndreaFrederica/OhMyYesSteveModel](https://github.com/AndreaFrederica/OhMyYesSteveModel)
at commit `d2536542747a1aa5c02594827f579171c525843c`. They are redistributed
unmodified and remain under the upstream Apache License 2.0.

| Path | Upstream path |
|---|---|
| `verification/src/test/resources/ysm/historical/v1.wire` … `v32.wire` (34 files, includes `v5-face.wire`, `v32-infinite-animation.wire`) | `runtime/java-v3/src/test/resources/historical/` |
| `verification/src/test/resources/ysm/legacy_v3_dynamic_vector.ysm` | `runtime/java-v3/src/test/resources/legacy_v3_dynamic_vector.ysm` |

License text: [verification/src/test/resources/ysm/LICENSE](verification/src/test/resources/ysm/LICENSE)
(Apache License 2.0). Per-file digests are pinned in
`verification/src/test/resources/ysm/historical/hashes.json` and in
`verification/src/test/resources/ysm/PROVENANCE.md`.

These are synthetic transform vectors, not artwork and not semantically valid
models. No upstream implementation source, binary, behavior oracle or fallback
is incorporated anywhere in this repository.

## 3. Historical format algorithms — MIT / Public Domain

`codec/src/main/java/dev/spm/core/codec/legacy/` reimplements historical wire
primitives independently, but derives from prior art that carries its own terms.
The full notices are bundled as a product resource at
`codec/src/main/resources/META-INF/spm-core/legacy-third-party.txt` and are
shipped inside the `codec` jar.

- **CityHash** — Copyright (c) 2011 Google, Inc. — MIT.
  The historical variant uses modified constants and a reversed two-word
  reduction; the MIT permission and warranty text is retained.
- **ChaCha/HChaCha** — attribution to
  [floodyberry/chacha-opt](https://github.com/floodyberry/chacha-opt) —
  upstream declares Public Domain or MIT.

## 4. Runtime dependencies — not relicensed

External dependencies are consumed as ordinary artifacts and are **not**
shaded, vendored or relicensed by this project.

| Component | Version | License |
|---|---|---|
| `io.airlift:aircompressor` | 0.27 | Apache License 2.0 |

Dependency coordinates and content hashes are pinned in each module's
`gradle.lockfile` and in `build/metadata/dependencies.json`. The resolved
dependency closure is **not** a certified SBOM.

## 5. Build tooling — Apache License 2.0

`gradle/wrapper/gradle-wrapper.jar` and `gradlew` / `gradlew.bat` are unmodified
Gradle wrapper artifacts, distributed under the Apache License 2.0. They are
build infrastructure, not part of the published product license.

## 6. Reference-only specifications — no code incorporated

Behavior and file-format specifications were studied from the projects below.
No source code, application binary, artwork or test asset from them is included
in this repository. Recorded in `provenance/allowed-sources.json`.

| Project | Commit | Role |
|---|---|---|
| [YesSteveModel/YesSteveModel](https://github.com/YesSteveModel/YesSteveModel) | `c0fff0bc99c58ff623e2f2acc2545521bdd8909c` | model, material, animation and runtime behavior specification |
| [AndreaFrederica/OhMyYesSteveModel](https://github.com/AndreaFrederica/OhMyYesSteveModel) | `d2536542747a1aa5c02594827f579171c525843c` | historical formats, independent Java runtime semantics, Apache-2.0 licensed synthetic fixtures |
| [JannisX11/blockbench](https://github.com/JannisX11/blockbench) | `ec0b2827104d0799c153bfb5f867579c0c44652f` | file-format and transform-order specification only |

Blockbench is GPL-3.0 as an application. Only its documented file-format
specification was used; this repository contains no JavaScript or TypeScript
sources and no Blockbench code.

## Exclusions

OpenYSM source or binaries, OpenYSM behavior oracles, unreviewed legacy Sparkle
Morpher algorithms, and upstream model artwork are excluded from this
repository by policy.

## Status

The component split above is derived from byte-level and token-level comparison
against the pinned upstream revisions. It is compliance-oriented documentation,
not a blanket origin certification of every upstream file, and it does not
constitute legal advice.
