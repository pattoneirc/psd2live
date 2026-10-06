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
| `advance(delta)`, `reset_physics()` | Step manually (the node steps itself every frame) |

Each mesh draws in its own canvas item in the rig's draw order. Cubism's add and multiply use a
CanvasItemMaterial; screen colors and the extended blend modes use shaders that read the screen below;
masks use a clip-only canvas group. Inverted masks draw unmasked: Godot's canvas groups cannot remove
coverage.

The demo (`demo/`) loads a rig given on the command line:

```bash
godot --path demo -- --rig model.p2lrt                     # window, gaze follows the mouse
godot --path demo -- --rig model.p2lrt --shot frame.png    # save a frame after a second and quit
godot --headless --path demo --script smoke_test.gd -- model.p2lrt
```
