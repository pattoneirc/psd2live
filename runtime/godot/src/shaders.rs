//! Canvas item shaders for what CanvasItemMaterial cannot draw: screen colors and the extended blend
//! modes, read from the screen texture below the mesh.

/// GLSL for one W3C blend function `vec3 blend(vec3 s, vec3 d)` of an IR blend mode.
fn blend_function(mode: u8) -> &'static str {
    match mode {
        3 => "return min(vec3(1.0), s + d);",
        5 => "return min(s, d);",
        6 => "return s * d;",
        7 => "return mix(vec3(1.0) - min(vec3(1.0), (vec3(1.0) - d) / max(s, vec3(1e-5))), vec3(0.0), step(s, vec3(0.0)));",
        8 => "return max(vec3(0.0), s + d - vec3(1.0));",
        9 => "return max(s, d);",
        10 => "return s + d - s * d;",
        11 => "return mix(min(vec3(1.0), d / max(vec3(1.0) - s, vec3(1e-5))), vec3(1.0), step(vec3(1.0), s));",
        12 => "return mix(2.0 * s * d, vec3(1.0) - 2.0 * (vec3(1.0) - s) * (vec3(1.0) - d), step(vec3(0.5), d));",
        13 => "vec3 dd = mix(sqrt(d), ((16.0 * d - 12.0) * d + 4.0) * d, step(d, vec3(0.25)));\n\treturn mix(d + (2.0 * s - 1.0) * (dd - d), d - (1.0 - 2.0 * s) * d * (1.0 - d), step(s, vec3(0.5)));",
        14 => "return mix(2.0 * s * d, vec3(1.0) - 2.0 * (vec3(1.0) - s) * (vec3(1.0) - d), step(vec3(0.5), s));",
        15 => "return clamp(d + 2.0 * s - 1.0, 0.0, 1.0);",
        16 => "return set_lum(set_sat(s, sat(d)), lum(d));",
        17 => "return set_lum(s, lum(d));",
        _ => "return s;",
    }
}

const NON_SEPARABLE: &str = r#"
float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
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

/// Whether drawing [mode] needs the colors below the mesh.
pub fn reads_below(mode: u8) -> bool {
    !matches!(mode, 0 | 1 | 2 | 4)
}

/// A shader drawing a mesh in [mode] with a `screen` color uniform.
pub fn mesh(mode: u8) -> String {
    let render_mode = match mode {
        1 | 4 => "blend_add",
        2 => "blend_mul",
        _ => "blend_mix",
    };
    let mut code = format!("shader_type canvas_item;\nrender_mode {};\nuniform vec3 screen = vec3(0.0);\n", render_mode);
    if reads_below(mode) {
        code += "uniform sampler2D below : hint_screen_texture, filter_nearest;\n";
        code += NON_SEPARABLE;
        code += &format!("vec3 blend(vec3 s, vec3 d) {{\n\t{}\n}}\n", blend_function(mode));
    }
    // COLOR arrives as the texture times the vertex color (multiply color and opacity).
    code += "void fragment() {\n\tvec4 c = COLOR;\n\tc.rgb = c.rgb + screen - c.rgb * screen;\n";
    if reads_below(mode) {
        // Over an opaque backdrop, W3C compositing mixes B(s, d) by the source alpha.
        code += "\tc.rgb = blend(c.rgb, texture(below, SCREEN_UV).rgb);\n";
    }
    code += "\tCOLOR = c;\n}\n";
    code
}
