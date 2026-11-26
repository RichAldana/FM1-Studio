//! The MIDI clock to the emulated FM-1, evened out (0.29.1).
//!
//! The real FM-1 sends 24 ticks a beat; Android hands USB MIDI over in bunches (several ticks within a millisecond,
//! then a gap), and the emulated machine runs ahead of what is heard by its audio queue (40-60 ms, in steps of the
//! audio callbacks). Played into the emulated MIDI IN as they came, the ticks reached the firmware irregular by a few
//! ms to a few tens: a firmware synced to the clock (CoralFlower in clock-in) placed its steps late or early, and
//! measured a wobbling tempo.
//!
//! Here, the transport bytes (clock, Start, Continue, Stop) are not played when they arrive but scheduled:
//!  1. each tick's time is put on the trend of the last ticks' arrival times (a least-squares line over up to 2 beats,
//!     against the tick numbers), as late as the latest arrivals so that none is played before it came: the ticks are
//!     evenly spaced, the tempo exact;
//!  2. that time, in the phone's clock (CLOCK_MONOTONIC: Android's MIDI timestamps), becomes an emulated time: the
//!     one heard at that moment (the emulated time at the head of the audio queue, smoothed), plus a fixed delay a
//!     little over the queue's length, so it is always still ahead of the emulator when it is scheduled. The machine
//!     loop plays each byte when the emulator reaches its time.
//! So the firmware hears every tick at the same distance after it came: a fixed latency (that the "Alignement des
//! deux FM-1" setting takes up) instead of a varying one. Notes and controls are not delayed.
//! Pure (no Android, no machine): engine.rs feeds it and asks what is due.

use std::collections::VecDeque;

/// ticks kept for the trend (2 beats)
const WINDOW: usize = 96;
/// ticks before a trend is trusted
const FIRST: usize = 12;
/// a gap that starts a new run (the clock stopped), ms
const GAP_MS: f64 = 300.0;
/// the period's bounds: 30 to 400 BPM, ms a tick
const P_MIN: f64 = 60_000.0 / (400.0 * 24.0);
const P_MAX: f64 = 60_000.0 / (30.0 * 24.0);
/// the most bytes waiting (a machine stopped): past it they are played at once
const MAX_PENDING: usize = 512;

#[derive(Default)]
pub struct ClockSync {
    /// (tick number, arrival ms) of the current run
    run: VecDeque<(f64, f64)>,
    n: f64,
    /// how late after the trend the latest ticks arrived (ms, a slowly falling maximum): the trend is moved by that
    late: f64,
    /// the last scheduled time (ms): never before it
    last: f64,
    last_arrival: f64,
    /// (scheduled ms, byte), in order
    pending: VecDeque<(f64, u8)>,
    /// the spacing of the last ticks: as they came, as scheduled (for the screen's diagnostic)
    arr_gaps: VecDeque<f64>,
    out_gaps: VecDeque<f64>,
}

fn std_dev(q: &VecDeque<f64>) -> f64 {
    if q.len() < 2 { return 0.0; }
    let m = q.iter().sum::<f64>() / q.len() as f64;
    (q.iter().map(|x| (x - m) * (x - m)).sum::<f64>() / q.len() as f64).sqrt()
}

impl ClockSync {
    /// the trend of the run: (ms at tick 0, ms a tick), when there are ticks enough
    fn trend(&self) -> Option<(f64, f64)> {
        let k = self.run.len();
        if k < FIRST { return None; }
        let (mut sx, mut sy) = (0.0, 0.0);
        for &(x, y) in &self.run { sx += x; sy += y; }
        let (mx, my) = (sx / k as f64, sy / k as f64);
        let (mut num, mut den) = (0.0, 0.0);
        for &(x, y) in &self.run { num += (x - mx) * (y - my); den += (x - mx) * (x - mx); }
        if den <= 0.0 { return None; }
        let p = num / den;
        if !(P_MIN..=P_MAX).contains(&p) { return None; }
        Some((my - p * mx, p))
    }

    fn schedule(&mut self, t: f64, b: u8) {
        let s = t.max(self.last + 0.01);
        self.last = s;
        self.pending.push_back((s, b));
        if self.pending.len() > MAX_PENDING { self.pending.pop_front(); }
    }

    /// A transport byte (0xF8 clock, 0xFA Start, 0xFB Continue, 0xFC Stop) that arrived at `t` ms.
    pub fn push(&mut self, b: u8, t: f64) {
        match b {
            0xf8 => {
                // a new run: the first tick, after a gap, or a tempo far from the trend
                let gap = self.run.is_empty() || t - self.last_arrival > GAP_MS;
                let off = self.trend().map_or(false, |(a, p)| (t - (a + p * self.n)).abs() > 3.0 * p);
                if gap || off { self.run.clear(); self.n = 0.0; }
                self.last_arrival = t;
                self.run.push_back((self.n, t));
                while self.run.len() > WINDOW { self.run.pop_front(); }
                let s = match self.trend() {
                    Some((a, p)) => {
                        let line = a + p * self.n;
                        // how late the ticks come after the line: the latest seen, falling 1 % a tick
                        self.late = (t - line).max(self.late * 0.998);
                        line + self.late
                    }
                    None => t,
                };
                self.n += 1.0;
                let prev = self.last;
                self.schedule(s.max(t), b);
                if prev > 0.0 && !gap {
                    self.out_gaps.push_back(self.last - prev);
                    if let Some(&(_, pa)) = self.run.iter().rev().nth(1) { self.arr_gaps.push_back(t - pa); }
                }
                while self.out_gaps.len() > WINDOW { self.out_gaps.pop_front(); }
                while self.arr_gaps.len() > WINDOW { self.arr_gaps.pop_front(); }
            }
            0xfa => {
                // Start: tick 0 of a new run comes next
                self.run.clear();
                self.n = 0.0;
                self.schedule(t, b);
            }
            _ => self.schedule(t, b),
        }
    }

    /// The bytes due by `upto` (phone ms), in order.
    pub fn due(&mut self, upto: f64, mut play: impl FnMut(u8)) {
        while let Some(&(s, b)) = self.pending.front() {
            if s > upto { break; }
            self.pending.pop_front();
            play(b);
        }
    }

    /// When the next byte is due (phone ms).
    pub fn next(&self) -> Option<f64> { self.pending.front().map(|p| p.0) }

    /// Everything waiting, at once (the machine not paced by its audio yet).
    pub fn flush(&mut self, play: impl FnMut(u8)) { self.due(f64::INFINITY, play) }

    /// [the ticks' spread as they came, as played (ms, standard deviation of their spacing), how late the trend is put]
    pub fn info(&self) -> [f32; 3] { [std_dev(&self.arr_gaps) as f32, std_dev(&self.out_gaps) as f32, self.late as f32] }
}

/// The phone's monotonic clock in ms (CLOCK_MONOTONIC, Android's MIDI timestamps' and System.nanoTime's).
pub fn now_ms() -> f64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
    ts.tv_sec as f64 * 1000.0 + ts.tv_nsec as f64 / 1e6
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 120 BPM delivered as Android does at its worst: held, then a bunch every ~20-60 ms, a little noise
    fn bunched(ticks: usize, p: f64) -> Vec<f64> {
        let mut seed = 7u32;
        let mut rnd = || { seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12345); (seed >> 16) as f64 / 65536.0 };
        let mut out = Vec::new();
        let mut release = 0.0;
        for i in 0..ticks {
            let true_t = 1000.0 + i as f64 * p;
            if true_t > release { release = true_t + 5.0 + rnd() * 45.0; }
            out.push(release + rnd() * 0.5);
        }
        out
    }

    fn spread(x: &[f64]) -> (f64, f64) {
        let g: Vec<f64> = x.windows(2).map(|w| w[1] - w[0]).collect();
        let m = g.iter().sum::<f64>() / g.len() as f64;
        (m, (g.iter().map(|v| (v - m) * (v - m)).sum::<f64>() / g.len() as f64).sqrt())
    }

    #[test]
    fn bunched_ticks_come_out_even() {
        let p = 60_000.0 / (120.0 * 24.0);
        let arr = bunched(24 * 16, p);
        let mut c = ClockSync::default();
        let mut out = Vec::new();
        for &t in &arr { c.push(0xf8, t); c.due(f64::INFINITY, |_| {}); out.push(c.last); }
        let (_, sd_in) = spread(&arr[48..]);
        let (m, sd_out) = spread(&out[48..]);
        // never before it came, and not long after
        let worst = out.iter().zip(&arr).map(|(o, a)| o - a).fold(f64::MIN, f64::max);
        assert!(out.iter().zip(&arr).all(|(o, a)| o >= a), "a tick played before it came");
        println!("spacing: as they came ±{sd_in:.2} ms, as played ±{sd_out:.2} ms (mean {m:.3}, the tempo's {p:.3}); latest {worst:.1} ms after it came");
        assert!(sd_in > 8.0);
        assert!(sd_out < 1.5, "{sd_out}");
        assert!((m - p).abs() < 0.05);
    }

    #[test]
    fn start_and_stop_keep_their_place() {
        let mut c = ClockSync::default();
        let p = 20.0;
        let mut t = 0.0;
        for _ in 0..30 { c.push(0xf8, t); t += p; }
        c.push(0xfc, t);
        c.push(0xfa, t + 500.0);
        for i in 0..30 { c.push(0xf8, t + 500.0 + i as f64 * p); }
        let mut seq = Vec::new();
        c.due(f64::INFINITY, |b| seq.push(b));
        assert_eq!(seq.iter().filter(|&&b| b == 0xf8).count(), 60);
        let stop = seq.iter().position(|&b| b == 0xfc).unwrap();
        let start = seq.iter().position(|&b| b == 0xfa).unwrap();
        assert_eq!(stop, 30);
        assert_eq!(start, 31);
    }

    #[test]
    fn a_new_tempo_is_followed() {
        let mut c = ClockSync::default();
        let mut t = 0.0;
        for _ in 0..96 { c.push(0xf8, t); t += 20.0; }
        for _ in 0..96 { c.push(0xf8, t); t += 15.0; }
        let last: Vec<f64> = c.pending.iter().rev().take(24).map(|p| p.0).collect();
        let (m, sd) = spread(&last.iter().rev().cloned().collect::<Vec<_>>());
        assert!((m - 15.0).abs() < 0.1 && sd < 0.5, "{m} {sd}");
    }
}
