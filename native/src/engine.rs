//! Host for the emulated FM-1: a machine thread that keeps a ring of 44.1 kHz stereo frames
//! filled, plus a mixer that sums that ring with audio captured from a real FM-1 (USB audio).
//!
//! Adapted from the host loop of fm1-live (MIT, joakimeriksson/fm1-emu): same boot sequence,
//! same pacing idea (run the machine while the ring holds less than a target).

use fm1_core::{Bus, Cpu};
use fm1_map::{Mapper, Out, Profile};
use fm1_soc::{machine, Action, Board, FlashPart, Soc};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

const RAM_BASE: u32 = 0x01c0_0000;
const RAM_SIZE: usize = 1 << 20;
/// Machine time kept ahead of the wall clock while no audio has started yet.
const LEAD_MS: f64 = 60.0;
/// Frames are stereo i16 at 44.1 kHz: samples per millisecond (both channels).
const SAMPLES_PER_MS: f64 = 88.2;
/// Emulator ring: never hold more than this (ms) of sound.
const EMU_CAP_MS: f64 = 250.0;
/// Captured (external) queue: never more than this (ms); the drift reader keeps it near its target (dsp.rs).
const EXT_CAP_MS: f64 = 2000.0;
/// The two latency settings: what the emulated FM-1 keeps queued, and what the captured one does (ms).
pub const EMU_MS_NORMAL: u32 = 50;
pub const EMU_MS_LOW: u32 = 20;
pub const EXT_MS_NORMAL: u32 = 60;
pub const EXT_MS_LOW: u32 = 25;

struct Machine {
    cpu: Cpu,
    cpu1: Option<Cpu>,
    soc: Soc,
    pending_tick: bool,
    /// the flash as the package left it: what is saved is what differs from it (state.rs)
    pristine: Vec<u8>,
}

impl Machine {
    fn new(pkg: &[u8], flash: Option<&[u8]>, mhz: u64, new_chip: bool) -> Result<Self, String> {
        let st = fm1_soc::stock_from_package(pkg, None)?;
        let mut soc = Soc::new(RAM_SIZE, st.xip.clone());
        // (before the boot: the board sets the flash chip's id from it)
        soc.set_flash_part(if new_chip { FlashPart::Uc25hq80 } else { FlashPart::P25q80 });
        soc.periph.cycle_ticks = 1;
        soc.periph.hz = mhz * 1_000_000;
        soc.strict_align = false;
        soc.lcd_timed = fm1_soc::lcd_timed_from_env();
        soc.periph.skip_idle = true;
        soc.cpu1_survives = false;
        let mut cpu = Cpu::new(st.entry);
        cpu.jit_on = true;
        cpu.cost_model = false;
        // the SPL hands cpu0 over in user mode
        cpu.user = true;
        let params = RAM_BASE + 0x7fc00;
        soc.write32(params, params + 0x40);
        cpu.regs[0] = fm1_soc::stock_boot(&mut soc, &st.flash, params, st.board);
        soc.sfc_boot(st.key, st.app_end);
        let pristine = soc.flash.mem.clone();
        if let Some(f) = flash {
            if crate::state::is_sectors(f) {
                // what the firmware wrote, over this package (its own code stays: a newer version keeps its data)
                for (at, d) in crate::state::decode(f, fm1_soc::FLASH_SIZE)? {
                    soc.restore_sector(at, d);
                }
            } else if f.len() == fm1_soc::FLASH_SIZE {
                soc.restore_flash(f); // (a whole image, as the app kept them before 0.15: the same package only)
            }
        }
        Ok(Machine { cpu, cpu1: None, soc, pending_tick: false, pristine })
    }

    fn run(&mut self, n: u32) -> Result<(), String> {
        let jit = self.cpu.jit_on;
        let Machine { cpu, cpu1, soc, pending_tick, .. } = self;
        machine::run_dual(cpu1, soc, n, RAM_SIZE, jit, |soc, k| machine::run_blocks(cpu, soc, k, pending_tick))
    }
}


/// What a controller on the phone's MIDI port does to the emulated FM-1.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MidiMode {
    /// Only clock, start, continue and stop reach the firmware (a hardware FM-1 running its own
    /// firmware, such as LOOM-8, as the clock master).
    ClockOnly = 0,
    /// Every message goes to the emulated MIDI IN jack as it is.
    Thru = 1,
    /// A profile (fm1-map) turns notes and CCs into the panel's keys, buttons, encoders and
    /// MASTER; what it does not take goes to MIDI IN if the profile says so.
    Panel = 2,
}

impl MidiMode {
    pub fn from_i32(v: i32) -> MidiMode {
        match v {
            1 => MidiMode::Thru,
            2 => MidiMode::Panel,
            _ => MidiMode::ClockOnly,
        }
    }
}

enum Cmd {
    Act(Action),
    Midi(Vec<u8>),
    Mode(MidiMode, Option<Box<Profile>>),
}

/// Splits a MIDI byte stream (as the USB port delivers it) into whole messages: running status,
/// real-time bytes in the middle of a message, SysEx skipped.
#[derive(Default)]
pub struct MidiParser {
    status: u8,
    data: [u8; 2],
    n: usize,
    sysex: bool,
}

fn data_len(status: u8) -> usize {
    match status {
        0x80..=0xbf | 0xe0..=0xef | 0xf2 => 2,
        0xc0..=0xdf | 0xf1 | 0xf3 => 1,
        _ => 0,
    }
}

impl MidiParser {
    pub fn push(&mut self, b: u8, emit: &mut dyn FnMut(&[u8])) {
        if b >= 0xf8 {
            emit(&[b]); // real time: may sit inside another message
            return;
        }
        if b >= 0x80 {
            self.n = 0;
            match b {
                0xf0 => { self.sysex = true; self.status = 0; }
                0xf7 => { self.sysex = false; self.status = 0; }
                0xf6 => { self.sysex = false; self.status = 0; emit(&[b]); }
                _ => { self.sysex = false; self.status = if data_len(b) > 0 { b } else { 0 }; }
            }
            return;
        }
        if self.sysex || self.status == 0 {
            return;
        }
        self.data[self.n] = b;
        self.n += 1;
        let need = data_len(self.status);
        if self.n == need {
            let msg = [self.status, self.data[0], self.data[1]];
            emit(&msg[..need + 1]);
            self.n = 0;
            if self.status >= 0xf0 {
                self.status = 0; // system common has no running status
            }
        }
    }
}

/// Encoder detents (from MIDI, or the screen) are queued and played one at a time, ENC_MIN_MS apart at least: the
/// emulator turns a detent in 8 ms (four contact changes, 2 ms each), and detents handed over together (a few in one
/// message: LOOM-8's PANEL MIDI sends what it read since its last look; or messages bunched by USB and Android) were
/// played back to back, the next one's contacts starting before the last had settled. Chordism then counted 32 to 38
/// for 30 detents: jumps (its knobs also move 3 steps a detent when it reads more than 2 at once). Queued, every way
/// of sending 30 detents counts 30, a fast turn too (30 in 150 ms), and none is dropped: a turn faster than 110
/// detents a second plays a little late (tests/enc_bursts.rs). (0.13 spaced them 20 ms and dropped past 40 waiting:
/// a fast turn lagged and lost travel.)
const ENC_MIN_MS: u64 = 9;

struct Router {
    mode: MidiMode,
    mapper: Option<Mapper>,
    parser: MidiParser,
    outs: Vec<Out>,
    /// detents waiting per encoder (+ clockwise), and the machine cycle the next may go at
    enc_q: [i32; 8],
    enc_due: [u64; 8],
    /// the knob speed's leftovers per encoder, in quarter detents (|x| < 4)
    enc_frac: [i32; 8],
}

/// The knobs' speed, in quarter detents per detent turned: 1 (¼×), 2 (½×), 4 (1×), 8 (2×). It scales KNOB 1-4
/// (matrix encoders 2..5), from the screen and from the FM-1's panel alike; SELECT, ALGORITHM and PRESETS too when
/// [KNOB_SPEED_ALL] is set. A slow speed keeps what is left over: two half turns make a detent.
pub static KNOB_SPEED_Q: AtomicU32 = AtomicU32::new(4);
pub static KNOB_SPEED_ALL: AtomicBool = AtomicBool::new(false);

/// `clicks` detents turned at speed `q` (quarters), with `frac` the encoder's leftover: the detents to play.
fn scale_detents(clicks: i32, q: i32, frac: &mut i32) -> i32 {
    if q == 4 {
        return clicks;
    }
    let t = *frac + clicks * q;
    let whole = t / 4;          // (toward zero: what is left keeps the turn's sign)
    *frac = t - whole * 4;
    whole
}

impl Router {
    fn new() -> Self {
        Router { mode: MidiMode::ClockOnly, mapper: None, parser: MidiParser::default(), outs: Vec::new(), enc_q: [0; 8], enc_due: [0; 8], enc_frac: [0; 8] }
    }

    fn set_mode(&mut self, m: &mut Machine, mode: MidiMode, profile: Option<Box<Profile>>) {
        // let go of what the old profile holds down
        if let Some(mp) = self.mapper.as_mut() {
            mp.release_all(&mut |o| self.outs.push(o));
        }
        self.flush(m);
        self.enc_q = [0; 8];
        self.enc_frac = [0; 8];
        self.mapper = profile.map(|p| Mapper::new(*p));
        self.mode = mode;
        self.parser = MidiParser::default();
    }

    fn flush(&mut self, m: &mut Machine) {
        for o in std::mem::take(&mut self.outs) {
            match o {
                Out::Key { id, down, vel } => {
                    if down && id >= fm1_map::KEY0 {
                        m.soc.periph.apply(&Action::KeyVel { vel: vel.clamp(1, 127) });
                    }
                    m.soc.periph.apply(&Action::Key { id, down });
                }
                Out::Enc { enc, clicks } => self.enc(enc, clicks),
                Out::Adc { ch, value } => m.soc.periph.apply(&Action::Adc { ch, value }),
                Out::Midi { bytes, len } => {
                    for &b in &bytes[..len as usize] {
                        m.soc.periph.apply(&Action::Midi { b });
                    }
                }
                _ => {} // Record, Labels, KeysMode, Learned: front-end matters
            }
        }
    }

    fn enc(&mut self, enc: u8, clicks: i32) {
        let i = enc as usize;
        if i >= 8 {
            return;
        }
        let scaled = (2..=5).contains(&i) || KNOB_SPEED_ALL.load(Ordering::Relaxed);
        let n = if scaled { scale_detents(clicks, KNOB_SPEED_Q.load(Ordering::Relaxed) as i32, &mut self.enc_frac[i]) } else { clicks };
        self.enc_q[i] = (self.enc_q[i] + n).clamp(-1000, 1000);
    }

    /// The detents due now, one per encoder (called at each turn of the machine loop).
    fn tick(&mut self, m: &mut Machine) {
        let now = m.soc.periph.cycles;
        let gap = m.soc.periph.hz / 1000 * ENC_MIN_MS;
        for i in 0..8 {
            let q = self.enc_q[i];
            if q != 0 && now >= self.enc_due[i] {
                m.soc.periph.apply(&Action::Enc { enc: i as u8, clicks: q.signum() });
                self.enc_q[i] -= q.signum();
                self.enc_due[i] = now + gap;
            }
        }
    }

    fn bytes(&mut self, m: &mut Machine, sh: &Shared, data: &[u8]) {
        let mut msgs: Vec<([u8; 3], usize)> = Vec::new();
        for &b in data {
            self.parser.push(b, &mut |msg| {
                let mut a = [0u8; 3];
                a[..msg.len()].copy_from_slice(msg);
                msgs.push((a, msg.len()));
            });
        }
        for (a, n) in msgs {
            self.message(m, sh, &a[..n]);
        }
        self.flush(m);
    }

    fn message(&mut self, m: &mut Machine, sh: &Shared, msg: &[u8]) {
        let st = msg[0];
        // transport and clock always reach the firmware (active sensing and reset do not); the pad's clock hears them
        if st == 0xf8 { crate::fx::clock_tick() }
        if st == 0xfa { crate::fx::clock_start(); crate::loops::midi_start(); }
        if (0xf8..=0xfc).contains(&st) {
            m.soc.periph.apply(&Action::Midi { b: st });
            return;
        }
        if st >= 0xf8 {
            return;
        }
        // for the screen of the app: how many messages came, and the last one
        sh.midi_count.fetch_add(1, Ordering::Relaxed);
        let last = ((st as u32) << 16) | ((*msg.get(1).unwrap_or(&0) as u32) << 8) | (*msg.get(2).unwrap_or(&0) as u32);
        sh.midi_last.store(last, Ordering::Relaxed);
        // a note-on: the loop player's note trigger hears it (it still goes on as before)
        if st & 0xf0 == 0x90 && msg.len() == 3 && msg[2] > 0 { crate::loops::note_on(msg[1]) }

        let panel = self.mode == MidiMode::Panel && m.soc.board == Board::Fm1 && self.mapper.is_some();
        match self.mode {
            MidiMode::ClockOnly => {}
            MidiMode::Panel if panel => {
                let outs = &mut self.outs;
                self.mapper.as_mut().unwrap().feed(msg, &mut |o| outs.push(o));
            }
            // Thru, or Panel on a board that has no panel to drive (SMK-37): as it comes
            _ => {
                for &b in msg {
                    m.soc.periph.apply(&Action::Midi { b });
                }
            }
        }
    }
}

/// State shared between the machine thread, the audio threads and the UI (through JNI).
pub struct Shared {
    emu: Mutex<VecDeque<i16>>,
    ext: Mutex<VecDeque<i16>>,
    lcd: Mutex<Vec<u16>>,
    leds: Mutex<[f32; 41]>,
    status: Mutex<String>,
    quit: AtomicBool,
    /// the engine thread has a performance hint session (ADPF)
    pub hint: AtomicBool,
    started: AtomicBool,
    underruns: AtomicU64,
    ext_underruns: AtomicU64,
    ext_drops: AtomicU64,
    /// peaks since the last `take_meters`, as i16 magnitudes
    peak_emu: AtomicU32,
    peak_ext: AtomicU32,
    midi_count: AtomicU64,
    midi_last: AtomicU32,
    /// share of the wall time the machine thread spends emulating, in percent
    load: AtomicU32,
    halted: AtomicBool,
    /// what the emulated FM-1 keeps queued at least, and the captured one's target (ms): the latency settings
    emu_base_ms: AtomicU32,
    ext_target_ms: AtomicU32,
    ext_reader: Mutex<crate::dsp::DriftReader>,
    limiter: Mutex<crate::dsp::Limiter>,
    /// the EQs: the emulated FM-1, the real one, the master of what plays, the master of what is recorded, the loops
    eq: Mutex<[crate::dsp::Eq3; 5]>,
    /// peaks of what plays (before the limiter) and of what is recorded, as the other peaks
    peak_out: AtomicU32,
    peak_rec: AtomicU32,
    /// the loop player's strip, as the other peaks
    peak_loop: AtomicU32,
    /// the firmware's saved state (state.rs) and its generation, published when the flash settles after a change
    saved: Mutex<Option<(u64, Vec<u8>)>>,
}

impl Shared {
    fn new() -> Self {
        Shared {
            emu: Mutex::new(VecDeque::new()),
            ext: Mutex::new(VecDeque::new()),
            lcd: Mutex::new(vec![0u16; 240 * 240]),
            leds: Mutex::new([0.0; 41]),
            status: Mutex::new(String::from("booting")),
            quit: AtomicBool::new(false),
            started: AtomicBool::new(false),
            underruns: AtomicU64::new(0),
            ext_underruns: AtomicU64::new(0),
            ext_drops: AtomicU64::new(0),
            peak_emu: AtomicU32::new(0),
            peak_ext: AtomicU32::new(0),
            midi_count: AtomicU64::new(0),
            midi_last: AtomicU32::new(0),
            load: AtomicU32::new(0),
            hint: AtomicBool::new(false),
            halted: AtomicBool::new(false),
            emu_base_ms: AtomicU32::new(EMU_MS_NORMAL),
            ext_target_ms: AtomicU32::new(EXT_MS_NORMAL),
            ext_reader: Mutex::new(crate::dsp::DriftReader::default()),
            limiter: Mutex::new(crate::dsp::Limiter::default()),
            eq: Mutex::new([crate::dsp::Eq3::default(); 5]),
            peak_out: AtomicU32::new(0),
            peak_rec: AtomicU32::new(0),
            peak_loop: AtomicU32::new(0),
            saved: Mutex::new(None),
        }
    }
}

pub struct Engine {
    shared: Arc<Shared>,
    tx: mpsc::Sender<Cmd>,
    thread: Option<JoinHandle<Vec<u8>>>,
}

fn clamp16(v: f32) -> i16 {
    v.round().clamp(-32768.0, 32767.0) as i16
}

impl Engine {
    /// Boot a firmware package (.fwsc). `flash`: what an earlier run saved (`stop`, `saved_if_changed`: state.rs).
    pub fn start(pkg: &[u8], flash: Option<&[u8]>, mhz: u64, new_chip: bool) -> Result<Engine, String> {
        let mut m = Machine::new(pkg, flash, mhz, new_chip)?;
        let shared = Arc::new(Shared::new());
        let (tx, rx) = mpsc::channel::<Cmd>();
        let sh = shared.clone();
        let thread = std::thread::Builder::new()
            .name("fm1-machine".into())
            .spawn(move || machine_loop(&mut m, &sh, rx))
            .map_err(|e| e.to_string())?;
        Ok(Engine { shared, tx, thread: Some(thread) })
    }

    /// Stop the machine and return what its firmware saved (state.rs: the sectors it wrote).
    pub fn stop(mut self) -> Vec<u8> {
        self.shared.quit.store(true, Ordering::Relaxed);
        self.thread.take().and_then(|t| t.join().ok()).unwrap_or_default()
    }

    /// What the firmware saved, when it changed since generation `since` (its new generation with it): published
    /// by the machine once the flash has been quiet for a second, so a save is never taken half written.
    pub fn saved_if_changed(&self, since: u64) -> Option<(u64, Vec<u8>)> {
        let g = self.shared.saved.lock().unwrap();
        match g.as_ref() {
            Some((gen, data)) if *gen != since => Some((*gen, data.clone())),
            _ => None,
        }
    }

    pub fn key(&self, id: u8, down: bool) {
        let _ = self.tx.send(Cmd::Act(Action::Key { id, down }));
    }
    /// A note key pressed with a velocity (1..=127), as the panel's touch gives it by the height of the touch.
    pub fn key_vel(&self, id: u8, vel: u8) {
        let _ = self.tx.send(Cmd::Act(Action::KeyVel { vel: vel.clamp(1, 127) }));
        let _ = self.tx.send(Cmd::Act(Action::Key { id, down: true }));
    }
    pub fn enc(&self, enc: u8, clicks: i32) {
        let _ = self.tx.send(Cmd::Act(Action::Enc { enc, clicks }));
    }
    /// The MASTER pot (SARADC channel 4), 0..=1023.
    pub fn pot(&self, value: u16) {
        let _ = self.tx.send(Cmd::Act(Action::Adc { ch: 4, value: value.min(1023) }));
    }
    /// Raw bytes from the phone's MIDI port (any number of messages, running status allowed).
    /// What reaches the firmware depends on the mode (see [`MidiMode`]).
    pub fn midi(&self, bytes: &[u8]) {
        let _ = self.tx.send(Cmd::Midi(bytes.to_vec()));
    }

    /// Choose what the MIDI port does. `profile_json` (fm1-map format) is needed for `Panel`.
    pub fn set_midi_mode(&self, mode: MidiMode, profile_json: Option<&str>) -> Result<(), String> {
        let profile = match (mode, profile_json) {
            (MidiMode::Panel, Some(j)) => Some(Box::new(Profile::from_json(j)?)),
            (MidiMode::Panel, None) => return Err("panel mode needs a profile".into()),
            _ => None,
        };
        let _ = self.tx.send(Cmd::Mode(mode, profile));
        Ok(())
    }

    /// (messages received, the last one as 0xSSD1D2), not counting clock and transport.
    pub fn midi_info(&self) -> (u64, u32) {
        (self.shared.midi_count.load(Ordering::Relaxed), self.shared.midi_last.load(Ordering::Relaxed))
    }

    /// Audio captured from the real FM-1 (stereo i16 interleaved, 44.1 kHz).
    pub fn push_capture(&self, samples: &[i16]) {
        let mut q = self.shared.ext.lock().unwrap();
        q.extend(samples.iter().copied());
        // (the drift reader keeps it near its target; this only bounds a queue nobody reads)
        let max = (EXT_CAP_MS * SAMPLES_PER_MS) as usize;
        if q.len() > max {
            let extra = (q.len() - max) & !1;
            q.drain(..extra);
            self.shared.ext_drops.fetch_add(1, Ordering::Relaxed);
        }
    }

    /// The latency: [low] small queues (less delay, more risk of crackles); [offset_ms] > 0 delays the real FM-1,
    /// < 0 the emulated one, to line the two up.
    pub fn set_latency(&self, low: bool, offset_ms: i32) {
        let (e, x) = if low { (EMU_MS_LOW, EXT_MS_LOW) } else { (EMU_MS_NORMAL, EXT_MS_NORMAL) };
        self.shared.emu_base_ms.store(e + (-offset_ms).max(0) as u32, Ordering::Relaxed);
        self.shared.ext_target_ms.store(x + offset_ms.max(0) as u32, Ordering::Relaxed);
    }

    /// Fill `out` (stereo i16 interleaved) with the emulated FM-1 and the captured audio, each
    /// with its gain. A source that has nothing queued contributes silence.
    pub fn read_mixed(&self, out: &mut [i16], g_emu: f32, g_ext: f32) {
        self.read_mixed_rec(out, g_emu, g_ext, g_ext)
    }

    /// As [`read_mixed`](Self::read_mixed), with the real FM-1 at `g_ext` in what plays and at `g_ext_rec` in what
    /// is recorded (sent to the FM-1 itself, the output leaves it out; a recording keeps it).
    pub fn read_mixed_rec(&self, out: &mut [i16], g_emu: f32, g_ext: f32, g_ext_rec: f32) {
        let t_start = std::time::Instant::now();
        let sh = &self.shared;
        let n = out.len();
        let mut emu = vec![0i16; n];
        let mut ext = vec![0i16; n];
        {
            let mut q = sh.emu.lock().unwrap();
            let take = q.len().min(n);
            for (i, s) in q.drain(..take).enumerate() {
                emu[i] = s;
            }
            if take < n && sh.started.load(Ordering::Relaxed) {
                sh.underruns.fetch_add(1, Ordering::Relaxed);
            }
        }
        let ext_late;
        {
            // the real FM-1, read at the phone's clock however its own drifts (dsp.rs): no cut, no repeat
            let mut extf = vec![0f32; n & !1];
            let target = (sh.ext_target_ms.load(Ordering::Relaxed) as f64 * SAMPLES_PER_MS / 2.0) as usize;
            let mut q = sh.ext.lock().unwrap();
            let mut r = sh.ext_reader.lock().unwrap();
            let was = r.underruns;
            r.read(&mut q, &mut extf, target);
            ext_late = r.latency_frames(&q);
            if r.underruns != was {
                sh.ext_underruns.fetch_add(1, Ordering::Relaxed);
            }
            for i in 0..extf.len() {
                ext[i] = clamp16(extf[i]);
            }
        }
        // the mixer: each source through its EQ, at its volume and balance; the sum at the master, through its EQ
        use crate::dsp::{eq_db, get_f, lr, BAL_EMU, BAL_EXT, EQ_EMU, EQ_EXT, EQ_LOOP, EQ_MASTER, MASTER};
        let (be, bx, gm) = (get_f(&BAL_EMU), get_f(&BAL_EXT), get_f(&MASTER));
        let ge = lr(g_emu, be, gm);
        let gx = lr(g_ext, bx, gm);
        let gxr = lr(g_ext_rec, bx, gm);
        let mut ef: Vec<f32> = emu.iter().map(|&v| v as f32).collect();
        let mut xf: Vec<f32> = ext.iter().map(|&v| v as f32).collect();
        let mut eq = sh.eq.lock().unwrap();
        let m_db = eq_db(EQ_MASTER);
        eq[0].set(eq_db(EQ_EMU));
        eq[1].set(eq_db(EQ_EXT));
        eq[2].set(m_db);
        eq[3].set(m_db);
        // the loop player (loops.rs): a part per slot (each its track's effect), and the passage screen's preview
        let n2 = n & !1;
        let mut parts = vec![0f32; crate::loops::PARTS * n2];
        crate::loops::process_split(&mut parts, n2, &xf, ext_late);
        eq[0].process(&mut ef);
        eq[1].process(&mut xf);
        // the tracks (fx.rs), each through its effect: the emulated FM-1 (1), the real one (2: only when it plays from
        // the phone; played on the FM-1, its sound does not pass here and the recording keeps it dry), the loops
        // (3-6); then the whole mix (0, "TOUT")
        let phone = gx == gxr;
        let (mut pe, mut px, mut pl) = (0u32, 0u32, 0u32);
        let mut eb = vec![0f32; n];
        let mut xb = vec![0f32; n];
        for i in 0..n {
            eb[i] = ef[i] * ge[i & 1];
            xb[i] = xf[i] * gx[i & 1];
            pe = pe.max(eb[i].abs() as u32);
            px = px.max(xb[i].abs() as u32);
        }
        let mut fxb = crate::fx::ENGINE.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(bank) = fxb.as_mut() {
            bank.process(1, &mut eb);
            if phone { bank.process(2, &mut xb); }
            for k in 0..crate::loops::SLOTS { bank.process(3 + k, &mut parts[k * n2..(k + 1) * n2]); }
        }
        // the loops' strip: their sum through its EQ, at its volume and the master's
        let mut lp = vec![0f32; n2];
        for p in 0..crate::loops::PARTS {
            for k in 0..n2 { lp[k] += parts[p * n2 + k]; }
        }
        eq[4].set(eq_db(EQ_LOOP));
        eq[4].process(&mut lp);
        let lg = f32::from_bits(crate::loops::GAIN.load(Ordering::Relaxed));
        let gl = lr(lg, 0.0, gm);
        let mut mix = vec![0f32; n];
        for i in 0..n {
            let l = if i < n2 { lp[i] * gl[i & 1] } else { 0.0 };
            pl = pl.max(l.abs() as u32);
            mix[i] = eb[i] + xb[i] + l;
        }
        if let Some(bank) = fxb.as_mut() { bank.process(0, &mut mix); }
        drop(fxb);
        let mut rec = vec![0f32; n];
        for i in 0..n {
            // what is recorded: what plays, and the real FM-1 when it plays on the FM-1 itself (not in `mix` then)
            rec[i] = mix[i] + xf[i] * (gxr[i & 1] - gx[i & 1]);
        }
        eq[2].process(&mut mix);
        eq[3].process(&mut rec);
        drop(eq);
        sh.peak_out.fetch_max(mix.iter().fold(0f32, |m, v| m.max(v.abs())) as u32, Ordering::Relaxed);
        sh.peak_rec.fetch_max(rec.iter().fold(0f32, |m, v| m.max(v.abs())) as u32, Ordering::Relaxed);
        if crate::dsp::LIMIT.load(Ordering::Relaxed) {
            sh.limiter.lock().unwrap().process(&mut mix);
        }
        for i in 0..n {
            out[i] = clamp16(mix[i]);
        }
        sh.peak_emu.fetch_max(pe, Ordering::Relaxed);
        sh.peak_ext.fetch_max(px, Ordering::Relaxed);
        sh.peak_loop.fetch_max(pl, Ordering::Relaxed);
        crate::recorder::feed_mix(&emu, &ext, rec);
        // the audio thread's load: this block's work against its length (the peak, falling 2 % a block)
        let spent = t_start.elapsed().as_secs_f64();
        let pct = (spent / ((n / 2).max(1) as f64 / 44100.0) * 100.0) as f32;
        let old = f32::from_bits(crate::fx::AUDIO_LOAD.load(Ordering::Relaxed));
        crate::fx::AUDIO_LOAD.store(pct.max(old * 0.98).to_bits(), Ordering::Relaxed);
    }

    pub fn lcd(&self, out: &mut [u16]) {
        let l = self.shared.lcd.lock().unwrap();
        let n = out.len().min(l.len());
        out[..n].copy_from_slice(&l[..n]);
    }
    pub fn leds(&self) -> [f32; 41] {
        *self.shared.leds.lock().unwrap()
    }
    /// (the captured FM-1's queue in ms, its clock against the phone's in ppm, the limiter's reduction in dB)
    pub fn audio_info(&self) -> (f64, f64, f32) {
        let q = self.shared.ext.lock().unwrap();
        let r = self.shared.ext_reader.lock().unwrap();
        let ms = r.latency_frames(&q) / 44.1;
        let ppm = (r.speed - 1.0) * 1e6;
        drop(r);
        drop(q);
        (ms, ppm, self.shared.limiter.lock().unwrap().reduction_db())
    }

    pub fn status(&self) -> String {
        let s = self.shared.status.lock().unwrap().clone();
        format!(
            "{s} | out underruns {} | ext underruns {} drops {}",
            self.shared.underruns.load(Ordering::Relaxed),
            self.shared.ext_underruns.load(Ordering::Relaxed),
            self.shared.ext_drops.load(Ordering::Relaxed)
        )
    }
    /// Share of the time spent emulating, in percent (over 100: the phone cannot keep up).
    pub fn load(&self) -> u32 {
        self.shared.load.load(Ordering::Relaxed)
    }
    /// The machine stopped on an error (see `status`).
    pub fn halted(&self) -> bool {
        self.shared.halted.load(Ordering::Relaxed)
    }
    /// (emulator peak, external peak) in 0..1 since the last call.
    pub fn take_meters(&self) -> (f32, f32) {
        let m = self.take_meters4();
        (m[0].min(1.0), m[1].min(1.0))
    }

    /// As take_meters4, and the loop player's strip fifth.
    pub fn take_meters5(&self) -> [f32; 5] {
        let m = self.take_meters4();
        [m[0], m[1], m[2], m[3], self.shared.peak_loop.swap(0, Ordering::Relaxed) as f32 / 32768.0]
    }

    /// The peaks since the last call, 1 = full scale and above it when it would clip: the emulated FM-1, the real
    /// one (each at its volume), what plays (before the limiter), what is recorded.
    pub fn take_meters4(&self) -> [f32; 4] {
        let sh = &self.shared;
        [sh.peak_emu.swap(0, Ordering::Relaxed), sh.peak_ext.swap(0, Ordering::Relaxed),
         sh.peak_out.swap(0, Ordering::Relaxed), sh.peak_rec.swap(0, Ordering::Relaxed)]
            .map(|p| p as f32 / 32768.0)
    }
}

fn machine_loop(m: &mut Machine, sh: &Shared, rx: mpsc::Receiver<Cmd>) -> Vec<u8> {
    let mut router = Router::new();
    let start = Instant::now();
    let mut offset_ms = 0.0f64;
    let mut extra_ms = 0.0f64;      // added after underruns, up to 40 ms
    let mut underruns_seen = 0u64;
    let mut last_ui = Instant::now();
    let mut last_stat = Instant::now();
    let (mut stat_emu, mut stat_wall) = (0u64, Instant::now());
    // the flash: looked at twice a second; a change is published once it has stayed the same for a second
    let (mut last_look, mut looked, mut published) = (Instant::now(), m.soc.flash.mem.clone(), m.soc.flash.mem.clone());
    let (mut moved_at, mut gen) = (Instant::now(), 0u64);
    let mut busy = Duration::ZERO;
    // the performance hint (ADPF): how long each window's worth of audio took to make
    let mut hint = crate::hint::Hint::for_this_thread();
    sh.hint.store(hint.is_some(), Ordering::Relaxed);
    let (mut hint_busy, mut hint_wall, mut hint_cycles) = (Duration::ZERO, Instant::now(), m.soc.periph.cycles);
    while !sh.quit.load(Ordering::Relaxed) {
        while let Ok(c) = rx.try_recv() {
            match c {
                // (the screen's encoders too: a drag sends several detents at once)
                Cmd::Act(Action::Enc { enc, clicks }) => router.enc(enc, clicks),
                Cmd::Act(a) => m.soc.periph.apply(&a),
                Cmd::Midi(b) => router.bytes(m, sh, &b),
                Cmd::Mode(mode, p) => router.set_mode(m, mode, p),
            }
        }
        router.tick(m);
        let real = start.elapsed().as_secs_f64() * 1000.0 - offset_ms;
        let emu_ms = m.soc.periph.cycles as f64 * 1000.0 / m.soc.periph.hz as f64;
        if real - emu_ms > 250.0 {
            offset_ms += real - emu_ms - LEAD_MS; // too far behind: resync, do not race
        }
        let u = sh.underruns.load(Ordering::Relaxed);
        if u > underruns_seen {
            extra_ms = (extra_ms + 4.0).min(40.0); // an underrun: queue a little more
        }
        underruns_seen = u;
        let target_ms = sh.emu_base_ms.load(Ordering::Relaxed) as f64 + extra_ms;
        let buffered = sh.emu.lock().unwrap().len() as f64 / SAMPLES_PER_MS;
        let behind = if m.soc.periph.audio_halves > 0 { buffered < target_ms } else { emu_ms < real + LEAD_MS };
        if behind {
            let t = Instant::now();
            // a panic in the emulator must not take the app down: it stops the machine, with its reason
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| m.run(20_000)));
            let took = t.elapsed();
            busy += took;
            hint_busy += took;
            let err = match r {
                Ok(Ok(())) => None,
                Ok(Err(e)) => Some(e),
                Err(p) => Some(p.downcast_ref::<&str>().map(|s| s.to_string())
                    .or_else(|| p.downcast_ref::<String>().cloned()).unwrap_or_else(|| "panic".into())),
            };
            if let Some(e) = err {
                if let Ok(mut st) = sh.status.lock() {
                    *st = format!("halted: {e}");
                }
                sh.halted.store(true, Ordering::Relaxed);
                break;
            }
        } else {
            std::thread::sleep(Duration::from_millis(1));
        }
        if !m.soc.periph.audio.is_empty() {
            let mut q = sh.emu.lock().unwrap();
            q.extend(m.soc.periph.audio.drain(..));
            sh.started.store(true, Ordering::Relaxed);
            let cap = (EMU_CAP_MS * SAMPLES_PER_MS) as usize;
            if q.len() > cap {
                let extra = (q.len() - cap) & !1;
                q.drain(..extra);
            }
        }
        if let Some(h) = hint.as_mut() {
            if hint_wall.elapsed() >= crate::hint::WINDOW {
                let made = (m.soc.periph.cycles - hint_cycles) as f64 / m.soc.periph.hz as f64;
                h.report(hint_busy, Duration::from_secs_f64(made));
                hint_busy = Duration::ZERO;
                hint_wall = Instant::now();
                hint_cycles = m.soc.periph.cycles;
            }
        }
        if last_ui.elapsed() >= Duration::from_millis(16) {
            sh.lcd.lock().unwrap().copy_from_slice(&m.soc.lcd.fb);
            *sh.leds.lock().unwrap() = m.soc.periph.led_shown();
            last_ui = Instant::now();
        }
        if last_look.elapsed() >= Duration::from_millis(500) {
            last_look = Instant::now();
            if m.soc.flash.mem != looked {
                looked.copy_from_slice(&m.soc.flash.mem);
                moved_at = Instant::now();
            } else if looked != published && moved_at.elapsed() >= Duration::from_millis(1000) && !m.soc.flash.busy() {
                published.copy_from_slice(&looked);
                gen += 1;
                *sh.saved.lock().unwrap() = Some((gen, crate::state::encode(&m.pristine, &published)));
            }
        }
        if last_stat.elapsed() >= Duration::from_millis(500) {
            let e = m.soc.periph.ms();
            let wall = stat_wall.elapsed();
            let speed = (e - stat_emu) as f64 / wall.as_secs_f64() / 1000.0;
            sh.load.store((busy.as_secs_f64() / wall.as_secs_f64() * 100.0).round() as u32, Ordering::Relaxed);
            busy = Duration::ZERO;
            *sh.status.lock().unwrap() =
                format!("{:.2}x real time | {} MHz | queued {:.0} ms{}", speed, m.soc.periph.hz / 1_000_000, buffered,
                    if hint.is_some() { " | ADPF" } else { "" });
            stat_emu = e;
            stat_wall = Instant::now();
            last_stat = Instant::now();
        }
    }
    crate::state::encode(&m.pristine, &m.soc.flash.mem)
}

#[cfg(test)]
mod tests {
    use super::MidiParser;

    #[test]
    fn knob_speed_keeps_what_is_left() {
        use super::scale_detents;
        let turn = |q: i32, steps: &[i32]| { let mut f = 0; steps.iter().map(|&c| scale_detents(c, q, &mut f)).sum::<i32>() };
        assert_eq!(turn(4, &[1, 1, 3, -2]), 3);           // 1x: as turned
        assert_eq!(turn(2, &[1; 10]), 5);                 // 1/2x: one detent every two
        assert_eq!(turn(1, &[1; 10]), 2);                 // 1/4x: every four
        assert_eq!(turn(8, &[1, 2, -1]), 4);              // 2x: twice
        assert_eq!(turn(2, &[-1; 6]), -3);                // counter-clockwise alike
        assert_eq!(turn(2, &[1, -1, 1, -1, 1]), 0);       // back and forth on the spot: nothing
        let mut f = 0;
        assert_eq!(scale_detents(1, 2, &mut f), 0);
        assert_eq!(scale_detents(1, 2, &mut f), 1);       // the second half makes it
    }

    fn run(bytes: &[u8]) -> Vec<Vec<u8>> {
        let mut p = MidiParser::default();
        let mut out = Vec::new();
        for &b in bytes {
            p.push(b, &mut |m| out.push(m.to_vec()));
        }
        out
    }

    #[test]
    fn splits_messages_and_keeps_running_status() {
        assert_eq!(run(&[0x9f, 53, 100, 54, 90, 0x8f, 53, 0]), vec![vec![0x9f, 53, 100], vec![0x9f, 54, 90], vec![0x8f, 53, 0]]);
    }

    #[test]
    fn realtime_inside_a_message_and_sysex_skipped() {
        assert_eq!(run(&[0xb0, 7, 0xf8, 100]), vec![vec![0xf8], vec![0xb0, 7, 100]]);
        assert_eq!(run(&[0xf0, 0x7d, 1, 2, 0xf7, 0xc0, 5]), vec![vec![0xc0, 5]]);
    }

    #[test]
    fn data_without_status_is_dropped() {
        assert!(run(&[1, 2, 3]).is_empty());
    }
}
