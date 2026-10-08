//! The C ABI: one opaque handle per loaded rig, holding its evaluator, clip player and physics.
//! Strings and arrays returned stay valid until the handle is freed or, for pose data, until the
//! next evaluation. Every function tolerates a null handle. See `include/p2l_runtime.h`.

use crate::advanced::{Advanced, Affine};
use crate::behavior::Behaviors;
use crate::clip::Player;
use crate::eval::Evaluator;
use crate::expression::ExpressionPlayer;
use crate::pose::PosePlayer;
use crate::physics::Physics;
use crate::rig::{Rig, TextureKind};
use std::ffi::{c_char, CStr, CString};
use std::ptr;

pub struct Handle {
    rig: Rig,
    evaluator: Evaluator,
    player: Player,
    behaviors: Behaviors,
    physics: Physics,
    /// The pose the host sets, under clips, expressions, behaviors and physics.
    values: Vec<f32>,
    /// The values the last evaluation used: [values] with every layer applied, rebuilt from it each time.
    current: Vec<f32>,
    parameter_ids: Vec<CString>,
    mesh_ids: Vec<CString>,
    clip_ids: Vec<CString>,
    texture_uris: Vec<CString>,
    bone_ids: Vec<CString>,
    expression_ids: Vec<CString>,
    hit_area_ids: Vec<CString>,
    user_data: Vec<CString>,
    expressions: ExpressionPlayer,
    poses: PosePlayer,
    advanced: Advanced,
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
    p2l_rig_load_ex(bytes, len, 0, error, error_capacity)
}

impl Handle {
    /// A handle on [rig] at its defaults, evaluated once.
    pub fn new(rig: Rig) -> Handle {
        let values = rig.defaults();
        let physics = Physics::new(&rig);
        let mut handle = Handle {
            parameter_ids: c_strings(rig.parameters.iter().map(|p| &p.id)),
            mesh_ids: c_strings(rig.meshes.iter().map(|m| &m.id)),
            clip_ids: c_strings(rig.clips.iter().map(|c| &c.id)),
            texture_uris: c_strings(rig.textures.iter().map(|t| &t.uri)),
            bone_ids: c_strings(rig.extensions.bones.iter().map(|(_, id)| id)),
            expression_ids: c_strings(rig.expressions.iter().map(|e| &e.id)),
            hit_area_ids: c_strings(rig.hit_areas.iter().map(|h| &h.0)),
            user_data: {
                let mut texts = vec![String::new(); rig.meshes.len()];
                for (m, text) in &rig.user_data {
                    texts[*m] = text.clone();
                }
                c_strings(texts.iter())
            },
            expressions: ExpressionPlayer::new(),
            poses: PosePlayer::new(&rig),
            advanced: Advanced::new(),
            evaluator: Evaluator::new(),
            player: Player::new(),
            behaviors: Behaviors::default(),
            physics,
            current: values.clone(),
            values,
            render_order: Vec::new(),
            rig,
        };
        evaluate(&mut handle);
        handle
    }
}

/// Load flag: check the CRC of every chunk that carries one.
pub const LOAD_VERIFY_CRC: u32 = 1;

/// [p2l_rig_load] with [flags], `LOAD_VERIFY_CRC` or 0.
#[no_mangle]
pub unsafe extern "C" fn p2l_rig_load_ex(bytes: *const u8, len: usize, flags: u32, error: *mut c_char, error_capacity: usize) -> *mut Handle {
    if bytes.is_null() {
        write_error("No rig bytes", error, error_capacity);
        return ptr::null_mut();
    }
    let data = std::slice::from_raw_parts(bytes, len);
    match std::panic::catch_unwind(|| Rig::read_with(data, flags & LOAD_VERIFY_CRC != 0)) {
        Ok(Ok(rig)) => {
            Box::into_raw(Box::new(Handle::new(rig)))
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
    let advanced = if handle.advanced.enabled() != 0 { Some(&mut handle.advanced) } else { None };
    let pose = handle.evaluator.evaluate_ext(&handle.rig, &handle.current, advanced);
    handle.render_order = crate::eval::render_order(&handle.rig, pose);
    handle.poses.apply(&handle.rig, &mut handle.evaluator.pose);
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

/// The pose the host sets, one value per parameter: what every update and evaluation starts from. Clips,
/// expressions, behaviors and physics apply over a copy of it, so additive layers never accumulate here.
#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_values(handle: *mut Handle) -> *mut f32 {
    with_mut!(handle, ptr::null_mut(), |h| h.values.as_mut_ptr())
}

/// The values the last evaluation used, after clips, expressions, behaviors and physics; read-only.
#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_current(handle: *const Handle) -> *const f32 {
    with!(handle, ptr::null(), |h| h.current.as_ptr())
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

/// Deforms the rig at the host's pose as it is, without clips, behaviors or physics.
#[no_mangle]
pub unsafe extern "C" fn p2l_evaluate(handle: *mut Handle) {
    with_mut!(handle, (), |h| {
        h.current.clone_from(&h.values);
        evaluate(h);
        h.advanced.show_simulations(&h.rig, &mut h.evaluator.pose);
    })
}

/// Advances clips, expressions, behaviors and physics by [dt] seconds over the host's pose, then evaluates.
#[no_mangle]
pub unsafe extern "C" fn p2l_update(handle: *mut Handle, dt: f32) {
    with_mut!(handle, (), |h| {
        // Every layer starts from the host's pose afresh, so blinks, sways and added expressions never pile up.
        h.current.clone_from(&h.values);
        h.player.update(&h.rig, dt, &mut h.current);
        h.expressions.update(&h.rig, dt, &mut h.current);
        h.behaviors.update(&h.rig, dt, &mut h.current);
        h.poses.update(&h.rig, dt);
        let skip = h.advanced.skipped_physics(&h.rig);
        h.physics.step_skipping(&h.rig, dt, &mut h.current, &skip);
        evaluate(h);
        if h.advanced.enabled() & crate::advanced::SIM != 0 {
            let values: Vec<f32> = h.rig.parameters.iter().zip(&h.current).map(|(p, v)| p.normalize(*v)).collect();
            h.advanced.step_simulations(&h.rig, &values, dt, &mut h.evaluator.pose);
        }
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

/// Blend mode (0 normal, 1 add, 2 multiply as Cubism draws them, then the extended modes in the IR's
/// order: 3 add, 4 add glow, 5 darken, 6 multiply, ...) and
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

/// The page's PNG bytes; [len] receives their length, [width] and [height] the page size. Null for a page
/// that is not an embedded PNG (see p2l_texture_info).
#[no_mangle]
pub unsafe extern "C" fn p2l_texture_png(handle: *const Handle, index: u32, len: *mut usize, width: *mut u32, height: *mut u32) -> *const u8 {
    with!(handle, ptr::null(), |h| match h.rig.textures.get(index as usize) {
        Some(t) => {
            for (out, v) in [(width, t.width), (height, t.height)] {
                if !out.is_null() {
                    *out = v;
                }
            }
            if t.kind != TextureKind::Png {
                return ptr::null();
            }
            if !len.is_null() {
                *len = t.data.len();
            }
            t.data.as_ptr()
        }
        None => ptr::null(),
    })
}

/// The page's kind (0 PNG, 1 KTX2, 2 a file next to the rig, -1 no such page) and size. [data] and [len]
/// receive embedded bytes (null for a file); the return value is the file name, empty for an embedded page.
#[no_mangle]
pub unsafe extern "C" fn p2l_texture_info(
    handle: *const Handle, index: u32, kind: *mut i32, width: *mut u32, height: *mut u32, data: *mut *const u8, len: *mut usize,
) -> *const c_char {
    with!(handle, ptr::null(), |h| {
        let t = h.rig.textures.get(index as usize);
        if !kind.is_null() {
            *kind = t.map_or(-1, |t| t.kind as i32);
        }
        let Some(t) = t else { return ptr::null() };
        for (out, v) in [(width, t.width), (height, t.height)] {
            if !out.is_null() {
                *out = v;
            }
        }
        if !data.is_null() {
            *data = if t.kind == TextureKind::External { ptr::null() } else { t.data.as_ptr() };
        }
        if !len.is_null() {
            *len = t.data.len();
        }
        h.texture_uris[index as usize].as_ptr()
    })
}

/// [len] bytes for the host to fill, as WebAssembly hosts need to pass a rig in; free with p2l_dealloc.
#[no_mangle]
pub extern "C" fn p2l_alloc(len: usize) -> *mut u8 {
    let mut bytes = Vec::<u8>::with_capacity(len);
    let pointer = bytes.as_mut_ptr();
    std::mem::forget(bytes);
    pointer
}

#[no_mangle]
pub unsafe extern "C" fn p2l_dealloc(pointer: *mut u8, len: usize) {
    if !pointer.is_null() {
        drop(Vec::from_raw_parts(pointer, 0, len));
    }
}

/// The `.p2lrt` versions and chunks this runtime reads: `"1,2 STRS/1 CANV/1 ..."`, the major versions, then
/// each chunk tag with the newest version understood.
#[no_mangle]
pub extern "C" fn p2l_format_support() -> *const c_char {
    static SUPPORT: std::sync::OnceLock<CString> = std::sync::OnceLock::new();
    SUPPORT
        .get_or_init(|| {
            let chunks: Vec<String> = crate::rig::CHUNKS.iter().map(|(tag, v)| format!("{}/{}", tag, v)).collect();
            CString::new(format!("1,{} {}", crate::rig::VERSION, chunks.join(" "))).unwrap()
        })
        .as_ptr()
}

// --- expressions, hit areas and user data ---

#[no_mangle]
pub unsafe extern "C" fn p2l_expression_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.rig.expressions.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_expression_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| h.expression_ids.get(index as usize).map_or(ptr::null(), |s| s.as_ptr()))
}

/// Fades expression [index] in over the motion, fading the last one out; -1 fades it out to none.
#[no_mangle]
pub unsafe extern "C" fn p2l_expression(handle: *mut Handle, index: i32) {
    with_mut!(handle, (), |h| h.expressions.play(&h.rig, usize::try_from(index).ok()))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_hit_area_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.rig.hit_areas.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_hit_area_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| h.hit_area_ids.get(index as usize).map_or(ptr::null(), |s| s.as_ptr()))
}

/// The first hit area (in file order) one of whose visible meshes covers canvas point ([x], [y]) at the last
/// evaluation, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_hit_test(handle: *const Handle, x: f32, y: f32) -> i32 {
    with!(handle, -1, |h| crate::eval::hit_area_at(&h.rig, &h.evaluator.pose, x, y).map_or(-1, |i| i as i32))
}

/// The meshes hit area [index] covers, written into [out] up to [capacity]; returns how many there are.
#[no_mangle]
pub unsafe extern "C" fn p2l_hit_area_meshes(handle: *const Handle, index: u32, out: *mut u32, capacity: u32) -> u32 {
    with!(handle, 0, |h| {
        let Some((_, _, meshes)) = h.rig.hit_areas.get(index as usize) else { return 0 };
        if !out.is_null() {
            for (i, m) in meshes.iter().take(capacity as usize).enumerate() {
                *out.add(i) = *m as u32;
            }
        }
        meshes.len() as u32
    })
}

#[no_mangle]
pub unsafe extern "C" fn p2l_pose_group_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.rig.poses.len() as u32)
}

/// How many parts pose group [group] switches between.
#[no_mangle]
pub unsafe extern "C" fn p2l_pose_group_size(handle: *const Handle, group: u32) -> u32 {
    with!(handle, 0, |h| h.rig.poses.get(group as usize).map_or(0, |g| g.entries.len() as u32))
}

/// Shows part [entry] of pose group [group], fading the others out over the next updates.
#[no_mangle]
pub unsafe extern "C" fn p2l_pose_show(handle: *mut Handle, group: u32, entry: u32) -> bool {
    with_mut!(handle, false, |h| h.poses.show(&h.rig, group as usize, entry as usize))
}

/// The part pose group [group] shows, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_pose_shown(handle: *const Handle, group: u32) -> i32 {
    with!(handle, -1, |h| h.poses.shown(group as usize).map_or(-1, |e| e as i32))
}

/// Mesh [index]'s user data, empty when it has none.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_user_data(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| h.user_data.get(index as usize).map_or(ptr::null(), |s| s.as_ptr()))
}

// --- advanced mode ---

/// The advanced features this rig's file carries: P2L_SKIN | P2L_EXACT_LINKS | ...
#[no_mangle]
pub unsafe extern "C" fn p2l_advanced_available(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| Advanced::available(&h.rig))
}

/// Turns on the advanced [features] the rig carries and the rest off (0 is the Cubism-equivalent evaluation);
/// returns those now on and re-evaluates.
#[no_mangle]
pub unsafe extern "C" fn p2l_set_advanced(handle: *mut Handle, features: u32) -> u32 {
    with_mut!(handle, 0, |h| {
        let on = h.advanced.set(&h.rig, features);
        evaluate(h);
        on
    })
}

/// Starts the simulations again from the next update's pose, at rest.
#[no_mangle]
pub unsafe extern "C" fn p2l_sim_reset(handle: *mut Handle) {
    with_mut!(handle, (), |h| h.advanced.reset_simulations())
}

/// The colliders as the last update placed them, six floats each (ax, ay, bx, by, radius a, radius b, canvas
/// pixels), written into [out] up to [capacity] colliders; returns how many there are.
#[no_mangle]
pub unsafe extern "C" fn p2l_sim_colliders(handle: *const Handle, out: *mut f32, capacity: u32) -> u32 {
    with!(handle, 0, |h| {
        let mut n = 0u32;
        for c in h.advanced.placed_colliders() {
            if !out.is_null() && n < capacity {
                for (k, v) in c.iter().enumerate() {
                    *out.add(n as usize * 6 + k) = *v;
                }
            }
            n += 1;
        }
        n
    })
}

/// Wind every simulation feels besides its own, canvas pixels per second² (x right, y down).
#[no_mangle]
pub unsafe extern "C" fn p2l_sim_wind(handle: *mut Handle, x: f32, y: f32) {
    with_mut!(handle, (), |h| h.advanced.set_wind(x, y))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_bone_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.rig.extensions.bones.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_bone_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| h.bone_ids.get(index as usize).map_or(ptr::null(), |s| s.as_ptr()))
}

/// The bone's frame at the last evaluation as a canvas-space affine map `[a, b, c, d, tx, ty]`
/// (x' = a x + b y + tx, y' = c x + d y + ty); false when there is no such bone.
#[no_mangle]
pub unsafe extern "C" fn p2l_bone_transform(handle: *mut Handle, index: u32, out: *mut f32) -> bool {
    with_mut!(handle, false, |h| {
        let Some((d, _)) = h.rig.extensions.bones.get(index as usize) else { return false };
        let m = match h.evaluator.pose.deformers.get(*d) {
            Some(t) => Affine::of(t),
            // A virtual bone: evaluated on request at the current values.
            None => {
                let values: Vec<f32> = h.rig.parameters.iter().zip(&h.current).map(|(p, v)| p.normalize(*v)).collect();
                h.advanced.bone_frame(&h.rig, &values, *d)
            }
        };
        let Some(m) = m else { return false };
        if !out.is_null() {
            for (i, v) in [m.a, m.b, m.c, m.d, m.tx, m.ty].into_iter().enumerate() {
                *out.add(i) = v;
            }
        }
        true
    })
}

/// The runtime's version, `major.minor.patch`.
#[no_mangle]
pub extern "C" fn p2l_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr() as *const c_char
}
