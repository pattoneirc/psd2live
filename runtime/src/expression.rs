//! Expressions: a few parameters added to, multiplied into or overwriting the motion's values, faded in and
//! out as Cubism's exp3 expressions are. One plays at a time; switching fades the last one out.

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

/// The expression playing and the one fading out behind it.
#[derive(Debug, Default, Clone)]
pub struct ExpressionPlayer {
    current: Option<(usize, f32)>,
    /// The last expression, its weight when it was replaced, and the time since.
    previous: Option<(usize, f32, f32)>,
}

impl ExpressionPlayer {
    pub fn new() -> ExpressionPlayer {
        ExpressionPlayer::default()
    }

    fn weight(rig: &Rig, (index, time): (usize, f32)) -> f32 {
        let fade = rig.expressions[index].fade_in;
        if fade > 0.0 { ease(time / fade) } else { 1.0 }
    }

    /// Starts expression [index] (none for `None`), fading out the one playing.
    pub fn play(&mut self, rig: &Rig, index: Option<usize>) {
        if index.is_some() && self.current.map(|c| c.0) == index {
            return;
        }
        if let Some(current) = self.current {
            self.previous = Some((current.0, ExpressionPlayer::weight(rig, current), 0.0));
        }
        self.current = index.filter(|i| *i < rig.expressions.len()).map(|i| (i, 0.0));
    }

    pub fn playing(&self) -> Option<usize> {
        self.current.map(|c| c.0)
    }

    /// Advances by [dt] seconds and applies the expressions over [values].
    pub fn update(&mut self, rig: &Rig, dt: f32, values: &mut [f32]) {
        if let Some((index, start, time)) = &mut self.previous {
            *time += dt.max(0.0);
            let fade = rig.expressions[*index].fade_out;
            let weight = *start * (1.0 - if fade > 0.0 { ease(*time / fade) } else { 1.0 });
            if weight <= 0.0 {
                self.previous = None;
            } else {
                apply(&rig.expressions[*index], weight, values);
            }
        }
        if let Some((index, time)) = &mut self.current {
            *time += dt.max(0.0);
            let weight = ExpressionPlayer::weight(rig, (*index, *time));
            apply(&rig.expressions[*index], weight, values);
        }
    }
}
