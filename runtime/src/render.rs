//! A software renderer of the last evaluation, by the drawing rules in `include/p2l_runtime.h` and as PSD2Live's
//! reference rasterizer draws them (format-compile, SoftwareRasterizer): bilinear premultiplied texels, pixel
//! centers with a top-left rule, masks by texture alpha, isolated groups as layers, every color and alpha blend
//! mode, and culling. For hosts without a renderer of their own, thumbnails and checks.

use crate::eval::{render_group_part, Pose, RENDER_END_GROUP};
use crate::png::Image;
use crate::rig::{Mesh, Rgb, Rig};

/// Color blend modes, as `p2l_mesh_blend` numbers them.
const NORMAL: u8 = 0;
const CUBISM_ADD: u8 = 1;
const CUBISM_MULTIPLY: u8 = 2;
const ADD: u8 = 3;
const ADD_GLOW: u8 = 4;
const DARKEN: u8 = 5;
const MULTIPLY: u8 = 6;
const COLOR_BURN: u8 = 7;
const LINEAR_BURN: u8 = 8;
const LIGHTEN: u8 = 9;
const SCREEN: u8 = 10;
const COLOR_DODGE: u8 = 11;
const OVERLAY: u8 = 12;
const SOFT_LIGHT: u8 = 13;
const HARD_LIGHT: u8 = 14;
const LINEAR_LIGHT: u8 = 15;
const HUE: u8 = 16;
const COLOR: u8 = 17;

/// Alpha blend modes.
const OVER: u8 = 0;
const ATOP: u8 = 1;
const OUT: u8 = 2;
const CONJOINT: u8 = 3;
const DISJOINT: u8 = 4;

/// How an isolated part's group composites, as the C ABI reports it.
pub struct GroupStyle<'a> {
    pub blend: u8,
    pub alpha_blend: u8,
    pub invert_mask: bool,
    pub masks: &'a [u32],
}

/// What a render reads: the rig, its last pose and render commands, each page's texture and each part's group.
pub struct Scene<'a> {
    pub rig: &'a Rig,
    pub pose: &'a Pose,
    pub commands: &'a [i32],
    pub textures: &'a [Option<&'a Image>],
    pub groups: &'a [GroupStyle<'a>],
}

/// Canvas pixels to image pixels: x' = a x + b y + tx, y' = c x + d y + ty.
#[derive(Clone, Copy, Debug)]
pub struct Affine {
    pub a: f32,
    pub b: f32,
    pub c: f32,
    pub d: f32,
    pub tx: f32,
    pub ty: f32,
}

impl Affine {
    fn apply(&self, x: f32, y: f32) -> (f32, f32) {
        (self.a * x + self.b * y + self.tx, self.c * x + self.d * y + self.ty)
    }
}

/// A premultiplied float image, four channels a pixel.
struct Target {
    width: usize,
    height: usize,
    pixels: Vec<f32>,
}

impl Target {
    fn new(width: usize, height: usize) -> Target {
        Target { width, height, pixels: vec![0.0; width * height * 4] }
    }
}

/// Draws [scene] through [transform] into [out]: on what it holds when [keep], else on transparent.
pub fn render(scene: &Scene, transform: Affine, width: usize, height: usize, out: &mut [f32], keep: bool) {
    let mut target = Target { width, height, pixels: if keep { out.to_vec() } else { vec![0.0; width * height * 4] } };
    let mut masks: Vec<(Vec<u32>, Vec<f32>)> = Vec::new();
    // A stack of layers for the isolated groups open; the bottom one is the image.
    let mut layers: Vec<(usize, Target)> = Vec::new();
    for &command in scene.commands {
        if let Some(part) = render_group_part(command) {
            layers.push((part, Target::new(width, height)));
            continue;
        }
        if command == RENDER_END_GROUP {
            let Some((part, layer)) = layers.pop() else { continue };
            let destination = match layers.last_mut() {
                Some((_, t)) => t,
                None => &mut target,
            };
            draw_layer(scene, part, &layer, destination, transform, &mut masks);
            continue;
        }
        let m = command as usize;
        let Some(mesh) = scene.rig.meshes.get(m) else { continue };
        let opacity = scene.pose.opacity.get(m).copied().unwrap_or(0.0).clamp(0.0, 1.0);
        if opacity <= 0.0 {
            continue;
        }
        let coverage = if mesh.masked_by.is_empty() {
            None
        } else {
            let ids: Vec<u32> = mesh.masked_by.iter().map(|i| *i as u32).collect();
            Some(mask(scene, &ids, transform, width, height, &mut masks))
        };
        let destination = match layers.last_mut() {
            Some((_, t)) => t,
            None => &mut target,
        };
        draw_mesh(scene, m, mesh, transform, destination, opacity, coverage.as_deref(), mesh.invert_mask);
    }
    out.copy_from_slice(&target.pixels);
}

/// The coverage of the union of [ids] by their texture alpha, cached per set of masks.
fn mask(scene: &Scene, ids: &[u32], transform: Affine, width: usize, height: usize, cache: &mut Vec<(Vec<u32>, Vec<f32>)>) -> Vec<f32> {
    if let Some((_, c)) = cache.iter().find(|(k, _)| k == ids) {
        return c.clone();
    }
    let mut coverage = vec![0.0f32; width * height];
    for &id in ids {
        let m = id as usize;
        let Some(mesh) = scene.rig.meshes.get(m) else { continue };
        let Some(texture) = texture(scene, mesh) else { continue };
        triangles(scene, m, mesh, transform, width, height, false, |pixel, u, v| {
            let s = sample(texture, u, v);
            coverage[pixel] = coverage[pixel].max(s[3]);
        });
    }
    cache.push((ids.to_vec(), coverage.clone()));
    coverage
}

fn texture<'a>(scene: &'a Scene, mesh: &Mesh) -> Option<&'a Image> {
    usize::try_from(mesh.page).ok().and_then(|p| scene.textures.get(p).copied().flatten())
}

#[allow(clippy::too_many_arguments)]
fn draw_mesh(scene: &Scene, m: usize, mesh: &Mesh, transform: Affine, target: &mut Target, opacity: f32, coverage: Option<&[f32]>, invert: bool) {
    let Some(texture) = texture(scene, mesh) else { return };
    let multiply: Rgb = scene.pose.multiply.get(m).copied().unwrap_or(crate::rig::WHITE);
    let screen: Rgb = scene.pose.screen.get(m).copied().unwrap_or(crate::rig::BLACK);
    let (width, height) = (target.width, target.height);
    let (blend, alpha) = (mesh.blend, mesh.alpha_blend);
    let pixels = &mut target.pixels;
    triangles(scene, m, mesh, transform, width, height, mesh.culling, |pixel, u, v| {
        let s = sample(texture, u, v);
        let a = s[3];
        if a <= 0.0 {
            return;
        }
        // Multiply, then screen, on premultiplied color: c' = c m, then c' + s a - c' s.
        let mut c = [s[0] * multiply[0], s[1] * multiply[1], s[2] * multiply[2]];
        for k in 0..3 {
            c[k] += screen[k] * a - c[k] * screen[k];
        }
        let mut k = opacity;
        if let Some(coverage) = coverage {
            k *= if invert { 1.0 - coverage[pixel] } else { coverage[pixel] };
        }
        if k <= 0.0 {
            return;
        }
        composite(blend, alpha, &mut pixels[pixel * 4..pixel * 4 + 4], [c[0] * k, c[1] * k, c[2] * k, a * k]);
    });
}

fn draw_layer(scene: &Scene, part: usize, layer: &Target, target: &mut Target, transform: Affine, masks: &mut Vec<(Vec<u32>, Vec<f32>)>) {
    let pose = scene.pose;
    let opacity = pose.part_opacity.get(part).copied().unwrap_or(1.0);
    if opacity <= 0.0 {
        return;
    }
    let Some(style) = scene.groups.get(part) else { return };
    let multiply = pose.part_multiply.get(part).copied().unwrap_or(crate::rig::WHITE);
    let screen = pose.part_screen.get(part).copied().unwrap_or(crate::rig::BLACK);
    let coverage = if style.masks.is_empty() { None } else { Some(mask(scene, style.masks, transform, target.width, target.height, masks)) };
    for pixel in 0..layer.width * layer.height {
        let at = pixel * 4;
        let la = layer.pixels[at + 3];
        if la <= 0.0 {
            continue;
        }
        let mut k = opacity;
        if let Some(coverage) = &coverage {
            k *= if style.invert_mask { 1.0 - coverage[pixel] } else { coverage[pixel] };
        }
        let a = la * k;
        if a <= 0.0 {
            continue;
        }
        let mut c = [0.0f32; 3];
        for i in 0..3 {
            c[i] = (layer.pixels[at + i] / la).clamp(0.0, 1.0) * multiply[i];
            c[i] += screen[i] - c[i] * screen[i];
        }
        composite(style.blend, style.alpha_blend, &mut target.pixels[at..at + 4], [c[0] * a, c[1] * a, c[2] * a, a]);
    }
}

/// Calls [fragment] for every image pixel center inside mesh [m]'s triangles with its texture coordinates; with
/// [cull], only for Cubism's front faces, those with a negative area in canvas coordinates.
#[allow(clippy::too_many_arguments)]
fn triangles(scene: &Scene, m: usize, mesh: &Mesh, transform: Affine, width: usize, height: usize, cull: bool, mut fragment: impl FnMut(usize, f32, f32)) {
    let (Some(g), Some(p)) = (&mesh.geometry, scene.pose.vertices.get(m)) else { return };
    // A mirroring transform turns every face over.
    let mirrored = transform.a * transform.d - transform.b * transform.c < 0.0;
    for t in g.indices.chunks_exact(3) {
        let (ia, ib, ic) = (t[0] as usize, t[1] as usize, t[2] as usize);
        if [ia, ib, ic].iter().any(|i| i * 2 + 1 >= p.len()) {
            continue;
        }
        let (ax, ay) = transform.apply(p[ia * 2], p[ia * 2 + 1]);
        let (bx, by) = transform.apply(p[ib * 2], p[ib * 2 + 1]);
        let (cx, cy) = transform.apply(p[ic * 2], p[ic * 2 + 1]);
        let area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if area.abs() < 1e-12 || !area.is_finite() || (cull && (area > 0.0) != mirrored) {
            continue;
        }
        let x0 = ((ax.min(bx).min(cx) - 0.5).floor() as i64).max(0);
        let x1 = ((ax.max(bx).max(cx) - 0.5).ceil() as i64).min(width as i64 - 1);
        let y0 = ((ay.min(by).min(cy) - 0.5).floor() as i64).max(0);
        let y1 = ((ay.max(by).max(cy) - 0.5).ceil() as i64).min(height as i64 - 1);
        if x0 > x1 || y0 > y1 {
            continue;
        }
        let inv = 1.0 / area;
        for y in y0..=y1 {
            let py = y as f32 + 0.5;
            for x in x0..=x1 {
                let px = x as f32 + 0.5;
                // Barycentric weights; a top-left rule keeps shared edges from drawing twice.
                let w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) * inv;
                let w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) * inv;
                let w2 = 1.0 - w0 - w1;
                if !inside(w0, by - cy, cx - bx) || !inside(w1, cy - ay, ax - cx) || !inside(w2, ay - by, bx - ax) {
                    continue;
                }
                let u = w0 * g.uvs[ia * 2] + w1 * g.uvs[ib * 2] + w2 * g.uvs[ic * 2];
                let v = w0 * g.uvs[ia * 2 + 1] + w1 * g.uvs[ib * 2 + 1] + w2 * g.uvs[ic * 2 + 1];
                fragment(y as usize * width + x as usize, u, v);
            }
        }
    }
}

/// Inside the edge with weight [w]: positive, or zero on a top or left edge (by the edge's direction).
fn inside(w: f32, dy: f32, dx: f32) -> bool {
    w > 0.0 || (w == 0.0 && (dy > 0.0 || (dy == 0.0 && dx < 0.0)))
}

/// The texture bilinearly at ([u], [v]), premultiplied, 0..1.
fn sample(t: &Image, u: f32, v: f32) -> [f32; 4] {
    let (w, h) = (t.width as usize, t.height as usize);
    let fx = (u * w as f32 - 0.5).clamp(0.0, (w - 1) as f32);
    let fy = (v * h as f32 - 0.5).clamp(0.0, (h - 1) as f32);
    let (x0, y0) = (fx as usize, fy as usize);
    let (x1, y1) = ((x0 + 1).min(w - 1), (y0 + 1).min(h - 1));
    let (tx, ty) = (fx - x0 as f32, fy - y0 as f32);
    let texel = |x: usize, y: usize| -> [f32; 4] {
        let p = &t.rgba[(y * w + x) * 4..(y * w + x) * 4 + 4];
        let a = p[3] as f32 / 255.0;
        [p[0] as f32 / 255.0 * a, p[1] as f32 / 255.0 * a, p[2] as f32 / 255.0 * a, a]
    };
    let (pa, pb, pd, pe) = (texel(x0, y0), texel(x1, y0), texel(x0, y1), texel(x1, y1));
    let mut out = [0.0; 4];
    for c in 0..4 {
        out[c] = (pa[c] + (pb[c] - pa[c]) * tx) * (1.0 - ty) + (pd[c] + (pe[c] - pd[c]) * tx) * ty;
    }
    out
}

/// The premultiplied source [s] composited into [d] by the color blend [mode] and the [alpha] blend mode, everything
/// clamped to 0..1, as the header states.
pub fn composite(mode: u8, alpha: u8, d: &mut [f32], s: [f32; 4]) {
    let [sr, sg, sb, sa] = s;
    let (dr, dg, db, da) = (d[0], d[1], d[2], d[3]);
    match mode {
        // Cubism's additive and multiply ignore the alpha mode and keep the destination alpha.
        CUBISM_ADD => {
            d[0] = (sr + dr).clamp(0.0, 1.0);
            d[1] = (sg + dg).clamp(0.0, 1.0);
            d[2] = (sb + db).clamp(0.0, 1.0);
        }
        CUBISM_MULTIPLY => {
            d[0] = (sr * dr + dr * (1.0 - sa)).clamp(0.0, 1.0);
            d[1] = (sg * dg + dg * (1.0 - sa)).clamp(0.0, 1.0);
            d[2] = (sb * db + db * (1.0 - sa)).clamp(0.0, 1.0);
        }
        NORMAL if alpha == OVER => {
            d[0] = (sr + dr * (1.0 - sa)).clamp(0.0, 1.0);
            d[1] = (sg + dg * (1.0 - sa)).clamp(0.0, 1.0);
            d[2] = (sb + db * (1.0 - sa)).clamp(0.0, 1.0);
            d[3] = (sa + da * (1.0 - sa)).clamp(0.0, 1.0);
        }
        _ => {
            let unpremultiply = |r: f32, g: f32, b: f32, a: f32| if a > 0.0 { [(r / a).clamp(0.0, 1.0), (g / a).clamp(0.0, 1.0), (b / a).clamp(0.0, 1.0)] } else { [0.0; 3] };
            let cs = unpremultiply(sr, sg, sb, sa);
            let cb = unpremultiply(dr, dg, db, da);
            let blended = mix(mode, cs, cb);
            let overlap = match alpha {
                CONJOINT => sa.min(da),
                DISJOINT => (sa + da - 1.0).max(0.0),
                _ => sa * da,
            };
            let w = if sa > 0.0 { overlap / sa } else { 0.0 };
            let (fa, fb) = match alpha {
                ATOP => (da, 1.0 - sa),
                // The source erases what it covers and adds no color.
                OUT => (0.0, 1.0 - sa),
                CONJOINT => (1.0, if da <= 0.0 || sa >= da { 0.0 } else { 1.0 - sa / da }),
                DISJOINT => (1.0, if da <= 0.0 { 0.0 } else { ((1.0 - sa) / da).min(1.0) }),
                _ => (1.0, 1.0 - sa),
            };
            let (s, dd) = (sa * fa, da * fb);
            for c in 0..3 {
                d[c] = (s * ((1.0 - w) * cs[c] + w * blended[c]) + dd * cb[c]).clamp(0.0, 1.0);
            }
            d[3] = (s + dd).clamp(0.0, 1.0);
        }
    }
}

/// The W3C blend function B(Cb, Cs) of the source [s] over the backdrop [d], unpremultiplied.
fn mix(mode: u8, s: [f32; 3], d: [f32; 3]) -> [f32; 3] {
    match mode {
        HUE => set_lum(set_sat(s, sat(d)), lum(d)),
        COLOR => set_lum(s, lum(d)),
        _ => [separable(mode, s[0], d[0]), separable(mode, s[1], d[1]), separable(mode, s[2], d[2])],
    }
}

fn separable(mode: u8, s: f32, d: f32) -> f32 {
    match mode {
        CUBISM_ADD | ADD | ADD_GLOW => (s + d).min(1.0),
        CUBISM_MULTIPLY | MULTIPLY => s * d,
        SCREEN => s + d - s * d,
        DARKEN => s.min(d),
        LIGHTEN => s.max(d),
        OVERLAY => hard_light(d, s),
        HARD_LIGHT => hard_light(s, d),
        COLOR_DODGE => {
            if d == 0.0 {
                0.0
            } else if s >= 1.0 {
                1.0
            } else {
                (d / (1.0 - s)).min(1.0)
            }
        }
        COLOR_BURN => {
            if d >= 1.0 {
                1.0
            } else if s <= 0.0 {
                0.0
            } else {
                1.0 - ((1.0 - d) / s).min(1.0)
            }
        }
        LINEAR_BURN => (s + d - 1.0).max(0.0),
        LINEAR_LIGHT => (d + 2.0 * s - 1.0).clamp(0.0, 1.0),
        SOFT_LIGHT => {
            if s <= 0.5 {
                d - (1.0 - 2.0 * s) * d * (1.0 - d)
            } else {
                let dd = if d <= 0.25 { ((16.0 * d - 12.0) * d + 4.0) * d } else { d.sqrt() };
                d + (2.0 * s - 1.0) * (dd - d)
            }
        }
        _ => s,
    }
}

fn hard_light(s: f32, d: f32) -> f32 {
    if s <= 0.5 {
        d * 2.0 * s
    } else {
        let t = 2.0 * s - 1.0;
        d + t - d * t
    }
}

fn lum(c: [f32; 3]) -> f32 {
    0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]
}

fn clip(c: [f32; 3]) -> [f32; 3] {
    let l = lum(c);
    let (n, x) = (c[0].min(c[1]).min(c[2]), c[0].max(c[1]).max(c[2]));
    let mut out = c;
    if n < 0.0 {
        out = out.map(|v| l + (v - l) * l / (l - n));
    }
    if x > 1.0 {
        out = out.map(|v| l + (v - l) * (1.0 - l) / (x - l));
    }
    out
}

fn set_lum(c: [f32; 3], l: f32) -> [f32; 3] {
    let d = l - lum(c);
    clip(c.map(|v| v + d))
}

fn sat(c: [f32; 3]) -> f32 {
    c[0].max(c[1]).max(c[2]) - c[0].min(c[1]).min(c[2])
}

fn set_sat(c: [f32; 3], s: f32) -> [f32; 3] {
    let mut order = [0usize, 1, 2];
    order.sort_by(|a, b| c[*a].partial_cmp(&c[*b]).unwrap_or(std::cmp::Ordering::Equal));
    let (lo, mid, hi) = (order[0], order[1], order[2]);
    let mut out = [0.0; 3];
    if c[hi] > c[lo] {
        out[mid] = (c[mid] - c[lo]) * s / (c[hi] - c[lo]);
        out[hi] = s;
    }
    out
}

/// [pixels] (premultiplied floats) as RGBA8, premultiplied or straight, rounded as the reference rasterizer does.
pub fn to_rgba8(pixels: &[f32], straight: bool, out: &mut [u8]) {
    for (p, o) in pixels.chunks_exact(4).zip(out.chunks_exact_mut(4)) {
        let a = p[3].clamp(0.0, 1.0);
        if a <= 0.0 {
            o.copy_from_slice(&[0, 0, 0, 0]);
            continue;
        }
        let channel = |v: f32| ((if straight { v / a } else { v }).clamp(0.0, 1.0) * 255.0 + 0.5) as u8;
        o.copy_from_slice(&[channel(p[0]), channel(p[1]), channel(p[2]), (a * 255.0 + 0.5) as u8]);
    }
}

/// RGBA8 [pixels], premultiplied or straight, as premultiplied floats.
pub fn from_rgba8(pixels: &[u8], straight: bool, out: &mut [f32]) {
    for (p, o) in pixels.chunks_exact(4).zip(out.chunks_exact_mut(4)) {
        let a = p[3] as f32 / 255.0;
        let k = if straight { a } else { 1.0 };
        o.copy_from_slice(&[p[0] as f32 / 255.0 * k, p[1] as f32 / 255.0 * k, p[2] as f32 / 255.0 * k, a]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_compositing_matches_the_reference_rasterizers_cases() {
        // The values SoftwareRasterizerTest expects of the same sources and backdrops.
        let mut d = [0.0, 0.0, 1.0, 1.0];
        composite(NORMAL, OVER, &mut d, [0.5, 0.0, 0.0, 0.5]);
        assert_eq!(d, [0.5, 0.0, 0.5, 1.0]);
        // Cubism's add keeps the destination alpha; the W3C add (glow) covers an empty destination.
        let mut empty = [0.0; 4];
        composite(CUBISM_ADD, OVER, &mut empty, [1.0, 0.0, 0.0, 1.0]);
        assert_eq!(empty[3], 0.0);
        let mut empty = [0.0; 4];
        composite(ADD_GLOW, OVER, &mut empty, [1.0, 0.0, 0.0, 1.0]);
        assert_eq!(empty, [1.0, 0.0, 0.0, 1.0]);
        // OUT erases where the source covers.
        let mut d = [0.0, 0.0, 1.0, 1.0];
        composite(NORMAL, OUT, &mut d, [1.0, 0.0, 0.0, 1.0]);
        assert_eq!(d, [0.0; 4]);
    }

    #[test]
    fn straight_output_unpremultiplies() {
        let mut out = [0u8; 4];
        to_rgba8(&[0.25, 0.0, 0.0, 0.5], true, &mut out);
        assert_eq!(out, [128, 0, 0, 128]);
        to_rgba8(&[0.25, 0.0, 0.0, 0.5], false, &mut out);
        assert_eq!(out, [64, 0, 0, 128]);
    }
}
