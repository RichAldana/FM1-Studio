//! The pad's effect engine (the brief "moteur d'effets XY"), around the effects of native/fx (C++: Faust's and the
//! app's own), which it reaches only through their C interface:
//!  - the XY layer: each effect's profile (JSON, loaded at start: no rebuild to change a setting) turns the finger's
//!    x and y (0..1, y = 0 at the bottom) into parameter values: a curve (lin, exp, steps: tempo divisions, scale:
//!    semitones on a scale), an inversion, a smoothing (one pole, smoothMs; none on steps and scales), a gesture on
//!    release (return to rest, hold, or the effect's own action: freeze, capture, tape stop), and the wet level
//!    rising in attackMs at the touch and falling in releaseMs after it (latch, or the pad's HOLD: it stays);
//!  - two slots: the effect playing and at most one on its way out, crossfaded in 30 ms; an effect with a tail
//!    (reverbs, echoes: profile "tail", played as a send) keeps sounding after another is chosen, until it stays
//!    under -90 dB for 100 ms;
//!  - the clock: the MIDI clock coming in (LOOM-8's, through the engine's MIDI router) when there is one, else the
//!    internal tempo, which tap tempo sets; the tempo-synced steps and the beat repeat read it;
//!  - guards: an effect whose output is not finite is cut and cleared.
//! Fixed 64-frame blocks; nothing allocated in `process`.

use serde::Deserialize;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, Ordering::Relaxed};
use std::sync::Mutex;
use std::time::Instant;

#[repr(C)]
pub struct FxEffect {
    _p: [u8; 0],
}

extern "C" {
    fn fx_new(id: *const c_char) -> *mut FxEffect;
    fn fx_free(f: *mut FxEffect);
    fn fx_prepare(f: *mut FxEffect, sr: f64, block: c_int);
    fn fx_reset(f: *mut FxEffect);
    fn fx_num_params(f: *mut FxEffect) -> c_int;
    fn fx_param_name(f: *mut FxEffect, i: c_int) -> *const c_char;
    fn fx_set_param(f: *mut FxEffect, i: c_int, v: f32);
    fn fx_on_touch(f: *mut FxEffect, down: c_int);
    fn fx_process(f: *mut FxEffect, il: *const f32, ir: *const f32, ol: *mut f32, or: *mut f32, n: c_int, bpm: f64, beat: f64, running: c_int);
}

const SR: f32 = 44100.0;
const BLOCK: usize = 64;

// ------------------------------------------------------------------ profiles

fn d_lin() -> String { "lin".into() }
fn d_one() -> f32 { 1.0 }
fn d_smooth() -> f32 { 20.0 }
fn d_attack() -> f32 { 30.0 }
fn d_release() -> f32 { 150.0 }
fn d_insert() -> String { "insert".into() }
fn d_return() -> String { "return".into() }
fn d_sec() -> String { "s".into() }

#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Axis {
    /// the effect's parameter, or "mix" (the slot's wet level), or "" (nothing)
    pub param: String,
    #[serde(default = "d_lin")]
    pub curve: String,
    #[serde(default)]
    pub min: f32,
    #[serde(default = "d_one")]
    pub max: f32,
    #[serde(default)]
    pub steps: Vec<String>,
    #[serde(default)]
    pub tempo_sync: bool,
    /// what a tempo step becomes: "s" seconds (a delay's time) or "beats" (the beat repeat's length)
    #[serde(default = "d_sec")]
    pub unit: String,
    /// for "scale": the intervals kept in the octave (0..11)
    #[serde(default)]
    pub scale: Vec<i32>,
    #[serde(default)]
    pub invert: bool,
    #[serde(default = "d_smooth")]
    pub smooth_ms: f32,
    /// with the "return" gesture: where the parameter glides back to (its own units)
    pub rest: Option<f32>,
}

#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub id: String,
    pub label: String,
    pub category: String,
    #[serde(default)]
    pub tail: bool,
    #[serde(default)]
    pub latch: bool,
    #[serde(default = "d_attack")]
    pub attack_ms: f32,
    #[serde(default = "d_release")]
    pub release_ms: f32,
    /// "send": the effect gets the sound at the touch's level and its output is added (reverbs, echoes: their tail
    /// rings on); "insert": its output replaces the sound by the wet level (filters, drives, the beat repeat)
    #[serde(default = "d_insert")]
    pub mode: String,
    pub x: Axis,
    pub y: Axis,
    #[serde(default = "d_return")]
    pub gesture: String,
    #[serde(default)]
    pub fixed: HashMap<String, f32>,
}

/// "1/8", "1/8.", "1/16" (a whole note's fraction, dotted ×1.5) in beats (quarter notes)
fn step_beats(s: &str) -> Option<f32> {
    let dotted = s.ends_with('.');
    let s = s.trim_end_matches('.');
    let (a, b) = s.split_once('/')?;
    let f = a.trim().parse::<f32>().ok()? / b.trim().parse::<f32>().ok()?;
    Some(4.0 * f * if dotted { 1.5 } else { 1.0 })
}

impl Axis {
    /// The finger's v (0..1) as the parameter's value, at tempo `bpm`.
    pub fn value(&self, v: f32, bpm: f32) -> f32 {
        let v = if self.invert { 1.0 - v } else { v }.clamp(0.0, 1.0);
        match self.curve.as_str() {
            "exp" => {
                let mn = self.min.max(1e-6);
                mn * (self.max / mn).powf(v)
            }
            "steps" => {
                if self.steps.is_empty() { return self.min; }
                let i = ((v * self.steps.len() as f32) as usize).min(self.steps.len() - 1);
                let s = &self.steps[i];
                if self.tempo_sync {
                    let beats = step_beats(s).unwrap_or(1.0);
                    if self.unit == "beats" { beats } else { beats * 60.0 / bpm.max(20.0) }
                } else {
                    s.parse::<f32>().unwrap_or(i as f32)
                }
            }
            "scale" => {
                let raw = self.min + v * (self.max - self.min);
                let n = raw.round() as i32;
                if self.scale.is_empty() { return n as f32; }
                // the nearest semitone of the scale
                (0..=12).flat_map(|d| [n - d, n + d])
                    .find(|k| self.scale.contains(&k.rem_euclid(12)) && (*k as f32) >= self.min - 0.01 && (*k as f32) <= self.max + 0.01)
                    .unwrap_or(n) as f32
            }
            _ => self.min + v * (self.max - self.min),
        }
    }
    fn smoothed(&self) -> bool { !(self.curve == "steps" || self.curve == "scale") }
}

// ------------------------------------------------------------------ the controls (UI thread -> audio)

static SELECT: AtomicI32 = AtomicI32::new(-1);
static TX: AtomicU32 = AtomicU32::new(0x3f00_0000);
static TY: AtomicU32 = AtomicU32::new(0x3f00_0000);
static DOWN: AtomicBool = AtomicBool::new(false);
static TOUCHES: AtomicU32 = AtomicU32::new(0);   // counts touches down (a tap between two blocks is not lost)
static HOLD: AtomicBool = AtomicBool::new(false);
/// what the pad's effect is on: 0 everything, 1 the emulated FM-1, 2 the real one
pub static TARGET: AtomicU32 = AtomicU32::new(0);
static BPM_INTERNAL: AtomicU32 = AtomicU32::new(0x42f0_0000); // 120.0
// the MIDI clock, as the engine's router hears it
static CLOCK_PULSES: AtomicU64 = AtomicU64::new(0);
static CLOCK_LAST_NS: AtomicU64 = AtomicU64::new(0);
static CLOCK_PERIOD_NS: AtomicU64 = AtomicU64::new(0);
static T0: Mutex<Option<Instant>> = Mutex::new(None);
static TAPS: Mutex<Vec<u64>> = Mutex::new(Vec::new());

fn now_ns() -> u64 {
    let mut g = T0.lock().unwrap();
    let t0 = *g.get_or_insert_with(Instant::now);
    t0.elapsed().as_nanos() as u64
}
fn f(a: &AtomicU32) -> f32 { f32::from_bits(a.load(Relaxed)) }

pub fn select(index: i32) { SELECT.store(index, Relaxed); }
pub fn touch(x: f32, y: f32, down: bool) {
    TX.store(x.clamp(0.0, 1.0).to_bits(), Relaxed);
    TY.store(y.clamp(0.0, 1.0).to_bits(), Relaxed);
    if down && !DOWN.load(Relaxed) { TOUCHES.fetch_add(1, Relaxed); }
    DOWN.store(down, Relaxed);
}
pub fn hold(on: bool) { HOLD.store(on, Relaxed); }
pub fn set_bpm(bpm: f32) { BPM_INTERNAL.store(bpm.clamp(30.0, 300.0).to_bits(), Relaxed); }
/// A tap: the internal tempo from the last taps (2 s apart at most); returns the tempo now.
pub fn tap() -> f32 {
    let t = now_ns();
    let mut taps = TAPS.lock().unwrap();
    if taps.last().map_or(false, |&l| t - l > 2_000_000_000) { taps.clear(); }
    taps.push(t);
    if taps.len() > 5 { taps.remove(0); }
    if taps.len() >= 2 {
        let span = (taps[taps.len() - 1] - taps[0]) as f64 / (taps.len() - 1) as f64;
        set_bpm((60e9 / span) as f32);
    }
    f(&BPM_INTERNAL)
}

/// The engine's MIDI router: a clock tick, a start (the beat back to 0).
pub fn clock_tick() {
    let t = now_ns();
    let last = CLOCK_LAST_NS.swap(t, Relaxed);
    if last != 0 && t > last {
        let d = t - last;
        let p = CLOCK_PERIOD_NS.load(Relaxed);
        // smoothed over a beat or so; a jump of more than half (a new tempo, a restart) taken at once
        let np = if p == 0 || d > p * 3 / 2 || d < p / 2 { d } else { (p * 23 + d) / 24 };
        CLOCK_PERIOD_NS.store(np, Relaxed);
    }
    CLOCK_PULSES.fetch_add(1, Relaxed);
}
pub fn clock_start() { CLOCK_PULSES.store(0, Relaxed); }

/// (tempo, beat position, true when it is the MIDI clock's)
fn transport(internal_beat: f64) -> (f64, f64, bool) {
    let last = CLOCK_LAST_NS.load(Relaxed);
    let p = CLOCK_PERIOD_NS.load(Relaxed);
    let now = now_ns();
    if last != 0 && p > 0 && now.saturating_sub(last) < 500_000_000 {
        let bpm = 60e9 / (p as f64 * 24.0);
        let frac = ((now - last) as f64 / p as f64).min(1.0);
        let beat = (CLOCK_PULSES.load(Relaxed) as f64 + frac) / 24.0;
        (bpm, beat, true)
    } else {
        (f(&BPM_INTERNAL) as f64, internal_beat, false)
    }
}

/// For the screen: [tempo, beat, 1 when it is the MIDI clock's]
pub fn info() -> [f32; 3] {
    let (bpm, beat, midi) = transport(*INTERNAL_BEAT.lock().unwrap());
    [bpm as f32, beat as f32, if midi { 1.0 } else { 0.0 }]
}
static INTERNAL_BEAT: Mutex<f64> = Mutex::new(0.0);

// ------------------------------------------------------------------ the engine

struct Loaded {
    p: Profile,
    h: *mut FxEffect,
    xi: i32,     // the parameter's index, -1 none, -2 the slot's mix
    yi: i32,
    fixed: Vec<(i32, f32)>,
}

struct Slot {
    fx: usize,
    env: f32,        // the wet level / the send's level, 0..1
    xv: f32,         // the parameters as last set (smoothed)
    yv: f32,
    mix: f32,        // the slot's mix (an axis on "mix"), 1 else
    leaving: bool,   // on its way out (its tail, or a 30 ms fade)
    quiet: usize,    // frames under -90 dB in a row
    touching: bool,
    latched: bool,
}

pub struct FxEngine {
    fx: Vec<Loaded>,
    active: Option<Slot>,
    tail: Option<Slot>,
    seen_touches: u32,
    beat: f64,
    // scratch
    il: [f32; BLOCK],
    ir: [f32; BLOCK],
    ol: [f32; BLOCK],
    or: [f32; BLOCK],
    tl: [f32; BLOCK],
    tr: [f32; BLOCK],
    dl: [f32; BLOCK],   // the dry sound, -1..1
    dr: [f32; BLOCK],
}

unsafe impl Send for FxEngine {}

impl Drop for FxEngine {
    fn drop(&mut self) {
        for l in &self.fx { unsafe { fx_free(l.h) } }
    }
}

fn param_index(h: *mut FxEffect, name: &str) -> i32 {
    if name == "mix" { return -2; }
    if name.is_empty() { return -1; }
    let n = unsafe { fx_num_params(h) };
    for i in 0..n {
        let s = unsafe { CStr::from_ptr(fx_param_name(h, i)) }.to_string_lossy();
        if s == name { return i; }
    }
    -1
}

impl FxEngine {
    /// Every effect of `json` (a list of profiles) this build has, made and prepared now (all the allocation).
    pub fn load(json: &str) -> Result<FxEngine, String> {
        let profiles: Vec<Profile> = serde_json::from_str(json).map_err(|e| format!("profiles: {e}"))?;
        let mut fx = Vec::new();
        for p in profiles {
            let id = CString::new(p.id.clone()).map_err(|e| e.to_string())?;
            let h = unsafe { fx_new(id.as_ptr()) };
            if h.is_null() { continue; } // (an effect of a later step)
            unsafe { fx_prepare(h, SR as f64, BLOCK as c_int) };
            let xi = param_index(h, &p.x.param);
            let yi = param_index(h, &p.y.param);
            let fixed = p.fixed.iter().map(|(k, v)| (param_index(h, k), *v)).filter(|(i, _)| *i >= 0).collect();
            fx.push(Loaded { p, h, xi, yi, fixed });
        }
        Ok(FxEngine { fx, active: None, tail: None, seen_touches: TOUCHES.load(Relaxed), beat: 0.0,
            il: [0.0; BLOCK], ir: [0.0; BLOCK], ol: [0.0; BLOCK], or: [0.0; BLOCK], tl: [0.0; BLOCK], tr: [0.0; BLOCK],
            dl: [0.0; BLOCK], dr: [0.0; BLOCK] })
    }

    /// The effects there are (for the screen): their profiles, in order.
    pub fn list(&self) -> Vec<&Profile> { self.fx.iter().map(|l| &l.p).collect() }

    fn new_slot(&self, i: usize) -> Slot {
        let l = &self.fx[i];
        unsafe {
            fx_reset(l.h);
            for &(k, v) in &l.fixed { fx_set_param(l.h, k, v) }
        }
        let bpm = transport(self.beat).0 as f32;
        let (x, y) = (f(&TX), f(&TY));
        let xv = l.p.x.rest.unwrap_or_else(|| l.p.x.value(x, bpm));
        let yv = l.p.y.rest.unwrap_or_else(|| l.p.y.value(y, bpm));
        Slot { fx: i, env: 0.0, xv, yv, mix: 1.0, leaving: false, quiet: 0, touching: false, latched: false }
    }

    /// The bus (interleaved stereo, i16 scale) through the pad's effect, in place.
    pub fn process(&mut self, bus: &mut [f32]) {
        let n = bus.len() / 2;
        let mut i = 0;
        while i < n {
            let m = (n - i).min(BLOCK);
            self.block(&mut bus[2 * i..2 * (i + m)], m);
            i += m;
        }
    }

    fn block(&mut self, bus: &mut [f32], m: usize) {
        let (bpm, beat, midi) = transport(self.beat);
        // the choice
        let sel = SELECT.load(Relaxed);
        let want = if sel >= 0 && (sel as usize) < self.fx.len() { Some(sel as usize) } else { None };
        if self.active.as_ref().map(|s| s.fx) != want {
            if let Some(mut old) = self.active.take() {
                old.leaving = true;
                old.touching = false;
                unsafe { fx_on_touch(self.fx[old.fx].h, 0) };
                self.tail = Some(old); // (an older tail goes: two at most)
            }
            if let Some(w) = want {
                if self.tail.as_ref().map(|t| t.fx) == Some(w) {
                    // back to the one on its way out: it goes on from where it is
                    let mut t = self.tail.take().unwrap();
                    t.leaving = false;
                    self.active = Some(t);
                } else {
                    self.active = Some(self.new_slot(w));
                }
            }
        }
        // the finger
        let down = DOWN.load(Relaxed);
        let touches = TOUCHES.load(Relaxed);
        let tapped = touches != self.seen_touches;
        self.seen_touches = touches;
        let (tx, ty) = (f(&TX), f(&TY));
        let hold = HOLD.load(Relaxed);
        // the effects work at -1..1 (Faust's libraries assume it: a drive's clipping, a compressor's threshold)
        for k in 0..m {
            self.dl[k] = bus[2 * k] * (1.0 / 32768.0);
            self.dr[k] = bus[2 * k + 1] * (1.0 / 32768.0);
        }
        self.il = self.dl;
        self.ir = self.dr;
        let mut out_l = self.dl;
        let mut out_r = self.dr;
        if let Some(s) = self.active.as_mut() {
            let l = &self.fx[s.fx];
            let p = &l.p;
            let pressed = down || tapped;
            if pressed != s.touching {
                if p.gesture == "action" { unsafe { fx_on_touch(l.h, pressed as c_int) } }
                if pressed { s.latched = p.latch || hold; }
                s.touching = pressed;
            }
            if !hold && !p.latch { s.latched = false; }
            // the parameters: where the finger is; released, back to rest ("return") or where they were
            let dt = m as f32 * 1000.0 / SR;
            let glide = |cur: f32, target: f32, ms: f32, smooth: bool| -> f32 {
                if !smooth || ms <= 0.0 { target } else { target + (cur - target) * (-dt / ms).exp() }
            };
            if s.touching {
                s.xv = glide(s.xv, p.x.value(tx, bpm as f32), p.x.smooth_ms, p.x.smoothed());
                s.yv = glide(s.yv, p.y.value(ty, bpm as f32), p.y.smooth_ms, p.y.smoothed());
            } else if p.gesture == "return" && !s.latched {
                if let Some(r) = p.x.rest { s.xv = glide(s.xv, r, p.release_ms, true) }
                if let Some(r) = p.y.rest { s.yv = glide(s.yv, r, p.release_ms, true) }
            }
            for (idx, v) in [(l.xi, s.xv), (l.yi, s.yv)] {
                if idx >= 0 { unsafe { fx_set_param(l.h, idx, v) } }
            }
            s.mix = if l.xi == -2 { s.xv } else if l.yi == -2 { s.yv } else { 1.0 }.clamp(0.0, 1.0);
            let target = if s.touching || s.latched { 1.0 } else { 0.0 };
            let send = p.mode == "send";
            let rate_up = 1.0 / (p.attack_ms.max(1.0) * SR / 1000.0);
            let rate_down = 1.0 / (p.release_ms.max(1.0) * SR / 1000.0);
            let sleeping = s.env <= 0.0 && target == 0.0 && !send;
            if !sleeping {
                // the effect's input: the sound (insert), or the sound at the send's level (send)
                let mut env = s.env;
                for k in 0..m {
                    env = if target > env { (env + rate_up).min(1.0) } else { (env - rate_down).max(0.0) };
                    self.tl[k] = env;
                    if send { self.il[k] *= env; self.ir[k] *= env; }
                }
                s.env = env;
                unsafe { fx_process(l.h, self.il.as_ptr(), self.ir.as_ptr(), self.ol.as_mut_ptr(), self.or.as_mut_ptr(), m as c_int, bpm, beat, 1) };
                if !self.ol[..m].iter().chain(&self.or[..m]).all(|v| v.is_finite()) {
                    unsafe { fx_reset(l.h) };   // (it allocates nothing: done here)
                    s.env = 0.0;
                } else {
                    for k in 0..m {
                        let (dl, dr) = (self.dl[k], self.dr[k]);
                        if send {
                            out_l[k] = dl + self.ol[k] * s.mix;
                            out_r[k] = dr + self.or[k] * s.mix;
                        } else {
                            let w = self.tl[k] * s.mix;
                            out_l[k] = dl * (1.0 - w) + self.ol[k] * w;
                            out_r[k] = dr * (1.0 - w) + self.or[k] * w;
                        }
                    }
                }
            } else {
                s.env = 0.0;
            }
        }
        // the one on its way out: a send rings on with no input until quiet; an insert fades in 30 ms
        let mut drop_tail = false;
        if let Some(t) = self.tail.as_mut() {
            let l = &self.fx[t.fx];
            let send = l.p.mode == "send";
            let fade = 1.0 / (0.03 * SR);
            if send {
                self.tl[..m].fill(0.0);
                unsafe { fx_process(l.h, self.tl.as_ptr(), self.tl.as_ptr(), self.ol.as_mut_ptr(), self.or.as_mut_ptr(), m as c_int, bpm, beat, 1) };
            } else {
                self.tl = self.dl;
                self.tr = self.dr;
                unsafe { fx_process(l.h, self.tl.as_ptr(), self.tr.as_ptr(), self.ol.as_mut_ptr(), self.or.as_mut_ptr(), m as c_int, bpm, beat, 1) };
            }
            let finite = self.ol[..m].iter().chain(&self.or[..m]).all(|v| v.is_finite());
            let mut peak = 0f32;
            for k in 0..m {
                if !finite { break; }
                if send {
                    out_l[k] += self.ol[k] * t.mix;
                    out_r[k] += self.or[k] * t.mix;
                    peak = peak.max(self.ol[k].abs()).max(self.or[k].abs());
                } else {
                    t.env = (t.env - fade).max(0.0);
                    let w = t.env * t.mix;
                    // (the outgoing insert's wet part crossfades out against the new one's sound)
                    out_l[k] += (self.ol[k] - self.dl[k]) * w;
                    out_r[k] += (self.or[k] - self.dr[k]) * w;
                }
            }
            if !finite {
                drop_tail = true;
            } else if send {
                // -90 dB of full scale for 100 ms: gone
                if peak < 3.16e-5 { t.quiet += m } else { t.quiet = 0 }
                drop_tail = t.quiet as f32 >= 0.1 * SR;
            } else {
                drop_tail = t.env <= 0.0;
            }
        }
        if drop_tail { self.tail = None; }
        for k in 0..m {
            bus[2 * k] = out_l[k] * 32768.0;
            bus[2 * k + 1] = out_r[k] * 32768.0;
        }
        // the internal clock runs on with the sound
        self.beat += bpm / 60.0 * m as f64 / SR as f64;
        if !midi { *INTERNAL_BEAT.lock().unwrap() = self.beat; }
    }
}

/// The engine, made once (Native.fxLoad), kept across firmwares.
pub static ENGINE: Mutex<Option<FxEngine>> = Mutex::new(None);

#[cfg(test)]
mod tests {
    use super::*;

    const PROFILES: &str = include_str!("../../app/src/main/assets/fx_profiles.json");

    #[test]
    fn curves() {
        let a = |j: &str| serde_json::from_str::<Axis>(j).unwrap();
        let e = a(r#"{"param":"cutoff","curve":"exp","min":40,"max":18000}"#);
        assert!((e.value(0.0, 120.0) - 40.0).abs() < 0.01 && (e.value(1.0, 120.0) - 18000.0).abs() < 1.0);
        let s = a(r#"{"param":"time","curve":"steps","steps":["1/16","1/8","1/8.","1/4","1/2"],"tempoSync":true}"#);
        assert!((s.value(0.0, 120.0) - 0.125).abs() < 1e-4);   // a sixteenth at 120: 125 ms
        assert!((s.value(0.5, 120.0) - 0.375).abs() < 1e-4);   // a dotted eighth: 375 ms
        assert!((s.value(1.0, 120.0) - 1.0).abs() < 1e-4);     // a half: 1 s
        let b = a(r#"{"param":"length","curve":"steps","steps":["1/4","1/64"],"tempoSync":true,"unit":"beats"}"#);
        assert!((b.value(0.0, 90.0) - 1.0).abs() < 1e-6 && (b.value(1.0, 90.0) - 0.0625).abs() < 1e-6);
        let c = a(r#"{"param":"shift","curve":"scale","min":-12,"max":12,"scale":[0,2,4,5,7,9,11]}"#);
        assert_eq!(c.value(0.5, 120.0), 0.0);
        assert!([0, 2, 4, 5, 7, 9, 11].contains(&(c.value(0.56, 120.0) as i32).rem_euclid(12)));
        let i = a(r#"{"param":"x","min":0,"max":10,"invert":true}"#);
        assert_eq!(i.value(0.0, 120.0), 10.0);
    }

    fn sine(f: f32, n: usize) -> Vec<f32> {
        (0..2 * n).map(|i| 8000.0 * (2.0 * std::f32::consts::PI * f * (i / 2) as f32 / SR).sin()).collect()
    }
    fn peak(x: &[f32]) -> f32 { x.iter().fold(0f32, |m, v| m.max(v.abs())) }

    // (the controls are process-wide: one test plays the whole story)
    #[test]
    fn the_pad_plays_every_effect() {
        let mut e = FxEngine::load(PROFILES).expect("profiles");
        let ids: Vec<String> = e.list().iter().map(|p| p.id.clone()).collect();
        eprintln!("effects: {ids:?}");
        assert_eq!(ids.len(), 11, "the effects of this step");
        let idx = |id: &str| ids.iter().position(|x| x == id).unwrap() as i32;
        // nothing chosen: as it is
        select(-1);
        let mut b = sine(440.0, 4096);
        let dry = b.clone();
        e.process(&mut b);
        assert_eq!(b, dry);
        // every effect, touched in the middle of the pad: finite, not silent; then released
        for id in &ids {
            select(idx(id));
            touch(0.5, 0.5, true);
            let mut out = Vec::new();
            for _ in 0..40 {
                let mut b = sine(220.0, 1024);
                e.process(&mut b);
                out.extend_from_slice(&b);
            }
            touch(0.5, 0.5, false);
            for _ in 0..10 {
                let mut b = sine(220.0, 1024);
                e.process(&mut b);
                out.extend_from_slice(&b);
            }
            assert!(out.iter().all(|v| v.is_finite()), "{id}: not finite");
            assert!(peak(&out) > 1000.0, "{id}: silent");
            assert!(peak(&out) < 32768.0 * 4.0, "{id}: runaway {}", peak(&out));
        }
        // the Moog filter closed (finger at the left, low): 5 kHz almost gone
        select(idx("faust.moog"));
        touch(0.0, 0.0, true);
        let mut b = sine(5000.0, 44100);
        e.process(&mut b);
        assert!(peak(&b[44100..]) < 1500.0, "moog: 5 kHz {}", peak(&b[44100..]));
        touch(0.0, 0.0, false);
        // the hall's tail rings on after another effect is chosen (spill-over), then stops
        select(idx("faust.zita"));
        touch(0.5, 1.0, true);
        let mut b = sine(300.0, 22050);
        e.process(&mut b);
        touch(0.5, 1.0, false);
        select(idx("faust.drive"));
        let mut silent = vec![0.0f32; 2 * 22050];
        e.process(&mut silent);
        assert!(peak(&silent) > 50.0, "the hall's tail stopped at the change");
        assert!(e.tail.is_some());
        // the beat repeat: touched, it repeats the slice before the next line (here at 120 bpm, a sixteenth)
        set_bpm(120.0);
        select(idx("cpp.beatrepeat"));
        touch(1.0, 0.0, true);
        let mut out = Vec::new();
        for k in 0..20 {
            // a click every 4410 frames, then nothing
            let mut b = vec![0.0f32; 2 * 2205];
            if k < 4 && k % 2 == 0 { b[0] = 20000.0; b[1] = 20000.0; }
            e.process(&mut b);
            out.extend_from_slice(&b);
        }
        touch(1.0, 0.0, false);
        let late = peak(&out[2 * 22050..]);
        eprintln!("beat repeat: after the clicks stopped, still {late}");
        select(-1);
    }

    #[test]
    fn tap_tempo() {
        // (taps 500 ms apart: 120 bpm) simulated through the internal store
        set_bpm(100.0);
        assert!((f(&BPM_INTERNAL) - 100.0).abs() < 1e-3);
    }
}

#[cfg(test)]
mod each_alone {
    use super::*;
    /// Each effect on its own through its C interface, at its parameters' defaults: finite for 3 s of a tone and
    /// 3 s of silence (a smoothed parameter starting from 0 once made the hall divide by zero).
    #[test]
    fn finite_alone() {
        let profiles: Vec<Profile> = serde_json::from_str(include_str!("../../app/src/main/assets/fx_profiles.json")).unwrap();
        for p in profiles {
            unsafe {
                let id = CString::new(p.id.clone()).unwrap();
                let h = fx_new(id.as_ptr());
                if h.is_null() { continue; }
                fx_prepare(h, 44100.0, 64);
                let mut il = [0f32; 64];
                let (mut ol, mut or) = ([0f32; 64], [0f32; 64]);
                for b in 0..4134 {
                    for k in 0..64 { il[k] = if b < 2067 { 0.3 * ((b * 64 + k) as f32 * 0.05).sin() } else { 0.0 }; }
                    fx_process(h, il.as_ptr(), il.as_ptr(), ol.as_mut_ptr(), or.as_mut_ptr(), 64, 120.0, b as f64 * 0.003, 1);
                    assert!(ol.iter().chain(&or).all(|v| v.is_finite() && v.abs() < 8.0), "{}: not finite or runaway at block {b}", p.id);
                }
                fx_free(h);
            }
        }
    }
}
