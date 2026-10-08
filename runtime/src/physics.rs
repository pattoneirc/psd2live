//! Pendulum physics in the manner of Cubism's runtime (physics3.json): each group hangs a strand of
//! particles from a root that its inputs push sideways and tilt, and its outputs read the strand back
//! into parameters. Steps run at the rig's rate with inputs interpolated between frames, and outputs
//! interpolate between the last two steps; at an unlimited rate the outputs lag one frame. Both
//! translation rotations reuse the already rotated x, as that runtime does.

use crate::rig::{PhysicsGroup, PhysicsOutput, PhysicsSource, Rig};

const AIR_RESISTANCE: f32 = 5.0;
const MAXIMUM_WEIGHT: f32 = 100.0;
const MOVEMENT_THRESHOLD: f32 = 0.001;
const MAX_DELTA_TIME: f32 = 5.0;

#[derive(Debug, Clone, Copy, Default)]
struct V2 {
    x: f32,
    y: f32,
}

impl V2 {
    fn new(x: f32, y: f32) -> V2 {
        V2 { x, y }
    }
    fn add(self, o: V2) -> V2 {
        V2::new(self.x + o.x, self.y + o.y)
    }
    fn sub(self, o: V2) -> V2 {
        V2::new(self.x - o.x, self.y - o.y)
    }
    fn scale(self, s: f32) -> V2 {
        V2::new(self.x * s, self.y * s)
    }
    fn normalized(self) -> V2 {
        let length = (self.x * self.x + self.y * self.y).sqrt();
        V2::new(self.x / length, self.y / length)
    }
}

/// The signed angle from [from] to [to], in (-π, π].
fn direction_to_radian(from: V2, to: V2) -> f32 {
    let mut r = to.y.atan2(to.x) - from.y.atan2(from.x);
    while r < -std::f32::consts::PI {
        r += 2.0 * std::f32::consts::PI;
    }
    while r > std::f32::consts::PI {
        r -= 2.0 * std::f32::consts::PI;
    }
    r
}

#[derive(Debug, Clone)]
struct Particle {
    radius: f32,
    mobility: f32,
    delay: f32,
    acceleration: f32,
    position: V2,
    last_position: V2,
    last_gravity: V2,
    velocity: V2,
}

#[derive(Debug, Clone)]
struct Strand {
    particles: Vec<Particle>,
    current: Vec<f32>,
    previous: Vec<f32>,
}

impl Strand {
    fn new(group: &PhysicsGroup) -> Strand {
        let mut particles = Vec::with_capacity(group.segments.len() + 1);
        let root = Particle {
            radius: 0.0, mobility: 0.0, delay: 0.0, acceleration: 0.0,
            position: V2::default(), last_position: V2::default(), last_gravity: V2::new(0.0, 1.0), velocity: V2::default(),
        };
        particles.push(root);
        for s in &group.segments {
            let above = particles.last().unwrap().position;
            let position = above.add(V2::new(0.0, s.length));
            particles.push(Particle {
                radius: s.length, mobility: s.mobility, delay: s.delay, acceleration: s.acceleration,
                position, last_position: position, last_gravity: V2::new(0.0, 1.0), velocity: V2::default(),
            });
        }
        Strand { particles, current: vec![0.0; group.outputs.len()], previous: vec![0.0; group.outputs.len()] }
    }

    fn update(&mut self, translation: V2, total_angle: f32, threshold: f32, dt: f32) {
        let p = &mut self.particles;
        p[0].position = translation;
        let radian = total_angle.to_radians();
        let gravity = V2::new(radian.sin(), radian.cos()).normalized();
        for i in 1..p.len() {
            let force = gravity.scale(p[i].acceleration);
            p[i].last_position = p[i].position;
            let delay = p[i].delay * dt * 30.0;
            let mut direction = p[i].position.sub(p[i - 1].position);
            let r = direction_to_radian(p[i].last_gravity, gravity) / AIR_RESISTANCE;
            direction.x = r.cos() * direction.x - direction.y * r.sin();
            direction.y = r.sin() * direction.x + direction.y * r.cos();
            p[i].position = p[i - 1].position.add(direction);
            let velocity = p[i].velocity.scale(delay);
            let pull = force.scale(delay * delay);
            p[i].position = p[i].position.add(velocity).add(pull);
            let new_direction = p[i].position.sub(p[i - 1].position).normalized();
            p[i].position = p[i - 1].position.add(new_direction.scale(p[i].radius));
            if p[i].position.x.abs() < threshold {
                p[i].position.x = 0.0;
            }
            if delay != 0.0 {
                p[i].velocity = p[i].position.sub(p[i].last_position).scale(1.0 / delay * p[i].mobility);
            }
            p[i].last_gravity = gravity;
        }
    }

    /// The raw output of vertex [index]: its sideways offset, or its angle against the segment above.
    fn output(&self, o: &PhysicsOutput) -> Option<f32> {
        let i = o.vertex;
        if i < 1 || i >= self.particles.len() {
            return None;
        }
        let translation = self.particles[i].position.sub(self.particles[i - 1].position);
        let value = match o.source {
            PhysicsSource::X => translation.x,
            PhysicsSource::Angle => {
                let parent = if i >= 2 { self.particles[i - 1].position.sub(self.particles[i - 2].position) } else { V2::new(0.0, 1.0) };
                direction_to_radian(parent, translation)
            }
        };
        Some(if o.reflect { -value } else { value })
    }
}

/// [value] mapped from the parameter's range, split at its midpoint, onto the normalization range split
/// at its default; negated unless inverted.
fn normalize(value: f32, min: f32, max: f32, norm_min: f32, norm_max: f32, norm_default: f32, inverted: bool) -> f32 {
    let (lo, hi) = (min.min(max), min.max(max));
    let value = value.clamp(lo, hi);
    let (norm_lo, norm_hi) = (norm_min.min(norm_max), norm_min.max(norm_max));
    let middle = lo + (hi - lo) / 2.0;
    let offset = value - middle;
    let result = if offset > 0.0 {
        let p = hi - middle;
        if p != 0.0 { offset * ((norm_hi - norm_default) / p) + norm_default } else { 0.0 }
    } else if offset < 0.0 {
        let p = lo - middle;
        if p != 0.0 { offset * ((norm_lo - norm_default) / p) + norm_default } else { 0.0 }
    } else {
        norm_default
    };
    if inverted { result } else { -result }
}

/// Writes a scaled output into [value]: clamped to the range, then mixed in by the output's weight. Only
/// angle outputs carry a scale; sideways outputs scale by zero, as in that runtime.
fn write_output(value: &mut f32, min: f32, max: f32, raw: f32, o: &PhysicsOutput) {
    let scale = if o.source == PhysicsSource::Angle { o.scale } else { 0.0 };
    let v = (raw * scale).clamp(min.min(max), max.max(min));
    let weight = o.weight / MAXIMUM_WEIGHT;
    *value = if weight >= 1.0 { v } else { *value * (1.0 - weight) + v * weight };
}

/// The physics state of one rig.
#[derive(Debug, Clone)]
pub struct Physics {
    strands: Vec<Strand>,
    input_caches: Option<Vec<f32>>,
    caches: Vec<f32>,
    remain: f32,
}

impl Physics {
    pub fn new(rig: &Rig) -> Physics {
        Physics { strands: rig.physics.iter().map(Strand::new).collect(), input_caches: None, caches: Vec::new(), remain: 0.0 }
    }

    /// Advances by [dt] seconds from [values], one per parameter, and writes the outputs into them.
    pub fn step(&mut self, rig: &Rig, dt: f32, values: &mut [f32]) {
        self.step_skipping(rig, dt, values, &[]);
    }

    /// [step] with the groups [skip] marks left out: neither moved nor written.
    pub fn step_skipping(&mut self, rig: &Rig, dt: f32, values: &mut [f32], skip: &[bool]) {
        if !(dt > 0.0) || self.strands.is_empty() {
            return;
        }
        self.remain += dt;
        if self.remain > MAX_DELTA_TIME {
            self.remain = 0.0;
        }
        let input = self.input_caches.get_or_insert_with(|| values.to_vec());
        self.caches.resize(values.len(), 0.0);
        let h = if rig.physics_fps > 0.0 { 1.0 / rig.physics_fps } else { dt };
        while self.remain >= h {
            let weight = h / self.remain;
            for j in 0..values.len() {
                self.caches[j] = input[j] * (1.0 - weight) + values[j] * weight;
                input[j] = self.caches[j];
            }
            for (g, (group, strand)) in rig.physics.iter().zip(self.strands.iter_mut()).enumerate() {
                if skip.get(g) == Some(&true) {
                    continue;
                }
                strand.previous.copy_from_slice(&strand.current);
                let mut translation = V2::default();
                let mut angle = 0.0f32;
                for i in &group.inputs {
                    let p = &rig.parameters[i.parameter];
                    let w = i.weight / MAXIMUM_WEIGHT;
                    let n = &group.normalization;
                    let value = self.caches[i.parameter];
                    match i.source {
                        PhysicsSource::X => translation.x += normalize(value, p.min, p.max, n.position_min, n.position_max, n.position_default, i.reflect) * w,
                        PhysicsSource::Angle => angle += normalize(value, p.min, p.max, n.angle_min, n.angle_max, n.angle_default, i.reflect) * w,
                    }
                }
                let r = (-angle).to_radians();
                translation.x = translation.x * r.cos() - translation.y * r.sin();
                translation.y = translation.x * r.sin() + translation.y * r.cos();
                strand.update(translation, angle, MOVEMENT_THRESHOLD * group.normalization.position_max, h);
                for (k, o) in group.outputs.iter().enumerate() {
                    if let Some(raw) = strand.output(o) {
                        strand.current[k] = raw;
                        let p = &rig.parameters[o.parameter];
                        write_output(&mut self.caches[o.parameter], p.min, p.max, raw, o);
                    }
                }
            }
            self.remain -= h;
        }
        let alpha = self.remain / h;
        for (g, (group, strand)) in rig.physics.iter().zip(&self.strands).enumerate() {
            if skip.get(g) == Some(&true) {
                continue;
            }
            for (k, o) in group.outputs.iter().enumerate() {
                if o.vertex < 1 || o.vertex >= strand.particles.len() {
                    continue;
                }
                let p = &rig.parameters[o.parameter];
                let raw = strand.previous[k] * (1.0 - alpha) + strand.current[k] * alpha;
                write_output(&mut values[o.parameter], p.min, p.max, raw, o);
            }
        }
    }
}
