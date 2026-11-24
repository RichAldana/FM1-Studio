//! The loop player (the middle zone's "Boucles" page): four slots, each a sound of up to 30 s from the phone,
//! played into the mix as its own strip (BOUCLES: volume, EQ, the pad's send).
//!
//! Each slot:
//!  - plays forward, backward or back and forth (ping-pong), once or over and over;
//!  - starts by the clock (the next beat, bar, 2 or 4 bars: the FM-1's MIDI clock when it comes, else the internal
//!    tempo, TAP's) or on an event: the touch itself, a note from MIDI (the FM-1's), the FM-1's Start, the end of a
//!    pass of another slot, or the real FM-1's sound over a threshold;
//!  - stops at once (a 5 ms fade), at the next bar (or its clock step) or at the end of its pass.
//!
//! States (the page's colours): empty (black), ready (loaded, stopped), armed (red: waiting for its start), playing
//! (green), stopping (green, its stop to come). A slot started by an event goes back to armed after a single pass:
//! the next event plays it again; tapping it disarms it.
//!
//! The sound is kept at 44.1 kHz stereo, 16 bits (5.3 MB for 30 s), resampled once when it is loaded (a windowed
//! sinc, off the audio thread). Playing is a read per sample: four slots cost next to nothing.
//!
//! With the MIDI clock, a beat is placed where the real FM-1's sound of that beat is in the mix: the clock comes
//! straight, the FM-1's audio after its queue, so the beat position is moved back by that queue.

use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, Ordering::Relaxed};
use std::sync::{Arc, Mutex};

pub const SLOTS: usize = 4;
pub const SR: f64 = 44100.0;
pub const MAX_SECONDS: f64 = 30.0;
const FADE_IN: f32 = 1.0 / 64.0;
const FADE_OUT: f32 = 1.0 / 220.0;
/// the threshold trigger: under half the threshold this long before it may fire again
const QUIET_FRAMES: u32 = 2205;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum State { Empty = 0, Ready = 1, Armed = 2, Playing = 3, Stopping = 4 }

pub const DIR_FORWARD: u8 = 0;
pub const DIR_REVERSE: u8 = 1;
pub const DIR_PINGPONG: u8 = 2;

pub const TRIG_CLOCK: u8 = 0;
pub const TRIG_TOUCH: u8 = 1;
pub const TRIG_NOTE: u8 = 2;
pub const TRIG_START: u8 = 3;
pub const TRIG_AFTER: u8 = 4;
pub const TRIG_LEVEL: u8 = 5;

pub const STOP_NOW: u8 = 0;
pub const STOP_BAR: u8 = 1;
pub const STOP_END: u8 = 2;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Settings {
    pub dir: u8,
    pub once: bool,
    pub trig: u8,
    /// the clock's step, in beats (1, 4, 8, 16)
    pub quant: u32,
    /// the note (TRIG_NOTE), -1 any
    pub note: i32,
    /// the slot whose pass ends start this one (TRIG_AFTER)
    pub after: usize,
    /// TRIG_LEVEL, dBFS
    pub level_db: f32,
    pub stop: u8,
    pub gain: f32,
}

impl Default for Settings {
    fn default() -> Self {
        Settings { dir: DIR_FORWARD, once: false, trig: TRIG_CLOCK, quant: 4, note: -1, after: 0, level_db: -30.0, stop: STOP_BAR, gain: 1.0 }
    }
}

struct Slot {
    buf: Option<Arc<Vec<i16>>>,
    frames: usize,
    s: Settings,
    state: State,
    pos: i64,
    step: i64,
    env: f32,
    /// fading out (then ready, or armed again)
    out: bool,
    /// stop at the end of this pass
    stop_at_end: bool,
    /// below half the threshold since (frames)
    quiet: u32,
}

impl Slot {
    const fn new() -> Self {
        Slot { buf: None, frames: 0, s: Settings { dir: 0, once: false, trig: 0, quant: 4, note: -1, after: 0, level_db: -30.0, stop: 1, gain: 1.0 },
               state: State::Empty, pos: 0, step: 1, env: 0.0, out: false, stop_at_end: false, quiet: QUIET_FRAMES }
    }

    fn event_driven(&self) -> bool { matches!(self.s.trig, TRIG_NOTE | TRIG_START | TRIG_AFTER | TRIG_LEVEL) }

    fn begin(&mut self) {
        if self.buf.is_none() { return; }
        self.state = State::Playing;
        self.env = 0.0;
        self.out = false;
        self.stop_at_end = false;
        if self.s.dir == DIR_REVERSE { self.pos = self.frames as i64 - 1; self.step = -1; } else { self.pos = 0; self.step = 1; }
    }

    /// after the last sound of a stop: ready, or armed again for the next event
    fn ended(&mut self, rearm: bool) {
        self.out = false;
        self.stop_at_end = false;
        self.env = 0.0;
        self.state = if rearm && self.event_driven() { State::Armed } else { State::Ready };
    }

    /// Play frames `from..to` into `out` (stereo, the 16-bit scale). Returns the frame after the end of a pass, if
    /// one ended here (the slots that follow this one start there).
    fn render(&mut self, out: &mut [f32], from: usize, to: usize) -> Option<usize> {
        let buf = match &self.buf { Some(b) => b.clone(), None => return None };
        let n = self.frames as i64;
        let mut pass_end = None;
        let g = self.s.gain;
        let mut k = from;
        while k < to && matches!(self.state, State::Playing | State::Stopping) {
            if self.out {
                self.env -= FADE_OUT;
                if self.env <= 0.0 { self.ended(false); break; }
            } else if self.env < 1.0 {
                self.env = (self.env + FADE_IN).min(1.0);
            }
            let i = self.pos.clamp(0, n - 1) as usize;
            let a = g * self.env;
            out[2 * k] += buf[2 * i] as f32 * a;
            out[2 * k + 1] += buf[2 * i + 1] as f32 * a;
            self.pos += self.step;
            let wrapped = match self.s.dir {
                DIR_REVERSE => if self.pos < 0 { self.pos = n - 1; true } else { false },
                DIR_PINGPONG => {
                    if self.pos >= n { self.pos = (n - 2).max(0); self.step = -1; false }
                    else if self.pos < 0 { self.pos = 1.min(n - 1); self.step = 1; true }
                    else { false }
                }
                _ => if self.pos >= n { self.pos = 0; true } else { false },
            };
            k += 1;
            if wrapped {
                pass_end = Some(k);
                if self.s.once || self.stop_at_end {
                    let rearm = !self.stop_at_end;
                    self.ended(rearm);
                    break;
                }
            }
        }
        pass_end
    }
}

pub struct Loops {
    slots: [Slot; SLOTS],
    /// the internal clock's beat (when no MIDI clock comes)
    beat: f64,
    /// the file the passage screen works on (whole, at its own rate), and its preview
    src: Option<Arc<Source>>,
    pv: Preview,
}

/// A whole file for the passage screen: stereo 16-bit at the file's rate (not resampled: only the passage kept is).
pub struct Source {
    pcm: Vec<i16>,
    rate: u32,
}

impl Source {
    fn frames(&self) -> usize { self.pcm.len() / 2 }
}

/// The passage screen's "listen": frames a..b of the source, round and round.
struct Preview {
    on: bool,
    a: usize,
    b: usize,
    pos: f64,
    env: f32,
}

static LOOPS: Mutex<Loops> = Mutex::new(Loops {
    slots: [Slot::new(), Slot::new(), Slot::new(), Slot::new()],
    beat: 0.0,
    src: None,
    pv: Preview { on: false, a: 0, b: 0, pos: 0.0, env: 0.0 },
});
/// a source being read (the file comes in pieces), swapped in by src_end()
static BUILD: Mutex<Option<Source>> = Mutex::new(None);
/// the longest file the passage screen takes (10 min: about 100 MB at 44.1 kHz)
pub const SRC_MAX_SECONDS: f64 = 600.0;

// events from the MIDI thread, taken by the audio thread (no lock on the way)
static NOTE_HIT: [AtomicBool; SLOTS] = [const { AtomicBool::new(false) }; SLOTS];
static NOTE_OF: [AtomicI32; SLOTS] = [const { AtomicI32::new(-1) }; SLOTS];
static START_HIT: AtomicBool = AtomicBool::new(false);
/// the slot learning a note (-1 none)
static LEARN: AtomicI32 = AtomicI32::new(-1);
/// what the page shows, per slot: state, where it plays (0..1), length (s), its note (-1 none)
static INFO: [[AtomicU32; 4]; SLOTS] = [const { [const { AtomicU32::new(0) }; 4] }; SLOTS];
/// the strip's volume (0..1.5, f32 bits; 0 when it is off)
pub static GAIN: AtomicU32 = AtomicU32::new(0x3f80_0000);

/// A note-on from MIDI (engine.rs, the router).
pub fn note_on(note: u8) {
    let l = LEARN.swap(-1, Relaxed);
    if l >= 0 && (l as usize) < SLOTS {
        NOTE_OF[l as usize].store(note as i32, Relaxed);
        return;
    }
    for i in 0..SLOTS {
        let n = NOTE_OF[i].load(Relaxed);
        if n < 0 || n == note as i32 { NOTE_HIT[i].store(true, Relaxed); }
    }
}

/// MIDI Start (engine.rs, the router).
pub fn midi_start() { START_HIT.store(true, Relaxed); }

/// (tempo, beat, true when it is the MIDI clock's): the pad's (fx.rs)
#[cfg(not(test))]
fn clock() -> (f64, f64, bool) { crate::fx::clock() }
#[cfg(test)]
fn clock() -> (f64, f64, bool) { (120.0, 0.0, false) }

fn lock() -> std::sync::MutexGuard<'static, Loops> { LOOPS.lock().unwrap_or_else(|p| p.into_inner()) }

/// A sound into slot `i`: `data` interleaved by `channels` at `rate`, full scale 1.0. Resampled here (the caller's
/// thread), then swapped in. Returns its length in seconds (cut at 30 s).
pub fn load(i: usize, data: &[f32], channels: usize, rate: u32) -> Result<f64, String> {
    if i >= SLOTS { return Err("slot".into()); }
    if channels == 0 || rate < 4000 || data.len() < channels { return Err("empty sound".into()); }
    let frames_in = data.len() / channels;
    let keep_in = frames_in.min((MAX_SECONDS * rate as f64).ceil() as usize);
    let mut st = vec![0f32; keep_in * 2];
    for f in 0..keep_in {
        let l = data[f * channels];
        let r = if channels > 1 { data[f * channels + 1] } else { l };
        st[2 * f] = l;
        st[2 * f + 1] = r;
    }
    let st = if rate == SR as u32 { st } else { resample(&st, rate as f64, SR) };
    let frames = (st.len() / 2).min((MAX_SECONDS * SR) as usize);
    if frames < 2 { return Err("too short".into()); }
    let pcm: Vec<i16> = st[..frames * 2].iter().map(|&v| (v * 32767.0).round().clamp(-32768.0, 32767.0) as i16).collect();
    let old = {
        let mut g = lock();
        let s = &mut g.slots[i];
        let old = s.buf.replace(Arc::new(pcm));
        s.frames = frames;
        s.state = State::Ready;
        s.out = false;
        s.stop_at_end = false;
        old
    };
    drop(old);
    publish_one(&lock(), i);
    Ok(frames as f64 / SR)
}

/// Slot `i`'s sound (stereo 16-bit at 44.1 kHz), for a project to keep.
pub fn pcm(i: usize) -> Option<Arc<Vec<i16>>> {
    if i >= SLOTS { return None; }
    lock().slots[i].buf.clone()
}

/// Empty slot `i`.
pub fn clear(i: usize) {
    if i >= SLOTS { return; }
    let old = {
        let mut g = lock();
        let s = &mut g.slots[i];
        s.state = State::Empty;
        s.frames = 0;
        s.buf.take()
    };
    drop(old);
    publish_one(&lock(), i);
}

pub fn set(i: usize, s: Settings) {
    if i >= SLOTS { return; }
    NOTE_OF[i].store(s.note, Relaxed);
    let mut g = lock();
    let sl = &mut g.slots[i];
    let was_pp = sl.s.dir;
    sl.s = s;
    sl.s.after = s.after.min(SLOTS - 1);
    // a direction changed while it plays: keep the place, go the new way
    if sl.state == State::Playing && was_pp != s.dir {
        sl.step = if s.dir == DIR_REVERSE { -1 } else { 1 };
    }
}

/// The next note heard sets slot `i`'s (-1: stop learning).
pub fn learn(i: i32) { LEARN.store(i, Relaxed); }

/// A tap on slot `i`'s button: ready → plays or arms; armed → ready; playing → stops (as its setting says; a
/// second tap while it stops: at once).
pub fn tap(i: usize) {
    if i >= SLOTS { return; }
    let (_, _, midi) = clock();
    let mut g = lock();
    let any_playing = g.slots.iter().any(|s| matches!(s.state, State::Playing | State::Stopping));
    let mut restart_clock = false;
    let s = &mut g.slots[i];
    match s.state {
        State::Empty => {}
        State::Ready => match s.s.trig {
            TRIG_TOUCH => s.begin(),
            // nothing plays and no MIDI clock: the internal clock starts with it, the bar from here
            TRIG_CLOCK if !midi && !any_playing => { s.begin(); restart_clock = true; }
            _ => { s.state = State::Armed; s.quiet = 0; }
        },
        State::Armed => s.state = State::Ready,
        State::Playing => match s.s.stop {
            STOP_NOW => s.out = true,
            STOP_END => { s.stop_at_end = true; s.state = State::Stopping; }
            _ => s.state = State::Stopping,       // at the next step (process() places it)
        },
        State::Stopping => { s.out = true; }
    }
    if restart_clock { g.beat = 0.0; }
    publish(&g);
}

/// Stop everything at once (STOP), disarm what waits.
pub fn stop_all() {
    let mut g = lock();
    for s in g.slots.iter_mut() {
        match s.state {
            State::Playing | State::Stopping => { s.out = true; s.stop_at_end = false; s.state = State::Stopping; }
            State::Armed => s.state = State::Ready,
            _ => {}
        }
    }
    publish(&g);
}

/// [state, where it plays 0..1, length s, note] per slot.
pub fn info() -> [f32; SLOTS * 4] {
    let mut o = [0f32; SLOTS * 4];
    for i in 0..SLOTS {
        for k in 0..4 { o[4 * i + k] = f32::from_bits(INFO[i][k].load(Relaxed)); }
        o[4 * i + 3] = NOTE_OF[i].load(Relaxed) as f32;
    }
    o
}

fn publish_one(g: &Loops, i: usize) {
    let s = &g.slots[i];
    let state = if s.state == State::Playing && s.out { State::Stopping } else { s.state };
    let at = if s.frames > 0 { s.pos.clamp(0, s.frames as i64) as f32 / s.frames as f32 } else { 0.0 };
    INFO[i][0].store((state as i32 as f32).to_bits(), Relaxed);
    INFO[i][1].store(at.to_bits(), Relaxed);
    INFO[i][2].store(((s.frames as f64 / SR) as f32).to_bits(), Relaxed);
}

fn publish(g: &Loops) { for i in 0..SLOTS { publish_one(g, i); } }

/// The first frame in `0..n` where a beat that is a multiple of `q` falls, from beat `b0` at `bpf` beats a frame.
fn boundary(b0: f64, bpf: f64, q: f64, n: usize) -> Option<usize> {
    if bpf <= 0.0 || q <= 0.0 { return None; }
    let next = (b0 / q - 1e-9).ceil() * q;
    let off = ((next - b0) / bpf).ceil().max(0.0);
    if off < n as f64 { Some(off as usize) } else { None }
}

/// One block, all in one: `out` (stereo interleaved, the 16-bit scale) gets the slots and the preview added, at the
/// strip's volume (the tests, the bench; the engine uses `process_split`).
pub fn process(out: &mut [f32], ext: &[f32], ext_late_frames: f64) {
    let n2 = out.len() & !1;
    let mut parts = vec![0f32; PARTS * n2];
    process_split(&mut parts, n2, ext, ext_late_frames);
    let gain = f32::from_bits(GAIN.load(Relaxed));
    for k in 0..n2 {
        let mut v = 0.0;
        for p in 0..PARTS { v += parts[p * n2 + k]; }
        out[k] += v * gain;
    }
}

/// The parts `process_split` writes: one per slot (each its own track's effect: fx.rs), then the passage screen's
/// preview.
pub const PARTS: usize = SLOTS + 1;

/// One block: `parts` holds PARTS buffers of `n2` values (stereo interleaved, the 16-bit scale) each; slot i's
/// sound goes into the i-th, the preview into the last (added: the caller clears them). `ext` is the real FM-1 as it
/// is in this block (the level trigger), `ext_late_frames` how far behind the clock its sound is. The strip's volume
/// (GAIN) is the caller's.
pub fn process_split(parts: &mut [f32], n2: usize, ext: &[f32], ext_late_frames: f64) {
    let n = n2 / 2;
    if n == 0 || parts.len() < PARTS * n2 { return; }
    let (bpm, midi_beat, midi) = clock();
    let bpf = bpm / 60.0 / SR;
    let start_hit = START_HIT.swap(false, Relaxed);
    let mut g = lock();
    if g.pv.on { preview(&mut g, &mut parts[SLOTS * n2..(SLOTS + 1) * n2]); }
    if g.slots.iter().all(|s| s.state == State::Empty) {
        g.beat += bpf * n as f64;
        return;
    }
    let b0 = if midi { midi_beat - ext_late_frames * bpf } else { g.beat };
    // the threshold: where the real FM-1 first goes over it in this block
    let peak_at = |db: f32| -> Option<usize> {
        let t = 32768.0 * 10f32.powf(db / 20.0);
        (0..n.min(ext.len() / 2)).find(|&k| ext[2 * k].abs().max(ext[2 * k + 1].abs()) > t)
    };
    let mut ends: [Option<usize>; SLOTS] = [None; SLOTS];
    for i in 0..SLOTS {
        let note_hit = NOTE_HIT[i].swap(false, Relaxed);
        let s = &mut g.slots[i];
        if s.buf.is_none() { continue; }
        let out = &mut parts[i * n2..(i + 1) * n2];
        // the level trigger's quiet time
        let quiet_now = {
            let t = 32768.0 * 10f32.powf(s.s.level_db / 20.0) * 0.5;
            (0..n.min(ext.len() / 2)).all(|k| ext[2 * k].abs().max(ext[2 * k + 1].abs()) <= t)
        };
        // what starts it in this block, and where
        let mut start: Option<usize> = None;
        match (s.state, s.s.trig) {
            (State::Armed, TRIG_CLOCK) => start = boundary(b0, bpf, s.s.quant.max(1) as f64, n),
            (State::Armed, TRIG_TOUCH) => start = Some(0),
            (State::Armed | State::Playing, TRIG_NOTE) if note_hit => start = Some(0),
            (State::Armed | State::Playing, TRIG_START) if start_hit => start = Some(0),
            (State::Armed, TRIG_LEVEL) if s.quiet >= QUIET_FRAMES => start = peak_at(s.s.level_db),
            _ => {}
        }
        s.quiet = if quiet_now { s.quiet.saturating_add(n as u32) } else { 0 };
        // a stop at the next step: placed now, done at that frame
        let mut stop_at: Option<usize> = None;
        if s.state == State::Stopping && !s.stop_at_end && !s.out {
            let q = if s.s.trig == TRIG_CLOCK { s.s.quant.max(1) as f64 } else { 4.0 };
            stop_at = boundary(b0, bpf, q, n);
        }
        let mut from = 0;
        if let Some(o) = start {
            // (a retrigger while it plays: what played so far in the block stays)
            if s.state == State::Playing { ends[i] = s.render(out, 0, o).or(ends[i]); }
            s.begin();
            from = o;
        }
        match stop_at {
            Some(o) => {
                ends[i] = s.render(out, from, o).or(ends[i]);
                s.out = true;
                ends[i] = s.render(out, o, n).or(ends[i]);
            }
            None => ends[i] = s.render(out, from, n).or(ends[i]),
        }
    }
    // the slots that start when another one's pass ends
    for i in 0..SLOTS {
        let s = &g.slots[i];
        if s.state != State::Armed || s.s.trig != TRIG_AFTER || s.s.after == i { continue; }
        if let Some(o) = ends[s.s.after] {
            let s = &mut g.slots[i];
            s.begin();
            s.render(&mut parts[i * n2..(i + 1) * n2], o.min(n), n);
        }
    }
    g.beat += bpf * n as f64;
    publish(&g);
}

/// The passage screen's preview into `out` (the 16-bit scale), read at the file's rate with a linear step.
fn preview(g: &mut Loops, out: &mut [f32]) {
    let src = match &g.src { Some(s) => s.clone(), None => return };
    let pv = &mut g.pv;
    let n = out.len() / 2;
    let fr = src.frames();
    let (a, b) = (pv.a.min(fr), pv.b.min(fr));
    if b <= a + 1 { return; }
    if pv.pos < a as f64 || pv.pos >= b as f64 { pv.pos = a as f64; pv.env = 0.0; }
    let step = src.rate as f64 / SR;
    for k in 0..n {
        pv.env = (pv.env + FADE_IN).min(1.0);
        let i = pv.pos as usize;
        let f = (pv.pos - i as f64) as f32;
        let j = if i + 1 < b { i + 1 } else { a };
        let l = src.pcm[2 * i] as f32 * (1.0 - f) + src.pcm[2 * j] as f32 * f;
        let r = src.pcm[2 * i + 1] as f32 * (1.0 - f) + src.pcm[2 * j + 1] as f32 * f;
        out[2 * k] += l * pv.env;
        out[2 * k + 1] += r * pv.env;
        pv.pos += step;
        if pv.pos >= b as f64 { pv.pos = a as f64 + (pv.pos - b as f64); }
    }
}

/// Start reading a file for the passage screen: its rate, and about how many frames (to make room once).
pub fn src_begin(rate: u32, frames_hint: usize) {
    let cap = frames_hint.min((SRC_MAX_SECONDS * rate as f64) as usize);
    *BUILD.lock().unwrap_or_else(|p| p.into_inner()) = Some(Source { pcm: Vec::with_capacity(cap * 2), rate });
}

/// A piece of it: `data` interleaved by `channels`, full scale 1.0. Past 10 min: left out. Returns the frames so far.
pub fn src_append(data: &[f32], channels: usize) -> usize {
    let mut g = BUILD.lock().unwrap_or_else(|p| p.into_inner());
    let src = match g.as_mut() { Some(s) => s, None => return 0 };
    if channels == 0 { return src.frames(); }
    let max = (SRC_MAX_SECONDS * src.rate as f64) as usize;
    let q = |v: f32| (v * 32767.0).round().clamp(-32768.0, 32767.0) as i16;
    for f in 0..data.len() / channels {
        if src.frames() >= max { break; }
        let l = data[f * channels];
        let r = if channels > 1 { data[f * channels + 1] } else { l };
        src.pcm.push(q(l));
        src.pcm.push(q(r));
    }
    src.frames()
}

/// The file read: it becomes the passage screen's. Returns its frames.
pub fn src_end() -> usize {
    let built = BUILD.lock().unwrap_or_else(|p| p.into_inner()).take();
    let n = built.as_ref().map_or(0, |s| s.frames());
    let old = { let mut g = lock(); g.pv.on = false; std::mem::replace(&mut g.src, built.map(Arc::new)) };
    drop(old);
    n
}

/// The passage screen closed: its file let go.
pub fn src_free() {
    let old = { let mut g = lock(); g.pv.on = false; g.src.take() };
    drop(old);
    *BUILD.lock().unwrap_or_else(|p| p.into_inner()) = None;
}

fn src() -> Option<Arc<Source>> { lock().src.clone() }

/// The source's rate and length (frames), or (0, 0).
pub fn src_info() -> (u32, usize) { src().map_or((0, 0), |s| (s.rate, s.frames())) }

/// The loudest of each of `bins` parts of frames `from..to` (1 = full scale).
pub fn src_peaks(from: usize, to: usize, bins: usize) -> Vec<f32> {
    let mut out = vec![0f32; bins];
    let s = match src() { Some(s) => s, None => return out };
    let to = to.min(s.frames());
    if to <= from || bins == 0 { return out; }
    let span = to - from;
    for (b, o) in out.iter_mut().enumerate() {
        let a = from + b * span / bins;
        let z = (from + (b + 1) * span / bins).max(a + 1).min(to);
        // (a long part: a few thousand reads are enough to find its loudest)
        let step = ((z - a) / 2048).max(1);
        let mut m = 0i32;
        let mut f = a;
        while f < z {
            m = m.max((s.pcm[2 * f] as i32).abs()).max((s.pcm[2 * f + 1] as i32).abs());
            f += step;
        }
        *o = m as f32 / 32768.0;
    }
    out
}

/// Where the sound starts again (the attacks), in frames: a jump of energy over 10 ms parts.
pub fn src_onsets() -> Vec<usize> {
    let s = match src() { Some(s) => s, None => return Vec::new() };
    let hop = (s.rate as usize / 100).max(1);
    let hops = s.frames() / hop;
    let mut e = vec![0f32; hops];
    for (h, v) in e.iter_mut().enumerate() {
        let mut acc = 0f64;
        for f in h * hop..(h + 1) * hop {
            let m = (s.pcm[2 * f] as f64 + s.pcm[2 * f + 1] as f64) * 0.5 / 32768.0;
            acc += m * m;
        }
        *v = (acc / hop as f64) as f32;
    }
    let floor = 10f32.powf(-45.0 / 10.0);     // -45 dBFS of mean power
    let mut out = Vec::new();
    let mut last: Option<usize> = None;
    for h in 1..hops {
        let lo = h.saturating_sub(8);
        let mean = e[lo..h].iter().sum::<f32>() / (h - lo) as f32;
        if e[h] > floor && e[h] > 4.0 * mean + 1e-7 && last.map_or(true, |l| h - l >= 8) {
            // the first frame of the hop (or the one before) where it rises: the loudest jump between samples
            out.push(h * hop);
            last = Some(h);
        }
    }
    out
}

/// The zero crossing nearest frame `at` within `radius` frames (else `at`).
pub fn src_zero(at: usize, radius: usize) -> usize {
    let s = match src() { Some(s) => s, None => return at };
    let n = s.frames();
    if n < 2 { return at; }
    let mono = |f: usize| s.pcm[2 * f] as i32 + s.pcm[2 * f + 1] as i32;
    for d in 0..=radius {
        for f in [at.saturating_sub(d), at + d] {
            if f + 1 < n && (mono(f) <= 0) != (mono(f + 1) <= 0) { return f + 1; }
            if f < n && mono(f) == 0 { return f; }
        }
    }
    at.min(n - 1)
}

/// The preview: frames a..b of the source, round and round (on), or quiet.
pub fn src_preview(a: usize, b: usize, on: bool) {
    let mut g = lock();
    let restart = on && !g.pv.on;
    g.pv.a = a;
    g.pv.b = b;
    g.pv.on = on;
    if restart { g.pv.pos = a as f64; g.pv.env = 0.0; }
}

/// The passage a..b (frames of the source, 30 s at most) into slot `i`. Its length in seconds.
pub fn src_commit(i: usize, a: usize, b: usize) -> Result<f64, String> {
    let s = src().ok_or("no file")?;
    let b = b.min(s.frames()).min(a + (MAX_SECONDS * s.rate as f64).round() as usize);
    if b <= a + 1 { return Err("empty passage".into()); }
    let data: Vec<f32> = s.pcm[2 * a..2 * b].iter().map(|&v| v as f32 / 32767.0).collect();
    load(i, &data, 2, s.rate)
}

const RS_K: usize = 16;          // half the taps
const RS_P: usize = 512;         // phases

/// Stereo interleaved `x` from `from` Hz to `to` Hz: a windowed sinc (32 taps, Blackman) from a table of 512
/// phases (linear between them), for loading only.
pub fn resample(x: &[f32], from: f64, to: f64) -> Vec<f32> {
    let n_in = x.len() / 2;
    if n_in == 0 { return Vec::new(); }
    let step = from / to;
    let n_out = (n_in as f64 * to / from).round() as usize;
    let fc = 0.95 * (to / from).min(1.0);
    // table[p][j]: the weight of tap j (input frame c - K + 1 + j) when the output falls p/P past frame c
    let taps = 2 * RS_K;
    let mut table = vec![0f32; (RS_P + 1) * taps];
    for p in 0..=RS_P {
        let frac = p as f64 / RS_P as f64;
        let mut sum = 0.0;
        let row = &mut table[p * taps..(p + 1) * taps];
        for (j, w) in row.iter_mut().enumerate() {
            let d = frac + (RS_K as f64 - 1.0) - j as f64;
            let v = if d.abs() >= RS_K as f64 { 0.0 } else {
                let s = if d == 0.0 { 1.0 } else { (std::f64::consts::PI * fc * d).sin() / (std::f64::consts::PI * fc * d) };
                let wp = d / RS_K as f64;
                s * (0.42 + 0.5 * (std::f64::consts::PI * wp).cos() + 0.08 * (2.0 * std::f64::consts::PI * wp).cos())
            };
            *w = v as f32;
            sum += v;
        }
        if sum != 0.0 { for w in row.iter_mut() { *w = (*w as f64 / sum) as f32; } }
    }
    let mut out = vec![0f32; n_out * 2];
    for jo in 0..n_out {
        let t = jo as f64 * step;
        let c = t.floor() as i64;
        let fp = (t - c as f64) * RS_P as f64;
        let p = (fp as usize).min(RS_P - 1);
        let a = (fp - p as f64) as f32;
        let r0 = &table[p * taps..(p + 1) * taps];
        let r1 = &table[(p + 1) * taps..(p + 2) * taps];
        let first = c - RS_K as i64 + 1;
        let (mut l, mut r) = (0f32, 0f32);
        if first >= 0 && (first as usize + taps) <= n_in {
            let base = first as usize;
            for j in 0..taps {
                let w = r0[j] + a * (r1[j] - r0[j]);
                l += x[2 * (base + j)] * w;
                r += x[2 * (base + j) + 1] * w;
            }
        } else {
            for j in 0..taps {
                let m = first + j as i64;
                if m < 0 || m as usize >= n_in { continue; }
                let w = r0[j] + a * (r1[j] - r0[j]);
                l += x[2 * m as usize] * w;
                r += x[2 * m as usize + 1] * w;
            }
        }
        out[2 * jo] = l;
        out[2 * jo + 1] = r;
    }
    out
}

#[cfg(test)]
pub(crate) fn reset_for_test() {
    let mut g = lock();
    for s in g.slots.iter_mut() { *s = Slot::new(); }
    g.beat = 0.0;
    for i in 0..SLOTS { NOTE_OF[i].store(-1, Relaxed); NOTE_HIT[i].store(false, Relaxed); }
    START_HIT.store(false, Relaxed);
    LEARN.store(-1, Relaxed);
    GAIN.store(1f32.to_bits(), Relaxed);
    g.src = None;
    g.pv.on = false;
}

#[cfg(test)]
pub(crate) static TEST_LOCK: Mutex<()> = Mutex::new(());

#[cfg(test)]
mod tests {
    use super::*;

    /// a ramp: frame f holds f (so where it plays can be read back), `frames` long, at 44.1 kHz
    fn ramp(frames: usize) -> Vec<f32> {
        (0..frames * 2).map(|k| (k / 2) as f32 / 32767.0).collect()
    }

    fn run(n: usize) -> Vec<f32> {
        let mut out = vec![0f32; 2 * n];
        process(&mut out, &vec![0f32; 2 * n], 0.0);
        out
    }

    fn state(i: usize) -> f32 { info()[4 * i] }

    #[test]
    fn forward_loop_wraps_and_once_stops() {
        let _l = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_for_test();
        load(0, &ramp(100), 2, 44100).unwrap();
        set(0, Settings { trig: TRIG_TOUCH, ..Default::default() });
        tap(0);
        assert_eq!(state(0), State::Playing as i32 as f32);
        let o = run(250);
        // after the fade-in (64 frames), frame k plays sample k mod 100
        assert!((o[2 * 120] - 20.0).abs() < 1.0 && (o[2 * 205] - 5.0).abs() < 1.0, "{} {}", o[240], o[410]);
        set(0, Settings { trig: TRIG_TOUCH, once: true, ..Default::default() });
        let _ = run(200);
        assert_eq!(state(0), State::Ready as i32 as f32);
    }

    #[test]
    fn reverse_and_pingpong() {
        let _l = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_for_test();
        load(1, &ramp(100), 2, 44100).unwrap();
        set(1, Settings { trig: TRIG_TOUCH, dir: DIR_REVERSE, ..Default::default() });
        tap(1);
        let o = run(130);
        assert!((o[2 * 80] - 19.0).abs() < 1.0, "{}", o[160]);           // 99 - 80
        assert!((o[2 * 120] - 79.0).abs() < 1.0, "{}", o[240]);          // wrapped: 99 - 20
        reset_for_test();
        load(1, &ramp(100), 2, 44100).unwrap();
        set(1, Settings { trig: TRIG_TOUCH, dir: DIR_PINGPONG, ..Default::default() });
        tap(1);
        let o = run(260);
        assert!((o[2 * 90] - 90.0).abs() < 1.0);
        assert!((o[2 * 110] - 88.0).abs() < 1.0, "{}", o[220]);          // turned at 99: 98, 97, ...
        assert!((o[2 * 250] - 52.0).abs() < 1.0, "{}", o[500]);          // back at 0 at 198, then up: 250 - 198
    }

    #[test]
    fn clock_start_waits_for_the_bar() {
        let _l = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_for_test();
        load(2, &vec![0.5f32; 2 * 44100], 2, 44100).unwrap();
        load(3, &vec![0.5f32; 2 * 44100], 2, 44100).unwrap();
        set(2, Settings { trig: TRIG_TOUCH, ..Default::default() });
        set(3, Settings { trig: TRIG_CLOCK, quant: 4, ..Default::default() });
        tap(2);                                         // something plays: the internal clock runs from 0
        let _ = run(1000);
        tap(3);
        assert_eq!(state(3), State::Armed as i32 as f32);
        // a bar at 120: 2 s = 88200 frames from the start; 1000 played: it starts at 87200 from here
        let mut played = 0usize;
        let mut first = None;
        while played < 90000 {
            let o = run(512);
            if first.is_none() && state(3) == State::Playing as i32 as f32 {
                first = Some(played);
                let _ = o;
            }
            played += 512;
        }
        let f = first.expect("started");
        assert!(f <= 87200 && f + 512 > 87200, "{f}");
    }

    #[test]
    fn note_arms_plays_once_and_rearms() {
        let _l = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_for_test();
        load(0, &ramp(50), 2, 44100).unwrap();
        set(0, Settings { trig: TRIG_NOTE, note: 60, once: true, ..Default::default() });
        note_on(60);                                    // not armed: nothing
        let _ = run(10);
        assert_eq!(state(0), State::Ready as i32 as f32);
        tap(0);
        assert_eq!(state(0), State::Armed as i32 as f32);
        note_on(61);
        let _ = run(10);
        assert_eq!(state(0), State::Armed as i32 as f32);
        note_on(60);
        let _ = run(10);
        assert_eq!(state(0), State::Playing as i32 as f32);
        let _ = run(100);
        assert_eq!(state(0), State::Armed as i32 as f32);  // armed again for the next note
        learn(0);
        note_on(64);
        assert_eq!(info()[3], 64.0);
    }

    #[test]
    fn after_another_and_stop_at_end() {
        let _l = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_for_test();
        load(0, &ramp(100), 2, 44100).unwrap();
        load(1, &ramp(100), 2, 44100).unwrap();
        set(0, Settings { trig: TRIG_TOUCH, stop: STOP_END, ..Default::default() });
        set(1, Settings { trig: TRIG_AFTER, after: 0, once: true, ..Default::default() });
        tap(1);
        tap(0);
        let o = run(150);
        // slot 1 starts at frame 100, where slot 0 wraps: at 150 both play (0 at 50, 1 at 50, faded in)
        assert_eq!(state(1), State::Playing as i32 as f32);
        assert!(o[2 * 149] > 49.0 + 30.0, "{}", o[298]);     // 49, and 49 fading in (50 of 64 frames)
        tap(0);                                         // stop at the end of the pass
        assert_eq!(state(0), State::Stopping as i32 as f32);
        let _ = run(60);
        assert_eq!(state(0), State::Ready as i32 as f32);
    }

    #[test]
    fn level_trigger_needs_quiet_first() {
        let _l = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_for_test();
        load(0, &ramp(1000), 2, 44100).unwrap();
        set(0, Settings { trig: TRIG_LEVEL, level_db: -20.0, once: true, ..Default::default() });
        tap(0);
        let loud = vec![10000f32; 2 * 256];
        let mut out = vec![0f32; 2 * 256];
        process(&mut out, &loud, 0.0);                  // loud from the start: no quiet before, not yet
        assert_eq!(state(0), State::Armed as i32 as f32);
        for _ in 0..10 { let mut o = vec![0f32; 512]; process(&mut o, &vec![0f32; 512], 0.0); }
        let mut ext = vec![0f32; 2 * 256];
        for v in ext[2 * 40..].iter_mut() { *v = 10000.0; }
        let mut out = vec![0f32; 2 * 256];
        process(&mut out, &ext, 0.0);
        assert_eq!(state(0), State::Playing as i32 as f32);
        assert_eq!(out[2 * 39], 0.0);
        assert!(out[2 * 41] != 0.0 || out[2 * 42] != 0.0);
    }

    #[test]
    fn resampling_keeps_a_tone() {
        // 1 kHz at 48 kHz → 44.1 kHz: same length in time, same amplitude
        let x: Vec<f32> = (0..96000).map(|k| (2.0 * std::f32::consts::PI * 1000.0 * (k / 2) as f32 / 48000.0).sin() * 0.5).collect();
        let y = resample(&x, 48000.0, 44100.0);
        assert_eq!(y.len() / 2, 44100);
        let peak = y[2000..80000].iter().fold(0f32, |m, v| m.max(v.abs()));
        assert!((peak - 0.5).abs() < 0.01, "{peak}");
        // the zero crossings at 1 kHz: 2000 a second
        let z = (1000..43000).filter(|&k| (y[2 * k] >= 0.0) != (y[2 * k + 2] >= 0.0)).count();
        assert!((z as i32 - 1905).abs() < 10, "{z}");
    }

    #[test]
    fn cut_at_thirty_seconds() {
        let _l = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_for_test();
        let s = load(2, &vec![0.1f32; 44100 * 35], 1, 44100).unwrap();
        assert!((s - 30.0).abs() < 1e-6);
    }

    #[test]
    fn passage_from_a_long_file() {
        let _l = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        reset_for_test();
        // 60 s at 48 kHz, mono: quiet with a click every second (at 0.5 s, 1.5 s, ...)
        let rate = 48000usize;
        let mut x = vec![0.001f32; rate * 60];
        for k in 0..60 { for f in 0..200 { x[k * rate + rate / 2 + f] = 0.8; } }
        src_begin(rate as u32, x.len());
        for piece in x.chunks(10000) { src_append(piece, 1); }
        assert_eq!(src_end(), rate * 60);
        assert_eq!(src_info(), (rate as u32, rate * 60));
        let on = src_onsets();
        assert_eq!(on.len(), 60, "{:?}", &on[..on.len().min(5)]);
        assert!(on.iter().enumerate().all(|(k, &f)| (f as i64 - (k * rate + rate / 2) as i64).abs() <= (rate / 100) as i64));
        let pk = src_peaks(0, rate * 60, 60);
        assert!(pk.iter().all(|&v| (v - 0.8).abs() < 0.01));
        // 10 s from 20.5 s into slot 1: 441000 frames at 44.1 kHz, its first frames the click
        let sec = src_commit(1, rate * 41 / 2, rate * 61 / 2).unwrap();
        assert!((sec - 10.0).abs() < 1e-3, "{sec}");
        // the preview plays a..b round and round
        src_preview(rate / 2, rate / 2 + 100, true);
        let mut out = vec![0f32; 2 * 256];
        process(&mut out, &vec![0f32; 512], 0.0);
        assert!(out[2 * 200] > 0.5 * 0.8 * 32767.0 * 0.9);
        src_free();
        assert_eq!(src_info(), (0, 0));
        assert_eq!(src_zero(5, 3), 5);
    }

    #[test]
    fn resampling_is_quick_enough() {
        let x: Vec<f32> = (0..48000 * 30 * 2).map(|k| ((k / 2) as f32 * 0.01).sin() * 0.5).collect();
        let t = std::time::Instant::now();
        let y = resample(&x, 48000.0, 44100.0);
        let el = t.elapsed().as_secs_f64();
        assert_eq!(y.len() / 2, 44100 * 30);
        // (a debug build is slow: this only catches a return to sines per tap)
        assert!(el < 20.0, "{el}");
    }
}
