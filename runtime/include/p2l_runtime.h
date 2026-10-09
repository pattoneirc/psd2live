/*
 * PSD2Live runtime: plays .p2lrt rigs compiled by PSD2Live. MIT License.
 *
 * One handle per loaded rig holds its parameters, clip player and physics. Set parameter values, call
 * p2l_update (clips, physics, deformation) or p2l_evaluate (deformation only), then read each mesh's
 * vertices in canvas pixels (y down) and draw them back to front in p2l_render_order. Pointers returned
 * stay valid until the handle is freed; pose data until the next evaluation. A null handle is accepted
 * everywhere and returns zeros; an index out of range on a working handle returns NULL for strings and
 * pointers, -1 for indices and modes, NaN for values and false for the functions that report success.
 *
 * Several rigs of one character share its file through a model: p2l_model_load once, p2l_rig_create per
 * character. p2l_rig_load is the two in one.
 *
 * Threads: a model is read-only and may be shared by rigs on any threads. A rig must not be used from two
 * threads at once, reads included (p2l_bone_transform, for one, updates the rig); different rigs may run on
 * different threads.
 *
 * A call that panics inside the runtime (a bug, or a rig the reader let through that cannot be evaluated)
 * never unwinds into the host: the call returns as for a null handle, the handle fails, p2l_rig_failure
 * tells why, and from then on it acts as a null handle until p2l_rig_free.
 *
 * Drawing. The reference is the software rasterizer in PSD2Live (format-compile, SoftwareRasterizer), which
 * follows the editor's compositing; the web player, the Godot node and the editor's preview draw the same way.
 * - Textures hold straight (not premultiplied) alpha. Sample them bilinearly; the rules below work on
 *   premultiplied color (rgb*a, a).
 * - A mesh's fragment: c = texel; c.rgb *= multiply; c.rgb += screen*c.a - c.rgb*screen; then c *= opacity*k,
 *   where k is the mask coverage, 1 without masks.
 * - Masks: coverage is the largest texture alpha of the mask meshes at the point, drawn at their own vertices
 *   without their colors or opacity; an inverted mask takes 1 - coverage. Real-time players may use a stencil
 *   that counts texels at least half opaque as covering (the web player, Godot and the editor do).
 * - Culling: with culling on, only Cubism's front faces draw: those with (b - a) x (c - a) < 0 in canvas
 *   coordinates (x right, y down), which turn counter-clockwise as the picture shows them.
 * - Compositing a source s over the destination d, both premultiplied, by a color blend mode and an alpha
 *   blend mode:
 *   P2L_BLEND_CUBISM_ADD (any alpha mode)       rgb = s.rgb + d.rgb, a = d.a
 *   P2L_BLEND_CUBISM_MULTIPLY (any alpha mode)  rgb = s.rgb*d.rgb + d.rgb*(1 - s.a), a = d.a
 *   P2L_BLEND_NORMAL with P2L_ALPHA_OVER        s + d*(1 - s.a)
 *   every other pair                            with Cs, Cb the unpremultiplied colors and B(Cb, Cs) the
 *     mode's W3C blend function (NORMAL: Cs; CUBISM_ADD, ADD and ADD_GLOW: min(1, Cb + Cs); the multiplies:
 *     Cb*Cs; the rest as named): overlap p = s.a*d.a, or min(s.a, d.a) for CONJOINT_OVER, or
 *     max(s.a + d.a - 1, 0) for DISJOINT_OVER; w = p/s.a; m = (1 - w)*Cs + w*B; the alpha mode's factors
 *     OVER Fa = 1, Fb = 1 - s.a; ATOP Fa = d.a, Fb = 1 - s.a; OUT Fa = 0, Fb = 1 - s.a (the source erases);
 *     CONJOINT_OVER Fa = 1, Fb = s.a >= d.a ? 0 : 1 - s.a/d.a; DISJOINT_OVER Fa = 1, Fb = min(1, (1 - s.a)/d.a);
 *     rgb = s.a*Fa*m + d.a*Fb*Cb, a = s.a*Fa + d.a*Fb, everything clamped to 0..1.
 * - Isolated groups (p2l_render_commands): draw the group's commands into a cleared layer; then the layer's
 *   pixel, unpremultiplied, takes the group's multiply and screen colors as a mesh's texel does, its alpha is
 *   scaled by the group's opacity and mask coverage, and it composites by the group's modes (p2l_part_composite,
 *   p2l_part_masks, p2l_part_group). Players that do not isolate draw p2l_render_order instead.
 */
#ifndef P2L_RUNTIME_H
#define P2L_RUNTIME_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* The ABI this header declares. The major version changes when a function changes or goes, the minor one when
 * functions are added. A host built against this header works with a library whose p2l_abi_version() passes
 * P2L_ABI_COMPATIBLE; a library without p2l_abi_version predates ABI 1.0. 1.1 adds models, update stages, the
 * host layer, parts and isolated groups, alpha blending and what the file says about parameters, clips and the
 * parameter panel. 1.2 adds clip layers with priorities, clip events, several expressions at once, the rig's
 * opacity, color overrides, physics wind and stabilization, behavior settings and lip sync from audio. 1.3 adds the
 * software renderer, p2l_render. */
#define P2L_ABI_VERSION_MAJOR 1
#define P2L_ABI_VERSION_MINOR 3
#define P2L_ABI_VERSION ((P2L_ABI_VERSION_MAJOR << 16) | P2L_ABI_VERSION_MINOR)
#define P2L_ABI_COMPATIBLE(v) (((v) >> 16) == P2L_ABI_VERSION_MAJOR && ((v) & 0xffffu) >= P2L_ABI_VERSION_MINOR)
/* The ABI the library implements, major << 16 | minor. */
uint32_t p2l_abi_version(void);

typedef struct P2lRig P2lRig;
typedef struct P2lModel P2lModel;

/* Loads [len] bytes of a .p2lrt file (version 1 or 2); on failure returns NULL and writes a message into [error]. */
P2lRig *p2l_rig_load(const uint8_t *bytes, size_t len, char *error, size_t error_capacity);
/* p2l_rig_load with flags: P2L_LOAD_VERIFY_CRC checks every chunk that carries a CRC. */
#define P2L_LOAD_VERIFY_CRC 1u
P2lRig *p2l_rig_load_ex(const uint8_t *bytes, size_t len, uint32_t flags, char *error, size_t error_capacity);
/* A model to create rigs from, read as p2l_rig_load_ex reads; NULL with a message in [error] on failure. */
P2lModel *p2l_model_load(const uint8_t *bytes, size_t len, uint32_t flags, char *error, size_t error_capacity);
/* Lets go of the model; the rigs created from it keep it until they are freed. */
void p2l_model_free(P2lModel *model);
/* A rig on [model] at its defaults, sharing the model with every rig created from it; NULL with a message in
 * [error] when its first evaluation fails. */
P2lRig *p2l_rig_create(const P2lModel *model, char *error, size_t error_capacity);
/* The versions and chunks this runtime reads, e.g. "1,2 STRS/1 CANV/1 ...": major versions, then tag/newest version. */
const char *p2l_format_support(void);
void p2l_rig_free(P2lRig *rig);
/* Why a call on the handle panicked, after which it acts as a null handle; NULL while it works. */
const char *p2l_rig_failure(const P2lRig *rig);
/* The library's build version, "major.minor.patch"; p2l_abi_version tells which functions it has. */
const char *p2l_version(void);
void p2l_canvas(const P2lRig *rig, float *width, float *height);
/* Where the canvas origin sits in canvas pixels, and the pixels in a model unit (0 when the file names none). */
void p2l_canvas_origin(const P2lRig *rig, float *x, float *y, float *pixels_per_unit);

/* Parameters */
uint32_t p2l_parameter_count(const P2lRig *rig);
const char *p2l_parameter_id(const P2lRig *rig, uint32_t index);
const char *p2l_parameter_name(const P2lRig *rig, uint32_t index);
int32_t p2l_parameter_index(const P2lRig *rig, const char *id);
bool p2l_parameter_range(const P2lRig *rig, uint32_t index, float *min, float *max, float *default_value);
/* P2L_PARAMETER_REPEAT: values beyond the range wrap; P2L_PARAMETER_BLEND_SHAPE: it weighs blend shapes. */
#define P2L_PARAMETER_REPEAT 1u
#define P2L_PARAMETER_BLEND_SHAPE 2u
uint32_t p2l_parameter_flags(const P2lRig *rig, uint32_t index);
/* The values a parameter panel snaps the parameter to, ascending; returns how many there are. */
uint32_t p2l_parameter_snaps(const P2lRig *rig, uint32_t index, float *out, uint32_t capacity);
/* The pose the host sets, p2l_parameter_count values: what every update and evaluation starts from. Clips,
 * expressions, behaviors and physics apply over a copy of it, so additive layers never accumulate here. */
float *p2l_parameter_values(P2lRig *rig);
/* The values the last evaluation used, after every layer; read-only. */
const float *p2l_parameter_current(const P2lRig *rig);
void p2l_set_parameter(P2lRig *rig, uint32_t index, float value);
/* The host's layer, for face tracking and the like: after clips and expressions, before behaviors and physics,
 * the parameter moves toward [value] by [weight] 0..1 (1 replaces). Weight 0 takes the layer off it. */
bool p2l_set_parameter_override(P2lRig *rig, uint32_t index, float value, float weight);
void p2l_clear_parameter_overrides(P2lRig *rig);
/* Parameters with a role such as "EyeBlink" or "LipSync"; returns how many there are. */
uint32_t p2l_role_parameters(const P2lRig *rig, const char *role, uint32_t *out, uint32_t capacity);

/* Parameter panel: pairs shown as one two-dimensional control (horizontal, vertical; returns the pair count),
 * and the group tree in pre-order, each node followed by its children. */
uint32_t p2l_gui_joysticks(const P2lRig *rig, uint32_t *out, uint32_t capacity);
uint32_t p2l_gui_node_count(const P2lRig *rig);
/* The parameter a leaf shows (-1 for a group), how many nodes follow as its children, whether it starts open. */
bool p2l_gui_node(const P2lRig *rig, uint32_t index, int32_t *parameter, uint32_t *children, bool *open);
const char *p2l_gui_node_id(const P2lRig *rig, uint32_t index);
const char *p2l_gui_node_name(const P2lRig *rig, uint32_t index);
/* 0 no label, 1 a preset named in [preset], 2 a custom color in [argb]. */
int32_t p2l_gui_node_label(const P2lRig *rig, uint32_t index, const char **preset, uint32_t *argb);

/* Evaluation */
/* Deforms the rig at the host's pose alone: what an earlier update's clips and physics added is not kept. */
void p2l_evaluate(P2lRig *rig);
/* Advances clips, behaviors and physics by [dt] seconds over the current values, then evaluates. */
void p2l_update(P2lRig *rig, float dt);
/* p2l_update running only the layers in [stages], in this order; the others neither advance nor apply. */
#define P2L_STAGE_CLIPS 1u
#define P2L_STAGE_EXPRESSIONS 2u
#define P2L_STAGE_BEHAVIORS 4u
#define P2L_STAGE_POSES 8u
#define P2L_STAGE_PHYSICS 16u
#define P2L_STAGE_SIM 32u
#define P2L_STAGE_ALL 63u
void p2l_update_stages(P2lRig *rig, float dt, uint32_t stages);
void p2l_physics_reset(P2lRig *rig);
/* Wind on every pendulum besides gravity, in physics units (x right, y down), as Cubism's physics wind. */
void p2l_physics_wind(P2lRig *rig, float x, float y);
/* Hangs every pendulum at rest under the last evaluation's pose, as Cubism's stabilization does, and evaluates. */
void p2l_physics_stabilize(P2lRig *rig);
/* The rig's opacity, 0..1, multiplying every mesh on top of what clips set (1 until set); p2l_opacity gives the
 * product at the last update. */
void p2l_set_opacity(P2lRig *rig, float opacity);
float p2l_opacity(const P2lRig *rig);
/* Colors in place of a mesh's evaluated multiply and screen colors (three floats each; a null one stands for
 * white multiply or black screen), from the next evaluation; both null gives the mesh its own back. The part
 * version covers every mesh under the part without colors of its own set. */
bool p2l_set_mesh_colors(P2lRig *rig, uint32_t index, const float *multiply, const float *screen);
bool p2l_set_part_colors(P2lRig *rig, uint32_t index, const float *multiply, const float *screen);

/* Behaviors: P2L_BLINK | P2L_BREATH | P2L_LOOK | P2L_LIP_SYNC; blinking and breathing start on. */
#define P2L_BLINK 1u
#define P2L_BREATH 2u
#define P2L_LOOK 4u
#define P2L_LIP_SYNC 8u
void p2l_behaviors(P2lRig *rig, uint32_t flags);
/* Reseeds the blink timing. Every rig after the first in a process gets a seed of its own, so rigs side by
 * side do not blink together; a host wanting the same blinks every run seeds them. */
void p2l_behavior_seed(P2lRig *rig, uint32_t seed);
/* Blink timing in seconds: the interval drawn evenly between the two, then how long the eyes take to close, stay
 * closed and open (2..6, 0.1, 0.05, 0.15 until set); false, changing nothing, for negative or reversed times. */
bool p2l_blink_settings(P2lRig *rig, float interval_min, float interval_max, float closing, float closed, float opening);
/* How strongly breathing sways and gaze turns, and how quickly gaze follows; 1 each until set. */
bool p2l_behavior_strength(P2lRig *rig, float sway, float look, float look_speed);
/* Lip sync from audio: the root mean square of [count] samples (-1..1) times [gain], clamped to 0..1, becomes
 * the mouth opening (as p2l_lip_sync); returns it. */
float p2l_lip_sync_samples(P2lRig *rig, const float *samples, size_t count, float gain);
/* Where to look, each axis -1..1 (x right, y up). */
void p2l_look_at(P2lRig *rig, float x, float y);
/* Mouth opening 0..1. */
void p2l_lip_sync(P2lRig *rig, float level);

/* Clips */
uint32_t p2l_clip_count(const P2lRig *rig);
const char *p2l_clip_id(const P2lRig *rig, uint32_t index);
const char *p2l_clip_name(const P2lRig *rig, uint32_t index);
/* The group a clip belongs to, such as "Idle"; empty when none. */
const char *p2l_clip_group(const P2lRig *rig, uint32_t index);
/* Length in seconds, frame rate, whether it loops, and its fade times (1 second when the file names none). */
bool p2l_clip_info(const P2lRig *rig, uint32_t index, float *duration, float *fps, bool *looping, float *fade_in,
                   float *fade_out);
/* Starts clip [index], fading out the current one (every replaced clip finishes its fade); -1 stops. */
void p2l_play(P2lRig *rig, int32_t index);
/* The clip playing, or -1. */
int32_t p2l_clip_playing(const P2lRig *rig);
/* The playing clip's time, wrapped for a loop and held at the end for a one-shot. */
float p2l_clip_time(const P2lRig *rig);
/* Moves the playing clip to [time] seconds; its fade-in goes on as it was. */
void p2l_clip_seek(P2lRig *rig, float time);
bool p2l_clip_finished(const P2lRig *rig);
/* Clip layers, as Cubism's motion managers: each plays one clip at a time over the layers below it, at its
 * weight (1 until set). p2l_play and the p2l_clip_* functions drive layer 0. A clip starts on a layer when its
 * priority is at least that of the clip playing there (0 once that finishes or stops); -1 stops the layer. */
#define P2L_MAX_LAYERS 16u
bool p2l_play_layer(P2lRig *rig, uint32_t layer, int32_t index, uint32_t priority);
bool p2l_set_layer_weight(P2lRig *rig, uint32_t layer, float weight);
/* What the layer plays: the clip (-1 for none), its time, its priority and whether it is a finished one-shot. */
bool p2l_layer_state(const P2lRig *rig, uint32_t layer, int32_t *clip, float *time, uint32_t *priority, bool *finished);
void p2l_layer_seek(P2lRig *rig, uint32_t layer, float time);
/* The clip events (Cubism motion user data) the last update passed, in order: the event's text, and the layer,
 * clip and clip time it came from. A loop fires them every pass; a seek does not fire what it jumps over. */
uint32_t p2l_event_count(const P2lRig *rig);
const char *p2l_event(const P2lRig *rig, uint32_t index, uint32_t *layer, int32_t *clip, float *time);

/* Meshes */
uint32_t p2l_mesh_count(const P2lRig *rig);
const char *p2l_mesh_id(const P2lRig *rig, uint32_t index);
const char *p2l_mesh_name(const P2lRig *rig, uint32_t index);
uint32_t p2l_mesh_vertex_count(const P2lRig *rig, uint32_t index);
const float *p2l_mesh_uvs(const P2lRig *rig, uint32_t index);
const uint32_t *p2l_mesh_indices(const P2lRig *rig, uint32_t index, uint32_t *count);
int32_t p2l_mesh_texture(const P2lRig *rig, uint32_t index);
/* Color blend modes (see Drawing above): Cubism's three, then the extended modes. */
#define P2L_BLEND_NORMAL 0
#define P2L_BLEND_CUBISM_ADD 1
#define P2L_BLEND_CUBISM_MULTIPLY 2
#define P2L_BLEND_ADD 3
#define P2L_BLEND_ADD_GLOW 4
#define P2L_BLEND_DARKEN 5
#define P2L_BLEND_MULTIPLY 6
#define P2L_BLEND_COLOR_BURN 7
#define P2L_BLEND_LINEAR_BURN 8
#define P2L_BLEND_LIGHTEN 9
#define P2L_BLEND_SCREEN 10
#define P2L_BLEND_COLOR_DODGE 11
#define P2L_BLEND_OVERLAY 12
#define P2L_BLEND_SOFT_LIGHT 13
#define P2L_BLEND_HARD_LIGHT 14
#define P2L_BLEND_LINEAR_LIGHT 15
#define P2L_BLEND_HUE 16
#define P2L_BLEND_COLOR 17
/* The color blend mode, and whether the mesh culls back faces. */
int32_t p2l_mesh_blend(const P2lRig *rig, uint32_t index, bool *culling);
/* Alpha blend modes, Cubism 5.3's. */
#define P2L_ALPHA_OVER 0
#define P2L_ALPHA_ATOP 1
#define P2L_ALPHA_OUT 2
#define P2L_ALPHA_CONJOINT_OVER 3
#define P2L_ALPHA_DISJOINT_OVER 4
int32_t p2l_mesh_alpha_blend(const P2lRig *rig, uint32_t index);
/* The part holding the mesh, or -1. */
int32_t p2l_mesh_part(const P2lRig *rig, uint32_t index);
uint32_t p2l_mesh_masks(const P2lRig *rig, uint32_t index, uint32_t *out, uint32_t capacity, bool *inverted);
const float *p2l_mesh_vertices(const P2lRig *rig, uint32_t index);
float p2l_mesh_opacity(const P2lRig *rig, uint32_t index);
float p2l_mesh_draw_order(const P2lRig *rig, uint32_t index);
void p2l_mesh_colors(const P2lRig *rig, uint32_t index, float *multiply, float *screen);
uint32_t p2l_render_order(const P2lRig *rig, uint32_t *out, uint32_t capacity);
/* p2l_render_order with isolated groups kept: a mesh index, P2L_RENDER_BEGIN_GROUP(part) opening the group of
 * an isolated part, P2L_RENDER_END_GROUP closing it. */
#define P2L_RENDER_END_GROUP (-1)
#define P2L_RENDER_BEGIN_GROUP(part) (-2 - (int32_t)(part))
#define P2L_RENDER_IS_GROUP(command) ((command) <= -2)
#define P2L_RENDER_GROUP_PART(command) ((uint32_t)(-2 - (command)))
uint32_t p2l_render_commands(const P2lRig *rig, int32_t *out, uint32_t capacity);

/* Parts */
uint32_t p2l_part_count(const P2lRig *rig);
const char *p2l_part_id(const P2lRig *rig, uint32_t index);
const char *p2l_part_name(const P2lRig *rig, uint32_t index);
/* The part holding this one, or -1. */
int32_t p2l_part_parent(const P2lRig *rig, uint32_t index);
#define P2L_PART_VISIBLE 1u
#define P2L_PART_SKETCH 2u
uint32_t p2l_part_flags(const P2lRig *rig, uint32_t index);
/* How the part groups its meshes, P2L_GROUP_*, and the blend modes and mask inversion of an isolated group. */
#define P2L_GROUP_PASS_THROUGH 0
#define P2L_GROUP_SORTED 1
#define P2L_GROUP_ISOLATED 2
int32_t p2l_part_group(const P2lRig *rig, uint32_t index, int32_t *blend, int32_t *alpha_blend, bool *invert_mask);
/* The meshes masking the part's isolated group, the masking parts' meshes included; returns how many. */
uint32_t p2l_part_masks(const P2lRig *rig, uint32_t index, uint32_t *out, uint32_t capacity);
/* The opacity and the multiply and screen colors (three floats each) of the group at the last evaluation. */
bool p2l_part_composite(const P2lRig *rig, uint32_t index, float *opacity, float *multiply, float *screen);
/* An opacity the host gives the part, 0..1 (1 until set), multiplying every mesh under it on top of part poses. */
float p2l_part_opacity(const P2lRig *rig, uint32_t index);
bool p2l_set_part_opacity(P2lRig *rig, uint32_t index, float opacity);

/* Advanced mode: what the file's extension chunks add over the Cubism-equivalent evaluation, all off by default.
 * P2L_SKIN skins meshes along true arcs between their baked keys; P2L_EXACT_LINKS moves pivots keyed along a
 * circle on it. p2l_set_advanced returns the features now on (0 is the Cubism-equivalent evaluation). */
#define P2L_SKIN 1u
#define P2L_EXACT_LINKS 2u
#define P2L_SIM 4u
#define P2L_COLLISION 8u
uint32_t p2l_advanced_available(const P2lRig *rig);
uint32_t p2l_set_advanced(P2lRig *rig, uint32_t features);
/* P2L_SIM: cloth and hair run live, stepped by p2l_update at their own rate; p2l_evaluate draws the last step.
 * The baked swing parameters stay at rest and their pendulums are skipped. P2L_COLLISION adds the colliders. */
void p2l_sim_reset(P2lRig *rig);
/* Wind every simulation feels besides its own, canvas pixels per second squared (x right, y down). */
void p2l_sim_wind(P2lRig *rig, float x, float y);
/* The colliders as the last update placed them: ax, ay, bx, by, radius a, radius b in canvas pixels, six floats
 * each, written into out up to capacity colliders; returns how many there are. */
uint32_t p2l_sim_colliders(const P2lRig *rig, float *out, uint32_t capacity);
/* Bones a host can attach things to, and each one's canvas frame at the last evaluation as an affine map
 * [a, b, c, d, tx, ty]: x' = a x + b y + tx, y' = c x + d y + ty. */
uint32_t p2l_bone_count(const P2lRig *rig);
const char *p2l_bone_id(const P2lRig *rig, uint32_t index);
bool p2l_bone_transform(P2lRig *rig, uint32_t index, float *out);

/* Expressions: a few parameters set over the motion, faded as Cubism fades exp3 expressions. p2l_expression
 * plays one alone, fading the others out; -1 fades them all out. */
uint32_t p2l_expression_count(const P2lRig *rig);
const char *p2l_expression_id(const P2lRig *rig, uint32_t index);
const char *p2l_expression_name(const P2lRig *rig, uint32_t index);
void p2l_expression(P2lRig *rig, int32_t index);
/* Several expressions at once: add fades one in over those playing, remove fades one out; the playing ones
 * (not fading out) come oldest first. */
bool p2l_expression_add(P2lRig *rig, uint32_t index);
bool p2l_expression_remove(P2lRig *rig, uint32_t index);
uint32_t p2l_expressions_playing(const P2lRig *rig, uint32_t *out, uint32_t capacity);
/* Hit areas (e.g. "HitAreaHead", "HitAreaBody"): the first one whose visible meshes cover a canvas point. */
uint32_t p2l_hit_area_count(const P2lRig *rig);
const char *p2l_hit_area_id(const P2lRig *rig, uint32_t index);
const char *p2l_hit_area_name(const P2lRig *rig, uint32_t index);
int32_t p2l_hit_test(const P2lRig *rig, float x, float y);
/* The meshes a hit area covers, written into out up to capacity; returns how many there are. */
uint32_t p2l_hit_area_meshes(const P2lRig *rig, uint32_t index, uint32_t *out, uint32_t capacity);
/* Part poses (Cubism pose3): each group shows one of its parts, fading the others out as p2l_update steps. */
uint32_t p2l_pose_group_count(const P2lRig *rig);
uint32_t p2l_pose_group_size(const P2lRig *rig, uint32_t group);
bool p2l_pose_show(P2lRig *rig, uint32_t group, uint32_t entry);
int32_t p2l_pose_shown(const P2lRig *rig, uint32_t group);
/* A mesh's user data, empty when it has none. */
const char *p2l_mesh_user_data(const P2lRig *rig, uint32_t index);
/* The file's generator information as key-value pairs. */
uint32_t p2l_meta_count(const P2lRig *rig);
const char *p2l_meta_key(const P2lRig *rig, uint32_t index);
const char *p2l_meta_value(const P2lRig *rig, uint32_t index);

/* Memory for passing a rig in from a WebAssembly host: [len] zeroed bytes, freed with the same [len]. */
uint8_t *p2l_alloc(size_t len);
void p2l_dealloc(uint8_t *pointer, size_t len);

/* Software rendering: draws the last evaluation into [rgba], width x height RGBA8 pixels, rows top first, by the
 * drawing rules above (masks, isolated groups, every blend mode, culling), as PSD2Live's reference rasterizer does.
 * [transform] maps canvas pixels to image pixels as [a, b, c, d, tx, ty]; NULL fits the canvas into the image from
 * its top left corner. P2L_RENDER_STRAIGHT gives straight alpha (premultiplied otherwise); P2L_RENDER_KEEP draws
 * over what [rgba] holds instead of transparency. Embedded PNG pages decode themselves (non-interlaced); other
 * pages draw once p2l_render_texture gives them straight RGBA8 pixels (NULL takes them back). */
#define P2L_RENDER_STRAIGHT 1u
#define P2L_RENDER_KEEP 2u
bool p2l_render(P2lRig *rig, uint8_t *rgba, uint32_t width, uint32_t height, const float *transform, uint32_t flags);
bool p2l_render_texture(P2lRig *rig, uint32_t page, const uint8_t *rgba, uint32_t width, uint32_t height);

/* Textures: PNG bytes per page; NULL, with [len] 0, for a page that is not an embedded PNG. */
uint32_t p2l_texture_count(const P2lRig *rig);
const uint8_t *p2l_texture_png(const P2lRig *rig, uint32_t index, size_t *len, uint32_t *width, uint32_t *height);
/* A page's kind (0 PNG, 1 KTX2, 2 a file next to the rig, -1 none), size and embedded bytes; returns the file
 * name, empty for an embedded page. */
const char *p2l_texture_info(const P2lRig *rig, uint32_t index, int32_t *kind, uint32_t *width, uint32_t *height,
                             const uint8_t **data, size_t *len);

#ifdef __cplusplus
}
#endif

#endif
