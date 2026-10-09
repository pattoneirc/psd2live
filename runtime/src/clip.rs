//! Animation clips: curve sampling and a player that fades clips over the current parameter values.

use crate::rig::{Clip, Curve, CurveTarget, Rig, Segment};

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

/// What clips set during an update besides parameters, as Cubism's motions set them.
#[derive(Debug, Default, Clone)]
pub struct Effects {
    /// Part opacities set outright, by part, in the order the clips set them: the last one wins.
    pub part_opacity: Vec<(usize, f32)>,
    /// The rig's opacity, when a clip sets it.
    pub model_opacity: Option<f32>,
    /// Events the playing clip passed: the clip and the event's index in it.
    pub events: Vec<(usize, usize)>,
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
    /// The clip time up to which events have fired; events after it and up to [time] fire next.
    fired: f32,
}

/// A fade-in factor after [age] seconds of a fade lasting [fade] (`None` for the 1 second default).
fn fade_in(fade: Option<f32>, age: f32) -> f32 {
    let fade = fade.unwrap_or(1.0);
    if fade > 0.0 { ease(age / fade) } else { 1.0 }
}

/// A fade-out factor [fading] seconds into a fade lasting [fade], 1 while not fading out.
fn fade_out(fade: Option<f32>, fading: Option<f32>) -> f32 {
    match fading {
        None => 1.0,
        Some(elapsed) => {
            let fade = fade.unwrap_or(1.0);
            if fade <= 0.0 { 0.0 } else { 1.0 - ease(elapsed / fade) }
        }
    }
}

impl Playing {
    /// The clip's weight: its fade-in times its fade-out, as Cubism weighs a motion.
    fn weight(&self, clip: &Clip) -> f32 {
        fade_in(clip.fade_in, self.age) * fade_out(clip.fade_out, self.fading)
    }

    /// Whether anything of the clip still shows: not fading out, or some fade-out not yet over.
    fn alive(&self, clip: &Clip) -> bool {
        let Some(elapsed) = self.fading else { return true };
        let longest = clip.extras.fades.iter().filter_map(|f| f.2).chain(clip.extras.curves.iter().filter_map(|c| c.fade_out))
            .fold(clip.fade_out.unwrap_or(1.0), f32::max);
        elapsed < longest && (self.weight(clip) > 0.0 || longest > clip.fade_out.unwrap_or(1.0))
    }
}

impl Player {
    pub fn new() -> Player {
        Player::default()
    }

    /// Starts [clip], fading out the one playing.
    pub fn play(&mut self, clip: usize) {
        self.stop();
        self.current = Some(Playing { clip, time: 0.0, age: 0.0, fading: None, fired: -1.0 });
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

    /// Moves the playing clip to [time] seconds; its fade-in goes on as it was, and the events it jumps over
    /// do not fire.
    pub fn seek(&mut self, time: f32) {
        if let Some(current) = &mut self.current {
            if time.is_finite() {
                current.time = time.max(0.0);
                current.fired = current.time;
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
        self.update_layer(rig, dt, 1.0, values, &mut Effects::default());
    }

    /// [update] at [weight] 0..1, collecting what the clips set besides parameters into [effects].
    pub fn update_layer(&mut self, rig: &Rig, dt: f32, weight: f32, values: &mut [f32], effects: &mut Effects) {
        let dt = if dt.is_finite() { dt.max(0.0) } else { 0.0 };
        for p in self.previous.iter_mut().chain(self.current.iter_mut()) {
            p.time += dt;
            p.age += dt;
            if let Some(f) = &mut p.fading {
                *f += dt;
            }
        }
        // The replaced clips fade out under the current one, the oldest lowest.
        self.previous.retain(|p| p.alive(&rig.clips[p.clip]));
        for p in self.previous.iter().chain(self.current.iter()) {
            apply_playing(rig, p, weight, values, effects);
        }
        if let Some(current) = &mut self.current {
            let clip = &rig.clips[current.clip];
            fire(clip, current.clip, current.fired, current.time, &mut effects.events);
            current.fired = current.time;
        }
    }
}

/// The events of [clip] (number [index]) after clip time [from] and up to [to], into [out]; a loop fires each
/// pass, a one-shot stops at its end.
fn fire(clip: &Clip, index: usize, from: f32, to: f32, out: &mut Vec<(usize, usize)>) {
    let events = &clip.extras.events;
    if events.is_empty() || to <= from {
        return;
    }
    if clip.looping && clip.duration > 0.0 {
        let d = clip.duration;
        let mut pass = (from.max(0.0) / d).floor();
        // A first update from the start fires the events at time 0 too.
        let from = if from < 0.0 { -f32::MIN_POSITIVE } else { from };
        while pass * d <= to {
            for (e, (time, _)) in events.iter().enumerate() {
                let at = pass * d + time;
                if at > from && at <= to {
                    out.push((index, e));
                }
            }
            pass += 1.0;
        }
    } else {
        let to = to.min(clip.duration);
        for (e, (time, _)) in events.iter().enumerate() {
            if (*time > from || (from < 0.0 && *time >= 0.0)) && *time <= to {
                out.push((index, e));
            }
        }
    }
}

/// Blends one playing clip into [values] at [layer] weight, as Cubism applies a motion: its EyeBlink effect
/// multiplies and its LipSync effect adds to those roles' parameters, curves with fades of their own weigh by
/// them, and part and rig opacities are set outright.
fn apply_playing(rig: &Rig, p: &Playing, layer: f32, values: &mut [f32], effects: &mut Effects) {
    let clip = &rig.clips[p.clip];
    let t = local_time(clip, p.time);
    let weight = layer * p.weight(clip);
    let (mut eye, mut lip) = (None, None);
    for c in &clip.extras.curves {
        let v = value_at(&c.curve, t);
        match c.target {
            CurveTarget::PartOpacity(part) => effects.part_opacity.push((part, v)),
            CurveTarget::ModelOpacity => effects.model_opacity = Some(v),
            CurveTarget::EyeBlink => eye = Some(v),
            CurveTarget::LipSync => lip = Some(v),
        }
    }
    let role = |name: &str| rig.roles.iter().filter(|r| r.role == name).flat_map(|r| r.parameters.iter().copied()).collect::<Vec<_>>();
    let (eyes, lips) = if eye.is_some() || lip.is_some() { (role("EyeBlink"), role("LipSync")) } else { (vec![], vec![]) };
    for (k, curve) in clip.curves.iter().enumerate() {
        let Some(slot) = values.get_mut(curve.parameter) else { continue };
        let mut v = value_at(curve, t);
        if let Some(e) = eye.filter(|_| eyes.contains(&curve.parameter)) {
            v *= e;
        }
        if let Some(l) = lip.filter(|_| lips.contains(&curve.parameter)) {
            v += l;
        }
        let w = match clip.extras.fades.iter().find(|f| f.0 == k) {
            Some(&(_, fin, fout)) => {
                let fin = if fin.is_some() { fade_in(fin, p.age) } else { fade_in(clip.fade_in, p.age) };
                let fout = if fout.is_some() { fade_out(fout, p.fading) } else { fade_out(clip.fade_out, p.fading) };
                layer * fin * fout
            }
            None => weight,
        };
        *slot += (v - *slot) * w;
    }
    // The effects reach the role parameters the clip has no curve of its own for.
    let own = |p: usize| clip.curves.iter().any(|c| c.parameter == p);
    if let Some(e) = eye {
        for &p in eyes.iter().filter(|p| !own(**p)) {
            let source = values[p];
            values[p] = source + (source * e - source) * weight;
        }
    }
    if let Some(l) = lip {
        for &p in lips.iter().filter(|p| !own(**p)) {
            values[p] += l * weight;
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
            fade_in: Some(0.0), fade_out: Some(0.0), curves: vec![curve()], extras: Default::default(),
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
