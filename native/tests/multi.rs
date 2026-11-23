//! Several emulated FM-1s in one process at once: each one's load (share of a core) and whether it keeps up.
//! FM1_TEST_FWSC=/path/to/firmware.fwsc FM1_N=2 FM1_MHZ=240 cargo test --release --test multi -- --nocapture

use fm1mix::engine::Engine;
use std::sync::Arc;
use std::time::{Duration, Instant};

#[test]
fn several_at_once() {
    let Ok(path) = std::env::var("FM1_TEST_FWSC") else { eprintln!("FM1_TEST_FWSC not set: skipped"); return };
    let n: usize = std::env::var("FM1_N").ok().and_then(|v| v.parse().ok()).unwrap_or(2);
    let mhz: u64 = std::env::var("FM1_MHZ").ok().and_then(|v| v.parse().ok()).unwrap_or(240);
    let pkg = std::fs::read(path).unwrap();
    let engines: Vec<Arc<Engine>> = (0..n).map(|_| Arc::new(Engine::start(&pkg, None, mhz, false).expect("boot"))).collect();
    // what the phone's audio thread does: pull 256-frame blocks at 44.1 kHz from each, summed
    let t0 = Instant::now();
    let mut out = vec![0i16; 512];
    let mut next = Instant::now();
    let mut played = 0u64;
    while t0.elapsed() < Duration::from_secs(10) {
        if t0.elapsed() > Duration::from_millis(2000) && played == 0 {
            for e in &engines { e.key(26, true); }   // a note on each
            played = 1;
        }
        for e in &engines { e.read_mixed(&mut out, 1.0, 0.0); }
        next += Duration::from_micros(5805);
        let now = Instant::now();
        if next > now { std::thread::sleep(next - now); }
    }
    for (i, e) in engines.iter().enumerate() {
        eprintln!("instance {}: load {}% | {}", i + 1, e.load(), e.status());
    }
    for e in engines { if let Ok(e) = Arc::try_unwrap(e) { let _ = e.stop(); } }
}
