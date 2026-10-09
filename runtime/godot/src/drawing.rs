//! The canvas items, materials and offscreen viewports that draw a rig into the viewport holding the node.
//!
//! Meshes outside isolated groups draw in canvas items under the node, in `render_commands` order. Each
//! isolated group draws its children into a layer: transparent viewports of the target's size whose canvases
//! map the node exactly as the target does. One canvas item under the group's parent composites the layer
//! where the group sits in the order.
//!
//! An item that reads the colors below (an extended blend mode, or an alpha mode other than over and out)
//! needs their alpha, which Godot's back buffer copy drops under the Forward+ and Mobile renderers. In the
//! target the backdrop is taken as it reads (opaque); inside a layer each reading item starts a segment, a
//! viewport of its own that first copies the previous segment and gives the item that copy to read, and the
//! group composites its last segment.
//!
//! Mask coverage is drawn the same way: each distinct set of mask meshes adds its texture alpha into one color
//! channel of a coverage viewport, which the masked mesh or layer reads at its own pixel (1 - coverage for an
//! inverted mask). The offscreen viewports draw before the target: coverage first, then the layers, inner
//! groups before outer ones and each layer's segments in order.

use std::collections::HashMap;

use godot::classes::rendering_server::{CanvasItemTextureFilter, ViewportUpdateMode};
use godot::classes::{ImageTexture, RenderingServer};
use godot::prelude::*;
use p2l_runtime::eval::{render_group_part, Pose};
use p2l_runtime::rig::{Child, Composite, RenderGroup, RenderNode, Rig, BLACK};

use crate::shaders::{self, Mask};

/// A viewport of the target's size and pixels, with a canvas whose root item maps the node onto it.
struct Page {
    viewport: Rid,
    canvas: Rid,
    root: Rid,
    texture: Rid,
}

/// A canvas item drawing a mesh, or compositing a layer, through its own material.
struct Item {
    rid: Rid,
    material: Rid,
    /// Whether it reads the colors below, from the segment before its own inside a layer.
    reads: bool,
    /// The layer it draws into, or none for the node.
    layer: Option<usize>,
    /// The canvas item it currently sits under.
    parent: Rid,
}

/// An isolated group's layer: its segments, the items copying each segment into the next, and its part.
struct Layer {
    segments: Vec<Page>,
    bases: Vec<Rid>,
    part: usize,
}

pub struct Drawing {
    /// The viewport drawn into and its size in pixels.
    pub target: Rid,
    size: Vector2i,
    shaders: Vec<Rid>,
    /// Materials shared by many items: the mask channels and the segment copy.
    shared_materials: Vec<Rid>,
    /// One item per mesh, then one per layer.
    items: Vec<Item>,
    /// Whether each mesh can take a screen color, which then is set every frame.
    screened: Vec<bool>,
    layers: Vec<Layer>,
    /// Per part, the layer of its isolated group.
    part_layer: Vec<Option<usize>>,
    /// Coverage viewports, three mask sets each (red, green, blue).
    mask_pages: Vec<Page>,
    /// The items drawing mask meshes into the coverage viewports, and the mesh each draws.
    mask_items: Vec<(Rid, usize)>,
}

/// Every mesh under [part], its sub-parts' included.
fn part_meshes(rig: &Rig, part: usize, out: &mut Vec<usize>) {
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
}

const CHANNELS: [Vector3; 3] = [Vector3::new(1.0, 0.0, 0.0), Vector3::new(0.0, 1.0, 0.0), Vector3::new(0.0, 0.0, 1.0)];

impl Page {
    fn new(rs: &mut Gd<RenderingServer>, size: Vector2i, hdr: bool) -> Page {
        let viewport = rs.viewport_create();
        rs.viewport_set_size(viewport, size.x.max(1), size.y.max(1));
        rs.viewport_set_transparent_background(viewport, true);
        rs.viewport_set_disable_3d(viewport, true);
        rs.viewport_set_use_hdr_2d(viewport, hdr);
        rs.viewport_set_update_mode(viewport, ViewportUpdateMode::ALWAYS);
        let canvas = rs.canvas_create();
        rs.viewport_attach_canvas(viewport, canvas);
        let root = rs.canvas_item_create();
        rs.canvas_item_set_parent(root, canvas);
        let texture = rs.viewport_get_texture(viewport);
        Page { viewport, canvas, root, texture }
    }

    fn free(&self, rs: &mut Gd<RenderingServer>) {
        rs.free_rid(self.root);
        rs.free_rid(self.viewport);
        rs.free_rid(self.canvas);
    }
}

/// Where the next item of an open level goes: its layer's segment and the draw index in it.
struct Open {
    layer: Option<usize>,
    segment: usize,
    next: i32,
    /// Whether closing the level composites its layer.
    composite: bool,
}

impl Drawing {
    /// The items drawing [rig] under the canvas item [parent], in viewport [target] of [size] pixels.
    pub fn new(rig: &Rig, parent: Rid, target: Rid, size: Vector2i, hdr: bool) -> Drawing {
        let mut rs = RenderingServer::singleton();

        // Each mesh's isolated group, if any, and each group's part, enclosing group and composite, parents first.
        let mut mesh_layer: Vec<Option<usize>> = vec![None; rig.meshes.len()];
        let mut groups: Vec<(usize, Option<usize>, &Composite)> = Vec::new();
        fn walk<'a>(g: &'a RenderGroup, layer: Option<usize>, mesh_layer: &mut [Option<usize>], groups: &mut Vec<(usize, Option<usize>, &'a Composite)>) {
            for child in &g.children {
                match child {
                    RenderNode::Mesh(m) => {
                        if let Some(slot) = mesh_layer.get_mut(*m) {
                            *slot = layer;
                        }
                    }
                    // As render_commands: a group with a part and a composite is isolated.
                    RenderNode::Group(child) => match (child.part, &child.composite) {
                        (Some(p), Some(c)) => {
                            groups.push((p, layer, c));
                            let l = groups.len() - 1;
                            walk(child, Some(l), mesh_layer, groups);
                        }
                        _ => walk(child, layer, mesh_layer, groups),
                    },
                }
            }
        }
        walk(&rig.render, None, &mut mesh_layer, &mut groups);

        // The distinct sets of mask meshes.
        let mut sets: Vec<Vec<usize>> = Vec::new();
        let mut set_of = |mut masks: Vec<usize>| -> Option<usize> {
            masks.retain(|&m| m < rig.meshes.len());
            masks.sort_unstable();
            masks.dedup();
            if masks.is_empty() {
                return None;
            }
            Some(sets.iter().position(|s| *s == masks).unwrap_or_else(|| {
                sets.push(masks);
                sets.len() - 1
            }))
        };
        let mesh_sets: Vec<Option<usize>> = rig.meshes.iter().map(|m| set_of(m.masked_by.clone())).collect();
        let group_sets: Vec<Option<usize>> = groups
            .iter()
            .map(|(_, _, c)| {
                let mut masks = c.masked_by.clone();
                for &p in &c.masked_by_parts {
                    part_meshes(rig, p, &mut masks);
                }
                set_of(masks)
            })
            .collect();

        let mut owned_shaders = Vec::new();
        let mut shared_materials = Vec::new();
        let mut shared = |rs: &mut Gd<RenderingServer>, code: &str| -> Rid {
            let shader = rs.shader_create();
            rs.shader_set_code(shader, code);
            owned_shaders.push(shader);
            shader
        };
        let mask_pages: Vec<Page> = (0..sets.len().div_ceil(3)).map(|_| Page::new(&mut rs, size, hdr)).collect();
        let mut mask_materials = Vec::new();
        if !sets.is_empty() {
            let shader = shared(&mut rs, shaders::MASK);
            for c in CHANNELS {
                let material = rs.material_create();
                rs.material_set_shader(material, shader);
                rs.material_set_param(material, "channel", &c.to_variant());
                mask_materials.push(material);
            }
        }
        let copy = rs.material_create();
        let copy_shader = shared(&mut rs, shaders::COPY);
        rs.material_set_shader(copy, copy_shader);
        shared_materials.extend(&mask_materials);
        shared_materials.push(copy);

        let mut shader_cache: HashMap<(u8, u8, Mask, bool), Rid> = HashMap::new();
        let everywhere = Rect2::new(Vector2::new(-1e6, -1e6), Vector2::new(2e6, 2e6));
        let mut item = |rs: &mut Gd<RenderingServer>, blend: u8, alpha: u8, invert: bool, set: Option<usize>, layer: Option<usize>| -> Item {
            let mask = match set {
                None => Mask::None,
                Some(_) if invert => Mask::Invert,
                Some(_) => Mask::Clip,
            };
            let reads = shaders::reads_below(blend, alpha);
            let in_layer = layer.is_some();
            let shader = *shader_cache.entry((blend, alpha, mask, in_layer)).or_insert_with(|| {
                let shader = rs.shader_create();
                rs.shader_set_code(shader, &shaders::mesh(blend, alpha, mask, in_layer));
                shader
            });
            let material = rs.material_create();
            rs.material_set_shader(material, shader);
            if let Some(j) = set {
                rs.material_set_param(material, "mask_texture", &mask_pages[j / 3].texture.to_variant());
                rs.material_set_param(material, "mask_channel", &CHANNELS[j % 3].to_variant());
            }
            let rid = rs.canvas_item_create();
            rs.canvas_item_set_material(rid, material);
            if reads && !in_layer {
                rs.canvas_item_set_copy_to_backbuffer(rid, true, everywhere);
            }
            // Inside a layer the parent follows the segment, set as the item draws.
            if !in_layer {
                rs.canvas_item_set_parent(rid, parent);
            }
            Item { rid, material, reads, layer, parent: if in_layer { Rid::Invalid } else { parent } }
        };

        let mut items: Vec<Item> = rig
            .meshes
            .iter()
            .enumerate()
            .map(|(i, m)| item(&mut rs, m.blend, m.alpha_blend, m.invert_mask, mesh_sets[i], mesh_layer[i]))
            .collect();
        for (l, &(_, enclosing, c)) in groups.iter().enumerate() {
            let composite = item(&mut rs, c.blend, c.alpha_blend, c.invert_mask, group_sets[l], enclosing);
            rs.canvas_item_set_default_texture_filter(composite.rid, CanvasItemTextureFilter::NEAREST);
            items.push(composite);
        }
        let screened = rig
            .meshes
            .iter()
            .map(|m| {
                m.screen != BLACK
                    || m.channels.iter().any(|(c, _)| *c == p2l_runtime::rig::Channel::ScreenColor)
                    || m.shapes.iter().flat_map(|b| b.shapes.iter().flatten()).any(|s| s.screen != BLACK)
            })
            .collect();

        // A segment per layer, and one more per item reading inside it.
        let mut layers: Vec<Layer> = groups.iter().map(|&(part, _, _)| Layer { segments: Vec::new(), bases: Vec::new(), part }).collect();
        let mut part_layer = vec![None; rig.parts.len()];
        for (l, layer) in layers.iter_mut().enumerate() {
            let readers = items.iter().filter(|i| i.reads && i.layer == Some(l)).count();
            layer.segments = (0..=readers).map(|_| Page::new(&mut rs, size, hdr)).collect();
            layer.bases = layer.segments[1..]
                .iter()
                .map(|segment| {
                    let base = rs.canvas_item_create();
                    rs.canvas_item_set_parent(base, segment.root);
                    rs.canvas_item_set_material(base, copy);
                    rs.canvas_item_set_default_texture_filter(base, CanvasItemTextureFilter::NEAREST);
                    rs.canvas_item_set_draw_index(base, 0);
                    base
                })
                .collect();
            if let Some(slot) = part_layer.get_mut(layer.part) {
                *slot = Some(l);
            }
        }

        let mut mask_items = Vec::new();
        for (j, set) in sets.iter().enumerate() {
            for &m in set {
                let item = rs.canvas_item_create();
                rs.canvas_item_set_parent(item, mask_pages[j / 3].root);
                rs.canvas_item_set_material(item, mask_materials[j % 3]);
                mask_items.push((item, m));
            }
        }

        // Godot draws a viewport's children before it, deepest first: chain coverage, then the layers with
        // inner groups first and each layer's segments in order, then the target.
        let mut chain: Vec<Rid> = mask_pages.iter().map(|p| p.viewport).collect();
        chain.extend(layers.iter().rev().flat_map(|l| l.segments.iter().map(|s| s.viewport)));
        for (i, &viewport) in chain.iter().enumerate() {
            rs.viewport_set_parent_viewport(viewport, chain.get(i + 1).copied().unwrap_or(target));
        }
        for &viewport in &chain {
            rs.viewport_set_active(viewport, true);
        }

        Drawing {
            target,
            size,
            shaders: owned_shaders.into_iter().chain(shader_cache.into_values()).collect(),
            shared_materials,
            items,
            screened,
            layers,
            part_layer,
            mask_pages,
            mask_items,
        }
    }

    /// Draws [commands] (render_commands of [pose]) with the node mapped onto the target by [transform] and
    /// the canvas offset by [offset] in the node.
    #[allow(clippy::too_many_arguments)]
    pub fn draw(&mut self, rig: &Rig, pose: &Pose, textures: &[Gd<ImageTexture>], commands: &[i32], offset: Vector2, transform: Transform2D, size: Vector2i) {
        let mut rs = RenderingServer::singleton();
        let pages = || self.mask_pages.iter().chain(self.layers.iter().flat_map(|l| l.segments.iter()));
        if size != self.size {
            for page in pages() {
                rs.viewport_set_size(page.viewport, size.x.max(1), size.y.max(1));
            }
        }
        for page in pages() {
            rs.canvas_item_set_transform(page.root, transform);
        }
        self.size = size;
        // Back from the node's space to the target's pixels, where layers lie.
        let inverse = transform.affine_inverse();
        let rect = Rect2::new(Vector2::ZERO, Vector2::new(size.x as f32, size.y as f32));
        let mesh_count = rig.meshes.len();
        for (i, item) in self.items.iter().enumerate() {
            rs.canvas_item_clear(item.rid);
            rs.canvas_item_set_visible(item.rid, false);
            if i >= mesh_count {
                rs.canvas_item_set_transform(item.rid, inverse);
            }
        }
        for &base in self.layers.iter().flat_map(|l| l.bases.iter()) {
            rs.canvas_item_clear(base);
            rs.canvas_item_set_visible(base, false);
            rs.canvas_item_set_transform(base, inverse);
        }
        // Masks cover by their texture alpha at their own vertices, without their colors or opacity.
        for &(item, m) in &self.mask_items {
            rs.canvas_item_clear(item);
            draw_mesh(&mut rs, item, rig, pose, textures, m, offset, Color::WHITE, false);
        }

        let mut open = vec![Open { layer: None, segment: 0, next: 0, composite: false }];
        for &command in commands {
            let index = if let Ok(m) = usize::try_from(command) {
                if m >= mesh_count {
                    continue;
                }
                m
            } else if let Some(part) = render_group_part(command) {
                match self.part_layer.get(part).copied().flatten() {
                    Some(l) => mesh_count + l,
                    None => {
                        // A group without a layer (none such comes from the render tree) keeps to its level.
                        let level = open.last().expect("the node's level stays open");
                        let copy = Open { layer: level.layer, segment: level.segment, next: level.next, composite: false };
                        open.push(copy);
                        continue;
                    }
                }
            } else {
                if open.len() > 1 {
                    let closed = open.pop().expect("checked");
                    let level = open.last_mut().expect("checked");
                    match closed.layer.filter(|_| closed.composite) {
                        Some(l) => {
                            // The group composites its last segment.
                            let layer = &self.layers[l];
                            let m = pose.part_multiply[layer.part];
                            rs.canvas_item_add_texture_rect_ex(self.items[mesh_count + l].rid, rect, layer.segments[closed.segment].texture)
                                .modulate(Color::from_rgba(m[0], m[1], m[2], pose.part_opacity[layer.part]))
                                .done();
                        }
                        None => {
                            level.segment = closed.segment;
                            level.next = closed.next;
                        }
                    }
                }
                continue;
            };
            // Place the item in its level: a reader inside a layer starts the next segment over a copy of the last.
            let level = open.last_mut().expect("the node's level stays open");
            let item = &mut self.items[index];
            rs.canvas_item_set_visible(item.rid, true);
            if let Some(l) = level.layer {
                let layer = &self.layers[l];
                if item.reads && level.segment + 1 < layer.segments.len() {
                    let below = layer.segments[level.segment].texture;
                    level.segment += 1;
                    let base = layer.bases[level.segment - 1];
                    rs.canvas_item_set_visible(base, true);
                    rs.canvas_item_add_texture_rect(base, rect, below);
                    rs.material_set_param(item.material, "below", &below.to_variant());
                    level.next = 1;
                }
                let root = layer.segments[level.segment].root;
                if item.parent != root {
                    rs.canvas_item_set_parent(item.rid, root);
                    item.parent = root;
                }
            }
            rs.canvas_item_set_draw_index(item.rid, level.next);
            level.next += 1;
            if index < mesh_count {
                if self.screened[index] {
                    let s = pose.screen[index];
                    rs.material_set_param(item.material, "screen", &Vector3::new(s[0], s[1], s[2]).to_variant());
                }
                let c = pose.multiply[index];
                draw_mesh(&mut rs, item.rid, rig, pose, textures, index, offset, Color::from_rgba(c[0], c[1], c[2], pose.opacity[index]), true);
            } else {
                let l = index - mesh_count;
                let s = pose.part_screen[self.layers[l].part];
                rs.material_set_param(item.material, "screen", &Vector3::new(s[0], s[1], s[2]).to_variant());
                open.push(Open { layer: Some(l), segment: 0, next: 0, composite: true });
            }
        }
    }

    pub fn free(&mut self) {
        let mut rs = RenderingServer::singleton();
        for (item, _) in self.mask_items.drain(..) {
            rs.free_rid(item);
        }
        for item in self.items.drain(..) {
            rs.free_rid(item.rid);
            rs.free_rid(item.material);
        }
        for layer in self.layers.drain(..) {
            for base in layer.bases {
                rs.free_rid(base);
            }
            for segment in layer.segments {
                segment.free(&mut rs);
            }
        }
        for page in self.mask_pages.drain(..) {
            page.free(&mut rs);
        }
        for material in self.shared_materials.drain(..) {
            rs.free_rid(material);
        }
        for shader in self.shaders.drain(..) {
            rs.free_rid(shader);
        }
    }
}

/// Mesh [m] of [pose] into [item], its vertices offset by [offset], with vertex color [color]; with [cull] and the
/// mesh culling, only Cubism's front faces, those with (b - a) x (c - a) < 0 in canvas coordinates.
#[allow(clippy::too_many_arguments)]
fn draw_mesh(rs: &mut Gd<RenderingServer>, item: Rid, rig: &Rig, pose: &Pose, textures: &[Gd<ImageTexture>], m: usize, offset: Vector2, color: Color, cull: bool) {
    let mesh = &rig.meshes[m];
    let (Some(geometry), Some(vertices)) = (&mesh.geometry, pose.vertices.get(m)) else { return };
    // A mesh without a texture draws nothing, as in the reference.
    let Some(texture) = usize::try_from(mesh.page).ok().and_then(|p| textures.get(p)) else { return };
    let points: PackedVector2Array = vertices.chunks_exact(2).map(|p| Vector2::new(p[0], p[1]) + offset).collect();
    let uvs: PackedVector2Array = geometry.uvs.chunks_exact(2).map(|p| Vector2::new(p[0], p[1])).collect();
    let front = |t: &[u32]| {
        let p = |i: u32| (vertices[i as usize * 2], vertices[i as usize * 2 + 1]);
        let (a, b, c) = (p(t[0]), p(t[1]), p(t[2]));
        (b.0 - a.0) * (c.1 - a.1) - (b.1 - a.1) * (c.0 - a.0) < 0.0
    };
    let indices: PackedInt32Array = if cull && mesh.culling {
        geometry.indices.chunks_exact(3).filter(|t| front(t)).flatten().map(|&i| i as i32).collect()
    } else {
        geometry.indices.iter().map(|&i| i as i32).collect()
    };
    if indices.is_empty() {
        return;
    }
    let colors: PackedColorArray = std::iter::repeat(color).take(points.len()).collect();
    rs.canvas_item_add_triangle_array_ex(item, &indices, &points, &colors).uvs(&uvs).texture(texture.get_rid()).done();
}
