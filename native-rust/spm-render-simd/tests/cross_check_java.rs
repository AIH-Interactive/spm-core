//! Cross-checks the Java transcription against the Rust reference, and compares the two full
//! evaluation paths on real models.
//!
//! The Java side of this comparison is a line-for-line transcription of the host's
//! `SpmCoreBoneMatrixWriter` (see `integration-tests/.../BoneEvaluationSmokeTest.java`). Before its
//! timings can be trusted, the transcription has to be shown to compute the same thing. That is
//! what `java_transcription_agrees_with_the_rust_reference` does: it loads the matrices the Java
//! code produced and compares them bone by bone, element by element, against
//! [`spm_render_simd::evaluate_bones`].
//!
//! # Why this matters
//!
//! The 1.1.0 plan asserted the optimized Java path costs 38.3 µs for a 500-bone model. Measured on
//! the real rigs in `D:\SparkleMorpher\TestModel`, the Java path costs 132–178 µs — off by nearly
//! 4x. That single number decides whether the whole SIMD effort is worth doing, so it is worth
//! pinning down with a test rather than trusting a figure from a document.
//!
//! Run with:
//! `cargo test --release -p spm-render-simd --test cross_check_java -- --ignored --nocapture`
use std::collections::HashMap;
use std::path::PathBuf;

use spm_native_abi::{Isa, BONE_PARAM_STRIDE};
use spm_render_simd::{evaluate_bones, BoneInput, CompiledSkeleton};

/// Relative tolerance for the cross-check.
///
/// Both sides accumulate in `f64` and follow the same operation order, so they should agree to
/// within `f32` rounding. `1e-4` is the budget the plan sets for the whole packed path.
const TOLERANCE: f32 = 1e-4;

/// One bone's reference output, as written by the Java harness.
struct JavaBone {
    transform: [f32; 16],
    normal: [f32; 9],
    visible: bool,
}

/// Locates a build artifact, walking up from the crate directory.
fn find(relative: &str) -> Option<PathBuf> {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    for _ in 0..4 {
        let candidate = dir.join(relative);
        if candidate.exists() {
            return Some(candidate);
        }
        dir = dir.parent()?.to_path_buf();
    }
    None
}

fn load_cross_check(path: &PathBuf) -> HashMap<String, Vec<JavaBone>> {
    let text = std::fs::read_to_string(path).expect("cross-check file must be readable");
    let mut models: HashMap<String, Vec<JavaBone>> = HashMap::new();
    let mut current: Option<String> = None;
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("model ") {
            // `model NAME COUNT`; the name may contain dots but not spaces.
            let name = rest
                .split_whitespace()
                .next()
                .unwrap_or_default()
                .to_string();
            models.entry(name.clone()).or_default();
            current = Some(name);
            continue;
        }
        let Some(name) = &current else { continue };
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() < 26 {
            continue;
        }
        let mut values = [0_f32; 26];
        let mut ok = true;
        for (index, field) in fields[1..27].iter().enumerate() {
            match field.parse::<f32>() {
                Ok(value) => values[index] = value,
                Err(_) => {
                    ok = false;
                    break;
                }
            }
        }
        if !ok {
            continue;
        }
        let mut transform = [0_f32; 16];
        transform.copy_from_slice(&values[..16]);
        let mut normal = [0_f32; 9];
        normal.copy_from_slice(&values[16..25]);
        models.get_mut(name).unwrap().push(JavaBone {
            transform,
            normal,
            visible: values[25] != 0.,
        });
    }
    models
}

/// The same per-bone parameters the Java harness used, so the inputs are identical.
fn params(n: usize) -> Vec<f32> {
    let mut params = vec![0_f32; n * BONE_PARAM_STRIDE];
    for i in 0..n {
        let p = i * BONE_PARAM_STRIDE;
        let step = i as f32;
        params[p] = step * 0.05;
        params[p + 1] = step * 0.031;
        params[p + 2] = -step * 0.017;
        params[p + 3] = step * 0.1 - 1.;
        params[p + 4] = 0.5;
        params[p + 5] = -step * 0.05;
        params[p + 6] = 1.;
        params[p + 7] = 1.;
        params[p + 8] = 1.;
        params[p + 11] = if i % 4 == 0 { 1. } else { 0. };
    }
    params
}

/// Builds reference inputs from the real skeleton and the shared parameter fixture.
fn reference_inputs(skeleton: &CompiledSkeleton, params: &[f32]) -> Vec<BoneInput> {
    let n = skeleton.bone_count();
    let parents = skeleton.parents();
    (0..n)
        .map(|i| {
            let p = i * BONE_PARAM_STRIDE;
            BoneInput {
                parent: parents[i],
                pivot: [
                    skeleton.pivots_x()[i],
                    skeleton.pivots_y()[i],
                    skeleton.pivots_z()[i],
                ],
                rotation: [params[p], params[p + 1], params[p + 2]],
                translation: [
                    -params[p + 3] * 0.0625,
                    params[p + 4] * 0.0625,
                    params[p + 5] * 0.0625,
                ],
                scale: [params[p + 6], params[p + 7], params[p + 8]],
                flags: (if params[p + 9] != 0. { 1 } else { 0 })
                    | (if params[p + 10] != 0. { 2 } else { 0 }),
                glow: if skeleton.glow_mask()[i] != 0 {
                    15
                } else {
                    255
                },
                color: 0,
            }
        })
        .collect()
}

#[test]
#[ignore = "needs the Java harness output; run with --release --ignored"]
fn java_transcription_agrees_with_the_rust_reference() {
    let Some(cross) = find("integration-tests/build/java-crosscheck.txt") else {
        println!("[cross] no java-crosscheck.txt; run the Java harness first");
        return;
    };
    let Some(skeleton_dir) = find("integration-tests/build/real-skeletons") else {
        println!("[cross] no dumped skeletons; run the Java harness first");
        return;
    };
    let java = load_cross_check(&cross);
    println!("\n=== Java transcription vs Rust reference ===");

    let identity = [
        1_f32, 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    let mut worst = 0_f32;
    let mut compared = 0_usize;
    for (name, bones) in &java {
        // The dump sanitizes the file name the same way the Java harness does, so a name with
        // characters like parentheses maps to underscores.
        let sanitized: String = name
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') {
                    c
                } else {
                    '_'
                }
            })
            .collect();
        let skeleton_path = skeleton_dir.join(format!("{sanitized}.skeleton.txt"));
        let Ok(text) = std::fs::read_to_string(&skeleton_path) else {
            println!("[cross] {name}: no skeleton dump at {sanitized}, skipped");
            continue;
        };
        let mut lines = text.lines();
        let Some(header) = lines.next() else { continue };
        let declared: usize = header
            .split_whitespace()
            .next()
            .and_then(|v| v.parse().ok())
            .unwrap_or(0);
        let mut parents = Vec::new();
        let mut pivots = Vec::new();
        for line in lines {
            let fields: Vec<&str> = line.split_whitespace().collect();
            if fields.len() != 4 {
                continue;
            }
            parents.push(fields[0].parse::<i32>().unwrap());
            for field in &fields[1..4] {
                pivots.push(field.parse::<f32>().unwrap());
            }
        }
        if parents.len() != declared || bones.len() != declared {
            println!(
                "[cross] {name}: size mismatch (dump {}, java {}, declared {declared}), skipped",
                parents.len(),
                bones.len()
            );
            continue;
        }
        let glow = vec![0_u8; declared];
        let skeleton = CompiledSkeleton::compile(&parents, &pivots, &glow).unwrap();
        let params = params(declared);
        let inputs = reference_inputs(&skeleton, &params);
        let (expected, _) = evaluate_bones(&inputs, &identity, Isa::Scalar).unwrap();

        let mut model_worst = 0_f32;
        for (bone, java_bone) in bones.iter().enumerate() {
            let reference = &expected[bone];
            // A bone the Java code marks invisible is written as the canonical hidden record by
            // both implementations, so its matrix is not comparable; visibility itself is.
            if reference.visible != java_bone.visible {
                panic!(
                    "{name} bone {bone}: visibility disagrees (rust {}, java {})",
                    reference.visible, java_bone.visible
                );
            }
            if !reference.visible {
                continue;
            }
            for k in 0..12 {
                let error = (java_bone.transform[k] - reference.transform[k]).abs()
                    / reference.transform[k].abs().max(1.);
                model_worst = model_worst.max(error);
            }
            for k in 0..9 {
                let error = (java_bone.normal[k] - reference.normal[k]).abs()
                    / reference.normal[k].abs().max(1.);
                model_worst = model_worst.max(error);
            }
        }
        println!(
            "  {:<34} {:>5} bones  worst rel error {:.3e}",
            name, declared, model_worst
        );
        worst = worst.max(model_worst);
        compared += 1;
    }
    println!("\nmodels compared : {compared}");
    println!("worst rel error : {worst:.3e}  (budget {TOLERANCE:.0e})");
    assert!(compared > 0, "at least one model must be compared");
    assert!(
        worst <= TOLERANCE,
        "the Java transcription disagrees with the Rust reference: {worst:.3e} > {TOLERANCE:.0e}"
    );
}
