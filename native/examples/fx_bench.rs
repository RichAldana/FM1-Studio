//! Each pad effect's cost, as a share of real time on this machine: alone (touched), then in the worst case the brief
//! asks for (the effect touched while the heaviest reverb's tail still rings).
//! cargo run --release --example fx_bench
use fm1mix::fx;
use std::time::Instant;

fn run(e: &mut fx::FxEngine, seconds: f64) -> f64 {
    // a fresh block each time (a block fed back through again and again fades to denormals: not a real case)
    let src: Vec<f32> = (0..512).map(|k| 8000.0 * (k as f32 * 0.03).sin()).collect();
    let mut b = src.clone();
    let blocks = (seconds * 44100.0 / 256.0) as usize;
    let t = Instant::now();
    for _ in 0..blocks { b.copy_from_slice(&src); e.process(&mut b); }
    t.elapsed().as_secs_f64() / seconds * 100.0
}

fn main() {
    let json = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets/fx_profiles.json")).unwrap();
    let mut e = fx::FxEngine::load(&json).unwrap();
    let ids: Vec<String> = e.list().iter().map(|p| p.id.clone()).collect();
    let tails: Vec<bool> = e.list().iter().map(|p| p.tail).collect();
    let mut alone = Vec::new();
    for (i, id) in ids.iter().enumerate() {
        fx::select(i as i32);
        fx::touch(0.6, 0.8, true);
        let c = run(&mut e, 5.0);
        fx::touch(0.6, 0.8, false);
        fx::select(-1);
        run(&mut e, 0.2);
        alone.push(c);
        println!("{id:16} {c:5.2} %");
    }
    // the heaviest effect with a tail, ringing out under each effect
    let heavy = (0..ids.len()).filter(|&i| tails[i]).max_by(|&a, &b| alone[a].total_cmp(&alone[b])).unwrap();
    println!("\nworst case: each effect touched while {}'s tail rings", ids[heavy]);
    let mut worst = 0f64;
    for (i, id) in ids.iter().enumerate() {
        if i == heavy { continue; }
        fx::select(heavy as i32);
        fx::touch(0.9, 1.0, true);
        run(&mut e, 1.0);
        fx::touch(0.9, 1.0, false);
        fx::select(i as i32);
        fx::touch(0.6, 0.8, true);
        let c = run(&mut e, 2.0);
        fx::touch(0.6, 0.8, false);
        fx::select(-1);
        run(&mut e, 0.2);
        worst = worst.max(c);
        println!("{id:16} {c:5.2} %");
    }
    println!("\nworst {worst:.2} % of real time (the brief: under 50 % of a block's time)");
}
