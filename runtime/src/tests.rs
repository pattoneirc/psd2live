//! The evaluation rules, each on a small rig with the editor's result for it.

use crate::eval::Evaluator;
use crate::rig::*;

fn parameter(id: &str, min: f32, max: f32, default: f32) -> Parameter {
    Parameter { id: id.into(), name: id.into(), min, max, default, blend: false, repeat: false }
}

fn blend_parameter(id: &str, min: f32, max: f32) -> Parameter {
    Parameter { blend: true, ..parameter(id, min, max, 0.0) }
}

fn single<F>(form: F) -> Grid<F> {
    Grid::new(vec![], vec![(vec![], form)]).unwrap()
}

fn grid<F>(parameter: usize, keys: &[f32], forms: Vec<F>) -> Grid<F> {
    Grid::new(vec![Axis { parameter, keys: keys.to_vec() }], forms.into_iter().enumerate().map(|(i, f)| (vec![i as u16], f)).collect()).unwrap()
}

fn deformer(id: &str, parent: Option<usize>, kind: DeformerKind) -> Deformer {
    Deformer { id: id.into(), parent, part: None, visible: true, enabled: true, opacity: 1.0, multiply: WHITE, screen: BLACK, channels: vec![], kind }
}

fn rotation(id: &str, parent: Option<usize>, base_angle: f32, pivot: Grid<Pivot>, flip_x: bool, flip_y: bool) -> Deformer {
    deformer(id, parent, DeformerKind::Rotation { base_angle, pivot: Some(pivot), flip_x, flip_y, shapes: vec![] })
}

fn warp(id: &str, parent: Option<usize>, points: Vec<f32>) -> Deformer {
    deformer(id, parent, DeformerKind::Warp { columns: 1, rows: 1, bilinear: true, lattice: Some(single(points)), shapes: vec![] })
}

fn pivot(x: f32, y: f32, angle: f32, scale: f32) -> Pivot {
    Pivot { x, y, angle, scale }
}

/// A mesh with its first vertices at [positions]; the triangle is only for validity.
fn mesh(id: &str, parent: Option<usize>, positions: Vec<f32>) -> Mesh {
    let n = positions.len();
    Mesh {
        id: id.into(), name: id.into(), parent, blend: 0, alpha_blend: 0, masked_by: vec![], invert_mask: false, culling: false, visible: true, page: -1,
        geometry: Some(Geometry { uvs: vec![0.0; n], indices: vec![0, 1, 2], positions }),
        offsets: None, channels: vec![], draw_order: 500.0, opacity: 1.0, multiply: WHITE, screen: BLACK, shapes: vec![],
    }
}

fn rig(parameters: Vec<Parameter>, deformers: Vec<Deformer>, meshes: Vec<Mesh>) -> Rig {
    Rig {
        canvas: Canvas { width: 512.0, height: 512.0, origin_x: 0.0, origin_y: 0.0, pixels_per_unit: None },
        parameters, deformers, parts: vec![], meshes, glues: vec![],
        render: RenderGroup { part: None, draw_order: 500, channels: vec![], composite: None, children: vec![] },
        textures: vec![], physics_fps: 0.0, physics: vec![], clips: vec![], roles: vec![], gui: None, meta: vec![], extensions: Extensions::default(),
    }
}

fn close(got: &[f32], want: &[f32], tolerance: f32) {
    assert_eq!(got.len(), want.len());
    for (g, w) in got.iter().zip(want) {
        assert!((g - w).abs() <= tolerance, "got {:?}, want {:?}", got, want);
    }
}

/// The frame of the child of the last deformer: origin and the 10 px unit axes.
const AXES: [f32; 6] = [0.0, 0.0, 10.0, 0.0, 0.0, 10.0];

fn frame(r: &Rig, values: &[f32]) -> Vec<f32> {
    let mut e = Evaluator::new();
    let v = e.evaluate(r, values).vertices[0].clone();
    vec![v[0], v[1], v[2] - v[0], v[3] - v[1], v[4] - v[0], v[5] - v[1]]
}

fn axis_angle(f: &[f32]) -> f32 {
    f[3].atan2(f[2]).to_degrees()
}

#[test]
fn the_base_angle_turns_a_rotation_on_top_of_its_keyed_angle() {
    let r = rig(vec![], vec![rotation("R", None, 45.0, single(pivot(100.0, 100.0, 20.0, 1.0)), false, false)], vec![mesh("M", Some(0), AXES.to_vec())]);
    assert!((axis_angle(&frame(&r, &[])) - 65.0).abs() < 1e-3);
}

#[test]
fn a_rotation_under_a_warp_turns_with_the_lattice_and_keeps_its_size() {
    // A lattice turned by half a radian (28.648°) and scaled by 20.
    let (c, s) = (0.5f32.cos(), 0.5f32.sin());
    let points = vec![200.0, 200.0, 200.0 + 200.0 * c, 200.0 + 200.0 * s, 200.0 - 200.0 * s, 200.0 + 200.0 * c, 200.0 + 200.0 * (c - s), 200.0 + 200.0 * (s + c)];
    let r = rig(vec![], vec![warp("W", None, points), rotation("R", Some(0), 45.0, single(pivot(0.5, 0.5, 0.0, 1.0)), false, false)], vec![mesh("M", Some(1), AXES.to_vec())]);
    let f = frame(&r, &[]);
    close(&f[..2], &[239.82, 335.70], 0.01);
    assert!((axis_angle(&f) - 73.648).abs() < 1e-2);
    assert!(((f[2] * f[2] + f[3] * f[3]).sqrt() - 10.0).abs() < 1e-3);
}

#[test]
fn a_parent_flip_places_the_child_and_a_vertical_one_turns_it_half_way() {
    for (parent_x, parent_y, want) in [(true, false, 30.0f32), (false, true, -150.0)] {
        let r = rig(vec![], vec![
            rotation("P", None, 0.0, single(pivot(250.0, 250.0, 30.0, 1.5)), parent_x, parent_y),
            rotation("R", Some(0), 0.0, single(pivot(20.0, 10.0, 0.0, 1.0)), false, false),
        ], vec![mesh("M", Some(1), AXES.to_vec())]);
        assert!((axis_angle(&frame(&r, &[])) - want).abs() < 1e-3, "flips {} {}", parent_x, parent_y);
    }
}

#[test]
fn a_negative_parent_scale_keeps_the_child_upright() {
    let r = rig(vec![], vec![
        rotation("P", None, 0.0, single(pivot(100.0, 100.0, 0.0, -1.0)), false, false),
        rotation("R", Some(0), 0.0, single(pivot(0.0, 0.0, 0.0, 1.0)), false, false),
    ], vec![mesh("M", Some(1), AXES.to_vec())]);
    // The scale negates the axes and the half turn negates them back.
    close(&frame(&r, &[]), &[100.0, 100.0, 10.0, 0.0, 0.0, 10.0], 1e-3);
}

#[test]
fn missing_cells_hold_zero_offsets_and_zero_lattices() {
    let axes = vec![Axis { parameter: 0, keys: vec![0.0, 1.0] }, Axis { parameter: 1, keys: vec![0.0, 1.0] }];
    // Vertex i moves 1 px in x only in cell i; cell 3 is missing.
    let cells: Vec<(Vec<u16>, Vec<f32>)> = [(0, 0), (1, 0), (0, 1)].iter().enumerate()
        .map(|(i, (a, b))| (vec![*a, *b], (0..8).map(|k| if k == i * 2 { 1.0 } else { 0.0 }).collect())).collect();
    let mut m = mesh("M", None, vec![1000.0, 2000.0, 1000.0, 2000.0, 1000.0, 2000.0, 1000.0, 2000.0]);
    m.offsets = Some(Grid::new(axes, cells).unwrap());
    let r = rig(vec![parameter("A", 0.0, 1.0, 0.0), parameter("B", 0.0, 1.0, 0.0)], vec![], vec![m]);
    let mut e = Evaluator::new();
    close(&e.evaluate(&r, &[0.5, 0.5]).vertices[0], &[1000.25, 2000.0, 1000.25, 2000.0, 1000.25, 2000.0, 1000.0, 2000.0], 1e-4);
}

#[test]
fn keyforms_snap_to_keys_within_a_thousandth_and_blend_shapes_do_not() {
    let mut m = mesh("M", None, vec![0.0; 6]);
    m.offsets = Some(grid(0, &[0.0, 1.0], vec![vec![0.0; 6], vec![100.0; 6]]));
    m.shapes = vec![Binding {
        parameter: 1, keys: vec![0.0, 1.0], neutral: 0, limits: vec![],
        shapes: vec![None, Some(MeshShape { deltas: vec![0.0; 6], draw_order: 500.0, opacity: 0.0, multiply: WHITE, screen: BLACK })],
    }];
    let r = rig(vec![parameter("A", 0.0, 1.0, 0.0), blend_parameter("S", 0.0, 1.0)], vec![], vec![m]);
    let mut e = Evaluator::new();
    let pose = e.evaluate(&r, &[0.9995, 0.9995]);
    assert_eq!(pose.vertices[0][0], 100.0);
    assert!((pose.opacity[0] - 0.0005).abs() < 1e-5);
}

#[test]
fn a_repeating_parameter_keeps_both_ends_and_wraps_beyond() {
    let mut p = parameter("C", -30.0, 30.0, 0.0);
    p.repeat = true;
    assert_eq!(p.normalize(30.0), 30.0);
    assert_eq!(p.normalize(-30.0), -30.0);
    assert!((p.normalize(40.0) + 20.0).abs() < 1e-4);
}

#[test]
fn blend_shapes_move_toward_targets_from_the_default_pose_with_limits() {
    // A rotation keyed on A, with a shape on S whose target is relative to the pivot at A's default.
    let pivots = grid(2, &[0.0, 1.0], vec![pivot(20.0, 10.0, 10.0, 1.0), pivot(30.0, 10.0, 30.0, 2.0)]);
    let mut child = rotation("R", None, 0.0, pivots, false, false);
    if let DeformerKind::Rotation { shapes, .. } = &mut child.kind {
        shapes.push(Binding {
            parameter: 1, keys: vec![0.0, 1.0], neutral: 0,
            shapes: vec![None, Some(PivotShape { pivot: pivot(10.0, 0.0, 40.0, 1.5), flip_x: false, flip_y: false, opacity: 1.0, multiply: BLACK, screen: BLACK })],
            limits: vec![Limit { parameter: 2, points: vec![(-1.0, 0.2), (0.0, 1.0), (1.0, 0.5)] }],
        });
    }
    let params = vec![blend_parameter("S", -1.0, 1.0), blend_parameter("T", 0.0, 1.0), parameter("A", 0.0, 1.0, 0.25)];
    let r = rig(params, vec![child], vec![mesh("M", Some(0), AXES.to_vec())]);
    // A = 1: the pivot is at 30° and the shape adds (40 - 15) at the limit's weight of 0.5.
    let f = frame(&r, &[0.0, 1.0, 1.0]);
    assert!((axis_angle(&f) - 42.5).abs() < 1e-3);
    // Scale 2 + (1.5 - 1.25) / 2.
    assert!(((f[2] * f[2] + f[3] * f[3]).sqrt() / 10.0 - 2.125).abs() < 1e-4);
}

#[test]
fn opacity_clamps_at_every_stage() {
    let mut w = warp("W", None, vec![0.0, 0.0, 100.0, 0.0, 0.0, 100.0, 100.0, 100.0]);
    w.opacity = -0.5;
    let mut m = mesh("M", Some(0), vec![0.0, 0.0, 1.0, 0.0, 0.0, 1.0]);
    m.opacity = -0.5;
    let r = rig(vec![], vec![w], vec![m]);
    let mut e = Evaluator::new();
    assert_eq!(e.evaluate(&r, &[]).opacity[0], 0.0);
}

#[test]
fn behaviors_blink_breathe_and_follow_the_gaze() {
    use crate::behavior::*;
    let params = vec![parameter("Eye", 0.0, 1.0, 1.0), parameter("Breath", 0.0, 1.0, 0.0), parameter("AngleX", -30.0, 30.0, 0.0), parameter("Mouth", 0.0, 1.0, 0.0)];
    let mut r = rig(params, vec![], vec![]);
    r.roles = vec![
        Role { role: "EyeBlink".into(), parameters: vec![0] },
        Role { role: "Breath".into(), parameters: vec![1] },
        Role { role: "AngleX".into(), parameters: vec![2] },
        Role { role: "LipSync".into(), parameters: vec![3] },
    ];
    let mut b = Behaviors::default();
    b.enabled = BLINK | LOOK | LIP_SYNC;
    b.look_at(1.0, 0.0);
    b.lip_sync(0.5);
    let mut closed = false;
    let mut last = vec![];
    for _ in 0..600 {
        let mut values = r.defaults();
        b.update(&r, 1.0 / 60.0, &mut values);
        closed |= values[0] < 0.05;
        last = values;
    }
    assert!(closed, "the eyes close within ten seconds");
    assert!((last[2] - 30.0).abs() < 0.5, "gaze settles at the target: {}", last[2]);
    assert_eq!(last[3], 0.5);
    assert_eq!(last[1], 0.0, "breathing is off");
}

#[test]
fn groups_sort_by_their_parts_blended_draw_order_and_orders_tie_within_one() {
    use crate::eval::render_order;
    let part = |id: &str, order: i32, channels: Channels| Part {
        id: id.into(), name: id.into(), visible: true, sketch: false, group_mode: 0, draw_order: order, children: vec![], channels,
        composite: Composite { blend: 0, alpha_blend: 0, masked_by: vec![], masked_by_parts: vec![], invert_mask: false, opacity: 1.0, multiply: WHITE, screen: BLACK },
        shapes: vec![],
    };
    let keyed = vec![(Channel::DrawOrder, grid(0, &[0.0, 1.0], vec![ChannelValue::Scalar(500.0), ChannelValue::Scalar(100.0)]))];
    let mut r = rig(vec![parameter("A", 0.0, 1.0, 0.0)], vec![], vec![mesh("a", None, vec![0.0; 6]), mesh("b", None, vec![0.0; 6]), mesh("c", None, vec![0.0; 6])]);
    r.parts = vec![part("P", 500, keyed), part("Q", 300, vec![])];
    let group = |part: usize, meshes: &[usize]| RenderNode::Group(RenderGroup {
        part: Some(part), draw_order: 0, channels: vec![], composite: None, children: meshes.iter().map(|m| RenderNode::Mesh(*m)).collect(),
    });
    r.render = RenderGroup { part: None, draw_order: 500, channels: vec![], composite: None, children: vec![group(0, &[0, 1]), group(1, &[2])] };
    // Within P, a (500.6) and b (500.4) tie at 500 and keep tree order.
    r.meshes[0].draw_order = 500.6;
    r.meshes[1].draw_order = 500.4;
    let mut e = Evaluator::new();
    assert_eq!(render_order(&r, e.evaluate(&r, &[0.0])), vec![2, 0, 1], "Q (300) behind P (500)");
    assert_eq!(render_order(&r, e.evaluate(&r, &[1.0])), vec![0, 1, 2], "P keyed to 100 moves behind Q");
}
