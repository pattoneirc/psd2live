//! Part poses, as Cubism's pose3: groups of parts of which one shows at a time (an arm drawn two ways), the
//! shown one fading in and the others out behind it, linked parts following their part. A mesh's opacity is
//! multiplied by the pose opacity of every part above it. A rig without poses is left as it is.

use crate::eval::Pose;
use crate::rig::{Child, Rig};

/// One pose group: its parts, each with the parts that follow it.
#[derive(Debug, Clone, PartialEq)]
pub struct PoseGroup {
    pub entries: Vec<(usize, Vec<usize>)>,
}

/// Cubism's fade rule: the share of the back part that may still show while the front one fades in.
const PHI: f32 = 0.5;
const BACK_OPACITY_THRESHOLD: f32 = 0.15;

/// The pose state of one rig instance.
#[derive(Debug, Default, Clone)]
pub struct PosePlayer {
    /// Per group, the entry to show.
    shown: Vec<usize>,
    /// Pose opacity of every part, 1 for parts in no group.
    opacity: Vec<f32>,
    /// Per mesh, the product of its ancestor parts' pose opacities.
    factor: Vec<f32>,
    /// Per mesh, the parts above it.
    ancestors: Vec<Vec<usize>>,
}

impl PosePlayer {
    /// Each group showing its first part, at once, as Cubism resets a pose.
    pub fn new(rig: &Rig) -> PosePlayer {
        let mut parent = vec![None; rig.parts.len()];
        let mut mesh_parent = vec![None; rig.meshes.len()];
        for (p, part) in rig.parts.iter().enumerate() {
            for child in &part.children {
                match child {
                    Child::Part(c) => parent[*c] = Some(p),
                    Child::Mesh(m) => mesh_parent[*m] = Some(p),
                }
            }
        }
        let ancestors = mesh_parent
            .iter()
            .map(|&first| {
                let mut chain = Vec::new();
                let mut at = first;
                while let Some(p) = at {
                    if chain.contains(&p) {
                        break;
                    }
                    chain.push(p);
                    at = parent[p];
                }
                chain
            })
            .collect();
        let mut player = PosePlayer { shown: vec![0; rig.poses.len()], opacity: vec![1.0; rig.parts.len()], factor: vec![1.0; rig.meshes.len()], ancestors };
        for group in &rig.poses {
            for (k, (part, links)) in group.entries.iter().enumerate() {
                let o = if k == 0 { 1.0 } else { 0.0 };
                player.opacity[*part] = o;
                for l in links {
                    player.opacity[*l] = o;
                }
            }
        }
        player.refresh();
        player
    }

    /// Shows entry [entry] of group [group], fading the others out.
    pub fn show(&mut self, rig: &Rig, group: usize, entry: usize) -> bool {
        match rig.poses.get(group) {
            Some(g) if entry < g.entries.len() => {
                self.shown[group] = entry;
                true
            }
            _ => false,
        }
    }

    pub fn shown(&self, group: usize) -> Option<usize> {
        self.shown.get(group).copied()
    }

    pub fn part_opacity(&self, part: usize) -> f32 {
        self.opacity.get(part).copied().unwrap_or(1.0)
    }

    /// Fades by [dt] seconds over [fade_in] seconds.
    pub fn update(&mut self, rig: &Rig, dt: f32) {
        if rig.poses.is_empty() {
            return;
        }
        let fade = rig.pose_fade_in.max(0.0);
        for (g, group) in rig.poses.iter().enumerate() {
            let shown = self.shown[g];
            let (front, _) = group.entries[shown];
            // The shown part fades in; the others may show only as much as the rule leaves the back.
            let mut new_opacity = if fade > 0.0 { (self.opacity[front] + dt.max(0.0) / fade).min(1.0) } else { 1.0 };
            if dt <= 0.0 {
                new_opacity = self.opacity[front];
            }
            let mut a1 = if new_opacity < PHI { new_opacity * (PHI - 1.0) / PHI + 1.0 } else { (1.0 - new_opacity) * PHI / (1.0 - PHI) };
            let back = (1.0 - a1) * (1.0 - new_opacity);
            if back > BACK_OPACITY_THRESHOLD {
                a1 = 1.0 - BACK_OPACITY_THRESHOLD / (1.0 - new_opacity);
            }
            for (k, (part, links)) in group.entries.iter().enumerate() {
                let o = if k == shown { new_opacity } else { self.opacity[*part].min(a1.max(0.0)) };
                self.opacity[*part] = o;
                for l in links {
                    self.opacity[*l] = o;
                }
            }
        }
        self.refresh();
    }

    fn refresh(&mut self) {
        for (m, chain) in self.ancestors.iter().enumerate() {
            self.factor[m] = chain.iter().map(|p| self.opacity[*p]).product();
        }
    }

    /// Multiplies each mesh's opacity in [pose] by its parts' pose opacities.
    pub fn apply(&self, rig: &Rig, pose: &mut Pose) {
        if rig.poses.is_empty() {
            return;
        }
        for (o, f) in pose.opacity.iter_mut().zip(&self.factor) {
            *o *= f;
        }
    }
}
