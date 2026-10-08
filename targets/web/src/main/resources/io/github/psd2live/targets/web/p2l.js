// PSD2Live web player: plays a .p2lrt rig with the PSD2Live runtime (WebAssembly) on a WebGL canvas.
// MIT License.
//
//   import { P2LPlayer } from "./p2l.js";
//   const player = await P2LPlayer.create(canvas, "p2l_runtime.wasm", "model.p2lrt");
//   player.play("Idle"); player.lookAt(0.5, 0.2); player.lipSync(0.8);
//
// Masks draw through the stencil buffer; add and multiply blending use blend functions; the other
// extended blend modes draw as normal meshes.

const BLINK = 1, BREATH = 2, LOOK = 4, LIP_SYNC = 8;

const VERTEX = `
attribute vec2 position;
attribute vec2 uv;
uniform vec4 view; // scale x, scale y, offset x, offset y
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position * view.xy + view.zw, 0.0, 1.0);
}`;

const FRAGMENT = `
precision mediump float;
uniform sampler2D tex;
uniform vec3 multiply;
uniform vec3 screen;
uniform float opacity;
uniform float maskThreshold; // above zero: draw as a mask, keeping only covered pixels
varying vec2 vUv;
void main() {
  vec4 c = texture2D(tex, vUv);
  if (maskThreshold > 0.0) {
    if (c.a < maskThreshold) discard;
    gl_FragColor = vec4(0.0);
    return;
  }
  vec3 rgb = c.rgb * multiply;
  rgb = rgb + screen - rgb * screen;
  gl_FragColor = vec4(rgb, c.a * opacity);
}`;

export class P2LPlayer {
  /** Loads the runtime and a rig and starts drawing on [canvas]. */
  static async create(canvas, wasmUrl, rigUrl) {
    const [wasm, rig] = await Promise.all([
      fetch(wasmUrl).then((r) => r.arrayBuffer()),
      fetch(rigUrl).then((r) => r.arrayBuffer()),
    ]);
    const { instance } = await WebAssembly.instantiate(wasm, {});
    const player = new P2LPlayer(canvas, instance.exports, new Uint8Array(rig));
    await player.loadTextures();
    player.start();
    return player;
  }

  constructor(canvas, runtime, bytes) {
    this.canvas = canvas;
    this.rt = runtime;
    const at = runtime.p2l_alloc(bytes.length);
    new Uint8Array(runtime.memory.buffer, at, bytes.length).set(bytes);
    const error = runtime.p2l_alloc(512);
    this.rig = runtime.p2l_rig_load(at, bytes.length, error, 512);
    runtime.p2l_dealloc(at, bytes.length);
    if (!this.rig) {
      const message = this.string(error);
      runtime.p2l_dealloc(error, 512);
      throw new Error("The rig could not be loaded: " + message);
    }
    runtime.p2l_dealloc(error, 512);
    runtime.p2l_behaviors(this.rig, BLINK | BREATH | LOOK);

    const width = runtime.p2l_alloc(8);
    runtime.p2l_canvas(this.rig, width, width + 4);
    [this.width, this.height] = new Float32Array(runtime.memory.buffer, width, 2);
    runtime.p2l_dealloc(width, 8);

    this.clips = this.list(runtime.p2l_clip_count, runtime.p2l_clip_id);
    this.parameters = this.list(runtime.p2l_parameter_count, runtime.p2l_parameter_id);
    this.expressions = this.list(runtime.p2l_expression_count, runtime.p2l_expression_id);
    this.hitAreas = this.list(runtime.p2l_hit_area_count, runtime.p2l_hit_area_id);
    /** What advanced mode the file offers: 1 skinning, 2 exact links, 4 live simulation, 8 collision. */
    this.advancedFeatures = runtime.p2l_advanced_available(this.rig);
    this.meshes = [];
    const count = runtime.p2l_mesh_count(this.rig);
    const scratch = runtime.p2l_alloc(4);
    for (let m = 0; m < count; m++) {
      const vertices = runtime.p2l_mesh_vertex_count(this.rig, m);
      const indexPointer = runtime.p2l_mesh_indices(this.rig, m, scratch);
      const indexCount = new Uint32Array(runtime.memory.buffer, scratch, 1)[0];
      const culling = runtime.p2l_alloc(1);
      const blend = runtime.p2l_mesh_blend(this.rig, m, culling);
      runtime.p2l_dealloc(culling, 1);
      const maskCount = runtime.p2l_mesh_masks(this.rig, m, 0, 0, 0);
      const masks = runtime.p2l_alloc(maskCount * 4 + 4);
      const inverted = masks + maskCount * 4;
      runtime.p2l_mesh_masks(this.rig, m, masks, maskCount, inverted);
      this.meshes.push({
        vertices,
        uvs: new Float32Array(new Float32Array(runtime.memory.buffer, runtime.p2l_mesh_uvs(this.rig, m), vertices * 2)),
        indices: new Uint32Array(new Uint32Array(runtime.memory.buffer, indexPointer, indexCount)),
        texture: runtime.p2l_mesh_texture(this.rig, m),
        blend,
        masks: Array.from(new Uint32Array(runtime.memory.buffer, masks, maskCount)),
        invertMask: new Uint8Array(runtime.memory.buffer, inverted, 1)[0] !== 0,
      });
      runtime.p2l_dealloc(masks, maskCount * 4 + 4);
    }
    runtime.p2l_dealloc(scratch, 4);
    this.order = new Uint32Array(count);
    this.orderPointer = runtime.p2l_alloc(count * 4 + 4);
    this.colors = runtime.p2l_alloc(24);
    this.setupGl();
  }

  string(pointer) {
    const bytes = new Uint8Array(this.rt.memory.buffer, pointer);
    let end = 0;
    while (bytes[end] !== 0) end++;
    return new TextDecoder().decode(bytes.subarray(0, end));
  }

  list(count, id) {
    return Array.from({ length: count(this.rig) }, (_, i) => this.string(id(this.rig, i)));
  }

  setupGl() {
    const gl = this.canvas.getContext("webgl", { stencil: true, premultipliedAlpha: false, alpha: true });
    if (!gl) throw new Error("WebGL is not available");
    // 32-bit indices where available; otherwise meshes past 65535 vertices cannot draw.
    const wide = gl.getExtension("OES_element_index_uint") != null;
    this.indexType = wide ? gl.UNSIGNED_INT : gl.UNSIGNED_SHORT;
    if (!wide) for (const mesh of this.meshes) mesh.indices = new Uint16Array(mesh.indices);
    this.gl = gl;
    const shader = (type, source) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, source);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      return s;
    };
    const program = gl.createProgram();
    gl.attachShader(program, shader(gl.VERTEX_SHADER, VERTEX));
    gl.attachShader(program, shader(gl.FRAGMENT_SHADER, FRAGMENT));
    gl.linkProgram(program);
    gl.useProgram(program);
    this.program = program;
    this.loc = {};
    for (const name of ["view", "tex", "multiply", "screen", "opacity", "maskThreshold"]) this.loc[name] = gl.getUniformLocation(program, name);
    this.positionAttribute = gl.getAttribLocation(program, "position");
    this.uvAttribute = gl.getAttribLocation(program, "uv");
    gl.enableVertexAttribArray(this.positionAttribute);
    gl.enableVertexAttribArray(this.uvAttribute);
    this.positionBuffer = gl.createBuffer();
    for (const mesh of this.meshes) {
      mesh.uvBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, mesh.uvBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, mesh.uvs, gl.STATIC_DRAW);
      mesh.indexBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.indexBuffer);
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, mesh.indices, gl.STATIC_DRAW);
    }
  }

  async loadTextures() {
    const gl = this.gl;
    const rt = this.rt;
    const out = rt.p2l_alloc(16);
    this.textures = [];
    for (let i = 0; i < rt.p2l_texture_count(this.rig); i++) {
      const pointer = rt.p2l_texture_png(this.rig, i, out, out + 8, out + 12);
      const length = new Uint32Array(rt.memory.buffer, out, 1)[0];
      const png = new Uint8Array(rt.memory.buffer, pointer, length).slice();
      const image = await createImageBitmap(new Blob([png], { type: "image/png" }), { premultiplyAlpha: "none" });
      const texture = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      this.textures.push(texture);
    }
    rt.p2l_dealloc(out, 16);
  }

  /** Plays a clip by id, cross-fading; null stops. */
  play(id) {
    this.rt.p2l_play(this.rig, id == null ? -1 : this.clips.indexOf(id));
  }

  /** Fades an expression in by id, the last one out; null fades it out to none. */
  expression(id) {
    this.rt.p2l_expression(this.rig, id == null ? -1 : this.expressions.indexOf(id));
  }

  /** Turns advanced mode's [features] on (0 is the Cubism-equivalent rig); returns those now on. */
  setAdvanced(features) {
    return this.rt.p2l_set_advanced(this.rig, features);
  }

  /** The rig's canvas point under a point of the page, as the canvas element draws it. */
  toRig(clientX, clientY) {
    const r = this.canvas.getBoundingClientRect();
    const fit = Math.min(r.width / this.width, r.height / this.height);
    return [(clientX - r.left - r.width / 2) / fit + this.width / 2, (clientY - r.top - r.height / 2) / fit + this.height / 2];
  }

  /** The hit area (e.g. "HitAreaHead") under a point of the page, or null. */
  hitTest(clientX, clientY) {
    const [x, y] = this.toRig(clientX, clientY);
    const i = this.rt.p2l_hit_test(this.rig, x, y);
    return i < 0 ? null : this.hitAreas[i];
  }

  /** Where to look, each axis -1..1 (x right, y up). */
  lookAt(x, y) {
    this.rt.p2l_look_at(this.rig, x, y);
  }

  /** Mouth opening 0..1; switches lip sync on. */
  lipSync(level) {
    this.rt.p2l_behaviors(this.rig, BLINK | BREATH | LOOK | LIP_SYNC);
    this.rt.p2l_lip_sync(this.rig, level);
  }

  /** Sets a parameter by id under clips and behaviors. */
  setParameter(id, value) {
    const i = this.parameters.indexOf(id);
    if (i >= 0) this.rt.p2l_set_parameter(this.rig, i, value);
  }

  start() {
    let last = performance.now();
    const frame = (now) => {
      this.update(Math.min((now - last) / 1000, 0.1));
      last = now;
      this.draw();
      this.animation = requestAnimationFrame(frame);
    };
    this.animation = requestAnimationFrame(frame);
  }

  stop() {
    cancelAnimationFrame(this.animation);
  }

  update(dt) {
    this.rt.p2l_update(this.rig, dt);
  }

  draw() {
    const gl = this.gl;
    const rt = this.rt;
    const canvas = this.canvas;
    const ratio = window.devicePixelRatio || 1;
    const w = Math.round(canvas.clientWidth * ratio), h = Math.round(canvas.clientHeight * ratio);
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    gl.viewport(0, 0, w, h);
    gl.clearColor(0, 0, 0, 0);
    gl.clearStencil(0);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.STENCIL_BUFFER_BIT);
    // Fit the rig's canvas into the element, y down.
    const fit = Math.min(w / this.width, h / this.height);
    const sx = (2 * fit) / w, sy = (-2 * fit) / h;
    gl.uniform4f(this.loc.view, sx, sy, -this.width * sx / 2, -this.height * sy / 2);
    gl.uniform1i(this.loc.tex, 0);
    gl.activeTexture(gl.TEXTURE0);
    gl.enable(gl.BLEND);

    const count = rt.p2l_render_order(this.rig, this.orderPointer, this.meshes.length);
    const order = new Uint32Array(rt.memory.buffer, this.orderPointer, count).slice();
    for (const m of order) {
      const mesh = this.meshes[m];
      const opacity = rt.p2l_mesh_opacity(this.rig, m);
      if (opacity <= 0) continue;
      if (mesh.masks.length > 0) {
        // Masks write 1 into the stencil where they cover; the mesh draws where it is 1 (or 0 inverted).
        gl.enable(gl.STENCIL_TEST);
        gl.clear(gl.STENCIL_BUFFER_BIT);
        gl.colorMask(false, false, false, false);
        gl.stencilFunc(gl.ALWAYS, 1, 0xff);
        gl.stencilOp(gl.KEEP, gl.KEEP, gl.REPLACE);
        for (const mask of mesh.masks) this.drawMesh(mask, 1, 0.5);
        gl.colorMask(true, true, true, true);
        gl.stencilFunc(mesh.invertMask ? gl.NOTEQUAL : gl.EQUAL, 1, 0xff);
        gl.stencilOp(gl.KEEP, gl.KEEP, gl.KEEP);
      }
      this.blend(mesh.blend);
      rt.p2l_mesh_colors(this.rig, m, this.colors, this.colors + 12);
      const colors = new Float32Array(rt.memory.buffer, this.colors, 6);
      gl.uniform3f(this.loc.multiply, colors[0], colors[1], colors[2]);
      gl.uniform3f(this.loc.screen, colors[3], colors[4], colors[5]);
      this.drawMesh(m, opacity, 0);
      if (mesh.masks.length > 0) gl.disable(gl.STENCIL_TEST);
    }
  }

  blend(mode) {
    const gl = this.gl;
    if (mode === 1 || mode === 3 || mode === 4) gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE, gl.ZERO, gl.ONE);
    else if (mode === 2 || mode === 6) gl.blendFuncSeparate(gl.DST_COLOR, gl.ONE_MINUS_SRC_ALPHA, gl.ZERO, gl.ONE);
    else gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
  }

  drawMesh(m, opacity, maskThreshold) {
    const gl = this.gl;
    const mesh = this.meshes[m];
    const texture = this.textures[mesh.texture];
    if (!texture || mesh.vertices === 0) return;
    const pointer = this.rt.p2l_mesh_vertices(this.rig, m);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.positionBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(this.rt.memory.buffer, pointer, mesh.vertices * 2), gl.STREAM_DRAW);
    gl.vertexAttribPointer(this.positionAttribute, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ARRAY_BUFFER, mesh.uvBuffer);
    gl.vertexAttribPointer(this.uvAttribute, 2, gl.FLOAT, false, 0, 0);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, mesh.indexBuffer);
    gl.bindTexture(gl.TEXTURE_2D, texture);
    gl.uniform1f(this.loc.opacity, opacity);
    gl.uniform1f(this.loc.maskThreshold, maskThreshold);
    gl.drawElements(gl.TRIANGLES, mesh.indices.length, this.indexType, 0);
  }
}
