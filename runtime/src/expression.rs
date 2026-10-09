//! Expressions: a few parameters added to, multiplied into or overwriting the motion's values, faded in and
//! out as Cubism's exp3 expressions are. Several may play at once, applied oldest first.

use crate::rig::Rig;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Blend {
    Add,
    Multiply,
    Overwrite,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Expression {
    pub id: String,
    pub name: String,
    pub fade_in: f32,
    pub fade_out: f32,
    pub parameters: Vec<(usize, Blend, f32)>,
}

fn ease(x: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    0.5 - 0.5 * (x * std::f32::consts::PI).cos()
}

/// Applies [e] over [values] at [weight] 0..1.
pub fn apply(e: &Expression, weight: f32, values: &mut [f32]) {
    for &(p, blend, v) in &e.parameters {
        let value = &mut values[p];
        *value = match blend {
            Blend::Add => *value + v * weight,
            Blend::Multiply => *value * (1.0 + (v - 1.0) * weight),
            Blend::Overwrite => *value * (1.0 - weight) + v * weight,
        };
    }
}

/// The expressions playing, each fading in when it starts and out when it stops, applied oldest first.
#[derive(Debug, Default, Clone)]
pub struct ExpressionPlayer {
    entries: Vec<Entry>,
}

#[derive(Debug, Clone, Copy)]
struct Entry {
    index: usize,
    age: f32,
    /// When fading out: the weight it had when it began to, and the time since.
    fading: Option<(f32, f32)>,
}

impl ExpressionPlayer {
    pub fn new() -> ExpressionPlayer {
        ExpressionPlayer::default()
    }

    fn weight(rig: &Rig, e: &Entry) -> f32 {
        let expression = &rig.expressions[e.index];
        let fade = expression.fade_in;
        let weight = if fade > 0.0 { ease(e.age / fade) } else { 1.0 };
        match e.fading {
            None => weight,
            Some((start, time)) => {
                let fade = expression.fade_out;
                start * (1.0 - if fade > 0.0 { ease(time / fade) } else { 1.0 })
            }
        }
    }

    /// Fades out the playing entries [which] picks.
    fn fade_out(&mut self, rig: &Rig, which: impl Fn(usize) -> bool) {
        for e in &mut self.entries {
            if e.fading.is_none() && which(e.index) {
                e.fading = Some((ExpressionPlayer::weight(rig, e), 0.0));
            }
        }
    }

    /// Plays expression [index] alone (none for `None`), fading out the others.
    pub fn play(&mut self, rig: &Rig, index: Option<usize>) {
        let index = index.filter(|i| *i < rig.expressions.len());
        if index.is_some() && self.active().collect::<Vec<_>>() == [index.unwrap()] {
            return;
        }
        self.fade_out(rig, |_| true);
        if let Some(i) = index {
            self.entries.push(Entry { index: i, age: 0.0, fading: None });
        }
    }

    /// Adds expression [index] over those playing; false when there is no such expression or it already plays.
    pub fn add(&mut self, rig: &Rig, index: usize) -> bool {
        if index >= rig.expressions.len() || self.active().any(|i| i == index) {
            return false;
        }
        self.entries.push(Entry { index, age: 0.0, fading: None });
        true
    }

    /// Fades expression [index] out, leaving the others; false when it is not playing.
    pub fn remove(&mut self, rig: &Rig, index: usize) -> bool {
        let playing = self.active().any(|i| i == index);
        self.fade_out(rig, |i| i == index);
        playing
    }

    /// The expressions playing and not fading out, oldest first.
    pub fn active(&self) -> impl Iterator<Item = usize> + '_ {
        self.entries.iter().filter(|e| e.fading.is_none()).map(|e| e.index)
    }

    /// The newest expression playing.
    pub fn playing(&self) -> Option<usize> {
        self.active().last()
    }

    /// Advances by [dt] seconds and applies the expressions over [values].
    pub fn update(&mut self, rig: &Rig, dt: f32, values: &mut [f32]) {
        let dt = dt.max(0.0);
        for e in &mut self.entries {
            e.age += dt;
            if let Some((_, time)) = &mut e.fading {
                *time += dt;
            }
        }
        self.entries.retain(|e| e.fading.is_none() || ExpressionPlayer::weight(rig, e) > 0.0);
        for e in &self.entries {
            apply(&rig.expressions[e.index], ExpressionPlayer::weight(rig, e), values);
        }
    }
}
