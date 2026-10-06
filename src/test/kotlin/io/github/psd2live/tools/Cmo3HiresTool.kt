package io.github.psd2live.tools

import io.github.psd2live.core.Cmo3ModelImport
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.PageSize
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TexturePage
import io.github.psd2live.format.model.TileArt
import io.github.psd2live.format.model.TilePlacement
import io.github.psd2live.targets.cubism.Cmo3Target
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayerSet
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasIngest
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.inversePlacementAffine
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test

/**
 * Spike: how a layer whose raster is denser than its canvas rectangle could be written to a .cmo3.
 * Builds tml, upscales one layer's art [DENSITY] times (with a one-texel checker band that only survives
 * at full resolution) and writes one file per candidate layout, then reads each back with Umamo's
 * reader and the product's cmo3 import and reports the placement chain. The files are for opening in
 * Cubism Editor by hand; the README in the output lists what to check.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*Cmo3HiresTool'
 * PSD2LIVE_HIRES_TILE picks the layer by name (default: the smallest eye/pupil-like layer).
 * Writes build/tools/cmo3-hires/.
 */
class Cmo3HiresTool {
	private val density = 4
	private val pad = 4

	@Test fun write() {
		requireTools()
		val out = output("cmo3-hires").apply { listFiles()?.forEach { it.delete() } }
		val preview = PSD2LivePipeline().buildPreview(Sample.fromEnvironment().path)
		val ir = RigIrCompiler.compile(preview, tileArt = true)
		val target = pickTile(ir)
		val art = ir.textures.tileArt.first { it.tile == target.id }
		val row = sourceRow(ir, target.id)
		val hi = withChecker(upscale(art.raster(), density))
		val report = StringBuilder()
		report.appendLine("layers: " + ir.textures.tiles.joinToString { "${it.name} ${it.width}x${it.height}" })
		report.appendLine("canvas ${ir.canvas.width}x${ir.canvas.height}; layer '${target.name}' (${target.id}) canvas rect ${row.left},${row.top} ${art.width}x${art.height}; hi-res raster ${hi.width}x${hi.height}")
		val variants = linkedMapOf(
			"hires-0-baseline" to Layout(ir, null, emptyMap()),
			"hires-b-layer-canvas-res_atlas-4x" to bLayout(ir, target.id, hi),
			"hires-c1-layer-4x_model-image-affine" to cLayout(ir, target.id, hi, boundsAtCanvasSize = false),
			"hires-c2-layer-4x_bounds-canvas-size" to cLayout(ir, target.id, hi, boundsAtCanvasSize = true),
			"hires-a-document-4x" to aLayout(ir, target.id, hi),
		)
		for ((name, layout) in variants) {
			val bytes = Cmo3.write(Cmo3Target { layout.decorate(it) }.convert(layout.ir, ExportOptions(name)).model)
			File(out, "$name.cmo3").writeBytes(bytes)
			report.appendLine().appendLine("== $name.cmo3 (${bytes.size / 1024} KiB)")
			readBack(bytes, layout.ir, target.name, report)
		}
		File(out, "report.txt").writeText(report.toString())
		File(out, "README.txt").writeText(CHECKLIST)
		print(report)
	}

	/** A variant: the edited IR and the graph edits made after conversion, keyed by tile name. */
	private class Layout(val ir: RigIR, val docScale: Int?, val layers: Map<String, LayerFix>) {
		fun decorate(root: CModelSource) = fixGraph(root, docScale, layers)
	}

	/** The model image's canvas scale (canvas units per layer pixel), and whether its layer rect is written at canvas size. */
	private class LayerFix(val scale: Float, val left: Int, val top: Int, val canvasWidth: Int, val canvasHeight: Int, val boundsAtCanvasSize: Boolean)

	private fun pickTile(ir: RigIR): io.github.psd2live.format.model.TextureTile {
		val named = System.getenv("PSD2LIVE_HIRES_TILE")
		val withArt = ir.textures.tiles.filter { tile -> tile.placement != null && ir.textures.tileArt.any { it.tile == tile.id } && ir.meshes.any { it.tile == tile.id } }
		if (named != null) return withArt.first { it.name == named || it.id == named }
		val eyes = withArt.filter { Regex("(?i)pupil|iris|eye|瞳|目").containsMatchIn(it.name) && !Regex("(?i)brow|lash|眉").containsMatchIn(it.name) && it.width >= 16 && it.height >= 16 }
		return (eyes.ifEmpty { withArt }).minBy { it.width * it.height }
	}

	private fun sourceRow(ir: RigIR, tileId: String): io.github.psd2live.format.model.SourceLayer {
		val ref = ir.textures.tiles.first { it.id == tileId }.source ?: error("tile has no source row")
		return ir.authoring.sources.first { it.id == ref.source }.layers.first { it.key == ref.layer }
	}

	/** (b) The layer stays at canvas resolution (box-filtered from the hi-res art); only the atlas page holds the hi-res texels, packed at scale [density]. */
	private fun bLayout(ir: RigIR, tileId: String, hi: RasterImage): Layout {
		val low = downscale(hi, density)
		val edited = withHiresPage(ir, tileId, hi, layerArt = low, packScale = density.toFloat())
		return Layout(edited, null, emptyMap())
	}

	/** (c) The layer holds the hi-res raster; its model image maps it onto the canvas at 1/[density]. */
	private fun cLayout(ir: RigIR, tileId: String, hi: RasterImage, boundsAtCanvasSize: Boolean): Layout {
		val edited = withHiresPage(ir, tileId, hi, layerArt = hi, packScale = 1f)
		val tile = ir.textures.tiles.first { it.id == tileId }
		val row = sourceRow(ir, tileId)
		return Layout(edited, null, mapOf(tile.name to LayerFix(1f / density, row.left, row.top, tile.width, tile.height, boundsAtCanvasSize)))
	}

	/** (a) The whole layered image at [density]: every layer upsampled, every model image mapped back at 1/[density]. */
	private fun aLayout(ir: RigIR, tileId: String, hi: RasterImage): Layout {
		var edited = withHiresPage(ir, tileId, hi, layerArt = hi, packScale = 1f)
		val tiles = edited.textures.tiles.map { tile ->
			if (tile.id == tileId) tile else tile.copy(width = tile.width * density, height = tile.height * density,
				placement = tile.placement?.let { it.copy(scaleX = it.scaleX / density, scaleY = it.scaleY / density) })
		}
		val art = edited.textures.tileArt.map { a -> if (a.tile == tileId) a else upscaleNearest(a.raster(), density).toTileArt(a.tile) }
		edited = edited.copy(textures = edited.textures.copy(tiles = tiles, tileArt = art))
		val fixes = ir.textures.tiles.filter { it.source != null }.associate { tile ->
			val row = sourceRow(ir, tile.id)
			tile.name to LayerFix(1f / density, row.left, row.top, tile.width, tile.height, false)
		}
		return Layout(edited, density, fixes)
	}

	/**
	 * [ir] with the tile's hi-res art on a page of its own: the page holds [hi] at (pad, pad), the tile's
	 * raster becomes [layerArt] packed at [packScale] page pixels per raster pixel, and its meshes' UVs and
	 * page bindings move to the new page.
	 */
	private fun withHiresPage(ir: RigIR, tileId: String, hi: RasterImage, layerArt: RasterImage, packScale: Float): RigIR {
		val pageW = pow2(hi.width + 2 * pad)
		val pageH = pow2(hi.height + 2 * pad)
		val page = RasterImage(pageW, pageH, ByteArray(pageW * pageH * 4))
		for (y in 0 until hi.height) System.arraycopy(hi.rgba, y * hi.width * 4, page.rgba, ((y + pad) * pageW + pad) * 4, hi.width * 4)
		val pageIndex = ir.textures.pages.size
		check(ir.textures.tilePages.size == pageIndex) { "pages and tile pages are not index-parallel" }
		val tile = ir.textures.tiles.first { it.id == tileId }
		val old = tile.placement!!
		val oldPage = ir.textures.tilePages[old.page]
		val inverse = inversePlacementAffine(AtlasPlacement(old.page, old.x, old.y, old.scaleX, old.scaleY, old.rotation))!!
		val meshes = ir.meshes.map { mesh ->
			val geometry = mesh.geometry
			if (mesh.tile != tileId || geometry == null) return@map mesh
			val uvs = geometry.uvs.shared().copyOf()
			for (i in uvs.indices step 2) {
				val px = uvs[i] * oldPage.width; val py = uvs[i + 1] * oldPage.height
				val lx = inverse[0] * px + inverse[1] * py + inverse[2]
				val ly = inverse[3] * px + inverse[4] * py + inverse[5]
				uvs[i] = (pad + lx * density) / pageW
				uvs[i + 1] = (pad + ly * density) / pageH
			}
			mesh.copy(geometry = geometry.copy(uvs = Floats.wrap(uvs)), page = pageIndex)
		}
		val moved = meshes.filter { it.tile == tileId }.map { it.id }
		val textures = ir.textures.copy(
			pages = ir.textures.pages + TexturePage(pageW, pageH, Bytes.wrap(PngCodec.write(page))),
			tilePages = ir.textures.tilePages + PageSize(pageW, pageH),
			bindings = ir.textures.bindings + moved.associateWith { pageIndex },
			tiles = ir.textures.tiles.map {
				if (it.id != tileId) it else it.copy(width = layerArt.width, height = layerArt.height,
					placement = TilePlacement(pageIndex, pad.toFloat(), pad.toFloat(), packScale, packScale, 0f))
			},
			tileArt = ir.textures.tileArt.map { if (it.tile == tileId) layerArt.toTileArt(tileId) else it },
		)
		return ir.copy(meshes = meshes, textures = textures)
	}

	/** Reads [bytes] back and reports the tile's layer rect, model-image affine, packing and how well the chain reproduces the meshes. */
	private fun readBack(bytes: ByteArray, ir: RigIR, tileName: String, report: StringBuilder) {
		val model = Cmo3.read(bytes)
		val root = model.root as CModelSource
		val manager = root.textureManager as CTextureManager
		val image = modelImages(manager).first { it.name == tileName }
		val m = (image._materialLocalToCanvasTransform as CAffine).array()
		val resource = image._filteredImage as CImageResource
		val (entry, atlas) = entries(manager).first { Cmo3Import.uuidOf(it.first.modelImageGuid) == Cmo3Import.uuidOf(image.guid) }
		val packing = placementOf(entry, 0)
		val half = (entry.atlasLocalToCanvasTransform as CAffine).array()
		val composed = compose(half, forward(packing))
		val layer = layers(manager).first { it.name == tileName }
		val bounds = layer.boundsOnImageDoc as CRect
		val doc = layer._layeredImage as CLayeredImage
		report.appendLine("document ${doc.width}x${doc.height}; layer bounds ${bounds.x},${bounds.y} ${bounds.width}x${bounds.height}; resource ${resource.width}x${resource.height}")
		report.appendLine("model image affine ${fmt(m)}; packing pos ${packing.positionX},${packing.positionY} scale ${packing.scaleX} on ${atlas.name} ${atlas.width}x${atlas.height}")
		report.appendLine("entry half ∘ packing − model image affine: max ${"%.2e".format(compose(composed, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)).zip(m).maxOf { abs(it.first - it.second) })}")
		val corner = apply(m, resource.width.toFloat(), resource.height.toFloat())
		report.appendLine("layer raster maps onto canvas ${m[2]},${m[5]} .. ${corner.first},${corner.second}")
		// The atlas-region input against the imported rest meshes: page pixels through the region transform should land on the canvas positions.
		val ingest = cmo3AtlasIngest(root)
		val tile = ingest.atlas.tiles.first { it.name == tileName }
		report.appendLine("umamo ingest: tile ${tile.width}x${tile.height}, placement scale ${tile.placement?.scaleX}, source row ${ingest.sources.flatMap { it.layers }.firstOrNull { it.name == tileName }?.let { "${it.left},${it.top} ${it.width}x${it.height}" }}")
		val puppet = restMeshesToCanvasSpace(Cmo3Import.fromModelSource(root), ir.restPose.mapKeys { ParameterId(it.key) })
		val pageOf = Cmo3ModelImport.read(bytes).pages
		var residual = 0f
		var count = 0
		for (mesh in artMeshes(root)) {
			val ext = mesh._extensions.let(Cmo3Import::elementsOf).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
			val inputs = Cmo3Import.elementsOf(ext._textureInputs)
			val modelImage = inputs.filterIsInstance<CTextureInput_ModelImage>().firstOrNull() ?: continue
			if (Cmo3Import.uuidOf(modelImage._modelImageGuid) != Cmo3Import.uuidOf(image.guid)) continue
			val region = (inputs.filterIsInstance<CTextureInput_TextureAtlasRegion>().first().inputImageLocalToCanvasTransform as CAffine).array()
			val id = Cmo3Import.idStrOf(mesh.id)!!
			val drawable = puppet.drawables.first { it.id.raw == id }
			val geometry = drawable.mesh ?: continue
			for (i in geometry.uvs.indices step 2) {
				val p = apply(region, geometry.uvs[i] * atlas.width, geometry.uvs[i + 1] * atlas.height)
				residual = max(residual, max(abs(p.first - geometry.positions[i]), abs(p.second - geometry.positions[i + 1])))
			}
			count++
			report.appendLine("drawable $id: region transform ${fmt(region)}; product import page ${pageOf[id]}")
		}
		report.appendLine("region transform vs imported rest mesh over $count drawable(s): max ${"%.3f".format(residual)} canvas px")
	}

	private companion object {
		val CHECKLIST = """
			Cubism Editor checks for build/tools/cmo3-hires (tml, one layer at 4x raster density)
			======================================================================================
			The chosen layer (see report.txt) carries a one-texel checker band across its middle
			third: at full resolution it reads as a fine checker when zoomed in; resampled to the
			canvas it turns into a flat grey-ish band. The rest of the layer is the original art.

			Files
			  hires-0-baseline.cmo3                    current export, canvas-resolution layer (control)
			  hires-b-layer-canvas-res_atlas-4x.cmo3   (b) layer at canvas size, atlas page holds 4x texels (packing scale 4)
			  hires-c1-layer-4x_model-image-affine.cmo3 (c) layer raster 4x, model image affine scale 0.25, layer rect = raster size
			  hires-c2-layer-4x_bounds-canvas-size.cmo3 (c) like c1 but layer rect written at canvas size
			  hires-a-document-4x.cmo3                 (a) whole layered image 4x, every model image affine scale 0.25

			For each file:
			  1. Opens without error or repair dialog; note any warning text.
			  2. Model view (texture atlas display): the chosen layer sits where it does in the
			     baseline, mesh aligned with the art; zoom 800%+ - is the checker crisp?
			  3. Switch to layered-art (PSD) display and back: still aligned? checker crisp in each?
			     Does the switch back recomposite the atlas (layer art replacing the stored page)?
			  4. Mesh edit on the chosen art mesh: the texture under the mesh lines up with the vertices.
			  5. Texture atlas editor: the layer's patch size (is it 4x of the baseline or 1x?), its
			     scale field, and whether moving/rescaling it keeps the checker crisp.
			  6. Re-export texture: File > Export > moc3 (or texture atlas export) - open the PNG:
			     is the layer's region 4x density and crisp?
			  7. PSD re-import is out of scope; note if the editor asks to re-link the source PSD.
			Record per file: OK / misplaced (by how much, which direction) / blurry / error.
		""".trimIndent()

		fun TileArt.raster() = RasterImage(width, height, rgba.shared())
		fun RasterImage.toTileArt(tile: String) = TileArt(tile, width, height, Bytes.wrap(rgba))

		fun pow2(v: Int): Int { var p = 64; while (p < v) p *= 2; return p }

		/** Bilinear upscale with straight alpha weighted by coverage. */
		fun upscale(src: RasterImage, k: Int): RasterImage {
			val w = src.width * k; val h = src.height * k
			val out = ByteArray(w * h * 4)
			fun px(x: Int, y: Int, c: Int) = (src.rgba[((y.coerceIn(0, src.height - 1)) * src.width + x.coerceIn(0, src.width - 1)) * 4 + c].toInt() and 0xFF).toFloat()
			for (y in 0 until h) for (x in 0 until w) {
				val sx = (x + 0.5f) / k - 0.5f; val sy = (y + 0.5f) / k - 0.5f
				val x0 = kotlin.math.floor(sx).toInt(); val y0 = kotlin.math.floor(sy).toInt()
				val fx = sx - x0; val fy = sy - y0
				val weights = floatArrayOf((1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy)
				val xs = intArrayOf(x0, x0 + 1, x0, x0 + 1); val ys = intArrayOf(y0, y0, y0 + 1, y0 + 1)
				var a = 0f; val rgb = FloatArray(3)
				for (i in 0..3) { val al = px(xs[i], ys[i], 3) * weights[i]; a += al; for (c in 0..2) rgb[c] += px(xs[i], ys[i], c) * al }
				val o = (y * w + x) * 4
				for (c in 0..2) out[o + c] = (if (a > 0f) rgb[c] / a else 0f).toInt().coerceIn(0, 255).toByte()
				out[o + 3] = a.toInt().coerceIn(0, 255).toByte()
			}
			return RasterImage(w, h, out)
		}

		fun upscaleNearest(src: RasterImage, k: Int): RasterImage {
			val w = src.width * k
			val out = ByteArray(w * src.height * k * 4)
			for (y in 0 until src.height * k) for (x in 0 until w) System.arraycopy(src.rgba, ((y / k) * src.width + x / k) * 4, out, (y * w + x) * 4, 4)
			return RasterImage(w, src.height * k, out)
		}

		/** Box filter by [k], colour weighted by alpha. */
		fun downscale(src: RasterImage, k: Int): RasterImage {
			val w = src.width / k; val h = src.height / k
			val out = ByteArray(w * h * 4)
			for (y in 0 until h) for (x in 0 until w) {
				var a = 0f; val rgb = FloatArray(3)
				for (dy in 0 until k) for (dx in 0 until k) {
					val i = ((y * k + dy) * src.width + x * k + dx) * 4
					val al = (src.rgba[i + 3].toInt() and 0xFF).toFloat(); a += al
					for (c in 0..2) rgb[c] += (src.rgba[i + c].toInt() and 0xFF) * al
				}
				val o = (y * w + x) * 4
				for (c in 0..2) out[o + c] = (if (a > 0f) rgb[c] / a else 0f).toInt().coerceIn(0, 255).toByte()
				out[o + 3] = (a / (k * k)).toInt().coerceIn(0, 255).toByte()
			}
			return RasterImage(w, h, out)
		}

		/** A one-texel black/white checker over the middle third of the rows, where the art is opaque. */
		fun withChecker(src: RasterImage): RasterImage {
			val out = src.rgba.copyOf()
			for (y in src.height / 3 until src.height * 2 / 3) for (x in 0 until src.width) {
				val i = (y * src.width + x) * 4
				if ((out[i + 3].toInt() and 0xFF) < 128) continue
				val v: Byte = if ((x + y) % 2 == 0) 0 else -1
				out[i] = v; out[i + 1] = v; out[i + 2] = v
			}
			return RasterImage(src.width, src.height, out)
		}

		fun modelImages(manager: CTextureManager) = Cmo3Import.elementsOf(manager._modelImageGroups).filterIsInstance<CModelImageGroup>()
			.flatMap { Cmo3Import.elementsOf(it._modelImages).filterIsInstance<CModelImage>() }

		fun entries(manager: CTextureManager) = Cmo3Import.elementsOf(manager._textureAtlases).filterIsInstance<CTextureAtlas>()
			.flatMap { atlas -> Cmo3Import.elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>().map { it to atlas } }

		fun layeredImages(manager: CTextureManager) = Cmo3Import.elementsOf(manager._rawImages)
			.mapNotNull { (it as? LayeredImageWrapper)?.image as? CLayeredImage }

		fun layers(manager: CTextureManager) = layeredImages(manager)
			.flatMap { Cmo3Import.elementsOf((it.layerSet as LayerSet)._layerEntryList).filterIsInstance<CLayer>() }

		fun artMeshes(root: CModelSource) = Cmo3Import.elementsOf((root.drawableSourceSet as CDrawableSourceSet)._sources).filterIsInstance<CArtMeshSource>()

		fun placementOf(entry: ModelImageEntry, page: Int): AtlasPlacement {
			val t = entry.materialLocalToAtlasTransform as GTransform2
			val p = t.position as? GVector2; val s = t.scale as? GVector2
			return AtlasPlacement(page, p?.x ?: 0f, p?.y ?: 0f, s?.x ?: 1f, s?.y ?: 1f, t.eulerAngle)
		}

		fun forward(p: AtlasPlacement): FloatArray {
			val r = Math.toRadians(p.rotationDegrees.toDouble())
			val c = kotlin.math.cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
			return floatArrayOf(c * p.scaleX, -s * p.scaleY, p.positionX, s * p.scaleX, c * p.scaleY, p.positionY)
		}

		/** a ∘ b for 2x3 affines (m00, m01, m02, m10, m11, m12). */
		fun compose(a: FloatArray, b: FloatArray) = floatArrayOf(
			a[0] * b[0] + a[1] * b[3], a[0] * b[1] + a[1] * b[4], a[0] * b[2] + a[1] * b[5] + a[2],
			a[3] * b[0] + a[4] * b[3], a[3] * b[1] + a[4] * b[4], a[3] * b[2] + a[4] * b[5] + a[5],
		)

		fun apply(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

		fun CAffine.array() = floatArrayOf(m00, m01, m02, m10, m11, m12)

		fun FloatArray.toAffine() = CAffine().also { it.m00 = this[0]; it.m01 = this[1]; it.m02 = this[2]; it.m10 = this[3]; it.m11 = this[4]; it.m12 = this[5] }

		fun fmt(m: FloatArray) = m.joinToString(",", "[", "]") { "%.4g".format(it) }

		/**
		 * The graph edits a variant needs after conversion: each listed layer's model image gets its canvas
		 * scale, the entry half and every drawable's atlas-region transform are recomposed so the pair still
		 * reproduces it, and the layer rect / document size follow [docScale] or the canvas-size choice.
		 */
		fun fixGraph(root: CModelSource, docScale: Int?, layers: Map<String, LayerFix>) {
			if (layers.isEmpty()) return
			val manager = root.textureManager as CTextureManager
			val entryByGuid = entries(manager).associate { Cmo3Import.uuidOf(it.first.modelImageGuid) to it.first }
			val regionsByImage = HashMap<String, MutableList<CTextureInput_TextureAtlasRegion>>()
			for (mesh in artMeshes(root)) {
				val ext = Cmo3Import.elementsOf(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: continue
				val inputs = Cmo3Import.elementsOf(ext._textureInputs)
				val guid = inputs.filterIsInstance<CTextureInput_ModelImage>().firstOrNull()?.let { Cmo3Import.uuidOf(it._modelImageGuid) } ?: continue
				regionsByImage.getOrPut(guid) { ArrayList() } += inputs.filterIsInstance<CTextureInput_TextureAtlasRegion>()
			}
			for (image in modelImages(manager)) {
				val fix = layers[image.name] ?: continue
				val m = floatArrayOf(fix.scale, 0f, fix.left.toFloat(), 0f, fix.scale, fix.top.toFloat())
				image._materialLocalToCanvasTransform = m.toAffine()
				val guid = Cmo3Import.uuidOf(image.guid)
				val entry = entryByGuid[guid] ?: continue
				val half = compose(m, inversePlacementAffine(placementOf(entry, 0))!!)
				entry.atlasLocalToCanvasTransform = half.toAffine()
				regionsByImage[guid].orEmpty().forEach { it.inputImageLocalToCanvasTransform = half.toAffine() }
			}
			for (layer in layers(manager)) {
				val fix = layers[layer.name] ?: continue
				val rect = layer.boundsOnImageDoc as CRect
				if (docScale != null) { rect.x = fix.left * docScale; rect.y = fix.top * docScale }
				if (fix.boundsAtCanvasSize) { rect.width = fix.canvasWidth; rect.height = fix.canvasHeight }
			}
			if (docScale != null) layeredImages(manager).forEach { it.width *= docScale; it.height *= docScale }
		}
	}
}
