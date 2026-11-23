//! What an emulated FM-1 keeps between runs: the flash sectors its firmware wrote (patches, presets, settings,
//! projects: whatever the firmware saves, as on a real FM-1), as the sectors that differ from the firmware's own
//! package. Kept per firmware by the app; a newer version of the same firmware boots its own code with them, as a
//! real FM-1 keeps its data through an update.
//!
//! File: "FM1S", version (u32 LE, 1), count (u32 LE), then each sector: its offset (u32 LE, 4 KiB aligned) and its
//! 4096 bytes. (Before 0.15 the app kept the whole 1 MiB image, code included: still read, for the same package.)

pub const SECTOR: usize = 4096;
const MAGIC: &[u8; 4] = b"FM1S";

/// The sectors of `now` that differ from `pristine` (the flash as the package left it).
pub fn encode(pristine: &[u8], now: &[u8]) -> Vec<u8> {
    let n = pristine.len().min(now.len()) / SECTOR;
    let changed: Vec<usize> = (0..n).filter(|&k| pristine[k * SECTOR..(k + 1) * SECTOR] != now[k * SECTOR..(k + 1) * SECTOR]).collect();
    let mut out = Vec::with_capacity(12 + changed.len() * (4 + SECTOR));
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&1u32.to_le_bytes());
    out.extend_from_slice(&(changed.len() as u32).to_le_bytes());
    for k in changed {
        out.extend_from_slice(&((k * SECTOR) as u32).to_le_bytes());
        out.extend_from_slice(&now[k * SECTOR..(k + 1) * SECTOR]);
    }
    out
}

pub fn is_sectors(saved: &[u8]) -> bool {
    saved.len() >= 12 && &saved[..4] == MAGIC
}

/// The sectors in a saved file (offset, bytes), checked: none outside `flash_size`, none twice, none cut short.
pub fn decode(saved: &[u8], flash_size: usize) -> Result<Vec<(usize, &[u8])>, String> {
    if !is_sectors(saved) {
        return Err("not a sectors file".into());
    }
    let u32_at = |i: usize| u32::from_le_bytes(saved[i..i + 4].try_into().unwrap());
    if u32_at(4) != 1 {
        return Err(format!("sectors file version {}", u32_at(4)));
    }
    let count = u32_at(8) as usize;
    if saved.len() != 12 + count * (4 + SECTOR) {
        return Err("sectors file cut short".into());
    }
    let mut seen = vec![false; flash_size / SECTOR];
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let p = 12 + i * (4 + SECTOR);
        let at = u32_at(p) as usize;
        if at % SECTOR != 0 || at + SECTOR > flash_size || std::mem::replace(&mut seen[at / SECTOR], true) {
            return Err(format!("bad sector at {at:#x}"));
        }
        out.push((at, &saved[p + 4..p + 4 + SECTOR]));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_what_changed_and_back() {
        let pristine = vec![0xFFu8; 16 * SECTOR];
        let mut now = pristine.clone();
        now[3 * SECTOR + 7] = 1;
        now[9 * SECTOR] = 2;
        let f = encode(&pristine, &now);
        assert_eq!(f.len(), 12 + 2 * (4 + SECTOR));
        let s = decode(&f, pristine.len()).unwrap();
        assert_eq!(s.iter().map(|x| x.0).collect::<Vec<_>>(), vec![3 * SECTOR, 9 * SECTOR]);
        let mut back = pristine.clone();
        for (at, d) in s { back[at..at + SECTOR].copy_from_slice(d); }
        assert_eq!(back, now);
        assert_eq!(decode(&encode(&pristine, &pristine), pristine.len()).unwrap().len(), 0);
    }

    #[test]
    fn a_damaged_file_is_refused() {
        let pristine = vec![0u8; 4 * SECTOR];
        let mut now = pristine.clone();
        now[SECTOR] = 9;
        let mut f = encode(&pristine, &now);
        f.pop();
        assert!(decode(&f, pristine.len()).is_err());
        let mut f = encode(&pristine, &now);
        f[12..16].copy_from_slice(&(8 * SECTOR as u32).to_le_bytes());   // outside the flash
        assert!(decode(&f, pristine.len()).is_err());
        assert!(decode(b"nope", 4 * SECTOR).is_err());
    }
}
