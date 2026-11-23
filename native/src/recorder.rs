//! Recording to WAV: the mix as heard (each source at its fader, summed) or the two sources apart (the emulated FM-1
//! and the real one, each as it comes, before its fader).
//!
//! Quality: 16-bit or 24-bit PCM, or 32-bit float; 44.1 kHz (the sources' own rate) or 48 kHz (resampled, for a
//! video or a DAW project at 48 kHz); stereo or mono. The sources are 16-bit at 44.1 kHz: 24-bit and float add no
//! detail to them, but they keep the summed mix and the resampled samples exact, with room above full scale (float).
//!
//! The audio thread (read_mixed) hands each block over as it comes, without waiting (a bounded queue: a block that
//! finds it full is counted as dropped); a writer thread mixes, resamples, encodes and writes. The WAV header is
//! written again with the right sizes when the recording stops. The files are Android's (MediaStore, opened "rw").

use std::fs::File;
use std::io::{BufWriter, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{sync_channel, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

/// The sources' rate.
pub const SRC_RATE: u32 = 44_100;

/// What a file is written as.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Quality {
    /// 16 or 24 (PCM), 32 (float)
    pub bits: u16,
    /// 44100 or 48000
    pub rate: u32,
    /// 1 or 2
    pub channels: u16,
}

impl Quality {
    pub const CD: Quality = Quality { bits: 16, rate: 44_100, channels: 2 };

    pub fn check(&self) -> Result<(), String> {
        if !matches!(self.bits, 16 | 24 | 32) {
            return Err(format!("{} bits: 16, 24 or 32", self.bits));
        }
        if !matches!(self.rate, 44_100 | 48_000) {
            return Err(format!("{} Hz: 44100 or 48000", self.rate));
        }
        if !matches!(self.channels, 1 | 2) {
            return Err(format!("{} channels: 1 or 2", self.channels));
        }
        Ok(())
    }
    fn float(&self) -> bool {
        self.bits == 32
    }
    fn frame_bytes(&self) -> u32 {
        self.channels as u32 * self.bits as u32 / 8
    }
}

/// The header of a WAV file of `frames` frames (`data_bytes` of samples) in quality `q`: 44 bytes for PCM; 58 for
/// float (its "fmt " chunk with an empty extension, and the "fact" chunk float files carry).
pub fn wav_header(q: &Quality, data_bytes: u32, frames: u32) -> Vec<u8> {
    let mut h = Vec::with_capacity(58);
    let fmt_len: u32 = if q.float() { 18 } else { 16 };
    let extra: u32 = if q.float() { 12 } else { 0 }; // the fact chunk
    h.extend(b"RIFF");
    h.extend((4 + 8 + fmt_len + extra + 8).saturating_add(data_bytes).to_le_bytes());
    h.extend(b"WAVEfmt ");
    h.extend(fmt_len.to_le_bytes());
    h.extend((if q.float() { 3u16 } else { 1u16 }).to_le_bytes()); // IEEE float / PCM
    h.extend(q.channels.to_le_bytes());
    h.extend(q.rate.to_le_bytes());
    h.extend((q.rate * q.frame_bytes()).to_le_bytes());
    h.extend((q.frame_bytes() as u16).to_le_bytes());
    h.extend(q.bits.to_le_bytes());
    if q.float() {
        h.extend(0u16.to_le_bytes()); // cbSize
        h.extend(b"fact");
        h.extend(4u32.to_le_bytes());
        h.extend(frames.to_le_bytes());
    }
    h.extend(b"data");
    h.extend(data_bytes.to_le_bytes());
    h
}

/// A WAV file being written: a provisional header first, the real one on [`Wav::finish`]. Samples come as f32 at
/// the 16-bit scale (full scale = 32768), interleaved by the file's channels.
pub struct Wav {
    f: BufWriter<File>,
    q: Quality,
    data_bytes: u64,
    bytes: Vec<u8>,
}

/// What one WAV file can hold (its sizes are 32-bit).
const MAX_DATA: u64 = u32::MAX as u64 - 64;

impl Wav {
    pub fn new(f: File, q: Quality) -> std::io::Result<Wav> {
        let mut f = BufWriter::with_capacity(1 << 16, f);
        f.write_all(&wav_header(&q, 0, 0))?;
        Ok(Wav { f, q, data_bytes: 0, bytes: Vec::new() })
    }

    pub fn write(&mut self, s: &[f32]) -> std::io::Result<()> {
        let fb = self.q.frame_bytes() as u64;
        let room = ((MAX_DATA - self.data_bytes) / fb) as usize * self.q.channels as usize;
        let s = &s[..s.len().min(room)];
        self.bytes.clear();
        match self.q.bits {
            16 => {
                for &v in s {
                    self.bytes.extend((v.round().clamp(-32768.0, 32767.0) as i16).to_le_bytes());
                }
            }
            24 => {
                for &v in s {
                    let x = (v * 256.0).round().clamp(-8_388_608.0, 8_388_607.0) as i32;
                    self.bytes.extend(&x.to_le_bytes()[..3]);
                }
            }
            _ => {
                for &v in s {
                    self.bytes.extend((v / 32768.0).to_le_bytes());
                }
            }
        }
        self.f.write_all(&self.bytes)?;
        self.data_bytes += self.bytes.len() as u64;
        Ok(())
    }

    /// Write the real header and flush to the disk. Returns the frames written.
    pub fn finish(self) -> std::io::Result<u64> {
        let frames = self.data_bytes / self.q.frame_bytes() as u64;
        let q = self.q;
        let mut f = self.f.into_inner().map_err(|e| e.into_error())?;
        f.seek(SeekFrom::Start(0))?;
        f.write_all(&wav_header(&q, self.data_bytes as u32, frames as u32))?;
        f.sync_all()?;
        Ok(frames)
    }
}

/// A streaming resampler (4-point cubic, Catmull-Rom), for 44.1 kHz to 48 kHz: smooth enough upwards, where nothing
/// above the source's band has to be removed.
pub struct Resampler {
    ch: usize,
    step: f64,
    pos: f64,
    buf: Vec<f32>,
}

impl Resampler {
    pub fn new(ch: usize, from: u32, to: u32) -> Resampler {
        // one silent frame of history before the first sample
        Resampler { ch, step: from as f64 / to as f64, pos: 1.0, buf: vec![0.0; ch] }
    }

    pub fn process(&mut self, input: &[f32], out: &mut Vec<f32>) {
        let ch = self.ch;
        self.buf.extend_from_slice(&input[..input.len() / ch * ch]);
        let frames = self.buf.len() / ch;
        while (self.pos as usize) + 2 < frames {
            let i = self.pos as usize;
            let t = (self.pos - i as f64) as f32;
            for c in 0..ch {
                let y0 = self.buf[(i - 1) * ch + c];
                let y1 = self.buf[i * ch + c];
                let y2 = self.buf[(i + 1) * ch + c];
                let y3 = self.buf[(i + 2) * ch + c];
                let a = -0.5 * y0 + 1.5 * y1 - 1.5 * y2 + 0.5 * y3;
                let b = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3;
                let cc = -0.5 * y0 + 0.5 * y2;
                out.push(((a * t + b) * t + cc) * t + y1);
            }
            self.pos += self.step;
        }
        // keep the frame before the next position (the cubic's history)
        let keep_from = (self.pos as usize).saturating_sub(1);
        self.buf.drain(..keep_from * ch);
        self.pos -= keep_from as f64;
    }
}

/// What a file holds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Source {
    /// the mix, each source at its fader
    Mix,
    /// the emulated FM-1, as it comes
    Emu,
    /// the real FM-1, as it comes
    Ext,
}

/// One file: its source, and what turns 44.1 kHz stereo into its quality.
struct Track {
    src: Source,
    wav: Wav,
    rs: Option<Resampler>,
    mono: bool,
    /// the mix as heard: limited like the output when the limiter is on (not in float: it keeps everything)
    lim: Option<crate::dsp::Limiter>,
    tmp: Vec<f32>,
    res: Vec<f32>,
}

impl Track {
    fn new(f: File, src: Source, q: Quality, limit: bool) -> std::io::Result<Track> {
        Ok(Track {
            src,
            wav: Wav::new(f, q)?,
            rs: if q.rate != SRC_RATE { Some(Resampler::new(q.channels as usize, SRC_RATE, q.rate)) } else { None },
            mono: q.channels == 1,
            lim: if src == Source::Mix && q.bits != 32 && limit { Some(crate::dsp::Limiter::default()) } else { None },
            tmp: Vec::new(),
            res: Vec::new(),
        })
    }

    fn push(&mut self, emu: &[i16], ext: &[i16], g_emu: [f32; 2], g_ext: [f32; 2]) -> std::io::Result<()> {
        let n = emu.len().min(ext.len()) & !1;
        self.tmp.clear();
        for i in 0..n {
            let v = match self.src {
                Source::Mix => emu[i] as f32 * g_emu[i & 1] + ext[i] as f32 * g_ext[i & 1],
                Source::Emu => emu[i] as f32,
                Source::Ext => ext[i] as f32,
            };
            self.tmp.push(v);
        }
        if let Some(l) = self.lim.as_mut() {
            l.process(&mut self.tmp);
        }
        if self.mono {
            let m: Vec<f32> = self.tmp.chunks_exact(2).map(|f| 0.5 * (f[0] + f[1])).collect();
            self.tmp = m;
        }
        match self.rs.as_mut() {
            Some(rs) => {
                self.res.clear();
                rs.process(&self.tmp, &mut self.res);
                self.wav.write(&self.res)
            }
            None => self.wav.write(&self.tmp),
        }
    }
}

struct Block {
    emu: Vec<i16>,
    ext: Vec<i16>,
    g_emu: [f32; 2],
    g_ext: [f32; 2],
}

#[derive(Default)]
pub struct Stats {
    /// frames (at 44.1 kHz) handed to the writer
    pub frames: AtomicU64,
    /// blocks lost because the writer was behind
    pub dropped: AtomicU64,
}

pub struct Recorder {
    tx: Option<SyncSender<Block>>,
    thread: Option<JoinHandle<Result<u64, String>>>,
    pub stats: Arc<Stats>,
}

impl Recorder {
    /// Record `files`: each a file and what it holds, all in quality `q`; the mix limited as the output is.
    pub fn start(files: Vec<(File, Source)>, q: Quality) -> Result<Recorder, String> {
        Self::start_with(files, q, crate::dsp::LIMIT.load(Ordering::Relaxed))
    }

    /// As [`start`](Self::start), [limit]: the mix (16/24-bit) through the limiter.
    pub fn start_with(files: Vec<(File, Source)>, q: Quality, limit: bool) -> Result<Recorder, String> {
        q.check()?;
        let mut tracks = Vec::new();
        for (f, s) in files {
            tracks.push(Track::new(f, s, q, limit).map_err(|e| format!("file: {e}"))?);
        }
        if tracks.is_empty() {
            return Err("no file to record into".into());
        }
        // 256 blocks of ~6 ms: about 1.5 s of slack for a slow storage write
        let (tx, rx) = sync_channel::<Block>(256);
        let thread = std::thread::Builder::new()
            .name("fm1-rec".into())
            .spawn(move || -> Result<u64, String> {
                let mut err: Option<String> = None;
                for b in rx {
                    if err.is_some() {
                        continue; // drain, then report
                    }
                    for t in tracks.iter_mut() {
                        if let Err(e) = t.push(&b.emu, &b.ext, b.g_emu, b.g_ext) {
                            err = Some(format!("write: {e}"));
                            break;
                        }
                    }
                }
                let mut frames = 0;
                for (k, t) in tracks.into_iter().enumerate() {
                    let n = t.wav.finish().map_err(|e| format!("finish: {e}"))?;
                    if k == 0 {
                        frames = n;
                    }
                }
                match err {
                    Some(e) => Err(e),
                    None => Ok(frames),
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(Recorder { tx: Some(tx), thread: Some(thread), stats: Arc::new(Stats::default()) })
    }

    /// One block from the mixer (`emu` and `ext` stereo interleaved, same length), never waiting.
    pub fn feed(&self, emu: &[i16], ext: &[i16], g_emu: [f32; 2], g_ext: [f32; 2]) {
        let Some(tx) = self.tx.as_ref() else { return };
        let n = emu.len().min(ext.len()) & !1;
        let b = Block { emu: emu[..n].to_vec(), ext: ext[..n].to_vec(), g_emu, g_ext };
        match tx.try_send(b) {
            Ok(()) => {
                self.stats.frames.fetch_add((n / 2) as u64, Ordering::Relaxed);
            }
            Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {
                self.stats.dropped.fetch_add(1, Ordering::Relaxed);
            }
        }
    }

    /// Stop: what is queued is written, the headers are completed. Returns the frames in the first file.
    pub fn stop(mut self) -> Result<u64, String> {
        drop(self.tx.take());
        match self.thread.take() {
            Some(t) => t.join().map_err(|_| "the writer panicked".to_string())?,
            None => Ok(0),
        }
    }
}

/// The recording in progress, fed by the mixer.
pub static REC: Mutex<Option<Recorder>> = Mutex::new(None);

/// Called by the mixer for every block: hands it to the recording if there is one (never waits for the lock).
/// The gains are per channel (left, right): the mixer's volume, balance and master.
pub fn feed(emu: &[i16], ext: &[i16], g_emu: [f32; 2], g_ext: [f32; 2]) {
    if let Ok(g) = REC.try_lock() {
        if let Some(r) = g.as_ref() {
            r.feed(emu, ext, g_emu, g_ext);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    fn tmp(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("fm1mix-rec-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d.join(name)
    }

    fn open_rw(p: &std::path::Path) -> File {
        std::fs::OpenOptions::new().read(true).write(true).create(true).truncate(true).open(p).unwrap()
    }

    fn bytes(p: &std::path::Path) -> Vec<u8> {
        let mut b = Vec::new();
        File::open(p).unwrap().read_to_end(&mut b).unwrap();
        b
    }

    fn u32_at(b: &[u8], i: usize) -> u32 {
        u32::from_le_bytes(b[i..i + 4].try_into().unwrap())
    }
    fn u16_at(b: &[u8], i: usize) -> u16 {
        u16::from_le_bytes([b[i], b[i + 1]])
    }

    fn i16s(b: &[u8]) -> Vec<i16> {
        b.chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]])).collect()
    }

    #[test]
    fn pcm_header_is_standard() {
        let h = wav_header(&Quality::CD, 4000, 1000);
        assert_eq!(h.len(), 44);
        assert_eq!(&h[0..4], b"RIFF");
        assert_eq!(u32_at(&h, 4), 36 + 4000);
        assert_eq!(&h[8..16], b"WAVEfmt ");
        assert_eq!(u32_at(&h, 16), 16);
        assert_eq!(u16_at(&h, 20), 1);
        assert_eq!(u16_at(&h, 22), 2);
        assert_eq!(u32_at(&h, 24), 44_100);
        assert_eq!(u32_at(&h, 28), 176_400);
        assert_eq!(u16_at(&h, 32), 4);
        assert_eq!(u16_at(&h, 34), 16);
        assert_eq!(&h[36..40], b"data");
        assert_eq!(u32_at(&h, 40), 4000);
        let h = wav_header(&Quality { bits: 24, rate: 48_000, channels: 1 }, 300, 100);
        assert_eq!((u32_at(&h, 28), u16_at(&h, 32), u16_at(&h, 34)), (144_000, 3, 24));
    }

    #[test]
    fn float_header_has_its_extension_and_fact_chunk() {
        let h = wav_header(&Quality { bits: 32, rate: 48_000, channels: 2 }, 8000, 1000);
        assert_eq!(h.len(), 58);
        assert_eq!(u32_at(&h, 4) as usize, h.len() - 8 + 8000);
        assert_eq!(u32_at(&h, 16), 18);
        assert_eq!(u16_at(&h, 20), 3);
        assert_eq!(u16_at(&h, 36), 0);
        assert_eq!(&h[38..42], b"fact");
        assert_eq!(u32_at(&h, 46), 1000);
        assert_eq!(&h[50..54], b"data");
        assert_eq!(u32_at(&h, 54), 8000);
    }

    #[test]
    fn bad_qualities_are_refused() {
        assert!(Quality { bits: 8, rate: 44_100, channels: 2 }.check().is_err());
        assert!(Quality { bits: 16, rate: 96_000, channels: 2 }.check().is_err());
        assert!(Quality { bits: 16, rate: 44_100, channels: 6 }.check().is_err());
        assert!(Recorder::start(vec![], Quality::CD).is_err());
    }

    #[test]
    fn records_the_mix_at_the_faders() {
        let p = tmp("mix.wav");
        let r = Recorder::start_with(vec![(open_rw(&p), Source::Mix)], Quality::CD, false).unwrap();
        let emu: Vec<i16> = (0..512).map(|i| (i * 10) as i16).collect();
        let ext: Vec<i16> = vec![1000; 512];
        for _ in 0..100 {
            r.feed(&emu, &ext, [0.5; 2], [2.0; 2]);
        }
        r.feed(&[30000, 30000], &[30000, 30000], [1.0; 2], [1.0; 2]); // clipped in 16-bit, not wrapped
        assert_eq!(r.stop().unwrap(), 100 * 256 + 1);
        let b = bytes(&p);
        assert_eq!(u32_at(&b, 40), (100 * 256 + 1) * 4);
        let s = i16s(&b[44..]);
        assert_eq!(s.len(), 100 * 512 + 2);
        assert_eq!(s[0], 2000); // 0 * 0.5 + 1000 * 2
        assert_eq!(s[3], 2015); // 30 * 0.5 + 2000
        assert_eq!(s[s.len() - 1], 32767);
    }

    #[test]
    fn two_tracks_apart_before_the_faders() {
        let (pa, pb) = (tmp("emu.wav"), tmp("fm1.wav"));
        let r = Recorder::start(vec![(open_rw(&pa), Source::Emu), (open_rw(&pb), Source::Ext)], Quality::CD).unwrap();
        for k in 0..50i16 {
            r.feed(&[k, -k, k, -k], &[100 + k, 200 + k, 100 + k, 200 + k], [0.0; 2], [0.0; 2]);
        }
        assert_eq!(r.stop().unwrap(), 100);
        let a = i16s(&bytes(&pa)[44..]);
        let b = i16s(&bytes(&pb)[44..]);
        assert_eq!((a.len(), b.len()), (200, 200));
        assert_eq!((a[4], a[5]), (1, -1)); // the faders at 0 do not touch the tracks
        assert_eq!((b[4], b[5]), (101, 201));
    }

    #[test]
    fn mix_in_24_bit_mono_keeps_what_16_bit_would_clip() {
        let p = tmp("m24.wav");
        let q = Quality { bits: 24, rate: 44_100, channels: 1 };
        let r = Recorder::start_with(vec![(open_rw(&p), Source::Mix)], q, false).unwrap();
        r.feed(&[20000, 10000], &[0, 0], [1.0; 2], [0.0; 2]); // mono: (20000 + 10000) / 2 = 15000
        r.feed(&[30000, 30000], &[30000, 30000], [0.6; 2], [0.6; 2]); // 36000: past 16-bit, within 24-bit's scale
        assert_eq!(r.stop().unwrap(), 2);
        let b = bytes(&p);
        assert_eq!(u32_at(&b, 40), 6);
        let s24 = |i: usize| i32::from_le_bytes([0, b[44 + 3 * i], b[45 + 3 * i], b[46 + 3 * i]]) >> 8;
        assert_eq!(s24(0), 15000 * 256);
        assert_eq!(s24(1), 8_388_607); // 36000 * 256 = 9216000: clipped at 24-bit full scale
    }

    #[test]
    fn the_limited_mix_never_clips() {
        let p = tmp("lim.wav");
        let r = Recorder::start_with(vec![(open_rw(&p), Source::Mix)], Quality::CD, true).unwrap();
        let loud: Vec<i16> = (0..512).map(|i| ((i as f32 * 0.07).sin() * 30000.0) as i16).collect();
        for _ in 0..50 {
            r.feed(&loud, &loud, [1.0; 2], [1.0; 2]); // two near-full sources: twice full scale summed
        }
        r.stop().unwrap();
        let s = i16s(&bytes(&p)[44..]);
        let peak = s.iter().map(|v| (*v as i32).abs()).max().unwrap();
        assert!(peak <= 29_200, "limited at -1 dBFS: {peak}");
        assert!(peak > 28_000, "and still loud: {peak}");
    }

    #[test]
    fn float_keeps_values_above_full_scale() {
        let p = tmp("f32.wav");
        let q = Quality { bits: 32, rate: 44_100, channels: 2 };
        let r = Recorder::start_with(vec![(open_rw(&p), Source::Mix)], q, true).unwrap();   // (float: never limited)
        r.feed(&[32767, -16384], &[32767, 0], [1.0; 2], [1.0; 2]);
        assert_eq!(r.stop().unwrap(), 1);
        let b = bytes(&p);
        let f = |i: usize| f32::from_le_bytes(b[58 + 4 * i..62 + 4 * i].try_into().unwrap());
        assert!((f(0) - 65534.0 / 32768.0).abs() < 1e-6, "{}", f(0)); // ~2.0: not clipped
        assert_eq!(f(1), -0.5);
        assert_eq!(u32_at(&b, 46), 1); // fact: 1 frame
    }

    #[test]
    fn resampler_keeps_level_and_pitch() {
        let mut rs = Resampler::new(1, 44_100, 48_000);
        let mut out = Vec::new();
        // 1 s of a 1 kHz sine at 44.1 kHz, fed in odd-sized blocks
        let x: Vec<f32> = (0..44_100).map(|i| (2.0 * std::f32::consts::PI * 1000.0 * i as f32 / 44_100.0).sin() * 10_000.0).collect();
        for c in x.chunks(333) {
            rs.process(c, &mut out);
        }
        assert!((out.len() as i64 - 48_000).abs() <= 3, "{} samples", out.len());
        // still 1 kHz: about 2000 sign changes in 1 s
        let crossings = out.windows(2).filter(|w| (w[0] < 0.0) != (w[1] < 0.0)).count();
        assert!((crossings as i64 - 2000).abs() <= 4, "{crossings}");
        // still the same level (the first few samples ramp up from the silent history)
        let peak = out[100..].iter().fold(0f32, |m, v| m.max(v.abs()));
        assert!((peak - 10_000.0).abs() < 60.0, "{peak}");
    }

    #[test]
    fn records_at_48_khz() {
        let p = tmp("r48.wav");
        let q = Quality { bits: 16, rate: 48_000, channels: 2 };
        let r = Recorder::start(vec![(open_rw(&p), Source::Ext)], q).unwrap();
        let blk = vec![5000i16; 512];
        for _ in 0..(44_100 / 256) {
            r.feed(&blk, &blk, [1.0; 2], [1.0; 2]);
        }
        let frames = r.stop().unwrap();
        // 172 blocks of 256 frames at 44.1 kHz -> x 48/44.1
        let want = 172.0 * 256.0 * 48_000.0 / 44_100.0;
        assert!((frames as f64 - want).abs() < 4.0, "{frames} vs {want}");
        let b = bytes(&p);
        assert_eq!(u32_at(&b, 24), 48_000);
        let s = i16s(&b[44..]);
        assert!(s[100..].iter().all(|&v| v == 5000), "a constant stays constant");
    }

    #[test]
    fn an_empty_recording_is_a_valid_empty_wav() {
        let p = tmp("empty.wav");
        let r = Recorder::start(vec![(open_rw(&p), Source::Mix)], Quality::CD).unwrap();
        assert_eq!(r.stop().unwrap(), 0);
        assert_eq!(bytes(&p), wav_header(&Quality::CD, 0, 0));
    }

    #[test]
    fn the_mixer_feeds_the_recording_in_progress() {
        let p = tmp("global.wav");
        *REC.lock().unwrap() = Some(Recorder::start(vec![(open_rw(&p), Source::Mix)], Quality::CD).unwrap());
        feed(&[1, 2, 3, 4], &[0, 0, 0, 0], [1.0; 2], [1.0; 2]);
        let r = REC.lock().unwrap().take().unwrap();
        assert_eq!(r.stop().unwrap(), 2);
        feed(&[1, 2], &[0, 0], [1.0; 2], [1.0; 2]); // nothing recording: nothing happens
    }
}
