//! `P2LCharacter`: a Godot 4 node that plays a `.p2lrt` rig through the PSD2Live runtime.
//!
//! The node draws by the rules in `p2l_runtime.h`: each mesh in its own canvas item, reordered every frame
//! by the rig's render commands, through a shader that applies its colors, opacity and mask coverage and
//! composites it by its color and alpha blend modes (see `shaders`). Isolated groups draw into layers and
//! masks into coverage viewports (see `drawing`).

mod drawing;
mod shaders;

use godot::classes::image::Format;
use godot::classes::{FileAccess, INode2D, Image, ImageTexture, Node2D};
use godot::prelude::*;
use p2l_runtime::advanced::{Advanced, Affine};
use p2l_runtime::behavior::Behaviors;
use p2l_runtime::clip::Player;
use p2l_runtime::eval::render_commands;
use p2l_runtime::expression::ExpressionPlayer;
use p2l_runtime::pose::PosePlayer;
use p2l_runtime::rig::TextureKind;
use p2l_runtime::{Evaluator, Physics, Rig};

use drawing::Drawing;

struct P2lExtension;

#[gdextension]
unsafe impl ExtensionLibrary for P2lExtension {}

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
    /// Advanced mode, where the rig offers it: 1 skins along true arcs, 2 exact links, 4 live cloth and hair, 8 collision.
    #[export(flags = (Skin = 1, ExactLinks = 2, Simulation = 4, Collision = 8))]
    advanced_features: u32,

    rig: Option<Rig>,
    evaluator: Evaluator,
    player: Player,
    behavior: Behaviors,
    physics: Option<Physics>,
    expressions: ExpressionPlayer,
    poses: PosePlayer,
    advanced: Advanced,
    /// The pose set through set_parameter, under clips and behaviors.
    pose: Vec<f32>,
    /// The values of the last frame.
    values: Vec<f32>,
    /// Texture pages, premultiplied.
    textures: Vec<Gd<ImageTexture>>,
    /// What draws the rig, made once the node is in a viewport.
    drawing: Option<Drawing>,
    base: Base<Node2D>,
}

#[godot_api]
impl INode2D for P2LCharacter {
    fn init(base: Base<Node2D>) -> Self {
        P2LCharacter {
            rig_path: GString::new(), centered: true, autoplay: GString::new(), behaviors: 3, look_at_mouse: false, advanced_features: 0,
            rig: None, evaluator: Evaluator::new(), player: Player::new(), behavior: Behaviors::default(), physics: None,
            expressions: ExpressionPlayer::new(), poses: PosePlayer::default(), advanced: Advanced::new(),
            pose: Vec::new(), values: Vec::new(), textures: Vec::new(), drawing: None, base,
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
        self.free_drawing();
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
        self.free_drawing();
        let folder = path.get_base_dir();
        self.textures = rig.textures.iter().map(|t| {
            let mut image = Image::new_gd();
            let loaded = match t.kind {
                TextureKind::Png => image.load_png_from_buffer(&PackedByteArray::from(t.data.as_slice())),
                TextureKind::Ktx2 => godot::global::Error::ERR_UNAVAILABLE,
                TextureKind::External => image.load(&folder.path_join(t.uri.as_str())),
            };
            if loaded != godot::global::Error::OK {
                godot_warn!("P2LCharacter: a texture page could not be decoded");
            }
            ImageTexture::create_from_image(&premultiplied(image)).unwrap_or_else(ImageTexture::new_gd)
        }).collect();

        self.pose = rig.defaults();
        self.player = Player::new();
        self.behavior = Behaviors::default();
        self.physics = Some(Physics::new(&rig));
        self.expressions = ExpressionPlayer::new();
        self.poses = PosePlayer::new(&rig);
        self.advanced = Advanced::new();
        self.advanced.set(&rig, self.advanced_features);
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
        self.advanced.reset_simulations();
    }

    /// The advanced features the rig offers (see advanced_features).
    #[func]
    fn get_advanced_available(&self) -> u32 {
        self.rig.as_ref().map_or(0, Advanced::available)
    }

    /// Turns advanced features on (0 is the Cubism-equivalent rig); returns those now on.
    #[func]
    fn set_advanced(&mut self, features: u32) -> u32 {
        self.advanced_features = features;
        let Some(rig) = &self.rig else { return 0 };
        self.advanced.set(rig, features)
    }

    #[func]
    fn get_expression_ids(&self) -> PackedStringArray {
        self.rig.iter().flat_map(|r| r.expressions.iter().map(|e| GString::from(e.id.as_str()))).collect()
    }

    /// Fades an expression in by id over the motion, the last one out; an empty id fades it out to none.
    #[func]
    fn set_expression(&mut self, id: GString) -> bool {
        let Some(rig) = &self.rig else { return false };
        let index = rig.expressions.iter().position(|e| e.id == id.to_string());
        self.expressions.play(rig, index);
        index.is_some() || id.is_empty()
    }

    /// The hit area (e.g. "HitAreaHead") under a point in global coordinates, or an empty string.
    #[func]
    fn hit_test(&self, global: Vector2) -> GString {
        let Some(rig) = &self.rig else { return GString::new() };
        let p = self.base().to_local(global) - self.offset(rig);
        p2l_runtime::eval::hit_area_at(rig, &self.evaluator.pose, p.x, p.y).map_or(GString::new(), |i| GString::from(rig.hit_areas[i].0.as_str()))
    }

    /// How many parts each pose group switches between.
    #[func]
    fn get_pose_group_sizes(&self) -> PackedInt32Array {
        self.rig.iter().flat_map(|r| r.poses.iter().map(|g| g.entries.len() as i32)).collect()
    }

    /// Shows part [entry] of pose group [group], fading the others out.
    #[func]
    fn show_pose(&mut self, group: i32, entry: i32) -> bool {
        let Some(rig) = &self.rig else { return false };
        group >= 0 && entry >= 0 && self.poses.show(rig, group as usize, entry as usize)
    }

    #[func]
    fn get_bone_ids(&self) -> PackedStringArray {
        self.rig.iter().flat_map(|r| r.extensions.bones.iter().map(|(_, id)| GString::from(id.as_str()))).collect()
    }

    /// A bone's frame in the last frame, in the node's space, to attach things to; the identity when unknown.
    #[func]
    fn get_bone_transform(&mut self, id: GString) -> Transform2D {
        let Some(rig) = &self.rig else { return Transform2D::IDENTITY };
        let Some(&(d, _)) = rig.extensions.bones.iter().find(|(_, b)| *b == id.to_string()) else { return Transform2D::IDENTITY };
        let frame = match self.evaluator.pose.deformers.get(d) {
            Some(t) => Affine::of(t),
            None => {
                let values: Vec<f32> = rig.parameters.iter().zip(&self.values).map(|(p, v)| p.normalize(*v)).collect();
                self.advanced.bone_frame(rig, &values, d)
            }
        };
        let Some(m) = frame else { return Transform2D::IDENTITY };
        let o = self.offset(rig);
        Transform2D::from_cols(Vector2::new(m.a, m.c), Vector2::new(m.b, m.d), Vector2::new(m.tx, m.ty) + o)
    }
}


impl P2LCharacter {
    fn last_values(&self) -> &[f32] {
        &self.values
    }

    /// Where the rig's canvas origin sits in the node.
    fn offset(&self, rig: &Rig) -> Vector2 {
        if self.centered { Vector2::new(-rig.canvas.width / 2.0, -rig.canvas.height / 2.0) } else { Vector2::ZERO }
    }

    /// Advances clips, behaviors and physics by [dt] seconds, deforms the rig and redraws it.
    fn advance(&mut self, dt: f32) {
        let Some(rig) = &self.rig else { return };
        self.values.clone_from(&self.pose);
        self.behavior.enabled = self.behaviors;
        self.player.update(rig, dt, &mut self.values);
        self.expressions.update(rig, dt, &mut self.values);
        self.behavior.update(rig, dt, &mut self.values);
        self.poses.update(rig, dt);
        if let Some(physics) = &mut self.physics {
            let skip = self.advanced.skipped_physics(rig);
            physics.step_skipping(rig, dt, &mut self.values, &skip);
        }
        let on = self.advanced.enabled() != 0;
        self.evaluator.evaluate_ext(rig, &self.values, if on { Some(&mut self.advanced) } else { None });
        if on {
            let values: Vec<f32> = rig.parameters.iter().zip(&self.values).map(|(p, v)| p.normalize(*v)).collect();
            self.advanced.step_simulations(rig, &values, dt, &mut self.evaluator.pose);
        }
        let commands = render_commands(rig, &self.evaluator.pose);
        self.poses.apply(rig, &mut self.evaluator.pose);
        self.redraw(&commands);
    }

    /// Draws [commands] into the viewport holding the node, first making the drawing for it when needed.
    fn redraw(&mut self, commands: &[i32]) {
        if self.rig.is_none() || !self.base().is_inside_tree() {
            return;
        }
        let Some(viewport) = self.base().get_viewport() else { return };
        let target = viewport.get_viewport_rid();
        let size = viewport.get_texture().map_or(Vector2::ONE, |t| t.get_size());
        let size = Vector2i::new(size.x.round() as i32, size.y.round() as i32);
        // The node's space onto the target's pixels, which every layer shares.
        let transform = self.base().get_viewport_transform() * self.base().get_global_transform();
        let parent = self.base().get_canvas_item();
        if self.drawing.as_ref().is_some_and(|d| d.target != target) {
            self.free_drawing();
        }
        let Some(rig) = &self.rig else { return };
        let offset = self.offset(rig);
        let drawing = self.drawing.get_or_insert_with(|| Drawing::new(rig, parent, target, size, viewport.is_using_hdr_2d()));
        drawing.draw(rig, &self.evaluator.pose, &self.textures, commands, offset, transform, size);
    }

    fn free_drawing(&mut self) {
        if let Some(mut drawing) = self.drawing.take() {
            drawing.free();
        }
    }
}

/// [image] as RGBA8 with its colors multiplied by their alpha, rounded.
fn premultiplied(mut image: Gd<Image>) -> Gd<Image> {
    if image.is_empty() {
        return image;
    }
    if image.is_compressed() {
        image.decompress();
    }
    image.convert(Format::RGBA8);
    let mut data = image.get_data().to_vec();
    for texel in data.chunks_exact_mut(4) {
        let a = texel[3] as u32;
        for c in &mut texel[..3] {
            *c = ((*c as u32 * a + 127) / 255) as u8;
        }
    }
    Image::create_from_data(image.get_width(), image.get_height(), false, Format::RGBA8, &PackedByteArray::from(data)).unwrap_or(image)
}
