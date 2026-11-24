//! Measures the platform trigonometry cost, which the packed path pays three times per dirty bone.
//!
//! The portable walk reduces a bone's rotation by calling `f32::sin_cos`, which lands in the system C
//! library. That is the one term in the packed path that no amount of vectorisation in this crate
//! can touch, and it is a property of the *platform*: bionic, glibc, the legacy `msvcrt.dll` and the
//! UCRT are different implementations with visibly different throughput. So a trigonometry figure
//! measured on one platform cannot be transferred to another, and a cross-platform difference in the
//! packed path must be attributed by measurement rather than by assumption.
//!
//! On Windows the binary also reports both CRTs directly: a `windows-gnu` build links the legacy
//! `msvcrt.dll`, while an MSVC build links `ucrtbase.dll`. Loading both at run time separates "this
//! build's libm is slow" from "Windows is slow", which a single-build measurement cannot.
//!
//! Run with:
//! `cargo test --release -p spm-render-simd --test bench_trig -- --ignored --nocapture --test-threads=1`
use std::hint::black_box;
use std::time::Instant;

/// Angles per pass. Large enough that the loop is not dominated by its own bookkeeping.
const COUNT: usize = 4096;

/// Passes per measurement, after a warm-up that lets the branch predictors and caches settle.
const ITERATIONS: usize = 3000;

/// Rotations a real animation produces: inside `|x| <= pi`, and never exactly zero.
///
/// The range matters. `sin_cos(0.)` is a special case in the packed path (it short-circuits to
/// `(0, 1)`), and a benchmark built on it would measure an addition instead of a polynomial.
fn angles() -> [f32; COUNT] {
    let mut angles = [0.0_f32; COUNT];
    for (index, angle) in angles.iter_mut().enumerate() {
        let t = index as f32 / COUNT as f32;
        *angle = (t * 6.2 - 3.1) + 0.001;
    }
    angles
}

/// Times `run` over `angles` and reports nanoseconds per call.
fn time<F>(angles: &[f32], label: &str, mut run: F) -> f64
where
    F: FnMut(&[f32], &mut [f32], &mut [f32]),
{
    let mut sin_out = [0.0_f32; COUNT];
    let mut cos_out = [0.0_f32; COUNT];
    for _ in 0..20 {
        run(angles, &mut sin_out, &mut cos_out);
    }
    let start = Instant::now();
    for _ in 0..ITERATIONS {
        run(angles, &mut sin_out, &mut cos_out);
    }
    let ns = start.elapsed().as_secs_f64() * 1e9 / (ITERATIONS * COUNT) as f64;
    black_box(&sin_out);
    black_box(&cos_out);
    println!("{label:<34} {ns:>8.2} ns/call");
    ns
}

#[cfg(target_os = "windows")]
mod windows_crt {
    use super::time;
    use std::ffi::{c_char, c_void};

    #[link(name = "kernel32")]
    extern "system" {
        fn LoadLibraryA(name: *const c_char) -> *mut c_void;
        fn GetProcAddress(module: *mut c_void, name: *const c_char) -> *mut c_void;
    }

    type Unary = extern "C" fn(f32) -> f32;

    /// Resolves `symbol` out of `module`, or `None` when the module or symbol is absent.
    fn load(module: &str, symbol: &str) -> Option<Unary> {
        fn with_nul(text: &str) -> Vec<u8> {
            let mut bytes = Vec::with_capacity(text.len() + 1);
            bytes.extend_from_slice(text.as_bytes());
            bytes.push(0);
            bytes
        }
        let module_name = with_nul(module);
        let symbol_name = with_nul(symbol);
        // SAFETY: both strings are NUL-terminated and outlive the calls; the returned handle is used
        // only for `GetProcAddress` and is never freed, which is harmless for a system DLL.
        unsafe {
            let handle = LoadLibraryA(module_name.as_ptr().cast());
            if handle.is_null() {
                return None;
            }
            let address = GetProcAddress(handle, symbol_name.as_ptr().cast());
            if address.is_null() {
                return None;
            }
            // SAFETY: `sinf`/`cosf` take one `f32` and return one `f32`, which is `Unary` exactly.
            Some(std::mem::transmute::<*mut c_void, Unary>(address))
        }
    }

    pub(super) fn report(angles: &[f32]) {
        for (module, crt) in [
            ("msvcrt.dll", "msvcrt (windows-gnu build)"),
            ("ucrtbase.dll", "ucrtbase (msvc build)"),
        ] {
            for symbol in ["sinf", "cosf"] {
                let Some(call) = load(module, symbol) else {
                    println!("{:<34} unavailable", format!("{symbol} {crt}"));
                    continue;
                };
                time(
                    angles,
                    &format!("{symbol} {crt}"),
                    |input, sin_out, cos_out| {
                        let out = if symbol == "sinf" { sin_out } else { cos_out };
                        for (value, angle) in out.iter_mut().zip(input) {
                            *value = call(*angle);
                        }
                    },
                );
            }
        }
    }
}

#[test]
#[ignore = "timing benchmark; run with --release --ignored"]
fn platform_trig_throughput() {
    println!(
        "=== platform trig, target {} / {} ===",
        std::env::consts::ARCH,
        std::env::consts::OS
    );
    let angles = angles();

    // `f32::sin_cos` is `(self.sin(), self.cos())`, so this pair is two library calls and should
    // land close to the sum of the two measured separately. Measuring all three makes that visible
    // instead of assumed.
    let sin = time(&angles, "sinf via std", |input, sin_out, _| {
        for (value, angle) in sin_out.iter_mut().zip(input) {
            *value = angle.sin();
        }
    });
    let cos = time(&angles, "cosf via std", |input, _, cos_out| {
        for (value, angle) in cos_out.iter_mut().zip(input) {
            *value = angle.cos();
        }
    });
    let pair = time(&angles, "sin_cos via std", |input, sin_out, cos_out| {
        for ((sin_value, cos_value), angle) in sin_out.iter_mut().zip(cos_out.iter_mut()).zip(input)
        {
            let (sin_angle, cos_angle) = angle.sin_cos();
            *sin_value = sin_angle;
            *cos_value = cos_angle;
        }
    });
    println!(
        "{:<34} {:>8.2} ns/call",
        "sin + cos (sum of parts)",
        sin + cos
    );
    println!("{:<34} {:>8.2} ns/call", "sin_cos (actual pair)", pair);

    #[cfg(target_os = "windows")]
    windows_crt::report(&angles);
}
