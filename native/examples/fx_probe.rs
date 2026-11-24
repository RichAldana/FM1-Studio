//! One effect, touched at a point, for a while: how much it changes the sound, second by second.
//! cargo run --release --example fx_probe -- mi.texture 0.5 0.5 3
use fm1mix::fx;
fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (id, x, y, secs) = (a[1].clone(), a[2].parse::<f32>().unwrap(), a[3].parse::<f32>().unwrap(), a[4].parse::<usize>().unwrap());
    let json = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets/fx_profiles.json")).unwrap();
    let mut e = fx::FxEngine::load(&json).unwrap();
    let i = e.list().iter().position(|p| p.id == id).expect("no such effect");
    fx::select(i as i32);
    // a second untouched first (what it hears before the finger)
    for b in 0..43 {
        let mut buf: Vec<f32> = (0..2048).map(|k| 8000.0 * (2.0 * std::f32::consts::PI * 220.0 * ((b * 1024 + k / 2) as f32) / 44100.0).sin()).collect();
        e.process(&mut buf);
    }
    fx::touch(x, y, true);
    for s in 0..secs {
        let (mut ch, mut pk) = (0f32, 0f32);
        for b in 0..(44100 / 1024) {
            let mut buf: Vec<f32> = (0..2048).map(|k| 8000.0 * (2.0 * std::f32::consts::PI * 220.0 * ((s * 44100 + b * 1024 + k / 2) as f32) / 44100.0).sin()).collect();
            let dry = buf.clone();
            e.process(&mut buf);
            for (o, d) in buf.iter().zip(&dry) { ch = ch.max((o - d).abs()); pk = pk.max(o.abs()); }
        }
        println!("{id} {x},{y} second {s}: change {ch:.0}, peak {pk:.0}");
    }
}
