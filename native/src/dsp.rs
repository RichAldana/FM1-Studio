//! The mixer's two helpers:
//!  - [`DriftReader`]: the real FM-1's sound, captured on its own clock, read at the phone's. Instead of dropping or
//!    repeating blocks when the two clocks drift apart (clicks), it resamples continuously (cubic) at a speed steered
//!    by how full its queue is, so the queue stays near a target (the latency) and nothing is ever cut; a queue that
//!    runs dry fades out, and fades back in once it is full enough again.
//!  - [`Limiter`]: a stereo-linked limiter at -1 dBFS (instant attack, 100 ms release) with a soft clip after it, so
//!    two loud sources summed never clip hard.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

/// The limiter on the output and on the recorded mix (the settings' switch).
pub static LIMIT: AtomicBool = AtomicBool::new(true);

/// The mixer's balances (-1 left .. 1 right, 0 centre) and master (1: as is), f32 bits (the settings' Mixer page);
/// the sources' volumes come with each read (AudioIo).
pub static BAL_EMU: AtomicU32 = AtomicU32::new(0);
pub static BAL_EXT: AtomicU32 = AtomicU32::new(0);
pub static MASTER: AtomicU32 = AtomicU32::new(0x3f80_0000);

pub fn set_f(a: &AtomicU32, v: f32) {
    a.store(v.to_bits(), Ordering::Relaxed);
}
pub fn get_f(a: &AtomicU32) -> f32 {
    f32::from_bits(a.load(Ordering::Relaxed))
}

/// A stereo source's gains, left and right: its volume [g], its balance [bal] (centre: both at g; to one side, the
/// other channel down, to silence at the end: a balance, not a pan, so a stereo source keeps its own picture) and
/// [master].
pub fn lr(g: f32, bal: f32, master: f32) -> [f32; 2] {
    let b = bal.clamp(-1.0, 1.0);
    [g * master * (1.0 - b.max(0.0)), g * master * (1.0 + b.min(0.0))]
}

/// how far the reading speed may move from 1 (0.3 %: 5 cents, beyond any crystal's drift; transients only)
const MAX_DEV: f64 = 0.003;
/// frames of fade when the queue runs dry or starts again
const FADE: usize = 128;

pub struct DriftReader {
    /// frames still to read, interleaved stereo, with one frame of history before `pos`
    buf: Vec<f32>,
    pos: f64,
    /// the queue's fill, smoothed (frames)
    fill: f64,
    /// the slow part of the speed correction: the clocks' own drift once settled
    pub integ: f64,
    /// playing (else waiting for the queue to fill up to the target)
    primed: bool,
    /// the fade-in's progress (FADE: done)
    fade_in: usize,
    last: [f32; 2],
    /// the speed used for the last block (for the screen)
    pub speed: f64,
    pub underruns: u64,
    pub skips: u64,
}

impl Default for DriftReader {
    fn default() -> Self {
        DriftReader { buf: vec![0.0; 2], pos: 1.0, fill: 0.0, integ: 0.0, primed: false, fade_in: FADE, last: [0.0; 2], speed: 1.0, underruns: 0, skips: 0 }
    }
}

impl DriftReader {
    fn frames_ready(&self) -> f64 {
        (self.buf.len() / 2) as f64 - self.pos
    }

    /// Fill `out` (stereo interleaved) from `q` (the capture queue: stereo i16 at the same nominal rate), keeping
    /// about `target` frames queued. Returns the frames that came from the queue (the rest: silence or a fade).
    pub fn read(&mut self, q: &mut VecDeque<i16>, out: &mut [f32], target: usize) -> usize {
        let n = out.len() / 2;
        let target = target.max(32) as f64;
        let have = (q.len() / 2) as f64 + self.frames_ready();
        self.fill = if self.fill == 0.0 { have } else { self.fill * 0.98 + have * 0.02 };   // (~0.3 s: USB comes in bursts)

        if !self.primed {
            if have < target {
                out.iter_mut().for_each(|v| *v = 0.0);
                return 0;
            }
            // start: drop what is beyond the target (an old backlog), then fade in
            let extra = (have - target) as usize;
            let drop = extra.min(q.len() / 2);
            q.drain(..drop * 2);
            self.buf = vec![0.0; 2];
            self.pos = 1.0;
            self.fill = target;
            self.integ = 0.0;
            self.primed = true;
            self.fade_in = 0;
        } else if have > target * 4.0 + 4410.0 {
            // far too much (the app was stalled): back to the target in one go, faded in
            let drop = ((have - target) as usize).min(q.len() / 2);
            q.drain(..drop * 2);
            self.fill = target;
            self.integ = 0.0;
            self.fade_in = 0;
            self.skips += 1;
        }

        // the speed: proportional to how far the smoothed fill is from the target, plus a slow integral
        // (P: 0.2 % for a queue twice its target; I: settles the clocks' drift over ~10 s, at most 0.1 %)
        let err = (self.fill - target) / target;
        self.integ = (self.integ + err * 0.002 / 1700.0).clamp(-0.001, 0.001);
        let step = 1.0 + (err * 0.002 + self.integ).clamp(-MAX_DEV, MAX_DEV);
        self.speed = step;

        let mut got = 0;
        for f in 0..n {
            // frames floor(pos)-1 .. floor(pos)+2 needed
            while (self.pos as usize) + 2 >= self.buf.len() / 2 {
                if q.len() < 2 {
                    break;
                }
                let l = q.pop_front().unwrap() as f32;
                let r = q.pop_front().unwrap() as f32;
                self.buf.push(l);
                self.buf.push(r);
            }
            if (self.pos as usize) + 2 >= self.buf.len() / 2 {
                // dry: fade the last value out over the rest of the block, then wait to be full again
                let left = n - f;
                for k in 0..left {
                    let g = 1.0 - (k as f32 + 1.0) / (left.min(FADE) as f32).max(1.0);
                    let g = g.max(0.0);
                    out[2 * (f + k)] = self.last[0] * g;
                    out[2 * (f + k) + 1] = self.last[1] * g;
                }
                self.last = [0.0; 2];
                self.primed = false;
                self.underruns += 1;
                break;
            }
            let i = self.pos as usize;
            let t = (self.pos - i as f64) as f32;
            let mut g = 1.0f32;
            if self.fade_in < FADE {
                g = self.fade_in as f32 / FADE as f32;
                self.fade_in += 1;
            }
            for c in 0..2 {
                let y0 = self.buf[(i - 1) * 2 + c];
                let y1 = self.buf[i * 2 + c];
                let y2 = self.buf[(i + 1) * 2 + c];
                let y3 = self.buf[(i + 2) * 2 + c];
                let a = -0.5 * y0 + 1.5 * y1 - 1.5 * y2 + 0.5 * y3;
                let b = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3;
                let cc = -0.5 * y0 + 0.5 * y2;
                let v = (((a * t + b) * t + cc) * t + y1) * g;
                out[2 * f + c] = v;
                self.last[c] = v;
            }
            self.pos += step;
            got += 1;
        }
        // keep one frame of history before pos
        let keep_from = (self.pos as usize).saturating_sub(1);
        if keep_from > 0 {
            self.buf.drain(..keep_from * 2);
            self.pos -= keep_from as f64;
        }
        got
    }

    /// queued and buffered frames (for the screen)
    pub fn latency_frames(&self, q: &VecDeque<i16>) -> f64 {
        (q.len() / 2) as f64 + self.frames_ready()
    }
}

/// A stereo-linked limiter at -1 dBFS (instant attack, 100 ms release), then a soft clip over the last 10 %:
/// samples at the 16-bit scale (full scale 32768).
pub struct Limiter {
    gain: f32,
    release: f32,
}

impl Default for Limiter {
    fn default() -> Self {
        Limiter { gain: 1.0, release: 1.0 / (0.1 * 44_100.0) }
    }
}

const FULL: f32 = 32768.0;
const THRESH: f32 = 0.891 * FULL; // -1 dBFS
const KNEE: f32 = 0.9 * FULL;

pub fn soft_clip(x: f32) -> f32 {
    let a = x.abs();
    if a <= KNEE {
        return x;
    }
    let room = FULL - KNEE;
    let y = KNEE + room * ((a - KNEE) / room).tanh();
    y.copysign(x)
}

impl Limiter {
    /// in place, stereo interleaved
    pub fn process(&mut self, s: &mut [f32]) {
        for f in s.chunks_exact_mut(2) {
            let peak = f[0].abs().max(f[1].abs());
            // never above what keeps this frame at the threshold: at once down, slowly up (~100 ms)
            let want = if peak > THRESH { THRESH / peak } else { 1.0 };
            if want < self.gain {
                self.gain = want;
            } else {
                self.gain += (want - self.gain) * self.release * 4.6;
            }
            f[0] = soft_clip(f[0] * self.gain);
            f[1] = soft_clip(f[1] * self.gain);
        }
    }

    pub fn reduction_db(&self) -> f32 {
        20.0 * self.gain.log10()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn balance_keeps_the_centre_and_fades_one_side() {
        assert_eq!(lr(0.8, 0.0, 1.0), [0.8, 0.8]);
        assert_eq!(lr(1.0, 0.5, 1.0), [0.5, 1.0]);
        assert_eq!(lr(1.0, -1.0, 0.5), [0.5, 0.0]);
        assert_eq!(lr(1.0, 3.0, 1.0), [0.0, 1.0]);
    }

    /// a 44.1 kHz producer at `ppm` off the consumer's clock, delivering in bursts of `burst` frames
    fn run(ppm: f64, burst: usize, target: usize, seconds: f64) -> (DriftReader, Vec<f32>, VecDeque<i16>, f64) {
        let mut q: VecDeque<i16> = VecDeque::new();
        let mut d = DriftReader::default();
        let mut all = Vec::new();
        let block = 256;
        let mut produced = 0.0f64;
        let mut phase = 0u64;
        let blocks = (seconds * 44100.0 / block as f64) as usize;
        let (mut sum, mut cnt) = (0.0, 0);
        for b in 0..blocks {
            produced += block as f64 * (1.0 + ppm * 1e-6);
            while produced >= burst as f64 {
                for _ in 0..burst {
                    let v = ((phase as f64 * 2.0 * std::f64::consts::PI * 440.0 / 44100.0).sin() * 10000.0) as i16;
                    q.push_back(v);
                    q.push_back(v);
                    phase += 1;
                }
                produced -= burst as f64;
            }
            let mut out = vec![0f32; block * 2];
            d.read(&mut q, &mut out, target);
            if b > blocks / 2 { sum += d.speed; cnt += 1; }
            all.extend_from_slice(&out);
        }
        (d, all, q, sum / cnt as f64)
    }

    #[test]
    fn follows_a_faster_producer_without_clicks() {
        // the FM-1's clock 300 ppm fast (far beyond a real crystal), USB bursts of 8 ms
        let (d, out, q, avg) = run(300.0, 353, 1100, 60.0);
        assert_eq!(d.underruns, 0, "no dry queue");
        assert_eq!(d.skips, 0, "no jump");
        let lat = q.len() / 2;
        assert!((lat as i64 - 1100).abs() < 500, "the queue held near its target: {lat} frames");
        assert!((avg - 1.0003).abs() < 0.0001, "reading 300 ppm faster on average: {avg}");
        // no click: after the start, the 440 Hz sine never jumps more than a sine's step (+ margin)
        let max_step = 2.0 * std::f32::consts::PI * 440.0 / 44100.0 * 10000.0 * 1.3;
        let mut worst = 0f32;
        for i in (2 * 44100)..(out.len() / 2 - 1) {
            worst = worst.max((out[2 * i + 2] - out[2 * i]).abs());
        }
        assert!(worst < max_step, "largest step {worst} (a sine's: {max_step})");
    }

    #[test]
    fn follows_a_slower_producer() {
        let (d, _, q, avg) = run(-300.0, 353, 1100, 60.0);
        assert_eq!(d.underruns, 0);
        assert!((avg - 0.9997).abs() < 0.0001, "reading 300 ppm slower on average: {avg}");
        assert!(((q.len() / 2) as i64 - 1100).abs() < 500);
    }

    #[test]
    fn a_dry_queue_fades_out_then_back_in() {
        let mut d = DriftReader::default();
        let mut q: VecDeque<i16> = (0..2000).flat_map(|_| [8000i16, 8000]).collect();
        let mut out = vec![0f32; 512];
        // primes (2000 >= 1000), plays
        assert_eq!(d.read(&mut q, &mut out, 1000), 256);
        assert!(out[0] == 0.0 && out[510] > 7000.0, "faded in from 0");
        // runs dry in the middle of a block: the end fades to 0
        let mut n = 0;
        while d.underruns == 0 && n < 20 {
            d.read(&mut q, &mut out, 1000);
            n += 1;
        }
        assert_eq!(d.underruns, 1);
        assert_eq!(out[510], 0.0, "the block ends silent");
        // waits for the target, silent, then plays again
        q.extend((0..500).flat_map(|_| [8000i16, 8000]));
        assert_eq!(d.read(&mut q, &mut out, 1000), 0);
        assert!(out.iter().all(|&v| v == 0.0));
        q.extend((0..1000).flat_map(|_| [8000i16, 8000]));
        assert_eq!(d.read(&mut q, &mut out, 1000), 256);
    }

    #[test]
    fn a_backlog_is_dropped_at_the_start() {
        let mut d = DriftReader::default();
        let mut q: VecDeque<i16> = (0..44100).flat_map(|_| [1i16, 1]).collect();
        let mut out = vec![0f32; 512];
        d.read(&mut q, &mut out, 1000);
        assert!((q.len() / 2) < 1100, "a second of backlog cut to the target: {}", q.len() / 2);
    }

    #[test]
    fn limiter_keeps_peaks_under_full_scale_and_quiet_sound_untouched() {
        let mut l = Limiter::default();
        let mut quiet: Vec<f32> = (0..1000).map(|i| ((i as f32) * 0.1).sin() * 10000.0).collect();
        let copy = quiet.clone();
        l.process(&mut quiet);
        assert_eq!(quiet, copy, "under the threshold: untouched");
        // two full-scale sources summed: 2x full scale
        let mut loud: Vec<f32> = (0..20000).map(|i| ((i as f32) * 0.05).sin() * 65000.0).collect();
        l.process(&mut loud);
        let peak = loud.iter().fold(0f32, |m, v| m.max(v.abs()));
        assert!(peak <= THRESH + 1.0, "peak {peak} at most -1 dBFS");
        assert!(l.reduction_db() < -5.0);
        // back to quiet: the gain recovers within ~0.3 s
        let mut after: Vec<f32> = (0..30000).map(|i| ((i as f32) * 0.1).sin() * 10000.0).collect();
        l.process(&mut after);
        assert!(l.reduction_db() > -0.2, "recovered: {} dB", l.reduction_db());
        assert!(soft_clip(1e9) <= FULL && soft_clip(-1e9) >= -FULL && soft_clip(1000.0) == 1000.0);
    }
}
