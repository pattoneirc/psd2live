//! The C ABI: one opaque handle per loaded rig, holding its evaluator, clip player and physics.
//! Strings and arrays returned stay valid until the handle is freed or, for pose data, until the
//! next evaluation. Every function tolerates a null handle. See `include/p2l_runtime.h`.

use crate::behavior::Behaviors;
use crate::clip::Player;
use crate::eval::Evaluator;
use crate::physics::Physics;
use crate::rig::Rig;
use std::ffi::{c_char, CStr, CString};
use std::ptr;

pub struct Handle {
    rig: Rig,
    evaluator: Evaluator,
    player: Player,
    behaviors: Behaviors,
    physics: Physics,
    values: Vec<f32>,
    parameter_ids: Vec<CString>,
    mesh_ids: Vec<CString>,
    clip_ids: Vec<CString>,
    render_order: Vec<u32>,
}

fn c_strings<'a>(ids: impl Iterator<Item = &'a String>) -> Vec<CString> {
    ids.map(|id| CString::new(id.replace('\0', "")).unwrap()).collect()
}

fn write_error(message: &str, error: *mut c_char, capacity: usize) {
    if error.is_null() || capacity == 0 {
        return;
    }
    let bytes = message.as_bytes();
    let n = bytes.len().min(capacity - 1);
    unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), error as *mut u8, n);
        *error.add(n) = 0;
    }
}

/// Loads a rig from [len] bytes; on failure returns null and writes a message into [error].
#[no_mangle]
pub unsafe extern "C" fn p2l_rig_load(bytes: *const u8, len: usize, error: *mut c_char, error_capacity: usize) -> *mut Handle {
    if bytes.is_null() {
        write_error("No rig bytes", error, error_capacity);
        return ptr::null_mut();
    }
    let data = std::slice::from_raw_parts(bytes, len);
    match std::panic::catch_unwind(|| Rig::read(data)) {
        Ok(Ok(rig)) => {
            let values = rig.defaults();
            let physics = Physics::new(&rig);
            let handle = Handle {
                parameter_ids: c_strings(rig.parameters.iter().map(|p| &p.id)),
                mesh_ids: c_strings(rig.meshes.iter().map(|m| &m.id)),
                clip_ids: c_strings(rig.clips.iter().map(|c| &c.id)),
                evaluator: Evaluator::new(),
                player: Player::new(),
                behaviors: Behaviors::default(),
                physics,
                values,
                render_order: Vec::new(),
                rig,
            };
            let mut handle = Box::new(handle);
            evaluate(&mut handle);
            Box::into_raw(handle)
        }
        Ok(Err(e)) => {
            write_error(&e.0, error, error_capacity);
            ptr::null_mut()
        }
        Err(_) => {
            write_error("The rig could not be read", error, error_capacity);
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn p2l_rig_free(handle: *mut Handle) {
    if !handle.is_null() {
        drop(Box::from_raw(handle));
    }
}

fn evaluate(handle: &mut Handle) {
    let pose = handle.evaluator.evaluate(&handle.rig, &handle.values);
    handle.render_order = crate::eval::render_order(&handle.rig, pose);
}

macro_rules! with {
    ($handle:expr, $default:expr, |$h:ident| $body:expr) => {{
        match ($handle as *const Handle).as_ref() {
            Some($h) => $body,
            None => $default,
        }
    }};
}

macro_rules! with_mut {
    ($handle:expr, $default:expr, |$h:ident| $body:expr) => {{
        match $handle.as_mut() {
            Some($h) => $body,
            None => $default,
        }
    }};
}

#[no_mangle]
pub unsafe extern "C" fn p2l_canvas(handle: *const Handle, width: *mut f32, height: *mut f32) {
    with!(handle, (), |h| {
        if !width.is_null() {
            *width = h.rig.canvas.width;
        }
        if !height.is_null() {
            *height = h.rig.canvas.height;
        }
    })
}

// --- parameters ---

#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.rig.parameters.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| h.parameter_ids.get(index as usize).map_or(ptr::null(), |s| s.as_ptr()))
}

/// The index of the parameter with [id], or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_index(handle: *const Handle, id: *const c_char) -> i32 {
    if id.is_null() {
        return -1;
    }
    let id = CStr::from_ptr(id).to_string_lossy();
    with!(handle, -1, |h| h.rig.parameter(&id).map_or(-1, |i| i as i32))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_range(handle: *const Handle, index: u32, min: *mut f32, max: *mut f32, default: *mut f32) -> bool {
    with!(handle, false, |h| match h.rig.parameters.get(index as usize) {
        Some(p) => {
            for (out, v) in [(min, p.min), (max, p.max), (default, p.default)] {
                if !out.is_null() {
                    *out = v;
                }
            }
            true
        }
        None => false,
    })
}

/// The current parameter values, one per parameter, that the next evaluation uses.
#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_values(handle: *mut Handle) -> *mut f32 {
    with_mut!(handle, ptr::null_mut(), |h| h.values.as_mut_ptr())
}

#[no_mangle]
pub unsafe extern "C" fn p2l_set_parameter(handle: *mut Handle, index: u32, value: f32) {
    with_mut!(handle, (), |h| if let Some(v) = h.values.get_mut(index as usize) {
        *v = value
    })
}

/// Indices of the parameters with [role] (such as EyeBlink or LipSync) into [out]; returns how many there are.
#[no_mangle]
pub unsafe extern "C" fn p2l_role_parameters(handle: *const Handle, role: *const c_char, out: *mut u32, capacity: u32) -> u32 {
    if role.is_null() {
        return 0;
    }
    let role = CStr::from_ptr(role).to_string_lossy();
    with!(handle, 0, |h| {
        let found: Vec<usize> = h.rig.roles.iter().filter(|r| r.role == role).flat_map(|r| r.parameters.iter().copied()).collect();
        if !out.is_null() {
            for (i, p) in found.iter().take(capacity as usize).enumerate() {
                *out.add(i) = *p as u32;
            }
        }
        found.len() as u32
    })
}

// --- evaluation ---

/// Deforms the rig at the current parameter values.
#[no_mangle]
pub unsafe extern "C" fn p2l_evaluate(handle: *mut Handle) {
    with_mut!(handle, (), |h| evaluate(h))
}

/// Advances clips, behaviors and physics by [dt] seconds over the current values, then evaluates.
#[no_mangle]
pub unsafe extern "C" fn p2l_update(handle: *mut Handle, dt: f32) {
    with_mut!(handle, (), |h| {
        h.player.update(&h.rig, dt, &mut h.values);
        h.behaviors.update(&h.rig, dt, &mut h.values);
        h.physics.step(&h.rig, dt, &mut h.values);
        evaluate(h)
    })
}

#[no_mangle]
pub unsafe extern "C" fn p2l_physics_reset(handle: *mut Handle) {
    with_mut!(handle, (), |h| h.physics = Physics::new(&h.rig))
}

// --- behaviors ---

/// Switches behaviors on: 1 blink, 2 breathing, 4 gaze, 8 lip sync. Blinking and breathing start on.
#[no_mangle]
pub unsafe extern "C" fn p2l_behaviors(handle: *mut Handle, flags: u32) {
    with_mut!(handle, (), |h| h.behaviors.enabled = flags)
}

/// Where to look, each axis -1..1 (x right, y up); gaze follows smoothly.
#[no_mangle]
pub unsafe extern "C" fn p2l_look_at(handle: *mut Handle, x: f32, y: f32) {
    with_mut!(handle, (), |h| h.behaviors.look_at(x, y))
}

/// The mouth opening 0..1 for lip sync.
#[no_mangle]
pub unsafe extern "C" fn p2l_lip_sync(handle: *mut Handle, level: f32) {
    with_mut!(handle, (), |h| h.behaviors.lip_sync(level))
}

// --- clips ---

#[no_mangle]
pub unsafe extern "C" fn p2l_clip_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.rig.clips.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_clip_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| h.clip_ids.get(index as usize).map_or(ptr::null(), |s| s.as_ptr()))
}

/// Starts clip [index], fading out the current one; -1 stops.
#[no_mangle]
pub unsafe extern "C" fn p2l_play(handle: *mut Handle, index: i32) {
    with_mut!(handle, (), |h| {
        if index < 0 {
            h.player.stop()
        } else if (index as usize) < h.rig.clips.len() {
            h.player.play(index as usize)
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn p2l_clip_finished(handle: *const Handle) -> bool {
    with!(handle, true, |h| h.player.finished(&h.rig))
}

// --- meshes ---

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.rig.meshes.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| h.mesh_ids.get(index as usize).map_or(ptr::null(), |s| s.as_ptr()))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_vertex_count(handle: *const Handle, index: u32) -> u32 {
    with!(handle, 0, |h| h.rig.meshes.get(index as usize).map_or(0, |m| m.vertex_count() as u32))
}

/// Texture coordinates, two per vertex.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_uvs(handle: *const Handle, index: u32) -> *const f32 {
    with!(handle, ptr::null(), |h| h.rig.meshes.get(index as usize).and_then(|m| m.geometry.as_ref()).map_or(ptr::null(), |g| g.uvs.as_ptr()))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_indices(handle: *const Handle, index: u32, count: *mut u32) -> *const u32 {
    with!(handle, ptr::null(), |h| match h.rig.meshes.get(index as usize).and_then(|m| m.geometry.as_ref()) {
        Some(g) => {
            if !count.is_null() {
                *count = g.indices.len() as u32;
            }
            g.indices.as_ptr()
        }
        None => {
            if !count.is_null() {
                *count = 0;
            }
            ptr::null()
        }
    })
}

/// The mesh's texture page, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_texture(handle: *const Handle, index: u32) -> i32 {
    with!(handle, -1, |h| h.rig.meshes.get(index as usize).map_or(-1, |m| m.page))
}

/// Blend mode (0 normal, 1 add, 2 multiply, then the extended modes in the IR's order) and
/// whether the mesh culls back faces.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_blend(handle: *const Handle, index: u32, culling: *mut bool) -> i32 {
    with!(handle, 0, |h| h.rig.meshes.get(index as usize).map_or(0, |m| {
        if !culling.is_null() {
            *culling = m.culling;
        }
        m.blend as i32
    }))
}

/// Meshes masking this one into [out]; returns how many there are. [inverted] reports an inverted mask.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_masks(handle: *const Handle, index: u32, out: *mut u32, capacity: u32, inverted: *mut bool) -> u32 {
    with!(handle, 0, |h| h.rig.meshes.get(index as usize).map_or(0, |m| {
        if !out.is_null() {
            for (i, mask) in m.masked_by.iter().take(capacity as usize).enumerate() {
                *out.add(i) = *mask as u32;
            }
        }
        if !inverted.is_null() {
            *inverted = m.invert_mask;
        }
        m.masked_by.len() as u32
    }))
}

/// Deformed vertices in canvas pixels (y down), two per vertex, from the last evaluation.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_vertices(handle: *const Handle, index: u32) -> *const f32 {
    with!(handle, ptr::null(), |h| h.evaluator.pose.vertices.get(index as usize).map_or(ptr::null(), |v| v.as_ptr()))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_opacity(handle: *const Handle, index: u32) -> f32 {
    with!(handle, 0.0, |h| h.evaluator.pose.opacity.get(index as usize).copied().unwrap_or(0.0))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_draw_order(handle: *const Handle, index: u32) -> f32 {
    with!(handle, 0.0, |h| h.evaluator.pose.draw_order.get(index as usize).copied().unwrap_or(0.0))
}

/// Multiply and screen colors, three floats each.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_colors(handle: *const Handle, index: u32, multiply: *mut f32, screen: *mut f32) {
    with!(handle, (), |h| {
        let pose = &h.evaluator.pose;
        if let (Some(m), Some(s)) = (pose.multiply.get(index as usize), pose.screen.get(index as usize)) {
            if !multiply.is_null() {
                ptr::copy_nonoverlapping(m.as_ptr(), multiply, 3);
            }
            if !screen.is_null() {
                ptr::copy_nonoverlapping(s.as_ptr(), screen, 3);
            }
        }
    })
}

/// Meshes back to front from the last evaluation; returns the count.
#[no_mangle]
pub unsafe extern "C" fn p2l_render_order(handle: *const Handle, out: *mut u32, capacity: u32) -> u32 {
    with!(handle, 0, |h| {
        if !out.is_null() {
            let n = h.render_order.len().min(capacity as usize);
            ptr::copy_nonoverlapping(h.render_order.as_ptr(), out, n);
        }
        h.render_order.len() as u32
    })
}

// --- textures ---

#[no_mangle]
pub unsafe extern "C" fn p2l_texture_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.rig.textures.len() as u32)
}

/// The page's PNG bytes; [len] receives their length, [width] and [height] the page size.
#[no_mangle]
pub unsafe extern "C" fn p2l_texture_png(handle: *const Handle, index: u32, len: *mut usize, width: *mut u32, height: *mut u32) -> *const u8 {
    with!(handle, ptr::null(), |h| match h.rig.textures.get(index as usize) {
        Some(t) => {
            for (out, v) in [(width, t.width), (height, t.height)] {
                if !out.is_null() {
                    *out = v;
                }
            }
            if !len.is_null() {
                *len = t.png.len();
            }
            t.png.as_ptr()
        }
        None => ptr::null(),
    })
}

/// The runtime's version, `major.minor.patch`.
#[no_mangle]
pub extern "C" fn p2l_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr() as *const c_char
}
