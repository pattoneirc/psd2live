/*
 * PSD2Live runtime: plays .p2lrt rigs compiled by PSD2Live. MIT License.
 *
 * One handle per loaded rig holds its parameters, clip player and physics. Set parameter values, call
 * p2l_update (clips, physics, deformation) or p2l_evaluate (deformation only), then read each mesh's
 * vertices in canvas pixels (y down) and draw them back to front in p2l_render_order. Pointers returned
 * stay valid until the handle is freed; pose data until the next evaluation. A null handle is accepted
 * everywhere and returns zeros.
 */
#ifndef P2L_RUNTIME_H
#define P2L_RUNTIME_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct P2lRig P2lRig;

/* Loads [len] bytes of a .p2lrt file (version 1 or 2); on failure returns NULL and writes a message into [error]. */
P2lRig *p2l_rig_load(const uint8_t *bytes, size_t len, char *error, size_t error_capacity);
/* p2l_rig_load with flags: P2L_LOAD_VERIFY_CRC checks every chunk that carries a CRC. */
#define P2L_LOAD_VERIFY_CRC 1u
P2lRig *p2l_rig_load_ex(const uint8_t *bytes, size_t len, uint32_t flags, char *error, size_t error_capacity);
/* The versions and chunks this runtime reads, e.g. "1,2 STRS/1 CANV/1 ...": major versions, then tag/newest version. */
const char *p2l_format_support(void);
void p2l_rig_free(P2lRig *rig);
const char *p2l_version(void);
void p2l_canvas(const P2lRig *rig, float *width, float *height);

/* Parameters */
uint32_t p2l_parameter_count(const P2lRig *rig);
const char *p2l_parameter_id(const P2lRig *rig, uint32_t index);
int32_t p2l_parameter_index(const P2lRig *rig, const char *id);
bool p2l_parameter_range(const P2lRig *rig, uint32_t index, float *min, float *max, float *default_value);
/* The values the next update reads and writes, one per parameter. */
float *p2l_parameter_values(P2lRig *rig);
void p2l_set_parameter(P2lRig *rig, uint32_t index, float value);
/* Parameters with a role such as "EyeBlink" or "LipSync"; returns how many there are. */
uint32_t p2l_role_parameters(const P2lRig *rig, const char *role, uint32_t *out, uint32_t capacity);

/* Evaluation */
void p2l_evaluate(P2lRig *rig);
/* Advances clips, behaviors and physics by [dt] seconds over the current values, then evaluates. */
void p2l_update(P2lRig *rig, float dt);
void p2l_physics_reset(P2lRig *rig);

/* Behaviors: P2L_BLINK | P2L_BREATH | P2L_LOOK | P2L_LIP_SYNC; blinking and breathing start on. */
#define P2L_BLINK 1u
#define P2L_BREATH 2u
#define P2L_LOOK 4u
#define P2L_LIP_SYNC 8u
void p2l_behaviors(P2lRig *rig, uint32_t flags);
/* Where to look, each axis -1..1 (x right, y up). */
void p2l_look_at(P2lRig *rig, float x, float y);
/* Mouth opening 0..1. */
void p2l_lip_sync(P2lRig *rig, float level);

/* Clips */
uint32_t p2l_clip_count(const P2lRig *rig);
const char *p2l_clip_id(const P2lRig *rig, uint32_t index);
/* Starts clip [index], fading out the current one; -1 stops. */
void p2l_play(P2lRig *rig, int32_t index);
bool p2l_clip_finished(const P2lRig *rig);

/* Meshes */
uint32_t p2l_mesh_count(const P2lRig *rig);
const char *p2l_mesh_id(const P2lRig *rig, uint32_t index);
uint32_t p2l_mesh_vertex_count(const P2lRig *rig, uint32_t index);
const float *p2l_mesh_uvs(const P2lRig *rig, uint32_t index);
const uint32_t *p2l_mesh_indices(const P2lRig *rig, uint32_t index, uint32_t *count);
int32_t p2l_mesh_texture(const P2lRig *rig, uint32_t index);
/* 0 normal, 1 add, 2 multiply as Cubism draws them, then the extended modes: 3 add, 4 add glow,
 * 5 darken, 6 multiply, 7 color burn, 8 linear burn, 9 lighten, 10 screen, 11 color dodge, 12 overlay,
 * 13 soft light, 14 hard light, 15 linear light, 16 hue, 17 color. */
int32_t p2l_mesh_blend(const P2lRig *rig, uint32_t index, bool *culling);
uint32_t p2l_mesh_masks(const P2lRig *rig, uint32_t index, uint32_t *out, uint32_t capacity, bool *inverted);
const float *p2l_mesh_vertices(const P2lRig *rig, uint32_t index);
float p2l_mesh_opacity(const P2lRig *rig, uint32_t index);
float p2l_mesh_draw_order(const P2lRig *rig, uint32_t index);
void p2l_mesh_colors(const P2lRig *rig, uint32_t index, float *multiply, float *screen);
uint32_t p2l_render_order(const P2lRig *rig, uint32_t *out, uint32_t capacity);

/* Memory for passing a rig in from a WebAssembly host. */
uint8_t *p2l_alloc(size_t len);
void p2l_dealloc(uint8_t *pointer, size_t len);

/* Textures: PNG bytes per page; NULL for a page that is not an embedded PNG. */
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
