//! The `.p2lrt` rig and its reader. Version 1 is documented with the writer, `targets/runtime/.../P2lrt.kt`;
//! version 2 (chunks, the same records) in `docs/zh/spec/P2LRT_V2.md`. Every reference is an index and parents
//! come before children; both versions read into the same [Rig].

use crate::container::{self, Chunk};
use std::fmt;

/// The newest major version this reader understands; version 1 is read too.
pub const VERSION: u32 = 2;
/// The chunks this reader understands, with the newest version of each.
pub const CHUNKS: [(&str, u16); 16] = [
    ("STRS", 1), ("CANV", 1), ("PARM", 1), ("DEFM", 1), ("PART", 1), ("MESH", 1), ("GLUE", 1),
    ("DRAW", 1), ("TEXR", 1), ("PHYS", 1), ("CLIP", 1), ("ROLE", 1), ("PGUI", 1), ("META", 1),
    ("BONE", 1), ("SKIN", 1),
];

#[derive(Debug)]
pub struct Error(pub String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

fn err<T>(message: impl Into<String>) -> Result<T> {
    Err(Error(message.into()))
}

pub type Rgb = [f32; 3];
pub const WHITE: Rgb = [1.0, 1.0, 1.0];
pub const BLACK: Rgb = [0.0, 0.0, 0.0];

#[derive(Debug, Clone, PartialEq)]
pub struct Canvas {
    pub width: f32,
    pub height: f32,
    pub origin_x: f32,
    pub origin_y: f32,
    pub pixels_per_unit: Option<f32>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Parameter {
    pub id: String,
    pub name: String,
    pub min: f32,
    pub max: f32,
    pub default: f32,
    pub blend: bool,
    pub repeat: bool,
}

impl Parameter {
    /// The value the rig is evaluated at: wrapped into a repeating range, clamped otherwise.
    pub fn normalize(&self, value: f32) -> f32 {
        if !value.is_finite() {
            return self.default;
        }
        // A repeating range wraps values outside it; both ends stay as they are.
        if self.repeat && self.max > self.min && (value < self.min || value > self.max) {
            return (value - self.min).rem_euclid(self.max - self.min) + self.min;
        }
        value.clamp(self.min.min(self.max), self.max.max(self.min))
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Axis {
    pub parameter: usize,
    pub keys: Vec<f32>,
}

/// Forms keyed over the cartesian product of parameter axes; cells may be sparse.
#[derive(Debug, Clone, PartialEq)]
pub struct Grid<F> {
    pub axes: Vec<Axis>,
    pub cells: Vec<(Vec<u16>, F)>,
    /// Cell index at each dense coordinate, `u32::MAX` where the combination is not authored.
    pub(crate) dense: Vec<u32>,
    pub(crate) strides: Vec<usize>,
}

impl<F> Grid<F> {
    pub(crate) fn new(axes: Vec<Axis>, cells: Vec<(Vec<u16>, F)>) -> Result<Self> {
        let mut strides = Vec::with_capacity(axes.len());
        let mut size = 1usize;
        for axis in &axes {
            if axis.keys.is_empty() {
                return err("A keyform axis has no keys");
            }
            strides.push(size);
            size = size.checked_mul(axis.keys.len()).filter(|s| *s <= 1 << 24).ok_or(Error("Keyform grid too large".into()))?;
        }
        let mut dense = vec![u32::MAX; size];
        for (index, (coordinate, _)) in cells.iter().enumerate() {
            let mut at = 0;
            for (i, c) in coordinate.iter().enumerate() {
                if *c as usize >= axes[i].keys.len() {
                    return err("Keyform cell outside its axes");
                }
                at += *c as usize * strides[i];
            }
            dense[at] = index as u32;
        }
        if cells.is_empty() {
            return err("Keyform grid without cells");
        }
        Ok(Grid { axes, cells, dense, strides })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Channel {
    DrawOrder,
    Opacity,
    MultiplyColor,
    ScreenColor,
    FlipX,
    FlipY,
    GlueIntensity,
}

const CHANNELS: [Channel; 7] = [
    Channel::DrawOrder, Channel::Opacity, Channel::MultiplyColor, Channel::ScreenColor, Channel::FlipX, Channel::FlipY, Channel::GlueIntensity,
];

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ChannelValue {
    Scalar(f32),
    Color(Rgb),
    Flag(bool),
}

pub type Channels = Vec<(Channel, Grid<ChannelValue>)>;

#[derive(Debug, Clone, PartialEq)]
pub struct Limit {
    pub parameter: usize,
    pub points: Vec<(f32, f32)>,
}

/// An additive shape keyed on one parameter; `shapes` has one entry per key.
#[derive(Debug, Clone, PartialEq)]
pub struct Binding<S> {
    pub parameter: usize,
    pub keys: Vec<f32>,
    pub neutral: usize,
    pub shapes: Vec<Option<S>>,
    pub limits: Vec<Limit>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct LatticeShape {
    pub points: Vec<f32>,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Pivot {
    pub x: f32,
    pub y: f32,
    pub angle: f32,
    pub scale: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PivotShape {
    pub pivot: Pivot,
    pub flip_x: bool,
    pub flip_y: bool,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MeshShape {
    pub deltas: Vec<f32>,
    pub draw_order: f32,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PartShape {
    pub draw_order: f32,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone, PartialEq)]
pub enum DeformerKind {
    Warp { columns: usize, rows: usize, bilinear: bool, lattice: Option<Grid<Vec<f32>>>, shapes: Vec<Binding<LatticeShape>> },
    Rotation { base_angle: f32, pivot: Option<Grid<Pivot>>, flip_x: bool, flip_y: bool, shapes: Vec<Binding<PivotShape>> },
}

#[derive(Debug, Clone, PartialEq)]
pub struct Deformer {
    pub id: String,
    pub parent: Option<usize>,
    pub part: Option<usize>,
    pub visible: bool,
    pub enabled: bool,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
    pub channels: Channels,
    pub kind: DeformerKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Child {
    Part(usize),
    Mesh(usize),
}

#[derive(Debug, Clone, PartialEq)]
pub struct Composite {
    pub blend: u8,
    pub alpha_blend: u8,
    pub masked_by: Vec<usize>,
    pub masked_by_parts: Vec<usize>,
    pub invert_mask: bool,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Part {
    pub id: String,
    pub name: String,
    pub visible: bool,
    pub sketch: bool,
    pub group_mode: u8,
    pub draw_order: i32,
    pub children: Vec<Child>,
    pub channels: Channels,
    pub composite: Composite,
    pub shapes: Vec<Binding<PartShape>>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Geometry {
    pub positions: Vec<f32>,
    pub uvs: Vec<f32>,
    pub indices: Vec<u32>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Mesh {
    pub id: String,
    pub name: String,
    pub parent: Option<usize>,
    pub blend: u8,
    pub alpha_blend: u8,
    pub masked_by: Vec<usize>,
    pub invert_mask: bool,
    pub culling: bool,
    pub visible: bool,
    pub page: i32,
    pub geometry: Option<Geometry>,
    pub offsets: Option<Grid<Vec<f32>>>,
    pub channels: Channels,
    pub draw_order: f32,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
    pub shapes: Vec<Binding<MeshShape>>,
}

impl Mesh {
    pub fn vertex_count(&self) -> usize {
        self.geometry.as_ref().map_or(0, |g| g.positions.len() / 2)
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GluePair {
    pub a: usize,
    pub b: usize,
    pub weight_a: f32,
    pub weight_b: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Glue {
    pub id: String,
    pub mesh_a: usize,
    pub mesh_b: usize,
    pub intensity: f32,
    pub pairs: Vec<GluePair>,
    pub channels: Channels,
}

#[derive(Debug, Clone, PartialEq)]
pub enum RenderNode {
    Mesh(usize),
    Group(RenderGroup),
}

#[derive(Debug, Clone, PartialEq)]
pub struct RenderGroup {
    pub part: Option<usize>,
    pub draw_order: i32,
    pub channels: Channels,
    pub composite: Option<Composite>,
    pub children: Vec<RenderNode>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TextureKind {
    Png,
    Ktx2,
    /// A file next to the rig, named by [Texture::uri]; the host loads it.
    External,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Texture {
    pub width: u32,
    pub height: u32,
    pub kind: TextureKind,
    /// The PNG or KTX2 bytes; empty for an external page.
    pub data: Vec<u8>,
    pub uri: String,
}

/// What an extension chunk overrides in the core evaluation, readable without understanding the rest of it.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct ExtensionHeader {
    /// The `p2l_set_advanced` bit that turns the extension on.
    pub feature: u16,
    /// The evaluation hooks it uses: H1 = 1, H2 = 2, H3 = 4, H4 = 8, H6 = 32.
    pub hooks: u16,
    /// H1: parameters evaluated at their defaults for the geometry while the extension is on.
    pub parameters: Vec<usize>,
    /// H1: physics groups skipped while the extension is on.
    pub physics_groups: Vec<usize>,
    /// H2: deformers whose frames the extension replaces.
    pub deformers: Vec<usize>,
    /// H4: per mesh, the keyform axes evaluated at their defaults and the blend shapes left out.
    pub meshes: Vec<MeshOverride>,
    /// Tags of the extension chunks this one needs.
    pub depends_on: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct MeshOverride {
    pub mesh: usize,
    pub axes: Vec<usize>,
    pub bindings: Vec<usize>,
}

/// A mesh the runtime skins: it follows [bones] (rotation deformers, its parent among them) with two-bone
/// weights, which the file gives or the runtime fits to the baked keyforms over [axes].
#[derive(Debug, Clone, PartialEq)]
pub struct Skin {
    pub mesh: usize,
    /// The parameters whose keys the baked skin was sampled at, each with those keys.
    pub axes: Vec<(usize, Vec<f32>)>,
    pub bones: Vec<usize>,
    /// Per vertex: the bone it follows, the bone it blends toward and how far; empty to fit at load.
    pub from: Vec<u32>,
    pub to: Vec<u32>,
    pub weight: Vec<f32>,
}

/// A rotation deformer whose keyed pivot positions along [parameter] lie on a circle about [center] (in its
/// parent's space): with exact links on they are interpolated along that circle rather than its chords.
#[derive(Debug, Clone, PartialEq)]
pub struct Arc {
    pub deformer: usize,
    pub parameter: usize,
    pub center: [f32; 2],
}

/// The runtime's advanced data: skeleton bones, exact links and skins.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Extensions {
    pub bone_header: Option<ExtensionHeader>,
    /// Bones the rig folded into keyforms, as rotations only skinning evaluates. Deformer index `n + i` (n the
    /// rig's deformer count) is virtual bone `i`; a parent is a rig deformer or an earlier virtual bone.
    pub virtual_bones: Vec<Deformer>,
    /// Deformers a host can attach things to, with their bone ids.
    pub bones: Vec<(usize, String)>,
    pub arcs: Vec<Arc>,
    pub skin_header: Option<ExtensionHeader>,
    pub skins: Vec<Skin>,
}

/// How a parameter panel groups and shows the parameters; it does not affect evaluation.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Gui {
    /// Values a parameter snaps to, ascending.
    pub snaps: Vec<(usize, Vec<f32>)>,
    /// Pairs of parameters shown as one two-dimensional control: horizontal, vertical.
    pub joysticks: Vec<(usize, usize)>,
    /// The group tree in pre-order; each node is followed by its children.
    pub nodes: Vec<GuiNode>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum GuiLabel {
    None,
    Preset(String),
    Custom(u32),
}

#[derive(Debug, Clone, PartialEq)]
pub struct GuiNode {
    /// The parameter a leaf shows; `None` for a group.
    pub parameter: Option<usize>,
    pub id: String,
    pub name: String,
    pub open: bool,
    pub label: GuiLabel,
    pub children: usize,
}

/// What a physics input drives or an output reads: sideways travel, or tilt and segment angle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PhysicsSource {
    X,
    Angle,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PhysicsInput {
    pub parameter: usize,
    pub weight: f32,
    pub source: PhysicsSource,
    pub reflect: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PhysicsOutput {
    pub parameter: usize,
    pub vertex: usize,
    pub scale: f32,
    pub weight: f32,
    pub source: PhysicsSource,
    pub reflect: bool,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PhysicsSegment {
    pub length: f32,
    pub mobility: f32,
    pub delay: f32,
    pub acceleration: f32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Normalization {
    pub position_min: f32,
    pub position_default: f32,
    pub position_max: f32,
    pub angle_min: f32,
    pub angle_default: f32,
    pub angle_max: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PhysicsGroup {
    pub id: String,
    pub name: String,
    pub inputs: Vec<PhysicsInput>,
    pub outputs: Vec<PhysicsOutput>,
    pub segments: Vec<PhysicsSegment>,
    pub normalization: Normalization,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Segment {
    Linear { time: f32, value: f32 },
    Bezier { c1: (f32, f32), c2: (f32, f32), time: f32, value: f32 },
    Stepped { time: f32, value: f32 },
    InverseStepped { time: f32, value: f32 },
}

impl Segment {
    pub fn end(&self) -> (f32, f32) {
        match *self {
            Segment::Linear { time, value }
            | Segment::Bezier { time, value, .. }
            | Segment::Stepped { time, value }
            | Segment::InverseStepped { time, value } => (time, value),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Curve {
    pub parameter: usize,
    pub start_time: f32,
    pub start_value: f32,
    pub segments: Vec<Segment>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Clip {
    pub id: String,
    pub name: String,
    pub group: String,
    pub duration: f32,
    pub fps: f32,
    pub looping: bool,
    pub fade_in: Option<f32>,
    pub fade_out: Option<f32>,
    pub curves: Vec<Curve>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Role {
    pub role: String,
    pub parameters: Vec<usize>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Rig {
    pub canvas: Canvas,
    pub parameters: Vec<Parameter>,
    pub deformers: Vec<Deformer>,
    pub parts: Vec<Part>,
    pub meshes: Vec<Mesh>,
    pub glues: Vec<Glue>,
    pub render: RenderGroup,
    pub textures: Vec<Texture>,
    /// Physics steps per second, or 0 for the runtime's default.
    pub physics_fps: f32,
    pub physics: Vec<PhysicsGroup>,
    pub clips: Vec<Clip>,
    pub roles: Vec<Role>,
    /// Parameter panel layout, when the file carries one.
    pub gui: Option<Gui>,
    /// Generator and other information as key-value pairs.
    pub meta: Vec<(String, String)>,
    /// Advanced data, off until a host enables it.
    pub extensions: Extensions,
}

impl Rig {
    /// Deformer [i] counting the virtual bones after the rig's own.
    pub fn deformer_or_bone(&self, i: usize) -> &Deformer {
        self.deformers.get(i).unwrap_or_else(|| &self.extensions.virtual_bones[i - self.deformers.len()])
    }

    /// The rig's deformers and the virtual bones after them.
    pub fn deformer_count_with_bones(&self) -> usize {
        self.deformers.len() + self.extensions.virtual_bones.len()
    }

    pub fn read(bytes: &[u8]) -> Result<Rig> {
        Rig::read_with(bytes, false)
    }

    /// Reads version 1 or 2; with [verify_crc], chunks that carry a CRC are checked against it.
    pub fn read_with(bytes: &[u8], verify_crc: bool) -> Result<Rig> {
        if bytes.len() < 12 || &bytes[..8] != b"P2LRT   " {
            return err("Not a .p2lrt rig");
        }
        let version = u32::from_le_bytes(bytes[8..12].try_into().unwrap());
        let rig = if version == 1 {
            Reader::new(bytes, None).rig()?
        } else if version & 0xFFFF == 2 {
            read_chunks(bytes, verify_crc)?
        } else {
            return err(format!("Unsupported .p2lrt version {}", version & 0xFFFF));
        };
        rig.validate()?;
        Ok(rig)
    }

    /// Extensions refer to rotation deformers and to meshes whose vertices they weigh.
    fn validate_extensions(&self) -> Result<()> {
        let rotation = |d: usize| matches!(self.deformer_or_bone(d).kind, DeformerKind::Rotation { .. });
        for arc in &self.extensions.arcs {
            if !matches!(&self.deformer_or_bone(arc.deformer).kind, DeformerKind::Rotation { pivot: Some(g), .. } if g.axes.len() == 1 && g.axes[0].parameter == arc.parameter) {
                return err(format!("Arc on {} is not a rotation keyed on its parameter alone", self.deformer_or_bone(arc.deformer).id));
            }
        }
        for skin in &self.extensions.skins {
            let mesh = &self.meshes[skin.mesh];
            let parent_is_bone = mesh.parent.is_some_and(|p| rotation(p) && skin.bones.contains(&p));
            if !parent_is_bone || skin.bones.iter().any(|b| !rotation(*b)) {
                return err(format!("Skin of {} must hang from one of its bones, all rotations", mesh.id));
            }
            if !skin.from.is_empty() && skin.from.len() != mesh.vertex_count() {
                return err(format!("Skin weights of {} do not match its vertices", mesh.id));
            }
        }
        Ok(())
    }

    /// Checks that hold across sections: glue pairs and keyform offsets against their meshes' vertices.
    fn validate(&self) -> Result<()> {
        for g in &self.glues {
            for p in &g.pairs {
                if p.a >= self.meshes[g.mesh_a].vertex_count() || p.b >= self.meshes[g.mesh_b].vertex_count() {
                    return err(format!("Glue {} pairs a vertex its meshes do not have", g.id));
                }
            }
        }
        for mesh in &self.meshes {
            if let (Some(g), Some(offsets)) = (&mesh.geometry, &mesh.offsets) {
                if offsets.cells.iter().any(|(_, d)| !d.is_empty() && d.len() != g.positions.len()) {
                    return err(format!("Offsets of mesh {} do not match its vertices", mesh.id));
                }
            }
        }
        Ok(())
    }

    pub fn parameter(&self, id: &str) -> Option<usize> {
        self.parameters.iter().position(|p| p.id == id)
    }

    pub fn mesh(&self, id: &str) -> Option<usize> {
        self.meshes.iter().position(|m| m.id == id)
    }

    /// Every parameter at its default.
    pub fn defaults(&self) -> Vec<f32> {
        self.parameters.iter().map(|p| p.default).collect()
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
    // Counts of what references may point at, checked as they are read.
    parameters: usize,
    deformers: usize,
    parts: usize,
    meshes: usize,
    /// Version 2's string table: strings are indices into it and arrays carry a codec. `None` reads version 1.
    strings: Option<&'a [String]>,
}

/// Array codecs of version 2.
const F32: u8 = 0;
const F16: u8 = 1;
const UNORM16: u8 = 2;
const U16: u8 = 16;
const U32: u8 = 17;
const U8: u8 = 32;

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8], strings: Option<&'a [String]>) -> Self {
        Reader { bytes, at: 0, parameters: 0, deformers: 0, parts: 0, meshes: 0, strings }
    }
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.bytes.len() - self.at < n {
            return err(format!("Truncated rig at byte {}", self.at));
        }
        let slice = &self.bytes[self.at..self.at + n];
        self.at += n;
        Ok(slice)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn f32(&mut self) -> Result<f32> {
        let v = f32::from_le_bytes(self.take(4)?.try_into().unwrap());
        if !v.is_finite() {
            return err("Non-finite value in the rig");
        }
        Ok(v)
    }
    fn bool(&mut self) -> Result<bool> {
        Ok(self.u8()? != 0)
    }
    fn rgb(&mut self) -> Result<Rgb> {
        Ok([self.f32()?, self.f32()?, self.f32()?])
    }
    /// A count of items each at least [min_size] bytes, bounded by what remains.
    fn count(&mut self, min_size: usize) -> Result<usize> {
        let n = self.u32()? as usize;
        if n.saturating_mul(min_size.max(1)) > self.bytes.len() - self.at {
            return err(format!("Count {} exceeds the rig at byte {}", n, self.at));
        }
        Ok(n)
    }
    fn str(&mut self) -> Result<String> {
        if let Some(strings) = self.strings {
            let i = self.u32()?;
            if i == u32::MAX {
                return Ok(String::new());
            }
            return strings.get(i as usize).cloned().ok_or(Error(format!("Invalid string reference {}", i)));
        }
        let n = self.count(1)?;
        String::from_utf8(self.take(n)?.to_vec()).map_err(|_| Error("Invalid UTF-8 in the rig".into()))
    }
    /// A version 2 array header, after zero padding to a four-byte boundary: its codec and length.
    fn array(&mut self, codecs: &[u8]) -> Result<(u8, usize)> {
        while self.at % 4 != 0 {
            if self.u8()? != 0 {
                return err("Non-zero padding before an array");
            }
        }
        let codec = self.u8()?;
        self.take(3)?;
        if !codecs.contains(&codec) {
            return err(format!("Array codec {} is not allowed here", codec));
        }
        let size = match codec {
            F32 | U32 => 4,
            F16 | UNORM16 | U16 => 2,
            _ => 1,
        };
        Ok((codec, self.count(size)?))
    }
    fn floats(&mut self) -> Result<Vec<f32>> {
        if self.strings.is_none() {
            let n = self.count(4)?;
            return (0..n).map(|_| self.f32()).collect();
        }
        let (codec, n) = self.array(&[F32, F16, UNORM16])?;
        (0..n)
            .map(|_| match codec {
                F32 => self.f32(),
                F16 => Some(container::f16_to_f32(self.u16()?)).filter(|v| v.is_finite()).ok_or(Error("Non-finite value in the rig".into())),
                _ => Ok(self.u16()? as f32 / 65535.0),
            })
            .collect()
    }
    fn indices(&mut self) -> Result<Vec<u32>> {
        if self.strings.is_none() {
            let n = self.count(4)?;
            return (0..n).map(|_| self.u32()).collect();
        }
        let (codec, n) = self.array(&[U16, U32])?;
        (0..n).map(|_| if codec == U16 { self.u16().map(u32::from) } else { self.u32() }).collect()
    }
    fn bytes_field(&mut self) -> Result<Vec<u8>> {
        let n = if self.strings.is_none() { self.count(1)? } else { self.array(&[U8])?.1 };
        Ok(self.take(n)?.to_vec())
    }
    /// The end of a version 2 chunk, which its records must fill exactly.
    fn end(&self, tag: &str) -> Result<()> {
        if self.at != self.bytes.len() {
            return err(format!("Chunk {} has {} bytes its records do not cover", tag, self.bytes.len() - self.at));
        }
        Ok(())
    }
    fn index(&mut self, limit: usize, what: &str) -> Result<usize> {
        let i = self.u32()? as usize;
        if i >= limit {
            return err(format!("Invalid {} reference {}", what, i));
        }
        Ok(i)
    }
    fn optional(&mut self, limit: usize, what: &str) -> Result<Option<usize>> {
        let i = self.i32()?;
        if i < 0 {
            return Ok(None);
        }
        if i as usize >= limit {
            return err(format!("Invalid {} reference {}", what, i));
        }
        Ok(Some(i as usize))
    }
    fn parameter(&mut self) -> Result<usize> {
        self.index(self.parameters, "parameter")
    }

    fn rig(&mut self) -> Result<Rig> {
        if self.take(8)? != b"P2LRT\0\0\0" {
            return err("Not a .p2lrt rig");
        }
        let version = self.u32()?;
        if version != 1 {
            return err(format!("Unsupported .p2lrt version {}", version));
        }
        let canvas = self.canvas()?;
        let parameters = self.parameters()?;
        self.parameters = parameters.len();

        // Sections refer forward (deformers to parts, parts to meshes), so their counts come from a first
        // pass that only skips; reading twice keeps the format free of an index table.
        let start = self.at;
        let deformer_count = self.count(1)?;
        self.deformers = deformer_count;
        self.parts = usize::MAX;
        self.meshes = usize::MAX;
        for _ in 0..deformer_count {
            self.deformer()?;
        }
        let part_count = self.count(1)?;
        for _ in 0..part_count {
            self.part()?;
        }
        let mesh_count = self.count(1)?;
        self.at = start;
        self.parts = part_count;
        self.meshes = mesh_count;

        self.count(1)?;
        let deformers = self.deformers(deformer_count)?;
        self.count(1)?;
        let parts = (0..part_count).map(|_| self.part()).collect::<Result<Vec<_>>>()?;
        self.count(1)?;
        let meshes = (0..mesh_count).map(|_| self.mesh()).collect::<Result<Vec<_>>>()?;
        let glues = self.glues()?;
        let render = self.group(0)?;
        let n = self.count(12)?;
        let mut textures = Vec::with_capacity(n);
        for _ in 0..n {
            textures.push(Texture { width: self.u32()?, height: self.u32()?, kind: TextureKind::Png, data: self.bytes_field()?, uri: String::new() });
        }
        let (physics_fps, physics) = self.physics()?;
        let clips = self.clips()?;
        let roles = self.roles()?;
        if self.at != self.bytes.len() {
            return err("Trailing bytes after the rig");
        }
        Ok(Rig { canvas, parameters, deformers, parts, meshes, glues, render, textures, physics_fps, physics, clips, roles, gui: None, meta: vec![], extensions: Extensions::default() })
    }

    fn canvas(&mut self) -> Result<Canvas> {
        Ok(Canvas {
            width: self.f32()?,
            height: self.f32()?,
            origin_x: self.f32()?,
            origin_y: self.f32()?,
            pixels_per_unit: Some(self.f32()?).filter(|v| *v >= 0.0),
        })
    }

    fn parameters(&mut self) -> Result<Vec<Parameter>> {
        let n = self.count(if self.strings.is_some() { 21 } else { 25 })?;
        let mut parameters = Vec::with_capacity(n);
        for _ in 0..n {
            let id = self.str()?;
            let name = self.str()?;
            let (min, max, default) = (self.f32()?, self.f32()?, self.f32()?);
            let flags = self.u8()?;
            parameters.push(Parameter { id, name, min, max, default, blend: flags & 1 != 0, repeat: flags & 2 != 0 });
        }
        Ok(parameters)
    }

    /// Deformers, each parent before its children.
    fn deformers(&mut self, count: usize) -> Result<Vec<Deformer>> {
        let mut deformers: Vec<Deformer> = Vec::with_capacity(count);
        for i in 0..count {
            let d = self.deformer()?;
            if let Some(parent) = d.parent {
                if parent >= i {
                    return err("A deformer's parent must come before it");
                }
            }
            deformers.push(d);
        }
        Ok(deformers)
    }

    fn glues(&mut self) -> Result<Vec<Glue>> {
        let n = self.count(1)?;
        (0..n).map(|_| self.glue()).collect()
    }

    fn physics(&mut self) -> Result<(f32, Vec<PhysicsGroup>)> {
        let fps = self.f32()?;
        let n = self.count(1)?;
        Ok((fps, (0..n).map(|_| self.physics_group()).collect::<Result<Vec<_>>>()?))
    }

    fn clips(&mut self) -> Result<Vec<Clip>> {
        let n = self.count(1)?;
        (0..n).map(|_| self.clip()).collect()
    }

    fn roles(&mut self) -> Result<Vec<Role>> {
        let n = self.count(8)?;
        let mut roles = Vec::with_capacity(n);
        for _ in 0..n {
            let role = self.str()?;
            let count = self.count(4)?;
            let parameters = (0..count).map(|_| self.parameter()).collect::<Result<Vec<_>>>()?;
            roles.push(Role { role, parameters });
        }
        Ok(roles)
    }

    /// Version 2 texture pages: a kind, the size, then the bytes or a file name.
    fn textures(&mut self) -> Result<Vec<Texture>> {
        let n = self.count(13)?;
        let mut textures = Vec::with_capacity(n);
        for _ in 0..n {
            let kind = match self.u8()? {
                0 => TextureKind::Png,
                1 => TextureKind::Ktx2,
                2 => TextureKind::External,
                k => return err(format!("Unknown texture kind {}", k)),
            };
            let (width, height) = (self.u32()?, self.u32()?);
            let (data, uri) = if kind == TextureKind::External { (vec![], self.str()?) } else { (self.bytes_field()?, String::new()) };
            textures.push(Texture { width, height, kind, data, uri });
        }
        Ok(textures)
    }

    fn gui(&mut self) -> Result<Gui> {
        let n = self.count(12)?;
        let mut snaps = Vec::with_capacity(n);
        for _ in 0..n {
            let parameter = self.parameter()?;
            let keys = self.floats()?;
            if keys.windows(2).any(|w| w[1] < w[0]) {
                return err("Snap values must ascend");
            }
            snaps.push((parameter, keys));
        }
        let n = self.count(8)?;
        let joysticks = (0..n).map(|_| Ok((self.parameter()?, self.parameter()?))).collect::<Result<Vec<_>>>()?;
        let n = self.count(28)?;
        let mut nodes = Vec::with_capacity(n);
        for _ in 0..n {
            let (kind, open, label, _) = (self.u8()?, self.u8()?, self.u8()?, self.u8()?);
            let parameter = self.optional(self.parameters, "parameter")?;
            let (id, name, preset, argb, children) = (self.str()?, self.str()?, self.str()?, self.u32()?, self.u32()? as usize);
            let label = match label {
                0 => GuiLabel::None,
                1 => GuiLabel::Preset(preset),
                2 => GuiLabel::Custom(argb),
                _ => return err("Unknown parameter group label"),
            };
            match kind {
                0 if parameter.is_some() && children == 0 => {}
                1 if parameter.is_none() => {}
                _ => return err("Invalid parameter tree node"),
            }
            nodes.push(GuiNode { parameter, id, name, open: open != 0, label, children });
        }
        // Each node's children follow it; together they must cover the list exactly.
        fn subtree(nodes: &[GuiNode], at: usize, depth: usize) -> Result<usize> {
            if depth > 256 {
                return err("Parameter tree too deep");
            }
            let mut next = at + 1;
            for _ in 0..nodes[at].children {
                if next >= nodes.len() {
                    return err("A parameter group has missing children");
                }
                next = subtree(nodes, next, depth + 1)?;
            }
            Ok(next)
        }
        let mut at = 0;
        while at < nodes.len() {
            at = subtree(&nodes, at, 0)?;
        }
        Ok(Gui { snaps, joysticks, nodes })
    }

    fn extension_header(&mut self) -> Result<ExtensionHeader> {
        let (feature, hooks) = (self.u16()?, self.u16()?);
        let n = self.count(4)?;
        let parameters = (0..n).map(|_| self.parameter()).collect::<Result<Vec<_>>>()?;
        let n = self.count(4)?;
        let physics_groups = (0..n).map(|_| self.u32().map(|v| v as usize)).collect::<Result<Vec<_>>>()?;
        let n = self.count(4)?;
        let deformers = (0..n).map(|_| self.index(self.deformers, "deformer")).collect::<Result<Vec<_>>>()?;
        let n = self.count(12)?;
        let mut meshes = Vec::with_capacity(n);
        for _ in 0..n {
            let mesh = self.index(self.meshes, "mesh")?;
            let count = self.count(4)?;
            let axes = (0..count).map(|_| self.parameter()).collect::<Result<Vec<_>>>()?;
            let count = self.count(4)?;
            let bindings = (0..count).map(|_| self.u32().map(|v| v as usize)).collect::<Result<Vec<_>>>()?;
            meshes.push(MeshOverride { mesh, axes, bindings });
        }
        let n = self.count(4)?;
        let depends_on = (0..n).map(|_| Ok(String::from_utf8_lossy(self.take(4)?).into_owned())).collect::<Result<Vec<_>>>()?;
        Ok(ExtensionHeader { feature, hooks, parameters, physics_groups, deformers, meshes, depends_on })
    }

    /// BONE: the header, the virtual bones, the attachable bones, then the arcs.
    fn bones(&mut self, ext: &mut Extensions) -> Result<()> {
        ext.bone_header = Some(self.extension_header()?);
        let rig_deformers = self.deformers;
        let n = self.count(1)?;
        for i in 0..n {
            // A virtual bone's parent may be any rig deformer or an earlier virtual bone.
            self.deformers = rig_deformers + i;
            let bone = self.deformer()?;
            if !matches!(bone.kind, DeformerKind::Rotation { .. }) {
                return err("A virtual bone must be a rotation");
            }
            ext.virtual_bones.push(bone);
        }
        self.deformers = rig_deformers + n;
        let n = self.count(8)?;
        for _ in 0..n {
            let d = self.index(self.deformers, "deformer")?;
            ext.bones.push((d, self.str()?));
        }
        let n = self.count(16)?;
        for _ in 0..n {
            let (deformer, parameter) = (self.index(self.deformers, "deformer")?, self.parameter()?);
            ext.arcs.push(Arc { deformer, parameter, center: [self.f32()?, self.f32()?] });
        }
        Ok(())
    }

    /// SKIN: the header, then per mesh its axes, bones and optional weights.
    fn skins(&mut self, ext: &mut Extensions) -> Result<()> {
        ext.skin_header = Some(self.extension_header()?);
        let n = self.count(16)?;
        for _ in 0..n {
            let mesh = self.index(self.meshes, "mesh")?;
            let count = self.count(8)?;
            let mut axes = Vec::with_capacity(count);
            for _ in 0..count {
                let parameter = self.parameter()?;
                let keys = self.floats()?;
                if keys.is_empty() || keys.windows(2).any(|w| w[1] < w[0]) {
                    return err("Skin axis keys must ascend");
                }
                axes.push((parameter, keys));
            }
            let count = self.count(4)?;
            let bones = (0..count).map(|_| self.index(self.deformers, "deformer")).collect::<Result<Vec<_>>>()?;
            let (from, to, weight) = (self.indices()?, self.indices()?, self.floats()?);
            if from.len() != to.len() || from.len() != weight.len() || from.iter().chain(&to).any(|b| *b as usize >= bones.len()) {
                return err("Invalid skin weights");
            }
            ext.skins.push(Skin { mesh, axes, bones, from, to, weight });
        }
        Ok(())
    }

    fn meta(&mut self) -> Result<Vec<(String, String)>> {
        let n = self.count(8)?;
        (0..n).map(|_| Ok((self.str()?, self.str()?))).collect()
    }

    fn grid<F>(&mut self, mut form: impl FnMut(&mut Self) -> Result<F>) -> Result<Option<Grid<F>>> {
        let axis_count = self.u16()?;
        if axis_count == 0xFFFF {
            return Ok(None);
        }
        let mut axes = Vec::with_capacity(axis_count as usize);
        for _ in 0..axis_count {
            let parameter = self.parameter()?;
            let keys = self.floats()?;
            if keys.windows(2).any(|w| w[1] < w[0]) {
                return err("Keyform axis keys must ascend");
            }
            axes.push(Axis { parameter, keys });
        }
        let n = self.count(2 * axes.len())?;
        let mut cells = Vec::with_capacity(n);
        for _ in 0..n {
            let coordinate = (0..axes.len()).map(|_| self.u16()).collect::<Result<Vec<_>>>()?;
            cells.push((coordinate, form(self)?));
        }
        Grid::new(axes, cells).map(Some)
    }

    fn channels(&mut self) -> Result<Channels> {
        let n = self.u8()?;
        let mut channels = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let channel = *CHANNELS.get(self.u8()? as usize).ok_or(Error("Unknown channel".into()))?;
            let kind = self.u8()?;
            let grid = self
                .grid(|r| match kind {
                    0 => Ok(ChannelValue::Scalar(r.f32()?)),
                    1 => Ok(ChannelValue::Color(r.rgb()?)),
                    2 => Ok(ChannelValue::Flag(r.bool()?)),
                    _ => err("Unknown channel kind"),
                })?
                .ok_or(Error("A channel without a grid".into()))?;
            channels.push((channel, grid));
        }
        Ok(channels)
    }

    fn bindings<S>(&mut self, mut shape: impl FnMut(&mut Self) -> Result<S>) -> Result<Vec<Binding<S>>> {
        let n = self.u16()?;
        let mut bindings = Vec::with_capacity(n as usize);
        for _ in 0..n {
            let parameter = self.parameter()?;
            let keys = self.floats()?;
            let neutral = self.u32()? as usize;
            if keys.is_empty() || neutral >= keys.len() || keys.windows(2).any(|w| w[1] < w[0]) {
                return err("Invalid blend binding keys");
            }
            let mut shapes = Vec::with_capacity(keys.len());
            for _ in 0..keys.len() {
                shapes.push(if self.bool()? { Some(shape(self)?) } else { None });
            }
            let limit_count = self.u16()?;
            let mut limits = Vec::with_capacity(limit_count as usize);
            for _ in 0..limit_count {
                let parameter = self.parameter()?;
                let points = self.u16()?;
                let points = (0..points).map(|_| Ok((self.f32()?, self.f32()?))).collect::<Result<Vec<_>>>()?;
                limits.push(Limit { parameter, points });
            }
            bindings.push(Binding { parameter, keys, neutral, shapes, limits });
        }
        Ok(bindings)
    }

    fn deformer(&mut self) -> Result<Deformer> {
        let kind = self.u8()?;
        let id = self.str()?;
        let parent = self.optional(self.deformers, "deformer")?;
        let part = self.optional(self.parts, "part")?;
        let flags = self.u8()?;
        let (opacity, multiply, screen) = (self.f32()?, self.rgb()?, self.rgb()?);
        let kind = match kind {
            0 => {
                let columns = self.u32()? as usize;
                let rows = self.u32()? as usize;
                if columns == 0 || rows == 0 || columns > 4096 || rows > 4096 {
                    return err(format!("Warp {} has an invalid lattice size", id));
                }
                let points = (columns + 1) * (rows + 1) * 2;
                let lattice = self.grid(|r| r.floats())?;
                if let Some(grid) = &lattice {
                    if grid.cells.iter().any(|(_, p)| p.len() != points) {
                        return err(format!("Warp {} keyforms do not match its lattice", id));
                    }
                }
                let channels = self.channels()?;
                let shapes = self.bindings(|r| Ok(LatticeShape { points: r.floats()?, opacity: r.f32()?, multiply: r.rgb()?, screen: r.rgb()? }))?;
                if shapes.iter().flat_map(|b| b.shapes.iter().flatten()).any(|s| s.points.len() != points) {
                    return err(format!("Warp {} blend shapes do not match its lattice", id));
                }
                return Ok(Deformer {
                    id, parent, part, visible: flags & 1 != 0, enabled: flags & 2 != 0, opacity, multiply, screen, channels,
                    kind: DeformerKind::Warp { columns, rows, bilinear: flags & 16 != 0, lattice, shapes },
                });
            }
            1 => {
                let base_angle = self.f32()?;
                let pivot = self.grid(|r| Ok(Pivot { x: r.f32()?, y: r.f32()?, angle: r.f32()?, scale: r.f32()? }))?;
                let channels = self.channels()?;
                let shapes = self.bindings(|r| {
                    Ok(PivotShape {
                        pivot: Pivot { x: r.f32()?, y: r.f32()?, angle: r.f32()?, scale: r.f32()? },
                        flip_x: r.bool()?,
                        flip_y: r.bool()?,
                        opacity: r.f32()?,
                        multiply: r.rgb()?,
                        screen: r.rgb()?,
                    })
                })?;
                (DeformerKind::Rotation { base_angle, pivot, flip_x: flags & 4 != 0, flip_y: flags & 8 != 0, shapes }, channels)
            }
            _ => return err("Unknown deformer kind"),
        };
        let (kind, channels) = kind;
        Ok(Deformer { id, parent, part, visible: flags & 1 != 0, enabled: flags & 2 != 0, opacity, multiply, screen, channels, kind })
    }

    fn composite(&mut self) -> Result<Composite> {
        let (blend, alpha_blend) = (self.u8()?, self.u8()?);
        let n = self.count(4)?;
        let masked_by = (0..n).map(|_| self.index(self.meshes, "mesh")).collect::<Result<Vec<_>>>()?;
        let n = self.count(4)?;
        let masked_by_parts = (0..n).map(|_| self.index(self.parts, "part")).collect::<Result<Vec<_>>>()?;
        Ok(Composite { blend, alpha_blend, masked_by, masked_by_parts, invert_mask: self.bool()?, opacity: self.f32()?, multiply: self.rgb()?, screen: self.rgb()? })
    }

    fn part(&mut self) -> Result<Part> {
        let id = self.str()?;
        let name = self.str()?;
        let flags = self.u8()?;
        let group_mode = self.u8()?;
        let draw_order = self.i32()?;
        let n = self.count(5)?;
        let mut children = Vec::with_capacity(n);
        for _ in 0..n {
            children.push(match self.u8()? {
                0 => Child::Part(self.index(self.parts, "part")?),
                1 => Child::Mesh(self.index(self.meshes, "mesh")?),
                _ => return err("Unknown part child"),
            });
        }
        let channels = self.channels()?;
        let composite = self.composite()?;
        let shapes = self.bindings(|r| Ok(PartShape { draw_order: r.f32()?, opacity: r.f32()?, multiply: r.rgb()?, screen: r.rgb()? }))?;
        Ok(Part { id, name, visible: flags & 1 != 0, sketch: flags & 2 != 0, group_mode, draw_order, children, channels, composite, shapes })
    }

    fn mesh(&mut self) -> Result<Mesh> {
        let id = self.str()?;
        let name = self.str()?;
        let parent = self.optional(self.deformers, "deformer")?;
        let (blend, alpha_blend) = (self.u8()?, self.u8()?);
        let n = self.count(4)?;
        let masked_by = (0..n).map(|_| self.index(self.meshes, "mesh")).collect::<Result<Vec<_>>>()?;
        let flags = self.u8()?;
        let page = self.i32()?;
        let geometry = if flags & 8 != 0 {
            let positions = self.floats()?;
            let uvs = self.floats()?;
            let indices = self.indices()?;
            let vertices = positions.len() / 2;
            if positions.len() % 2 != 0 || uvs.len() != positions.len() || indices.len() % 3 != 0 || indices.iter().any(|i| *i as usize >= vertices) {
                return err(format!("Mesh {} has invalid geometry", id));
            }
            Some(Geometry { positions, uvs, indices })
        } else {
            None
        };
        let offsets = self.grid(|r| r.floats())?;
        let channels = self.channels()?;
        let (draw_order, opacity, multiply, screen) = (self.f32()?, self.f32()?, self.rgb()?, self.rgb()?);
        let shapes = self.bindings(|r| Ok(MeshShape { deltas: r.floats()?, draw_order: r.f32()?, opacity: r.f32()?, multiply: r.rgb()?, screen: r.rgb()? }))?;
        let vertices = geometry.as_ref().map_or(0, |g| g.positions.len());
        if shapes.iter().flat_map(|b| b.shapes.iter().flatten()).any(|s| !s.deltas.is_empty() && s.deltas.len() != vertices) {
            return err(format!("Blend shapes of mesh {} do not match its vertices", id));
        }
        Ok(Mesh {
            id, name, parent, blend, alpha_blend, masked_by, invert_mask: flags & 1 != 0, culling: flags & 2 != 0, visible: flags & 4 != 0,
            page, geometry, offsets, channels, draw_order, opacity, multiply, screen, shapes,
        })
    }

    fn glue(&mut self) -> Result<Glue> {
        let id = self.str()?;
        let mesh_a = self.index(self.meshes, "mesh")?;
        let mesh_b = self.index(self.meshes, "mesh")?;
        let intensity = self.f32()?;
        let n = self.count(16)?;
        let mut pairs = Vec::with_capacity(n);
        for _ in 0..n {
            pairs.push(GluePair { a: self.u32()? as usize, b: self.u32()? as usize, weight_a: self.f32()?, weight_b: self.f32()? });
        }
        Ok(Glue { id, mesh_a, mesh_b, intensity, pairs, channels: self.channels()? })
    }

    fn group(&mut self, depth: usize) -> Result<RenderGroup> {
        if depth > 256 {
            return err("Render tree too deep");
        }
        let part = self.optional(self.parts, "part")?;
        let draw_order = self.i32()?;
        let channels = self.channels()?;
        let composite = if self.bool()? { Some(self.composite()?) } else { None };
        let n = self.count(5)?;
        let mut children = Vec::with_capacity(n);
        for _ in 0..n {
            children.push(match self.u8()? {
                1 => RenderNode::Mesh(self.index(self.meshes, "mesh")?),
                0 => RenderNode::Group(self.group(depth + 1)?),
                _ => return err("Unknown render node"),
            });
        }
        Ok(RenderGroup { part, draw_order, channels, composite, children })
    }

    fn source(&mut self) -> Result<PhysicsSource> {
        match self.u8()? {
            0 => Ok(PhysicsSource::X),
            1 => Ok(PhysicsSource::Angle),
            _ => err("Unknown physics source"),
        }
    }

    fn physics_group(&mut self) -> Result<PhysicsGroup> {
        let id = self.str()?;
        let name = self.str()?;
        let n = self.count(10)?;
        let mut inputs = Vec::with_capacity(n);
        for _ in 0..n {
            inputs.push(PhysicsInput { parameter: self.parameter()?, weight: self.f32()?, source: self.source()?, reflect: self.bool()? });
        }
        let n = self.count(18)?;
        let mut outputs = Vec::with_capacity(n);
        for _ in 0..n {
            outputs.push(PhysicsOutput {
                parameter: self.parameter()?,
                vertex: self.u32()? as usize,
                scale: self.f32()?,
                weight: self.f32()?,
                source: self.source()?,
                reflect: self.bool()?,
            });
        }
        let n = self.count(16)?;
        let mut segments = Vec::with_capacity(n);
        for _ in 0..n {
            segments.push(PhysicsSegment { length: self.f32()?, mobility: self.f32()?, delay: self.f32()?, acceleration: self.f32()? });
        }
        let normalization = Normalization {
            position_min: self.f32()?,
            position_default: self.f32()?,
            position_max: self.f32()?,
            angle_min: self.f32()?,
            angle_default: self.f32()?,
            angle_max: self.f32()?,
        };
        if outputs.iter().any(|o| o.vertex > segments.len()) {
            return err(format!("Physics group {} outputs a vertex it does not have", id));
        }
        Ok(PhysicsGroup { id, name, inputs, outputs, segments, normalization })
    }

    fn clip(&mut self) -> Result<Clip> {
        let id = self.str()?;
        let name = self.str()?;
        let group = self.str()?;
        let (duration, fps, looping) = (self.f32()?, self.f32()?, self.bool()?);
        let fade_in = Some(self.f32()?).filter(|v| *v >= 0.0);
        let fade_out = Some(self.f32()?).filter(|v| *v >= 0.0);
        let n = self.count(16)?;
        let mut curves = Vec::with_capacity(n);
        for _ in 0..n {
            let parameter = self.parameter()?;
            let (start_time, start_value) = (self.f32()?, self.f32()?);
            let count = self.count(9)?;
            let mut segments = Vec::with_capacity(count);
            for _ in 0..count {
                let kind = self.u8()?;
                let c = if kind == 1 { [self.f32()?, self.f32()?, self.f32()?, self.f32()?] } else { [0.0; 4] };
                let (time, value) = (self.f32()?, self.f32()?);
                segments.push(match kind {
                    0 => Segment::Linear { time, value },
                    1 => Segment::Bezier { c1: (c[0], c[1]), c2: (c[2], c[3]), time, value },
                    2 => Segment::Stepped { time, value },
                    3 => Segment::InverseStepped { time, value },
                    _ => return err("Unknown curve segment"),
                });
            }
            curves.push(Curve { parameter, start_time, start_value, segments });
        }
        Ok(Clip { id, name, group, duration, fps, looping, fade_in, fade_out, curves })
    }
}

/// Reads a version 2 file: the core chunks into a [Rig], unknown optional chunks skipped.
fn read_chunks(bytes: &[u8], verify_crc: bool) -> Result<Rig> {
    let file = container::read(bytes, verify_crc)?;
    let mut known: Vec<Option<&Chunk>> = vec![None; CHUNKS.len()];
    for chunk in &file.chunks {
        let name = chunk.name();
        let slot = CHUNKS.iter().position(|(tag, _)| *tag == name);
        match slot {
            Some(i) if chunk.version <= CHUNKS[i].1 => {
                if known[i].is_some() {
                    return err(format!("Chunk {} appears twice", name));
                }
                known[i] = Some(chunk);
            }
            _ if chunk.required => return err(format!("This rig requires feature {} version {}", name, chunk.version)),
            _ => {}
        }
    }
    let chunk = |tag: &str| known[CHUNKS.iter().position(|(t, _)| *t == tag).unwrap()].map(|c| &c.data[..]);
    let need = |tag: &str| chunk(tag).ok_or(Error(format!("Missing chunk {}", tag)));

    let strings = read_strings(need("STRS")?)?;
    let strings = Some(&strings[..]);
    // Records refer forward (deformers to parts, parts to meshes); each table starts with its count.
    let count = |data: &[u8]| if data.len() < 4 { err("Truncated chunk") } else { Ok(u32::from_le_bytes(data[..4].try_into().unwrap()) as usize) };
    let (parm, defm, part, mesh) = (need("PARM")?, need("DEFM")?, need("PART")?, need("MESH")?);
    let counts = (count(parm)?, count(defm)?, count(part)?, count(mesh)?);
    let reader = |data| {
        let mut r = Reader::new(data, strings);
        (r.parameters, r.deformers, r.parts, r.meshes) = counts;
        r
    };
    let empty: &[u8] = &[];

    let mut r = reader(need("CANV")?);
    let canvas = r.canvas()?;
    r.end("CANV")?;
    let mut r = reader(parm);
    let parameters = r.parameters()?;
    r.end("PARM")?;
    let mut r = reader(defm);
    r.count(1)?;
    let deformers = r.deformers(counts.1)?;
    r.end("DEFM")?;
    let mut r = reader(part);
    r.count(1)?;
    let parts = (0..counts.2).map(|_| r.part()).collect::<Result<Vec<_>>>()?;
    r.end("PART")?;
    let mut r = reader(mesh);
    r.count(1)?;
    let meshes = (0..counts.3).map(|_| r.mesh()).collect::<Result<Vec<_>>>()?;
    r.end("MESH")?;
    let mut r = reader(need("DRAW")?);
    let render = r.group(0)?;
    r.end("DRAW")?;
    // Optional core tables are empty when absent.
    let mut glues = vec![];
    if let Some(data) = chunk("GLUE") {
        let mut r = reader(data);
        glues = r.glues()?;
        r.end("GLUE")?;
    }
    let mut textures = vec![];
    if let Some(data) = chunk("TEXR") {
        let mut r = reader(data);
        textures = r.textures()?;
        r.end("TEXR")?;
    }
    let (mut physics_fps, mut physics) = (0.0, vec![]);
    if let Some(data) = chunk("PHYS") {
        let mut r = reader(data);
        (physics_fps, physics) = r.physics()?;
        r.end("PHYS")?;
    }
    let mut clips = vec![];
    if let Some(data) = chunk("CLIP") {
        let mut r = reader(data);
        clips = r.clips()?;
        r.end("CLIP")?;
    }
    let mut roles = vec![];
    if let Some(data) = chunk("ROLE") {
        let mut r = reader(data);
        roles = r.roles()?;
        r.end("ROLE")?;
    }
    let mut gui = None;
    if let Some(data) = chunk("PGUI") {
        let mut r = reader(data);
        gui = Some(r.gui()?);
        r.end("PGUI")?;
    }
    let mut r = reader(chunk("META").unwrap_or(empty));
    let meta = if r.bytes.is_empty() { vec![] } else { r.meta()? };
    r.end("META")?;
    let mut extensions = Extensions::default();
    if let Some(data) = chunk("BONE") {
        let mut r = reader(data);
        r.bones(&mut extensions)?;
        r.end("BONE")?;
    }
    if let Some(data) = chunk("SKIN") {
        let mut r = reader(data);
        r.deformers += extensions.virtual_bones.len();
        r.skins(&mut extensions)?;
        r.end("SKIN")?;
    }
    let rig = Rig { canvas, parameters, deformers, parts, meshes, glues, render, textures, physics_fps, physics, clips, roles, gui, meta, extensions };
    rig.validate_extensions()?;
    Ok(rig)
}

/// The string table: a count, `count + 1` ascending offsets into the UTF-8 that follows, then that UTF-8.
fn read_strings(data: &[u8]) -> Result<Vec<String>> {
    let mut r = Reader::new(data, None);
    let n = r.count(4)?;
    let offsets = (0..=n).map(|_| r.u32().map(|v| v as usize)).collect::<Result<Vec<_>>>()?;
    let blob = &data[r.at..];
    if offsets[0] != 0 || offsets[n] != blob.len() || offsets.windows(2).any(|w| w[1] < w[0]) {
        return err("Invalid string table");
    }
    offsets
        .windows(2)
        .map(|w| String::from_utf8(blob[w[0]..w[1]].to_vec()).map_err(|_| Error("Invalid UTF-8 in the string table".into())))
        .collect()
}
