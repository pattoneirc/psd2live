//! What a host may plug in: a log for what goes wrong, and, with the `host-allocator` feature (on by default), an
//! allocator the runtime takes all its memory from, as Cubism's core lets a host hand it the model's memory.

use std::ffi::{c_char, c_void, CString};
use std::sync::atomic::{AtomicPtr, Ordering};

/// Log levels.
pub const ERROR: i32 = 0;
pub const WARNING: i32 = 1;

pub type LogFunction = unsafe extern "C" fn(level: i32, message: *const c_char, user: *mut c_void);

struct Log {
    function: LogFunction,
    user: *mut c_void,
}

static LOG: AtomicPtr<Log> = AtomicPtr::new(std::ptr::null_mut());

/// Sends [message] to the host's log, when it set one.
pub fn log(level: i32, message: &str) {
    let log = LOG.load(Ordering::Acquire);
    if log.is_null() {
        return;
    }
    let text = CString::new(message.replace('\0', "")).unwrap_or_default();
    unsafe { ((*log).function)(level, text.as_ptr(), (*log).user) }
}

/// Sets the log function (null for none); the previous setting is kept alive, so a log call racing it stays valid.
pub fn set_log(function: Option<LogFunction>, user: *mut c_void) {
    let next = match function {
        Some(function) => Box::into_raw(Box::new(Log { function, user })),
        None => std::ptr::null_mut(),
    };
    // Leaked on purpose: a few bytes per change, and no reader can be left holding freed memory.
    LOG.store(next, Ordering::Release);
}

#[cfg(feature = "host-allocator")]
pub use allocator::set_allocator;

#[cfg(feature = "host-allocator")]
mod allocator {
    use super::*;
    use std::alloc::{GlobalAlloc, Layout, System};
    use std::sync::atomic::AtomicUsize;

    pub type Allocate = unsafe extern "C" fn(size: usize, align: usize, user: *mut c_void) -> *mut c_void;
    pub type Free = unsafe extern "C" fn(pointer: *mut c_void, size: usize, align: usize, user: *mut c_void);

    struct Hooks {
        allocate: Allocate,
        free: Free,
        user: *mut c_void,
    }

    static HOOKS: AtomicPtr<Hooks> = AtomicPtr::new(std::ptr::null_mut());
    /// Allocations taken from the system before any host allocator: once there is one, the allocator stays the system's.
    static SYSTEM_ALLOCATIONS: AtomicUsize = AtomicUsize::new(0);

    /// Routes every allocation to the host's functions once they are set, to the system's before.
    pub struct Dispatch;

    unsafe impl GlobalAlloc for Dispatch {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            let hooks = HOOKS.load(Ordering::Acquire);
            if hooks.is_null() {
                SYSTEM_ALLOCATIONS.fetch_add(1, Ordering::Relaxed);
                return System.alloc(layout);
            }
            ((*hooks).allocate)(layout.size(), layout.align(), (*hooks).user) as *mut u8
        }

        unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
            let hooks = HOOKS.load(Ordering::Acquire);
            if hooks.is_null() {
                return System.dealloc(pointer, layout);
            }
            ((*hooks).free)(pointer as *mut c_void, layout.size(), layout.align(), (*hooks).user)
        }
    }

    #[global_allocator]
    static GLOBAL: Dispatch = Dispatch;

    /// Takes every allocation from now on from [allocate] and [free]; false, changing nothing, once the runtime has
    /// allocated from the system (memory must go back where it came from) or for a missing function.
    pub fn set_allocator(allocate: Option<Allocate>, free: Option<Free>, user: *mut c_void) -> bool {
        let (Some(allocate), Some(free)) = (allocate, free) else { return false };
        if SYSTEM_ALLOCATIONS.load(Ordering::Acquire) != 0 || !HOOKS.load(Ordering::Acquire).is_null() {
            return false;
        }
        // The hooks live for the rest of the process; built on the stack and placed with the host's own allocator,
        // so setting them takes nothing from the system.
        let pointer = unsafe { allocate(std::mem::size_of::<Hooks>(), std::mem::align_of::<Hooks>(), user) } as *mut Hooks;
        if pointer.is_null() {
            return false;
        }
        unsafe { pointer.write(Hooks { allocate, free, user }) };
        // The host sets it first, from one thread, before anything else of the runtime runs.
        HOOKS.compare_exchange(std::ptr::null_mut(), pointer, Ordering::AcqRel, Ordering::Acquire).is_ok()
    }
}
