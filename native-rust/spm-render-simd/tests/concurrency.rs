//! Concurrent-execution corpus: the kernels must be pure functions of their inputs.
//!
//! Each test runs the same batch on 1, 2, 4 and 8 threads and requires bit-identical results. That
//! is a real property check rather than a smoke test: any shared mutable scratch, static buffer or
//! lazy-initialised table inside a kernel would show up as a divergence between thread counts.
use std::sync::Arc;
use std::thread;

use spm_native_abi::Isa;
use spm_render_simd::{
    evaluate_bones, render_rigid, supported, transform_positions, BoneInput, BoneOutput,
    RigidVertex,
};

const THREADS: [usize; 4] = [1, 2, 4, 8];

const MATRIX: [f32; 16] = [
    2., 0.25, 0., 0., 0., 3., 0.125, 0., 0.5, 0., 4., 0., 5., 6., 7., 1.,
];

fn batch(count: usize) -> Vec<f32> {
    (0..count * 3).map(|i| i as f32 * 0.03125 - 2.).collect()
}

fn bone_chain(n: usize) -> Vec<BoneInput> {
    (0..n)
        .map(|i| {
            let step = i as f32;
            BoneInput {
                parent: if i == 0 { -1 } else { i as i32 - 1 },
                pivot: [0.25 + step * 0.01, -0.5 + step * 0.02, 0.75],
                rotation: [step * 0.05, step * 0.031, -step * 0.017],
                translation: [step * 0.1 - 1., 0.5, -step * 0.05],
                scale: [1. + step * 0.01, 2. - step * 0.005, 0.5 + step * 0.02],
                flags: 0,
                glow: 7,
                color: 0x8040_2010u32.wrapping_add(i as u32),
            }
        })
        .collect()
}

fn bones_fixture() -> Vec<BoneOutput> {
    (0..2)
        .map(|i| {
            let mirror = if i == 0 { -2. } else { 2. };
            BoneOutput {
                transform: [
                    mirror, 0., 0., 0., 0., 3., 0., 0., 0., 0., 4., 0., 5., 6., 7., 1.,
                ],
                normal: [mirror * 0.5, 0., 0., 0., 1. / 3., 0., 0., 0., 0.25],
                locator: [0.; 3],
                visible: true,
                descendants: true,
                glow: 7,
                color: 0x8040_2010u32.wrapping_add(i as u32),
            }
        })
        .collect()
}

fn rigid_vertices(count: usize) -> Vec<RigidVertex> {
    (0..count)
        .map(|i| RigidVertex {
            position: [i as f32 * 0.5 - 1., i as f32 * 0.25 + 2., 3.],
            normal: [1., 1., 0.],
            bone: (i % 2) as u32,
            color: 0x80ff_8080u32.wrapping_add(i as u32),
        })
        .collect()
}

/// Every tier this host can execute, plus `Auto`.
fn tiers() -> Vec<Isa> {
    [
        Isa::Auto,
        Isa::Scalar,
        Isa::Sse2,
        Isa::Avx,
        Isa::Avx2,
        Isa::Avx2Fma,
        Isa::Neon,
        Isa::Avx512,
    ]
    .into_iter()
    .filter(|isa| supported(*isa))
    .collect()
}

/// Splits `jobs` across `threads` workers and collects their outputs in submission order.
///
/// Results are compared as raw bits: for a pure kernel, concurrency must not perturb a single bit,
/// so an exact comparison is stronger than an epsilon bound and cannot hide a race.
fn run_on_threads<T, F>(threads: usize, jobs: usize, work: F) -> Vec<T>
where
    T: Send + 'static,
    F: Fn(usize) -> T + Send + Sync + 'static,
{
    let work = Arc::new(work);
    let mut results: Vec<(usize, T)> = Vec::with_capacity(jobs);
    thread::scope(|scope| {
        let mut handles = Vec::new();
        for worker in 0..threads.min(jobs) {
            let work = Arc::clone(&work);
            handles.push(scope.spawn(move || {
                let mut local = Vec::new();
                let mut index = worker;
                while index < jobs {
                    local.push((index, work(index)));
                    index += threads;
                }
                local
            }));
        }
        for handle in handles {
            results.extend(handle.join().expect("worker thread must not panic"));
        }
    });
    results.sort_by_key(|(index, _)| *index);
    results.into_iter().map(|(_, value)| value).collect()
}

#[test]
fn transform_is_identical_across_one_two_four_and_eight_threads() {
    let input = Arc::new(batch(128));
    for isa in tiers() {
        let single = {
            let input = Arc::clone(&input);
            transform_positions(&MATRIX, &input, isa).expect("scalar tier must succeed")
        };
        for threads in THREADS {
            let input = Arc::clone(&input);
            let outputs = run_on_threads(threads, 16, move |_| {
                transform_positions(&MATRIX, &input, isa).expect("same request must succeed")
            });
            for (index, (values, used)) in outputs.iter().enumerate() {
                assert_eq!(
                    *used, single.1,
                    "{isa:?} reported a different tier on {threads} threads (job {index})"
                );
                assert_eq!(
                    values.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                    single.0.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                    "{isa:?} diverged on {threads} threads (job {index})"
                );
            }
        }
    }
}

#[test]
fn bones_are_identical_across_one_two_four_and_eight_threads() {
    let root = [
        1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
    ];
    let bones = Arc::new(bone_chain(16));
    for isa in tiers() {
        let single = {
            let bones = Arc::clone(&bones);
            evaluate_bones(&bones, &root, isa).expect("scalar tier must succeed")
        };
        for threads in THREADS {
            let bones = Arc::clone(&bones);
            let outputs = run_on_threads(threads, 16, move |_| {
                evaluate_bones(&bones, &root, isa).expect("same request must succeed")
            });
            for (index, (values, used)) in outputs.iter().enumerate() {
                assert_eq!(*used, single.1, "{isa:?} tier changed on {threads} threads");
                assert_eq!(values.len(), single.0.len());
                for (a, b) in values.iter().zip(&single.0) {
                    for k in 0..16 {
                        assert_eq!(
                            a.transform[k].to_bits(),
                            b.transform[k].to_bits(),
                            "{isa:?} transform[{k}] diverged on {threads} threads (job {index})"
                        );
                    }
                    for k in 0..9 {
                        assert_eq!(
                            a.normal[k].to_bits(),
                            b.normal[k].to_bits(),
                            "{isa:?} normal[{k}] diverged on {threads} threads"
                        );
                    }
                    assert_eq!(a.locator.map(f32::to_bits), b.locator.map(f32::to_bits));
                    assert_eq!(a.visible, b.visible);
                    assert_eq!(a.descendants, b.descendants);
                    assert_eq!(a.glow, b.glow);
                    assert_eq!(a.color, b.color);
                }
            }
        }
    }
}

#[test]
fn rigid_rendering_is_identical_across_one_two_four_and_eight_threads() {
    let bones = Arc::new(bones_fixture());
    let vertices = Arc::new(rigid_vertices(96));
    for isa in tiers() {
        let single = {
            let (bones, vertices) = (Arc::clone(&bones), Arc::clone(&vertices));
            render_rigid(&vertices, &bones, isa).expect("scalar tier must succeed")
        };
        for threads in THREADS {
            let (bones, vertices) = (Arc::clone(&bones), Arc::clone(&vertices));
            let outputs = run_on_threads(threads, 16, move |_| {
                render_rigid(&vertices, &bones, isa).expect("same request must succeed")
            });
            for (index, result) in outputs.iter().enumerate() {
                assert_eq!(
                    result.isa, single.isa,
                    "{isa:?} tier changed on {threads} threads"
                );
                assert_eq!(result.vertices.len(), single.vertices.len());
                for (a, b) in result.vertices.iter().zip(&single.vertices) {
                    assert_eq!(
                        a.position.map(f32::to_bits),
                        b.position.map(f32::to_bits),
                        "{isa:?} position diverged on {threads} threads (job {index})"
                    );
                    assert_eq!(
                        a.normal.map(f32::to_bits),
                        b.normal.map(f32::to_bits),
                        "{isa:?} normal diverged on {threads} threads"
                    );
                    assert_eq!(a.bone, b.bone);
                    assert_eq!(a.glow, b.glow);
                    assert_eq!(a.color, b.color);
                }
            }
        }
    }
}

/// Concurrent mixed-tier traffic, which is the pattern the JVM actually produces when several
/// frames render while a provider probe runs.
#[test]
fn mixed_tiers_and_thread_counts_do_not_interfere() {
    let input = Arc::new(batch(64));
    let available = tiers();
    // One job per (tier, repetition), so different workers exercise different tiers at once.
    let jobs: Vec<(Isa, usize)> = available
        .iter()
        .flat_map(|isa| (0..4).map(move |repeat| (*isa, repeat)))
        .collect();
    let jobs = Arc::new(jobs);
    let expected: Vec<(Vec<u32>, Isa)> = jobs
        .iter()
        .map(|(isa, _)| {
            let (values, used) = transform_positions(&MATRIX, &input, *isa).unwrap();
            (values.iter().map(|v| v.to_bits()).collect(), used)
        })
        .collect();
    let expected = Arc::new(expected);
    for threads in THREADS {
        let (input, jobs, expected) =
            (Arc::clone(&input), Arc::clone(&jobs), Arc::clone(&expected));
        let results = run_on_threads(threads, jobs.len(), move |index| {
            let (isa, _) = jobs[index];
            let (values, used) = transform_positions(&MATRIX, &input, isa).expect("must succeed");
            (
                index,
                values.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                used,
            )
        });
        for (index, bits, used) in results {
            assert_eq!(used, expected[index].1, "tier changed for job {index}");
            assert_eq!(bits, expected[index].0, "values changed for job {index}");
        }
    }
}
