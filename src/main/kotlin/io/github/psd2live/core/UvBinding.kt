package io.github.psd2live.core

import org.umamo.format.art.LayerBounds
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.atlasPixelOf
import org.umamo.runtime.model.layerPixelOf
import org.umamo.runtime.model.AtlasPlacement as TilePlacement

/**
 * One layer's texture: where its pixels sit on the canvas ([space]) and where they sit on an atlas
 * page ([placement]), and the conversions between every coordinate a mesh is addressed in:
 *
 * - canvas: canvas units;
 * - layer offset: canvas units from the layer rectangle's top-left corner - what an unbound rig stores
 *   as its texture coordinates ([UvBinding]);
 * - raster: pixels of the layer raster ([LayerSpace]);
 * - stored uv: what a bound drawable stores - `0..1` over the page the [placement] puts the raster on,
 *   or `0..1` over the raster itself when there is no placement.
 *
 * This is the single place the atlas convention lives: a paint commit's slice remap, a repack of the
 * generation rig onto the current textures, the CMO3 mesh migration and the binding stage all read it.
 * An axis-aligned placement uses `uv = (x + raster * scale) / page` and its exact inverse; a rotated one
 * goes through Umamo's placement affine.
 */
internal class LayerTexture(
	val space: LayerSpace,
	/** Where the raster sits on its page, or null when stored uvs address the raster itself. */
	val placement: TilePlacement?,
	val pageWidth: Int,
	val pageHeight: Int,
) {
	private val axisAligned = placement == null || placement.rotationDegrees == 0f

	/** The page this texture's placement names, 0 when it has none. */
	val page: Int get() = placement?.pageIndex ?: 0

	fun uvOfRaster(x: Float, y: Float): FloatArray {
		val placed = placement ?: return floatArrayOf(x / space.rasterWidth, y / space.rasterHeight)
		if (axisAligned) {
			return floatArrayOf((placed.positionX + x * placed.scaleX) / pageWidth, (placed.positionY + y * placed.scaleY) / pageHeight)
		}
		val pixel = atlasPixelOf(placed, x, y)
		return floatArrayOf(pixel[0] / pageWidth, pixel[1] / pageHeight)
	}

	fun rasterOfUv(u: Float, v: Float): FloatArray {
		val placed = placement ?: return floatArrayOf(u * space.rasterWidth, v * space.rasterHeight)
		if (axisAligned) {
			return floatArrayOf((u * pageWidth - placed.positionX) / placed.scaleX, (v * pageHeight - placed.positionY) / placed.scaleY)
		}
		return requireNotNull(layerPixelOf(placed, u * pageWidth, v * pageHeight)) { "Degenerate atlas placement" }
	}

	fun uvOfCanvas(x: Float, y: Float): FloatArray = uvOfRaster(space.canvasToRasterX(x), space.canvasToRasterY(y))

	fun canvasOfUv(u: Float, v: Float): FloatArray {
		val raster = rasterOfUv(u, v)
		return floatArrayOf(space.rasterToCanvasX(raster[0]), space.rasterToCanvasY(raster[1]))
	}

	/** The stored uv of a layer offset - the binding of one unbound texture coordinate. */
	fun uvOfOffset(x: Float, y: Float): FloatArray = uvOfRaster(x * space.scaleX, y * space.scaleY)

	/** Stored uvs to canvas units, pairwise. */
	fun toCanvas(uvs: FloatArray): FloatArray = FloatArray(uvs.size).also { out ->
		for (index in 0 until uvs.size - 1 step 2) {
			val canvas = canvasOfUv(uvs[index], uvs[index + 1])
			out[index] = canvas[0]; out[index + 1] = canvas[1]
		}
	}

	/** Canvas units to stored uvs, pairwise. */
	fun toUvs(canvas: FloatArray): FloatArray = FloatArray(canvas.size).also { out ->
		for (index in 0 until canvas.size - 1 step 2) {
			val uv = uvOfCanvas(canvas[index], canvas[index + 1])
			out[index] = uv[0]; out[index + 1] = uv[1]
		}
	}

	/** Layer offsets (an unbound mesh's texture coordinates) to stored uvs, pairwise. */
	fun bind(offsets: FloatArray): FloatArray = FloatArray(offsets.size).also { out ->
		for (index in 0 until offsets.size - 1 step 2) {
			val uv = uvOfOffset(offsets[index], offsets[index + 1])
			out[index] = uv[0]; out[index + 1] = uv[1]
		}
	}

	/** Re-addresses stored uvs of this texture to [to] through canvas units: `uv -> canvas -> uv`. */
	fun remap(uvs: FloatArray, to: LayerTexture): FloatArray = to.toUvs(toCanvas(uvs))

	companion object {
		/**
		 * The space the rig generator assumes for a layer: raster pixels are canvas units, anchored at the
		 * integer bounds' top-left corner. Equal to [LayerSpace.of] whenever the raster covers its bounds
		 * one to one, which every generated layer does today.
		 */
		fun generatorSpace(left: Int, top: Int, rasterWidth: Int, rasterHeight: Int): LayerSpace = LayerSpace(
			left.toFloat(), top.toFloat(), rasterWidth.toFloat(), rasterHeight.toFloat(), rasterWidth, rasterHeight,
		)

		fun generatorSpace(layer: ClassifiedLayer): LayerSpace =
			generatorSpace(layer.source.bounds.left, layer.source.bounds.top, layer.source.raster.width, layer.source.raster.height)

		/** Umamo's form of a packed placement: axis aligned, positioned at whole page pixels. */
		fun tilePlacement(placement: AtlasPlacement): TilePlacement = TilePlacement(
			pageIndex = placement.page,
			positionX = placement.x.toFloat(),
			positionY = placement.y.toFloat(),
			scaleX = placement.scaleX,
			scaleY = placement.scaleY,
			rotationDegrees = 0f,
		)

		/** [layer]'s texture on [atlas] under the generator's layer space. */
		fun packed(layer: ClassifiedLayer, placement: AtlasPlacement, atlas: PackedAtlas): LayerTexture =
			packed(generatorSpace(layer), placement, atlas.pages[placement.page].image.width, atlas.pages[placement.page].image.height)

		fun packed(space: LayerSpace, placement: AtlasPlacement, pageWidth: Int, pageHeight: Int): LayerTexture =
			LayerTexture(space, tilePlacement(placement), pageWidth, pageHeight)

		/**
		 * A packed slice known only by the layer's integer [bounds], whose raster covers them one to one.
		 * Unknown bounds read as the canvas origin, which leaves the texture coordinates translated but
		 * unscaled.
		 */
		fun packed(bounds: LayerBounds?, placement: AtlasPlacement, pageWidth: Int, pageHeight: Int): LayerTexture =
			packed(bounds?.let { generatorSpace(it.left, it.top, it.width, it.height) } ?: LayerSpace(0f, 0f, 0f, 0f, 0, 0),
				placement, pageWidth, pageHeight)

		/**
		 * [drawable]'s texture as [model] records it: the source layer its tile came from for the canvas
		 * rectangle, the tile for the raster, and the tile's placement when stored uvs address pages.
		 * Handles rotated and scaled tiles of an imported model.
		 */
		fun of(model: PuppetModel, drawable: Drawable): Resolved {
			val tile = model.atlas.tiles.single { it.id == drawable.atlasTileId }
			val reference = requireNotNull(tile.source) { "Mesh migration requires source artwork" }
			val layer = model.sources.single { it.id == reference.sourceId }.layers.single { it.key == reference.layerKey }
			require(tile.width > 0 && tile.height > 0 && layer.width > 0 && layer.height > 0) { "Invalid mesh migration artwork dimensions" }
			val placement = tile.placement?.takeIf { model.atlas.storedUvsAddressPages }
			val page = placement?.let { model.atlas.pages[it.pageIndex] }
			val space = LayerSpace(layer.left.toFloat(), layer.top.toFloat(), layer.width.toFloat(), layer.height.toFloat(), tile.width, tile.height)
			return Resolved(LayerTexture(space, placement, page?.width ?: 0, page?.height ?: 0), reference.sourceId, layer)
		}
	}

	/** A model drawable's texture together with the source layer that names it. */
	internal class Resolved(val texture: LayerTexture, val sourceId: org.umamo.runtime.model.ArtSourceId, val layer: org.umamo.runtime.model.ArtSourceLayer)
}

/**
 * The binding stage: turns a rig built in layer offsets into one whose texture coordinates address the
 * packed atlas pages.
 *
 * The rig generator never sees the atlas. Every generated mesh stores, as its texture coordinates, the
 * vertex's offset from its layer's top-left corner in canvas units, and the unbound model carries tiles
 * without placements and no pages. So the unbound model - and any content hash of it - depends on the
 * art and the generation settings only; packing the same layers onto a different page layout changes
 * nothing but what [bind] writes.
 *
 * Offsets rather than `0..1` layer units because the binding has to reproduce the packed uvs bit for bit:
 * `(x + offset * scale) / page` is exactly what a direct build computed, while a `0..1` value stored as a
 * float has already lost the bits needed to get the offset back. The two are the same up to the layer
 * size, so nothing else depends on the choice.
 *
 * The unbound model is not renderable - its uvs are neither page nor tile uvs - and only ever feeds this
 * stage and content hashes.
 */
internal object UvBinding {
	/** A bound model and the page each bound drawable samples. */
	data class Bound(val puppet: PuppetModel, val pageByDrawableId: Map<String, Int>)

	/**
	 * The atlas an unbound model carries: one unplaced tile per textured layer, in [layerIds] order, with
	 * the source inventory to match. Nothing about the packing appears, not even the page count.
	 */
	fun unboundAtlas(analysis: PipelineAnalysis, layerIds: List<String>): Pair<PuppetAtlas, List<ArtSource>> {
		val (atlas, sources) = PuppetSourceAtlas.build(analysis, layerIds.associateWith { null })
		return atlas.copy(pages = emptyList(), storedUvsAddressPages = false) to sources
	}

	/**
	 * Binds [unbound] to [atlas]: every drawable whose layer has a placement gets its offsets converted to
	 * page uvs and the page it samples, and the model gets the packed tiles and sources of [analysis].
	 * Drawables without a layer, or whose layer has no placement, keep their stored values.
	 *
	 * @param layerOf The layer whose rectangle a drawable's offsets were measured from. By default the
	 *                [analysis] layer its tile names; the builder passes the very layer it generated from.
	 */
	fun bind(
		unbound: PuppetModel,
		analysis: PipelineAnalysis,
		atlas: PackedAtlas,
		layerOf: (Drawable) -> ClassifiedLayer? = defaultLayers(unbound, analysis),
	): Bound {
		val pages = LinkedHashMap<String, Int>()
		val drawables = unbound.drawables.map { drawable ->
			val layer = layerOf(drawable) ?: return@map drawable
			val placement = atlas.placementByLayerId[layer.source.id.raw] ?: return@map drawable
			val texture = LayerTexture.packed(layer, placement, atlas)
			pages[drawable.id.raw] = placement.page
			val mesh = drawable.mesh ?: return@map drawable.copy(texturePage = placement.page)
			drawable.copy(mesh = DrawableMesh(mesh.positions, texture.bind(mesh.uvs), mesh.indices), texturePage = placement.page)
		}
		val (puppetAtlas, sources) = PuppetSourceAtlas.build(analysis, atlas)
		return Bound(unbound.copy(drawables = drawables, atlas = puppetAtlas, sources = sources), pages)
	}

	private fun defaultLayers(unbound: PuppetModel, analysis: PipelineAnalysis): (Drawable) -> ClassifiedLayer? {
		val layers = analysis.layers.associateBy { it.source.id.raw }
		val tiles = unbound.atlas.tileById
		return { drawable -> drawable.atlasTileId?.let { tiles[it]?.source?.layerKey }?.let(layers::get) }
	}

	/** Binds one unbound mesh of [layer] to its [placement]. */
	fun bindMesh(mesh: DrawableMesh, layer: ClassifiedLayer, placement: AtlasPlacement, pageWidth: Int, pageHeight: Int): DrawableMesh =
		DrawableMesh(mesh.positions, LayerTexture.packed(LayerTexture.generatorSpace(layer), placement, pageWidth, pageHeight).bind(mesh.uvs), mesh.indices)

	/**
	 * The part of a bound model the binding decides for [drawables] (all when null): each one's page and
	 * its tile's placement and page size. Together with a hash of the unbound model it identifies the
	 * bound one, for a cache whose output carries bound uvs.
	 */
	fun bindingKey(bound: PuppetModel, drawables: Collection<DrawableId>? = null): List<String> {
		val wanted = drawables?.toSet()
		return bound.drawables.filter { wanted == null || it.id in wanted }.map { drawable ->
			val tile = drawable.atlasTileId?.let { bound.atlas.tileById[it] }
			val page = tile?.placement?.let { bound.atlas.pages.getOrNull(it.pageIndex) }
			"${drawable.id.raw}:${drawable.texturePage}:${tile?.id?.raw}:${tile?.placement}:${page?.width}x${page?.height}"
		}
	}
}
