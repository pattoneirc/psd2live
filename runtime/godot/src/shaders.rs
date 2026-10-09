//! Canvas item shaders for the drawing rules in `p2l_runtime.h`: every mesh, and every isolated group's
//! layer, draws through [mesh], which applies the multiply and screen colors, opacity and mask coverage
//! to a premultiplied texel and composites it by the color and alpha blend modes. Where the hardware
//! blend can express the rule exactly it does; otherwise the shader reads the screen below.

/// How a mesh or layer takes its mask coverage.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Mask {
    None,
    Clip,
    Invert,
}

/// How a source s composites over the destination d, both premultiplied.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Path {
    /// Normal under over: s + d*(1 - s.a).
    Over,
    /// Cubism's add, any alpha mode: rgb = s.rgb + d.rgb, a = d.a.
    CubismAdd,
    /// Cubism's multiply, any alpha mode: rgb = s.rgb*d.rgb + d.rgb*(1 - s.a), a = d.a.
    CubismMultiply,
    /// Another color mode under over: the blended color weighed by d.a, then source-over.
    BlendOver,
    /// Out: the source erases, d*(1 - s.a).
    Out,
    /// Atop, conjoint and disjoint over: the whole rule, written over the destination.
    Full,
}

fn path(blend: u8, alpha: u8) -> Path {
    match (blend, alpha) {
        (1, _) => Path::CubismAdd,
        (2, _) => Path::CubismMultiply,
        (_, 2) => Path::Out,
        (_, 1 | 3 | 4) => Path::Full,
        (0, _) => Path::Over,
        _ => Path::BlendOver,
    }
}

/// Whether drawing in [blend] and [alpha] reads the colors below.
pub fn reads_below(blend: u8, alpha: u8) -> bool {
    matches!(path(blend, alpha), Path::BlendOver | Path::Full)
}

/// GLSL for the W3C blend function `vec3 blend(vec3 s, vec3 d)` of a color blend mode, on unpremultiplied
/// colors (s the source, d the backdrop).
fn blend_function(mode: u8) -> &'static str {
    match mode {
        1 | 3 | 4 => "return min(vec3(1.0), s + d);",
        2 | 6 => "return s * d;",
        5 => "return min(s, d);",
        7 => "vec3 r = vec3(1.0) - min(vec3(1.0), (vec3(1.0) - d) / max(s, vec3(1e-6)));\n\tr = mix(r, vec3(0.0), step(s, vec3(0.0)));\n\treturn mix(r, vec3(1.0), step(vec3(1.0), d));",
        8 => "return max(vec3(0.0), s + d - vec3(1.0));",
        9 => "return max(s, d);",
        10 => "return s + d - s * d;",
        11 => "vec3 r = min(vec3(1.0), d / max(vec3(1.0) - s, vec3(1e-6)));\n\tr = mix(r, vec3(1.0), step(vec3(1.0), s));\n\treturn mix(r, vec3(0.0), step(d, vec3(0.0)));",
        12 => "return mix(2.0 * s * d, vec3(1.0) - 2.0 * (vec3(1.0) - s) * (vec3(1.0) - d), step(vec3(0.5), d));",
        13 => "vec3 dd = mix(sqrt(d), ((16.0 * d - 12.0) * d + 4.0) * d, step(d, vec3(0.25)));\n\treturn mix(d + (2.0 * s - 1.0) * (dd - d), d - (1.0 - 2.0 * s) * d * (1.0 - d), step(s, vec3(0.5)));",
        14 => "return mix(2.0 * s * d, vec3(1.0) - 2.0 * (vec3(1.0) - s) * (vec3(1.0) - d), step(vec3(0.5), s));",
        15 => "return clamp(d + 2.0 * s - 1.0, 0.0, 1.0);",
        16 => "return set_lum(set_sat(s, sat(d)), lum(d));",
        17 => "return set_lum(s, lum(d));",
        _ => "return s;",
    }
}

const NON_SEPARABLE: &str = r#"float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 clip_color(vec3 c) {
	float l = lum(c); float n = min(c.r, min(c.g, c.b)); float x = max(c.r, max(c.g, c.b));
	if (n < 0.0) c = l + (c - l) * l / (l - n);
	if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l);
	return c;
}
vec3 set_lum(vec3 c, float l) { return clip_color(c + (l - lum(c))); }
float sat(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
vec3 set_sat(vec3 c, float s) {
	float lo = min(c.r, min(c.g, c.b)); float hi = max(c.r, max(c.g, c.b));
	return hi > lo ? (c - lo) * s / (hi - lo) : vec3(0.0);
}
"#;

/// A shader drawing a mesh (or a layer) in color blend mode [blend] and alpha blend mode [alpha].
///
/// TEXTURE holds premultiplied color; the vertex color carries the multiply color and the opacity, the
/// `screen` uniform the screen color. A masked shader multiplies by the coverage in `mask_texture`'s
/// channel `mask_channel` at the fragment (1 - coverage when inverted). A shader that reads the colors
/// below takes them from the screen texture, or with [below_uniform] from the texture `below`: Godot's
/// back buffer copy drops the screen's alpha under the Forward+ and Mobile renderers, so a layer keeps
/// what lies below a reading item in a viewport of its own (see `drawing`).
pub fn mesh(blend: u8, alpha: u8, mask: Mask, below_uniform: bool) -> String {
    let path = path(blend, alpha);
    let render_mode = match path {
        Path::Over | Path::CubismAdd | Path::BlendOver => "blend_premul_alpha",
        Path::CubismMultiply | Path::Out => "blend_mul",
        Path::Full => "blend_disabled",
    };
    let mut code = format!("shader_type canvas_item;\nrender_mode unshaded, {};\n", render_mode);
    code += "uniform vec3 screen = vec3(0.0);\n";
    if mask != Mask::None {
        code += "uniform sampler2D mask_texture : filter_nearest, repeat_disable;\n";
        code += "uniform vec3 mask_channel = vec3(1.0, 0.0, 0.0);\n";
    }
    if reads_below(blend, alpha) {
        let hint = if below_uniform { "" } else { "hint_screen_texture, " };
        code += &format!("uniform sampler2D below : {}filter_nearest, repeat_disable;\n", hint);
        if matches!(blend, 16 | 17) {
            code += NON_SEPARABLE;
        }
        code += &format!("vec3 blend(vec3 s, vec3 d) {{\n\t{}\n}}\n", blend_function(blend));
    }
    code += "varying vec4 tint;\nvoid vertex() {\n\ttint = COLOR;\n}\n";
    // A layer can hold more color than alpha (Cubism's add over nothing); unpremultiplied, it clamps.
    code += "void fragment() {\n\tvec4 c = texture(TEXTURE, UV);\n\tc.rgb = min(c.rgb, vec3(c.a));\n\tc.rgb *= tint.rgb;\n\tc.rgb += screen * c.a - c.rgb * screen;\n";
    code += "\tfloat k = clamp(tint.a, 0.0, 1.0);\n";
    match mask {
        Mask::None => {}
        Mask::Clip => code += "\tk *= min(1.0, dot(texture(mask_texture, SCREEN_UV).rgb, mask_channel));\n",
        Mask::Invert => code += "\tk *= 1.0 - min(1.0, dot(texture(mask_texture, SCREEN_UV).rgb, mask_channel));\n",
    }
    code += "\tc *= k;\n";
    match path {
        Path::Over => code += "\tCOLOR = c;\n",
        // The source alpha 0 leaves the destination, alpha included, and adds the source color.
        Path::CubismAdd => code += "\tCOLOR = vec4(c.rgb, 0.0);\n",
        // Multiplied by the destination: rgb*(s.rgb + 1 - s.a), alpha by 1.
        Path::CubismMultiply => code += "\tCOLOR = vec4(c.rgb + vec3(1.0 - c.a), 1.0);\n",
        Path::Out => code += "\tCOLOR = vec4(1.0 - c.a);\n",
        Path::BlendOver | Path::Full => {
            code += "\tvec4 d = texture(below, SCREEN_UV);\n\tfloat sa = c.a;\n\tfloat da = d.a;\n";
            code += "\tvec3 cs = sa > 0.0 ? clamp(c.rgb / sa, 0.0, 1.0) : vec3(0.0);\n";
            code += "\tvec3 cb = da > 0.0 ? clamp(d.rgb / da, 0.0, 1.0) : vec3(0.0);\n";
            // The overlap the blended color takes: uncorrelated, conjoint (largest) or disjoint (smallest).
            let overlap = match alpha {
                3 => "min(sa, da)",
                4 => "max(sa + da - 1.0, 0.0)",
                _ => "sa * da",
            };
            code += &format!("\tfloat p = {};\n", overlap);
            code += "\tvec3 m = mix(cs, blend(cs, cb), sa > 0.0 ? p / sa : 0.0);\n";
            if path == Path::BlendOver {
                // Source-over by the hardware: s.a*m + d*(1 - s.a).
                code += "\tCOLOR = vec4(clamp(sa * m, 0.0, 1.0), sa);\n";
            } else {
                let (fa, fb) = match alpha {
                    1 => ("da", "1.0 - sa"),
                    3 => ("1.0", "(da <= 0.0 || sa >= da) ? 0.0 : 1.0 - sa / da"),
                    _ => ("1.0", "da <= 0.0 ? 0.0 : min(1.0, (1.0 - sa) / da)"),
                };
                code += &format!("\tfloat fa = {};\n\tfloat fb = {};\n", fa, fb);
                code += "\tCOLOR = clamp(vec4(sa * fa * m + da * fb * cb, sa * fa + da * fb), 0.0, 1.0);\n";
            }
        }
    }
    code += "}\n";
    code
}

/// A shader copying a texture, alpha included, over what lies below.
pub const COPY: &str = "shader_type canvas_item;
render_mode unshaded, blend_disabled;
void fragment() {
	COLOR = texture(TEXTURE, UV);
}
";

/// A shader adding a mask mesh's texture alpha into channel `channel` of a coverage layer, the other
/// channels and the alpha kept.
pub const MASK: &str = "shader_type canvas_item;
render_mode unshaded, blend_premul_alpha;
uniform vec3 channel = vec3(1.0, 0.0, 0.0);
void fragment() {
	COLOR = vec4(channel * texture(TEXTURE, UV).a, 0.0);
}
";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_follow_the_header() {
        // Cubism's add and multiply ignore the alpha mode.
        for alpha in 0..5 {
            assert_eq!(path(1, alpha), Path::CubismAdd);
            assert_eq!(path(2, alpha), Path::CubismMultiply);
        }
        assert_eq!(path(0, 0), Path::Over);
        assert_eq!(path(4, 0), Path::BlendOver);
        assert_eq!(path(0, 1), Path::Full);
        assert_eq!(path(10, 2), Path::Out);
        assert!(!reads_below(0, 0) && !reads_below(1, 3) && !reads_below(0, 2));
        assert!(reads_below(3, 0) && reads_below(0, 4));
    }

    #[test]
    fn every_shader_names_what_it_uses() {
        for blend in 0..18 {
            for alpha in 0..5 {
                for (mask, uniform) in [(Mask::None, false), (Mask::Clip, true), (Mask::Invert, false)] {
                    let code = mesh(blend, alpha, mask, uniform);
                    assert_eq!(code.contains("hint_screen_texture"), reads_below(blend, alpha) && !uniform);
                    assert_eq!(code.contains("texture(below"), reads_below(blend, alpha), "{blend} {alpha}");
                    assert_eq!(code.contains("mask_texture"), mask != Mask::None);
                    assert_eq!(code.contains("set_lum("), matches!(blend, 16 | 17) && reads_below(blend, alpha));
                    assert_eq!(code.matches('{').count(), code.matches('}').count());
                }
            }
        }
    }
}
