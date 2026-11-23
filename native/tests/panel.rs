//! A controller's MIDI driving the emulated panel: keys, buttons, encoders, MASTER.
//! FM1_TEST_FWSC=/path/to/chordism.fwsc cargo test --release --test panel -- --nocapture

use fm1mix::engine::{Engine, MidiMode};
use std::time::{Duration, Instant};

const PANEL: &str = include_str!("../../app/src/main/assets/profiles/remotefm1-panel.json");
const REMOTE: &str = include_str!("../../app/src/main/assets/profiles/remotefm1-midi-remote-absolute.json");

/// Pull audio like the phone's audio thread does for `ms`; the loudest sample, 0..1.
fn listen(e: &Engine, ms: u64) -> f32 {
    let t0 = Instant::now();
    let mut peak = 0f32;
    while t0.elapsed() < Duration::from_millis(ms) {
        let mut out = vec![0i16; 512];
        e.read_mixed(&mut out, 1.0, 0.0);
        peak = peak.max(out.iter().map(|s| (*s as f32).abs()).fold(0.0, f32::max) / 32768.0);
        std::thread::sleep(Duration::from_micros(5805));
    }
    peak
}

fn lcd_sum(e: &Engine) -> u64 {
    let mut l = vec![0u16; 240 * 240];
    e.lcd(&mut l);
    l.iter().enumerate().map(|(i, &p)| (i as u64 % 977 + 1) * p as u64).sum()
}

#[test]
fn controller_drives_the_panel() {
    let Ok(path) = std::env::var("FM1_TEST_FWSC") else {
        eprintln!("FM1_TEST_FWSC not set: skipped");
        return;
    };
    let pkg = std::fs::read(path).unwrap();
    let e = Engine::start(&pkg, None, 240, false).expect("boot");
    listen(&e, 2500); // boot

    // 1) clock-only (the default): a note must not play the firmware; the app still counts it
    e.midi(&[0x9f, 65, 100]);
    let quiet = listen(&e, 1200);
    e.midi(&[0x8f, 65, 0]);
    listen(&e, 100);
    eprintln!("clock-only, note sent: peak {quiet:.3}");
    assert!(quiet < 0.005, "a note played in clock-only mode");
    let (n, last) = e.midi_info();
    assert_eq!(n, 2);
    assert_eq!(last, 0x8f4100);

    // 2) RemoteFM1 panel events: a key (note on channel 16) plays it
    e.set_midi_mode(MidiMode::Panel, Some(PANEL)).unwrap();
    e.midi(&[0x9f, 53 + 12, 100]);
    let loud = listen(&e, 1500);
    e.midi(&[0x8f, 53 + 12, 0]);
    listen(&e, 300);
    eprintln!("panel mode, key sent: peak {loud:.3}");
    assert!(loud > 0.01, "the key did not play");

    // 3) a button (note on channel 15) and an encoder (CC 16+e, 64+d) change the screen
    let before = lcd_sum(&e);
    for _ in 0..3 {
        e.midi(&[0x9e, 6, 127]); // EDIT down
        listen(&e, 250);
        e.midi(&[0x8e, 6, 0]); // EDIT up
        listen(&e, 250);
    }
    let after_button = lcd_sum(&e);
    for _ in 0..5 {
        e.midi(&[0xbe, 16, 65]); // SELECT +1
        listen(&e, 150);
    }
    let after_enc = lcd_sum(&e);
    eprintln!("screen checksum: {before} -> button {after_button} -> encoder {after_enc}");
    assert!(after_button != before || after_enc != after_button, "neither the button nor the encoder changed the screen");

    // 4) MASTER (14-bit CC 7/39) and a clock byte do not break anything
    e.midi(&[0xbe, 7, 100, 0xbe, 39, 0]);
    e.midi(&[0xf8, 0xf8, 0xfa]);
    listen(&e, 300);

    // 5) the standalone MIDI REMOTE map: a note on any channel plays, a CC button is pressed
    e.set_midi_mode(MidiMode::Panel, Some(REMOTE)).unwrap();
    e.midi(&[0x90, 53 + 7, 100]);
    let loud2 = listen(&e, 1500);
    e.midi(&[0x80, 53 + 7, 0]);
    e.midi(&[0xb0, 24, 127]);
    listen(&e, 200);
    e.midi(&[0xb0, 24, 0]);
    eprintln!("MIDI REMOTE map, note sent: peak {loud2:.3}");
    assert!(loud2 > 0.01);

    // 6) Thru: the note goes to the firmware's MIDI IN as it is (Chordism may ignore it); no crash
    e.set_midi_mode(MidiMode::Thru, None).unwrap();
    e.midi(&[0x90, 60, 100]);
    listen(&e, 500);
    e.midi(&[0x80, 60, 0]);

    // a profile is required for Panel mode, and a bad one is refused
    assert!(e.set_midi_mode(MidiMode::Panel, None).is_err());
    assert!(e.set_midi_mode(MidiMode::Panel, Some("{not json")).is_err());
    eprintln!("status: {}", e.status());
    e.stop();
}
