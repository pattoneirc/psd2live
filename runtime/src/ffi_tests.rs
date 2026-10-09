//! The C ABI: failures, versioning, shared models, update stages and what it reports about parts and clips.

use super::*;
use crate::rig::*;
use crate::tests::{mesh, parameter, rig};
use std::sync::Arc;

fn triangle(parent: Option<usize>) -> Rig {
    rig(vec![parameter("A", 0.0, 1.0, 0.0)], vec![], vec![mesh("M", parent, vec![0.0, 0.0, 10.0, 0.0, 0.0, 10.0])])
}

/// A clip holding parameter 0 at [value] for two seconds, fading in and out over a second.
fn hold(id: &str, value: f32) -> Clip {
    Clip {
        id: id.into(), name: id.into(), group: "Idle".into(), duration: 2.0, fps: 30.0, looping: true, fade_in: None, fade_out: None,
        curves: vec![Curve { parameter: 0, start_time: 0.0, start_value: value, segments: vec![Segment::Linear { time: 2.0, value }] }],
        extras: ClipExtras::default(),
    }
}

fn scalar_channel(parameter: usize, at_min: f32, at_max: f32) -> (Channel, Grid<ChannelValue>) {
    let axes = vec![Axis { parameter, keys: vec![0.0, 1.0] }];
    (Channel::Opacity, Grid::new(axes, vec![(vec![0], ChannelValue::Scalar(at_min)), (vec![1], ChannelValue::Scalar(at_max))]).unwrap())
}

/// A triangle in a part isolated into its own group, whose opacity follows parameter 0 from 0.2 to 0.8.
fn isolated() -> Rig {
    let mut r = triangle(None);
    r.parameters[0] = parameter("A", 0.0, 1.0, 0.0);
    let composite = Composite {
        blend: 10, alpha_blend: 1, masked_by: vec![], masked_by_parts: vec![0], invert_mask: true, opacity: 1.0, multiply: WHITE, screen: BLACK,
    };
    r.parts = vec![Part {
        id: "P".into(), name: "Part".into(), visible: true, sketch: false, group_mode: 2, draw_order: 500, children: vec![Child::Mesh(0)],
        channels: vec![], composite: composite.clone(), shapes: vec![],
    }];
    r.render.children = vec![RenderNode::Group(RenderGroup {
        part: Some(0), draw_order: 500, channels: vec![scalar_channel(0, 0.2, 0.8)], composite: Some(composite), children: vec![RenderNode::Mesh(0)],
    })];
    r
}

fn rig_on(r: Rig) -> *mut Handle {
    let handle = open(|| Ok(r), ptr::null_mut(), 0);
    assert!(!handle.is_null());
    handle
}

fn current(handle: *mut Handle) -> f32 {
    unsafe { *p2l_parameter_current(handle) }
}

#[test]
fn a_panic_fails_the_handle_instead_of_unwinding_into_the_host() {
    let handle = rig_on(triangle(None));
    unsafe {
        assert!(p2l_rig_failure(handle).is_null());
        assert_eq!(p2l_mesh_count(handle), 1);
        // A mesh under a deformer the rig does not have panics in the evaluation.
        Arc::get_mut(&mut (&mut *handle).model).unwrap().rig.meshes[0].parent = Some(7);
        p2l_update(handle, 0.1);
        let failure = p2l_rig_failure(handle);
        assert!(!failure.is_null());
        assert!(!CStr::from_ptr(failure).to_bytes().is_empty());
        // From then on the handle acts as a null one, reads and writes alike.
        assert_eq!(p2l_mesh_count(handle), 0);
        assert!(p2l_mesh_vertices(handle, 0).is_null());
        assert!(p2l_parameter_values(handle).is_null());
        p2l_evaluate(handle);
        p2l_rig_free(handle);
        assert!(p2l_rig_failure(ptr::null()).is_null());
    }
}

#[test]
fn a_panic_in_the_first_evaluation_fails_the_load_with_its_message() {
    let mut error = [0 as c_char; 256];
    let handle = open(|| Ok(triangle(Some(7))), error.as_mut_ptr(), error.len());
    assert!(handle.is_null());
    let message = unsafe { CStr::from_ptr(error.as_ptr()) }.to_string_lossy();
    assert!(message.starts_with("The rig could not be read: "), "{message}");
}

#[test]
fn the_header_declares_every_exported_function_and_this_abi_version() {
    let header = include_str!("../include/p2l_runtime.h");
    let source = include_str!("ffi.rs");
    let exported: Vec<&str> = source.split("extern \"C\" fn ").skip(1).map(|s| &s[..s.find('(').unwrap()]).collect();
    // Declarations: the first p2l_ name followed by a parenthesis on each line outside comments and macros.
    let declared: Vec<&str> = header.lines()
        .map(str::trim_start)
        .filter(|l| !l.starts_with("/*") && !l.starts_with('*') && !l.starts_with('#'))
        .filter_map(|l| {
            let start = l.find("p2l_")?;
            let name = &l[start..];
            let end = name.find(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))?;
            name[end..].starts_with('(').then(|| &name[..end])
        })
        .collect();
    let mut a = exported.clone();
    let mut b = declared.clone();
    a.sort_unstable();
    b.sort_unstable();
    assert_eq!(a, b);
    assert!(header.contains(&format!("#define P2L_ABI_VERSION_MAJOR {ABI_MAJOR}\n")));
    assert!(header.contains(&format!("#define P2L_ABI_VERSION_MINOR {ABI_MINOR}\n")));
    assert_eq!(p2l_abi_version(), ABI_MAJOR << 16 | ABI_MINOR);
    // The C# declarations generated from the header (bindings/csharp/generate.py) are up to date.
    let csharp = include_str!("../bindings/csharp/P2lNative.cs");
    for name in &exported {
        assert!(csharp.contains(&format!(" {name}(")), "{name} is missing from P2lNative.cs; run bindings/csharp/generate.py");
    }
    assert!(csharp.contains(&format!("ABI_VERSION_MINOR = {ABI_MINOR};")));
    // The constants the header names for the bits and modes the functions take and give.
    for (name, value) in [
        ("P2L_STAGE_CLIPS", STAGE_CLIPS), ("P2L_STAGE_EXPRESSIONS", STAGE_EXPRESSIONS), ("P2L_STAGE_BEHAVIORS", STAGE_BEHAVIORS),
        ("P2L_STAGE_POSES", STAGE_POSES), ("P2L_STAGE_PHYSICS", STAGE_PHYSICS), ("P2L_STAGE_SIM", STAGE_SIM), ("P2L_STAGE_ALL", STAGE_ALL),
        ("P2L_PARAMETER_REPEAT", PARAMETER_REPEAT), ("P2L_PARAMETER_BLEND_SHAPE", PARAMETER_BLEND_SHAPE),
        ("P2L_PART_VISIBLE", PART_VISIBLE), ("P2L_PART_SKETCH", PART_SKETCH), ("P2L_LOAD_VERIFY_CRC", LOAD_VERIFY_CRC),
    ] {
        assert!(header.contains(&format!("#define {name} {value}u\n")), "{name}");
    }
}

#[test]
fn rigs_created_from_one_model_share_it_and_keep_their_own_state() {
    let model = Arc::into_raw(read_model(|| Ok(triangle(None))).unwrap());
    unsafe {
        let a = p2l_rig_create(model, ptr::null_mut(), 0);
        let b = p2l_rig_create(model, ptr::null_mut(), 0);
        assert!(!a.is_null() && !b.is_null());
        assert!(Arc::ptr_eq(&(*a).model, &(*b).model));
        // The model outlives the host's reference while rigs hold it.
        p2l_model_free(model);
        p2l_set_parameter(a, 0, 1.0);
        p2l_evaluate(a);
        p2l_evaluate(b);
        assert_eq!(current(a), 1.0);
        assert_eq!(current(b), 0.0);
        assert_eq!(CStr::from_ptr(p2l_mesh_id(b, 0)).to_str().unwrap(), "M");
        p2l_rig_free(a);
        p2l_rig_free(b);
        assert!(p2l_rig_create(ptr::null(), ptr::null_mut(), 0).is_null());
    }
}

#[test]
fn a_model_that_does_not_read_is_null_with_the_readers_message() {
    let mut error = [0 as c_char; 256];
    let bytes = [1u8, 2, 3];
    let model = unsafe { p2l_model_load(bytes.as_ptr(), bytes.len(), 0, error.as_mut_ptr(), error.len()) };
    assert!(model.is_null());
    assert!(!unsafe { CStr::from_ptr(error.as_ptr()) }.to_bytes().is_empty());
}

#[test]
fn the_host_layer_sits_over_clips_and_stages_run_only_when_asked() {
    let mut r = triangle(None);
    r.clips = vec![hold("c", 0.9)];
    let handle = rig_on(r);
    unsafe {
        p2l_behaviors(handle, 0);
        p2l_play(handle, 0);
        p2l_update(handle, 2.0);
        assert!((current(handle) - 0.9).abs() < 1e-6);
        // The override replaces what the clip set, and taking it off gives the clip back.
        assert!(p2l_set_parameter_override(handle, 0, 0.25, 1.0));
        p2l_update(handle, 0.1);
        assert!((current(handle) - 0.25).abs() < 1e-6);
        assert!(p2l_set_parameter_override(handle, 0, 0.25, 0.5));
        p2l_update(handle, 0.1);
        assert!((current(handle) - 0.575).abs() < 1e-6);
        p2l_clear_parameter_overrides(handle);
        // Without the clip stage the clip neither applies nor advances.
        let time = p2l_clip_time(handle);
        p2l_update_stages(handle, 0.5, STAGE_ALL & !STAGE_CLIPS);
        assert_eq!(current(handle), 0.0);
        assert_eq!(p2l_clip_time(handle), time);
        p2l_update_stages(handle, 0.0, STAGE_ALL);
        assert!((current(handle) - 0.9).abs() < 1e-6);
        assert!(!p2l_set_parameter_override(handle, 5, 0.0, 1.0));
        p2l_rig_free(handle);
    }
}

#[test]
fn switching_clips_mid_fade_keeps_every_replaced_clip_fading() {
    let mut r = triangle(None);
    r.parameters[0] = parameter("A", 0.0, 100.0, 0.0);
    r.clips = vec![hold("a", 10.0), hold("b", 20.0), hold("c", 30.0)];
    let handle = rig_on(r);
    unsafe {
        p2l_behaviors(handle, 0);
        p2l_play(handle, 0);
        p2l_update(handle, 2.5);
        p2l_play(handle, 1);
        p2l_update(handle, 0.1);
        let before = current(handle);
        // The third switch comes while the first clip still fades out: it goes on fading instead of vanishing.
        p2l_play(handle, 2);
        p2l_update(handle, 0.1);
        assert!((current(handle) - before).abs() < 1.0, "{before} -> {}", current(handle));
        assert_eq!(p2l_clip_playing(handle), 2);
        p2l_rig_free(handle);
    }
}

#[test]
fn clips_report_their_settings_and_seek() {
    let mut r = triangle(None);
    r.clips = vec![hold("c", 0.5)];
    let handle = rig_on(r);
    unsafe {
        let (mut duration, mut fps, mut looping, mut fade_in, mut fade_out) = (0.0, 0.0, false, 0.0, 0.0);
        assert!(p2l_clip_info(handle, 0, &mut duration, &mut fps, &mut looping, &mut fade_in, &mut fade_out));
        assert_eq!((duration, fps, looping, fade_in, fade_out), (2.0, 30.0, true, 1.0, 1.0));
        assert_eq!(CStr::from_ptr(p2l_clip_group(handle, 0)).to_str().unwrap(), "Idle");
        assert!(!p2l_clip_info(handle, 1, ptr::null_mut(), ptr::null_mut(), ptr::null_mut(), ptr::null_mut(), ptr::null_mut()));
        assert_eq!(p2l_clip_playing(handle), -1);
        p2l_play(handle, 0);
        p2l_update(handle, 0.5);
        p2l_clip_seek(handle, 2.5);
        assert!((p2l_clip_time(handle) - 0.5).abs() < 1e-6);
        p2l_rig_free(handle);
    }
}

#[test]
fn isolated_parts_keep_their_group_in_the_render_commands_with_its_composite() {
    let handle = rig_on(isolated());
    unsafe {
        let mut commands = [0i32; 8];
        let n = p2l_render_commands(handle, commands.as_mut_ptr(), 8);
        assert_eq!(&commands[..n as usize], &[-2, 0, -1]);
        assert_eq!(p2l_render_order(handle, ptr::null_mut(), 0), 1);
        let (mut blend, mut alpha, mut invert) = (0, 0, false);
        assert_eq!(p2l_part_group(handle, 0, &mut blend, &mut alpha, &mut invert), 2);
        assert_eq!((blend, alpha, invert), (10, 1, true));
        // A part masking the group stands for its meshes.
        let mut masks = [9u32; 4];
        assert_eq!(p2l_part_masks(handle, 0, masks.as_mut_ptr(), 4), 1);
        assert_eq!(masks[0], 0);
        // The group's opacity follows its channel.
        let mut opacity = 0.0;
        p2l_set_parameter(handle, 0, 0.5);
        p2l_evaluate(handle);
        assert!(p2l_part_composite(handle, 0, &mut opacity, ptr::null_mut(), ptr::null_mut()));
        assert!((opacity - 0.5).abs() < 1e-6);
        assert_eq!(p2l_mesh_part(handle, 0), 0);
        assert_eq!(p2l_part_parent(handle, 0), -1);
        assert_eq!(p2l_part_flags(handle, 0), PART_VISIBLE);
        assert_eq!(CStr::from_ptr(p2l_part_name(handle, 0)).to_str().unwrap(), "Part");
        p2l_rig_free(handle);
    }
}

#[test]
fn a_part_opacity_the_host_sets_multiplies_the_meshes_under_it() {
    let handle = rig_on(isolated());
    unsafe {
        assert_eq!(p2l_part_opacity(handle, 0), 1.0);
        assert!(p2l_set_part_opacity(handle, 0, 0.25));
        p2l_evaluate(handle);
        assert_eq!(p2l_mesh_opacity(handle, 0), 0.25);
        assert_eq!(p2l_part_opacity(handle, 0), 0.25);
        assert!(!p2l_set_part_opacity(handle, 3, 0.5));
        p2l_rig_free(handle);
    }
}

#[test]
fn an_index_out_of_range_reads_as_none_rather_than_as_a_value() {
    let handle = rig_on(triangle(None));
    unsafe {
        assert!(p2l_mesh_opacity(handle, 4).is_nan());
        assert!(p2l_mesh_draw_order(handle, 4).is_nan());
        assert_eq!(p2l_mesh_blend(handle, 4, ptr::null_mut()), -1);
        assert_eq!(p2l_mesh_alpha_blend(handle, 4), -1);
        assert!(p2l_part_opacity(handle, 0).is_nan());
        assert_eq!(p2l_part_group(handle, 0, ptr::null_mut(), ptr::null_mut(), ptr::null_mut()), -1);
        assert!(p2l_parameter_name(handle, 1).is_null());
        // A null handle still reads as zeros.
        assert_eq!(p2l_mesh_opacity(ptr::null(), 0), 0.0);
        let mut len = 7usize;
        assert!(p2l_texture_png(handle, 0, &mut len, ptr::null_mut(), ptr::null_mut()).is_null());
        assert_eq!(len, 0);
        p2l_rig_free(handle);
    }
}

#[test]
fn parameters_report_their_names_flags_and_ids_as_handed_out() {
    let mut r = triangle(None);
    r.parameters = vec![Parameter { id: "Angle".into(), name: "Angle X".into(), min: -30.0, max: 30.0, default: 0.0, blend: true, repeat: true }];
    r.canvas.pixels_per_unit = Some(512.0);
    let handle = rig_on(r);
    unsafe {
        assert_eq!(CStr::from_ptr(p2l_parameter_name(handle, 0)).to_str().unwrap(), "Angle X");
        assert_eq!(p2l_parameter_flags(handle, 0), PARAMETER_REPEAT | PARAMETER_BLEND_SHAPE);
        assert_eq!(p2l_parameter_index(handle, p2l_parameter_id(handle, 0)), 0);
        let mut ppu = 0.0;
        p2l_canvas_origin(handle, ptr::null_mut(), ptr::null_mut(), &mut ppu);
        assert_eq!(ppu, 512.0);
        assert_eq!(p2l_gui_node_count(handle), 0);
        assert_eq!(p2l_parameter_snaps(handle, 0, ptr::null_mut(), 0), 0);
        p2l_rig_free(handle);
    }
}

#[test]
fn host_memory_round_trips_at_its_length() {
    unsafe {
        for len in [0usize, 1, 4096] {
            let p = p2l_alloc(len);
            assert!(!p.is_null());
            if len > 0 {
                *p.add(len - 1) = 7;
            }
            p2l_dealloc(p, len);
        }
    }
}

/// A clip holding parameter 0 at [value], looping over a second, without fades.
fn instant(id: &str, value: f32) -> Clip {
    Clip { duration: 1.0, fade_in: Some(0.0), fade_out: Some(0.0), ..hold(id, value) }
}

#[test]
fn layers_play_over_each_other_by_weight_and_priority() {
    let mut r = triangle(None);
    r.parameters[0] = parameter("A", 0.0, 10.0, 0.0);
    r.clips = vec![instant("low", 2.0), instant("high", 6.0)];
    let handle = rig_on(r);
    unsafe {
        p2l_behaviors(handle, 0);
        assert!(p2l_play_layer(handle, 0, 0, 1));
        assert!(p2l_play_layer(handle, 1, 1, 2));
        p2l_update(handle, 0.1);
        assert!((current(handle) - 6.0).abs() < 1e-5);
        assert!(p2l_set_layer_weight(handle, 1, 0.5));
        p2l_update(handle, 0.1);
        assert!((current(handle) - 4.0).abs() < 1e-5);
        // A lower priority cannot take the layer over; an equal or higher one can, and stopping frees it.
        assert!(!p2l_play_layer(handle, 1, 0, 1));
        assert!(p2l_play_layer(handle, 1, 0, 2));
        let (mut clip, mut priority) = (0, 0);
        assert!(p2l_layer_state(handle, 1, &mut clip, ptr::null_mut(), &mut priority, ptr::null_mut()));
        assert_eq!((clip, priority), (0, 2));
        assert!(p2l_play_layer(handle, 1, -1, 0));
        assert!(p2l_play_layer(handle, 1, 1, 0));
        assert!(!p2l_play_layer(handle, MAX_LAYERS, 0, 9));
        p2l_rig_free(handle);
    }
}

fn event_texts(handle: *mut Handle) -> Vec<String> {
    unsafe {
        (0..p2l_event_count(handle))
            .map(|i| CStr::from_ptr(p2l_event(handle, i, ptr::null_mut(), ptr::null_mut(), ptr::null_mut())).to_string_lossy().into_owned())
            .collect()
    }
}

#[test]
fn clip_events_fire_as_the_clip_passes_them() {
    let mut r = triangle(None);
    let mut clip = instant("c", 0.5);
    clip.extras.events = vec![(0.0, "start".into()), (0.5, "middle".into())];
    r.clips = vec![clip];
    let handle = rig_on(r);
    unsafe {
        p2l_play(handle, 0);
        p2l_update(handle, 0.6);
        assert_eq!(event_texts(handle), ["start", "middle"]);
        p2l_update(handle, 0.2);
        assert!(event_texts(handle).is_empty());
        // The loop comes round: the start fires again.
        p2l_update(handle, 0.3);
        assert_eq!(event_texts(handle), ["start"]);
        // A seek jumps over what lies between.
        p2l_clip_seek(handle, 0.9);
        p2l_update(handle, 0.05);
        assert!(event_texts(handle).is_empty());
        let (mut layer, mut clip, mut time) = (9, 9, 0.0);
        p2l_update(handle, 0.6);
        assert!(!p2l_event(handle, 0, &mut layer, &mut clip, &mut time).is_null());
        assert_eq!((layer, clip, time), (0, 0, 0.0));
        p2l_rig_free(handle);
    }
}

#[test]
fn clips_set_part_and_rig_opacity_and_drive_the_blink_and_lip_sync_effects() {
    let mut r = isolated();
    r.parameters = vec![parameter("A", 0.0, 1.0, 0.0), parameter("Eye", 0.0, 1.0, 1.0), parameter("Mouth", 0.0, 1.0, 0.0)];
    r.roles = vec![Role { role: "EyeBlink".into(), parameters: vec![1] }, Role { role: "LipSync".into(), parameters: vec![2] }];
    let constant = |value: f32| Curve { parameter: usize::MAX, start_time: 0.0, start_value: value, segments: vec![] };
    let mut clip = instant("c", 0.0);
    clip.extras.curves = vec![
        TargetCurve { target: CurveTarget::PartOpacity(0), fade_in: None, fade_out: None, curve: constant(0.5) },
        TargetCurve { target: CurveTarget::ModelOpacity, fade_in: None, fade_out: None, curve: constant(0.5) },
        TargetCurve { target: CurveTarget::EyeBlink, fade_in: None, fade_out: None, curve: constant(0.25) },
        TargetCurve { target: CurveTarget::LipSync, fade_in: None, fade_out: None, curve: constant(0.5) },
    ];
    r.clips = vec![clip];
    let handle = rig_on(r);
    unsafe {
        p2l_behaviors(handle, 0);
        p2l_play(handle, 0);
        p2l_update(handle, 0.1);
        // Part 0.5 times rig 0.5.
        assert!((p2l_mesh_opacity(handle, 0) - 0.25).abs() < 1e-6);
        assert!((p2l_opacity(handle) - 0.5).abs() < 1e-6);
        let values = std::slice::from_raw_parts(p2l_parameter_current(handle), 3);
        assert!((values[1] - 0.25).abs() < 1e-6, "{:?}", values);
        assert!((values[2] - 0.5).abs() < 1e-6, "{:?}", values);
        // Evaluating the host pose alone drops what the clip set.
        p2l_evaluate(handle);
        assert_eq!(p2l_mesh_opacity(handle, 0), 1.0);
        p2l_rig_free(handle);
    }
}

#[test]
fn a_curve_with_fades_of_its_own_weighs_by_them() {
    let mut r = triangle(None);
    r.parameters[0] = parameter("A", 0.0, 10.0, 0.0);
    let mut clip = instant("c", 10.0);
    clip.extras.fades = vec![(0, Some(1.0), None)];
    r.clips = vec![clip];
    let handle = rig_on(r);
    unsafe {
        p2l_behaviors(handle, 0);
        p2l_play(handle, 0);
        p2l_update(handle, 0.5);
        // The clip has no fade, but its curve fades in over a second: half way, the cosine ease gives a half.
        assert!((current(handle) - 5.0).abs() < 1e-4, "{}", current(handle));
        p2l_rig_free(handle);
    }
}

#[test]
fn several_expressions_play_at_once() {
    use crate::expression::{Blend, Expression};
    let mut r = triangle(None);
    r.parameters[0] = parameter("A", -10.0, 10.0, 0.0);
    let add = |id: &str, v: f32| Expression { id: id.into(), name: id.into(), fade_in: 0.0, fade_out: 0.0, parameters: vec![(0, Blend::Add, v)] };
    r.expressions = vec![add("a", 1.0), add("b", 2.0)];
    let handle = rig_on(r);
    unsafe {
        p2l_behaviors(handle, 0);
        assert!(p2l_expression_add(handle, 0));
        assert!(p2l_expression_add(handle, 1));
        assert!(!p2l_expression_add(handle, 1));
        p2l_update(handle, 0.1);
        assert!((current(handle) - 3.0).abs() < 1e-6);
        let mut playing = [9u32; 4];
        assert_eq!(p2l_expressions_playing(handle, playing.as_mut_ptr(), 4), 2);
        assert_eq!(&playing[..2], &[0, 1]);
        assert!(p2l_expression_remove(handle, 0));
        p2l_update(handle, 0.1);
        assert!((current(handle) - 2.0).abs() < 1e-6);
        // Playing one alone fades out the rest.
        p2l_expression(handle, 0);
        p2l_update(handle, 0.1);
        assert!((current(handle) - 1.0).abs() < 1e-6);
        p2l_rig_free(handle);
    }
}

#[test]
fn the_host_sets_the_rig_opacity_and_colors_in_place_of_the_evaluated_ones() {
    let handle = rig_on(isolated());
    unsafe {
        p2l_set_opacity(handle, 0.5);
        let red = [1.0f32, 0.0, 0.0];
        assert!(p2l_set_part_colors(handle, 0, red.as_ptr(), ptr::null()));
        p2l_evaluate(handle);
        assert_eq!(p2l_mesh_opacity(handle, 0), 0.5);
        let (mut multiply, mut screen) = ([0.0f32; 3], [9.0f32; 3]);
        p2l_mesh_colors(handle, 0, multiply.as_mut_ptr(), screen.as_mut_ptr());
        assert_eq!((multiply, screen), (red, [0.0; 3]));
        // The mesh setting wins over its part; clearing both gives the evaluated colors back.
        let blue = [0.0f32, 0.0, 1.0];
        assert!(p2l_set_mesh_colors(handle, 0, blue.as_ptr(), ptr::null()));
        p2l_evaluate(handle);
        p2l_mesh_colors(handle, 0, multiply.as_mut_ptr(), ptr::null_mut());
        assert_eq!(multiply, blue);
        p2l_set_mesh_colors(handle, 0, ptr::null(), ptr::null());
        p2l_set_part_colors(handle, 0, ptr::null(), ptr::null());
        p2l_evaluate(handle);
        p2l_mesh_colors(handle, 0, multiply.as_mut_ptr(), ptr::null_mut());
        assert_eq!(multiply, [1.0; 3]);
        assert!(!p2l_set_mesh_colors(handle, 5, red.as_ptr(), ptr::null()));
        p2l_rig_free(handle);
    }
}

/// A pendulum: parameter 0 moves the root sideways, parameter 1 reads the segment angle.
fn pendulum() -> Rig {
    let mut r = triangle(None);
    r.parameters = vec![parameter("Tilt", -10.0, 10.0, 0.0), parameter("Swing", -10.0, 10.0, 0.0)];
    r.physics = vec![PhysicsGroup {
        id: "g".into(), name: "g".into(),
        inputs: vec![PhysicsInput { parameter: 0, weight: 100.0, source: PhysicsSource::X, reflect: false }],
        outputs: vec![PhysicsOutput { parameter: 1, vertex: 1, scale: 10.0, weight: 100.0, source: PhysicsSource::Angle, reflect: false }],
        segments: vec![PhysicsSegment { length: 10.0, mobility: 0.9, delay: 0.9, acceleration: 1.0 }],
        normalization: Normalization { position_min: -10.0, position_default: 0.0, position_max: 10.0, angle_min: -10.0, angle_default: 0.0, angle_max: 10.0 },
    }];
    r
}

#[test]
fn wind_pushes_the_pendulums_and_stabilizing_hangs_them_at_rest() {
    let handle = rig_on(pendulum());
    unsafe {
        p2l_behaviors(handle, 0);
        let swing = |handle: *mut Handle| *p2l_parameter_current(handle).add(1);
        p2l_physics_stabilize(handle);
        assert_eq!(swing(handle), 0.0);
        // A sideways wind tilts the hanging segment; at rest it hangs along gravity plus the wind.
        p2l_physics_wind(handle, 1.0, 0.0);
        p2l_physics_stabilize(handle);
        let at_rest = swing(handle);
        assert!(at_rest.abs() > 1.0, "{}", at_rest);
        // Stepping on from rest keeps it there.
        for _ in 0..30 {
            p2l_update(handle, 1.0 / 30.0);
        }
        assert!((swing(handle) - at_rest).abs() < 0.05, "{} vs {}", swing(handle), at_rest);
        p2l_rig_free(handle);
    }
}

#[test]
fn behaviors_take_settings_and_lip_sync_follows_audio() {
    let mut r = triangle(None);
    r.parameters = vec![parameter("Mouth", 0.0, 1.0, 0.0)];
    r.roles = vec![Role { role: "LipSync".into(), parameters: vec![0] }];
    let handle = rig_on(r);
    unsafe {
        assert!(!p2l_blink_settings(handle, 3.0, 2.0, 0.1, 0.05, 0.15));
        assert!(p2l_blink_settings(handle, 1.0, 1.5, 0.05, 0.0, 0.05));
        assert!(!p2l_behavior_strength(handle, -1.0, 1.0, 1.0));
        assert!(p2l_behavior_strength(handle, 0.0, 1.0, 2.0));
        p2l_behaviors(handle, 8);
        // A square wave of amplitude 0.5 has a root mean square of 0.5.
        let samples = [0.5f32, -0.5, 0.5, -0.5];
        assert!((p2l_lip_sync_samples(handle, samples.as_ptr(), 4, 1.0) - 0.5).abs() < 1e-6);
        p2l_update(handle, 0.1);
        assert!((current(handle) - 0.5).abs() < 1e-6);
        assert_eq!(p2l_lip_sync_samples(handle, ptr::null(), 0, 1.0), 0.0);
        p2l_rig_free(handle);
    }
}

#[test]
fn meshes_report_what_changed_since_the_evaluation_before() {
    // The triangle's first vertex follows parameter A.
    let mut r = triangle(None);
    r.meshes[0].offsets = Some(Grid::new(
        vec![Axis { parameter: 0, keys: vec![0.0, 1.0] }],
        vec![(vec![0], vec![0.0; 6]), (vec![1], vec![5.0, 0.0, 0.0, 0.0, 0.0, 0.0])],
    ).unwrap());
    r.render.children = vec![RenderNode::Mesh(0)];
    let handle = rig_on(r);
    unsafe {
        let all = MESH_VISIBILITY_CHANGED | MESH_OPACITY_CHANGED | MESH_DRAW_ORDER_CHANGED | MESH_RENDER_ORDER_CHANGED | MESH_VERTICES_CHANGED | MESH_COLORS_CHANGED;
        assert_eq!(p2l_mesh_changes(handle, 0), MESH_VISIBLE | all);
        p2l_evaluate(handle);
        assert_eq!(p2l_mesh_changes(handle, 0), MESH_VISIBLE);
        p2l_set_parameter(handle, 0, 1.0);
        p2l_evaluate(handle);
        assert_eq!(p2l_mesh_changes(handle, 0), MESH_VISIBLE | MESH_VERTICES_CHANGED);
        p2l_set_opacity(handle, 0.0);
        p2l_evaluate(handle);
        assert_eq!(p2l_mesh_changes(handle, 0), MESH_VISIBILITY_CHANGED | MESH_OPACITY_CHANGED);
        assert_eq!(p2l_mesh_changes(handle, 9), 0);
        p2l_rig_free(handle);
    }
}

#[test]
fn what_goes_wrong_reaches_the_hosts_log() {
    use std::sync::Mutex;
    static MESSAGES: Mutex<Vec<(i32, String)>> = Mutex::new(Vec::new());
    unsafe extern "C" fn log(level: i32, message: *const c_char, _user: *mut std::ffi::c_void) {
        MESSAGES.lock().unwrap().push((level, CStr::from_ptr(message).to_string_lossy().into_owned()));
    }
    unsafe {
        p2l_set_log(Some(log), ptr::null_mut());
        let bytes = [9u8; 16];
        assert!(p2l_rig_load(bytes.as_ptr(), bytes.len(), ptr::null_mut(), 0).is_null());
        p2l_set_log(None, ptr::null_mut());
        assert!(p2l_rig_load(bytes.as_ptr(), bytes.len(), ptr::null_mut(), 0).is_null());
    }
    // Other tests may log meanwhile; this one's failed load is there, and nothing came after the log was cleared
    // from this load.
    let messages = MESSAGES.lock().unwrap();
    assert!(messages.iter().any(|(level, text)| *level == crate::host::ERROR && text.contains("rig")), "{:?}", *messages);
}

#[test]
fn a_host_allocator_comes_too_late_once_the_runtime_has_allocated() {
    unsafe extern "C" fn allocate(size: usize, align: usize, _user: *mut std::ffi::c_void) -> *mut std::ffi::c_void {
        std::alloc::System.alloc(std::alloc::Layout::from_size_align(size, align).unwrap()) as *mut std::ffi::c_void
    }
    unsafe extern "C" fn free(pointer: *mut std::ffi::c_void, size: usize, align: usize, _user: *mut std::ffi::c_void) {
        std::alloc::System.dealloc(pointer as *mut u8, std::alloc::Layout::from_size_align(size, align).unwrap())
    }
    use std::alloc::GlobalAlloc;
    // The test harness has allocated long before: memory must go back where it came from, so the host's comes too late.
    let _warm = vec![0u8; 16];
    assert!(!unsafe { p2l_set_allocator(Some(allocate), Some(free), ptr::null_mut()) });
    assert!(!unsafe { p2l_set_allocator(None, None, ptr::null_mut()) });
}
