//! Encoder turns as LOOM-8's PANEL MIDI sends them, through the panel profile (CC 21 on channel 15: KNOB 4, the
//! home page's volume in Chordism, 70 % at the start): a turn at a hand's pace must end 30 detents lower, 40 %,
//! whether its detents come one by one, in bunches (USB and Android hand MIDI over in packets), a few a message, or
//! fast (30 in 150 ms).
//! FM1_TEST_FWSC=/path/to/chordism.fwsc cargo test --release --test enc_bursts -- --nocapture
//! (SHOTS=dir: each run's screen as raw RGB565)

use fm1mix::engine::{Engine, MidiMode};
use std::time::{Duration, Instant};

fn pump(e: &Engine, ms: u64, out: &mut Vec<i16>) {
    let t = Instant::now();
    while t.elapsed() < Duration::from_millis(ms) {
        e.read_mixed(out, 1.0, 0.0);
        std::thread::sleep(Duration::from_millis(1));
    }
}

/// plan: (detents in the message, ms until the next one)
fn run_midi(pkg: &[u8], plan: &[(i32, u64)], dump: Option<String>) -> Vec<u16> {
    let e = Engine::start(pkg, None, 240, false).expect("boot");
    let json = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets/profiles/remotefm1-panel.json")).unwrap();
    e.set_midi_mode(MidiMode::Panel, Some(&json)).unwrap();
    let mut out = vec![0i16; 512];
    pump(&e, 2500, &mut out);
    for &(clicks, wait) in plan {
        e.midi(&[0xBE, 21, (64 + clicks) as u8]);
        pump(&e, wait, &mut out);
    }
    pump(&e, 1500, &mut out);
    let mut lcd = vec![0u16; 240 * 240];
    e.lcd(&mut lcd);
    if let Some(p) = dump {
        let b: Vec<u8> = lcd.iter().flat_map(|c| c.to_le_bytes()).collect();
        std::fs::write(p, b).unwrap();
    }
    let _ = e.stop();
    lcd
}

#[test]
fn turns_at_a_hands_pace_count_every_detent() {
    let Ok(path) = std::env::var("FM1_TEST_FWSC") else { eprintln!("FM1_TEST_FWSC not set: skipped"); return };
    let pkg = std::fs::read(path).unwrap();
    let dir = std::env::var("SHOTS").ok();
    let d = |n: &str| dir.as_ref().map(|d| format!("{d}/{n}.raw"));
    let slow: Vec<(i32, u64)> = (0..30).map(|_| (-1, 60)).collect();
    let steady: Vec<(i32, u64)> = (0..30).map(|_| (-1, 25)).collect();
    // the same 25 ms average, the messages two by two (2 ms apart, then 48)
    let bunched: Vec<(i32, u64)> = (0..30).map(|i| (-1, if i % 2 == 0 { 2 } else { 48 })).collect();
    // bunched harder: four messages at once every 80 ms (Android reading the USB less often), 20 ms a detent
    let packets: Vec<(i32, u64)> = (0..32).map(|i| (-1, if i % 4 == 3 { 80 } else { 0 })).take(30).collect();
    // 2 or 3 a message, 20 ms a detent on average
    let several: Vec<(i32, u64)> = (0..12).map(|i| if i % 2 == 0 { (-3, 60) } else { (-2, 40) }).collect();
    // a fast turn: 30 detents in 150 ms, one message every 16 ms (LOOM-8's frames)
    let fast: Vec<(i32, u64)> = (0..10).map(|_| (-3, 16)).collect();
    let runs = [("slow", slow), ("steady", steady), ("bunched", bunched), ("packets", packets), ("several", several), ("fast", fast)];
    let shots: Vec<(&str, Vec<u16>)> = runs.iter().map(|(n, p)| (*n, run_midi(&pkg, p, d(n)))).collect();
    // the volume's value (the fourth box) against the slow turn's
    let val = |l: &[u16]| -> Vec<u16> { (174..190).flat_map(|y| (184..238).map(move |x| (y, x))).map(|(y, x)| l[y * 240 + x]).collect() };
    let mut bad = Vec::new();
    for (n, l) in &shots[1..] {
        if val(l) != val(&shots[0].1) { bad.push(*n); }
    }
    assert!(bad.is_empty(), "not the value 30 slow detents give: {bad:?}");
    eprintln!("30 detents, 30 counted: slow, steady, bunched, in packets, several a message, and a fast turn");
}
