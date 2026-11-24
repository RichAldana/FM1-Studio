//! An Android performance hint session (ADPF) for the engine thread, after Harbor's (markterm,
//! harbor-jni/src/hint.rs). The engine works in short bursts between 1 ms sleeps, which the CPU
//! governor reads as a light load: it may keep the thread on a small core at a low clock until the
//! audio runs dry. Telling the system, window by window, how long the work for that much audio took
//! against a target lets it raise the clock or move the thread first.
//!
//! `APerformanceHint_*` is in libandroid from API 33; FM1 Mix starts at 31, so the functions are
//! looked up at run time. Without them, or without a session, the engine runs as before.
//!
//! Off unless asked for ([ON], Settings > Machine): 0.20 had it always on, with the engine thread at the audio
//! priority (nice -19), and a phone crackled and saturated sooner than before. The priority is gone (the engine,
//! busy most of a core, could starve the thread that hands the sound to Android); the session stays, to try.

use std::sync::atomic::AtomicBool;
use std::time::Duration;

/// Ask for a session when the engine starts (read at each start).
pub static ON: AtomicBool = AtomicBool::new(false);

/// How much audio one report covers.
pub const WINDOW: Duration = Duration::from_millis(10);
/// The share of real time the engine may spend emulating before the system is asked for more.
pub const TARGET_LOAD: f64 = 0.6;

#[cfg(target_os = "android")]
mod sys {
    use std::ffi::{c_void, CStr};

    pub type GetManager = unsafe extern "C" fn() -> *mut c_void;
    pub type CreateSession = unsafe extern "C" fn(*mut c_void, *const i32, usize, i64) -> *mut c_void;
    pub type Report = unsafe extern "C" fn(*mut c_void, i64) -> i32;
    pub type Close = unsafe extern "C" fn(*mut c_void);

    pub struct Api {
        pub get_manager: GetManager,
        pub create: CreateSession,
        pub report: Report,
        pub close: Close,
    }

    unsafe fn sym<T>(lib: *mut c_void, name: &CStr) -> Option<T> {
        let p = libc::dlsym(lib, name.as_ptr());
        // SAFETY: T is the C signature `name` has in the NDK's performance_hint.h
        (!p.is_null()).then(|| std::mem::transmute_copy(&p))
    }

    pub fn load() -> Option<Api> {
        // SAFETY: dlopen / dlsym on the system's libandroid
        unsafe {
            let lib = libc::dlopen(c"libandroid.so".as_ptr(), libc::RTLD_NOW);
            if lib.is_null() {
                return None;
            }
            Some(Api {
                get_manager: sym(lib, c"APerformanceHint_getManager")?,
                create: sym(lib, c"APerformanceHint_createSession")?,
                report: sym(lib, c"APerformanceHint_reportActualWorkDuration")?,
                close: sym(lib, c"APerformanceHint_closeSession")?,
            })
        }
    }
}

/// The session of the thread that made it (make it on the engine thread).
pub struct Hint {
    #[cfg(target_os = "android")]
    api: sys::Api,
    #[cfg(target_os = "android")]
    session: *mut std::ffi::c_void,
}

impl Hint {
    pub fn for_this_thread() -> Option<Hint> {
        #[cfg(target_os = "android")]
        {
            if !ON.load(std::sync::atomic::Ordering::Relaxed) {
                return None;
            }
            let api = sys::load()?;
            let target = (WINDOW.as_nanos() as f64 * TARGET_LOAD) as i64;
            // SAFETY: NDK calls with a one-thread id array; a null manager or session: no ADPF here
            unsafe {
                let manager = (api.get_manager)();
                if manager.is_null() {
                    return None;
                }
                let tid = libc::gettid();
                let session = (api.create)(manager, &tid, 1, target);
                if session.is_null() {
                    return None;
                }
                Some(Hint { api, session })
            }
        }
        #[cfg(not(target_os = "android"))]
        None
    }

    /// The engine spent `busy` making `audio` worth of output.
    pub fn report(&mut self, busy: Duration, audio: Duration) {
        #[cfg(target_os = "android")]
        {
            if audio.is_zero() {
                return;
            }
            // scaled to exactly one window, so that the target stays the same
            let actual = busy.as_nanos() as f64 * WINDOW.as_nanos() as f64 / audio.as_nanos() as f64;
            // SAFETY: the session lives until drop
            unsafe { (self.api.report)(self.session, actual.max(1.0) as i64) };
        }
        #[cfg(not(target_os = "android"))]
        let _ = (busy, audio);
    }
}

impl Drop for Hint {
    fn drop(&mut self) {
        #[cfg(target_os = "android")]
        // SAFETY: closed once, here
        unsafe {
            (self.api.close)(self.session)
        };
    }
}
