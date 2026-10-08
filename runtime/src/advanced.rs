//! Advanced mode: what a rig's extension chunks add over the Cubism-equivalent evaluation, each feature off
//! until a host turns it on. Off, evaluation is exactly the core one.
//!
//! - Skinning (`SKIN`): a skinned mesh's baked keyforms sample its bones at keys and interpolate between
//!   them along chords. The runtime blends the bone frames per vertex (two-bone linear blend skinning) and
//!   adds the difference between that blend now and the same blend interpolated over the keys, which is zero
//!   at every key - where the bake is exact - and the arc's departure from its chord in between.
//! - Exact links (`EXACT_LINKS`): pivots keyed along a circle interpolate on it (see `eval::along_arc`).
//! - Simulation (`SIM`): cloth and hair the bake reduced to pendulum-driven keyforms run live in the editor's
//!   XPBD solver (`sim`). The baked mode parameters stay at their defaults, their pendulums are skipped and the
//!   static corrections are taken back out, so the evaluated targets are the scene's goals; each update then
//!   steps the scene at its own rate and draws the particles in their place.
//! - Collision (`COLLISION`): the simulations' colliders, placed each step from the pose.

use crate::eval::{deformer_frame, mesh_local, span, Pose, Transform};
use crate::rig::{Rig, Skin};
use crate::sim::{self, PlacedCollider, SimState, Solver};

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

/// One simulation as it runs: its particles, and the time not yet stepped.
struct SimRun {
    state: SimState,
    started: bool,
    remain: f32,
    /// Firmly pinned particles, whose anchors give the body's turn, and those anchors at rest.
    frame: Vec<usize>,
    frame_rest: Vec<f32>,
    /// World positions after the step before last and after the last one; drawn between them.
    previous: Vec<f32>,
    current: Vec<f32>,
}

/// Steps one update may run before the rest of a long frame is dropped.
const MAX_SIM_STEPS: f32 = 4.0;

/// The advanced features of one rig instance and the state they keep.
#[derive(Default)]
pub struct Advanced {
    runs: Vec<SimRun>,
    /// Every simulation's colliders as the last step placed them, world space.
    placed: Vec<PlacedCollider>,
    /// Wind the host adds to every simulation, world space (y up).
    wind: [f32; 2],
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
        (if ext.skins.is_empty() { 0 } else { SKIN })
            | (if ext.arcs.is_empty() { 0 } else { EXACT_LINKS })
            | (if ext.simulations.is_empty() { 0 } else { SIM })
            | (if ext.simulations.iter().any(|s| !s.colliders.is_empty()) { COLLISION } else { 0 })
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
        // Collision only acts inside a simulation.
        let features = if features & SIM == 0 { features & !COLLISION } else { features };
        self.runs = if features & SIM == 0 { Vec::new() } else { rig.extensions.simulations.iter().map(SimRun::new).collect() };
        self.enabled = features;
        features
    }

    /// Starts every simulation again from the pose of its next update.
    pub fn reset_simulations(&mut self) {
        for run in &mut self.runs {
            run.started = false;
        }
    }

    /// Wind every simulation feels besides its own, canvas pixels per second² (y down).
    pub fn set_wind(&mut self, x: f32, y: f32) {
        self.wind = [x, -y];
    }

    /// H1: the baked mode parameters, held at their defaults while the simulations run.
    pub(crate) fn override_parameters(&self, rig: &Rig, values: &mut [f32], defaults: &[f32]) {
        if self.enabled & SIM == 0 {
            return;
        }
        for (parameters, _) in &rig.extensions.simulation_overrides {
            for p in parameters {
                values[*p] = defaults[*p];
            }
        }
    }

    /// H1: the physics groups the simulations replace, to skip; empty with simulation off.
    pub fn skipped_physics(&self, rig: &Rig) -> Vec<bool> {
        let mut skip = Vec::new();
        if self.enabled & SIM != 0 {
            skip = vec![false; rig.physics.len()];
            for (_, groups) in &rig.extensions.simulation_overrides {
                for g in groups {
                    skip[*g] = true;
                }
            }
        }
        skip
    }

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

    /// H4: the static corrections the simulations' bakes added to mesh [m], taken back out (they interpolate
    /// linearly between their keys, as the bake wrote them into the keyforms).
    fn without_statics(rig: &Rig, m: usize, values: &[f32], out: &mut [f32]) {
        for sim in &rig.extensions.simulations {
            for (parameter, keys, offsets) in &sim.statics {
                let Some((_, per)) = offsets.iter().find(|(mesh, _)| *mesh == m) else { continue };
                let s = span(keys, values[*parameter]);
                for (o, (a, b)) in out.iter_mut().zip(per[s.lo].iter().zip(&per[s.hi])) {
                    *o -= a + (b - a) * s.t;
                }
            }
        }
    }

    /// H6: steps every simulation by [dt] from the evaluated [pose] - its targets there are the goals - and
    /// draws the particles over them. [values] are the evaluation's (normalized).
    pub fn step_simulations(&mut self, rig: &Rig, values: &[f32], dt: f32, pose: &mut Pose) {
        if self.enabled & SIM == 0 {
            return;
        }
        let collide = self.enabled & COLLISION != 0;
        self.placed.clear();
        for k in 0..self.runs.len() {
            let sim = &rig.extensions.simulations[k];
            let colliders = if collide { self.place_colliders(rig, sim, values, pose) } else { Vec::new() };
            let run = &mut self.runs[k];
            if !run.place(sim, pose) {
                continue;
            }
            self.placed.extend_from_slice(&colliders);
            let run = &mut self.runs[k];
            run.state.colliders = colliders;
            if !run.started {
                run.start(sim);
            } else if dt > 0.0 {
                run.state.frame_angle = sim::frame_angle(&run.frame, &run.frame_rest, &run.state);
                let h = 1.0 / sim.fps;
                run.remain = (run.remain + dt).min(h * MAX_SIM_STEPS);
                while run.remain >= h {
                    std::mem::swap(&mut run.previous, &mut run.current);
                    let wind = [sim.wind[0] + self.wind[0], sim.wind[1] + self.wind[1]];
                    Solver { sim, state: &mut run.state, wind }.step(h);
                    run.capture();
                    run.remain -= h;
                }
            }
            run.draw(sim, pose, sim.fps);
        }
    }

    /// Draws the simulations' last state over [pose] without stepping, as an evaluation without time does.
    pub fn show_simulations(&self, rig: &Rig, pose: &mut Pose) {
        if self.enabled & SIM == 0 {
            return;
        }
        for (run, sim) in self.runs.iter().zip(&rig.extensions.simulations) {
            if run.started {
                run.draw(sim, pose, sim.fps);
            }
        }
    }

    /// The colliders as the last update placed them, in canvas pixels (y down): a, b, radius a, radius b.
    pub fn placed_colliders(&self) -> impl Iterator<Item = [f32; 6]> + '_ {
        self.placed.iter().map(|c| [c.a[0], -c.a[1], c.b[0], -c.b[1], c.radius_a, c.radius_b])
    }

    /// The particle positions of simulation [k] after its last step, world space; for tests and tools.
    pub fn simulation_state(&self, k: usize) -> Option<(&[f32], &[f32])> {
        self.runs.get(k).filter(|r| r.started).map(|r| (&r.state.x[..], &r.state.y[..]))
    }

    /// The colliders [sim] uses, placed in world space at this pose.
    fn place_colliders(&mut self, rig: &Rig, sim: &sim::Simulation, values: &[f32], pose: &Pose) -> Vec<PlacedCollider> {
        let world = |p: [f32; 2]| [p[0], -p[1]];
        sim.colliders.iter().filter_map(|&c| {
            let c = &rig.extensions.colliders[c];
            let (a, b) = match (c.mesh, c.deformer) {
                (Some(m), _) => {
                    let v = &pose.vertices[m];
                    ([v[c.vertex_a * 2], v[c.vertex_a * 2 + 1]], [v[c.vertex_b * 2], v[c.vertex_b * 2 + 1]])
                }
                (None, Some(d)) if d < pose.deformers.len() => (pose.deformers[d].apply(c.a[0], c.a[1]), pose.deformers[d].apply(c.b[0], c.b[1])),
                (None, Some(d)) => {
                    let frame = self.bone_frame(rig, values, d)?;
                    (frame.apply(c.a[0], c.a[1]), frame.apply(c.b[0], c.b[1]))
                }
                (None, None) => (c.a, c.b),
            };
            let b = if c.capsule { b } else { a };
            Some(PlacedCollider::of(c, world(a), world(b)))
        }).collect()
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
        if self.enabled & SIM != 0 {
            Advanced::without_statics(rig, m, values, out);
        }
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

impl SimRun {
    fn new(sim: &sim::Simulation) -> SimRun {
        SimRun {
            state: SimState::new(sim),
            started: false,
            remain: 0.0,
            frame: (0..sim.particles()).filter(|i| sim.pin_weight[*i] >= 0.5).collect(),
            frame_rest: Vec::new(),
            previous: Vec::new(),
            current: Vec::new(),
        }
    }

    /// Goals and anchors from [pose]: each target vertex where the rig puts it, each pin on its own vertex or the
    /// vertex it follows. False when a target is missing from the pose.
    fn place(&mut self, sim: &sim::Simulation, pose: &Pose) -> bool {
        let s = &mut self.state;
        for (k, &(mesh, first)) in sim.targets.iter().enumerate() {
            let end = sim.targets.get(k + 1).map_or(sim.particles(), |t| t.1);
            let v = &pose.vertices[mesh];
            if v.len() < (end - first) * 2 {
                return false;
            }
            for j in 0..end - first {
                s.goal_x[first + j] = v[j * 2];
                s.goal_y[first + j] = -v[j * 2 + 1];
            }
        }
        for i in 0..sim.particles() {
            let (x, y) = match sim.anchors[i] {
                Some((m, v)) => (pose.vertices[m][v * 2], -pose.vertices[m][v * 2 + 1]),
                None => (s.goal_x[i], s.goal_y[i]),
            };
            s.anchor_x[i] = x;
            s.anchor_y[i] = y;
        }
        true
    }

    /// At rest on the goals, the anchors' rest kept for the body's turn, as the editor's scene resets.
    fn start(&mut self, _sim: &sim::Simulation) {
        let positions: Vec<f32> = self.state.goal_x.iter().zip(&self.state.goal_y).flat_map(|(x, y)| [*x, *y]).collect();
        let (anchor_x, anchor_y) = (self.state.anchor_x.clone(), self.state.anchor_y.clone());
        let colliders = std::mem::take(&mut self.state.colliders);
        self.state.reset(&positions);
        self.state.anchor_x = anchor_x;
        self.state.anchor_y = anchor_y;
        self.state.colliders = colliders;
        self.frame_rest = self.frame.iter().flat_map(|&i| [self.state.anchor_x[i], self.state.anchor_y[i]]).collect();
        self.state.frame_angle = 0.0;
        self.state.settle();
        self.started = true;
        self.remain = 0.0;
        self.capture();
        self.previous = self.current.clone();
    }

    fn capture(&mut self) {
        self.current.clear();
        self.current.extend(self.state.x.iter().zip(&self.state.y).flat_map(|(x, y)| [*x, *y]));
    }

    /// The particles over the targets in [pose], between the last two steps by the time left over.
    fn draw(&self, sim: &sim::Simulation, pose: &mut Pose, fps: f32) {
        let alpha = (self.remain * fps).clamp(0.0, 1.0);
        for (k, &(mesh, first)) in sim.targets.iter().enumerate() {
            let end = sim.targets.get(k + 1).map_or(sim.particles(), |t| t.1);
            let out = &mut pose.vertices[mesh];
            for j in 0..end - first {
                let i = (first + j) * 2;
                let (px, py) = (self.previous[i], self.previous[i + 1]);
                let (cx, cy) = (self.current[i], self.current[i + 1]);
                out[j * 2] = px + (cx - px) * alpha;
                out[j * 2 + 1] = -(py + (cy - py) * alpha);
            }
        }
    }
}
