// Plays a DragonBones export with the official runtime core and prints slot vertices.
//   node check.mjs <dir> <name> <spec>
// The spec has one line per sample: `<animation>\t<progress 0..1>`. For each it prints
// `sample\t<animation>\t<progress>` and then `slot\t<name>\t<x y ...>` per mesh slot (armature space).
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
// The runtime's Pixi classes extend PIXI.Sprite at load; the core needs nothing else from Pixi.
globalThis.PIXI = { Sprite: function () {}, VERSION: "4.8.0", Container: function () {}, mesh: {}, utils: {} };
vm.runInThisContext(fs.readFileSync(require.resolve("pixi-dragonbones/dragonBones.js"), "utf8"));
const db = globalThis.dragonBones;

// The runtime pools objects by their class's toString, so each class names itself.
class TestTextureData extends db.TextureData {
  static toString() { return "[class TestTextureData]"; }
}
class TestAtlas extends db.TextureAtlasData {
  static toString() { return "[class TestAtlas]"; }
  createTexture() { return db.BaseObject.borrowObject(TestTextureData); }
}

class TestSlot extends db.Slot {
  static toString() { return "[class TestSlot]"; }
  _onClear() { super._onClear(); this.vertices = null; }
  _initDisplay() {}
  _disposeDisplay() {}
  _onUpdateDisplay() { this._renderDisplay = this._display !== null ? this._display : this._rawDisplay; }
  _addDisplay() {}
  _replaceDisplay() {}
  _removeDisplay() {}
  _updateZOrder() {}
  _updateVisible() {}
  _updateBlendMode() {}
  _updateColor() {}
  _updateFrame() {
    this._renderDisplay = this._meshDisplay;
    if (this._geometryData !== null) {
      const data = this._geometryData.data;
      const count = data.intArray[this._geometryData.offset];
      this.vertices = new Float32Array(count * 2);
    }
  }
  _identityTransform() {}
  _updateTransform() { this.updateGlobalTransform(); }
  _updateMesh() {
    // As the Pixi slot computes an unweighted mesh: setup vertices plus the deform.
    const geometry = this._geometryData;
    const deform = this._displayFrame.deformVertices;
    const hasDeform = deform.length > 0 && geometry.inheritDeform;
    const data = geometry.data;
    const count = data.intArray[geometry.offset];
    let offset = data.intArray[geometry.offset + 2];
    if (offset < 0) offset += 65536;
    const scale = this._armature._armatureData.scale;
    if (this.vertices === null || this.vertices.length !== count * 2) this.vertices = new Float32Array(count * 2);
    for (let i = 0; i < count * 2; i++) this.vertices[i] = data.floatArray[offset + i] * scale + (hasDeform ? deform[i] : 0);
  }
}

const events = { hasDBEventListener: () => false, dispatchDBEvent() {}, addDBEventListener() {}, removeDBEventListener() {} };

class TestFactory extends db.BaseFactory {
  constructor() {
    super(null);
    this._dragonBones = new db.DragonBones(events);
  }
  _buildTextureAtlasData(atlasData) { return atlasData !== null ? atlasData : db.BaseObject.borrowObject(TestAtlas); }
  _buildArmature(dataPackage) {
    const armature = db.BaseObject.borrowObject(db.Armature);
    const proxy = Object.assign({ dbInit() {}, dbClear() {}, dbUpdate() {}, dispose() {}, get armature() { return armature; }, get animation() { return armature.animation; } }, events);
    armature.init(dataPackage.armature, proxy, proxy, this._dragonBones);
    return armature;
  }
  _buildSlot(dataPackage, slotData, armature) {
    const slot = db.BaseObject.borrowObject(TestSlot);
    slot.init(slotData, armature, {}, {});
    return slot;
  }
}

const [dir, name, specFile] = process.argv.slice(2);
const factory = new TestFactory();
factory.parseDragonBonesData(JSON.parse(fs.readFileSync(path.join(dir, `${name}_ske.json`), "utf8")));
for (const file of fs.readdirSync(dir).filter((f) => f.startsWith(`${name}_tex_`) && f.endsWith(".json"))) {
  factory.parseTextureAtlasData(JSON.parse(fs.readFileSync(path.join(dir, file), "utf8")), null);
}
const armature = factory.buildArmature(name);
if (armature === null) throw new Error("The armature did not build");
const animations = armature.animation.animationNames;
console.log(`animations\t${animations.length}`);
console.log(`slots\t${armature.getSlots().length}`);
for (const line of fs.readFileSync(specFile, "utf8").split("\n").filter((l) => l.trim())) {
  const [animation, progress] = line.split("\t");
  armature.animation.gotoAndStopByProgress(animation, Number(progress));
  armature.advanceTime(0);
  console.log(`sample\t${animation}\t${progress}`);
  for (const slot of armature.getSlots()) {
    if (slot.vertices) console.log(`slot\t${slot.name}\t${Array.from(slot.vertices).map((v) => v.toFixed(4)).join(" ")}`);
  }
}
