# User quick reference

[Documentation](../../README.md) · [中文](../../zh/guide/USER_GUIDE.md) · [日本語](../../ja/guide/USER_GUIDE.md)

Open **Help → Tutorials…** (`F1`) first. Interactive lessons highlight the controls and show your configured shortcuts. This page is a short companion, in the same lesson order.

## Basic workflow

Import PSD (`Ctrl+Shift+O`) → inspect classifications and preview → edit → save (`Ctrl+S`) → export (`Ctrl+G`). Open an existing `.psd2live` project with `Ctrl+O`.

## Workspaces

The UI is organized into workspace tabs, each with its own canvases and panel layout. Click **+** at the end of the tab bar to add one from eight presets or a blank layout; the right side previews the layout and its purpose. The screenshot shows the Chinese UI.

![New workspace menu](../../imgs/workspace-presets.webp)

| Preset | Use |
| --- | --- |
| Edit | One edit canvas for deformers, keyforms and bones, with all property panels |
| Mesh | Hierarchy, edit canvas and Mesh panel for mesh topology |
| Rigging | Edit canvas beside a live preview, with the Parameters panel docked |
| Animation | Motion list, preview canvas and animation editor |
| Preview | Large preview with only the motion list |
| Physics | Preview canvas with Parameters and Physics panels, to tune while moving the model |
| Texture | Atlas pages, an edit canvas and the Texture panel, to check and adjust each layer's pixel size |
| History | Only the history tree and its operation list, to browse and check out history |
| Blank | Empty dock; add canvases and panels from the **Windows** menu |

**Reset layout** restores the current workspace's preset layout.

### Texture workspace

Every layer has three sizes: the rectangle it covers on the canvas (canvas units), the pixels of its own raster, and the pixels of its tile in the texture atlas. The Texture workspace shows them side by side and adjusts them per layer:

- **Atlas** (left): the page image fills the view and handles like the edit canvas: the wheel zooms, the middle button or Space + drag pans, `F` frames the selection, `Home` / `0` fits the page and `Ctrl+A` selects every tile on the page. Clicking a tile selects its layer and dragging over empty page draws a selection box (Shift adds, Alt takes away, as on the edit canvas), in sync with the canvas and the layer list. **Dragging a tile** moves its pixels with the pointer, live, and releasing it leaves it there; a drop where its meshes would meet another tile's (by the cells the meshes use, not the rectangles) does not move it, and the tile shows red, as does a spot past the page edge. A selected tile wears the edit canvas's transform box: dragging the **round handle** off its top edge, or from just outside a corner, where an arrowed arc lights up under the pointer, turns it about its **anchor** by any angle (Shift steps by 15°), its overlaps judged by its turned meshes too; the anchor (the ringed cross) starts at the tile's centre and can be dragged anywhere, snapping to the centre, the corners and the edges' middles. Moves, scales and turns are an **edit session**, as in paint mode: they change only what the atlas page shows and rebuild nothing, so they stay perfectly smooth; once something changed, the session bar - the same bar paint mode shows - drops in at the top centre of the page, counts the changed tiles and offers Undo/Redo (Ctrl+Z / Ctrl+Shift+Z), Discard and **Apply** (or Enter), which commits them all as one step - one rebuild, one history node; in a narrow view its buttons show icons alone, named on hover. While a session holds changes, Arrange, the automatic arrangement, the budget, Lock and Replace image wait for it. **Dragging a selected tile's corner** scales its density by the distance to the opposite corner, continuously, with no steps, keeping the opposite corner in place (with Alt, the anchor), and shows the tiles at their new size as you drag; the rest of the selection scales by the same ratio, into the session. a tile grown onto another tile's meshes shows red and the release changes nothing; moves do not snap either and follow the pointer. Dragging the Texture panel's density slider previews the new sizes on the page and in the size cards the same way. Double-click frames a tile. Right-click a tile to double, halve or reset its density, turn it by 90° or back upright, move it to page N when there are several (to the first spot from the top left where it meets no other tile's meshes, into the session; refused when the page has no room), lock or unlock its size (with some of the selection locked, the item says how many and locks the rest), arrange the selected tiles or replace its image; right-click the empty page to arrange all tiles, save the page as a PNG (byte for byte what exports write), select all or fit the page. While a session holds changes, the menu says so above the commands that wait for it. A page number's tooltip gives the page's size, tile count and fill. At the top left, a floating bar like the edit canvas's mode bar: the **Arrange** button arranges once and keeps the result, with a drop-down that holds only its options: the method as a choice - by mesh shape (the default: tiles nest by the area their meshes actually cover, the most compact) or by rectangles - and the scope as a choice: all tiles or the selected tiles only; the **Auto arrange** button, a toggle that stays lit while on - on, the atlas packs itself by rectangles (MaxRects) after every change; off (the default for new projects), it keeps its layout, new layers and any tile that no longer fits its spot fill free space quietly, and a tile placed that way keeps its spot - room that moving or shrinking another tile leaves is not filled by the tiles after it; and, with several pages, the page numbers. At the top right: the atlas budget (page size 1024–16384, maximum page count and padding). The bottom right holds the same display rail as the edit canvas, widening on hover: texture, mesh wireframes, outlines and the **Density heatmap** (off by default; atlas pixels per canvas unit: blue below 1, green around 1, orange above, legend ¼–4); the bottom-left status pill shows the tile under the pointer or being dragged, otherwise the fit and occupancy. A fit below 100% means the budget is too small and every unlocked tile is scaled down together; such notes go to the log panel only. Deleted layers, including the originals a split leaves behind, take no atlas space and are not shown.
- **Texture panel** (right): Canvas → Source → Atlas cards show the selected layer's canvas rectangle, source pixels and tile size, with the effective density marked on the heat scale. The **Texture density** slider and the ½× / 2× / reset buttons set the multiplier (atlas pixels per source pixel, 1/64–16, applied to the whole selection), **Lock size** keeps a layer's size when the atlas has to shrink, and with one layer selected **Turn** types its tile's angle (**Upright** resets it), into the session like a turn on the page. **Replace image…** replaces the pixels at any resolution, keeping the canvas rectangle, with Stretch or Contain and an optional mesh rebuild; **Texture upscale…** opens the project's upscale settings. The exact canvas rectangle is folded away under **Exact canvas rectangle**. With nothing selected the panel shows the atlas summary - each page's size, tiles and fill, the free space, the video memory the pages take (RGBA), the locked tiles and the fit with what it means - and the budget, which waits for an open session too. The export dialog only summarizes the atlas and links to the Texture workspace; the alpha threshold moved to the mesh panel's global settings.
- **Source pixels preview**: **Source pixels** on an edit canvas's bottom-right view rail makes that canvas sample each layer's own raster instead of its atlas tile, to compare against the packed sharpness. It is saved per canvas and draws that canvas in software while on; the preview canvas (Cubism or PSD2Live runtime) and exports always use atlas pixels.

Moving the canvas rectangle, or replacing an image with a mesh rebuild, is refused for layers with authored edits, bindings or materialized meshes; the reason shows above the atlas page. Every change is one history node and can be undone. The matching Agent operations are listed in the Chinese [MCP_AUTHORING.md](../../zh/agent/MCP_AUTHORING.md#纹理与纹理集).

## Tutorial paths

The catalog has 19 topics. The beginner path contains the 18 lessons below; the experienced path starts with a Cubism-to-PSD2Live terminology bridge and skips selected introductory lessons. Every chapter except Basic workflow first asks you to import a PSD or open a project when no model is open.

| Lesson | Topic | Remember |
| --- | --- | --- |
| 1 | Basic workflow | Import layered artwork, inspect parts and model presets, then choose export formats; texture atlas settings are in the export dialog. |
| 2 | Workspace | Edit changes the model; Preview shows it; History restores versions. Each canvas tab has its own camera and overlays. |
| 3 | Hierarchy and mode bar | Select, search and reparent objects. Drop transparent artwork on the tree and confirm placement. Drawing order and parent deformation are different. |
| 4 | Layer types and variants | Presets apply part algorithms; toggle variants show/hide; exclusive variants share a parameter with different association IDs. |
| 5 | Parameters and keyforms | Drag sliders or XY controls; right-click a key mark to snap. Move to the target key before editing its keyform. |
| 6 | Select mode | Select objects with the canvas tools or hierarchy; selection alone changes no geometry. |
| 7 | Create deformers | Select a target, use the tree context menu, adjust the placement preview and confirm. |
| 8 | Deform mode | Edit points or use brushes at the current parameter pose; check the L1 / L2 editing level. |
| 9 | Edit mode | Subdivide, connect, cut or remove mesh elements; inspect existing poses afterward. |
| 10 | Paint mode | Select a layer, paint pixels and use session-local undo. Apply or discard the session. |
| 11 | Inspector | Edit properties for the selected object: name, ownership, masks, drawing order, opacity and colors. |
| 12 | Tool details | Configure the current tool; canvas context menus also change with mode and tool. |
| 13 | Skeleton rigging and editing | Create, extrude, duplicate and mirror bones in Skeleton mode, batch-bind ArtMeshes, pose with FK/IK, paint and clean skin weights and save poses; configure parameter sampling limits in the panel. Export bakes this into Cubism parameters, deformers and keyforms. |
| 14 | Animation editor | Tune generated motions with each preset's knobs; edit parameter tracks and keyframes on the timeline with auto-keying, default Bezier easing, track key marks, and shared poses across canvases. |
| 15 | Physics canvas | Configure inputs, pendulums and outputs, then calibrate output scale against the observed range. |
| 16 | Cloth and hair simulation | Generate hair and clothing simulation in the model presets' Physics & Simulation group (first if there is none) and learn the hair modes and clothing options; then inspect bodies in the Simulation panel, paint and understand pin, stiffness, shape, mass and damping weights in Simulate mode, set glue, material presets and values, inputs and outputs and bake options, and bake into parameters, keyforms and a Cubism pendulum; only the bake exports. |
| 17 | Project and history | Save the project, search nodes in the compact history tree, highlight branch paths, or double-click to check out; includes a dedicated History workspace preset. |
| 18 | Texture upscaling | Configure the local backend, choose 2× / 4× and check edges, transparency and exports. |

## Important distinctions

- Saving preserves artwork, edits and history. Exporting delivers model files. `.psd2live.json` is only a report.
- When an export finishes (Live2D model, PSD or any Export as format), its dialog closes and **Export complete** shows where the files went, with the warnings or what the format could not keep, and offers **Open folder**. After **Close and don't show again**, exports report only in the log and status bar; **Settings › Prompts** lists every window with "Don't show again" (the start screen after an import too), to turn each back on or **Show all prompts again**.
- Parameter keyforms belong to modeling and interpolate model shapes. Animation keyframes record parameter values at points in time.
- Deform changes shapes; Edit changes mesh structure; Paint changes pixels in an isolated apply/discard session.
- Temporary solo visibility and static visibility are not parameter-driven variants. Use variants or opacity keyforms for animated switches.

For collar and hair occlusion workflows, see the [illustrated front/back layering tutorial (Chinese)](../../zh/guide/DEPTH_SPLIT.md).

For hair and clothing simulation, pin weights and baking, see the [illustrated simulation tutorial (Chinese)](../../zh/guide/SIMULATION_TUTORIAL.md).

Splits made by earlier versions (mesh islands, polygon, front/back layering) froze the original layer's state into their parts, so later face settings, stance, skeleton or part classification changes never reach those parts. **Tools → Upgrade Split Records** rewrites such records in the current format: the parts then take part in generation like any mesh, and the shape keys, paths, weights and Glues you made on the original and the parts are kept. The item is enabled only while old records exist; the whole upgrade is one undoable history step. A record that cannot be upgraded safely stays as it is, with the reason in the status bar. Opening a project never upgrades it.

## Default shortcuts

These are the default (Photoshop-style) bindings. **Settings** can switch to Blender- or Cubism-style presets or rebind individual actions; **Help → Keyboard Shortcuts…** shows the current bindings. Besides keys, the wheel with modifiers (up / down / left / right) and the middle and side mouse buttons (`MouseBack` / `MouseForward`) can be bound to any command: while recording, turn the wheel or press the button over the cell being recorded. The **canvas mouse gestures** (pan, zoom drag, brush adjust) are bound to the button that is pressed and dragged; the left and right buttons may be used there, with a modifier. A bare wheel, left button and right button stay with scrolling and zooming, the tools and the context menus. Windows reports a tilted wheel as Shift + wheel, so it records as `Shift+WheelUp` / `Shift+WheelDown`.

| Action | Keys |
| --- | --- |
| Open project / import PSD | `Ctrl+O` / `Ctrl+Shift+O` |
| Save / save as | `Ctrl+S` / `Ctrl+Shift+S` |
| Undo / redo | `Ctrl+Z` / `Ctrl+Shift+Z` or `Ctrl+Y` |
| Export model / export PSD | `Ctrl+G` / `Ctrl+Shift+E` |
| Reanalyze PSD | `Ctrl+R` |
| Texture upscale | `Ctrl+U` |
| Tutorials | `F1` |
| Zoom / pan | Wheel / middle (or `Shift+MouseMiddle`) drag, or Space + left drag |
| Zoom by dragging | `Ctrl+MouseMiddle`, drag up / down |
| Adjust the brush: size·hardness / opacity·angle | Drag with `Alt+MouseRight` / `Shift+Alt+MouseRight` |
| Turn the brush | `Alt+WheelUp` / `Alt+WheelDown`, 45° steps with Shift |
| Frame selection / reset camera | `F` / `Home` or `0` |
| Temporary selection / toggle quick preview | Hold `Z`, release to restore / grave accent key (below Esc) |
| Confirm / cancel | `Enter` / `Esc`; the current tool shows its own gestures |
| Motion timeline: play / key the pose / step a frame | `Space` / `K` / `Left` `Right` (while the timeline has focus) |

## Troubleshooting

| Symptom | Check first |
| --- | --- |
| A part moves wrongly or not at all | [Layer naming](../spec/PSD_LAYER_SPEC.md) and the type and side in the Layers table; masks and parents |
| Static poses look right but motion breaks | Play it in the animation or physics panel; one static pose does not show dynamic behavior |
| A panel is missing | Panel toggles in the Windows menu, or Reset layout at the top right |
| The model is not visible | Fit the canvas (`F` / `Home`), then check layer visibility |
| Export fails or reports downgrades | The Log panel, the `.psd2live.json` report and the export target version |

Further reading: [SDK setup](CUBISM_SDK_SETUP.md), [development and CLI](DEVELOPMENT.md), [project format](../spec/PROJECT_FORMAT.md), and the Chinese references for [canvas editing](../../zh/guide/CANVAS_EDITOR.md), [paths](../../zh/guide/DEFORM_PATHS.md), [upscaling](../../zh/guide/TEXTURE_UPSCALE.md) and [MCP](../../zh/agent/MCP_AUTHORING.md).

Maintained against the [tutorial catalog](../../../src/main/kotlin/io/github/psd2live/ui/tutorial/InteractiveTutorial.kt) and [shortcut registry](../../../src/main/kotlin/io/github/psd2live/ui/state/ShortcutRegistry.kt).
