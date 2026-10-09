//! A small PNG decoder for the software renderer: non-interlaced images of every color type at 8 bits (16-bit
//! channels keep their high byte; palette and gray also at 1, 2 and 4 bits), into straight RGBA8.

/// A decoded image: straight (not premultiplied) RGBA, four bytes a pixel, rows top first.
#[derive(Debug, Clone, PartialEq)]
pub struct Image {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

const SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];

fn be32(b: &[u8]) -> u32 {
    u32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

fn paeth(a: u8, b: u8, c: u8) -> u8 {
    let (a16, b16, c16) = (a as i16, b as i16, c as i16);
    let p = a16 + b16 - c16;
    let (pa, pb, pc) = ((p - a16).abs(), (p - b16).abs(), (p - c16).abs());
    if pa <= pb && pa <= pc {
        a
    } else if pb <= pc {
        b
    } else {
        c
    }
}

/// Decodes [bytes]; `None` for what is not a PNG this decoder reads.
pub fn decode(bytes: &[u8]) -> Option<Image> {
    if bytes.len() < 8 || bytes[..8] != SIGNATURE {
        return None;
    }
    let (mut width, mut height, mut depth, mut color, mut interlace) = (0u32, 0u32, 0u8, 0u8, 0u8);
    let mut palette: Vec<[u8; 4]> = Vec::new();
    let mut transparent: Option<[u16; 3]> = None;
    let mut data = Vec::new();
    let mut at = 8;
    while at + 8 <= bytes.len() {
        let length = be32(&bytes[at..]) as usize;
        let kind = &bytes[at + 4..at + 8];
        let body = bytes.get(at + 8..at + 8 + length)?;
        match kind {
            b"IHDR" if length >= 13 => {
                (width, height, depth, color, interlace) = (be32(body), be32(&body[4..]), body[8], body[9], body[12]);
            }
            b"PLTE" => palette = body.chunks_exact(3).map(|c| [c[0], c[1], c[2], 255]).collect(),
            b"tRNS" => match color {
                3 => body.iter().zip(palette.iter_mut()).for_each(|(a, p)| p[3] = *a),
                0 if body.len() >= 2 => transparent = Some([u16::from_be_bytes([body[0], body[1]]); 3]),
                2 if body.len() >= 6 => {
                    let s = |i: usize| u16::from_be_bytes([body[i], body[i + 1]]);
                    transparent = Some([s(0), s(2), s(4)]);
                }
                _ => {}
            },
            b"IDAT" => data.extend_from_slice(body),
            b"IEND" => break,
            _ => {}
        }
        at += 12 + length;
    }
    if width == 0 || height == 0 || interlace != 0 || (width as u64) * (height as u64) > (1 << 28) {
        return None;
    }
    let channels = match color {
        0 | 3 => 1,
        2 => 3,
        4 => 2,
        6 => 4,
        _ => return None,
    };
    let ok_depth = match color {
        0 | 3 => matches!(depth, 1 | 2 | 4 | 8) || (color == 0 && depth == 16),
        _ => matches!(depth, 8 | 16),
    };
    if !ok_depth {
        return None;
    }
    let raw = miniz_oxide::inflate::decompress_to_vec_zlib(&data).ok()?;
    let bits = channels * depth as usize;
    let stride = (width as usize * bits + 7) / 8;
    let step = ((bits + 7) / 8).max(1);
    if raw.len() < (stride + 1) * height as usize {
        return None;
    }
    let mut rows = vec![0u8; stride * height as usize];
    for y in 0..height as usize {
        let filter = raw[y * (stride + 1)];
        let line = &raw[y * (stride + 1) + 1..(y + 1) * (stride + 1)];
        let (done, rest) = rows.split_at_mut(y * stride);
        let previous = if y > 0 { &done[(y - 1) * stride..] } else { &[][..] };
        let current = &mut rest[..stride];
        for x in 0..stride {
            let a = if x >= step { current[x - step] } else { 0 };
            let b = if y > 0 { previous[x] } else { 0 };
            let c = if y > 0 && x >= step { previous[x - step] } else { 0 };
            current[x] = line[x].wrapping_add(match filter {
                0 => 0,
                1 => a,
                2 => b,
                3 => ((a as u16 + b as u16) / 2) as u8,
                4 => paeth(a, b, c),
                _ => return None,
            });
        }
    }
    let mut rgba = Vec::with_capacity(width as usize * height as usize * 4);
    for y in 0..height as usize {
        let row = &rows[y * stride..(y + 1) * stride];
        for x in 0..width as usize {
            // The sample [k] of pixel x: its value at the image's depth and scaled to 8 bits, and its 16-bit value.
            let sample = |k: usize| -> (u8, u16) {
                match depth {
                    8 => (row[x * channels + k], row[x * channels + k] as u16),
                    16 => {
                        let i = (x * channels + k) * 2;
                        (row[i], u16::from_be_bytes([row[i], row[i + 1]]))
                    }
                    d => {
                        let bit = x * d as usize;
                        let v = (row[bit / 8] >> (8 - d as usize - bit % 8)) & ((1u8 << d) - 1);
                        let scaled = if color == 3 { v } else { (v as u16 * 255 / ((1u16 << d) - 1)) as u8 };
                        (scaled, v as u16)
                    }
                }
            };
            let pixel = match color {
                0 => {
                    let (g, raw) = sample(0);
                    [g, g, g, if transparent.is_some_and(|t| t[0] == raw) { 0 } else { 255 }]
                }
                2 => {
                    let (r, g, b) = (sample(0), sample(1), sample(2));
                    let clear = transparent.is_some_and(|t| t == [r.1, g.1, b.1]);
                    [r.0, g.0, b.0, if clear { 0 } else { 255 }]
                }
                3 => *palette.get(sample(0).1 as usize).unwrap_or(&[0, 0, 0, 0]),
                4 => {
                    let (g, a) = (sample(0).0, sample(1).0);
                    [g, g, g, a]
                }
                _ => [sample(0).0, sample(1).0, sample(2).0, sample(3).0],
            };
            rgba.extend_from_slice(&pixel);
        }
    }
    Some(Image { width, height, rgba })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A PNG of [rows] (filter byte first) at [depth] and [color], stored deflated.
    fn png(width: u32, height: u32, depth: u8, color: u8, rows: &[u8], extra: &[(&[u8; 4], Vec<u8>)]) -> Vec<u8> {
        fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], body: &[u8]) {
            out.extend((body.len() as u32).to_be_bytes());
            out.extend(kind);
            out.extend(body);
            out.extend(crate::container::crc32(&[&kind[..], body].concat()).to_be_bytes());
        }
        let mut out = SIGNATURE.to_vec();
        let mut ihdr = width.to_be_bytes().to_vec();
        ihdr.extend(height.to_be_bytes());
        ihdr.extend([depth, color, 0, 0, 0]);
        chunk(&mut out, b"IHDR", &ihdr);
        for (kind, body) in extra {
            chunk(&mut out, kind, body);
        }
        chunk(&mut out, b"IDAT", &miniz_oxide::deflate::compress_to_vec_zlib(rows, 6));
        chunk(&mut out, b"IEND", &[]);
        out
    }

    #[test]
    fn every_filter_unfilters_to_the_same_pixels() {
        // Two RGBA rows: the first unfiltered, the second Up, Sub, Average and Paeth filtered from the same pixels.
        let first = [10u8, 20, 30, 255, 40, 50, 60, 128];
        let second = [11u8, 22, 33, 200, 44, 55, 66, 100];
        for filter in 0..5u8 {
            let mut line = vec![filter];
            for x in 0..8 {
                let a = if x >= 4 { second[x - 4] } else { 0 };
                let b = first[x];
                let c = if x >= 4 { first[x - 4] } else { 0 };
                let predict = match filter {
                    0 => 0,
                    1 => a,
                    2 => b,
                    3 => ((a as u16 + b as u16) / 2) as u8,
                    _ => paeth(a, b, c),
                };
                line.push(second[x].wrapping_sub(predict));
            }
            let rows = [&[0u8][..], &first, &line].concat();
            let image = decode(&png(2, 2, 8, 6, &rows, &[])).unwrap();
            assert_eq!(image.rgba, [&first[..], &second].concat(), "filter {}", filter);
        }
    }

    #[test]
    fn palettes_gray_and_rgb_become_rgba() {
        // A 2-bit palette with a transparent entry.
        let image = decode(&png(2, 1, 2, 3, &[0, 0b0001_0000], &[(b"PLTE", vec![1, 2, 3, 4, 5, 6]), (b"tRNS", vec![255, 0])])).unwrap();
        assert_eq!(image.rgba, [1, 2, 3, 255, 4, 5, 6, 0]);
        let gray = decode(&png(1, 1, 8, 4, &[0, 100, 50], &[])).unwrap();
        assert_eq!(gray.rgba, [100, 100, 100, 50]);
        let rgb = decode(&png(1, 1, 16, 2, &[0, 1, 2, 3, 4, 5, 6], &[])).unwrap();
        assert_eq!(rgb.rgba, [1, 3, 5, 255]);
        assert!(decode(b"not a png").is_none());
    }
}
