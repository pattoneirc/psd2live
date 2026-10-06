//! `P2LCharacter`: a Godot 4 node that plays a `.p2lrt` rig through the PSD2Live runtime.
//!
//! Each mesh draws into its own canvas item under the node, reordered every frame by the rig's draw
//! order. Add and multiply blending use a CanvasItemMaterial; a masked mesh draws inside a clip-only
//! canvas group holding its masks. Inverted masks, screen colors and the extended blend modes draw as
//! normal meshes.

use godot::classes::canvas_item_material::BlendMode;
use godot::classes::rendering_server::CanvasGroupMode;
use godot::classes::{CanvasItemMaterial, FileAccess, INode2D, Image, ImageTexture, Node2D, RenderingServer};
use godot::prelude::*;
use p2l_runtime::behavior::Behaviors;
use p2l_runtime::clip::Player;
use p2l_runtime::{render_order, Evaluator, Physics, Rig};

struct P2lExtension;

#[gdextension]
unsafe impl ExtensionLibrary for P2lExtension {}

/// The canvas items of one mesh: a slot in draw order and, for a masked mesh, the item inside its mask group.
struct Slot {
    item: Rid,
    content: Option<Rid>,
}

#[derive(GodotClass)]
#[class(base = Node2D)]
pub struct P2LCharacter {
    /// The compiled rig, exported from PSD2Live as `.p2lrt`.
    #[export(file = "*.p2lrt")]
    rig_path: GString,
    /// Draws the canvas centered on the node instead of from its top-left corner.
    #[export]
    centered: bool,
    /// A clip to play when the rig loads, by id; empty for none.
    #[export]
    autoplay: GString,
    /// Behaviors: 1 blink, 2 breathing, 4 gaze, 8 lip sync.
    #[export(flags = (Blink = 1, Breath = 2, Look = 4, LipSync = 8))]
    behaviors: u32,
    /// Turns the gaze toward the mouse every frame.
    #[export]
    look_at_mouse: bool,

    rig: Option<Rig>,
    evaluator: Evaluator,
    player: Player,
    behavior: Behaviors,
    physics: Option<Physics>,
    /// The pose set through set_parameter, under clips and behaviors.
    pose: Vec<f32>,
    /// The values of the last frame.
    values: Vec<f32>,
    textures: Vec<Gd<ImageTexture>>,
    materials: Vec<Option<Gd<CanvasItemMaterial>>>,
    slots: Vec<Slot>,
    base: Base<Node2D>,
}

#[godot_api]
impl INode2D for P2LCharacter {
    fn init(base: Base<Node2D>) -> Self {
        P2LCharacter {
            rig_path: GString::new(), centered: true, autoplay: GString::new(), behaviors: 3, look_at_mouse: false,
            rig: None, evaluator: Evaluator::new(), player: Player::new(), behavior: Behaviors::default(), physics: None,
            pose: Vec::new(), values: Vec::new(), textures: Vec::new(), materials: Vec::new(), slots: Vec::new(), base,
        }
    }

    fn ready(&mut self) {
        if !self.rig_path.is_empty() {
            let path = self.rig_path.clone();
            self.load(path);
        }
    }

    fn process(&mut self, delta: f64) {
        if self.look_at_mouse {
            let mouse = self.base().get_global_mouse_position();
            self.look_at_point(mouse);
        }
        self.advance(delta as f32);
    }

    fn exit_tree(&mut self) {
        self.free_slots();
    }
}

#[godot_api]
impl P2LCharacter {
    /// Emitted when a rig has loaded.
    #[signal]
    fn rig_loaded();

    /// Loads a `.p2lrt` file; returns false and reports an error when it cannot.
    #[func]
    fn load(&mut self, path: GString) -> bool {
        let bytes = FileAccess::get_file_as_bytes(&path);
        let rig = match Rig::read(bytes.as_slice()) {
            Ok(rig) => rig,
            Err(e) => {
                godot_error!("P2LCharacter: cannot load {}: {}", path, e);
                return false;
            }
        };
        self.free_slots();
        self.textures = rig.textures.iter().map(|t| {
            let mut image = Image::new_gd();
            let png = PackedByteArray::from(t.png.as_slice());
            if image.load_png_from_buffer(&png) != godot::global::Error::OK {
                godot_warn!("P2LCharacter: a texture page could not be decoded");
            }
            ImageTexture::create_from_image(&image).unwrap_or_else(ImageTexture::new_gd)
        }).collect();
        self.materials = rig.meshes.iter().map(|m| {
            let mode = match m.blend {
                1 => BlendMode::ADD,
                2 => BlendMode::MUL,
                _ => return None,
            };
            let mut material = CanvasItemMaterial::new_gd();
            material.set_blend_mode(mode);
            Some(material)
        }).collect();
        let mut rs = RenderingServer::singleton();
        let parent = self.base().get_canvas_item();
        self.slots = rig.meshes.iter().enumerate().map(|(i, m)| {
            let item = rs.canvas_item_create();
            rs.canvas_item_set_parent(item, parent);
            let content = if m.masked_by.is_empty() || m.invert_mask {
                None
            } else {
                rs.canvas_item_set_canvas_group_mode(item, CanvasGroupMode::CLIP_ONLY);
                let content = rs.canvas_item_create();
                rs.canvas_item_set_parent(content, item);
                Some(content)
            };
            if let Some(material) = &self.materials[i] {
                rs.canvas_item_set_material(content.unwrap_or(item), material.get_rid());
            }
            Slot { item, content }
        }).collect();
        self.pose = rig.defaults();
        self.player = Player::new();
        self.behavior = Behaviors::default();
        self.physics = Some(Physics::new(&rig));
        let autoplay = rig.clips.iter().position(|c| c.id == self.autoplay.to_string());
        self.rig = Some(rig);
        if let Some(clip) = autoplay {
            self.player.play(clip);
        }
        self.advance(0.0);
        self.base_mut().emit_signal("rig_loaded", &[]);
        true
    }

    /// Plays a clip by id, fading out the current one.
    #[func]
    fn play(&mut self, clip: GString) -> bool {
        let Some(rig) = &self.rig else { return false };
        match rig.clips.iter().position(|c| c.id == clip.to_string()) {
            Some(i) => {
                self.player.play(i);
                true
            }
            None => false,
        }
    }

    #[func]
    fn stop(&mut self) {
        self.player.stop();
    }

    /// The rig's canvas in pixels.
    #[func]
    fn get_canvas_size(&self) -> Vector2 {
        self.rig.as_ref().map_or(Vector2::ZERO, |r| Vector2::new(r.canvas.width, r.canvas.height))
    }

    #[func]
    fn get_clip_ids(&self) -> PackedStringArray {
        self.rig.iter().flat_map(|r| r.clips.iter().map(|c| GString::from(c.id.as_str()))).collect()
    }

    #[func]
    fn get_parameter_ids(&self) -> PackedStringArray {
        self.rig.iter().flat_map(|r| r.parameters.iter().map(|p| GString::from(p.id.as_str()))).collect()
    }

    /// Sets a parameter of the pose clips and behaviors play over.
    #[func]
    fn set_parameter(&mut self, id: GString, value: f32) -> bool {
        let Some(i) = self.rig.as_ref().and_then(|r| r.parameter(&id.to_string())) else { return false };
        self.pose[i] = value;
        true
    }

    /// A parameter's value in the last frame.
    #[func]
    fn get_parameter(&self, id: GString) -> f32 {
        let Some(rig) = &self.rig else { return 0.0 };
        rig.parameter(&id.to_string()).map_or(0.0, |i| self.last_values().get(i).copied().unwrap_or(0.0))
    }

    /// Turns the gaze toward a point in global coordinates.
    #[func]
    fn look_at_point(&mut self, global: Vector2) {
        let Some(rig) = &self.rig else { return };
        let local = self.base().to_local(global);
        let (w, h) = (rig.canvas.width, rig.canvas.height);
        let center = if self.centered { Vector2::ZERO } else { Vector2::new(w / 2.0, h / 2.0) };
        let d = local - center;
        self.behavior.look_at(d.x / (w / 2.0), -d.y / (h / 2.0));
    }

    /// The mouth opening 0..1 for lip sync.
    #[func]
    fn lip_sync(&mut self, level: f32) {
        self.behavior.lip_sync(level);
    }

    /// Advances clips, behaviors and physics by [delta] seconds and redraws; the node calls it every frame.
    #[func(rename = advance)]
    fn advance_func(&mut self, delta: f32) {
        self.advance(delta);
    }

    #[func]
    fn reset_physics(&mut self) {
        if let Some(rig) = &self.rig {
            self.physics = Some(Physics::new(rig));
        }
    }
}

impl P2LCharacter {
    fn last_values(&self) -> &[f32] {
        &self.values
    }

    /// Advances clips, behaviors and physics by [dt] seconds, deforms the rig and redraws it.
    fn advance(&mut self, dt: f32) {
        let Some(rig) = &self.rig else { return };
        self.values.clone_from(&self.pose);
        self.behavior.enabled = self.behaviors;
        self.player.update(rig, dt, &mut self.values);
        self.behavior.update(rig, dt, &mut self.values);
        if let Some(physics) = &mut self.physics {
            physics.step(rig, dt, &mut self.values);
        }
        let pose = self.evaluator.evaluate(rig, &self.values);
        let order = render_order(rig, pose);
        let offset = if self.centered { Vector2::new(-rig.canvas.width / 2.0, -rig.canvas.height / 2.0) } else { Vector2::ZERO };
        let mut rs = RenderingServer::singleton();
        for slot in &self.slots {
            rs.canvas_item_clear(slot.item);
            rs.canvas_item_set_visible(slot.item, false);
            if let Some(content) = slot.content {
                rs.canvas_item_clear(content);
            }
        }
        for (draw_index, &m) in order.iter().enumerate() {
            let m = m as usize;
            let slot = &self.slots[m];
            rs.canvas_item_set_visible(slot.item, true);
            rs.canvas_item_set_draw_index(slot.item, draw_index as i32);
            match slot.content {
                Some(content) => {
                    // The masks draw into the clip group; the mesh inside it shows only where they cover.
                    for &mask in &rig.meshes[m].masked_by {
                        self.draw_mesh(&mut rs, slot.item, mask, offset, true);
                    }
                    self.draw_mesh(&mut rs, content, m, offset, false);
                }
                None => self.draw_mesh(&mut rs, slot.item, m, offset, false),
            }
        }
    }

    fn draw_mesh(&self, rs: &mut Gd<RenderingServer>, item: Rid, m: usize, offset: Vector2, as_mask: bool) {
        let (Some(rig), pose) = (&self.rig, &self.evaluator.pose) else { return };
        let mesh = &rig.meshes[m];
        let Some(geometry) = &mesh.geometry else { return };
        let vertices = &pose.vertices[m];
        let points: PackedVector2Array = vertices.chunks_exact(2).map(|p| Vector2::new(p[0], p[1]) + offset).collect();
        let uvs: PackedVector2Array = geometry.uvs.chunks_exact(2).map(|p| Vector2::new(p[0], p[1])).collect();
        let indices: PackedInt32Array = geometry.indices.iter().map(|&i| i as i32).collect();
        let color = if as_mask {
            Color::from_rgba(1.0, 1.0, 1.0, pose.opacity[m].max(0.0).min(1.0).max(if pose.opacity[m] > 0.0 { 1.0 } else { 0.0 }))
        } else {
            let c = pose.multiply[m];
            Color::from_rgba(c[0], c[1], c[2], pose.opacity[m])
        };
        let colors: PackedColorArray = std::iter::repeat(color).take(points.len()).collect();
        let mut call = rs.canvas_item_add_triangle_array_ex(item, &indices, &points, &colors).uvs(&uvs);
        if let Some(texture) = usize::try_from(mesh.page).ok().and_then(|p| self.textures.get(p)) {
            call = call.texture(texture.get_rid());
        }
        call.done();
    }

    fn free_slots(&mut self) {
        let mut rs = RenderingServer::singleton();
        for slot in self.slots.drain(..) {
            if let Some(content) = slot.content {
                rs.free_rid(content);
            }
            rs.free_rid(slot.item);
        }
    }
}
