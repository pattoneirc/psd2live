//! The version 2 container and its records, on files assembled here chunk by chunk.

use crate::container::{crc32, f16_to_f32, DEFLATE, HAS_CRC, REQUIRED, ZSTD};
use crate::rig::*;

/// A chunk payload under construction; arrays align relative to its start.
#[derive(Default, Clone)]
struct W(Vec<u8>);

impl W {
    fn u8(mut self, v: u8) -> Self {
        self.0.push(v);
        self
    }
    fn u16(mut self, v: u16) -> Self {
        self.0.extend(v.to_le_bytes());
        self
    }
    fn u32(mut self, v: u32) -> Self {
        self.0.extend(v.to_le_bytes());
        self
    }
    fn i32(self, v: i32) -> Self {
        self.u32(v as u32)
    }
    fn f32(mut self, v: f32) -> Self {
        self.0.extend(v.to_le_bytes());
        self
    }
    fn array(mut self, codec: u8, count: u32, elements: &[u8]) -> Self {
        while self.0.len() % 4 != 0 {
            self.0.push(0);
        }
        self.0.extend([codec, 0, 0, 0]);
        self.u32(count).raw(elements)
    }
    fn floats(self, v: &[f32]) -> Self {
        let bytes: Vec<u8> = v.iter().flat_map(|f| f.to_le_bytes()).collect();
        self.array(0, v.len() as u32, &bytes)
    }
    fn raw(mut self, v: &[u8]) -> Self {
        self.0.extend(v);
        self
    }
}

struct C {
    tag: &'static str,
    version: u16,
    flags: u16,
    data: Vec<u8>,
}

fn chunk(tag: &'static str, data: W) -> C {
    C { tag, version: 1, flags: REQUIRED | HAS_CRC, data: data.0 }
}

fn strings(list: &[&str]) -> W {
    let mut w = W::default().u32(list.len() as u32);
    let mut at = 0;
    w = w.u32(0);
    for s in list {
        at += s.len() as u32;
        w = w.u32(at);
    }
    for s in list {
        w = w.raw(s.as_bytes());
    }
    w
}

/// One mesh "M" (a triangle) under no deformer, one parameter "A".
fn core() -> Vec<C> {
    vec![
        chunk("STRS", strings(&["A", "M"])),
        chunk("CANV", W::default().f32(100.0).f32(80.0).f32(0.0).f32(0.0).f32(-1.0)),
        chunk("PARM", W::default().u32(1).u32(0).u32(0).f32(-1.0).f32(1.0).f32(0.0).u8(0)),
        chunk("DEFM", W::default().u32(0)),
        chunk("PART", W::default().u32(0)),
        chunk("MESH", mesh(W::default().floats(&[0.0, 0.0, 10.0, 0.0, 0.0, 10.0]))),
        chunk("DRAW", W::default().i32(-1).i32(500).u8(0).u8(0).u32(1).u8(1).u32(0)),
    ]
}

/// The mesh table with [positions] already encoded as an array.
fn mesh(positions: W) -> W {
    let head = W::default().u32(1).u32(1).u32(1).i32(-1).u8(0).u8(0).u32(0).u8(4 | 8).i32(-1);
    let positions_bytes = positions.0;
    // Splice the positions array in at its aligned place within this payload.
    let mut w = head;
    while w.0.len() % 4 != 0 {
        w.0.push(0);
    }
    w = w.raw(&positions_bytes);
    w.floats(&[0.0, 0.0, 1.0, 0.0, 0.0, 1.0])
        .array(16, 3, &[0, 0, 1, 0, 2, 0])
        .u16(0xFFFF)
        .u8(0)
        .f32(500.0)
        .f32(1.0)
        .f32(1.0).f32(1.0).f32(1.0)
        .f32(0.0).f32(0.0).f32(0.0)
        .u16(0)
}

fn zlib(data: &[u8]) -> Vec<u8> {
    miniz_oxide::deflate::compress_to_vec_zlib(data, 6)
}

fn zstd(data: &[u8]) -> Vec<u8> {
    ruzstd::encoding::compress_to_vec(data, ruzstd::encoding::CompressionLevel::Fastest)
}

/// A version 2 file of [chunks], each compressed as its flags say.
fn file(chunks: &[C]) -> Vec<u8> {
    let mut out = b"P2LRT\0\0\0".to_vec();
    out.extend(2u16.to_le_bytes());
    out.extend(0u16.to_le_bytes());
    out.extend(0u32.to_le_bytes());
    out.extend((chunks.len() as u32).to_le_bytes());
    out.extend(32u32.to_le_bytes());
    out.extend([0; 8]);
    let table = out.len();
    out.resize(table + chunks.len() * 40, 0);
    for (i, c) in chunks.iter().enumerate() {
        let stored = match (c.flags >> 2) & 3 {
            DEFLATE => zlib(&c.data),
            ZSTD => zstd(&c.data),
            _ => c.data.clone(),
        };
        while out.len() % 16 != 0 {
            out.push(0);
        }
        let at = table + i * 40;
        let mut entry = c.tag.as_bytes().to_vec();
        entry.extend(c.version.to_le_bytes());
        entry.extend(c.flags.to_le_bytes());
        entry.extend((out.len() as u64).to_le_bytes());
        entry.extend((stored.len() as u64).to_le_bytes());
        entry.extend((c.data.len() as u64).to_le_bytes());
        entry.extend(if c.flags & HAS_CRC != 0 { crc32(&stored) } else { 0 }.to_le_bytes());
        entry.extend([0; 4]);
        out[at..at + 40].copy_from_slice(&entry);
        out.extend(stored);
    }
    out
}

fn read(chunks: &[C]) -> Result<Rig> {
    Rig::read_with(&file(chunks), true)
}

fn error(chunks: &[C]) -> String {
    read(chunks).err().expect("the file should be rejected").0
}

fn with(mut chunks: Vec<C>, tag: &str, f: impl FnOnce(&mut C)) -> Vec<C> {
    f(chunks.iter_mut().find(|c| c.tag == tag).unwrap());
    chunks
}

#[test]
fn a_minimal_file_reads_its_core_chunks() {
    let rig = read(&core()).unwrap();
    assert_eq!(rig.canvas.width, 100.0);
    assert_eq!(rig.canvas.pixels_per_unit, None);
    assert_eq!(rig.parameters[0].id, "A");
    assert_eq!(rig.meshes[0].id, "M");
    assert_eq!(rig.meshes[0].geometry.as_ref().unwrap().indices, vec![0, 1, 2]);
    assert_eq!(rig.meshes[0].geometry.as_ref().unwrap().positions, vec![0.0, 0.0, 10.0, 0.0, 0.0, 10.0]);
    assert!(rig.textures.is_empty() && rig.glues.is_empty() && rig.gui.is_none() && rig.meta.is_empty());
}

#[test]
fn compressed_chunks_read_as_the_same_rig() {
    let plain = read(&core()).unwrap();
    for method in [DEFLATE, ZSTD] {
        let chunks: Vec<C> = core().into_iter().map(|c| C { flags: c.flags | method << 2, ..c }).collect();
        assert_eq!(read(&chunks).unwrap(), plain);
    }
}

#[test]
fn unknown_chunks_are_skipped_unless_required() {
    let mut chunks = core();
    chunks.push(C { tag: "XTRA", version: 1, flags: 0, data: vec![1, 2, 3] });
    chunks.push(C { tag: "lab0", version: 7, flags: HAS_CRC, data: vec![] });
    assert_eq!(read(&chunks).unwrap(), read(&core()).unwrap());
    chunks.push(C { tag: "XREQ", version: 1, flags: REQUIRED, data: vec![] });
    assert!(error(&chunks).contains("requires feature XREQ"));
    // A newer version of a known chunk is as unknown as a new tag.
    let newer = with(core(), "CANV", |c| c.version = 2);
    assert!(error(&newer).contains("requires feature CANV version 2"));
    let mut optional = core();
    optional.push(C { tag: "META", version: 9, flags: 0, data: vec![9] });
    assert!(read(&optional).unwrap().meta.is_empty());
}

#[test]
fn core_chunks_must_be_present_once() {
    let missing: Vec<C> = core().into_iter().filter(|c| c.tag != "DRAW").collect();
    assert!(error(&missing).contains("Missing chunk DRAW"));
    let mut twice = core();
    twice.push(chunk("CANV", W::default().f32(1.0).f32(1.0).f32(0.0).f32(0.0).f32(-1.0)));
    assert!(error(&twice).contains("appears twice"));
}

#[test]
fn records_must_fill_their_chunk_exactly() {
    let long = with(core(), "CANV", |c| c.data.push(0));
    assert!(error(&long).contains("Chunk CANV has 1 bytes"));
    let short = with(core(), "CANV", |c| {
        c.data.pop();
    });
    assert!(error(&short).contains("Truncated"));
}

#[test]
fn the_table_rejects_misplaced_chunks() {
    let good = file(&core());
    let patch = |f: &dyn Fn(&mut Vec<u8>)| {
        let mut bytes = good.clone();
        f(&mut bytes);
        Rig::read(&bytes).err().expect("rejected").0
    };
    let offset_of = |bytes: &[u8], i: usize| u64::from_le_bytes(bytes[32 + i * 40 + 8..32 + i * 40 + 16].try_into().unwrap());
    let set_offset = |bytes: &mut Vec<u8>, i: usize, v: u64| bytes[32 + i * 40 + 8..32 + i * 40 + 16].copy_from_slice(&v.to_le_bytes());
    assert!(patch(&|b| { let o = offset_of(b, 1); set_offset(b, 1, o + 4) }).contains("aligned"));
    assert!(patch(&|b| { let o = offset_of(b, 0); set_offset(b, 1, o) }).contains("overlaps"));
    assert!(patch(&|b| set_offset(b, 6, 1 << 40)).contains("outside the file"));
    assert!(patch(&|b| b.extend([0; 16])).contains("Trailing bytes"));
    assert!(patch(&|b| b.push(1)).contains("Trailing bytes"));
    assert!(patch(&|b| b[20] = 40).contains("must follow the header"));
    assert!(patch(&|b| b[16] = 200).contains("Truncated chunk table"));
    assert!(patch(&|b| b[32] = b'-').contains("Invalid chunk tag"));
    assert!(patch(&|b| { b[8] = 3; }).contains("Unsupported .p2lrt version 3"));
    // A byte flipped inside a chunk fails its CRC when asked to check, and reads otherwise.
    let mut flipped = good.clone();
    let canvas = offset_of(&flipped, 1) as usize;
    flipped[canvas] ^= 0x40;
    assert!(Rig::read_with(&flipped, true).err().unwrap().0.contains("fails its CRC"));
    assert!(Rig::read_with(&flipped, false).is_ok());
}

#[test]
fn compression_lengths_and_methods_are_checked() {
    let mut chunks = core();
    chunks[1].flags |= DEFLATE << 2;
    let mut bytes = file(&chunks);
    let raw = 32 + 40 + 24;
    bytes[raw] += 1;
    assert!(Rig::read(&bytes).err().unwrap().0.contains("decompresses to"));
    let mut reserved = file(&core());
    reserved[32 + 40 + 6] |= 3 << 2;
    assert!(Rig::read(&reserved).err().unwrap().0.contains("unknown compression"));
    let mut uneven = file(&core());
    uneven[32 + 40 + 24] += 1;
    assert!(Rig::read(&uneven).err().unwrap().0.contains("lengths differ"));
}

#[test]
fn arrays_decode_every_codec() {
    // Half floats for the positions: 0, 0.5, 10, -2, 65504 (the largest), and the smallest subnormal.
    let halves: [u16; 6] = [0x0000, 0x3800, 0x4900, 0xC000, 0x7BFF, 0x0001];
    let bytes: Vec<u8> = halves.iter().flat_map(|h| h.to_le_bytes()).collect();
    let rig = read(&with(core(), "MESH", |c| c.data = mesh(W::default().array(1, 6, &bytes)).0)).unwrap();
    assert_eq!(rig.meshes[0].geometry.as_ref().unwrap().positions, vec![0.0, 0.5, 10.0, -2.0, 65504.0, 2f32.powi(-24)]);
    let unorm: Vec<u8> = [0u16, 65535, 32768, 0, 0, 65535].iter().flat_map(|h| h.to_le_bytes()).collect();
    let rig = read(&with(core(), "MESH", |c| c.data = mesh(W::default().array(2, 6, &unorm)).0)).unwrap();
    assert_eq!(rig.meshes[0].geometry.as_ref().unwrap().positions[1], 1.0);
    // Infinity is no more allowed as a half than as a float; nor is a codec meant for indices.
    let infinite: Vec<u8> = [0x7C00u16; 6].iter().flat_map(|h| h.to_le_bytes()).collect();
    assert!(error(&with(core(), "MESH", |c| c.data = mesh(W::default().array(1, 6, &infinite)).0)).contains("Non-finite"));
    assert!(error(&with(core(), "MESH", |c| c.data = mesh(W::default().array(16, 6, &infinite)).0)).contains("codec 16"));
    assert_eq!(f16_to_f32(0xBC00), -1.0);
}

#[test]
fn array_padding_must_be_zero() {
    // The parameter table ends unaligned, so a glue table after it would pad; here the mesh table's first
    // array is preceded by padding, which must be zero.
    let chunks = with(core(), "MESH", |c| {
        let at = 4 * 4 + 1 + 1 + 4 + 1 + 4;
        assert_eq!(c.data[at], 0);
        c.data[at] = 7;
    });
    assert!(error(&chunks).contains("Non-zero padding"));
}

#[test]
fn texture_pages_carry_their_kind() {
    let mut chunks = core();
    chunks[0] = chunk("STRS", strings(&["A", "M", "page1.png"]));
    chunks.push(chunk(
        "TEXR",
        W::default().u32(3).u8(0).u32(4).u32(4).array(32, 3, &[1, 2, 3]).u8(1).u32(8).u32(8).array(32, 2, &[9, 9]).u8(2).u32(16).u32(16).u32(2),
    ));
    let rig = read(&chunks).unwrap();
    assert_eq!(rig.textures.iter().map(|t| t.kind).collect::<Vec<_>>(), vec![TextureKind::Png, TextureKind::Ktx2, TextureKind::External]);
    assert_eq!(rig.textures[0].data, vec![1, 2, 3]);
    assert_eq!(rig.textures[2].uri, "page1.png");
    assert!(rig.textures[2].data.is_empty());
}

#[test]
fn the_parameter_panel_and_information_read_back() {
    let mut chunks = core();
    chunks[0] = chunk("STRS", strings(&["A", "M", "G", "Face", "generator", "test"]));
    let node = |w: W, kind: u8, label: u8, parameter: i32, id: u32, name: u32, argb: u32, children: u32| {
        w.u8(kind).u8(1).u8(label).u8(0).i32(parameter).u32(id).u32(name).u32(u32::MAX).u32(argb).u32(children)
    };
    let gui = W::default().u32(1).u32(0).floats(&[-1.0, 0.0, 1.0]).u32(1).u32(0).u32(0).u32(2);
    let gui = node(gui, 1, 2, -1, 2, 3, 0xFF00FF00, 1);
    let gui = node(gui, 0, 0, 0, u32::MAX, u32::MAX, 0, 0);
    chunks.push(C { flags: HAS_CRC, ..chunk("PGUI", gui.clone()) });
    chunks.push(C { flags: 0, ..chunk("META", W::default().u32(1).u32(4).u32(5)) });
    let rig = read(&chunks).unwrap();
    let g = rig.gui.unwrap();
    assert_eq!(g.snaps, vec![(0, vec![-1.0, 0.0, 1.0])]);
    assert_eq!(g.joysticks, vec![(0, 0)]);
    assert_eq!(g.nodes[0].label, GuiLabel::Custom(0xFF00FF00));
    assert_eq!((g.nodes[0].name.as_str(), g.nodes[0].children, g.nodes[1].parameter), ("Face", 1, Some(0)));
    assert_eq!(rig.meta, vec![("generator".to_string(), "test".to_string())]);
    // A group that claims more children than follow it is rejected.
    let mut broken = gui.0.clone();
    let children = broken.len() - 28 - 4;
    broken[children] = 2;
    chunks.retain(|c| c.tag != "PGUI");
    chunks.push(C { flags: 0, ..chunk("PGUI", W(broken)) });
    assert!(error(&chunks).contains("missing children"));
}
