//! The `.p2lrt` 2 container: a 32-byte header, a table of chunks and the chunks themselves, each
//! 16-byte aligned and optionally compressed. See `docs/zh/spec/P2LRT_V2.md`.

use crate::rig::{Error, Result};
use std::borrow::Cow;

pub const HEADER_SIZE: usize = 32;
pub const ENTRY_SIZE: usize = 40;
/// Header flag: every `name` was written empty.
pub const NAMES_STRIPPED: u32 = 1;
/// Chunk flags.
pub const REQUIRED: u16 = 1;
pub const HAS_CRC: u16 = 2;
pub const COMPRESSION_SHIFT: u16 = 2;
pub const DEFLATE: u16 = 1;
pub const ZSTD: u16 = 2;
/// The largest chunk a reader inflates, so a corrupt length cannot exhaust memory.
const MAX_CHUNK: u64 = 1 << 30;

pub struct Chunk<'a> {
    pub tag: [u8; 4],
    pub version: u16,
    pub required: bool,
    pub data: Cow<'a, [u8]>,
}

impl Chunk<'_> {
    pub fn name(&self) -> String {
        String::from_utf8_lossy(&self.tag).into_owned()
    }
}

pub struct Container<'a> {
    pub minor: u16,
    pub flags: u32,
    pub chunks: Vec<Chunk<'a>>,
}

fn err<T>(message: impl Into<String>) -> Result<T> {
    Err(Error(message.into()))
}

fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes(b[at..at + 2].try_into().unwrap())
}

fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}

fn u64_at(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

fn size(v: u64, what: &str) -> Result<usize> {
    usize::try_from(v).or_else(|_| err(format!("{} exceeds this reader's address width", what)))
}

/// Reads the header and chunk table of a version 2 file whose magic was already checked.
pub fn read(bytes: &[u8], verify_crc: bool) -> Result<Container<'_>> {
    if bytes.len() < HEADER_SIZE {
        return err("Truncated .p2lrt header");
    }
    let minor = u16_at(bytes, 10);
    let flags = u32_at(bytes, 12);
    let count = u32_at(bytes, 16) as usize;
    if u32_at(bytes, 20) as usize != HEADER_SIZE {
        return err("The chunk table must follow the header");
    }
    let table_end = count.checked_mul(ENTRY_SIZE).and_then(|n| n.checked_add(HEADER_SIZE)).filter(|end| *end <= bytes.len());
    let Some(table_end) = table_end else { return err("Truncated chunk table") };

    struct Entry {
        tag: [u8; 4],
        version: u16,
        flags: u16,
        offset: usize,
        length: usize,
        raw_length: u64,
        crc: u32,
    }
    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        let at = HEADER_SIZE + i * ENTRY_SIZE;
        let tag: [u8; 4] = bytes[at..at + 4].try_into().unwrap();
        if !tag.iter().all(|c| c.is_ascii_alphanumeric()) {
            return err(format!("Invalid chunk tag {:?}", String::from_utf8_lossy(&tag)));
        }
        let offset = size(u64_at(bytes, at + 8), "A chunk offset")?;
        let length = size(u64_at(bytes, at + 16), "A chunk length")?;
        let name = String::from_utf8_lossy(&tag);
        if offset % 16 != 0 {
            return err(format!("Chunk {} is not 16-byte aligned", name));
        }
        if offset < table_end || offset.checked_add(length).map_or(true, |end| end > bytes.len()) {
            return err(format!("Chunk {} lies outside the file", name));
        }
        entries.push(Entry {
            tag,
            version: u16_at(bytes, at + 4),
            flags: u16_at(bytes, at + 6),
            offset,
            length,
            raw_length: u64_at(bytes, at + 24),
            crc: u32_at(bytes, at + 32),
        });
    }

    // Chunks may not overlap; the bytes between and after them are padding: zero, and shorter than one alignment.
    let mut order: Vec<usize> = (0..entries.len()).collect();
    order.sort_by_key(|i| entries[*i].offset);
    let mut end = table_end;
    let padding = |from: usize, to: usize| to - from < 16 && bytes[from..to].iter().all(|b| *b == 0);
    for i in &order {
        let e = &entries[*i];
        if e.offset < end {
            return err(format!("Chunk {} overlaps another", String::from_utf8_lossy(&e.tag)));
        }
        if !padding(end, e.offset) {
            return err("Unexpected bytes between chunks");
        }
        end = e.offset + e.length;
    }
    if !padding(end, bytes.len()) {
        return err("Trailing bytes after the last chunk");
    }

    let mut chunks = Vec::with_capacity(entries.len());
    for e in entries {
        let name = String::from_utf8_lossy(&e.tag).into_owned();
        let stored = &bytes[e.offset..e.offset + e.length];
        if verify_crc && e.flags & HAS_CRC != 0 && crc32(stored) != e.crc {
            return err(format!("Chunk {} fails its CRC", name));
        }
        if e.raw_length > MAX_CHUNK {
            return err(format!("Chunk {} is too large", name));
        }
        let raw_length = e.raw_length as usize;
        let data: Cow<[u8]> = match (e.flags >> COMPRESSION_SHIFT) & 3 {
            0 => {
                if raw_length != e.length {
                    return err(format!("Chunk {} is not compressed but its lengths differ", name));
                }
                Cow::Borrowed(stored)
            }
            DEFLATE => Cow::Owned(
                miniz_oxide::inflate::decompress_to_vec_zlib_with_limit(stored, raw_length)
                    .or_else(|_| err(format!("Chunk {} does not inflate", name)))?,
            ),
            ZSTD => Cow::Owned(unzstd(stored, raw_length).ok_or_else(|| Error(format!("Chunk {} does not decompress", name)))?),
            _ => return err(format!("Chunk {} uses an unknown compression", name)),
        };
        if data.len() != raw_length {
            return err(format!("Chunk {} decompresses to {} bytes, not {}", name, data.len(), raw_length));
        }
        chunks.push(Chunk { tag: e.tag, version: e.version, required: e.flags & REQUIRED != 0, data });
    }
    Ok(Container { minor, flags, chunks })
}

/// One zstd frame holding exactly [raw_length] bytes and nothing after it.
fn unzstd(stored: &[u8], raw_length: usize) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut source = stored;
    let mut out = Vec::with_capacity(raw_length);
    {
        let decoder = ruzstd::decoding::StreamingDecoder::new(&mut source).ok()?;
        decoder.take(raw_length as u64 + 1).read_to_end(&mut out).ok()?;
    }
    (source.is_empty()).then_some(out)
}

/// IEEE CRC-32, as `java.util.zip.CRC32` computes it.
pub fn crc32(data: &[u8]) -> u32 {
    static TABLE: std::sync::OnceLock<[u32; 256]> = std::sync::OnceLock::new();
    let table = TABLE.get_or_init(|| {
        let mut t = [0u32; 256];
        for (i, v) in t.iter_mut().enumerate() {
            let mut c = i as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            }
            *v = c;
        }
        t
    });
    !data.iter().fold(!0u32, |c, b| table[((c ^ *b as u32) & 0xFF) as usize] ^ (c >> 8))
}

/// An IEEE half-precision value.
pub fn f16_to_f32(h: u16) -> f32 {
    let sign = ((h & 0x8000) as u32) << 16;
    let exponent = ((h >> 10) & 0x1F) as u32;
    let mantissa = (h & 0x3FF) as u32;
    let bits = match exponent {
        0 if mantissa == 0 => sign,
        0 => {
            // Subnormal: shift the mantissa up to its implicit bit.
            let (mut e, mut m) = (113u32, mantissa);
            while m & 0x400 == 0 {
                m <<= 1;
                e -= 1;
            }
            sign | (e << 23) | ((m & 0x3FF) << 13)
        }
        0x1F => sign | 0x7F80_0000 | (mantissa << 13),
        _ => sign | ((exponent + 112) << 23) | (mantissa << 13),
    };
    f32::from_bits(bits)
}
