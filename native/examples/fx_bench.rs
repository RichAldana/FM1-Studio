//! Each pad effect's cost: 10 s of sound through it (touched), as a share of real time on this machine.
//! cargo run --release --example fx_bench
use fm1mix::fx;
use std::time::Instant;
fn main() {
    let json = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets/fx_profiles.json")).unwrap();
    let mut e = fx::FxEngine::load(&json).unwrap();
    let ids: Vec<String> = e.list().iter().map(|p| p.id.clone()).collect();
    for (i, id) in ids.iter().enumerate() {
        fx::select(i as i32);
        fx::touch(0.6, 0.6, true);
        // a fresh block each time (a block fed back through again and again fades to denormals: not a real case)
        let src: Vec<f32> = (0..512).map(|k| 8000.0 * (k as f32 * 0.03).sin()).collect();
        let mut b = src.clone();
        let t = Instant::now();
        let blocks = 10 * 44100 / 256;
        for _ in 0..blocks { b.copy_from_slice(&src); e.process(&mut b); }
        let s = t.elapsed().as_secs_f64();
        println!("{id:16} {:5.2} % of real time", s / 10.0 * 100.0);
        fx::touch(0.6, 0.6, false);
    }
}
