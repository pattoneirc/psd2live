# P2LCharacter for Godot 4

A GDExtension (godot-rust, Godot 4.3+) that plays `.p2lrt` rigs exported from PSD2Live through the
PSD2Live runtime. MIT License.

```bash
cargo build --release          # target/release/p2l_godot.{dll,so,dylib}
```

Copy `demo/p2l.gdextension` into your project and point its library paths at the built file, then add
a `P2LCharacter` node and set **Rig Path** to an exported `.p2lrt`.

| Member | Meaning |
| --- | --- |
| `rig_path`, `load(path)` | The rig to play; `rig_loaded` is emitted after loading |
| `centered` | Draw the canvas centered on the node (default) or from its top-left |
| `autoplay`, `play(id)`, `stop()`, `get_clip_ids()` | Clips, cross-faded |
| `behaviors` | 1 blink, 2 breathing, 4 gaze, 8 lip sync (default blink and breathing) |
| `look_at_mouse`, `look_at_point(global)` | Gaze toward the mouse or a point |
| `lip_sync(level)` | Mouth opening 0..1 |
| `set_parameter(id, value)`, `get_parameter(id)`, `get_parameter_ids()` | The pose under clips and behaviors |
| `advance(delta)`, `reset_physics()` | Step manually (the node steps itself every frame); the reset restarts live cloth too |
| `get_expression_ids()`, `set_expression(id)` | Expressions faded in over the motion; an empty id fades out |
| `hit_test(global)` | The hit area under a point (`HitAreaHead`, `HitAreaBody`), or an empty string |
| `advanced_features`, `get_advanced_available()`, `set_advanced(features)` | Advanced mode where the rig offers it: 1 skinning along true arcs, 2 exact links, 4 live cloth and hair, 8 collision; 0 (default) plays the Cubism-equivalent rig |
| `get_pose_group_sizes()`, `show_pose(group, entry)` | Part poses: each group shows one of its parts, the others fading out |
| `get_bone_ids()`, `get_bone_transform(id)` | Skeleton bones' frames in the node's space, to attach things to |

The node draws by the rules at the top of `runtime/include/p2l_runtime.h`. Each mesh draws in its own
canvas item in the rig's render order, through a shader that applies its multiply and screen colors,
opacity and mask coverage to the premultiplied texture and composites it by its color and alpha blend
modes: normal under over, Cubism's add and multiply (any alpha mode) and out use the hardware blend; the
other color modes under over, and atop, conjoint and disjoint over, read the colors below.

Isolated groups draw their meshes into a layer: offscreen viewports of the target viewport's size whose
canvases map the node exactly as the target does. One canvas item, where the group sits in the order,
composites the layer by the group's blend modes, opacity, multiply and screen colors and masks; groups
nest. Godot's back buffer copy drops the alpha under the Forward+ and Mobile renderers, so inside a
layer each item that reads the colors below starts a viewport of its own that copies the layer so far
and reads that copy. Outside groups such an item reads the screen (copying the back buffer first), whose
alpha counts as opaque there: exact over an opaque scene, approximate over a transparent window.

Masks are coverage viewports: each distinct set of mask meshes adds its texture alpha into one color
channel (three sets per viewport), and the masked mesh or layer reads it at its own pixel, taking
1 - coverage when inverted. Overlapping mask meshes of one set add their alphas (the reference takes the
largest). Every layer, reading item inside a layer and coverage viewport costs a render target of the
screen's size and a pass per frame.

The demo (`demo/`) loads a rig given on the command line:

```bash
godot --path demo -- --rig model.p2lrt                     # window, gaze follows the mouse
godot --path demo -- --rig model.p2lrt --shot frame.png    # save a frame after a second and quit
godot --headless --path demo --script smoke_test.gd -- model.p2lrt
```

`GodotLayerSamplesTool` (in the editor's tests, `PSD2LIVE_TOOLS=1`) writes a rig of solid quads covering
every color and alpha blend pair, over the background and inside isolated groups, and the groups' own
settings (colors, opacity, blend modes, masks by meshes and by parts, inverted masks, nesting), with the
expected color at sample points; render it with `--resolution 800x600 ... --shot` and compare the shot's
pixels there.
