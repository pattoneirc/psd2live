//! The `.p2lrt` rig and its reader. The layout is documented with the writer,
//! `targets/runtime/.../P2lrt.kt`; every reference is an index and parents come before children.

use std::fmt;

pub const VERSION: u32 = 1;

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

#[derive(Debug, Clone)]
pub struct Canvas {
    pub width: f32,
    pub height: f32,
    pub origin_x: f32,
    pub origin_y: f32,
    pub pixels_per_unit: Option<f32>,
}

#[derive(Debug, Clone)]
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

#[derive(Debug, Clone)]
pub struct Axis {
    pub parameter: usize,
    pub keys: Vec<f32>,
}

/// Forms keyed over the cartesian product of parameter axes; cells may be sparse.
#[derive(Debug, Clone)]
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

#[derive(Debug, Clone, Copy)]
pub enum ChannelValue {
    Scalar(f32),
    Color(Rgb),
    Flag(bool),
}

pub type Channels = Vec<(Channel, Grid<ChannelValue>)>;

#[derive(Debug, Clone)]
pub struct Limit {
    pub parameter: usize,
    pub points: Vec<(f32, f32)>,
}

/// An additive shape keyed on one parameter; `shapes` has one entry per key.
#[derive(Debug, Clone)]
pub struct Binding<S> {
    pub parameter: usize,
    pub keys: Vec<f32>,
    pub neutral: usize,
    pub shapes: Vec<Option<S>>,
    pub limits: Vec<Limit>,
}

#[derive(Debug, Clone)]
pub struct LatticeShape {
    pub points: Vec<f32>,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone, Copy)]
pub struct Pivot {
    pub x: f32,
    pub y: f32,
    pub angle: f32,
    pub scale: f32,
}

#[derive(Debug, Clone)]
pub struct PivotShape {
    pub pivot: Pivot,
    pub flip_x: bool,
    pub flip_y: bool,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone)]
pub struct MeshShape {
    pub deltas: Vec<f32>,
    pub draw_order: f32,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone)]
pub struct PartShape {
    pub draw_order: f32,
    pub opacity: f32,
    pub multiply: Rgb,
    pub screen: Rgb,
}

#[derive(Debug, Clone)]
pub enum DeformerKind {
    Warp { columns: usize, rows: usize, bilinear: bool, lattice: Option<Grid<Vec<f32>>>, shapes: Vec<Binding<LatticeShape>> },
    Rotation { base_angle: f32, pivot: Option<Grid<Pivot>>, flip_x: bool, flip_y: bool, shapes: Vec<Binding<PivotShape>> },
}

#[derive(Debug, Clone)]
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

#[derive(Debug, Clone)]
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

#[derive(Debug, Clone)]
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

#[derive(Debug, Clone)]
pub struct Geometry {
    pub positions: Vec<f32>,
    pub uvs: Vec<f32>,
    pub indices: Vec<u32>,
}

#[derive(Debug, Clone)]
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

#[derive(Debug, Clone, Copy)]
pub struct GluePair {
    pub a: usize,
    pub b: usize,
    pub weight_a: f32,
    pub weight_b: f32,
}

#[derive(Debug, Clone)]
pub struct Glue {
    pub id: String,
    pub mesh_a: usize,
    pub mesh_b: usize,
    pub intensity: f32,
    pub pairs: Vec<GluePair>,
    pub channels: Channels,
}

#[derive(Debug, Clone)]
pub enum RenderNode {
    Mesh(usize),
    Group(RenderGroup),
}

#[derive(Debug, Clone)]
pub struct RenderGroup {
    pub part: Option<usize>,
    pub draw_order: i32,
    pub channels: Channels,
    pub composite: Option<Composite>,
    pub children: Vec<RenderNode>,
}

#[derive(Debug, Clone)]
pub struct Texture {
    pub width: u32,
    pub height: u32,
    pub png: Vec<u8>,
}

/// What a physics input drives or an output reads: sideways travel, or tilt and segment angle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PhysicsSource {
    X,
    Angle,
}

#[derive(Debug, Clone)]
pub struct PhysicsInput {
    pub parameter: usize,
    pub weight: f32,
    pub source: PhysicsSource,
    pub reflect: bool,
}

#[derive(Debug, Clone)]
pub struct PhysicsOutput {
    pub parameter: usize,
    pub vertex: usize,
    pub scale: f32,
    pub weight: f32,
    pub source: PhysicsSource,
    pub reflect: bool,
}

#[derive(Debug, Clone, Copy)]
pub struct PhysicsSegment {
    pub length: f32,
    pub mobility: f32,
    pub delay: f32,
    pub acceleration: f32,
}

#[derive(Debug, Clone, Copy)]
pub struct Normalization {
    pub position_min: f32,
    pub position_default: f32,
    pub position_max: f32,
    pub angle_min: f32,
    pub angle_default: f32,
    pub angle_max: f32,
}

#[derive(Debug, Clone)]
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

#[derive(Debug, Clone)]
pub struct Curve {
    pub parameter: usize,
    pub start_time: f32,
    pub start_value: f32,
    pub segments: Vec<Segment>,
}

#[derive(Debug, Clone)]
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

#[derive(Debug, Clone)]
pub struct Role {
    pub role: String,
    pub parameters: Vec<usize>,
}

#[derive(Debug, Clone)]
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
}

impl Rig {
    pub fn read(bytes: &[u8]) -> Result<Rig> {
        let mut reader = Reader { bytes, at: 0, parameters: 0, deformers: 0, parts: 0, meshes: 0 };
        reader.rig()
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
}

impl<'a> Reader<'a> {
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
        let n = self.count(1)?;
        String::from_utf8(self.take(n)?.to_vec()).map_err(|_| Error("Invalid UTF-8 in the rig".into()))
    }
    fn floats(&mut self) -> Result<Vec<f32>> {
        let n = self.count(4)?;
        (0..n).map(|_| self.f32()).collect()
    }
    fn bytes_field(&mut self) -> Result<Vec<u8>> {
        let n = self.count(1)?;
        Ok(self.take(n)?.to_vec())
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
        if version != VERSION {
            return err(format!("Unsupported .p2lrt version {}", version));
        }
        let canvas = Canvas {
            width: self.f32()?,
            height: self.f32()?,
            origin_x: self.f32()?,
            origin_y: self.f32()?,
            pixels_per_unit: Some(self.f32()?).filter(|v| *v >= 0.0),
        };
        let n = self.count(25)?;
        let mut parameters = Vec::with_capacity(n);
        for _ in 0..n {
            let id = self.str()?;
            let name = self.str()?;
            let (min, max, default) = (self.f32()?, self.f32()?, self.f32()?);
            let flags = self.u8()?;
            parameters.push(Parameter { id, name, min, max, default, blend: flags & 1 != 0, repeat: flags & 2 != 0 });
        }
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
        let mut deformers: Vec<Deformer> = Vec::with_capacity(deformer_count);
        for i in 0..deformer_count {
            let d = self.deformer()?;
            if let Some(parent) = d.parent {
                if parent >= i {
                    return err("A deformer's parent must come before it");
                }
            }
            deformers.push(d);
        }
        self.count(1)?;
        let parts = (0..part_count).map(|_| self.part()).collect::<Result<Vec<_>>>()?;
        self.count(1)?;
        let meshes = (0..mesh_count).map(|_| self.mesh()).collect::<Result<Vec<_>>>()?;
        let n = self.count(1)?;
        let mut glues = Vec::with_capacity(n);
        for _ in 0..n {
            let g = self.glue()?;
            for p in &g.pairs {
                if p.a >= meshes[g.mesh_a].vertex_count() || p.b >= meshes[g.mesh_b].vertex_count() {
                    return err(format!("Glue {} pairs a vertex its meshes do not have", g.id));
                }
            }
            glues.push(g);
        }
        let render = self.group(0)?;
        let n = self.count(12)?;
        let mut textures = Vec::with_capacity(n);
        for _ in 0..n {
            textures.push(Texture { width: self.u32()?, height: self.u32()?, png: self.bytes_field()? });
        }
        let physics_fps = self.f32()?;
        let n = self.count(1)?;
        let physics = (0..n).map(|_| self.physics_group()).collect::<Result<Vec<_>>>()?;
        let n = self.count(1)?;
        let clips = (0..n).map(|_| self.clip()).collect::<Result<Vec<_>>>()?;
        let n = self.count(8)?;
        let mut roles = Vec::with_capacity(n);
        for _ in 0..n {
            let role = self.str()?;
            let count = self.count(4)?;
            let parameters = (0..count).map(|_| self.parameter()).collect::<Result<Vec<_>>>()?;
            roles.push(Role { role, parameters });
        }
        if self.at != self.bytes.len() {
            return err("Trailing bytes after the rig");
        }
        for mesh in &meshes {
            if let (Some(g), Some(offsets)) = (&mesh.geometry, &mesh.offsets) {
                if offsets.cells.iter().any(|(_, d)| !d.is_empty() && d.len() != g.positions.len()) {
                    return err(format!("Offsets of mesh {} do not match its vertices", mesh.id));
                }
            }
        }
        Ok(Rig { canvas, parameters, deformers, parts, meshes, glues, render, textures, physics_fps, physics, clips, roles })
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
            let n = self.count(4)?;
            let indices = (0..n).map(|_| self.u32()).collect::<Result<Vec<_>>>()?;
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
