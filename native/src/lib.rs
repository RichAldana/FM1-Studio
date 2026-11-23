//! fm1mix: the emulated FM-1 (fm1-emu) plus a USB-audio mixer, for Android through JNI.
pub mod engine;
pub mod usbin;
pub mod recorder;
pub mod dsp;
pub mod state;
pub mod fx;
mod jni_api;
