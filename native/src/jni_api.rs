//! JNI surface: Kotlin class `app.fm1mix.Native`.

use crate::engine::Engine;
use jni::objects::{JByteArray, JClass, JFloatArray, JShortArray};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jstring};
use jni::JNIEnv;
use std::sync::Mutex;

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);

fn with<R>(f: impl FnOnce(&Engine) -> R) -> Option<R> {
    let g = ENGINE.lock().unwrap_or_else(|p| p.into_inner());
    g.as_ref().map(f)
}

fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Boot a package. Returns "" on success, else the error.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_start(
    mut env: JNIEnv, _c: JClass, pkg: JByteArray, flash: JByteArray, mhz: jint, new_chip: jboolean,
) -> jstring {
    let pkg = match env.convert_byte_array(&pkg) {
        Ok(v) => v,
        Err(e) => return jstr(&mut env, &format!("package: {e}")),
    };
    let flash = if flash.is_null() { None } else { env.convert_byte_array(&flash).ok() };
    let mut g = ENGINE.lock().unwrap_or_else(|p| p.into_inner());
    if let Some(old) = g.take() {
        old.stop();
    }
    let r = std::panic::catch_unwind(|| Engine::start(&pkg, flash.as_deref(), mhz.max(1) as u64, new_chip != 0))
        .unwrap_or_else(|_| Err("the emulator panicked while booting".into()));
    match r {
        Ok(e) => {
            *g = Some(e);
            drop(g);
            jstr(&mut env, "")
        }
        Err(e) => {
            drop(g);
            jstr(&mut env, &e)
        }
    }
}

/// Stop and return the flash image (null when nothing ran).
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_stop(env: JNIEnv, _c: JClass) -> jbyteArray {
    let e = ENGINE.lock().unwrap_or_else(|p| p.into_inner()).take();
    match e {
        Some(e) => {
            let flash = e.stop();
            env.byte_array_from_slice(&flash).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
        }
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_key(_e: JNIEnv, _c: JClass, id: jint, down: jboolean) {
    with(|e| e.key(id as u8, down != 0));
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_keyVel(_e: JNIEnv, _c: JClass, id: jint, vel: jint) {
    with(|e| e.key_vel(id as u8, vel.clamp(1, 127) as u8));
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_enc(_e: JNIEnv, _c: JClass, enc: jint, clicks: jint) {
    with(|e| e.enc(enc as u8, clicks));
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_pot(_e: JNIEnv, _c: JClass, value: jint) {
    with(|e| e.pot(value.clamp(0, 1023) as u16));
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_midi(env: JNIEnv, _c: JClass, bytes: JByteArray, len: jint) {
    let n = len.max(0) as usize;
    let mut buf = vec![0i8; n];
    if env.get_byte_array_region(&bytes, 0, &mut buf).is_ok() {
        let b: Vec<u8> = buf.iter().map(|&x| x as u8).collect();
        with(|e| e.midi(&b));
    }
}

/// Fill `out` (stereo interleaved) with the mix. Returns the number of samples written.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_readMixed(
    env: JNIEnv, _c: JClass, out: JShortArray, g_emu: jfloat, g_ext: jfloat, g_ext_rec: jfloat,
) -> jint {
    let n = env.get_array_length(&out).unwrap_or(0).max(0) as usize;
    let mut buf = vec![0i16; n];
    if with(|e| e.read_mixed_rec(&mut buf, g_emu, g_ext, g_ext_rec)).is_none() {
        // nothing running: silence (and the caller's thread must not spin)
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    let _ = env.set_short_array_region(&out, 0, &buf);
    n as jint
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_pushCapture(env: JNIEnv, _c: JClass, buf: JShortArray, n: jint) {
    let n = n.max(0) as usize;
    let mut tmp = vec![0i16; n];
    if env.get_short_array_region(&buf, 0, &mut tmp).is_ok() {
        with(|e| e.push_capture(&tmp));
    }
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_lcd(env: JNIEnv, _c: JClass, out: JShortArray) {
    let n = env.get_array_length(&out).unwrap_or(0).max(0) as usize;
    let mut px = vec![0u16; n];
    if with(|e| e.lcd(&mut px)).is_some() {
        let s: Vec<i16> = px.iter().map(|&v| v as i16).collect();
        let _ = env.set_short_array_region(&out, 0, &s);
    }
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_leds(env: JNIEnv, _c: JClass, out: JFloatArray) {
    if let Some(l) = with(|e| e.leds()) {
        let _ = env.set_float_array_region(&out, 0, &l);
    }
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_meters(env: JNIEnv, _c: JClass, out: JFloatArray) {
    if let Some((a, b)) = with(|e| e.take_meters()) {
        let _ = env.set_float_array_region(&out, 0, &[a, b]);
    }
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_status(mut env: JNIEnv, _c: JClass) -> jstring {
    let s = with(|e| e.status()).unwrap_or_else(|| "stopped".into());
    jstr(&mut env, &s)
}

/// Choose what the MIDI port does: 0 clock only, 1 thru, 2 panel (needs `profile`, fm1-map JSON).
/// Returns "" or an error message.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_setMidiMode(
    mut env: JNIEnv, _c: JClass, mode: jint, profile: jni::objects::JString,
) -> jstring {
    let json: Option<String> = if profile.is_null() { None } else { env.get_string(&profile).ok().map(|s| s.into()) };
    let r = with(|e| e.set_midi_mode(crate::engine::MidiMode::from_i32(mode), json.as_deref()));
    let msg = match r {
        Some(Ok(())) => String::new(),
        Some(Err(e)) => e,
        None => "not running".to_string(),
    };
    jstr(&mut env, &msg)
}

/// out = [messages received (clipped to i32), last message as 0xSSD1D2].
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_midiInfo(env: JNIEnv, _c: JClass, out: jni::objects::JIntArray) {
    if let Some((n, last)) = with(|e| e.midi_info()) {
        let _ = env.set_int_array_region(&out, 0, &[n.min(i32::MAX as u64) as i32, last as i32]);
    }
}

/// Share of the time spent emulating, in percent; -1 when stopped, -2 when the machine halted on an error.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_load(_e: JNIEnv, _c: JClass) -> jint {
    with(|e| if e.halted() { -2 } else { e.load() as jint }).unwrap_or(-1)
}

// ------------------------------------------------------------------ the FM-1's sound read straight from USB (usbin.rs)

static USB_IN: Mutex<Option<crate::usbin::Reader>> = Mutex::new(None);

/// The isochronous IN stream in raw descriptors: [interface, alt, endpoint, max packet, channels, bytes a sample,
/// bits, rate], or null when there is none.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_usbParse(env: JNIEnv, _c: JClass, raw: JByteArray) -> jni::sys::jintArray {
    let Ok(raw) = env.convert_byte_array(&raw) else { return std::ptr::null_mut() };
    match crate::usbin::parse_in_stream(&raw) {
        Some(s) => {
            let v = [s.interface as i32, s.alt as i32, s.endpoint as i32, s.max_packet as i32, s.channels as i32,
                     s.subframe as i32, s.bits as i32, s.rate as i32];
            match env.new_int_array(v.len() as i32) {
                Ok(a) => {
                    let _ = env.set_int_array_region(&a, 0, &v);
                    a.into_raw()
                }
                Err(_) => std::ptr::null_mut(),
            }
        }
        None => std::ptr::null_mut(),
    }
}

/// Start reading the FM-1's sound on `fd` (Android's UsbDeviceConnection, the interface claimed and its streaming
/// alternate set), into the engine's capture. Returns "" or an error.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_usbStart(mut env: JNIEnv, _c: JClass, fd: jint, raw: JByteArray) -> jstring {
    let msg = (|| -> Result<(), String> {
        let raw = env.convert_byte_array(&raw).map_err(|e| e.to_string())?;
        let st = crate::usbin::parse_in_stream(&raw).ok_or("no audio stream towards the phone")?;
        let mut g = USB_IN.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(old) = g.take() {
            old.stop();
        }
        let sink: Box<dyn FnMut(&[i16]) + Send> = Box::new(|s: &[i16]| {
            with(|e| e.push_capture(s));
        });
        *g = Some(crate::usbin::Reader::start(fd, st, sink)?);
        Ok(())
    })();
    jstr(&mut env, &msg.err().unwrap_or_default())
}

#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_usbStop(_e: JNIEnv, _c: JClass) {
    let r = USB_IN.lock().unwrap_or_else(|p| p.into_inner()).take();
    if let Some(r) = r {
        r.stop();
    }
}

/// out = [samples handed over, packets, bad packets, errno that stopped it (0 none), 1 while reading]
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_usbStats(env: JNIEnv, _c: JClass, out: jni::objects::JLongArray) {
    use std::sync::atomic::Ordering::Relaxed;
    let g = USB_IN.lock().unwrap_or_else(|p| p.into_inner());
    let v = match g.as_ref() {
        Some(r) => [r.stats.samples.load(Relaxed) as i64, r.stats.packets.load(Relaxed) as i64,
                    r.stats.bad_packets.load(Relaxed) as i64, r.stats.error.load(Relaxed) as i64,
                    r.stats.running.load(Relaxed) as i64],
        None => [0, 0, 0, 0, 0],
    };
    let _ = env.set_long_array_region(&out, 0, &v);
}

// ------------------------------------------------------------------ recording (recorder.rs)

/// Record into `fd_a` (the mix, or the emulated FM-1 when `fd_b` >= 0) and `fd_b` (the real FM-1), as `bits` (16, 24,
/// 32 float), `rate` (44100, 48000), `channels` (1, 2). The descriptors (MediaStore files opened "rw", detached) are
/// taken over and closed when the recording stops. "" or an error.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_recStart(
    mut env: JNIEnv, _c: JClass, fd_a: jint, fd_b: jint, bits: jint, rate: jint, channels: jint,
) -> jstring {
    use crate::recorder::{Quality, Recorder, Source};
    use std::os::fd::FromRawFd;
    let msg = (|| -> Result<(), String> {
        let q = Quality { bits: bits as u16, rate: rate as u32, channels: channels as u16 };
        // SAFETY: Java detached these descriptors for us: they are ours to own and close (on every path below)
        let a = unsafe { std::fs::File::from_raw_fd(fd_a) };
        let b = if fd_b >= 0 { Some(unsafe { std::fs::File::from_raw_fd(fd_b) }) } else { None };
        let files = match b {
            Some(b) => vec![(a, Source::Emu), (b, Source::Ext)],
            None => vec![(a, Source::Mix)],
        };
        let mut g = crate::recorder::REC.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(old) = g.take() {
            let _ = old.stop();
        }
        *g = Some(Recorder::start(files, q)?);
        Ok(())
    })();
    jstr(&mut env, &msg.err().unwrap_or_default())
}

/// Stop the recording: what is queued is written, the WAV headers completed. Returns "" or an error.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_recStop(mut env: JNIEnv, _c: JClass) -> jstring {
    let r = crate::recorder::REC.lock().unwrap_or_else(|p| p.into_inner()).take();
    let msg = match r {
        Some(r) => r.stop().err().unwrap_or_default(),
        None => String::new(),
    };
    jstr(&mut env, &msg)
}

/// out = [frames recorded, blocks dropped, 1 while recording]
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_recStats(env: JNIEnv, _c: JClass, out: jni::objects::JLongArray) {
    use std::sync::atomic::Ordering::Relaxed;
    let g = crate::recorder::REC.lock().unwrap_or_else(|p| p.into_inner());
    let v = match g.as_ref() {
        Some(r) => [r.stats.frames.load(Relaxed) as i64, r.stats.dropped.load(Relaxed) as i64, 1],
        None => [0, 0, 0],
    };
    let _ = env.set_long_array_region(&out, 0, &v);
}

/// The latency (low: small queues) and the offset between the two FM-1s (ms, > 0: the real one later).
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_setLatency(_e: JNIEnv, _c: JClass, low: jboolean, offset_ms: jint) {
    with(|e| e.set_latency(low != 0, offset_ms.clamp(-200, 200)));
}

/// The limiter on the output and the recorded mix.
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_setLimiter(_e: JNIEnv, _c: JClass, on: jboolean) {
    crate::dsp::LIMIT.store(on != 0, std::sync::atomic::Ordering::Relaxed);
}

/// out = [the captured FM-1's queue (ms), its clock against the phone's (ppm), the limiter's reduction (dB)]
#[no_mangle]
pub extern "system" fn Java_app_fm1mix_Native_audioInfo(env: JNIEnv, _c: JClass, out: jni::objects::JFloatArray) {
    if let Some((ms, ppm, db)) = with(|e| e.audio_info()) {
        let _ = env.set_float_array_region(&out, 0, &[ms as f32, ppm as f32, db]);
    }
}
