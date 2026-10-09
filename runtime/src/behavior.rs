//! Procedural behaviors a host switches on: blinking, breathing, gaze toward a target and lip sync.
//! Each drives the parameters of a role the rig declares (EyeBlink, Breath, AngleX, ..., LipSync), on
//! top of whatever clips set, before physics runs.

use crate::rig::Rig;

pub const BLINK: u32 = 1;
pub const BREATH: u32 = 2;
pub const LOOK: u32 = 4;
pub const LIP_SYNC: u32 = 8;

/// A sway on one role: offset and peak as fractions of the range (half range for the angles), and the
/// cycle in seconds.
const SWAYS: [(&str, f32, f32, f32); 5] = [
    ("AngleX", 0.0, 0.15, 6.5345),
    ("AngleY", 0.0, 0.08, 3.5345),
    ("AngleZ", 0.0, 0.10, 5.5345),
    ("BodyAngleX", 0.0, 0.04, 15.5345),
    ("Breath", 0.5, 0.5, 3.2345),
];

/// How far gaze turns each role, as a fraction of the parameter's half range; AngleZ follows x times y.
const LOOKS: [(&str, f32, f32); 5] = [("AngleX", 1.0, 0.0), ("AngleY", 0.0, 1.0), ("BodyAngleX", 0.33, 0.0), ("EyeBallX", 1.0, 0.0), ("EyeBallY", 0.0, 1.0)];

#[derive(Debug, Clone)]
pub struct Behaviors {
    pub enabled: u32,
    time: f32,
    blink_at: f32,
    blink_start: Option<f32>,
    seed: u32,
    look: (f32, f32),
    look_velocity: (f32, f32),
    target: (f32, f32),
    lip: f32,
}

impl Default for Behaviors {
    fn default() -> Self {
        Behaviors { enabled: BLINK | BREATH, time: 0.0, blink_at: 2.0, blink_start: None, seed: 0x2545_f491, look: (0.0, 0.0), look_velocity: (0.0, 0.0), target: (0.0, 0.0), lip: 0.0 }
    }
}

const CLOSING: f32 = 0.1;
const CLOSED: f32 = 0.05;
const OPENING: f32 = 0.15;

impl Behaviors {
    /// Reseeds the blink timing, so rigs side by side do not blink together; the next blink comes in 2..6
    /// seconds. Zero takes the default seed.
    pub fn seed(&mut self, seed: u32) {
        self.seed = if seed == 0 { Behaviors::default().seed } else { seed };
        if self.blink_start.is_none() {
            self.blink_at = self.time + 2.0 + self.random() * 4.0;
        }
    }

    /// Where to look, each axis -1..1 (x right, y up).
    pub fn look_at(&mut self, x: f32, y: f32) {
        self.target = (x.clamp(-1.0, 1.0), y.clamp(-1.0, 1.0));
    }

    /// The mouth opening 0..1, from the host's audio.
    pub fn lip_sync(&mut self, level: f32) {
        self.lip = if level.is_finite() { level.clamp(0.0, 1.0) } else { 0.0 };
    }

    fn random(&mut self) -> f32 {
        self.seed ^= self.seed << 13;
        self.seed ^= self.seed >> 17;
        self.seed ^= self.seed << 5;
        (self.seed >> 8) as f32 / (1u32 << 24) as f32
    }

    /// How open the eyes are now, 0..1.
    fn eyes(&mut self) -> f32 {
        if self.blink_start.is_none() && self.time >= self.blink_at {
            self.blink_start = Some(self.time);
        }
        let Some(start) = self.blink_start else { return 1.0 };
        let t = self.time - start;
        if t < CLOSING {
            1.0 - t / CLOSING
        } else if t < CLOSING + CLOSED {
            0.0
        } else if t < CLOSING + CLOSED + OPENING {
            (t - CLOSING - CLOSED) / OPENING
        } else {
            self.blink_start = None;
            // The next blink in 2..6 seconds.
            self.blink_at = self.time + 2.0 + self.random() * 4.0;
            1.0
        }
    }

    pub fn update(&mut self, rig: &Rig, dt: f32, values: &mut [f32]) {
        let dt = if dt.is_finite() { dt.clamp(0.0, 1.0) } else { 0.0 };
        self.time += dt;
        fn roles<'a>(rig: &'a Rig, name: &'a str) -> impl Iterator<Item = usize> + 'a {
            rig.roles.iter().filter(move |r| r.role == name).flat_map(|r| r.parameters.iter().copied())
        }
        if self.enabled & BLINK != 0 {
            let open = self.eyes();
            for p in roles(rig, "EyeBlink") {
                values[p] *= open;
            }
        }
        if self.enabled & BREATH != 0 {
            for (role, offset, peak, cycle) in SWAYS {
                let wave = (self.time * std::f32::consts::TAU / cycle).sin();
                for p in roles(rig, role) {
                    let param = &rig.parameters[p];
                    let span = param.max - param.min;
                    if role == "Breath" {
                        // Breathing sets its parameter across the range; the sways add to the pose.
                        values[p] = param.min + (offset + peak * wave) * span;
                    } else {
                        values[p] += (offset + peak * wave) * span * 0.5;
                    }
                }
            }
        }
        if self.enabled & LOOK != 0 {
            // A critically damped follow, settling in about a third of a second.
            let k = 120.0f32;
            let d = 2.0 * k.sqrt();
            for (pos, vel, target) in [(&mut self.look.0, &mut self.look_velocity.0, self.target.0), (&mut self.look.1, &mut self.look_velocity.1, self.target.1)] {
                let mut remaining = dt;
                while remaining > 0.0 {
                    let h = remaining.min(1.0 / 120.0);
                    *vel += ((target - *pos) * k - *vel * d) * h;
                    *pos += *vel * h;
                    remaining -= h;
                }
            }
            let (x, y) = self.look;
            for (role, gx, gy) in LOOKS {
                for p in roles(rig, role) {
                    values[p] += half_range(rig, p, gx * x + gy * y);
                }
            }
            for p in roles(rig, "AngleZ") {
                values[p] += half_range(rig, p, -x * y);
            }
        }
        if self.enabled & LIP_SYNC != 0 {
            for p in roles(rig, "LipSync") {
                let param = &rig.parameters[p];
                values[p] = values[p].max(param.min + (param.max - param.min) * self.lip);
            }
        }
    }
}

/// [fraction] of the way from the default toward the parameter's end in that direction.
fn half_range(rig: &Rig, p: usize, fraction: f32) -> f32 {
    let param = &rig.parameters[p];
    if fraction >= 0.0 {
        (param.max - param.default) * fraction
    } else {
        (param.default - param.min) * fraction
    }
}
