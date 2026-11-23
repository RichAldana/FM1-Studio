//! A firmware's own save survives the app: turn KNOB 4, press SAVE (twice: Chordism asks where), stop, boot again
//! with what was saved, and look. cargo run --release --example save_check -- FW.fwsc OUTDIR
use fm1mix::engine::Engine;
use std::time::{Duration, Instant};

fn pump(e: &Engine, ms: u64) {
    let mut out = vec![0i16; 512];
    let t = Instant::now();
    while t.elapsed() < Duration::from_millis(ms) {
        e.read_mixed(&mut out, 1.0, 0.0);
        std::thread::sleep(Duration::from_millis(5));
    }
}
fn shot(e: &Engine, p: &str) {
    let mut l = vec![0u16; 240 * 240];
    e.lcd(&mut l);
    std::fs::write(p, l.iter().flat_map(|c| c.to_le_bytes()).collect::<Vec<u8>>()).unwrap();
}
fn tap(e: &Engine, id: u8) { e.key(id, true); pump(e, 120); e.key(id, false); pump(e, 400); }

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let pkg = std::fs::read(&a[1]).unwrap();
    let d = &a[2];
    let e = Engine::start(&pkg, None, 240, false).unwrap();
    pump(&e, 3000);
    for _ in 0..12 { e.enc(5, -1); pump(&e, 60); }
    pump(&e, 500);
    shot(&e, &format!("{d}/s1_turned.raw"));
    tap(&e, 9); // SAVE
    shot(&e, &format!("{d}/s2_save.raw"));
    tap(&e, 9);
    pump(&e, 2500);
    shot(&e, &format!("{d}/s3_saved.raw"));
    let saved = e.stop();
    eprintln!("saved: {} bytes", saved.len());
    let e = Engine::start(&pkg, Some(&saved), 240, false).unwrap();
    pump(&e, 3500);
    shot(&e, &format!("{d}/s4_again.raw"));
    let _ = e.stop();
    let e = Engine::start(&pkg, None, 240, false).unwrap();
    pump(&e, 3500);
    shot(&e, &format!("{d}/s5_blank.raw"));
    let _ = e.stop();
}
