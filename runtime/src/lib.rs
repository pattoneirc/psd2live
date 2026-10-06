//! The PSD2Live runtime: plays `.p2lrt` rigs compiled by PSD2Live.
//!
//! [`Rig::read`] loads a rig; [`Evaluator::evaluate`] deforms it at parameter values into per-mesh
//! vertices in canvas pixels. The C ABI in [`ffi`] wraps both for other languages.

pub mod behavior;
pub mod clip;
pub mod eval;
pub mod ffi;
pub mod physics;
pub mod rig;
pub mod warp;

pub use eval::{render_order, Evaluator, Pose, Transform};
pub use physics::Physics;
pub use rig::{Error, Rig};

#[cfg(test)]
mod tests;
