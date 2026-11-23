//! Encoder turns as LOOM-8's PANEL MIDI sends them (a few detents a message, a message a UI frame) against the same
//! turn sent whole: the emulated firmware must end on the same value.
//! FM1_TEST_FWSC=/path/to/chordism.fwsc cargo test --release --test enc_bursts -- --nocapture

use fm1mix::engine::Engine;
use std::time::{Duration, Instant};

fn run(pkg: &[u8], plan: &[(u64, i32)], dump: Option<&str>) -> Vec<u16> {
    let e = Engine::start(pkg, None, 240, false).expect("boot");
    let mut out = vec![0i16; 512];
    let pump = |e: &Engine, ms: u64, out: &mut Vec<i16>| {
        let t = Instant::now();
        while t.elapsed() < Duration::from_millis(ms) {
            e.read_mixed(out, 1.0, 0.0);
            std::thread::sleep(Duration::from_millis(5));
        }
    };
    pump(&e, 2500, &mut out);
    for &(wait, clicks) in plan {
        e.enc(5, clicks); // KNOB 4 (matrix encoder 5): the home page's volume
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

fn run_midi(pkg: &[u8], plan: &[(u64, i32)], dump: Option<&str>) -> Vec<u16> {
    let e = Engine::start(pkg, None, 240, false).expect("boot");
    let json = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets/profiles/remotefm1-panel.json")).unwrap();
    e.set_midi_mode(fm1mix::engine::MidiMode::Panel, Some(&json)).unwrap();
    let mut out = vec![0i16; 512];
    let pump = |e: &Engine, ms: u64, out: &mut Vec<i16>| {
        let t = Instant::now();
        while t.elapsed() < Duration::from_millis(ms) {
            e.read_mixed(out, 1.0, 0.0);
            std::thread::sleep(Duration::from_millis(5));
        }
    };
    pump(&e, 2500, &mut out);
    for &(wait, clicks) in plan {
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
fn bursts_end_where_a_whole_turn_does() {
    let Ok(path) = std::env::var("FM1_TEST_FWSC") else { eprintln!("FM1_TEST_FWSC not set: skipped"); return };
    let pkg = std::fs::read(path).unwrap();
    let dir = std::env::var("SHOTS").ok();
    let d = |n: &str| dir.as_ref().map(|d| format!("{d}/{n}.raw"));
    // 30 detents down: whole, one by one slowly, and as PANEL MIDI sends a fast turn (2-3 a message every 16 ms)
    let whole = run(&pkg, &[(100, -30)], d("whole").as_deref());
    let slow: Vec<(u64, i32)> = (0..30).map(|_| (60, -1)).collect();
    let slow = run(&pkg, &slow, d("slow").as_deref());
    let fast: Vec<(u64, i32)> = (0..12).map(|i| (16, if i % 2 == 0 { -3 } else { -2 })).collect();
    let fast = run(&pkg, &fast, d("fast").as_deref());
    // the same fast turn as PANEL MIDI sends it, through the panel profile (CC 21 on channel 15: KNOB 4)
    let midi = run_midi(&pkg, &(0..12).map(|i| (16, if i % 2 == 0 { -3 } else { -2 })).collect::<Vec<_>>(), d("midi").as_deref());
    // the volume's value (the home page's fourth box): the same 40 % (70 - 30) every way
    let val = |l: &[u16]| -> Vec<u16> { (174..190).flat_map(|y| (184..238).map(move |x| (y, x))).map(|(y, x)| l[y * 240 + x]).collect() };
    for (name, l) in [("whole", &whole), ("fast", &fast), ("midi", &midi)] {
        assert!(val(l) == val(&slow), "{name}: not the value 30 slow detents give");
    }
    eprintln!("30 detents: the same value whole, slow, in bursts and through MIDI");
}
