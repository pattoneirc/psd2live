//! Advanced mode: what a rig's extension chunks add over the Cubism-equivalent evaluation, each feature off
//! until a host turns it on. Off, evaluation is exactly the core one.
//!
//! - Skinning (`SKIN`): a skinned mesh's baked keyforms sample its bones at keys and interpolate between
//!   them along chords. The runtime blends the bone frames per vertex (two-bone linear blend skinning) and
//!   adds the difference between that blend now and the same blend interpolated over the keys, which is zero
//!   at every key - where the bake is exact - and the arc's departure from its chord in between.
//! - Exact links (`EXACT_LINKS`): pivots keyed along a circle interpolate on it (see `eval::along_arc`).

use crate::eval::{deformer_frame, mesh_local, span, Transform};
use crate::rig::{Rig, Skin};

pub const SKIN: u32 = 1;
pub const EXACT_LINKS: u32 = 2;
pub const SIM: u32 = 4;
pub const COLLISION: u32 = 8;

/// A 2D affine map: `x' = a x + b y + tx`, `y' = c x + d y + ty`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Affine {
    pub a: f32,
    pub b: f32,
    pub c: f32,
    pub d: f32,
    pub tx: f32,
    pub ty: f32,
}

impl Affine {
    pub const IDENTITY: Affine = Affine { a: 1.0, b: 0.0, c: 0.0, d: 1.0, tx: 0.0, ty: 0.0 };

    /// A rotation deformer's frame as an affine map; a warp has none.
    pub fn of(t: &Transform) -> Option<Affine> {
        match t {
            Transform::Rotation { origin, angle, scale, flip_x, flip_y } => {
                let (sin, cos) = angle.to_radians().sin_cos();
                let (fx, fy) = (if *flip_x { -scale } else { *scale }, if *flip_y { -scale } else { *scale });
                Some(Affine { a: cos * fx, b: -sin * fy, c: sin * fx, d: cos * fy, tx: origin[0], ty: origin[1] })
            }
            Transform::Warp { .. } => None,
        }
    }

    /// `self ∘ other`: [other] first.
    pub fn then(self, o: Affine) -> Affine {
        Affine {
            a: self.a * o.a + self.b * o.c,
            b: self.a * o.b + self.b * o.d,
            c: self.c * o.a + self.d * o.c,
            d: self.c * o.b + self.d * o.d,
            tx: self.a * o.tx + self.b * o.ty + self.tx,
            ty: self.c * o.tx + self.d * o.ty + self.ty,
        }
    }

    pub fn inverse(self) -> Affine {
        let det = self.a * self.d - self.b * self.c;
        let inv = if det.abs() > 1e-12 { 1.0 / det } else { 0.0 };
        let (a, b, c, d) = (self.d * inv, -self.b * inv, -self.c * inv, self.a * inv);
        Affine { a, b, c, d, tx: -(a * self.tx + b * self.ty), ty: -(c * self.tx + d * self.ty) }
    }

    pub fn apply(self, x: f32, y: f32) -> [f32; 2] {
        [self.a * x + self.b * y + self.tx, self.c * x + self.d * y + self.ty]
    }

    fn scaled_sum(self, o: Affine, w: f32) -> Affine {
        Affine { a: self.a + o.a * w, b: self.b + o.b * w, c: self.c + o.c * w, d: self.d + o.d * w, tx: self.tx + o.tx * w, ty: self.ty + o.ty * w }
    }
}

/// A skinned mesh as the runtime drives it.
struct SkinState {
    /// Its index among the rig's skins.
    skin: usize,
    /// Deformers to evaluate for its bones' frames: the bones and everything above them.
    needed: Vec<bool>,
    /// Its vertices at rest in its parent's space.
    rest: Vec<f32>,
    /// Per bone, rest-to-home: its rest frame inverted after the home's rest frame.
    rest_relative: Vec<Affine>,
    from: Vec<usize>,
    to: Vec<usize>,
    weight: Vec<f32>,
}

/// The advanced features of one rig instance and the state they keep.
#[derive(Default)]
pub struct Advanced {
    enabled: u32,
    skins: Vec<SkinState>,
    /// The skin of each mesh, by index into [skins].
    skin_of: Vec<Option<usize>>,
    /// The arc of each deformer, by index into the rig's arcs.
    arc_of: Vec<Option<usize>>,
    cells: Vec<(usize, f32)>,
}

impl Advanced {
    pub fn new() -> Advanced {
        Advanced::default()
    }

    /// The features [rig]'s extensions offer.
    pub fn available(rig: &Rig) -> u32 {
        let ext = &rig.extensions;
        (if ext.skins.is_empty() { 0 } else { SKIN }) | (if ext.arcs.is_empty() { 0 } else { EXACT_LINKS })
    }

    pub fn enabled(&self) -> u32 {
        self.enabled
    }

    /// Turns on [features] that [rig] offers, the rest off; returns those now on.
    pub fn set(&mut self, rig: &Rig, features: u32) -> u32 {
        let features = features & Advanced::available(rig);
        self.arc_of = vec![None; rig.deformer_count_with_bones()];
        if features & EXACT_LINKS != 0 {
            for (i, arc) in rig.extensions.arcs.iter().enumerate() {
                self.arc_of[arc.deformer] = Some(i);
            }
        }
        // The arcs shape the bone frames the skins are fitted to.
        self.enabled = features & EXACT_LINKS;
        self.skins.clear();
        self.skin_of = vec![None; rig.meshes.len()];
        if features & SKIN != 0 {
            for (i, skin) in rig.extensions.skins.iter().enumerate() {
                if let Some(state) = self.prepare(rig, i, skin) {
                    self.skin_of[skin.mesh] = Some(self.skins.len());
                    self.skins.push(state);
                }
            }
        }
        self.enabled = features;
        features
    }

    /// H1: parameters an extension evaluates at their defaults while it is on. None of this rig's yet.
    pub(crate) fn override_parameters(&self, _rig: &Rig, _values: &mut [f32], _defaults: &[f32]) {}

    /// Bone [d]'s canvas frame at [values] (normalized), a rig deformer or a virtual bone.
    pub fn bone_frame(&mut self, rig: &Rig, values: &[f32], d: usize) -> Option<Affine> {
        let mut needed = vec![false; rig.deformer_count_with_bones()];
        let mut at = Some(d);
        while let Some(i) = at {
            needed[i] = true;
            at = rig.deformer_or_bone(i).parent;
        }
        let defaults: Vec<f32> = rig.parameters.iter().map(|p| p.normalize(p.default)).collect();
        let mut cells = std::mem::take(&mut self.cells);
        let frames = self.frames(rig, values, &defaults, &needed, &mut cells);
        self.cells = cells;
        frames[d].as_ref().and_then(Affine::of)
    }

    /// H2: the arc deformer [d] follows, with exact links on.
    pub(crate) fn arc<'a>(&self, rig: &'a Rig, d: usize) -> Option<&'a crate::rig::Arc> {
        if self.enabled & EXACT_LINKS == 0 {
            return None;
        }
        self.arc_of.get(d).copied().flatten().map(|i| &rig.extensions.arcs[i])
    }

    /// The frames of the [needed] deformers and virtual bones at [values].
    fn frames(&self, rig: &Rig, values: &[f32], defaults: &[f32], needed: &[bool], cells: &mut Vec<(usize, f32)>) -> Vec<Option<Transform>> {
        let mut frames: Vec<Option<Transform>> = vec![None; rig.deformer_count_with_bones()];
        for i in 0..frames.len() {
            if !needed[i] {
                continue;
            }
            let d = rig.deformer_or_bone(i);
            let parent = d.parent.and_then(|p| frames[p].as_ref());
            frames[i] = Some(deformer_frame(d, parent, values, defaults, cells, self.arc(rig, i)).0);
        }
        frames
    }

    /// Per bone, where it carries a vertex at rest in the home's space now: home now inverted, bone now, rest-to-home.
    fn carry(state: &SkinState, skin: &Skin, home: usize, frame: impl Fn(usize) -> Option<Affine>) -> Option<Vec<Affine>> {
        let home_now = frame(home)?.inverse();
        skin.bones.iter().zip(&state.rest_relative).map(|(b, rest)| Some(home_now.then(frame(*b)?).then(*rest))).collect()
    }

    /// The skin's evaluation data, its weights fitted to the baked keyforms when the file gives none.
    fn prepare(&mut self, rig: &Rig, index: usize, skin: &Skin) -> Option<SkinState> {
        let mesh = &rig.meshes[skin.mesh];
        let home = mesh.parent?;
        let mut needed = vec![false; rig.deformer_count_with_bones()];
        for &start in skin.bones.iter().chain([&home]) {
            let mut at = Some(start);
            while let Some(d) = at {
                needed[d] = true;
                at = rig.deformer_or_bone(d).parent;
            }
        }
        let defaults = rig.defaults();
        let mut cells = std::mem::take(&mut self.cells);
        let rest_frames = self.frames(rig, &defaults, &defaults, &needed, &mut cells);
        let affine = |frames: &[Option<Transform>], d: usize| frames[d].as_ref().and_then(Affine::of);
        let home_rest = affine(&rest_frames, home)?;
        let rest_relative = skin.bones.iter().map(|b| Some(affine(&rest_frames, *b)?.inverse().then(home_rest))).collect::<Option<Vec<_>>>()?;
        let mut rest = Vec::new();
        mesh_local(mesh, &defaults, &defaults, &mut cells, &mut rest);
        let mut state = SkinState { skin: index, needed, rest, rest_relative, from: vec![], to: vec![], weight: vec![] };
        if skin.from.is_empty() {
            self.fit(rig, skin, home, &mut state, &defaults, &mut cells);
        } else {
            state.from = skin.from.iter().map(|v| *v as usize).collect();
            state.to = skin.to.iter().map(|v| *v as usize).collect();
            state.weight = skin.weight.clone();
        }
        self.cells = cells;
        Some(state)
    }

    /// Two-bone weights per vertex that best carry its rest position to where the bake puts it at every
    /// combination of the axis keys (up to 1024 of them; beyond that, each axis on its own).
    fn fit(&self, rig: &Rig, skin: &Skin, home: usize, state: &mut SkinState, defaults: &[f32], cells: &mut Vec<(usize, f32)>) {
        let mesh = &rig.meshes[skin.mesh];
        let combinations: usize = skin.axes.iter().map(|(_, k)| k.len()).product();
        let mut samples: Vec<Vec<f32>> = Vec::new();
        if combinations <= 1024 {
            for mut i in 0..combinations {
                let mut values = defaults.to_vec();
                for (parameter, keys) in &skin.axes {
                    values[*parameter] = keys[i % keys.len()];
                    i /= keys.len();
                }
                samples.push(values);
            }
        } else {
            for (parameter, keys) in &skin.axes {
                for key in keys {
                    let mut values = defaults.to_vec();
                    values[*parameter] = *key;
                    samples.push(values);
                }
            }
        }
        let vertices = state.rest.len() / 2;
        let bones = skin.bones.len();
        // Where each bone carries each vertex, and where the bake has it, per sample.
        let mut carried = vec![0.0f32; samples.len() * bones * vertices * 2];
        let mut baked = vec![0.0f32; samples.len() * vertices * 2];
        let mut local = Vec::new();
        for (s, values) in samples.iter().enumerate() {
            let values: Vec<f32> = rig.parameters.iter().zip(values).map(|(p, v)| p.normalize(*v)).collect();
            let frames = self.frames(rig, &values, defaults, &state.needed, cells);
            let Some(carry) = Advanced::carry(state, skin, home, |d| frames[d].as_ref().and_then(Affine::of)) else { return };
            mesh_local(mesh, &values, defaults, cells, &mut local);
            baked[s * vertices * 2..(s + 1) * vertices * 2].copy_from_slice(&local);
            for (b, m) in carry.iter().enumerate() {
                for v in 0..vertices {
                    let q = m.apply(state.rest[v * 2], state.rest[v * 2 + 1]);
                    let at = ((s * bones + b) * vertices + v) * 2;
                    carried[at] = q[0];
                    carried[at + 1] = q[1];
                }
            }
        }
        let home_index = skin.bones.iter().position(|b| *b == home).unwrap_or(0);
        for v in 0..vertices {
            let mut best = (f32::INFINITY, home_index, home_index, 0.0f32);
            for i in 0..bones {
                for j in i..bones {
                    let (mut num, mut den) = (0.0f32, 0.0f32);
                    for s in 0..samples.len() {
                        let xi = ((s * bones + i) * vertices + v) * 2;
                        let xj = ((s * bones + j) * vertices + v) * 2;
                        let q = (s * vertices + v) * 2;
                        for k in 0..2 {
                            let (e, r) = (carried[xj + k] - carried[xi + k], baked[q + k] - carried[xi + k]);
                            num += r * e;
                            den += e * e;
                        }
                    }
                    let w = if i == j || den <= 1e-12 { 0.0 } else { (num / den).clamp(0.0, 1.0) };
                    let mut error = 0.0f32;
                    for s in 0..samples.len() {
                        let xi = ((s * bones + i) * vertices + v) * 2;
                        let xj = ((s * bones + j) * vertices + v) * 2;
                        let q = (s * vertices + v) * 2;
                        for k in 0..2 {
                            let r = carried[xi + k] + (carried[xj + k] - carried[xi + k]) * w - baked[q + k];
                            error += r * r;
                        }
                    }
                    // Rigid to one bone wins ties: fewer moving parts.
                    if error < best.0 - 1e-6 {
                        best = (error, i, j, w);
                    }
                }
            }
            state.from.push(best.1);
            state.to.push(best.2);
            state.weight.push(best.3);
        }
    }

    /// H4: adds to mesh [m]'s vertices (in its parent's space) the skin's departure from its own interpolation
    /// over the axis keys.
    pub(crate) fn correct_mesh(&mut self, rig: &Rig, m: usize, values: &[f32], defaults: &[f32], out: &mut [f32]) {
        if self.enabled & SKIN == 0 {
            return;
        }
        let Some(index) = self.skin_of.get(m).copied().flatten() else { return };
        let state = &self.skins[index];
        let skin = &rig.extensions.skins[state.skin];
        let home = rig.meshes[m].parent.expect("a skinned mesh hangs from its home");
        let mut cells = std::mem::take(&mut self.cells);
        // The same frames the evaluation has for the rig's deformers, and the virtual bones' too.
        let now = self.frames(rig, values, defaults, &state.needed, &mut cells);
        self.cells = cells;
        let Some(current) = Advanced::carry(state, skin, home, |d| now[d].as_ref().and_then(Affine::of)) else { return };
        // The same blend at each corner of the axis keys around the values, weighted as the bake interpolates.
        let spans: Vec<_> = skin.axes.iter().map(|(p, keys)| (*p, keys, span(keys, values[*p]))).collect();
        let mut interpolated = vec![Affine { a: 0.0, b: 0.0, c: 0.0, d: 0.0, tx: 0.0, ty: 0.0 }; skin.bones.len()];
        let mut cells = std::mem::take(&mut self.cells);
        for corner in 0..(1usize << spans.len().min(16)) {
            let mut weight = 1.0f32;
            let mut corner_values = values.to_vec();
            for (i, (parameter, keys, s)) in spans.iter().enumerate() {
                let upper = corner >> i & 1 == 1;
                if upper && s.hi == s.lo {
                    weight = 0.0;
                    break;
                }
                weight *= if upper { s.t } else { 1.0 - s.t };
                corner_values[*parameter] = keys[if upper { s.hi } else { s.lo }];
            }
            if weight <= 0.0 {
                continue;
            }
            let frames = self.frames(rig, &corner_values, defaults, &state.needed, &mut cells);
            let Some(carry) = Advanced::carry(state, skin, home, |d| frames[d].as_ref().and_then(Affine::of)) else {
                self.cells = cells;
                return;
            };
            for (sum, m) in interpolated.iter_mut().zip(carry) {
                *sum = sum.scaled_sum(m, weight);
            }
        }
        self.cells = cells;
        let difference: Vec<Affine> = current.iter().zip(&interpolated).map(|(c, i)| c.scaled_sum(*i, -1.0)).collect();
        for v in 0..state.rest.len() / 2 {
            let (x, y) = (state.rest[v * 2], state.rest[v * 2 + 1]);
            let a = difference[state.from[v]].apply(x, y);
            let b = difference[state.to[v]].apply(x, y);
            let w = state.weight[v];
            out[v * 2] += a[0] + (b[0] - a[0]) * w;
            out[v * 2 + 1] += a[1] + (b[1] - a[1]) * w;
        }
    }
}
