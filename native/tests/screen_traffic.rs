//! How much would it take to show the emulated FM-1's screen on the real one, over USB-MIDI?
//! Boots a firmware headless, plays with it (keys, encoders, buttons) and, 30 times a second, codes what changed on
//! its screen the way fm1-base's mirror does (RGB565 run-length with a 64-colour cache, by row segments), then
//! counts the bytes as they would travel: 7-bit packed SysEx, 3 bytes per 4-byte USB-MIDI packet.
//! FM1_TEST_FWSC=/path/to/firmware.fwsc cargo test --release --test screen_traffic -- --nocapture

use fm1mix::engine::Engine;
use std::time::{Duration, Instant};

const W: usize = 240;

fn hash(c: u16) -> usize { ((c as u32).wrapping_mul(40503) >> 10 & 63) as usize }

/// fm1-base mirror.c's pixel code
fn code(px: &[u16]) -> usize {
    let mut cache = [0u16; 64];
    let mut cur = 0u16;
    let mut n = 0usize;
    let mut i = 0;
    while i < px.len() {
        let c = px[i];
        let mut run = 1;
        while i + run < px.len() && px[i + run] == c { run += 1; }
        let mut left = run;
        if c != cur {
            if cache[hash(c)] == c { n += 1; left -= 1; } else { cache[hash(c)] = c; n += 3; left -= left.min(64); }
            cur = c;
        }
        n += (left + 127) / 128;
        i += run;
    }
    n
}

#[test]
fn screen_traffic() {
    let Ok(path) = std::env::var("FM1_TEST_FWSC") else { eprintln!("FM1_TEST_FWSC not set: skipped"); return };
    let segw: usize = std::env::var("SEGW").ok().and_then(|s| s.parse().ok()).unwrap_or(40);
    let pkg = std::fs::read(path).unwrap();
    let e = Engine::start(&pkg, None, 240, false).expect("boot");
    let mut prev = vec![0u16; W * W];
    let mut cur = vec![0u16; W * W];
    let mut out = vec![0i16; 512];
    std::thread::sleep(Duration::from_millis(2500));
    e.lcd(&mut prev);
    let full = code(&prev) + 8;
    eprintln!("whole screen coded: {full} B ({} B on the wire)", full * 8 / 7 * 4 / 3);
    let t0 = Instant::now();
    let mut shots: Vec<Vec<u16>> = Vec::new();
    let mut next = Instant::now();
    let mut step = 0u32;
    while t0.elapsed() < Duration::from_secs(20) {
        // the audio thread's pull keeps the machine going
        e.read_mixed(&mut out, 1.0, 0.0);
        if Instant::now() < next { std::thread::sleep(Duration::from_millis(2)); continue; }
        next += Duration::from_millis(33);
        step += 1;
        // the player: a chord every 0.5 s, an encoder turn every 0.2 s, now and then a page button
        let s = step % 15;
        if s == 0 { e.key(14 + (step / 15 % 12) as u8, true); e.key(18 + (step / 15 % 12) as u8, true); }
        if s == 8 { e.key(14 + (step / 15 % 12) as u8, false); e.key(18 + (step / 15 % 12) as u8, false); }
        if step % 6 == 0 { e.enc((step / 6 % 7) as u8, if step % 12 == 0 { 1 } else { -1 }); }
        if step % 90 == 45 { e.key(((step / 90) % 12 + 2) as u8, true); }
        if step % 90 == 50 { e.key(((step / 90) % 12 + 2) as u8, false); }
        e.lcd(&mut cur);
        shots.push(cur.clone());
    }
    let secs = t0.elapsed().as_secs_f64();
    let _ = e.stop();
    if let Ok(dir) = std::env::var("SHOTS") {
        for k in [30usize, 150, 270, 400, 520] {
            let b: Vec<u8> = shots[k].iter().flat_map(|c| c.to_le_bytes()).collect();
            std::fs::write(format!("{dir}/shot{k}.raw"), b).unwrap();
        }
    }
    // mixed: 10 a second, RGB332; each block of 2 rows x SEGW px that changed goes sharp when that is cheap (text, a
    // value), at half size when not (an animation); a block is sent again only when the picture there changes
    for &(th, rgb) in &[(48usize, 2u8), (64, 2), (96, 2), (64, 1), (64, 0)] {
        let q = |c: u16| match rgb { 2 => c & 0xE718, 1 => c & 0xF79E, _ => c };
        let mut last = vec![0xFFFFu16; W * W];
        let (mut total, mut sharp, mut halfn) = (0usize, 0usize, 0usize);
        let mut per_sec = vec![0usize; secs as usize + 1];
        for (k, shot) in shots.iter().enumerate().step_by(3) {
            let tgt: Vec<u16> = shot.iter().map(|&c| q(c)).collect();
            let mut bytes = 0usize;
            for y2 in 0..W / 2 { for s0 in (0..W).step_by(segw) {
                let r0 = 2 * y2 * W + s0;
                let r1 = r0 + W;
                if tgt[r0..r0 + segw] == last[r0..r0 + segw] && tgt[r1..r1 + segw] == last[r1..r1 + segw] { continue; }
                let full = code(&tgt[r0..r0 + segw]) + code(&tgt[r1..r1 + segw]) + 4;
                let half: Vec<u16> = (0..segw / 2).map(|x| tgt[r0 + 2 * x]).collect();
                let h = code(&half) + 2;
                if full <= th || full <= h { bytes += full; sharp += 1 } else { bytes += h; halfn += 1 }
            } }
            last.copy_from_slice(&tgt);
            let wire = if bytes > 0 { (bytes + 8) * 8 / 7 * 4 / 3 } else { 0 };
            total += wire;
            per_sec[(k as f64 * 0.033) as usize] += wire;
        }
        eprintln!("mixed {:<6} sharp up to {th} B a block: average {:.1} KB/s, busiest second {:.1} KB; blocks sharp {sharp}, half {halfn}",
            ["RGB565", "RGB444", "RGB332"][rgb as usize], total as f64 / secs / 1024.0, *per_sec.iter().max().unwrap() as f64 / 1024.0);
    }
    // the variants: frames a second, colours (0 as they are, 1 RGB444, 2 RGB332), half the resolution or not
    for &(every, q, half) in &[(1usize, 0u8, false), (3, 0, false), (3, 1, false), (3, 2, false), (3, 2, true), (2, 2, true), (6, 2, false)] {
        let quant = |c: u16| -> u16 { match q { 1 => c & 0xF79E, 2 => c & 0xE718, _ => c } };
        let (w, step) = if half { (120usize, 2usize) } else { (240, 1) };
        let mut sent: Vec<u16> = vec![0xFFFF; w * w];
        let (mut total, mut peak) = (0usize, 0usize);
        let mut per_sec = vec![0usize; secs as usize + 1];
        for (k, shot) in shots.iter().enumerate().step_by(every) {
            let img: Vec<u16> = (0..w * w).map(|i| quant(shot[(i / w) * step * W + (i % w) * step])).collect();
            let sw = segw.min(w);
            let mut bytes = 0usize;
            for y in 0..w {
                for s0 in (0..w).step_by(sw) {
                    let a = &img[y * w + s0..y * w + s0 + sw];
                    if a != &sent[y * w + s0..y * w + s0 + sw] { bytes += code(a) + 2; }
                }
            }
            if bytes > 0 { bytes += 8; }
            let wire = bytes * 8 / 7 * 4 / 3;
            total += wire;
            peak = peak.max(wire);
            per_sec[(k as f64 * 0.033) as usize] += wire;
            sent = img;
        }
        eprintln!("{:>2} fps, {:<9} {}: average {:>6.1} KB/s, busiest second {:>6.1} KB, busiest frame {:>6} B",
            30 / every, ["RGB565", "RGB444", "RGB332"][q as usize], if half { "120x120" } else { "240x240" },
            total as f64 / secs / 1024.0, *per_sec.iter().max().unwrap() as f64 / 1024.0, peak);
    }
}
