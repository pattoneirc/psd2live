//! The C ABI. A model holds what one `.p2lrt` file defines, read-only and shared; each rig handle created
//! from it holds one character's state: parameters, clip player, physics and the last pose. Strings and
//! arrays returned stay valid until the handle is freed or, for pose data, until the next evaluation.
//! Every function tolerates a null handle. A panic never unwinds into the host: the handle fails and acts
//! as a null one from then on (`p2l_rig_failure`). See `include/p2l_runtime.h`.

use crate::advanced::{Advanced, Affine};
use crate::behavior::Behaviors;
use crate::clip::{Effects, Player};
use crate::eval::{render_commands, render_order, Evaluator};
use crate::expression::ExpressionPlayer;
use crate::pose::PosePlayer;
use crate::physics::Physics;
use crate::rig::{Child, Composite, GuiLabel, RenderGroup, RenderNode, Rig, TextureKind};
use std::any::Any;
use std::cell::OnceCell;
use std::ffi::{c_char, CStr, CString};
use std::panic::{self, AssertUnwindSafe};
use std::ptr;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

/// The ABI version, `p2l_abi_version`: the major changes when a function changes or goes, the minor when
/// functions are added. Kept equal to `P2L_ABI_VERSION_MAJOR` / `_MINOR` in the header.
pub const ABI_MAJOR: u32 = 1;
pub const ABI_MINOR: u32 = 4;

/// `p2l_update_stages` bits: the layers an update runs, in this order.
pub const STAGE_CLIPS: u32 = 1;
pub const STAGE_EXPRESSIONS: u32 = 2;
pub const STAGE_BEHAVIORS: u32 = 4;
pub const STAGE_POSES: u32 = 8;
pub const STAGE_PHYSICS: u32 = 16;
pub const STAGE_SIM: u32 = 32;
pub const STAGE_ALL: u32 = 63;

/// `p2l_render` flags: straight (not premultiplied) output, and drawing over what the image holds.
pub const RENDER_STRAIGHT: u32 = 1;
pub const RENDER_KEEP: u32 = 2;

/// `p2l_mesh_changes` bits: whether the mesh draws, then what changed since the evaluation before the last.
pub const MESH_VISIBLE: u32 = 1;
pub const MESH_VISIBILITY_CHANGED: u32 = 2;
pub const MESH_OPACITY_CHANGED: u32 = 4;
pub const MESH_DRAW_ORDER_CHANGED: u32 = 8;
pub const MESH_RENDER_ORDER_CHANGED: u32 = 16;
pub const MESH_VERTICES_CHANGED: u32 = 32;
pub const MESH_COLORS_CHANGED: u32 = 64;

/// How many clip layers a rig can play at once.
pub const MAX_LAYERS: u32 = 16;

/// `p2l_parameter_flags` bits.
pub const PARAMETER_REPEAT: u32 = 1;
pub const PARAMETER_BLEND_SHAPE: u32 = 2;

/// `p2l_part_flags` bits.
pub const PART_VISIBLE: u32 = 1;
pub const PART_SKETCH: u32 = 2;

/// How an isolated part's group composites, as the render tree (or else the part) gives it.
struct Group {
    blend: i32,
    alpha_blend: i32,
    invert_mask: bool,
    /// The meshes masking the group, the masking parts' meshes included.
    masks: Vec<u32>,
}

/// What every rig loaded from one file shares, read-only: the rig and the strings and indices handed out about it.
pub struct Model {
    rig: Rig,
    parameter_ids: Vec<CString>,
    parameter_names: Vec<CString>,
    mesh_ids: Vec<CString>,
    mesh_names: Vec<CString>,
    clip_ids: Vec<CString>,
    clip_names: Vec<CString>,
    clip_groups: Vec<CString>,
    texture_uris: Vec<CString>,
    bone_ids: Vec<CString>,
    expression_ids: Vec<CString>,
    expression_names: Vec<CString>,
    hit_area_ids: Vec<CString>,
    hit_area_names: Vec<CString>,
    user_data: Vec<CString>,
    part_ids: Vec<CString>,
    part_names: Vec<CString>,
    gui_ids: Vec<CString>,
    gui_names: Vec<CString>,
    gui_presets: Vec<CString>,
    meta_keys: Vec<CString>,
    meta_values: Vec<CString>,
    /// Per clip, the text of each of its events.
    event_values: Vec<Vec<CString>>,
    /// Each embedded PNG page decoded once, on the first render that needs it.
    decoded: Vec<std::sync::OnceLock<Option<crate::png::Image>>>,
    /// Per mesh, the part holding it, or -1.
    mesh_part: Vec<i32>,
    /// Per part, the part holding it, or -1.
    part_parent: Vec<i32>,
    groups: Vec<Group>,
}

fn c_string(text: &str) -> CString {
    CString::new(text.replace('\0', "")).unwrap()
}

fn c_strings<'a>(texts: impl Iterator<Item = &'a String>) -> Vec<CString> {
    texts.map(|t| c_string(t)).collect()
}

impl Model {
    pub fn new(rig: Rig) -> Model {
        let mut mesh_part = vec![-1; rig.meshes.len()];
        let mut part_parent = vec![-1; rig.parts.len()];
        for (p, part) in rig.parts.iter().enumerate() {
            for child in &part.children {
                match *child {
                    Child::Mesh(m) => mesh_part[m] = p as i32,
                    Child::Part(c) => part_parent[c] = p as i32,
                }
            }
        }
        // The part's own composite, unless the render tree gives its group one.
        let mut composites: Vec<&Composite> = rig.parts.iter().map(|p| &p.composite).collect();
        fn from_tree<'a>(g: &'a RenderGroup, out: &mut Vec<&'a Composite>) {
            if let (Some(p), Some(c)) = (g.part, &g.composite) {
                if let Some(slot) = out.get_mut(p) {
                    *slot = c;
                }
            }
            for child in &g.children {
                if let RenderNode::Group(child) = child {
                    from_tree(child, out);
                }
            }
        }
        from_tree(&rig.render, &mut composites);
        let groups = composites
            .iter()
            .map(|c| {
                let mut masks: Vec<u32> = c.masked_by.iter().map(|m| *m as u32).collect();
                for &p in &c.masked_by_parts {
                    for m in part_meshes(&rig, p) {
                        if !masks.contains(&(m as u32)) {
                            masks.push(m as u32);
                        }
                    }
                }
                Group { blend: c.blend as i32, alpha_blend: c.alpha_blend as i32, invert_mask: c.invert_mask, masks }
            })
            .collect();
        let mut user_data = vec![String::new(); rig.meshes.len()];
        for (m, text) in &rig.user_data {
            user_data[*m] = text.clone();
        }
        let gui = rig.gui.as_ref();
        let gui_nodes = gui.map_or(&[][..], |g| &g.nodes[..]);
        Model {
            parameter_ids: c_strings(rig.parameters.iter().map(|p| &p.id)),
            parameter_names: c_strings(rig.parameters.iter().map(|p| &p.name)),
            mesh_ids: c_strings(rig.meshes.iter().map(|m| &m.id)),
            mesh_names: c_strings(rig.meshes.iter().map(|m| &m.name)),
            clip_ids: c_strings(rig.clips.iter().map(|c| &c.id)),
            clip_names: c_strings(rig.clips.iter().map(|c| &c.name)),
            clip_groups: c_strings(rig.clips.iter().map(|c| &c.group)),
            texture_uris: c_strings(rig.textures.iter().map(|t| &t.uri)),
            bone_ids: c_strings(rig.extensions.bones.iter().map(|(_, id)| id)),
            expression_ids: c_strings(rig.expressions.iter().map(|e| &e.id)),
            expression_names: c_strings(rig.expressions.iter().map(|e| &e.name)),
            hit_area_ids: c_strings(rig.hit_areas.iter().map(|h| &h.0)),
            hit_area_names: c_strings(rig.hit_areas.iter().map(|h| &h.1)),
            user_data: c_strings(user_data.iter()),
            part_ids: c_strings(rig.parts.iter().map(|p| &p.id)),
            part_names: c_strings(rig.parts.iter().map(|p| &p.name)),
            gui_ids: c_strings(gui_nodes.iter().map(|n| &n.id)),
            gui_names: c_strings(gui_nodes.iter().map(|n| &n.name)),
            gui_presets: gui_nodes.iter().map(|n| c_string(if let GuiLabel::Preset(p) = &n.label { p } else { "" })).collect(),
            meta_keys: c_strings(rig.meta.iter().map(|(k, _)| k)),
            meta_values: c_strings(rig.meta.iter().map(|(_, v)| v)),
            event_values: rig.clips.iter().map(|c| c_strings(c.extras.events.iter().map(|(_, v)| v))).collect(),
            decoded: rig.textures.iter().map(|_| std::sync::OnceLock::new()).collect(),
            mesh_part,
            part_parent,
            groups,
            rig,
        }
    }
}

/// The meshes under [part], however deep.
fn part_meshes(rig: &Rig, part: usize) -> Vec<usize> {
    let mut out = Vec::new();
    let mut stack = vec![part];
    let mut seen = vec![false; rig.parts.len()];
    while let Some(p) = stack.pop() {
        if p >= seen.len() || std::mem::replace(&mut seen[p], true) {
            continue;
        }
        for child in &rig.parts[p].children {
            match *child {
                Child::Mesh(m) => out.push(m),
                Child::Part(c) => stack.push(c),
            }
        }
    }
    out
}

/// Rigs created so far, so each after the first blinks on its own seed.
static INSTANCES: AtomicU32 = AtomicU32::new(0);

/// One clip layer: its player, the priority of what it plays, and its weight over the layers below.
struct Layer {
    player: Player,
    priority: u32,
    weight: f32,
}

impl Layer {
    fn new() -> Layer {
        Layer { player: Player::new(), priority: 0, weight: 1.0 }
    }
}

type Colors = ([f32; 3], [f32; 3]);

pub struct Handle {
    model: Arc<Model>,
    evaluator: Evaluator,
    /// Clip layers, applied in order; layer 0 is the one p2l_play drives.
    layers: Vec<Layer>,
    /// Events the last update's clips passed: layer, clip and event.
    events: Vec<(u32, usize, usize)>,
    /// The rig's opacity: the host's, and the one clips set.
    opacity: f32,
    clip_opacity: f32,
    /// Colors the host puts in place of a mesh's, or of every mesh under a part.
    mesh_colors: Vec<Option<Colors>>,
    part_colors: Vec<Option<Colors>>,
    /// Textures the host gave pages in place of the file's, for the software renderer.
    textures: Vec<Option<crate::png::Image>>,
    /// What each mesh showed at the evaluation before the last, and what changed since.
    shown: Shown,
    changes: Vec<u32>,
    behaviors: Behaviors,
    physics: Physics,
    /// The pose the host sets, under clips, expressions, behaviors and physics.
    values: Vec<f32>,
    /// The values the last evaluation used: [values] with every layer applied, rebuilt from it each time.
    current: Vec<f32>,
    /// The host's layer over clips and expressions: per parameter a value and the weight it takes over with.
    overrides: Vec<(f32, f32)>,
    expressions: ExpressionPlayer,
    poses: PosePlayer,
    advanced: Advanced,
    render_order: Vec<u32>,
    render_commands: Vec<i32>,
    /// Why a call on this handle panicked; once set, the handle acts as a null one.
    failure: OnceCell<CString>,
}

/// The message a panic carried.
fn panic_message(payload: &(dyn Any + Send)) -> String {
    payload.downcast_ref::<&str>().map(|s| s.to_string())
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "unknown panic".to_string())
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

impl Handle {
    /// A handle on [model] at its defaults, evaluated once.
    pub fn new(model: Arc<Model>) -> Handle {
        let rig = &model.rig;
        let values = rig.defaults();
        let mut behaviors = Behaviors::default();
        let instance = INSTANCES.fetch_add(1, Ordering::Relaxed);
        if instance > 0 {
            behaviors.seed(instance.wrapping_mul(0x9e37_79b9) ^ 0x2545_f491);
        }
        let mut handle = Handle {
            physics: Physics::new(rig),
            poses: PosePlayer::new(rig),
            overrides: vec![(0.0, 0.0); rig.parameters.len()],
            expressions: ExpressionPlayer::new(),
            advanced: Advanced::new(),
            failure: OnceCell::new(),
            evaluator: Evaluator::new(),
            layers: vec![Layer::new()],
            events: Vec::new(),
            opacity: 1.0,
            clip_opacity: 1.0,
            mesh_colors: vec![None; rig.meshes.len()],
            part_colors: vec![None; rig.parts.len()],
            textures: vec![None; rig.textures.len()],
            shown: Shown::default(),
            changes: Vec::new(),
            behaviors,
            current: values.clone(),
            values,
            render_order: Vec::new(),
            render_commands: Vec::new(),
            model,
        };
        evaluate(&mut handle);
        track_changes(&mut handle);
        handle
    }

    /// Records the panic a call ended in; the handle stays failed.
    fn fail(&self, payload: Box<dyn Any + Send>) {
        let message = panic_message(payload.as_ref()).replace('\0', "");
        crate::host::log(crate::host::ERROR, &format!("The rig failed: {}", message));
        let _ = self.failure.set(CString::new(message).unwrap());
    }
}

/// Load flag: check the CRC of every chunk that carries one.
pub const LOAD_VERIFY_CRC: u32 = 1;

/// The model [read] gives; a message when it does not read or panics.
fn read_model(read: impl FnOnce() -> crate::rig::Result<Rig>) -> Result<Arc<Model>, String> {
    let model = match panic::catch_unwind(AssertUnwindSafe(|| read().map(|rig| Arc::new(Model::new(rig))))) {
        Ok(Ok(model)) => Ok(model),
        Ok(Err(e)) => Err(e.0),
        Err(payload) => Err(format!("The rig could not be read: {}", panic_message(payload.as_ref()))),
    };
    model.inspect_err(|message| crate::host::log(crate::host::ERROR, message))
}

/// A rig handle on [model], evaluated once; null with a message in [error] when that first evaluation panics.
fn create(model: Arc<Model>, error: *mut c_char, error_capacity: usize) -> *mut Handle {
    match panic::catch_unwind(AssertUnwindSafe(|| Handle::new(model))) {
        Ok(handle) => Box::into_raw(Box::new(handle)),
        Err(payload) => {
            let message = format!("The rig could not be read: {}", panic_message(payload.as_ref()));
            crate::host::log(crate::host::ERROR, &message);
            write_error(&message, error, error_capacity);
            ptr::null_mut()
        }
    }
}

/// What every mesh showed at an evaluation, to tell what the next one changes.
#[derive(Default)]
struct Shown {
    vertices: Vec<Vec<f32>>,
    opacity: Vec<f32>,
    draw_order: Vec<f32>,
    multiply: Vec<crate::rig::Rgb>,
    screen: Vec<crate::rig::Rgb>,
    /// Each mesh's place in the render order, -1 when it does not draw.
    place: Vec<i64>,
}

/// Sets each mesh's MESH_* bits from the pose against the one shown before (everything changed the first time).
fn track_changes(h: &mut Handle) {
    let pose = &h.evaluator.pose;
    let n = h.model.rig.meshes.len();
    let mut place = vec![-1i64; n];
    for (k, &m) in h.render_order.iter().enumerate() {
        if let Some(p) = place.get_mut(m as usize) {
            *p = k as i64;
        }
    }
    let first = h.shown.place.len() != n;
    h.changes.clear();
    for m in 0..n {
        let opacity = pose.opacity.get(m).copied().unwrap_or(0.0);
        let visible = place[m] >= 0 && opacity > 0.0;
        let mut bits = if visible { MESH_VISIBLE } else { 0 };
        if first {
            bits |= MESH_VISIBILITY_CHANGED | MESH_OPACITY_CHANGED | MESH_DRAW_ORDER_CHANGED | MESH_RENDER_ORDER_CHANGED | MESH_VERTICES_CHANGED | MESH_COLORS_CHANGED;
        } else {
            let was_visible = h.shown.place[m] >= 0 && h.shown.opacity[m] > 0.0;
            let changed = |bit: u32, differs: bool| if differs { bit } else { 0 };
            bits |= changed(MESH_VISIBILITY_CHANGED, visible != was_visible)
                | changed(MESH_OPACITY_CHANGED, opacity.to_bits() != h.shown.opacity[m].to_bits())
                | changed(MESH_DRAW_ORDER_CHANGED, pose.draw_order.get(m).map(|d| d.to_bits()) != Some(h.shown.draw_order[m].to_bits()))
                | changed(MESH_RENDER_ORDER_CHANGED, place[m] != h.shown.place[m])
                | changed(MESH_VERTICES_CHANGED, pose.vertices.get(m).map_or(&[][..], |v| &v[..]) != &h.shown.vertices[m][..])
                | changed(MESH_COLORS_CHANGED, pose.multiply.get(m) != h.shown.multiply.get(m) || pose.screen.get(m) != h.shown.screen.get(m));
        }
        h.changes.push(bits);
    }
    let shown = &mut h.shown;
    shown.vertices.resize(n, Vec::new());
    for (m, v) in pose.vertices.iter().enumerate().take(n) {
        shown.vertices[m].clone_from(v);
    }
    shown.opacity.clone_from(&pose.opacity);
    shown.opacity.resize(n, 0.0);
    shown.draw_order.clone_from(&pose.draw_order);
    shown.draw_order.resize(n, 0.0);
    shown.multiply.clone_from(&pose.multiply);
    shown.screen.clone_from(&pose.screen);
    shown.place = place;
}

/// A rig handle on the rig [read] gives; null with a message in [error] when it does not read or panics,
/// reading or in its first evaluation.
fn open(read: impl FnOnce() -> crate::rig::Result<Rig>, error: *mut c_char, error_capacity: usize) -> *mut Handle {
    match read_model(read) {
        Ok(model) => create(model, error, error_capacity),
        Err(message) => {
            write_error(&message, error, error_capacity);
            ptr::null_mut()
        }
    }
}

/// Loads a rig from [len] bytes; on failure returns null and writes a message into [error].
#[no_mangle]
pub unsafe extern "C" fn p2l_rig_load(bytes: *const u8, len: usize, error: *mut c_char, error_capacity: usize) -> *mut Handle {
    p2l_rig_load_ex(bytes, len, 0, error, error_capacity)
}

/// [p2l_rig_load] with [flags], `LOAD_VERIFY_CRC` or 0.
#[no_mangle]
pub unsafe extern "C" fn p2l_rig_load_ex(bytes: *const u8, len: usize, flags: u32, error: *mut c_char, error_capacity: usize) -> *mut Handle {
    if bytes.is_null() {
        write_error("No rig bytes", error, error_capacity);
        return ptr::null_mut();
    }
    let data = std::slice::from_raw_parts(bytes, len);
    open(|| Rig::read_with(data, flags & LOAD_VERIFY_CRC != 0), error, error_capacity)
}

/// Loads a model to create rigs from, with [flags] as [p2l_rig_load_ex]; on failure returns null and writes a
/// message into [error].
#[no_mangle]
pub unsafe extern "C" fn p2l_model_load(bytes: *const u8, len: usize, flags: u32, error: *mut c_char, error_capacity: usize) -> *const Model {
    if bytes.is_null() {
        write_error("No rig bytes", error, error_capacity);
        return ptr::null();
    }
    let data = std::slice::from_raw_parts(bytes, len);
    match read_model(|| Rig::read_with(data, flags & LOAD_VERIFY_CRC != 0)) {
        Ok(model) => Arc::into_raw(model),
        Err(message) => {
            write_error(&message, error, error_capacity);
            ptr::null()
        }
    }
}

/// Sends what goes wrong (loads that fail, rigs that fail, pages that do not decode) to [log] with [user]; null for
/// none. Any thread may call it.
#[no_mangle]
pub unsafe extern "C" fn p2l_set_log(log: Option<crate::host::LogFunction>, user: *mut std::ffi::c_void) {
    crate::host::set_log(log, user)
}

/// Takes all the runtime's memory from [allocate] and [free] from now on; call it first, from one thread, before
/// anything else of the runtime. False, changing nothing, once the runtime has taken memory from the system, for a
/// missing function, or in a build without the host-allocator feature.
#[no_mangle]
pub unsafe extern "C" fn p2l_set_allocator(
    allocate: Option<unsafe extern "C" fn(usize, usize, *mut std::ffi::c_void) -> *mut std::ffi::c_void>,
    free: Option<unsafe extern "C" fn(*mut std::ffi::c_void, usize, usize, *mut std::ffi::c_void)>,
    user: *mut std::ffi::c_void,
) -> bool {
    #[cfg(feature = "host-allocator")]
    return crate::host::set_allocator(allocate, free, user);
    #[cfg(not(feature = "host-allocator"))]
    {
        let _ = (allocate, free, user);
        false
    }
}

/// Lets go of [model]; rigs created from it keep it until they are freed.
#[no_mangle]
pub unsafe extern "C" fn p2l_model_free(model: *const Model) {
    if !model.is_null() {
        let model = Arc::from_raw(model);
        let _ = panic::catch_unwind(AssertUnwindSafe(|| drop(model)));
    }
}

/// A rig on [model] at its defaults, sharing the model with every other rig created from it; null with a
/// message in [error] when its first evaluation fails.
#[no_mangle]
pub unsafe extern "C" fn p2l_rig_create(model: *const Model, error: *mut c_char, error_capacity: usize) -> *mut Handle {
    if model.is_null() {
        write_error("No model", error, error_capacity);
        return ptr::null_mut();
    }
    Arc::increment_strong_count(model);
    create(Arc::from_raw(model), error, error_capacity)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_rig_free(handle: *mut Handle) {
    if !handle.is_null() {
        let handle = Box::from_raw(handle);
        let _ = panic::catch_unwind(AssertUnwindSafe(|| drop(handle)));
    }
}

/// Why a call on the handle panicked, after which it acts as a null handle; null while it works.
#[no_mangle]
pub unsafe extern "C" fn p2l_rig_failure(handle: *const Handle) -> *const c_char {
    match handle.as_ref().and_then(|h| h.failure.get()) {
        Some(message) => message.as_ptr(),
        None => ptr::null(),
    }
}

fn evaluate(handle: &mut Handle) {
    let rig = &handle.model.rig;
    let advanced = if handle.advanced.enabled() != 0 { Some(&mut handle.advanced) } else { None };
    let pose = handle.evaluator.evaluate_ext(rig, &handle.current, advanced);
    handle.render_order = render_order(rig, pose);
    handle.render_commands = render_commands(rig, pose);
    handle.poses.apply(rig, &mut handle.evaluator.pose);
    let pose = &mut handle.evaluator.pose;
    let opacity = handle.opacity * handle.clip_opacity;
    if opacity != 1.0 {
        pose.opacity.iter_mut().for_each(|o| *o *= opacity);
    }
    if handle.mesh_colors.iter().chain(&handle.part_colors).any(Option::is_some) {
        let model = &handle.model;
        for m in 0..pose.multiply.len() {
            // The mesh's own colors, else those of the nearest part above it that has some.
            let mut colors = handle.mesh_colors[m];
            let mut part = model.mesh_part[m];
            let mut steps = 0;
            while colors.is_none() && part >= 0 && steps <= model.part_parent.len() {
                colors = handle.part_colors[part as usize];
                part = model.part_parent[part as usize];
                steps += 1;
            }
            if let Some((multiply, screen)) = colors {
                pose.multiply[m] = multiply;
                pose.screen[m] = screen;
            }
        }
    }
}

/// Runs the layers in [stages] over the host's pose by [dt] seconds, then evaluates.
fn update(h: &mut Handle, dt: f32, stages: u32) {
    // Every layer starts from the host's pose afresh, so blinks, sways and added expressions never pile up.
    h.current.clone_from(&h.values);
    let rig = &h.model.rig;
    if stages & STAGE_CLIPS != 0 {
        h.events.clear();
        let mut effects = Effects::default();
        for (l, layer) in h.layers.iter_mut().enumerate() {
            layer.player.update_layer(rig, dt, layer.weight, &mut h.current, &mut effects);
            h.events.extend(effects.events.drain(..).map(|(c, e)| (l as u32, c, e)));
            if layer.player.finished(rig) {
                layer.priority = 0;
            }
        }
        h.poses.set_clip_opacities(&effects.part_opacity);
        h.clip_opacity = effects.model_opacity.map_or(1.0, |o| if o.is_finite() { o.clamp(0.0, 1.0) } else { 1.0 });
    }
    if stages & STAGE_EXPRESSIONS != 0 {
        h.expressions.update(rig, dt, &mut h.current);
    }
    for (value, &(target, weight)) in h.current.iter_mut().zip(&h.overrides) {
        if weight > 0.0 {
            *value += (target - *value) * weight;
        }
    }
    if stages & STAGE_BEHAVIORS != 0 {
        h.behaviors.update(rig, dt, &mut h.current);
    }
    if stages & STAGE_POSES != 0 {
        h.poses.update(rig, dt);
    }
    if stages & STAGE_PHYSICS != 0 {
        let skip = h.advanced.skipped_physics(rig);
        h.physics.step_skipping(rig, dt, &mut h.current, &skip);
    }
    evaluate(h);
    let rig = &h.model.rig;
    if stages & STAGE_SIM != 0 && h.advanced.enabled() & crate::advanced::SIM != 0 {
        let values: Vec<f32> = rig.parameters.iter().zip(&h.current).map(|(p, v)| p.normalize(*v)).collect();
        h.advanced.step_simulations(rig, &values, dt, &mut h.evaluator.pose);
    } else {
        h.advanced.show_simulations(rig, &mut h.evaluator.pose);
    }
    track_changes(h);
}

/// Runs [body] on a working handle, [default] for a null or failed one. A panic in [body] fails the handle and
/// gives [default] instead of unwinding into the host.
macro_rules! with {
    ($handle:expr, $default:expr, |$h:ident| $body:expr) => {{
        match ($handle as *const Handle).as_ref() {
            Some($h) if $h.failure.get().is_none() => match panic::catch_unwind(AssertUnwindSafe(|| $body)) {
                Ok(value) => value,
                Err(payload) => {
                    $h.fail(payload);
                    $default
                }
            },
            _ => $default,
        }
    }};
}

macro_rules! with_mut {
    ($handle:expr, $default:expr, |$h:ident| $body:expr) => {{
        match $handle.as_mut() {
            Some($h) if $h.failure.get().is_none() => match panic::catch_unwind(AssertUnwindSafe(|| $body)) {
                Ok(value) => value,
                Err(payload) => {
                    $h.fail(payload);
                    $default
                }
            },
            _ => $default,
        }
    }};
}

/// The string at [index] of [strings], or null.
fn string_at(strings: &[CString], index: u32) -> *const c_char {
    strings.get(index as usize).map_or(ptr::null(), |s| s.as_ptr())
}

/// Writes [values] into [out] up to [capacity]; returns how many there are.
unsafe fn write_all<T: Copy>(values: &[T], out: *mut T, capacity: u32) -> u32 {
    if !out.is_null() {
        let n = values.len().min(capacity as usize);
        ptr::copy_nonoverlapping(values.as_ptr(), out, n);
    }
    values.len() as u32
}

unsafe fn put<T>(out: *mut T, value: T) {
    if !out.is_null() {
        *out = value;
    }
}

#[no_mangle]
pub unsafe extern "C" fn p2l_canvas(handle: *const Handle, width: *mut f32, height: *mut f32) {
    with!(handle, (), |h| {
        put(width, h.model.rig.canvas.width);
        put(height, h.model.rig.canvas.height);
    })
}

/// Where the canvas origin sits in canvas pixels and how many pixels make a model unit (0 when the file names none).
#[no_mangle]
pub unsafe extern "C" fn p2l_canvas_origin(handle: *const Handle, x: *mut f32, y: *mut f32, pixels_per_unit: *mut f32) {
    with!(handle, (), |h| {
        let canvas = &h.model.rig.canvas;
        put(x, canvas.origin_x);
        put(y, canvas.origin_y);
        put(pixels_per_unit, canvas.pixels_per_unit.unwrap_or(0.0));
    })
}

// --- parameters ---

#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.rig.parameters.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.parameter_ids, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_name(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.parameter_names, index))
}

/// The index of the parameter with [id] as [p2l_parameter_id] gives it, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_index(handle: *const Handle, id: *const c_char) -> i32 {
    if id.is_null() {
        return -1;
    }
    let id = CStr::from_ptr(id);
    with!(handle, -1, |h| h.model.parameter_ids.iter().position(|s| s.as_c_str() == id).map_or(-1, |i| i as i32))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_range(handle: *const Handle, index: u32, min: *mut f32, max: *mut f32, default: *mut f32) -> bool {
    with!(handle, false, |h| match h.model.rig.parameters.get(index as usize) {
        Some(p) => {
            put(min, p.min);
            put(max, p.max);
            put(default, p.default);
            true
        }
        None => false,
    })
}

/// PARAMETER_REPEAT when values beyond the range wrap, PARAMETER_BLEND_SHAPE for a blend shape's parameter.
#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_flags(handle: *const Handle, index: u32) -> u32 {
    with!(handle, 0, |h| h.model.rig.parameters.get(index as usize).map_or(0, |p| {
        (if p.repeat { PARAMETER_REPEAT } else { 0 }) | (if p.blend { PARAMETER_BLEND_SHAPE } else { 0 })
    }))
}

/// The values a parameter panel snaps the parameter to, ascending, into [out] up to [capacity]; returns how many.
#[no_mangle]
pub unsafe extern "C" fn p2l_parameter_snaps(handle: *const Handle, index: u32, out: *mut f32, capacity: u32) -> u32 {
    with!(handle, 0, |h| {
        let snaps = h.model.rig.gui.as_ref().and_then(|g| g.snaps.iter().find(|(p, _)| *p == index as usize));
        snaps.map_or(0, |(_, values)| write_all(values, out, capacity))
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

/// Sets the host's layer on parameter [index]: after clips and expressions, before behaviors and physics, the
/// value moves toward [value] by [weight] 0..1. Weight 0 takes the layer off the parameter.
#[no_mangle]
pub unsafe extern "C" fn p2l_set_parameter_override(handle: *mut Handle, index: u32, value: f32, weight: f32) -> bool {
    with_mut!(handle, false, |h| match h.overrides.get_mut(index as usize) {
        Some(slot) if value.is_finite() => {
            *slot = (value, if weight.is_finite() { weight.clamp(0.0, 1.0) } else { 0.0 });
            true
        }
        _ => false,
    })
}

/// Takes the host's layer off every parameter.
#[no_mangle]
pub unsafe extern "C" fn p2l_clear_parameter_overrides(handle: *mut Handle) {
    with_mut!(handle, (), |h| h.overrides.iter_mut().for_each(|o| *o = (0.0, 0.0)))
}

/// Indices of the parameters with [role] (such as EyeBlink or LipSync) into [out]; returns how many there are.
#[no_mangle]
pub unsafe extern "C" fn p2l_role_parameters(handle: *const Handle, role: *const c_char, out: *mut u32, capacity: u32) -> u32 {
    if role.is_null() {
        return 0;
    }
    let role = CStr::from_ptr(role).to_string_lossy();
    with!(handle, 0, |h| {
        let found: Vec<u32> = h.model.rig.roles.iter().filter(|r| r.role == role).flat_map(|r| r.parameters.iter().map(|p| *p as u32)).collect();
        write_all(&found, out, capacity)
    })
}

// --- parameter panel ---

/// Pairs of parameters a panel shows as one two-dimensional control, horizontal then vertical, two indices
/// each, into [out] up to [capacity] pairs; returns how many pairs there are.
#[no_mangle]
pub unsafe extern "C" fn p2l_gui_joysticks(handle: *const Handle, out: *mut u32, capacity: u32) -> u32 {
    with!(handle, 0, |h| {
        let pairs: Vec<u32> = h.model.rig.gui.as_ref().map_or(Vec::new(), |g| g.joysticks.iter().flat_map(|(x, y)| [*x as u32, *y as u32]).collect());
        if !out.is_null() {
            let n = (pairs.len() / 2).min(capacity as usize) * 2;
            ptr::copy_nonoverlapping(pairs.as_ptr(), out, n);
        }
        (pairs.len() / 2) as u32
    })
}

#[no_mangle]
pub unsafe extern "C" fn p2l_gui_node_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.gui_ids.len() as u32)
}

/// Panel node [index] in pre-order: the parameter a leaf shows (-1 for a group), how many nodes follow as its
/// children, and whether the group starts open; false when there is no such node.
#[no_mangle]
pub unsafe extern "C" fn p2l_gui_node(handle: *const Handle, index: u32, parameter: *mut i32, children: *mut u32, open: *mut bool) -> bool {
    with!(handle, false, |h| match h.model.rig.gui.as_ref().and_then(|g| g.nodes.get(index as usize)) {
        Some(node) => {
            put(parameter, node.parameter.map_or(-1, |p| p as i32));
            put(children, node.children as u32);
            put(open, node.open);
            true
        }
        None => false,
    })
}

#[no_mangle]
pub unsafe extern "C" fn p2l_gui_node_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.gui_ids, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_gui_node_name(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.gui_names, index))
}

/// Node [index]'s label: 0 none, 1 a preset named in [preset], 2 a custom color in [argb]; -1 for no such node.
#[no_mangle]
pub unsafe extern "C" fn p2l_gui_node_label(handle: *const Handle, index: u32, preset: *mut *const c_char, argb: *mut u32) -> i32 {
    with!(handle, -1, |h| match h.model.rig.gui.as_ref().and_then(|g| g.nodes.get(index as usize)) {
        Some(node) => {
            put(preset, h.model.gui_presets[index as usize].as_ptr());
            put(argb, if let GuiLabel::Custom(c) = node.label { c } else { 0 });
            match node.label {
                GuiLabel::None => 0,
                GuiLabel::Preset(_) => 1,
                GuiLabel::Custom(_) => 2,
            }
        }
        None => -1,
    })
}

// --- evaluation ---

/// Deforms the rig at the host's pose as it is, without clips, behaviors or physics.
#[no_mangle]
pub unsafe extern "C" fn p2l_evaluate(handle: *mut Handle) {
    with_mut!(handle, (), |h| {
        h.current.clone_from(&h.values);
        h.poses.set_clip_opacities(&[]);
        h.clip_opacity = 1.0;
        evaluate(h);
        h.advanced.show_simulations(&h.model.rig, &mut h.evaluator.pose);
        track_changes(h);
    })
}

/// Advances clips, expressions, behaviors and physics by [dt] seconds over the host's pose, then evaluates.
#[no_mangle]
pub unsafe extern "C" fn p2l_update(handle: *mut Handle, dt: f32) {
    with_mut!(handle, (), |h| update(h, dt, STAGE_ALL))
}

/// [p2l_update] running only the layers in [stages]; the others neither advance nor apply.
#[no_mangle]
pub unsafe extern "C" fn p2l_update_stages(handle: *mut Handle, dt: f32, stages: u32) {
    with_mut!(handle, (), |h| update(h, dt, stages))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_physics_reset(handle: *mut Handle) {
    with_mut!(handle, (), |h| {
        let wind = h.physics.wind;
        h.physics = Physics::new(&h.model.rig);
        h.physics.wind = wind;
    })
}

/// Wind on every pendulum besides gravity, in physics units (x right, y down), as Cubism's physics wind.
#[no_mangle]
pub unsafe extern "C" fn p2l_physics_wind(handle: *mut Handle, x: f32, y: f32) {
    with_mut!(handle, (), |h| if x.is_finite() && y.is_finite() {
        h.physics.wind = (x, y)
    })
}

/// Hangs every pendulum at rest under the last evaluation's pose, as Cubism's stabilization does, and evaluates.
#[no_mangle]
pub unsafe extern "C" fn p2l_physics_stabilize(handle: *mut Handle) {
    with_mut!(handle, (), |h| {
        let mut values = h.current.clone();
        h.physics.stabilize(&h.model.rig, &mut values);
        h.current = values;
        evaluate(h);
        track_changes(h);
    })
}

/// The rig's opacity, 0..1, multiplying every mesh on top of what clips set (1 until set).
#[no_mangle]
pub unsafe extern "C" fn p2l_set_opacity(handle: *mut Handle, opacity: f32) {
    with_mut!(handle, (), |h| if opacity.is_finite() {
        h.opacity = opacity.clamp(0.0, 1.0)
    })
}

/// The rig's opacity at the last update: the host's times the one clips set.
#[no_mangle]
pub unsafe extern "C" fn p2l_opacity(handle: *const Handle) -> f32 {
    with!(handle, 0.0, |h| h.opacity * h.clip_opacity)
}

/// Puts [multiply] and [screen] (three floats each) in place of the mesh's evaluated colors from the next
/// evaluation; both null gives the mesh its own back. False for no such mesh.
#[no_mangle]
pub unsafe extern "C" fn p2l_set_mesh_colors(handle: *mut Handle, index: u32, multiply: *const f32, screen: *const f32) -> bool {
    with_mut!(handle, false, |h| match h.mesh_colors.get_mut(index as usize) {
        Some(slot) => {
            *slot = colors_from(multiply, screen);
            true
        }
        None => false,
    })
}

/// [p2l_set_mesh_colors] for every mesh under the part that has no colors of its own set.
#[no_mangle]
pub unsafe extern "C" fn p2l_set_part_colors(handle: *mut Handle, index: u32, multiply: *const f32, screen: *const f32) -> bool {
    with_mut!(handle, false, |h| match h.part_colors.get_mut(index as usize) {
        Some(slot) => {
            *slot = colors_from(multiply, screen);
            true
        }
        None => false,
    })
}

/// Colors the host gives: null for none when both are null, white multiply and black screen standing in for a
/// null one.
unsafe fn colors_from(multiply: *const f32, screen: *const f32) -> Option<Colors> {
    if multiply.is_null() && screen.is_null() {
        return None;
    }
    let read = |p: *const f32, default: [f32; 3]| if p.is_null() { default } else { [*p, *p.add(1), *p.add(2)] };
    Some((read(multiply, crate::rig::WHITE), read(screen, crate::rig::BLACK)))
}

// --- behaviors ---

/// Switches behaviors on: 1 blink, 2 breathing, 4 gaze, 8 lip sync. Blinking and breathing start on.
#[no_mangle]
pub unsafe extern "C" fn p2l_behaviors(handle: *mut Handle, flags: u32) {
    with_mut!(handle, (), |h| h.behaviors.enabled = flags)
}

/// Blink timing in seconds: the interval drawn evenly between [interval_min] and [interval_max], and how long
/// the eyes take to close, stay closed and open; false, changing nothing, for negative or reversed times.
#[no_mangle]
pub unsafe extern "C" fn p2l_blink_settings(handle: *mut Handle, interval_min: f32, interval_max: f32, closing: f32, closed: f32, opening: f32) -> bool {
    with_mut!(handle, false, |h| {
        let times = [interval_min, interval_max, closing, closed, opening];
        if times.iter().any(|t| !t.is_finite() || *t < 0.0) || interval_max < interval_min || closing <= 0.0 || opening <= 0.0 {
            return false;
        }
        let s = &mut h.behaviors.settings;
        s.blink_interval = (interval_min, interval_max);
        (s.blink_closing, s.blink_closed, s.blink_opening) = (closing, closed, opening);
        true
    })
}

/// How strongly breathing sways and gaze turns (1 as they are), and how quickly gaze follows (1 settles in about
/// a third of a second); false, changing nothing, for negative values.
#[no_mangle]
pub unsafe extern "C" fn p2l_behavior_strength(handle: *mut Handle, sway: f32, look: f32, look_speed: f32) -> bool {
    with_mut!(handle, false, |h| {
        if [sway, look, look_speed].iter().any(|v| !v.is_finite() || *v < 0.0) {
            return false;
        }
        let s = &mut h.behaviors.settings;
        (s.sway, s.look, s.look_speed) = (sway, look, look_speed);
        true
    })
}

/// Lip sync from audio: the root mean square of [count] samples (-1..1) times [gain], clamped to 0..1, becomes
/// the mouth opening; returns it.
#[no_mangle]
pub unsafe extern "C" fn p2l_lip_sync_samples(handle: *mut Handle, samples: *const f32, count: usize, gain: f32) -> f32 {
    with_mut!(handle, 0.0, |h| {
        let samples = if samples.is_null() || count == 0 { &[][..] } else { std::slice::from_raw_parts(samples, count) };
        h.behaviors.lip_sync_samples(samples, gain)
    })
}

/// Reseeds the blink timing; rigs after the first get seeds of their own.
#[no_mangle]
pub unsafe extern "C" fn p2l_behavior_seed(handle: *mut Handle, seed: u32) {
    with_mut!(handle, (), |h| h.behaviors.seed(seed))
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
    with!(handle, 0, |h| h.model.rig.clips.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_clip_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.clip_ids, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_clip_name(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.clip_names, index))
}

/// The group a clip belongs to (such as "Idle"), empty when none.
#[no_mangle]
pub unsafe extern "C" fn p2l_clip_group(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.clip_groups, index))
}

/// Clip [index]'s length in seconds, frame rate, whether it loops and its fade times (the 1 second default when
/// the file names none); false when there is no such clip.
#[no_mangle]
pub unsafe extern "C" fn p2l_clip_info(
    handle: *const Handle, index: u32, duration: *mut f32, fps: *mut f32, looping: *mut bool, fade_in: *mut f32, fade_out: *mut f32,
) -> bool {
    with!(handle, false, |h| match h.model.rig.clips.get(index as usize) {
        Some(c) => {
            put(duration, c.duration);
            put(fps, c.fps);
            put(looping, c.looping);
            put(fade_in, c.fade_in.unwrap_or(1.0));
            put(fade_out, c.fade_out.unwrap_or(1.0));
            true
        }
        None => false,
    })
}

/// Starts clip [index], fading out the current one; -1 stops.
#[no_mangle]
pub unsafe extern "C" fn p2l_play(handle: *mut Handle, index: i32) {
    with_mut!(handle, (), |h| {
        if index < 0 {
            h.layers[0].player.stop();
            h.layers[0].priority = 0;
        } else if (index as usize) < h.model.rig.clips.len() {
            h.layers[0].player.play(index as usize);
            h.layers[0].priority = 0;
        }
    })
}

/// Starts clip [index] on [layer] (0 to MAX_LAYERS - 1) when [priority] is at least that of the clip the layer
/// plays (0 once it finishes or stops); -1 stops the layer. Returns whether it did.
#[no_mangle]
pub unsafe extern "C" fn p2l_play_layer(handle: *mut Handle, layer: u32, index: i32, priority: u32) -> bool {
    with_mut!(handle, false, |h| {
        if layer >= MAX_LAYERS || (index >= 0 && index as usize >= h.model.rig.clips.len()) {
            return false;
        }
        while h.layers.len() <= layer as usize {
            h.layers.push(Layer::new());
        }
        let l = &mut h.layers[layer as usize];
        if index < 0 {
            l.player.stop();
            l.priority = 0;
            return true;
        }
        let current = if l.player.finished(&h.model.rig) { 0 } else { l.priority };
        if priority < current {
            return false;
        }
        l.player.play(index as usize);
        l.priority = priority;
        true
    })
}

/// How much [layer] covers the layers below it, 0..1 (1 until set).
#[no_mangle]
pub unsafe extern "C" fn p2l_set_layer_weight(handle: *mut Handle, layer: u32, weight: f32) -> bool {
    with_mut!(handle, false, |h| {
        if layer >= MAX_LAYERS || !weight.is_finite() {
            return false;
        }
        while h.layers.len() <= layer as usize {
            h.layers.push(Layer::new());
        }
        h.layers[layer as usize].weight = weight.clamp(0.0, 1.0);
        true
    })
}

/// What [layer] plays: the clip (-1 for none), its time, its priority and whether it is a finished one-shot.
#[no_mangle]
pub unsafe extern "C" fn p2l_layer_state(handle: *const Handle, layer: u32, clip: *mut i32, time: *mut f32, priority: *mut u32, finished: *mut bool) -> bool {
    with!(handle, false, |h| {
        if layer >= MAX_LAYERS {
            return false;
        }
        let rig = &h.model.rig;
        match h.layers.get(layer as usize) {
            Some(l) => {
                let done = l.player.finished(rig);
                put(clip, l.player.playing().map_or(-1, |c| c as i32));
                put(time, l.player.time(rig));
                put(priority, if done { 0 } else { l.priority });
                put(finished, done);
            }
            None => {
                put(clip, -1);
                put(time, 0.0);
                put(priority, 0);
                put(finished, true);
            }
        }
        true
    })
}

/// Moves the clip [layer] plays to [time] seconds; the events it jumps over do not fire.
#[no_mangle]
pub unsafe extern "C" fn p2l_layer_seek(handle: *mut Handle, layer: u32, time: f32) {
    with_mut!(handle, (), |h| if let Some(l) = h.layers.get_mut(layer as usize) {
        l.player.seek(time)
    })
}

/// How many clip events the last update passed.
#[no_mangle]
pub unsafe extern "C" fn p2l_event_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.events.len() as u32)
}

/// Event [index] of the last update: its text, and the layer, clip and clip time it came from.
#[no_mangle]
pub unsafe extern "C" fn p2l_event(handle: *const Handle, index: u32, layer: *mut u32, clip: *mut i32, time: *mut f32) -> *const c_char {
    with!(handle, ptr::null(), |h| match h.events.get(index as usize) {
        Some(&(l, c, e)) => {
            put(layer, l);
            put(clip, c as i32);
            put(time, h.model.rig.clips[c].extras.events[e].0);
            h.model.event_values[c][e].as_ptr()
        }
        None => ptr::null(),
    })
}

/// The clip playing, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_clip_playing(handle: *const Handle) -> i32 {
    with!(handle, -1, |h| h.layers[0].player.playing().map_or(-1, |c| c as i32))
}

/// The playing clip's time in seconds, wrapped for a loop and held at the end for a one-shot.
#[no_mangle]
pub unsafe extern "C" fn p2l_clip_time(handle: *const Handle) -> f32 {
    with!(handle, 0.0, |h| h.layers[0].player.time(&h.model.rig))
}

/// Moves the playing clip to [time] seconds; its fade-in goes on as it was.
#[no_mangle]
pub unsafe extern "C" fn p2l_clip_seek(handle: *mut Handle, time: f32) {
    with_mut!(handle, (), |h| h.layers[0].player.seek(time))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_clip_finished(handle: *const Handle) -> bool {
    with!(handle, true, |h| h.layers[0].player.finished(&h.model.rig))
}

// --- meshes ---

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.rig.meshes.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.mesh_ids, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_name(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.mesh_names, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_vertex_count(handle: *const Handle, index: u32) -> u32 {
    with!(handle, 0, |h| h.model.rig.meshes.get(index as usize).map_or(0, |m| m.vertex_count() as u32))
}

/// Texture coordinates, two per vertex.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_uvs(handle: *const Handle, index: u32) -> *const f32 {
    with!(handle, ptr::null(), |h| h.model.rig.meshes.get(index as usize).and_then(|m| m.geometry.as_ref()).map_or(ptr::null(), |g| g.uvs.as_ptr()))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_indices(handle: *const Handle, index: u32, count: *mut u32) -> *const u32 {
    with!(handle, ptr::null(), |h| match h.model.rig.meshes.get(index as usize).and_then(|m| m.geometry.as_ref()) {
        Some(g) => {
            put(count, g.indices.len() as u32);
            g.indices.as_ptr()
        }
        None => {
            put(count, 0);
            ptr::null()
        }
    })
}

/// The mesh's texture page, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_texture(handle: *const Handle, index: u32) -> i32 {
    with!(handle, -1, |h| h.model.rig.meshes.get(index as usize).map_or(-1, |m| m.page))
}

/// Color blend mode (P2L_BLEND_*: 0 normal, 1 add, 2 multiply as Cubism draws them, then the extended modes
/// in the IR's order) and whether the mesh culls back faces; -1 for no such mesh.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_blend(handle: *const Handle, index: u32, culling: *mut bool) -> i32 {
    with!(handle, 0, |h| h.model.rig.meshes.get(index as usize).map_or(-1, |m| {
        put(culling, m.culling);
        m.blend as i32
    }))
}

/// Alpha blend mode (P2L_ALPHA_*: 0 over, 1 atop, 2 out, 3 conjoint over, 4 disjoint over); -1 for no such mesh.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_alpha_blend(handle: *const Handle, index: u32) -> i32 {
    with!(handle, 0, |h| h.model.rig.meshes.get(index as usize).map_or(-1, |m| m.alpha_blend as i32))
}

/// The part holding the mesh, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_part(handle: *const Handle, index: u32) -> i32 {
    with!(handle, -1, |h| h.model.mesh_part.get(index as usize).copied().unwrap_or(-1))
}

/// Meshes masking this one into [out]; returns how many there are. [inverted] reports an inverted mask.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_masks(handle: *const Handle, index: u32, out: *mut u32, capacity: u32, inverted: *mut bool) -> u32 {
    with!(handle, 0, |h| h.model.rig.meshes.get(index as usize).map_or(0, |m| {
        put(inverted, m.invert_mask);
        let masks: Vec<u32> = m.masked_by.iter().map(|m| *m as u32).collect();
        write_all(&masks, out, capacity)
    }))
}

/// Deformed vertices in canvas pixels (y down), two per vertex, from the last evaluation.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_vertices(handle: *const Handle, index: u32) -> *const f32 {
    with!(handle, ptr::null(), |h| h.evaluator.pose.vertices.get(index as usize).map_or(ptr::null(), |v| v.as_ptr()))
}

/// The mesh's opacity at the last evaluation; NaN for no such mesh.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_opacity(handle: *const Handle, index: u32) -> f32 {
    with!(handle, 0.0, |h| h.evaluator.pose.opacity.get(index as usize).copied().unwrap_or(f32::NAN))
}

/// The mesh's draw order at the last evaluation; NaN for no such mesh.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_draw_order(handle: *const Handle, index: u32) -> f32 {
    with!(handle, 0.0, |h| h.evaluator.pose.draw_order.get(index as usize).copied().unwrap_or(f32::NAN))
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

/// Mesh [index]'s MESH_* bits: whether it draws at the last evaluation, and what changed from the one before.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_changes(handle: *const Handle, index: u32) -> u32 {
    with!(handle, 0, |h| h.changes.get(index as usize).copied().unwrap_or(0))
}

/// Meshes back to front from the last evaluation; returns the count.
#[no_mangle]
pub unsafe extern "C" fn p2l_render_order(handle: *const Handle, out: *mut u32, capacity: u32) -> u32 {
    with!(handle, 0, |h| write_all(&h.render_order, out, capacity))
}

/// [p2l_render_order] with the isolated parts' groups kept: a mesh index, `-2 - part` opening the group of an
/// isolated part and -1 closing it; returns the count.
#[no_mangle]
pub unsafe extern "C" fn p2l_render_commands(handle: *const Handle, out: *mut i32, capacity: u32) -> u32 {
    with!(handle, 0, |h| write_all(&h.render_commands, out, capacity))
}

// --- parts ---

#[no_mangle]
pub unsafe extern "C" fn p2l_part_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.rig.parts.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_part_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.part_ids, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_part_name(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.part_names, index))
}

/// The part holding this one, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_part_parent(handle: *const Handle, index: u32) -> i32 {
    with!(handle, -1, |h| h.model.part_parent.get(index as usize).copied().unwrap_or(-1))
}

/// PART_VISIBLE and PART_SKETCH.
#[no_mangle]
pub unsafe extern "C" fn p2l_part_flags(handle: *const Handle, index: u32) -> u32 {
    with!(handle, 0, |h| h.model.rig.parts.get(index as usize).map_or(0, |p| {
        (if p.visible { PART_VISIBLE } else { 0 }) | (if p.sketch { PART_SKETCH } else { 0 })
    }))
}

/// How the part groups its meshes: 0 passes them through, 1 sorts them as one slot, 2 isolates them into a
/// layer composited with [blend], [alpha_blend] and an inverted mask when [invert_mask]; -1 for no such part.
#[no_mangle]
pub unsafe extern "C" fn p2l_part_group(handle: *const Handle, index: u32, blend: *mut i32, alpha_blend: *mut i32, invert_mask: *mut bool) -> i32 {
    with!(handle, -1, |h| match (h.model.rig.parts.get(index as usize), h.model.groups.get(index as usize)) {
        (Some(part), Some(group)) => {
            put(blend, group.blend);
            put(alpha_blend, group.alpha_blend);
            put(invert_mask, group.invert_mask);
            part.group_mode as i32
        }
        _ => -1,
    })
}

/// The meshes masking the part's isolated group, the masking parts' meshes included, into [out]; returns how many.
#[no_mangle]
pub unsafe extern "C" fn p2l_part_masks(handle: *const Handle, index: u32, out: *mut u32, capacity: u32) -> u32 {
    with!(handle, 0, |h| h.model.groups.get(index as usize).map_or(0, |g| write_all(&g.masks, out, capacity)))
}

/// The opacity, multiply and screen colors (three floats each) the part's isolated group composites with at the
/// last evaluation; false when there is no such part.
#[no_mangle]
pub unsafe extern "C" fn p2l_part_composite(handle: *const Handle, index: u32, opacity: *mut f32, multiply: *mut f32, screen: *mut f32) -> bool {
    with!(handle, false, |h| {
        let pose = &h.evaluator.pose;
        let i = index as usize;
        let (Some(o), Some(m), Some(s)) = (pose.part_opacity.get(i), pose.part_multiply.get(i), pose.part_screen.get(i)) else { return false };
        put(opacity, *o);
        if !multiply.is_null() {
            ptr::copy_nonoverlapping(m.as_ptr(), multiply, 3);
        }
        if !screen.is_null() {
            ptr::copy_nonoverlapping(s.as_ptr(), screen, 3);
        }
        true
    })
}

/// The opacity the host gave the part (1 unless set); NaN for no such part.
#[no_mangle]
pub unsafe extern "C" fn p2l_part_opacity(handle: *const Handle, index: u32) -> f32 {
    with!(handle, 0.0, |h| if (index as usize) < h.model.rig.parts.len() { h.poses.host_opacity(index as usize) } else { f32::NAN })
}

/// Gives the part an opacity, 0..1, that multiplies every mesh under it from the next evaluation, on top of
/// part poses; false when there is no such part.
#[no_mangle]
pub unsafe extern "C" fn p2l_set_part_opacity(handle: *mut Handle, index: u32, opacity: f32) -> bool {
    with_mut!(handle, false, |h| h.poses.set_host_opacity(index as usize, opacity))
}

// --- software rendering ---

/// Draws the last evaluation into [rgba], [width] x [height] RGBA8 pixels, rows top first, by the drawing rules
/// the header states. [transform] maps canvas pixels to image pixels as [a, b, c, d, tx, ty] (x' = a x + b y + tx,
/// y' = c x + d y + ty); null fits the canvas into the image from its top left corner. [flags]: RENDER_STRAIGHT for
/// straight alpha (premultiplied otherwise), RENDER_KEEP to draw over what [rgba] holds instead of transparency.
/// Embedded PNG pages decode themselves; other pages draw only once p2l_render_texture gives them pixels.
#[no_mangle]
pub unsafe extern "C" fn p2l_render(handle: *mut Handle, rgba: *mut u8, width: u32, height: u32, transform: *const f32, flags: u32) -> bool {
    if rgba.is_null() || width == 0 || height == 0 || (width as u64) * (height as u64) > (1 << 28) {
        return false;
    }
    let (w, h) = (width as usize, height as usize);
    with_mut!(handle, false, |h_| {
        let model = &h_.model;
        let rig = &model.rig;
        let transform = if transform.is_null() {
            let scale = (w as f32 / rig.canvas.width).min(h as f32 / rig.canvas.height);
            crate::render::Affine { a: scale, b: 0.0, c: 0.0, d: scale, tx: 0.0, ty: 0.0 }
        } else {
            let t = std::slice::from_raw_parts(transform, 6);
            crate::render::Affine { a: t[0], b: t[1], c: t[2], d: t[3], tx: t[4], ty: t[5] }
        };
        let textures: Vec<Option<&crate::png::Image>> = (0..rig.textures.len())
            .map(|p| {
                h_.textures[p].as_ref().or_else(|| {
                    let page = &rig.textures[p];
                    if page.kind != TextureKind::Png {
                        return None;
                    }
                    model.decoded[p]
                        .get_or_init(|| {
                            let image = crate::png::decode(&page.data);
                            if image.is_none() {
                                crate::host::log(crate::host::WARNING, &format!("Texture page {} is not a PNG the renderer reads", p));
                            }
                            image
                        })
                        .as_ref()
                })
            })
            .collect();
        let groups: Vec<crate::render::GroupStyle> = model.groups.iter()
            .map(|g| crate::render::GroupStyle { blend: g.blend as u8, alpha_blend: g.alpha_blend as u8, invert_mask: g.invert_mask, masks: &g.masks })
            .collect();
        let scene = crate::render::Scene { rig, pose: &h_.evaluator.pose, commands: &h_.render_commands, textures: &textures, groups: &groups };
        let image = std::slice::from_raw_parts_mut(rgba, w * h * 4);
        let straight = flags & RENDER_STRAIGHT != 0;
        let mut pixels = vec![0.0f32; w * h * 4];
        let keep = flags & RENDER_KEEP != 0;
        if keep {
            crate::render::from_rgba8(image, straight, &mut pixels);
        }
        crate::render::render(&scene, transform, w, h, &mut pixels, keep);
        crate::render::to_rgba8(&pixels, straight, image);
        true
    })
}

/// Gives page [page] [width] x [height] straight RGBA8 pixels for p2l_render, in place of the file's (a KTX2 page
/// or a file next to the rig, which the runtime does not decode); null takes them back. False for no such page.
#[no_mangle]
pub unsafe extern "C" fn p2l_render_texture(handle: *mut Handle, page: u32, rgba: *const u8, width: u32, height: u32) -> bool {
    with_mut!(handle, false, |h| {
        let Some(slot) = h.textures.get_mut(page as usize) else { return false };
        if rgba.is_null() {
            *slot = None;
            return true;
        }
        if width == 0 || height == 0 || (width as u64) * (height as u64) > (1 << 28) {
            return false;
        }
        let n = width as usize * height as usize * 4;
        *slot = Some(crate::png::Image { width, height, rgba: std::slice::from_raw_parts(rgba, n).to_vec() });
        true
    })
}

// --- textures ---

#[no_mangle]
pub unsafe extern "C" fn p2l_texture_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.rig.textures.len() as u32)
}

/// The page's PNG bytes; [len] receives their length, [width] and [height] the page size. Null, with [len] 0, for
/// a page that is not an embedded PNG (see p2l_texture_info).
#[no_mangle]
pub unsafe extern "C" fn p2l_texture_png(handle: *const Handle, index: u32, len: *mut usize, width: *mut u32, height: *mut u32) -> *const u8 {
    with!(handle, ptr::null(), |h| match h.model.rig.textures.get(index as usize) {
        Some(t) => {
            put(width, t.width);
            put(height, t.height);
            if t.kind != TextureKind::Png {
                put(len, 0);
                return ptr::null();
            }
            put(len, t.data.len());
            t.data.as_ptr()
        }
        None => {
            put(len, 0);
            ptr::null()
        }
    })
}

/// The page's kind (0 PNG, 1 KTX2, 2 a file next to the rig, -1 no such page) and size. [data] and [len]
/// receive embedded bytes (null for a file); the return value is the file name, empty for an embedded page.
#[no_mangle]
pub unsafe extern "C" fn p2l_texture_info(
    handle: *const Handle, index: u32, kind: *mut i32, width: *mut u32, height: *mut u32, data: *mut *const u8, len: *mut usize,
) -> *const c_char {
    with!(handle, ptr::null(), |h| {
        let t = h.model.rig.textures.get(index as usize);
        put(kind, t.map_or(-1, |t| t.kind as i32));
        let Some(t) = t else { return ptr::null() };
        put(width, t.width);
        put(height, t.height);
        put(data, if t.kind == TextureKind::External { ptr::null() } else { t.data.as_ptr() });
        put(len, t.data.len());
        h.model.texture_uris[index as usize].as_ptr()
    })
}

/// [len] zeroed bytes for the host to fill, as WebAssembly hosts need to pass a rig in; free with p2l_dealloc
/// and the same length.
#[no_mangle]
pub extern "C" fn p2l_alloc(len: usize) -> *mut u8 {
    Box::into_raw(vec![0u8; len].into_boxed_slice()) as *mut u8
}

#[no_mangle]
pub unsafe extern "C" fn p2l_dealloc(pointer: *mut u8, len: usize) {
    if !pointer.is_null() {
        drop(Box::from_raw(ptr::slice_from_raw_parts_mut(pointer, len)));
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

// --- expressions, hit areas, poses, user data and file information ---

#[no_mangle]
pub unsafe extern "C" fn p2l_expression_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.rig.expressions.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_expression_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.expression_ids, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_expression_name(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.expression_names, index))
}

/// Fades expression [index] in over the motion, fading the others out; -1 fades them all out.
#[no_mangle]
pub unsafe extern "C" fn p2l_expression(handle: *mut Handle, index: i32) {
    with_mut!(handle, (), |h| h.expressions.play(&h.model.rig, usize::try_from(index).ok()))
}

/// Fades expression [index] in over those playing; false when there is no such expression or it already plays.
#[no_mangle]
pub unsafe extern "C" fn p2l_expression_add(handle: *mut Handle, index: u32) -> bool {
    with_mut!(handle, false, |h| h.expressions.add(&h.model.rig, index as usize))
}

/// Fades expression [index] out, leaving the others; false when it is not playing.
#[no_mangle]
pub unsafe extern "C" fn p2l_expression_remove(handle: *mut Handle, index: u32) -> bool {
    with_mut!(handle, false, |h| h.expressions.remove(&h.model.rig, index as usize))
}

/// The expressions playing (not fading out), oldest first, into [out]; returns how many.
#[no_mangle]
pub unsafe extern "C" fn p2l_expressions_playing(handle: *const Handle, out: *mut u32, capacity: u32) -> u32 {
    with!(handle, 0, |h| write_all(&h.expressions.active().map(|i| i as u32).collect::<Vec<_>>(), out, capacity))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_hit_area_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.rig.hit_areas.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_hit_area_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.hit_area_ids, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_hit_area_name(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.hit_area_names, index))
}

/// The first hit area (in file order) one of whose visible meshes covers canvas point ([x], [y]) at the last
/// evaluation, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_hit_test(handle: *const Handle, x: f32, y: f32) -> i32 {
    with!(handle, -1, |h| crate::eval::hit_area_at(&h.model.rig, &h.evaluator.pose, x, y).map_or(-1, |i| i as i32))
}

/// The meshes hit area [index] covers, written into [out] up to [capacity]; returns how many there are.
#[no_mangle]
pub unsafe extern "C" fn p2l_hit_area_meshes(handle: *const Handle, index: u32, out: *mut u32, capacity: u32) -> u32 {
    with!(handle, 0, |h| {
        let Some((_, _, meshes)) = h.model.rig.hit_areas.get(index as usize) else { return 0 };
        let meshes: Vec<u32> = meshes.iter().map(|m| *m as u32).collect();
        write_all(&meshes, out, capacity)
    })
}

#[no_mangle]
pub unsafe extern "C" fn p2l_pose_group_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.rig.poses.len() as u32)
}

/// How many parts pose group [group] switches between.
#[no_mangle]
pub unsafe extern "C" fn p2l_pose_group_size(handle: *const Handle, group: u32) -> u32 {
    with!(handle, 0, |h| h.model.rig.poses.get(group as usize).map_or(0, |g| g.entries.len() as u32))
}

/// Shows part [entry] of pose group [group], fading the others out over the next updates.
#[no_mangle]
pub unsafe extern "C" fn p2l_pose_show(handle: *mut Handle, group: u32, entry: u32) -> bool {
    with_mut!(handle, false, |h| h.poses.show(&h.model.rig, group as usize, entry as usize))
}

/// The part pose group [group] shows, or -1.
#[no_mangle]
pub unsafe extern "C" fn p2l_pose_shown(handle: *const Handle, group: u32) -> i32 {
    with!(handle, -1, |h| h.poses.shown(group as usize).map_or(-1, |e| e as i32))
}

/// Mesh [index]'s user data, empty when it has none.
#[no_mangle]
pub unsafe extern "C" fn p2l_mesh_user_data(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.user_data, index))
}

/// How many key-value pairs the file's generator information holds.
#[no_mangle]
pub unsafe extern "C" fn p2l_meta_count(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| h.model.meta_keys.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_meta_key(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.meta_keys, index))
}

#[no_mangle]
pub unsafe extern "C" fn p2l_meta_value(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.meta_values, index))
}

// --- advanced mode ---

/// The advanced features this rig's file carries: P2L_SKIN | P2L_EXACT_LINKS | ...
#[no_mangle]
pub unsafe extern "C" fn p2l_advanced_available(handle: *const Handle) -> u32 {
    with!(handle, 0, |h| Advanced::available(&h.model.rig))
}

/// Turns on the advanced [features] the rig carries and the rest off (0 is the Cubism-equivalent evaluation);
/// returns those now on and re-evaluates.
#[no_mangle]
pub unsafe extern "C" fn p2l_set_advanced(handle: *mut Handle, features: u32) -> u32 {
    with_mut!(handle, 0, |h| {
        let on = h.advanced.set(&h.model.rig, features);
        evaluate(h);
        track_changes(h);
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
    with!(handle, 0, |h| h.model.rig.extensions.bones.len() as u32)
}

#[no_mangle]
pub unsafe extern "C" fn p2l_bone_id(handle: *const Handle, index: u32) -> *const c_char {
    with!(handle, ptr::null(), |h| string_at(&h.model.bone_ids, index))
}

/// The bone's frame at the last evaluation as a canvas-space affine map `[a, b, c, d, tx, ty]`
/// (x' = a x + b y + tx, y' = c x + d y + ty); false when there is no such bone.
#[no_mangle]
pub unsafe extern "C" fn p2l_bone_transform(handle: *mut Handle, index: u32, out: *mut f32) -> bool {
    with_mut!(handle, false, |h| {
        let rig = &h.model.rig;
        let Some((d, _)) = rig.extensions.bones.get(index as usize) else { return false };
        let m = match h.evaluator.pose.deformers.get(*d) {
            Some(t) => Affine::of(t),
            // A virtual bone: evaluated on request at the current values.
            None => {
                let values: Vec<f32> = rig.parameters.iter().zip(&h.current).map(|(p, v)| p.normalize(*v)).collect();
                h.advanced.bone_frame(rig, &values, *d)
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

/// The library's build version, `major.minor.patch`; [p2l_abi_version] tells which functions it has.
#[no_mangle]
pub extern "C" fn p2l_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr() as *const c_char
}

/// The ABI the library implements, `major << 16 | minor`.
#[no_mangle]
pub extern "C" fn p2l_abi_version() -> u32 {
    ABI_MAJOR << 16 | ABI_MINOR
}

#[cfg(test)]
#[path = "ffi_tests.rs"]
mod tests;
