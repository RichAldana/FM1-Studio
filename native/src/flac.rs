//! FLAC for the projects' sounds (Project.kt: "Sons compressés sans perte"): a small encoder of our own (16-bit, mono
//! or stereo, fixed predictors of order 0 to 4, Rice-coded residual in up to 16 partitions, stereo as left/side when
//! that is smaller), and decoding through claxon (Apache-2.0). Lossless: what comes back is what went in.
//! Streams are ordinary FLAC files: any player opens them.

const BLOCK: usize = 4096;

struct Bits {
    out: Vec<u8>,
    acc: u64,
    n: u32,
}

impl Bits {
    fn new() -> Self { Bits { out: Vec::new(), acc: 0, n: 0 } }
    fn put(&mut self, v: u64, bits: u32) {
        if bits == 0 { return; }
        let v = if bits == 64 { v } else { v & ((1u64 << bits) - 1) };
        // (in pieces of at most 32 bits: the accumulator never overflows)
        if bits > 32 { self.put(v >> 32, bits - 32); self.put(v & 0xffff_ffff, 32); return; }
        self.acc = (self.acc << bits) | v;
        self.n += bits;
        while self.n >= 8 {
            self.n -= 8;
            self.out.push((self.acc >> self.n) as u8);
        }
        self.acc &= (1u64 << self.n) - 1;
    }
    fn signed(&mut self, v: i64, bits: u32) { self.put(v as u64, bits) }
    fn unary(&mut self, q: u32) {
        let mut q = q;
        while q >= 32 { self.put(0, 32); q -= 32; }
        self.put(1, q + 1);
    }
    fn align(&mut self) { if self.n > 0 { let pad = 8 - self.n; self.put(0, pad); } }
}

fn crc8(data: &[u8]) -> u8 {
    let mut c = 0u8;
    for &b in data {
        c ^= b;
        for _ in 0..8 { c = if c & 0x80 != 0 { (c << 1) ^ 0x07 } else { c << 1 }; }
    }
    c
}

fn crc16(data: &[u8]) -> u16 {
    let mut c = 0u16;
    for &b in data {
        c ^= (b as u16) << 8;
        for _ in 0..8 { c = if c & 0x8000 != 0 { (c << 1) ^ 0x8005 } else { c << 1 }; }
    }
    c
}

/// FLAC's "UTF-8" coding of a frame number
fn utf8(v: u64, b: &mut Bits) {
    if v < 0x80 { b.put(v, 8); return; }
    let mut bytes = Vec::new();
    let mut v = v;
    let mut first_bits = 6u32;
    loop {
        bytes.push((0x80 | (v & 0x3f)) as u8);
        v >>= 6;
        first_bits -= 1;
        if v < (1 << first_bits) { break; }
    }
    let n = bytes.len() + 1;
    let lead = (0xffu16 << (8 - n)) as u8 | v as u8;
    b.put(lead as u64, 8);
    for x in bytes.iter().rev() { b.put(*x as u64, 8); }
}

/// The residual of a fixed predictor of `order` over `x`.
fn residual(x: &[i64], order: usize) -> Vec<i64> {
    (order..x.len()).map(|i| match order {
        0 => x[i],
        1 => x[i] - x[i - 1],
        2 => x[i] - 2 * x[i - 1] + x[i - 2],
        3 => x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3],
        _ => x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4],
    }).collect()
}

fn zz(r: i64) -> u64 { ((r << 1) ^ (r >> 63)) as u64 }

/// The best Rice parameter of a part and what it costs, in bits
fn rice_cost(part: &[i64]) -> (u32, u64) {
    let mut best = (0u32, u64::MAX);
    for k in 0..15u32 {
        let mut bits = 0u64;
        for &r in part { bits += (zz(r) >> k) + 1 + k as u64; }
        if bits < best.1 { best = (k, bits); }
    }
    best
}

/// The residual's partitions: (order, [(param, start, end)], bits)
fn plan_residual(res: &[i64], block: usize, order: usize) -> (u32, Vec<(u32, usize, usize)>, u64) {
    let mut best: Option<(u32, Vec<(u32, usize, usize)>, u64)> = None;
    for po in 0..=4u32 {
        let parts = 1usize << po;
        if block % parts != 0 || block / parts <= order { break; }
        let size = block / parts;
        let mut plan = Vec::new();
        let mut bits = 4u64 * parts as u64;
        let mut start = 0usize;
        for p in 0..parts {
            let n = if p == 0 { size - order } else { size };
            let end = start + n;
            let (k, c) = rice_cost(&res[start..end]);
            plan.push((k, start, end));
            bits += c;
            start = end;
        }
        if best.as_ref().map_or(true, |b| bits < b.2) { best = Some((po, plan, bits)); }
    }
    best.unwrap()
}

/// One channel's subframe: the fixed order with the smallest residual, or verbatim
fn subframe(b: &mut Bits, x: &[i64], bps: u32) {
    let n = x.len();
    if x.iter().all(|&v| v == x[0]) {
        b.put(0, 1); b.put(0b000000, 6); b.put(0, 1);
        b.signed(x[0], bps);
        return;
    }
    let mut best: Option<(usize, Vec<i64>, u32, Vec<(u32, usize, usize)>, u64)> = None;
    for order in 0..=4usize.min(n.saturating_sub(1)) {
        let res = residual(x, order);
        let (po, plan, bits) = plan_residual(&res, n, order);
        let total = bits + order as u64 * bps as u64;
        if best.as_ref().map_or(true, |b| total < b.4) { best = Some((order, res, po, plan, total)); }
    }
    let (order, res, po, plan, total) = best.unwrap();
    if total >= n as u64 * bps as u64 {
        b.put(0, 1); b.put(0b000001, 6); b.put(0, 1);
        for &v in x { b.signed(v, bps); }
        return;
    }
    b.put(0, 1); b.put(0b001000 | order as u64, 6); b.put(0, 1);
    for &v in &x[..order] { b.signed(v, bps); }
    b.put(0, 2);                     // Rice, 4-bit parameters
    b.put(po as u64, 4);
    for (k, s, e) in plan {
        b.put(k as u64, 4);
        for &r in &res[s..e] {
            let u = zz(r);
            b.unary((u >> k) as u32);
            b.put(u & ((1u64 << k) - 1), k);
        }
    }
}

/// `pcm` (interleaved by `channels`, 1 or 2) at `rate` as a FLAC file.
pub fn encode(pcm: &[i16], channels: usize, rate: u32) -> Vec<u8> {
    let ch = channels.clamp(1, 2);
    let frames = pcm.len() / ch;
    let mut out = Vec::new();
    out.extend_from_slice(b"fLaC");
    // STREAMINFO, the only (so the last) metadata block
    let mut si = Bits::new();
    si.put(1, 1); si.put(0, 7); si.put(34, 24);
    si.put(BLOCK as u64, 16); si.put(BLOCK as u64, 16);
    si.put(0, 24); si.put(0, 24);
    si.put(rate as u64, 20); si.put(ch as u64 - 1, 3); si.put(15, 5);
    si.put(frames as u64, 36);
    si.put(0, 64); si.put(0, 64);                   // MD5: not computed (allowed)
    out.extend_from_slice(&si.out);
    let mut f = 0usize;
    let mut index = 0u64;
    while f < frames {
        let n = BLOCK.min(frames - f);
        let mut l = Vec::with_capacity(n);
        let mut r = Vec::with_capacity(n);
        for i in 0..n {
            l.push(pcm[(f + i) * ch] as i64);
            if ch == 2 { r.push(pcm[(f + i) * ch + 1] as i64); }
        }
        // stereo: left and side when that is smaller (a sound near the middle: much smaller)
        let side: Vec<i64> = if ch == 2 { l.iter().zip(&r).map(|(a, b)| a - b).collect() } else { Vec::new() };
        let cost = |x: &[i64]| -> u64 { residual(x, 2).iter().map(|v| v.unsigned_abs()).sum() };
        let left_side = ch == 2 && cost(&side) < cost(&r);
        let mut b = Bits::new();
        b.put(0b11111111111110, 14); b.put(0, 1); b.put(0, 1);
        b.put(if n == BLOCK { 0b1100 } else { 0b0111 }, 4);
        b.put(0b0000, 4);                                       // the rate: STREAMINFO's
        b.put(if ch == 1 { 0b0000 } else if left_side { 0b1000 } else { 0b0001 }, 4);
        b.put(0b100, 3); b.put(0, 1);                           // 16 bits
        utf8(index, &mut b);
        if n != BLOCK { b.put(n as u64 - 1, 16); }
        let h = crc8(&b.out);
        b.put(h as u64, 8);
        subframe(&mut b, &l, 16);
        if ch == 2 { if left_side { subframe(&mut b, &side, 17) } else { subframe(&mut b, &r, 16) } }
        b.align();
        let c = crc16(&b.out);
        b.put(c as u64, 16);
        out.extend_from_slice(&b.out);
        f += n;
        index += 1;
    }
    out
}

/// A FLAC file back: (rate, channels, samples interleaved), 16 bits.
pub fn decode(bytes: &[u8]) -> Result<(u32, usize, Vec<i16>), String> {
    let mut r = claxon::FlacReader::new(std::io::Cursor::new(bytes)).map_err(|e| e.to_string())?;
    let info = r.streaminfo();
    if info.bits_per_sample != 16 { return Err(format!("{} bits", info.bits_per_sample)); }
    let ch = info.channels as usize;
    let mut out = Vec::with_capacity(info.samples.unwrap_or(0) as usize * ch);
    for s in r.samples() { out.push(s.map_err(|e| e.to_string())? as i16); }
    Ok((info.sample_rate, ch, out))
}

/// A WAV file (16-bit PCM) of `pcm`: what a project's sound becomes again.
pub fn wav(pcm: &[i16], channels: usize, rate: u32) -> Vec<u8> {
    let data = pcm.len() * 2;
    let mut w = Vec::with_capacity(44 + data);
    let ba = (channels * 2) as u16;
    w.extend_from_slice(b"RIFF"); w.extend_from_slice(&((36 + data) as u32).to_le_bytes()); w.extend_from_slice(b"WAVE");
    w.extend_from_slice(b"fmt "); w.extend_from_slice(&16u32.to_le_bytes()); w.extend_from_slice(&1u16.to_le_bytes());
    w.extend_from_slice(&(channels as u16).to_le_bytes()); w.extend_from_slice(&rate.to_le_bytes());
    w.extend_from_slice(&(rate * ba as u32).to_le_bytes()); w.extend_from_slice(&ba.to_le_bytes()); w.extend_from_slice(&16u16.to_le_bytes());
    w.extend_from_slice(b"data"); w.extend_from_slice(&(data as u32).to_le_bytes());
    for v in pcm { w.extend_from_slice(&v.to_le_bytes()); }
    w
}

#[cfg(test)]
mod tests {
    use super::*;

    fn music(frames: usize, ch: usize) -> Vec<i16> {
        let mut seed = 12345u32;
        let mut v = Vec::with_capacity(frames * ch);
        for i in 0..frames {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            let noise = ((seed >> 16) as i32 - 32768) as f32 / 32768.0;
            let t = i as f32 / 44100.0;
            let s = (t * 220.0 * 6.283).sin() * 0.5 + (t * 3300.0 * 6.283).sin() * 0.1 + noise * 0.02;
            for c in 0..ch { v.push(((s + c as f32 * 0.01 * (t * 50.0).sin()) * 30000.0) as i16); }
        }
        v
    }

    #[test]
    fn it_comes_back_the_same() {
        for (ch, frames) in [(2usize, 44100 * 3 + 123), (1, 5000), (2, 10), (2, 4096)] {
            let pcm = music(frames, ch);
            let f = encode(&pcm, ch, 44100);
            let (rate, c, back) = decode(&f).expect("decodes");
            assert_eq!((rate, c), (44100, ch));
            assert_eq!(back, pcm, "{ch} channels, {frames} frames");
            if frames > 44100 { assert!(f.len() < pcm.len() * 2 * 7 / 10, "{} of {}", f.len(), pcm.len() * 2); }
        }
        // silence and full-scale extremes
        let mut pcm = vec![0i16; 2 * 9000];
        for i in 0..pcm.len() { if i % 97 == 0 { pcm[i] = if i % 2 == 0 { i16::MAX } else { i16::MIN }; } }
        let (_, _, back) = decode(&encode(&pcm, 2, 48000)).unwrap();
        assert_eq!(back, pcm);
    }
}
