//! A stem for a video: the emulated firmware played in real time, its sound (WAV), its screen (30 frames a second,
//! raw RGB565) and its lights (41 f32 a frame), with chords on the bar and the volume knob turned down and up.
//! cargo run --release --example video_stem -- FIRMWARE.fwsc OUTDIR SECONDS
use fm1mix::engine::Engine;
use std::io::Write;
use std::time::{Duration, Instant};

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let pkg = std::fs::read(&a[1]).unwrap();
    let out = std::path::PathBuf::from(&a[2]);
    let secs: f64 = a[3].parse().unwrap();
    let e = Engine::start(&pkg, None, 240, false).expect("boot");
    let mut buf = vec![0i16; 512];
    let t = Instant::now();
    while t.elapsed() < Duration::from_millis(3000) {
        e.read_mixed(&mut buf, 1.0, 0.0);
        std::thread::sleep(Duration::from_millis(5));
    }
    // the chords: C, F, G, F (keys from F3 = 0), a bar each at 120 bpm (2 s), held 1.8 s
    let roots = [7u8, 0, 2, 0];
    let total = (secs * 44100.0) as usize;
    let lat = 2205usize; // what the engine keeps queued (~50 ms): events sent that much early
    let mut events: Vec<(usize, Box<dyn Fn(&Engine)>)> = Vec::new();
    let mut bar = 0usize;
    while bar * 88200 < total {
        let k = 14 + roots[bar % 4];
        events.push((bar * 88200, Box::new(move |e: &Engine| e.key(k, true))));
        events.push((bar * 88200 + 79380, Box::new(move |e: &Engine| e.key(k, false))));
        bar += 1;
    }
    // the volume (KNOB 4) down 24 detents from 40 s, up again from 44 s
    for i in 0..24 {
        events.push(((40.0 * 44100.0) as usize + i * 3000, Box::new(|e: &Engine| e.enc(5, -1))));
        events.push(((44.0 * 44100.0) as usize + i * 3000, Box::new(|e: &Engine| e.enc(5, 1))));
    }
    events.sort_by_key(|x| x.0);
    let mut wav = Vec::with_capacity(total * 4);
    let mut frames = std::fs::File::create(out.join("frames.raw")).unwrap();
    let mut leds = std::fs::File::create(out.join("leds.bin")).unwrap();
    let mut lcd = vec![0u16; 240 * 240];
    let (mut got, mut next_ev, mut next_frame) = (0usize, 0usize, 0usize);
    let start = Instant::now();
    while got < total {
        while next_ev < events.len() && events[next_ev].0 <= got + lat {
            (events[next_ev].1)(&e);
            next_ev += 1;
        }
        e.read_mixed(&mut buf, 1.0, 0.0);
        for s in &buf { wav.extend_from_slice(&s.to_le_bytes()); }
        got += 256;
        while next_frame * 1470 <= got {
            e.lcd(&mut lcd);
            let b: Vec<u8> = lcd.iter().flat_map(|c| c.to_le_bytes()).collect();
            frames.write_all(&b).unwrap();
            let l = e.leds();
            let lb: Vec<u8> = l.iter().flat_map(|v| v.to_le_bytes()).collect();
            leds.write_all(&lb).unwrap();
            next_frame += 1;
        }
        // real time: 256 frames each 5.805 ms
        let due = Duration::from_secs_f64(got as f64 / 44100.0);
        let el = start.elapsed();
        if due > el { std::thread::sleep(due - el); }
    }
    let n = wav.len() as u32;
    let mut f = std::fs::File::create(out.join("chord.wav")).unwrap();
    let mut h = Vec::new();
    h.extend_from_slice(b"RIFF"); h.extend_from_slice(&(36 + n).to_le_bytes()); h.extend_from_slice(b"WAVEfmt ");
    h.extend_from_slice(&16u32.to_le_bytes()); h.extend_from_slice(&1u16.to_le_bytes()); h.extend_from_slice(&2u16.to_le_bytes());
    h.extend_from_slice(&44100u32.to_le_bytes()); h.extend_from_slice(&(44100u32 * 4).to_le_bytes());
    h.extend_from_slice(&4u16.to_le_bytes()); h.extend_from_slice(&16u16.to_le_bytes());
    h.extend_from_slice(b"data"); h.extend_from_slice(&n.to_le_bytes());
    f.write_all(&h).unwrap(); f.write_all(&wav).unwrap();
    eprintln!("{} frames, {:.1} s, {}", next_frame, got as f64 / 44100.0, e.status());
    let _ = e.stop();
}
