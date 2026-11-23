//! The FM-1's sound read straight from USB, for phones whose sound system turns its input down (a Pixel 8a offers
//! the FM-1 as an output but never as an input: every input there runs at 48 kHz, the FM-1 sends 44.1 kHz only).
//!
//! The app opens the FM-1 with Android's UsbManager, claims its audio streaming interface (the one with an
//! isochronous IN endpoint; the MIDI interface is left to Android) and selects the alternate setting that streams.
//! Here, on the file descriptor Android gives: isochronous URBs submitted and reaped through usbdevfs (what libusb
//! does), each packet's frames turned into stereo 16-bit (channels 1 and 2: the master, with LOOM-8's 10 channels too)
//! and handed to a sink (the engine's capture ring).
//!
//! The pure parts (descriptor parsing, sample conversion, the usbdevfs layout) are tested on the build machine.

use std::os::raw::{c_int, c_uchar, c_uint, c_void};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

// ------------------------------------------------------------------ descriptors

/// The audio stream the FM-1 sends, as its descriptors declare it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct InStream {
    pub interface: u8,
    pub alt: u8,
    pub endpoint: u8,
    pub max_packet: u16,
    pub channels: u8,
    /// bytes a sample (2: 16-bit, 3: 24-bit, 4: 32-bit)
    pub subframe: u8,
    pub bits: u8,
    /// the first sampling rate declared (0: none)
    pub rate: u32,
}

/// Find the isochronous IN audio stream in a configuration's descriptors (Android's `getRawDescriptors`: the device
/// descriptor then the configuration). USB Audio Class 1 (what the FM-1 is: full speed, 1 ms packets).
pub fn parse_in_stream(raw: &[u8]) -> Option<InStream> {
    let mut i = 0usize;
    // the interface being read: (number, alt, class, subclass)
    let mut cur: Option<(u8, u8, u8, u8)> = None;
    // the format of the current alternate setting
    let (mut channels, mut subframe, mut bits, mut rate) = (0u8, 0u8, 0u8, 0u32);
    while i + 2 <= raw.len() {
        let len = raw[i] as usize;
        if len < 2 || i + len > raw.len() {
            break;
        }
        let d = &raw[i..i + len];
        match d[1] {
            4 if len >= 9 => {
                // INTERFACE
                cur = Some((d[2], d[3], d[5], d[6]));
                channels = 0;
                subframe = 0;
                bits = 0;
                rate = 0;
            }
            0x24 if len >= 8 => {
                // CS_INTERFACE: FORMAT_TYPE (subtype 2), type I, in an audio streaming interface
                if let Some((_, _, 1, 2)) = cur {
                    if d[2] == 2 && d[3] == 1 {
                        channels = d[4];
                        subframe = d[5];
                        bits = d[6];
                        let n = d[7] as usize;
                        rate = if n >= 1 && len >= 11 { d[8] as u32 | (d[9] as u32) << 8 | (d[10] as u32) << 16 } else { 0 };
                    }
                }
            }
            5 if len >= 7 => {
                // ENDPOINT: isochronous (bits 0-1 = 1), IN (bit 7), in an audio streaming interface
                if let Some((num, alt, 1, 2)) = cur {
                    if d[2] & 0x80 != 0 && d[3] & 3 == 1 {
                        let mp = (d[4] as u16 | (d[5] as u16) << 8) & 0x7ff;
                        return Some(InStream {
                            interface: num,
                            alt,
                            endpoint: d[2],
                            max_packet: mp,
                            channels: if channels == 0 { 2 } else { channels },
                            subframe: if subframe == 0 { 2 } else { subframe },
                            bits: if bits == 0 { 16 } else { bits },
                            rate,
                        });
                    }
                }
            }
            _ => {}
        }
        i += len;
    }
    None
}

// ------------------------------------------------------------------ samples

/// Channels 1 and 2 of interleaved little-endian PCM (`channels` channels, `subframe` bytes a sample) appended to
/// `out` as stereo 16-bit (the top 16 bits of wider samples; a mono stream on both sides). A partial frame is dropped.
pub fn convert(data: &[u8], channels: usize, subframe: usize, out: &mut Vec<i16>) {
    if channels == 0 || !(2..=4).contains(&subframe) {
        return;
    }
    let fb = channels * subframe;
    let s16 = |p: &[u8]| -> i16 { i16::from_le_bytes([p[subframe - 2], p[subframe - 1]]) };
    for f in data.chunks_exact(fb) {
        let l = s16(&f[0..subframe]);
        let r = if channels >= 2 { s16(&f[subframe..2 * subframe]) } else { l };
        out.push(l);
        out.push(r);
    }
}

// ------------------------------------------------------------------ usbdevfs

/// `struct usbdevfs_iso_packet_desc` (linux/usbdevice_fs.h)
#[repr(C)]
#[derive(Clone, Copy, Default, Debug)]
pub struct IsoDesc {
    pub length: c_uint,
    pub actual_length: c_uint,
    pub status: c_uint,
}

/// `struct usbdevfs_urb`, without its trailing `iso_frame_desc[]`
#[repr(C)]
#[derive(Debug)]
pub struct Urb {
    pub typ: c_uchar,
    pub endpoint: c_uchar,
    pub status: c_int,
    pub flags: c_uint,
    pub buffer: *mut c_void,
    pub buffer_length: c_int,
    pub actual_length: c_int,
    pub start_frame: c_int,
    pub number_of_packets: c_int,
    pub error_count: c_int,
    pub signr: c_uint,
    pub usercontext: *mut c_void,
}

/// Packets (1 ms each at full speed) a URB, and URBs kept in flight: 8 x 6 = 48 ms queued at the device.
pub const PACKETS: usize = 8;
pub const URBS: usize = 6;

/// A URB with its packet table right behind it, as the kernel reads it.
#[repr(C)]
struct UrbIso {
    urb: Urb,
    iso: [IsoDesc; PACKETS],
}

/// The URBs and their buffers, moved to the reading thread (their raw pointers are only ours and the kernel's).
struct Owned {
    _bufs: Vec<Vec<u8>>,
    _urbs: Vec<Box<UrbIso>>,
}
// SAFETY: nothing else holds the pointers; the reading thread is the only one that touches the URBs' contents
unsafe impl Send for Owned {}

const URB_TYPE_ISO: c_uchar = 0;
const URB_ISO_ASAP: c_uint = 0x02;

const fn ioc(dir: u32, nr: u32, size: usize) -> u32 {
    (dir << 30) | ((size as u32) << 16) | (0x55 << 8) | nr // 'U'
}
pub const SUBMITURB: u32 = ioc(2, 10, std::mem::size_of::<Urb>()); // _IOR('U', 10, struct usbdevfs_urb)
pub const DISCARDURB: u32 = ioc(0, 11, 0); // _IO('U', 11)
pub const REAPURB: u32 = ioc(1, 12, std::mem::size_of::<*mut c_void>()); // _IOW('U', 12, void *)

fn ioctl(fd: c_int, req: u32, arg: *mut c_void) -> Result<(), i32> {
    // SAFETY: usbdevfs ioctls on the descriptor Android opened; `arg` points at what each request expects
    let r = unsafe { libc::ioctl(fd, req as _, arg) };
    if r < 0 {
        Err(std::io::Error::last_os_error().raw_os_error().unwrap_or(-1))
    } else {
        Ok(())
    }
}

// ------------------------------------------------------------------ the reader

/// What the reader reports (for the screen).
#[derive(Default)]
pub struct Stats {
    /// stereo samples (2 a frame) handed to the sink
    pub samples: AtomicU64,
    pub packets: AtomicU64,
    pub bad_packets: AtomicU64,
    /// the errno that stopped it (0: none)
    pub error: AtomicU64,
    pub running: AtomicBool,
}

pub struct Reader {
    stop: Arc<AtomicBool>,
    fd: c_int,
    /// the URBs' addresses, to discard them from another thread
    urbs: Arc<Mutex<Vec<usize>>>,
    thread: Option<JoinHandle<()>>,
    pub stats: Arc<Stats>,
}

type Sink = Box<dyn FnMut(&[i16]) + Send>;

impl Reader {
    /// Start reading `st` on `fd` (the interface claimed and its streaming alternate selected by the caller).
    pub fn start(fd: c_int, st: InStream, mut sink: Sink) -> Result<Reader, String> {
        if st.max_packet == 0 {
            return Err("endpoint with a 0-byte packet".into());
        }
        let stop = Arc::new(AtomicBool::new(false));
        let stats = Arc::new(Stats::default());
        let urbs_addr = Arc::new(Mutex::new(Vec::new()));
        let mp = st.max_packet as usize;

        // the URBs and their buffers live with the thread; every one is reaped before they are freed
        let mut bufs: Vec<Vec<u8>> = (0..URBS).map(|_| vec![0u8; mp * PACKETS]).collect();
        let mut urbs: Vec<Box<UrbIso>> = Vec::with_capacity(URBS);
        for (k, b) in bufs.iter_mut().enumerate() {
            urbs.push(Box::new(UrbIso {
                urb: Urb {
                    typ: URB_TYPE_ISO,
                    endpoint: st.endpoint,
                    status: 0,
                    flags: URB_ISO_ASAP,
                    buffer: b.as_mut_ptr() as *mut c_void,
                    buffer_length: (mp * PACKETS) as c_int,
                    actual_length: 0,
                    start_frame: 0,
                    number_of_packets: PACKETS as c_int,
                    error_count: 0,
                    signr: 0,
                    usercontext: k as *mut c_void,
                },
                iso: [IsoDesc { length: mp as c_uint, actual_length: 0, status: 0 }; PACKETS],
            }));
        }
        let mut in_flight = 0usize;
        for u in urbs.iter_mut() {
            let p = &mut **u as *mut UrbIso as *mut c_void;
            if let Err(e) = ioctl(fd, SUBMITURB, p) {
                // nothing to reap but what went out
                for v in urbs.iter_mut().take(in_flight) {
                    let _ = ioctl(fd, DISCARDURB, &mut **v as *mut UrbIso as *mut c_void);
                }
                drain(fd, in_flight);
                return Err(format!("submit: errno {e}"));
            }
            in_flight += 1;
        }
        *urbs_addr.lock().unwrap() = urbs.iter().map(|u| &**u as *const UrbIso as usize).collect();
        stats.running.store(true, Ordering::Relaxed);

        let (stop2, stats2) = (stop.clone(), stats.clone());
        let ch = st.channels.max(1) as usize;
        let sub = st.subframe as usize;
        let owned = Owned { _bufs: bufs, _urbs: urbs };
        let thread = std::thread::Builder::new()
            .name("fm1-usb-in".into())
            .spawn(move || {
                let _keep = owned; // freed only after every URB came back
                let mut out: Vec<i16> = Vec::with_capacity(mp * PACKETS);
                let mut in_flight = URBS;
                while in_flight > 0 {
                    let mut p: *mut c_void = std::ptr::null_mut();
                    if let Err(e) = ioctl(fd, REAPURB, &mut p as *mut *mut c_void as *mut c_void) {
                        if e == libc::EINTR {
                            continue;
                        }
                        // ENODEV: unplugged; the URBs are gone with the device
                        stats2.error.store(e as u64, Ordering::Relaxed);
                        break;
                    }
                    if p.is_null() {
                        continue;
                    }
                    // SAFETY: the kernel hands back one of our URBs, untouched until resubmitted
                    let u = unsafe { &mut *(p as *mut UrbIso) };
                    in_flight -= 1;
                    if stop2.load(Ordering::Relaxed) {
                        continue; // reap the rest, resubmit nothing
                    }
                    out.clear();
                    let base = u.urb.buffer as *const u8;
                    for (i, d) in u.iso.iter().enumerate() {
                        stats2.packets.fetch_add(1, Ordering::Relaxed);
                        let n = (d.actual_length as usize).min(mp);
                        if d.status != 0 {
                            stats2.bad_packets.fetch_add(1, Ordering::Relaxed);
                            continue;
                        }
                        // SAFETY: packet i sits at i * max_packet in the URB's buffer, n <= max_packet bytes
                        let data = unsafe { std::slice::from_raw_parts(base.add(i * mp), n) };
                        convert(data, ch, sub, &mut out);
                    }
                    if !out.is_empty() {
                        sink(&out);
                        stats2.samples.fetch_add(out.len() as u64, Ordering::Relaxed);
                    }
                    for d in u.iso.iter_mut() {
                        d.actual_length = 0;
                        d.status = 0;
                    }
                    u.urb.status = 0;
                    u.urb.actual_length = 0;
                    u.urb.error_count = 0;
                    match ioctl(fd, SUBMITURB, p) {
                        Ok(()) => in_flight += 1,
                        Err(e) => {
                            stats2.error.store(e as u64, Ordering::Relaxed);
                            stop2.store(true, Ordering::Relaxed);
                        }
                    }
                }
                stats2.running.store(false, Ordering::Relaxed);
            })
            .map_err(|e| e.to_string())?;
        Ok(Reader { stop, fd, urbs: urbs_addr, thread: Some(thread), stats })
    }

    /// Stop: no more resubmits, every URB in flight discarded and reaped. The caller then releases the interface.
    pub fn stop(mut self) {
        self.stop.store(true, Ordering::Relaxed);
        for &a in self.urbs.lock().unwrap().iter() {
            let _ = ioctl(self.fd, DISCARDURB, a as *mut c_void);
        }
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

/// Reap `n` URBs (after discarding them), for a start that failed half way.
fn drain(fd: c_int, mut n: usize) {
    while n > 0 {
        let mut p: *mut c_void = std::ptr::null_mut();
        match ioctl(fd, REAPURB, &mut p as *mut *mut c_void as *mut c_void) {
            Ok(()) => n -= 1,
            Err(e) if e == libc::EINTR => {}
            Err(_) => break,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A configuration as fm1-base builds it: audio control, the IN stream (alt 0 empty, alt 1 streaming), the OUT
    /// stream, MIDI. `ch` channels of 16-bit at 44.1 kHz.
    fn fm1_like(ch: u8) -> Vec<u8> {
        let mp = 46u16 * ch as u16 * 2;
        let mut v = vec![18, 1, 0x00, 0x02, 0, 0, 0, 64, 0x09, 0x12, 0x01, 0, 0, 1, 1, 2, 3, 1]; // device
        v.extend([9, 2, 0, 0, 4, 1, 0, 0x80, 50]); // configuration (total length unchecked here)
        v.extend([9, 4, 0, 0, 0, 1, 1, 0, 0]); // interface 0: audio control
        v.extend([9, 0x24, 1, 0x00, 0x01, 30, 0, 2, 1]); // AC header (a CS_INTERFACE that is not a format)
        v.extend([9, 4, 1, 0, 0, 1, 2, 0, 0]); // interface 1 alt 0: streaming, no endpoint
        v.extend([9, 4, 1, 1, 1, 1, 2, 0, 0]); // interface 1 alt 1
        v.extend([7, 0x24, 1, 2, 1, 1, 0]); // AS general
        v.extend([11, 0x24, 2, 1, ch, 2, 16, 1, 0x44, 0xac, 0x00]); // format type I: ch, 2 bytes, 16 bits, 44100
        v.extend([9, 5, 0x81, 0x05, (mp & 0xff) as u8, (mp >> 8) as u8, 1, 0, 0]); // iso IN async
        v.extend([7, 0x25, 1, 1, 0, 0, 0]); // CS endpoint
        v.extend([9, 4, 2, 0, 0, 1, 2, 0, 0]); // interface 2 alt 0
        v.extend([9, 4, 2, 1, 1, 1, 2, 0, 0]); // interface 2 alt 1: the OUT stream
        v.extend([11, 0x24, 2, 1, 2, 2, 16, 1, 0x44, 0xac, 0x00]);
        v.extend([9, 5, 0x02, 0x09, 196, 0, 1, 0, 0]); // iso OUT
        v.extend([9, 4, 3, 0, 2, 1, 3, 0, 0]); // interface 3: MIDI
        v.extend([9, 5, 0x03, 0x02, 64, 0, 0, 0, 0]); // bulk OUT
        v.extend([9, 5, 0x83, 0x02, 64, 0, 0, 0, 0]); // bulk IN (not audio streaming: ignored)
        v
    }

    #[test]
    fn finds_chordism_stream() {
        let s = parse_in_stream(&fm1_like(2)).expect("stream");
        assert_eq!(s, InStream { interface: 1, alt: 1, endpoint: 0x81, max_packet: 184, channels: 2, subframe: 2, bits: 16, rate: 44100 });
    }

    #[test]
    fn finds_loom8_ten_channels() {
        let s = parse_in_stream(&fm1_like(10)).expect("stream");
        assert_eq!((s.channels, s.max_packet, s.interface, s.alt), (10, 920, 1, 1));
    }

    #[test]
    fn no_in_stream_and_garbage() {
        let mut v = fm1_like(2);
        let at = v.windows(3).position(|w| w == [9, 5, 0x81]).unwrap();
        v[at + 2] = 0x01; // the IN endpoint made OUT
        assert_eq!(parse_in_stream(&v), None);
        assert_eq!(parse_in_stream(&[]), None);
        assert_eq!(parse_in_stream(&[0, 4, 1]), None); // a zero length stops the walk
        assert_eq!(parse_in_stream(&[40, 4, 1, 2]), None); // a length past the end
    }

    #[test]
    fn converts_channels_one_and_two() {
        // 10 channels of 16-bit: channel c of frame f = f*100 + c
        let mut data = Vec::new();
        for f in 0..3i16 {
            for c in 0..10i16 {
                data.extend((f * 100 + c).to_le_bytes());
            }
        }
        data.extend([1, 2, 3]); // a partial frame
        let mut out = Vec::new();
        convert(&data, 10, 2, &mut out);
        assert_eq!(out, vec![0, 1, 100, 101, 200, 201]);

        // 24-bit stereo: the top 16 bits
        let mut out = Vec::new();
        convert(&[0x11, 0x34, 0x12, 0x22, 0xcd, 0xab], 2, 3, &mut out);
        assert_eq!(out, vec![0x1234, 0xabcdu16 as i16]);

        // mono on both sides
        let mut out = Vec::new();
        convert(&[0x05, 0x00, 0x06, 0x00], 1, 2, &mut out);
        assert_eq!(out, vec![5, 5, 6, 6]);
    }

    #[test]
    fn a_descriptor_that_is_not_usb_fails_cleanly() {
        use std::os::fd::AsRawFd;
        let f = std::fs::File::open("/dev/null").unwrap();
        let st = parse_in_stream(&fm1_like(2)).unwrap();
        let r = Reader::start(f.as_raw_fd(), st, Box::new(|_| {}));
        let e = r.err().expect("must fail");
        assert!(e.starts_with("submit: errno"), "{e}");
    }

    #[test]
    fn usbdevfs_layout_matches_the_kernel() {
        // linux/usbdevice_fs.h on a 64-bit kernel (arm64 phones, as on x86-64)
        if std::mem::size_of::<usize>() == 8 {
            assert_eq!(std::mem::size_of::<Urb>(), 56);
            assert_eq!(std::mem::offset_of!(Urb, buffer), 16);
            assert_eq!(std::mem::offset_of!(Urb, number_of_packets), 36);
            assert_eq!(std::mem::offset_of!(Urb, usercontext), 48);
            assert_eq!(std::mem::size_of::<UrbIso>(), 56 + 12 * PACKETS);
            assert_eq!(std::mem::offset_of!(UrbIso, iso), 56);
            assert_eq!(SUBMITURB, 0x8038_550a);
            assert_eq!(REAPURB, 0x4008_550c);
        }
        assert_eq!(std::mem::size_of::<IsoDesc>(), 12);
        assert_eq!(DISCARDURB, 0x0000_550b);
    }
}
