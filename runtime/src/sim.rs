//! Live cloth and hair: the editor's 2D XPBD solver (`core/sim/XpbdSolver.kt`) ported line for line - same
//! constraint order, the same alternating sweeps, f32 state with the JVM's double-precision transcendentals -
//! so a scene steps here as it does in the editor. Space is world space: canvas x, negated canvas y (y up).

use crate::rig::Collider;

/// A compliance that stands for "no constraint" (the editor's infinity).
pub fn none(c: f32) -> bool {
    !(c >= 0.0) || !c.is_finite()
}

/// One simulation as the file holds it: particles (one per vertex of each target, in target order) and constraints.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Simulation {
    pub fps: f32,
    pub substeps: u32,
    pub gravity: [f32; 2],
    pub wind: [f32; 2],
    pub pin_compliance: f32,
    /// Target meshes and the first particle of each.
    pub targets: Vec<(usize, usize)>,
    pub inv_mass: Vec<f32>,
    pub damping: Vec<f32>,
    pub wind_factor: Vec<f32>,
    pub pin_weight: Vec<f32>,
    /// Negative: no goal.
    pub goal_compliance: Vec<f32>,
    pub goal_offset_x: Vec<f32>,
    pub goal_offset_y: Vec<f32>,
    /// The mesh and vertex each particle's pin follows; `None` for its own position.
    pub anchors: Vec<Option<(usize, usize)>>,
    pub stretch_a: Vec<u32>,
    pub stretch_b: Vec<u32>,
    pub stretch_rest: Vec<f32>,
    pub stretch_compliance: Vec<f32>,
    pub compression_compliance: Vec<f32>,
    pub tri_a: Vec<u32>,
    pub tri_b: Vec<u32>,
    pub tri_c: Vec<u32>,
    /// Negative: the triangle keeps no area (only the guard against turning over).
    pub area_compliance: Vec<f32>,
    pub bend_t1: Vec<u32>,
    pub bend_t2: Vec<u32>,
    pub bend_compliance: Vec<f32>,
    pub weld_a: Vec<u32>,
    pub weld_b: Vec<u32>,
    pub weld_weight_a: Vec<f32>,
    pub weld_weight_b: Vec<f32>,
    pub weld_compliance: Vec<f32>,
    pub long_particle: Vec<u32>,
    pub long_root: Vec<u32>,
    pub long_distance: Vec<f32>,
    /// Corrections the bake added on other parameters: parameter, keys, and per target mesh the offsets at each key.
    pub statics: Vec<(usize, Vec<f32>, Vec<(usize, Vec<Vec<f32>>)>)>,
    /// Colliders (indices into the rig's) the particles keep out of.
    pub colliders: Vec<usize>,
}

impl Simulation {
    pub fn particles(&self) -> usize {
        self.inv_mass.len()
    }
}

/// Below this share of its rest area a triangle is pushed back out.
const MIN_AREA: f32 = 0.2;

fn exp(x: f32) -> f32 {
    (x as f64).exp() as f32
}
fn sqrt(x: f32) -> f32 {
    (x as f64).sqrt() as f32
}
fn atan2(y: f32, x: f32) -> f32 {
    (y as f64).atan2(x as f64) as f32
}
fn cos(x: f32) -> f32 {
    (x as f64).cos() as f32
}
fn sin(x: f32) -> f32 {
    (x as f64).sin() as f32
}

/// A collider placed for one substep, world space: a capsule from a to b (a circle when they coincide).
#[derive(Clone, Copy, Debug, Default)]
pub struct PlacedCollider {
    pub a: [f32; 2],
    pub b: [f32; 2],
    pub radius_a: f32,
    pub radius_b: f32,
    pub friction: f32,
}

impl PlacedCollider {
    pub fn lerp(from: &PlacedCollider, to: &PlacedCollider, t: f32) -> PlacedCollider {
        let mix = |x: f32, y: f32| x + (y - x) * t;
        PlacedCollider {
            a: [mix(from.a[0], to.a[0]), mix(from.a[1], to.a[1])],
            b: [mix(from.b[0], to.b[0]), mix(from.b[1], to.b[1])],
            radius_a: to.radius_a, radius_b: to.radius_b, friction: to.friction,
        }
    }

    pub fn of(c: &Collider, a: [f32; 2], b: [f32; 2]) -> PlacedCollider {
        PlacedCollider { a, b, radius_a: c.radius_a, radius_b: c.radius_b, friction: c.friction }
    }
}

/// Particle state and the solver's scratch, for one simulation.
#[derive(Clone, Debug, Default)]
pub struct SimState {
    pub x: Vec<f32>,
    pub y: Vec<f32>,
    pub vx: Vec<f32>,
    pub vy: Vec<f32>,
    pub anchor_x: Vec<f32>,
    pub anchor_y: Vec<f32>,
    pub goal_x: Vec<f32>,
    pub goal_y: Vec<f32>,
    pub frame_angle: f32,
    last_frame_angle: f32,
    px: Vec<f32>,
    py: Vec<f32>,
    last_anchor_x: Vec<f32>,
    last_anchor_y: Vec<f32>,
    last_goal_x: Vec<f32>,
    last_goal_y: Vec<f32>,
    step_anchor_x: Vec<f32>,
    step_anchor_y: Vec<f32>,
    step_goal_x: Vec<f32>,
    step_goal_y: Vec<f32>,
    stretch_lambda: Vec<f32>,
    area_lambda: Vec<f32>,
    flip_lambda: Vec<f32>,
    bend_lambda: Vec<f32>,
    weld_lambda: Vec<f32>,
    pin_lambda: Vec<f32>,
    goal_lambda: Vec<f32>,
    rest_length: Vec<f32>,
    rest_area: Vec<f32>,
    rest_inverse: Vec<f32>,
    turn_grad: [f32; 6],
    /// Colliders now and as they were at the last step, interpolated across the substeps.
    pub colliders: Vec<PlacedCollider>,
    last_colliders: Vec<PlacedCollider>,
    step_colliders: Vec<PlacedCollider>,
}

impl SimState {
    pub fn new(sim: &Simulation) -> SimState {
        let n = sim.particles();
        let z = || vec![0.0f32; n];
        SimState {
            x: z(), y: z(), vx: z(), vy: z(), anchor_x: z(), anchor_y: z(), goal_x: z(), goal_y: z(),
            px: z(), py: z(), last_anchor_x: z(), last_anchor_y: z(), last_goal_x: z(), last_goal_y: z(),
            step_anchor_x: z(), step_anchor_y: z(), step_goal_x: z(), step_goal_y: z(),
            stretch_lambda: vec![0.0; sim.stretch_a.len()],
            area_lambda: vec![0.0; sim.tri_a.len()],
            flip_lambda: vec![0.0; sim.tri_a.len()],
            bend_lambda: vec![0.0; sim.bend_t1.len()],
            weld_lambda: vec![0.0; sim.weld_a.len() * 2],
            pin_lambda: vec![0.0; n * 2],
            goal_lambda: vec![0.0; n * 2],
            rest_length: sim.stretch_rest.clone(),
            rest_area: vec![0.0; sim.tri_a.len()],
            rest_inverse: vec![0.0; sim.tri_a.len() * 4],
            ..Default::default()
        }
    }

    /// Every particle at rest on [positions], with anchors and goals there too.
    pub fn reset(&mut self, positions: &[f32]) {
        for i in 0..self.x.len() {
            let (x, y) = (positions[i * 2], positions[i * 2 + 1]);
            self.x[i] = x;
            self.y[i] = y;
            self.vx[i] = 0.0;
            self.vy[i] = 0.0;
            self.anchor_x[i] = x;
            self.anchor_y[i] = y;
            self.goal_x[i] = x;
            self.goal_y[i] = y;
            self.last_anchor_x[i] = x;
            self.last_anchor_y[i] = y;
            self.last_goal_x[i] = x;
            self.last_goal_y[i] = y;
            self.step_goal_x[i] = x;
            self.step_goal_y[i] = y;
        }
        self.frame_angle = 0.0;
        self.last_frame_angle = 0.0;
    }

    /// Takes the current anchors, goals and colliders as the last step's.
    pub fn settle(&mut self) {
        self.last_anchor_x.copy_from_slice(&self.anchor_x);
        self.last_anchor_y.copy_from_slice(&self.anchor_y);
        self.last_goal_x.copy_from_slice(&self.goal_x);
        self.last_goal_y.copy_from_slice(&self.goal_y);
        self.last_frame_angle = self.frame_angle;
        self.last_colliders.clone_from(&self.colliders);
    }
}

pub struct Solver<'a> {
    pub sim: &'a Simulation,
    pub state: &'a mut SimState,
    /// The wind this step: the scene's own plus whatever the host adds.
    pub wind: [f32; 2],
}

impl Solver<'_> {
    fn kinematic(&self, i: usize) -> bool {
        self.sim.pin_weight[i] >= 0.999 || self.sim.inv_mass[i] == 0.0
    }

    fn mobility(&self, i: usize) -> f32 {
        if self.kinematic(i) { 0.0 } else { self.sim.inv_mass[i] }
    }

    /// Advances one step of [dt] seconds.
    pub fn step(&mut self, dt: f32) {
        let substeps = self.sim.substeps.max(1);
        let h = dt / substeps as f32;
        let n = self.state.x.len();
        for sub in 1..=substeps {
            let t = sub as f32 / substeps as f32;
            let s = &mut *self.state;
            for i in 0..n {
                s.step_anchor_x[i] = s.last_anchor_x[i] + (s.anchor_x[i] - s.last_anchor_x[i]) * t;
                s.step_anchor_y[i] = s.last_anchor_y[i] + (s.anchor_y[i] - s.last_anchor_y[i]) * t;
                s.step_goal_x[i] = s.last_goal_x[i] + (s.goal_x[i] - s.last_goal_x[i]) * t;
                s.step_goal_y[i] = s.last_goal_y[i] + (s.goal_y[i] - s.last_goal_y[i]) * t;
            }
            s.step_colliders = s.colliders.iter().enumerate()
                .map(|(k, c)| s.last_colliders.get(k).map_or(*c, |last| PlacedCollider::lerp(last, c, t)))
                .collect();
            let angle = s.last_frame_angle + (s.frame_angle - s.last_frame_angle) * t;
            let reverse = sub % 2 == 0;
            self.measure_rest();
            self.integrate(h);
            let s = &mut *self.state;
            for v in [&mut s.stretch_lambda, &mut s.area_lambda, &mut s.flip_lambda, &mut s.bend_lambda, &mut s.weld_lambda, &mut s.pin_lambda, &mut s.goal_lambda] {
                v.fill(0.0);
            }
            self.solve_pins(h);
            self.solve_distances(h, reverse);
            self.solve_triangles(h, reverse);
            self.solve_bends(h, reverse);
            self.solve_welds(h, reverse);
            self.solve_goals(h, angle);
            self.solve_collisions();
            self.solve_long_range(reverse);
            self.state.stretch_lambda.fill(0.0);
            self.solve_distances(h, !reverse);
            self.update_velocities(h);
        }
        self.state.settle();
    }

    fn measure_rest(&mut self) {
        let (sim, s) = (self.sim, &mut *self.state);
        for k in 0..sim.stretch_a.len() {
            let (a, b) = (sim.stretch_a[k] as usize, sim.stretch_b[k] as usize);
            let dx = s.step_goal_x[a] - s.step_goal_x[b];
            let dy = s.step_goal_y[a] - s.step_goal_y[b];
            s.rest_length[k] = sim.stretch_rest[k].max(sqrt(dx * dx + dy * dy));
        }
        for k in 0..sim.tri_a.len() {
            let (a, b, c) = (sim.tri_a[k] as usize, sim.tri_b[k] as usize, sim.tri_c[k] as usize);
            let m00 = s.step_goal_x[b] - s.step_goal_x[a];
            let m01 = s.step_goal_x[c] - s.step_goal_x[a];
            let m10 = s.step_goal_y[b] - s.step_goal_y[a];
            let m11 = s.step_goal_y[c] - s.step_goal_y[a];
            let det = m00 * m11 - m01 * m10;
            s.rest_area[k] = det / 2.0;
            let o = k * 4;
            if det * det < 1e-12 {
                s.rest_inverse[o..o + 4].fill(0.0);
                continue;
            }
            s.rest_inverse[o] = m11 / det;
            s.rest_inverse[o + 1] = -m01 / det;
            s.rest_inverse[o + 2] = -m10 / det;
            s.rest_inverse[o + 3] = m00 / det;
        }
    }

    fn integrate(&mut self, h: f32) {
        let sim = self.sim;
        for i in 0..self.state.x.len() {
            let kinematic = self.kinematic(i);
            let s = &mut *self.state;
            s.px[i] = s.x[i];
            s.py[i] = s.y[i];
            if kinematic {
                s.x[i] = s.step_anchor_x[i];
                s.y[i] = s.step_anchor_y[i];
                continue;
            }
            let decay = exp(-sim.damping[i] * h);
            s.vx[i] = (s.vx[i] + (sim.gravity[0] + self.wind[0] * sim.wind_factor[i]) * h) * decay;
            s.vy[i] = (s.vy[i] + (sim.gravity[1] + self.wind[1] * sim.wind_factor[i]) * h) * decay;
            s.x[i] += s.vx[i] * h;
            s.y[i] += s.vy[i] * h;
        }
    }

    fn solve_distances(&mut self, h: f32, reverse: bool) {
        let sim = self.sim;
        let h2 = h * h;
        let count = sim.stretch_a.len();
        for step in 0..count {
            let k = if reverse { count - 1 - step } else { step };
            let (a, b) = (sim.stretch_a[k] as usize, sim.stretch_b[k] as usize);
            let (wa, wb) = (self.mobility(a), self.mobility(b));
            let w = wa + wb;
            if w == 0.0 {
                continue;
            }
            let s = &mut *self.state;
            let dx = s.x[a] - s.x[b];
            let dy = s.y[a] - s.y[b];
            let length = sqrt(dx * dx + dy * dy);
            if length < 1e-9 {
                continue;
            }
            let constraint = length - s.rest_length[k];
            let alpha = (if constraint < 0.0 { sim.compression_compliance[k] } else { sim.stretch_compliance[k] }) / h2;
            let delta = (-constraint - alpha * s.stretch_lambda[k]) / (w + alpha);
            s.stretch_lambda[k] += delta;
            let (nx, ny) = (dx / length, dy / length);
            s.x[a] += wa * delta * nx;
            s.y[a] += wa * delta * ny;
            s.x[b] -= wb * delta * nx;
            s.y[b] -= wb * delta * ny;
        }
    }

    fn solve_triangles(&mut self, h: f32, reverse: bool) {
        let sim = self.sim;
        let h2 = h * h;
        let count = sim.tri_a.len();
        for step in 0..count {
            let k = if reverse { count - 1 - step } else { step };
            let rest = self.state.rest_area[k];
            if rest * rest < 1e-8 {
                continue;
            }
            let sign = if rest > 0.0 { 1.0 } else { -1.0 };
            let (a, b, c) = (sim.tri_a[k] as usize, sim.tri_b[k] as usize, sim.tri_c[k] as usize);
            let (wa, wb, wc) = (self.mobility(a), self.mobility(b), self.mobility(c));
            if wa + wb + wc == 0.0 {
                continue;
            }
            let compliance = sim.area_compliance[k];
            if !none(compliance) {
                self.area(k, [a, b, c], [wa, wb, wc], sign, rest.abs(), compliance / h2, false);
            }
            self.area(k, [a, b, c], [wa, wb, wc], sign, rest.abs() * MIN_AREA, 0.0, true);
        }
    }

    /// Pulls triangle [k]'s signed area toward [target]; [unilateral] only pushes it up to it (the flip guard).
    #[allow(clippy::too_many_arguments)]
    fn area(&mut self, k: usize, [a, b, c]: [usize; 3], [wa, wb, wc]: [f32; 3], sign: f32, target: f32, alpha: f32, unilateral: bool) {
        let s = &mut *self.state;
        let (xa, ya, xb, yb, xc, yc) = (s.x[a], s.y[a], s.x[b], s.y[b], s.x[c], s.y[c]);
        let value = sign * ((xb - xa) * (yc - ya) - (xc - xa) * (yb - ya)) / 2.0;
        let constraint = value - target;
        if unilateral && constraint >= 0.0 {
            return;
        }
        let (gax, gay) = (sign * (yb - yc) / 2.0, sign * (xc - xb) / 2.0);
        let (gbx, gby) = (sign * (yc - ya) / 2.0, sign * (xa - xc) / 2.0);
        let (gcx, gcy) = (sign * (ya - yb) / 2.0, sign * (xb - xa) / 2.0);
        let w = wa * (gax * gax + gay * gay) + wb * (gbx * gbx + gby * gby) + wc * (gcx * gcx + gcy * gcy);
        if w < 1e-12 {
            return;
        }
        let lambda = if unilateral { &mut s.flip_lambda } else { &mut s.area_lambda };
        let delta = (-constraint - alpha * lambda[k]) / (w + alpha);
        lambda[k] += delta;
        s.x[a] += wa * delta * gax;
        s.y[a] += wa * delta * gay;
        s.x[b] += wb * delta * gbx;
        s.y[b] += wb * delta * gby;
        s.x[c] += wc * delta * gcx;
        s.y[c] += wc * delta * gcy;
    }

    /// How far triangle [k] has turned from rest, with its gradient over corners a, b, c in `turn_grad`; NaN without a rest shape.
    fn turn(&mut self, k: usize) -> f32 {
        let sim = self.sim;
        let s = &mut *self.state;
        let o = k * 4;
        let (p, q, r, t) = (s.rest_inverse[o], s.rest_inverse[o + 1], s.rest_inverse[o + 2], s.rest_inverse[o + 3]);
        if p == 0.0 && q == 0.0 && r == 0.0 && t == 0.0 {
            return f32::NAN;
        }
        let (a, b, c) = (sim.tri_a[k] as usize, sim.tri_b[k] as usize, sim.tri_c[k] as usize);
        let (e1x, e1y) = (s.x[b] - s.x[a], s.y[b] - s.y[a]);
        let (e2x, e2y) = (s.x[c] - s.x[a], s.y[c] - s.y[a]);
        let cs = e1x * p + e2x * r + e1y * q + e2y * t;
        let sn = e1y * p + e2y * r - e1x * q - e2x * t;
        let norm = cs * cs + sn * sn;
        if norm < 1e-12 {
            return f32::NAN;
        }
        let (bx, by) = ((cs * -q - sn * p) / norm, (cs * p - sn * q) / norm);
        let (cx, cy) = ((cs * -t - sn * r) / norm, (cs * r - sn * t) / norm);
        s.turn_grad = [-(bx + cx), -(by + cy), bx, by, cx, cy];
        atan2(sn, cs)
    }

    fn solve_bends(&mut self, h: f32, reverse: bool) {
        let sim = self.sim;
        let h2 = h * h;
        let count = sim.bend_t1.len();
        let (mut index, mut gx, mut gy) = ([0usize; 6], [0.0f32; 6], [0.0f32; 6]);
        for step in 0..count {
            let k = if reverse { count - 1 - step } else { step };
            let (t1, t2) = (sim.bend_t1[k] as usize, sim.bend_t2[k] as usize);
            let mut n = 0;
            let mut add = |i: usize, x: f32, y: f32, n: &mut usize| {
                for j in 0..*n {
                    if index[j] == i {
                        gx[j] += x;
                        gy[j] += y;
                        return;
                    }
                }
                index[*n] = i;
                gx[*n] = x;
                gy[*n] = y;
                *n += 1;
            };
            let first = self.turn(t1);
            if first.is_nan() {
                continue;
            }
            let g = self.state.turn_grad;
            add(sim.tri_a[t1] as usize, g[0], g[1], &mut n);
            add(sim.tri_b[t1] as usize, g[2], g[3], &mut n);
            add(sim.tri_c[t1] as usize, g[4], g[5], &mut n);
            let second = self.turn(t2);
            if second.is_nan() {
                continue;
            }
            let g = self.state.turn_grad;
            add(sim.tri_a[t2] as usize, -g[0], -g[1], &mut n);
            add(sim.tri_b[t2] as usize, -g[2], -g[3], &mut n);
            add(sim.tri_c[t2] as usize, -g[4], -g[5], &mut n);
            let pi = std::f64::consts::PI as f32;
            let mut constraint = first - second;
            if constraint > pi {
                constraint -= 2.0 * pi;
            } else if constraint < -pi {
                constraint += 2.0 * pi;
            }
            let mut w = 0.0f32;
            for j in 0..n {
                w += self.mobility(index[j]) * (gx[j] * gx[j] + gy[j] * gy[j]);
            }
            if w < 1e-12 {
                continue;
            }
            let alpha = sim.bend_compliance[k] / h2;
            let s = &mut *self.state;
            let delta = (-constraint - alpha * s.bend_lambda[k]) / (w + alpha);
            s.bend_lambda[k] += delta;
            for j in 0..n {
                let i = index[j];
                let wi = if sim.pin_weight[i] >= 0.999 || sim.inv_mass[i] == 0.0 { 0.0 } else { sim.inv_mass[i] };
                let s = &mut *self.state;
                s.x[i] += wi * delta * gx[j];
                s.y[i] += wi * delta * gy[j];
            }
        }
    }

    fn solve_welds(&mut self, h: f32, reverse: bool) {
        let sim = self.sim;
        let h2 = h * h;
        let count = sim.weld_a.len();
        for step in 0..count {
            let k = if reverse { count - 1 - step } else { step };
            let (a, b) = (sim.weld_a[k] as usize, sim.weld_b[k] as usize);
            let wa = self.mobility(a) * sim.weld_weight_a[k];
            let wb = self.mobility(b) * sim.weld_weight_b[k];
            let w = wa + wb;
            if w == 0.0 {
                continue;
            }
            let alpha = sim.weld_compliance[k] / h2;
            let s = &mut *self.state;
            for axis in 0..2 {
                let offset = if axis == 0 { s.x[a] - s.x[b] } else { s.y[a] - s.y[b] };
                let slot = k * 2 + axis;
                let delta = (-offset - alpha * s.weld_lambda[slot]) / (w + alpha);
                s.weld_lambda[slot] += delta;
                if axis == 0 {
                    s.x[a] += wa * delta;
                    s.x[b] -= wb * delta;
                } else {
                    s.y[a] += wa * delta;
                    s.y[b] -= wb * delta;
                }
            }
        }
    }

    fn solve_pins(&mut self, h: f32) {
        let sim = self.sim;
        let h2 = h * h;
        for i in 0..self.state.x.len() {
            let weight = sim.pin_weight[i];
            if weight <= 0.0 || self.kinematic(i) {
                continue;
            }
            let compliance = sim.pin_compliance * (1.0 - weight) / weight * sim.inv_mass[i];
            let (tx, ty) = (self.state.step_anchor_x[i], self.state.step_anchor_y[i]);
            self.attach(i, tx, ty, compliance / h2, false);
        }
    }

    fn solve_goals(&mut self, h: f32, angle: f32) {
        let sim = self.sim;
        let h2 = h * h;
        let (cos_a, sin_a) = (cos(angle), sin(angle));
        for i in 0..self.state.x.len() {
            let compliance = sim.goal_compliance[i];
            if none(compliance) || self.kinematic(i) {
                continue;
            }
            let ox = sim.goal_offset_x[i] * cos_a - sim.goal_offset_y[i] * sin_a;
            let oy = sim.goal_offset_x[i] * sin_a + sim.goal_offset_y[i] * cos_a;
            let (tx, ty) = (self.state.step_goal_x[i] + ox, self.state.step_goal_y[i] + oy);
            self.attach(i, tx, ty, compliance / h2, true);
        }
    }

    /// A zero-length spring from particle [i] to a moving point, one XPBD update per axis.
    fn attach(&mut self, i: usize, tx: f32, ty: f32, alpha: f32, goal: bool) {
        let w = self.sim.inv_mass[i];
        if w == 0.0 {
            return;
        }
        let s = &mut *self.state;
        let lambda = if goal { &mut s.goal_lambda } else { &mut s.pin_lambda };
        let dx = (-(s.x[i] - tx) - alpha * lambda[i * 2]) / (w + alpha);
        lambda[i * 2] += dx;
        s.x[i] += w * dx;
        let dy = (-(s.y[i] - ty) - alpha * lambda[i * 2 + 1]) / (w + alpha);
        lambda[i * 2 + 1] += dy;
        s.y[i] += w * dy;
    }

    /// Particles pushed out of every collider (rigid, one-sided); [friction] takes that share of the
    /// particle's slide along the surface this substep away.
    fn solve_collisions(&mut self) {
        if self.state.step_colliders.is_empty() {
            return;
        }
        for i in 0..self.state.x.len() {
            if self.kinematic(i) {
                continue;
            }
            let s = &mut *self.state;
            for c in &s.step_colliders {
                let (ax, ay, bx, by) = (c.a[0], c.a[1], c.b[0], c.b[1]);
                let (ex, ey) = (bx - ax, by - ay);
                let length2 = ex * ex + ey * ey;
                let t = if length2 > 1e-12 { (((s.x[i] - ax) * ex + (s.y[i] - ay) * ey) / length2).clamp(0.0, 1.0) } else { 0.0 };
                let (cx, cy) = (ax + ex * t, ay + ey * t);
                let radius = c.radius_a + (c.radius_b - c.radius_a) * t;
                let (dx, dy) = (s.x[i] - cx, s.y[i] - cy);
                let distance = sqrt(dx * dx + dy * dy);
                if distance >= radius {
                    continue;
                }
                let (nx, ny) = if distance > 1e-6 { (dx / distance, dy / distance) } else { (0.0, 1.0) };
                let depth = radius - distance;
                s.x[i] += nx * depth;
                s.y[i] += ny * depth;
                if c.friction > 0.0 {
                    // The slide this substep, tangent to the surface, cut by the friction share.
                    let (mx, my) = (s.x[i] - s.px[i], s.y[i] - s.py[i]);
                    let along = mx * -ny + my * nx;
                    s.x[i] -= -ny * along * c.friction;
                    s.y[i] -= nx * along * c.friction;
                }
            }
        }
    }

    fn solve_long_range(&mut self, reverse: bool) {
        let sim = self.sim;
        let count = sim.long_particle.len();
        for step in 0..count {
            let k = if reverse { count - 1 - step } else { step };
            let i = sim.long_particle[k] as usize;
            if self.kinematic(i) {
                continue;
            }
            let r = sim.long_root[k] as usize;
            let s = &mut *self.state;
            let (dx, dy) = (s.x[i] - s.x[r], s.y[i] - s.y[r]);
            let length = sqrt(dx * dx + dy * dy);
            let limit = sim.long_distance[k];
            if length <= limit || length < 1e-9 {
                continue;
            }
            let k2 = limit / length;
            s.x[i] = s.x[r] + dx * k2;
            s.y[i] = s.y[r] + dy * k2;
        }
    }

    fn update_velocities(&mut self, h: f32) {
        let s = &mut *self.state;
        for i in 0..s.x.len() {
            s.vx[i] = (s.x[i] - s.px[i]) / h;
            s.vy[i] = (s.y[i] - s.py[i]) / h;
        }
    }
}

/// The best-fit rotation of the pinned anchors from [rest] to now (2D Procrustes about the centroids).
pub fn frame_angle(frame: &[usize], rest: &[f32], s: &SimState) -> f32 {
    if frame.len() < 2 || rest.len() != frame.len() * 2 {
        return 0.0;
    }
    let (mut rx, mut ry, mut cx, mut cy) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
    for (k, &i) in frame.iter().enumerate() {
        rx += rest[k * 2];
        ry += rest[k * 2 + 1];
        cx += s.anchor_x[i];
        cy += s.anchor_y[i];
    }
    let count = frame.len() as f32;
    rx /= count;
    ry /= count;
    cx /= count;
    cy /= count;
    let (mut dot, mut cross) = (0.0f32, 0.0f32);
    for (k, &i) in frame.iter().enumerate() {
        let (ax, ay) = (rest[k * 2] - rx, rest[k * 2 + 1] - ry);
        let (bx, by) = (s.anchor_x[i] - cx, s.anchor_y[i] - cy);
        dot += ax * bx + ay * by;
        cross += ax * by - ay * bx;
    }
    if dot == 0.0 && cross == 0.0 { 0.0 } else { atan2(cross, dot) }
}
