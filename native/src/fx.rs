//! The pad's effect engine (the brief "moteur d'effets XY"), around the effects of native/fx (C++: Faust's and the
//! app's own), which it reaches only through their C interface:
//!  - the XY layer: each effect's profile (JSON, loaded at start: no rebuild to change a setting) turns the finger's
//!    x and y (0..1, y = 0 at the bottom) into parameter values, after the live convention (the brief's section 4):
//!    X = what (left dark, slow, long, low; right bright, fast, short, high), Y = how much, as a macro of several
//!    parameters, each with its range, curve (lin, exp, pow, steps: tempo divisions, scale: semitones on a scale) and
//!    the height it comes in from (`from`); or, "corners" mode, four presets blended under the finger (bilinear, the
//!    discrete ones from the nearest corner). Steps and scales are cells with a 3 % hysteresis; the rest is smoothed
//!    (one pole, smoothMs). On release: back to rest, hold, or the effect's own action (freeze, capture, tape stop);
//!    the wet level rises in attackMs at the touch and falls in releaseMs after it (latch, or the pad's HOLD: it
//!    stays). "wet" on an axis or a corner is the slot's wet level;
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
fn d_axes() -> String { "axes".into() }
fn d_return() -> String { "return".into() }
fn d_sec() -> String { "s".into() }
fn d_hyst() -> f32 { 0.03 }

/// One parameter driven by one axis of the pad (the live convention: X = what, Y = how much, Y a macro of several).
#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Map {
    /// the effect's parameter, "wet" (the slot's wet level), or "" (nothing)
    pub param: String,
    /// lin, exp (min > 0), pow (exponent `exp`), steps, scale
    #[serde(default = "d_lin")]
    pub curve: String,
    #[serde(default)]
    pub min: f32,
    #[serde(default = "d_one")]
    pub max: f32,
    /// pow's exponent
    #[serde(default = "d_one")]
    pub exp: f32,
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
    /// the axis' height (0..1) from which this parameter comes in: below, its min (the top keeps some intensity)
    #[serde(default)]
    pub from: f32,
    /// steps and scales: how far past a cell's edge the finger goes before the next cell is taken (share of the axis)
    #[serde(default = "d_hyst")]
    pub hysteresis: f32,
}

/// One mapping or several on an axis (both written the same way in the profiles).
#[derive(Deserialize, Clone, Debug)]
#[serde(untagged)]
pub enum Maps {
    One(Map),
    Many(Vec<Map>),
}
impl Default for Maps {
    fn default() -> Self { Maps::Many(Vec::new()) }
}
impl Maps {
    pub fn list(&self) -> Vec<Map> {
        match self { Maps::One(m) => vec![m.clone()], Maps::Many(v) => v.clone() }
    }
}

/// The four corners' parameter values ("corners" mode): bottom-left, bottom-right, top-left, top-right.
#[derive(Deserialize, Clone, Debug, Default)]
pub struct Corners {
    #[serde(default)]
    pub bl: HashMap<String, f32>,
    #[serde(default)]
    pub br: HashMap<String, f32>,
    #[serde(default)]
    pub tl: HashMap<String, f32>,
    #[serde(default)]
    pub tr: HashMap<String, f32>,
}

#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub id: String,
    pub label: String,
    pub category: String,
    /// "axes" (each axis drives its parameters) or "corners" (four presets, the finger between them)
    #[serde(default = "d_axes")]
    pub mode: String,
    /// the effect keeps sounding after the finger, and after another effect is chosen (reverbs, echoes)
    #[serde(default)]
    pub tail: bool,
    #[serde(default)]
    pub latch: bool,
    #[serde(default = "d_attack")]
    pub attack_ms: f32,
    #[serde(default = "d_release")]
    pub release_ms: f32,
    /// "send": the effect gets the sound at the touch's level and its output is added (its tail rings on);
    /// "insert": its output replaces the sound by the wet level. Unsaid: send with a tail, insert without.
    pub routing: Option<String>,
    /// the effect hears the sound all the time, touched or not (its buffer is full when the finger comes: Clouds'
    /// freeze, the beat repeat's slice); a send of this kind has its output faded by the touch instead of its input
    #[serde(default)]
    pub listen: bool,
    #[serde(default)]
    pub x: Maps,
    #[serde(default)]
    pub y: Maps,
    #[serde(default)]
    pub corners: Corners,
    /// corners mode: the parameters taking the nearest corner's value instead of a blend (switches, modes)
    #[serde(default)]
    pub discrete: Vec<String>,
    /// corners mode, "return" gesture: the pad's point the parameters glide back to
    pub rest_at: Option<[f32; 2]>,
    #[serde(default = "d_return")]
    pub gesture: String,
    #[serde(default)]
    pub fixed: HashMap<String, f32>,
}

impl Profile {
    pub fn send(&self) -> bool {
        self.routing.as_deref().map_or(self.tail, |r| r == "send")
    }
}

/// "1/8", "1/8.", "1/16" (a whole note's fraction, dotted ×1.5) in beats (quarter notes)
fn step_beats(s: &str) -> Option<f32> {
    let dotted = s.ends_with('.');
    let s = s.trim_end_matches('.');
    let (a, b) = s.split_once('/')?;
    let f = a.trim().parse::<f32>().ok()? / b.trim().parse::<f32>().ok()?;
    Some(4.0 * f * if dotted { 1.5 } else { 1.0 })
}

impl Map {
    /// the axis' v (0..1) as this parameter's position (0..1): inverted, and from its `from` up
    fn pos(&self, v: f32) -> f32 {
        let v = if self.invert { 1.0 - v } else { v }.clamp(0.0, 1.0);
        if self.from > 0.0 { ((v - self.from) / (1.0 - self.from).max(1e-6)).clamp(0.0, 1.0) } else { v }
    }

    /// The cells of a stepped map (steps, or the scale's notes), low to high.
    fn cells(&self) -> usize {
        match self.curve.as_str() {
            "steps" => self.steps.len().max(1),
            "scale" => self.notes().len().max(1),
            _ => 0,
        }
    }

    /// a scale's notes between min and max
    fn notes(&self) -> Vec<i32> {
        let (lo, hi) = (self.min.round() as i32, self.max.round() as i32);
        (lo..=hi).filter(|k| self.scale.is_empty() || self.scale.contains(&k.rem_euclid(12))).collect()
    }

    /// The cell under the finger at position p (0..1), kept while the finger stays within `hysteresis` of it.
    pub fn cell(&self, p: f32, last: i32) -> i32 {
        let n = self.cells() as f32;
        let raw = ((p * n) as i32).clamp(0, n as i32 - 1);
        if last < 0 || raw == last { return raw; }
        let (lo, hi) = (last as f32 / n - self.hysteresis, (last + 1) as f32 / n + self.hysteresis);
        if p >= lo && p <= hi { last } else { raw }
    }

    /// A cell's value, at tempo `bpm`.
    pub fn cell_value(&self, i: i32, bpm: f32) -> f32 {
        if self.curve == "scale" {
            let notes = self.notes();
            return notes.get(i.max(0) as usize).copied().unwrap_or(0) as f32;
        }
        let Some(s) = self.steps.get(i.max(0) as usize) else { return self.min };
        if self.tempo_sync {
            let beats = step_beats(s).unwrap_or(1.0);
            if self.unit == "beats" { beats } else { beats * 60.0 / bpm.max(20.0) }
        } else {
            s.parse::<f32>().unwrap_or(i as f32)
        }
    }

    /// The axis' v (0..1) as the parameter's value (continuous curves; steps and scales: the cell's, no memory).
    pub fn value(&self, v: f32, bpm: f32) -> f32 {
        let p = self.pos(v);
        match self.curve.as_str() {
            "exp" => {
                let mn = self.min.max(1e-6);
                mn * (self.max / mn).powf(p)
            }
            "pow" => self.min + p.powf(self.exp.max(1e-3)) * (self.max - self.min),
            "steps" | "scale" => self.cell_value(self.cell(p, -1), bpm),
            _ => self.min + p * (self.max - self.min),
        }
    }
    fn stepped(&self) -> bool { self.curve == "steps" || self.curve == "scale" }
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

/// (tempo, beat position, true when it is the MIDI clock's), for the loop player (loops.rs)
pub fn clock() -> (f64, f64, bool) { transport(*INTERNAL_BEAT.lock().unwrap()) }

/// For the screen: [tempo, beat, 1 when it is the MIDI clock's]
pub fn info() -> [f32; 3] {
    let (bpm, beat, midi) = transport(*INTERNAL_BEAT.lock().unwrap());
    [bpm as f32, beat as f32, if midi { 1.0 } else { 0.0 }]
}
static INTERNAL_BEAT: Mutex<f64> = Mutex::new(0.0);

// ------------------------------------------------------------------ the engine

const WET: i32 = -2;

/// One parameter the pad drives: an axis' mapping ("axes") or a blend of the corners ("corners").
struct Drive {
    idx: i32,              // the effect's parameter, WET the slot's wet level
    axis: usize,           // axes: 0 x, 1 y
    map: Option<Map>,      // axes
    corner: [f32; 4],      // corners: bl, br, tl, tr
    discrete: bool,
}

impl Drive {
    fn smoothed(&self) -> bool { self.map.as_ref().map_or(!self.discrete, |m| !m.stepped()) }
    fn smooth_ms(&self) -> f32 { self.map.as_ref().map_or(20.0, |m| m.smooth_ms) }
}

struct Loaded {
    p: Profile,
    h: *mut FxEffect,
    drives: Vec<Drive>,
    fixed: Vec<(i32, f32)>,
}

struct Slot {
    fx: usize,
    env: f32,           // the touch's envelope (wet level of an insert, level sent to a send), 0..1
    vals: Vec<f32>,     // the parameters as last set (smoothed), one per drive
    cells: Vec<i32>,    // the stepped ones' cells (hysteresis), -1 none yet
    wet: f32,           // the slot's wet level ("wet" on an axis or a corner), 1 else
    leaving: bool,      // on its way out (its tail, or a 30 ms fade)
    quiet: usize,       // frames under -90 dB in a row
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
    targets: Vec<f32>,
}

unsafe impl Send for FxEngine {}

impl Drop for FxEngine {
    fn drop(&mut self) {
        for l in &self.fx { unsafe { fx_free(l.h) } }
    }
}

fn param_index(h: *mut FxEffect, name: &str) -> i32 {
    if name == "wet" || name == "mix" { return WET; }
    if name.is_empty() { return -1; }
    let n = unsafe { fx_num_params(h) };
    for i in 0..n {
        let s = unsafe { CStr::from_ptr(fx_param_name(h, i)) }.to_string_lossy();
        if s == name { return i; }
    }
    -1
}

/// The parameters' targets for the finger at x, y (slot `s`'s stepped cells move with it).
fn targets(l: &Loaded, cells: &mut [i32], x: f32, y: f32, bpm: f32, out: &mut Vec<f32>) {
    out.clear();
    let (x, y) = (x.clamp(0.0, 1.0), y.clamp(0.0, 1.0));
    for (k, d) in l.drives.iter().enumerate() {
        let v = if let Some(m) = &d.map {
            let a = if d.axis == 0 { x } else { y };
            if m.stepped() {
                let c = m.cell(m.pos(a), cells[k]);
                cells[k] = c;
                m.cell_value(c, bpm)
            } else {
                m.value(a, bpm)
            }
        } else if d.discrete {
            // the nearest corner
            let i = (if y >= 0.5 { 2 } else { 0 }) + if x >= 0.5 { 1 } else { 0 };
            d.corner[i]
        } else {
            let [bl, br, tl, tr] = d.corner;
            bl * (1.0 - x) * (1.0 - y) + br * x * (1.0 - y) + tl * (1.0 - x) * y + tr * x * y
        };
        out.push(v);
    }
}

impl FxEngine {
    /// Every effect of `json` (a list of profiles) this build has, made and prepared now (all the allocation).
    pub fn load(json: &str) -> Result<FxEngine, String> {
        let profiles: Vec<Profile> = serde_json::from_str(json).map_err(|e| format!("profiles: {e}"))?;
        let mut fx = Vec::new();
        for p in profiles {
            let id = CString::new(p.id.clone()).map_err(|e| e.to_string())?;
            let h = unsafe { fx_new(id.as_ptr()) };
            if h.is_null() { continue; } // (an effect this build does not have)
            unsafe { fx_prepare(h, SR as f64, BLOCK as c_int) };
            let mut drives = Vec::new();
            if p.mode == "corners" {
                let c = &p.corners;
                let mut names: Vec<&String> = c.bl.keys().chain(c.br.keys()).chain(c.tl.keys()).chain(c.tr.keys()).collect();
                names.sort();
                names.dedup();
                for nm in names {
                    let idx = param_index(h, nm);
                    if idx == -1 { continue; }
                    let g = |m: &HashMap<String, f32>| m.get(nm).copied().unwrap_or(0.0);
                    drives.push(Drive { idx, axis: 0, map: None, corner: [g(&c.bl), g(&c.br), g(&c.tl), g(&c.tr)],
                        discrete: p.discrete.iter().any(|d| d == nm) });
                }
            } else {
                for (axis, maps) in [(0, p.x.list()), (1, p.y.list())] {
                    for m in maps {
                        let idx = param_index(h, &m.param);
                        if idx == -1 { continue; }
                        drives.push(Drive { idx, axis, map: Some(m), corner: [0.0; 4], discrete: false });
                    }
                }
            }
            let fixed = p.fixed.iter().map(|(k, v)| (param_index(h, k), *v)).filter(|(i, _)| *i >= 0).collect();
            fx.push(Loaded { p, h, drives, fixed });
        }
        Ok(FxEngine { fx, active: None, tail: None, seen_touches: TOUCHES.load(Relaxed), beat: 0.0,
            il: [0.0; BLOCK], ir: [0.0; BLOCK], ol: [0.0; BLOCK], or: [0.0; BLOCK], tl: [0.0; BLOCK], tr: [0.0; BLOCK],
            dl: [0.0; BLOCK], dr: [0.0; BLOCK], targets: Vec::with_capacity(32) })
    }

    /// The effects there are (for the screen): their profiles, in order.
    pub fn list(&self) -> Vec<&Profile> { self.fx.iter().map(|l| &l.p).collect() }

    /// A fresh slot for effect i: its parameters where the finger is (or at rest), set at once.
    fn new_slot(&mut self, i: usize) -> Slot {
        let bpm = transport(self.beat).0 as f32;
        let l = &self.fx[i];
        unsafe {
            fx_reset(l.h);
            for &(k, v) in &l.fixed { fx_set_param(l.h, k, v) }
        }
        let mut cells = vec![-1; l.drives.len()];
        let (x, y) = match l.p.rest_at { Some([x, y]) => (x, y), None => (f(&TX), f(&TY)) };
        let mut t = std::mem::take(&mut self.targets);
        targets(l, &mut cells, x, y, bpm, &mut t);
        let vals: Vec<f32> = l.drives.iter().zip(&t).map(|(d, &v)| d.map.as_ref().and_then(|m| m.rest).unwrap_or(v)).collect();
        self.targets = t;
        let mut wet = 1.0;
        for (d, &v) in l.drives.iter().zip(&vals) {
            if d.idx == WET { wet = v.clamp(0.0, 1.0) } else if d.idx >= 0 { unsafe { fx_set_param(l.h, d.idx, v) } }
        }
        Slot { fx: i, env: 0.0, vals, cells, wet, leaving: false, quiet: 0, touching: false, latched: false }
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
                    let s = self.new_slot(w);
                    self.active = Some(s);
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
            let glide = |cur: f32, target: f32, ms: f32| -> f32 {
                if ms <= 0.0 { target } else { target + (cur - target) * (-dt / ms).exp() }
            };
            let back = !s.touching && p.gesture == "return" && !s.latched;
            if s.touching || (back && p.rest_at.is_some()) {
                let (x, y) = if s.touching { (tx, ty) } else { let [x, y] = p.rest_at.unwrap(); (x, y) };
                targets(l, &mut s.cells, x, y, bpm as f32, &mut self.targets);
                for (k, d) in l.drives.iter().enumerate() {
                    let ms = if s.touching { d.smooth_ms() } else { p.release_ms };
                    s.vals[k] = if d.smoothed() { glide(s.vals[k], self.targets[k], ms) } else { self.targets[k] };
                }
            } else if back {
                for (k, d) in l.drives.iter().enumerate() {
                    if let Some(r) = d.map.as_ref().and_then(|m| m.rest) { s.vals[k] = glide(s.vals[k], r, p.release_ms) }
                }
            }
            s.wet = 1.0;
            for (d, &v) in l.drives.iter().zip(&s.vals) {
                if d.idx == WET { s.wet = v.clamp(0.0, 1.0) } else if d.idx >= 0 { unsafe { fx_set_param(l.h, d.idx, v) } }
            }
            let target = if s.touching || s.latched { 1.0 } else { 0.0 };
            let send = p.send();
            let rate_up = 1.0 / (p.attack_ms.max(1.0) * SR / 1000.0);
            let rate_down = 1.0 / (p.release_ms.max(1.0) * SR / 1000.0);
            let listen = p.listen;
            let sleeping = s.env <= 0.0 && target == 0.0 && !send && !listen;
            if !sleeping {
                // the effect's input: the sound (insert), or the sound at the touch's level (send)
                let mut env = s.env;
                for k in 0..m {
                    env = if target > env { (env + rate_up).min(1.0) } else { (env - rate_down).max(0.0) };
                    self.tl[k] = env;
                    if send && !listen { self.il[k] *= env; self.ir[k] *= env; }
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
                            let g = if listen { s.wet * self.tl[k] } else { s.wet };
                            out_l[k] = dl + self.ol[k] * g;
                            out_r[k] = dr + self.or[k] * g;
                        } else {
                            let w = self.tl[k] * s.wet;
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
            let send = l.p.send();
            let listen = l.p.listen;
            let fade = 1.0 / (0.03 * SR);
            let fade_out = 1.0 / (l.p.release_ms.max(30.0) * SR / 1000.0);
            if send && !listen {
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
                if send && listen {
                    // it hears the sound on: its output fades out instead, at its release
                    t.env = (t.env - fade_out).max(0.0);
                    out_l[k] += self.ol[k] * t.wet * t.env;
                    out_r[k] += self.or[k] * t.wet * t.env;
                } else if send {
                    out_l[k] += self.ol[k] * t.wet;
                    out_r[k] += self.or[k] * t.wet;
                    peak = peak.max(self.ol[k].abs()).max(self.or[k].abs());
                } else {
                    t.env = (t.env - fade).max(0.0);
                    let w = t.env * t.wet;
                    // (the outgoing insert's wet part crossfades out against the new one's sound)
                    out_l[k] += (self.ol[k] - self.dl[k]) * w;
                    out_r[k] += (self.or[k] - self.dr[k]) * w;
                }
            }
            if !finite {
                drop_tail = true;
            } else if send && listen {
                drop_tail = t.env <= 0.0;
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
        let a = |j: &str| serde_json::from_str::<Map>(j).unwrap();
        let e = a(r#"{"param":"cutoff","curve":"exp","min":40,"max":18000}"#);
        assert!((e.value(0.0, 120.0) - 40.0).abs() < 0.01 && (e.value(1.0, 120.0) - 18000.0).abs() < 1.0);
        let s = a(r#"{"param":"time","curve":"steps","steps":["1/2","1/4","1/8.","1/8","1/16"],"tempoSync":true}"#);
        assert!((s.value(0.0, 120.0) - 1.0).abs() < 1e-4);     // a half at 120: 1 s (long at the left)
        assert!((s.value(0.5, 120.0) - 0.375).abs() < 1e-4);   // a dotted eighth: 375 ms
        assert!((s.value(1.0, 120.0) - 0.125).abs() < 1e-4);   // a sixteenth: 125 ms
        let b = a(r#"{"param":"length","curve":"steps","steps":["1/4","1/64"],"tempoSync":true,"unit":"beats"}"#);
        assert!((b.value(0.0, 90.0) - 1.0).abs() < 1e-6 && (b.value(1.0, 90.0) - 0.0625).abs() < 1e-6);
        let c = a(r#"{"param":"shift","curve":"scale","min":-12,"max":12,"scale":[0,2,4,5,7,9,11]}"#);
        assert_eq!(c.value(0.5, 120.0), 0.0);
        assert!([0, 2, 4, 5, 7, 9, 11].contains(&(c.value(0.56, 120.0) as i32).rem_euclid(12)));
        let i = a(r#"{"param":"x","min":0,"max":10,"invert":true}"#);
        assert_eq!(i.value(0.0, 120.0), 10.0);
        // pow, and a parameter that only comes in from 0.6 up
        let p = a(r#"{"param":"wet","curve":"pow","exp":2,"min":0,"max":1}"#);
        assert!((p.value(0.5, 120.0) - 0.25).abs() < 1e-6);
        let f = a(r#"{"param":"loopDrive","min":0,"max":0.5,"from":0.6}"#);
        assert_eq!(f.value(0.3, 120.0), 0.0);
        assert_eq!(f.value(0.6, 120.0), 0.0);
        assert!((f.value(0.8, 120.0) - 0.25).abs() < 1e-6 && (f.value(1.0, 120.0) - 0.5).abs() < 1e-6);
        // a range that goes down (a long fall at the left)
        let d = a(r#"{"param":"fall","curve":"exp","min":2.0,"max":0.15}"#);
        assert!((d.value(0.0, 120.0) - 2.0).abs() < 1e-5 && (d.value(1.0, 120.0) - 0.15).abs() < 1e-5);
    }

    #[test]
    fn cells_hold_with_hysteresis() {
        let s = serde_json::from_str::<Map>(r#"{"param":"time","curve":"steps","steps":["a","b","c","d","e"],"hysteresis":0.03}"#).unwrap();
        // five cells of 0.2: from cell 1 (0.2..0.4), 0.41 and 0.19 still hold it, 0.44 and 0.16 do not
        assert_eq!(s.cell(0.3, -1), 1);
        assert_eq!(s.cell(0.41, 1), 1);
        assert_eq!(s.cell(0.19, 1), 1);
        assert_eq!(s.cell(0.44, 1), 2);
        assert_eq!(s.cell(0.16, 1), 0);
    }

    #[test]
    fn corners_blend() {
        let j = r#"[{"id":"x","label":"x","category":"x","mode":"corners","discrete":["sel"],
            "corners":{"bl":{"a":0,"sel":1},"br":{"a":1,"sel":2},"tl":{"a":2,"sel":3},"tr":{"a":3,"sel":4}}}]"#;
        let p: Vec<Profile> = serde_json::from_str(j).unwrap();
        let l = Loaded { p: p[0].clone(), h: std::ptr::null_mut(), drives: vec![
            Drive { idx: 0, axis: 0, map: None, corner: [0.0, 1.0, 2.0, 3.0], discrete: false },
            Drive { idx: 1, axis: 0, map: None, corner: [1.0, 2.0, 3.0, 4.0], discrete: true },
        ], fixed: vec![] };
        let mut cells = vec![-1; 2];
        let mut out = Vec::new();
        targets(&l, &mut cells, 0.5, 0.5, 120.0, &mut out);
        assert!((out[0] - 1.5).abs() < 1e-6);               // the middle: the four averaged
        targets(&l, &mut cells, 1.0, 0.0, 120.0, &mut out);
        assert_eq!(out, vec![1.0, 2.0]);                    // bottom right
        targets(&l, &mut cells, 0.3, 0.8, 120.0, &mut out);
        assert_eq!(out[1], 3.0);                            // a switch: the nearest corner's (top left)
        std::mem::forget(l);                                // (no effect behind it to free)
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
        assert_eq!(ids.len(), 26, "the 26 effects of the brief");
        let idx = |id: &str| ids.iter().position(|x| x == id).unwrap() as i32;
        // nothing chosen: as it is
        select(-1);
        let mut b = sine(440.0, 4096);
        let dry = b.clone();
        e.process(&mut b);
        assert_eq!(b, dry);
        // every effect, touched at the middle and the four corners of the pad: finite, not silent, bounded; released
        for (id, (x, y)) in ids.iter().flat_map(|id| [(0.5f32, 0.5f32), (0.0, 0.0), (1.0, 0.0), (0.0, 1.0), (1.0, 1.0)].map(|p| (id, p))) {
            select(idx(id));
            // half a second untouched first (what the listening ones hear before the finger)
            for _ in 0..11 {
                let mut b = sine(220.0, 1024);
                e.process(&mut b);
            }
            touch(x, y, true);
            let mut out = Vec::new();
            let mut changed = 0f32;
            for _ in 0..40 {
                let mut b = sine(220.0, 1024);
                let dry = b.clone();
                e.process(&mut b);
                changed = changed.max(b.iter().zip(&dry).fold(0f32, |m, (o, d)| m.max((o - d).abs())));
                out.extend_from_slice(&b);
            }
            // it does something to the sound (a hundredth of full scale at least), even at the bottom of the pad
            assert!(changed > 300.0, "{id} at {x},{y}: no change ({changed})");
            touch(x, y, false);
            for _ in 0..10 {
                let mut b = sine(220.0, 1024);
                e.process(&mut b);
                out.extend_from_slice(&b);
            }
            assert!(out.iter().all(|v| v.is_finite()), "{id} at {x},{y}: not finite");
            assert!(peak(&out) > 1000.0, "{id} at {x},{y}: silent");
            assert!(peak(&out) < 32768.0 * 3.0, "{id} at {x},{y}: runaway {}", peak(&out));
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
