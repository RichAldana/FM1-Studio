//! What the loop player costs: four slots of 30 s playing (the worst: all four, ping-pong and forward), 60 s of
//! audio in blocks of 256 frames, against the time that audio lasts.
//!   cargo run --release --example loops_bench
use fm1mix::loops;
use std::time::Instant;

fn main() {
    let secs = 30usize;
    let data: Vec<f32> = (0..44100 * secs * 2).map(|k| ((k as f32) * 0.001).sin() * 0.5).collect();
    for i in 0..loops::SLOTS {
        loops::load(i, &data, 2, 44100).unwrap();
        loops::set(i, loops::Settings { trig: loops::TRIG_TOUCH, dir: if i % 2 == 0 { loops::DIR_FORWARD } else { loops::DIR_PINGPONG }, ..Default::default() });
        loops::tap(i);
    }
    let n = 256;
    let blocks = 44100 * 60 / n;
    let ext = vec![0f32; 2 * n];
    let mut out = vec![0f32; 2 * n];
    let t = Instant::now();
    let mut keep = 0f32;
    for _ in 0..blocks {
        out.iter_mut().for_each(|v| *v = 0.0);
        loops::process(&mut out, &ext, 0.0);
        keep += out[0];
    }
    let spent = t.elapsed().as_secs_f64();
    let audio = (blocks * n) as f64 / 44100.0;
    println!("4 loops: {:.3} s of work for {:.0} s of audio = {:.3} % of one core ({})", spent, audio, 100.0 * spent / audio, keep.is_finite());
    let t = Instant::now();
    let data2 = vec![0.1f32; 48000 * 30 * 2];
    let _ = loops::load(0, &data2, 2, 48000).unwrap();
    println!("loading 30 s at 48 kHz (resampled): {:.2} s", t.elapsed().as_secs_f64());
}
