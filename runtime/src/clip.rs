//! Animation clips: curve sampling and a player that fades clips over the current parameter values.

use crate::rig::{Clip, Curve, Rig, Segment};

fn cubic(p0: f32, p1: f32, p2: f32, p3: f32, u: f32) -> f32 {
    let v = 1.0 - u;
    v * v * v * p0 + 3.0 * v * v * u * p1 + 3.0 * v * u * u * p2 + u * u * u * p3
}

/// A curve's value at [time] seconds. Bezier handles are restricted to their segment, so the curve
/// parameter advances linearly with time.
pub fn value_at(curve: &Curve, time: f32) -> f32 {
    if curve.segments.is_empty() || time <= curve.start_time {
        return curve.start_value;
    }
    let (mut from_time, mut from_value) = (curve.start_time, curve.start_value);
    for segment in &curve.segments {
        let (end_time, end_value) = segment.end();
        if time <= end_time {
            let span = end_time - from_time;
            let u = if span <= 0.0 { 1.0 } else { (time - from_time) / span };
            return match *segment {
                Segment::Linear { .. } => from_value + (end_value - from_value) * u,
                Segment::Stepped { .. } => if time >= end_time { end_value } else { from_value },
                Segment::InverseStepped { .. } => if time > from_time { end_value } else { from_value },
                Segment::Bezier { c1, c2, .. } => cubic(from_value, c1.1, c2.1, end_value, u),
            };
        }
        from_time = end_time;
        from_value = end_value;
    }
    from_value
}

/// The clip's local time at [time]: wrapped for a loop, clamped for a one-shot.
pub fn local_time(clip: &Clip, time: f32) -> f32 {
    if clip.looping && clip.duration > 0.0 {
        time.rem_euclid(clip.duration)
    } else {
        time.clamp(0.0, clip.duration.max(0.0))
    }
}

/// Eases 0..1 in and out, as clip fades do.
fn ease(x: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    0.5 - 0.5 * (x * std::f32::consts::PI).cos()
}

/// Plays one clip at a time over the parameters, fading between clips. Every clip a switch replaced keeps
/// fading out under the newer ones, however quickly the switches come.
#[derive(Debug, Default, Clone)]
pub struct Player {
    current: Option<Playing>,
    /// Replaced clips fading out, oldest first.
    previous: Vec<Playing>,
}

#[derive(Debug, Clone, Copy)]
struct Playing {
    clip: usize,
    /// The clip's own time, which a seek moves.
    time: f32,
    /// Seconds since the clip started, which its fade-in follows.
    age: f32,
    /// Seconds since the clip started fading out, when it is.
    fading: Option<f32>,
}

impl Playing {
    /// The clip's weight: its fade-in times its fade-out, as Cubism weighs a motion.
    fn weight(&self, clip: &Clip) -> f32 {
        let fade_in = clip.fade_in.unwrap_or(1.0);
        let weight = if fade_in > 0.0 { ease(self.age / fade_in) } else { 1.0 };
        match self.fading {
            None => weight,
            Some(elapsed) => {
                let out = clip.fade_out.unwrap_or(1.0);
                if out <= 0.0 { 0.0 } else { weight * (1.0 - ease(elapsed / out)) }
            }
        }
    }
}

impl Player {
    pub fn new() -> Player {
        Player::default()
    }

    /// Starts [clip], fading out the one playing.
    pub fn play(&mut self, clip: usize) {
        self.stop();
        self.current = Some(Playing { clip, time: 0.0, age: 0.0, fading: None });
    }

    pub fn stop(&mut self) {
        if let Some(mut current) = self.current.take() {
            current.fading = Some(0.0);
            self.previous.push(current);
        }
    }

    pub fn playing(&self) -> Option<usize> {
        self.current.map(|p| p.clip)
    }

    /// The playing clip's local time: wrapped for a loop, held at the end for a one-shot.
    pub fn time(&self, rig: &Rig) -> f32 {
        self.current.map_or(0.0, |p| local_time(&rig.clips[p.clip], p.time))
    }

    /// Moves the playing clip to [time] seconds; its fade-in goes on as it was.
    pub fn seek(&mut self, time: f32) {
        if let Some(current) = &mut self.current {
            if time.is_finite() {
                current.time = time.max(0.0);
            }
        }
    }

    /// Whether the current clip is a one-shot past its end.
    pub fn finished(&self, rig: &Rig) -> bool {
        self.current.map_or(true, |p| {
            let clip = &rig.clips[p.clip];
            !clip.looping && p.time >= clip.duration
        })
    }

    /// Advances by [dt] seconds and blends the clips into [values], one per rig parameter.
    pub fn update(&mut self, rig: &Rig, dt: f32, values: &mut [f32]) {
        let dt = if dt.is_finite() { dt.max(0.0) } else { 0.0 };
        for p in self.previous.iter_mut().chain(self.current.iter_mut()) {
            p.time += dt;
            p.age += dt;
            if let Some(f) = &mut p.fading {
                *f += dt;
            }
        }
        // The replaced clips fade out under the current one, the oldest lowest.
        self.previous.retain(|p| p.weight(&rig.clips[p.clip]) > 0.0);
        for p in self.previous.iter().chain(self.current.iter()) {
            let clip = &rig.clips[p.clip];
            apply(clip, p.time, p.weight(clip), values);
        }
    }
}

/// Blends [clip] at [time] into [values] with [weight].
pub fn apply(clip: &Clip, time: f32, weight: f32, values: &mut [f32]) {
    let t = local_time(clip, time);
    for curve in &clip.curves {
        if let Some(slot) = values.get_mut(curve.parameter) {
            *slot += (value_at(curve, t) - *slot) * weight;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn curve() -> Curve {
        Curve {
            parameter: 0,
            start_time: 0.0,
            start_value: 0.0,
            segments: vec![
                Segment::Linear { time: 1.0, value: 10.0 },
                Segment::Stepped { time: 2.0, value: 20.0 },
                Segment::InverseStepped { time: 3.0, value: 30.0 },
                Segment::Bezier { c1: (3.0 + 1.0 / 3.0, 30.0), c2: (4.0 - 1.0 / 3.0, 40.0), time: 4.0, value: 40.0 },
            ],
        }
    }

    #[test]
    fn curves_follow_each_segment_type() {
        let c = curve();
        assert_eq!(value_at(&c, -1.0), 0.0);
        assert!((value_at(&c, 0.5) - 5.0).abs() < 1e-5);
        assert_eq!(value_at(&c, 1.5), 10.0);
        assert_eq!(value_at(&c, 2.0), 20.0);
        assert_eq!(value_at(&c, 2.5), 30.0);
        assert!((value_at(&c, 3.5) - 35.0).abs() < 1e-4);
        assert!(value_at(&c, 3.25) < 32.5);
        assert_eq!(value_at(&c, 9.0), 40.0);
    }

    #[test]
    fn loops_wrap_and_one_shots_hold() {
        let mut clip = Clip {
            id: "c".into(), name: "c".into(), group: "".into(), duration: 4.0, fps: 30.0, looping: true,
            fade_in: Some(0.0), fade_out: Some(0.0), curves: vec![curve()],
        };
        let mut a = [0.0];
        let mut b = [0.0];
        apply(&clip, 0.5, 1.0, &mut a);
        apply(&clip, 4.5, 1.0, &mut b);
        assert_eq!(a, b);
        clip.looping = false;
        apply(&clip, 10.0, 1.0, &mut a);
        assert_eq!(a[0], 40.0);
    }
}
