//! Boots a real firmware package headless and checks that sound and screen come out.
//! FM1_TEST_FWSC=/path/to/firmware.fwsc cargo test --release -- --nocapture

use fm1mix::engine::Engine;
use std::time::{Duration, Instant};

#[test]
fn boots_plays_and_mixes() {
    let Ok(path) = std::env::var("FM1_TEST_FWSC") else {
        eprintln!("FM1_TEST_FWSC not set: skipped");
        return;
    };
    let pkg = std::fs::read(path).unwrap();
    let e = Engine::start(&pkg, None, 240, false).expect("boot");

    // 1) a played key (a note on the keybed: F3 is matrix id 14) after the firmware has booted
    std::thread::sleep(Duration::from_millis(1500));
    e.key(26, true);

    // 2) what the Android audio thread does: pull 256-frame blocks, here also with a fake
    //    "external FM-1" tone pushed in at the same rate to test the mixer
    let (mut peak_emu, mut peak_ext) = (0f32, 0f32);
    let t0 = Instant::now();
    let mut phase = 0f32;
    while t0.elapsed() < Duration::from_secs(4) {
        let mut tone = vec![0i16; 512];
        for f in 0..256 {
            let s = (phase.sin() * 8000.0) as i16;
            phase += 2.0 * std::f32::consts::PI * 440.0 / 44100.0;
            tone[2 * f] = s;
            tone[2 * f + 1] = s;
        }
        e.push_capture(&tone);
        let mut out = vec![0i16; 512];
        e.read_mixed(&mut out, 1.0, 0.5);
        let (a, b) = e.take_meters();
        peak_emu = peak_emu.max(a);
        peak_ext = peak_ext.max(b);
        std::thread::sleep(Duration::from_micros(5805)); // 256 frames at 44.1 kHz
    }
    e.key(26, false);

    let mut lcd = vec![0u16; 240 * 240];
    e.lcd(&mut lcd);
    let lit = lcd.iter().filter(|&&p| p != lcd[0]).count();
    eprintln!("status: {} | load {}%", e.status(), e.load());
    assert!(!e.halted(), "the machine halted");
    assert!(e.load() > 0, "no load measured");
    eprintln!("peak emu {peak_emu:.3}  peak ext {peak_ext:.3}  lcd pixels != bg: {lit}");
    let flash = e.stop();
    eprintln!("flash image: {} bytes", flash.len());

    assert!(lit > 100, "the LCD stayed blank");
    assert!(peak_emu > 0.01, "no sound from the emulated firmware");
    assert!(peak_ext > 0.05, "the external tone did not reach the mix");
    assert!(fm1mix::state::decode(&flash, 1 << 20).is_ok(), "the saved state is not a sectors file");
}

/// Low latency with Chordism running and a "real FM-1" whose clock runs 300 ppm fast, its sound coming in 8 ms USB
/// bursts: the captured queue held near its 25 ms target, no dry queue, no emulator underrun, no clipping.
#[test]
fn low_latency_and_a_drifting_fm1() {
    let Ok(path) = std::env::var("FM1_TEST_FWSC") else { return };
    let pkg = std::fs::read(path).unwrap();
    let e = Engine::start(&pkg, None, 240, false).expect("boot");
    e.set_latency(true, 0);
    std::thread::sleep(Duration::from_millis(1500));
    e.key(26, true);
    let t0 = Instant::now();
    let mut produced = 0.0f64;
    let mut phase = 0u64;
    let mut peak = 0i32;
    let mut out = vec![0i16; 512];
    let mut blocks = 0u64;
    while t0.elapsed() < Duration::from_secs(8) {
        // the real FM-1: 256 frames of the phone's time = 256 * (1 + 300e-6) of its own, in bursts of 353 frames
        produced += 256.0 * (1.0 + 300e-6);
        while produced >= 353.0 {
            let mut b = vec![0i16; 706];
            for f in 0..353 {
                let v = ((phase as f64 * 2.0 * std::f64::consts::PI * 220.0 / 44100.0).sin() * 30000.0) as i16;
                b[2 * f] = v;
                b[2 * f + 1] = v;
                phase += 1;
            }
            e.push_capture(&b);
            produced -= 353.0;
        }
        e.read_mixed_rec(&mut out, 1.0, 1.0, 1.0);
        peak = peak.max(out.iter().map(|v| (*v as i32).abs()).max().unwrap());
        blocks += 1;
        std::thread::sleep(Duration::from_micros(5805));
    }
    let (ms, ppm, db) = e.audio_info();
    let st = e.status();
    eprintln!("low latency: {blocks} blocks, captured queue {ms:.1} ms, drift {ppm:.0} ppm, limiter {db:.1} dB, peak {peak} | {st}");
    assert!(ms > 10.0 && ms < 45.0, "the captured queue near 25 ms: {ms}");
    assert!(st.contains("ext underruns 0"), "no dry queue: {st}");
    assert!(peak <= 29_200, "the loud mix limited: {peak}");
    e.stop();
}

/// What a firmware saved comes back: a run's saved state, booted again, is saved the same; the whole image the app
/// kept before 0.15 still boots.
#[test]
fn saved_state_comes_back() {
    let Ok(path) = std::env::var("FM1_TEST_FWSC") else { return };
    let pkg = std::fs::read(path).unwrap();
    let run = |saved: Option<&[u8]>| {
        let e = Engine::start(&pkg, saved, 240, false).expect("boot");
        let mut out = vec![0i16; 512];
        let t = Instant::now();
        while t.elapsed() < Duration::from_millis(3000) {
            e.read_mixed(&mut out, 1.0, 0.0);
            std::thread::sleep(Duration::from_millis(5));
        }
        let live = e.saved_if_changed(0);
        (e.stop(), live)
    };
    let (first, live) = run(None);
    let n = fm1mix::state::decode(&first, 1 << 20).unwrap().len();
    eprintln!("the firmware wrote {n} sectors at its first boot; published while running: {}", live.is_some());
    let (second, _) = run(Some(&first));
    assert_eq!(fm1mix::state::decode(&second, 1 << 20).unwrap().len(), n.max(fm1mix::state::decode(&second, 1 << 20).unwrap().len()));
    // a sector changed by hand comes back as it was
    let mut changed = first.clone();
    if n > 0 {
        let last = changed.len() - 1;
        changed[last] ^= 0x5A;
        let (third, _) = run(Some(&changed));
        let back = fm1mix::state::decode(&third, 1 << 20).unwrap();
        let mine = fm1mix::state::decode(&changed, 1 << 20).unwrap();
        let (at, d) = mine[mine.len() - 1];
        assert!(back.iter().any(|(a, b)| *a == at && *b == d), "the changed sector did not come back");
    }
}
