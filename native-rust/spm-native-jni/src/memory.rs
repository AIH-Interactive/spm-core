//! Rust-owned, call-scoped arenas. No JNI reference, JNIEnv, host or buffer is retained here.
//!
//! Java confines each owner and every borrowed view to one synchronous try-with-resources call.
//! A provider cannot close somebody else's in-flight allocation. Handles never repeat, so stale
//! closes cannot free a replacement generation. Reflection/unsafe code violating that private
//! confinement contract is outside the supported Java API.
use jni::{
    objects::{JClass, JValue},
    sys::{jboolean, jint, jlong, jobject},
    JNIEnv,
};
use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    ptr::null_mut,
    sync::{Mutex, MutexGuard},
};

struct Allocation {
    handle: jlong,
    memory: Vec<u8>,
}

struct Registry {
    next_handle: jlong,
    live: Vec<Allocation>,
}

impl Registry {
    const fn new() -> Self {
        Self {
            next_handle: 1,
            live: Vec::new(),
        }
    }

    fn allocate(&mut self, bytes: usize) -> Result<(jlong, *mut u8), &'static str> {
        let next = self
            .next_handle
            .checked_add(1)
            .ok_or("Arena handle exhaustion")?;
        // Zero-capacity direct buffers still need a non-null, stable backing address.
        let physical = bytes.max(1);
        let mut memory = Vec::new();
        memory
            .try_reserve_exact(physical)
            .map_err(|_| "Arena allocation failed")?;
        memory.resize(physical, 0);
        self.live
            .try_reserve(1)
            .map_err(|_| "Arena registry allocation failed")?;
        let pointer = memory.as_mut_ptr();
        let handle = self.next_handle;
        self.live.push(Allocation { handle, memory });
        self.next_handle = next;
        Ok((handle, pointer))
    }

    fn free(&mut self, handle: jlong) {
        if let Some(index) = self.live.iter().position(|entry| entry.handle == handle) {
            // Dropping the Vec releases physical storage now, independently of Java GC.
            self.live.swap_remove(index);
        }
    }

    fn owned(&self, bytes: bool) -> jlong {
        if bytes {
            self.live.iter().fold(0_i64, |sum, entry| {
                sum.saturating_add(jlong::try_from(entry.memory.capacity()).unwrap_or(jlong::MAX))
            })
        } else {
            jlong::try_from(self.live.len()).unwrap_or(jlong::MAX)
        }
    }
}

static ARENAS: Mutex<Registry> = Mutex::new(Registry::new());

fn registry() -> MutexGuard<'static, Registry> {
    // A caught panic must not prevent all later owners from freeing memory.
    ARENAS
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

struct PendingAllocation(Option<jlong>);
impl Drop for PendingAllocation {
    fn drop(&mut self) {
        if let Some(handle) = self.0 {
            registry().free(handle);
        }
    }
}

/// Every exit, including JNI constructor exceptions and unwinding, drops the pending owner.
/// Fallible reserves recover allocation failures where possible. Process aborts (including a
/// global allocator OOM abort) and VM termination cannot be recovered by catch_unwind.
#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativeArena_nAllocate(
    mut env: JNIEnv,
    class: JClass,
    bytes: jint,
) -> jobject {
    catch_unwind(AssertUnwindSafe(|| {
        if env.exception_check().unwrap_or(true) {
            return null_mut();
        }
        let Ok(bytes) = usize::try_from(bytes) else {
            let _ = env.throw_new(
                "java/lang/IllegalArgumentException",
                "Negative arena capacity",
            );
            return null_mut();
        };
        let result = (|| {
            let (handle, pointer) = registry().allocate(bytes)?;
            let mut pending = PendingAllocation(Some(handle));
            // SAFETY: the registered Vec cannot resize, and its Java owner is the only supported
            // release route. Construction failures drop pending before returning. No view is
            // published from the wrapper, and the owner closes after its final copy/read.
            let buffer = unsafe { env.new_direct_byte_buffer(pointer, bytes) }
                .map_err(|_| "Direct arena buffer construction failed")?;
            let owner = env
                .new_object(
                    class,
                    "(JLjava/nio/ByteBuffer;)V",
                    &[JValue::Long(handle), JValue::Object(buffer.as_ref())],
                )
                .map_err(|_| "Arena owner construction failed")?;
            pending.0 = None;
            Ok::<_, &'static str>(owner.into_raw())
        })();
        match result {
            Ok(owner) => owner,
            Err(message) => {
                if !env.exception_check().unwrap_or(true) {
                    let _ = env.throw_new("java/lang/OutOfMemoryError", message);
                }
                null_mut()
            }
        }
    }))
    .unwrap_or(null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativeArena_nFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| registry().free(handle)));
}

#[no_mangle]
pub extern "system" fn Java_dev_spm_core_nativejni_NativeArena_nOwned(
    _env: JNIEnv,
    _class: JClass,
    bytes: jboolean,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| registry().owned(bytes != 0))).unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_size_invalid_handles_and_generations_release_exactly_once() {
        let mut registry = Registry::new();
        let (old, first) = registry.allocate(0).unwrap();
        assert!(!first.is_null());
        assert_eq!(registry.owned(false), 1);
        assert_eq!(registry.owned(true), 1);
        registry.free(old);
        let (new, _) = registry.allocate(20).unwrap();
        assert!(new > old);
        for invalid in [old, -1, 0, jlong::MAX] {
            registry.free(invalid);
        }
        assert_eq!(registry.owned(false), 1);
        assert_eq!(registry.owned(true), 20);
        registry.free(new);
        registry.free(new);
        assert_eq!(registry.owned(false), 0);
        assert_eq!(registry.owned(true), 0);
    }

    #[test]
    fn exhausted_generation_and_overflow_do_not_allocate() {
        let mut registry = Registry::new();
        assert!(registry.allocate(usize::MAX).is_err());
        assert_eq!(registry.owned(false), 0);
        registry.next_handle = jlong::MAX;
        assert!(registry.allocate(16).is_err());
        assert_eq!(registry.owned(false), 0);
    }

    #[test]
    fn unwinding_before_java_owner_transfer_releases_registered_memory() {
        let baseline = registry().owned(false);
        let result = catch_unwind(|| {
            let (handle, _) = registry().allocate(64).unwrap();
            let _pending = PendingAllocation(Some(handle));
            panic!("simulate construction failure");
        });
        assert!(result.is_err());
        assert_eq!(registry().owned(false), baseline);
    }
}
