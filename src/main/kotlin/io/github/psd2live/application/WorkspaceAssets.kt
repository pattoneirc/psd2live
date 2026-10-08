package io.github.psd2live.application

import io.github.psd2live.core.StableIds
import io.github.psd2live.project.WorkspaceAddLayerRequest
import io.github.psd2live.project.WorkspaceCanvasPlacement
import io.github.psd2live.project.WorkspaceImportedPngAsset
import io.github.psd2live.project.WorkspaceLayerInsertion
import io.github.psd2live.project.WorkspacePngAsset
import io.github.psd2live.project.WorkspacePngImportRequest
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceViewSpatialMetadata
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.placementForGeneratedPng

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.LayerSizeBudget
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import java.awt.image.BufferedImage
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

internal class WorkspacePngAssetStore(private val checkCancelled: () -> Unit = {}) {
	private val assets = ConcurrentHashMap<String, WorkspacePngAsset>()

	fun import(request: WorkspacePngImportRequest, spatial: WorkspaceViewSpatialMetadata): WorkspaceImportedPngAsset {
		require(request.png.size >= PNG_SIGNATURE.size && request.png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
			"asset import accepts PNG data only"
		}
		checkCancelled()
        val decoded = javax.imageio.stream.MemoryCacheImageInputStream(request.png.inputStream()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "The supplied bytes are not a decodable PNG" }
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216) { "PNG exceeds 16 megapixels" }
                checkCancelled()
                reader.read(0)
            } finally { reader.dispose() }
        }
        checkCancelled()
        val matte = if (request.referenceId != null && request.solidBackground != null) processGeneratedMatte(decoded,
            request.solidBackground, request.backgroundTolerance, request.processing, checkCancelled) else null
        val image = matte?.image ?: request.solidBackground?.let { cleanGeneratedMatte(decoded, it, request.backgroundTolerance, checkCancelled) } ?: decoded
        if (request.requireTransparency) {
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            require(pixels.any { it ushr 24 == 0 } && pixels.any { it ushr 24 > 0 }) {
                "Asset needs transparent background and visible pixels. Supply native alpha or an explicit solid_background for matte removal; a checkerboard is not transparency."
            }
        }
        require(image.width > 0 && image.height > 0) { "PNG dimensions must be positive" }
		val placement = if (request.referenceId != null) WorkspaceCanvasPlacement(spatial.coordinateSpace, spatial.viewRect, image.width, image.height,
            spatial.viewRect.width / image.width, spatial.viewRect.height / image.height, request.spatialReferenceId) else spatial.placementForGeneratedPng(
			sourceViewId = request.spatialReferenceId,
			imagePixelWidth = image.width,
			imagePixelHeight = image.height,
			sourcePixelRect = request.sourcePixelRect,
		)
		val rgba = image.toRgba(checkCancelled)
		val digest = sha256(if (request.solidBackground == null) request.png else java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
		val placementKey = listOf(
			placement.canvasRect.left,
			placement.canvasRect.top,
			placement.canvasRect.right,
			placement.canvasRect.bottom,
		).joinToString(":")
		val details = if (request.referenceId == null) kotlinx.serialization.json.JsonObject(emptyMap()) else kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(2)); put("reference_id", kotlinx.serialization.json.JsonPrimitive(request.referenceId))
            put("solid_background", kotlinx.serialization.json.JsonPrimitive(request.solidBackground)); put("background_tolerance", kotlinx.serialization.json.JsonPrimitive(request.backgroundTolerance))
            put("registration_required", kotlinx.serialization.json.JsonPrimitive(true)); put("processing", request.processing)
            put("diagnostics", matte?.diagnostics ?: kotlinx.serialization.json.buildJsonObject { put("mode", kotlinx.serialization.json.JsonPrimitive("native_alpha")) }); put("raw_sha256", kotlinx.serialization.json.JsonPrimitive(sha256(request.png)))
            var left=image.width; var top=image.height; var right=0; var bottom=0
            for (i in 0 until image.width*image.height) {
                if (i % 4096 == 0) checkCancelled()
                if ((rgba[i*4+3].toInt() and 255)>0) {
                left=minOf(left,i%image.width);top=minOf(top,i/image.width)
                right=maxOf(right,i%image.width+1);bottom=maxOf(bottom,i/image.width+1)
                }
            }
            if (right>left && bottom>top) put("content_pixel_rect", Bounds(left.toFloat(),top.toFloat(),right.toFloat(),bottom.toFloat()).json())
        }
        val id = "asset-${sha256("$digest|$placementKey|${placement.sourceViewId}|$details".encodeToByteArray()).take(24)}"
		val imported = WorkspaceImportedPngAsset(id, digest, image.width, image.height, placement, details)
		checkCancelled()
		assets.putIfAbsent(id, WorkspacePngAsset(imported, rgba, request.png.copyOf()))
		return assets.getValue(id).public
	}

	fun require(assetId: String): WorkspacePngAsset =
		assets[assetId] ?: throw IllegalArgumentException("PNG asset not found: $assetId")

	fun find(assetId: String): WorkspacePngAsset? = assets[assetId]
    fun clear() { assets.clear() }

	fun remember(asset: WorkspacePngAsset): WorkspacePngAsset = asset.also { assets.putIfAbsent(it.public.id, it) }

	private companion object {
		val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
	}
}

internal fun WorkspaceDocument.addLayer(
	asset: WorkspacePngAsset,
	request: WorkspaceAddLayerRequest,
    checkCancelled: () -> Unit = {},
): Pair<WorkspaceDocument, String> {
	// An unnamed layer is named from what it is made of, so the same request adds the same layer ID.
	val rawId = request.layerId?.trim().orEmpty().ifEmpty {
		StableIds.fresh(StableIds.stem("agent:", request.copy(layerId = null, expectedState = "", taskId = null))) { id -> source.layers.any { it.id.raw == id } }
	}
	require(rawId.none(Char::isISOControl)) { "Layer ID contains control characters" }
	require(source.layers.none { it.id.raw == rawId }) { "Layer ID already exists: $rawId" }
	val name = request.name.trim()
	require(name.isNotEmpty()) { "Layer name must not be blank" }
	require(request.opacity.isFinite() && request.opacity in 0f..1f) { "Layer opacity must be within 0..1" }
	val tag = enumValue<SemanticTag>(request.semanticTag, "semantic_tag")
	val side = enumValue<Side>(request.side, "side")
	val normalized = normalizeAssetRaster(asset, request.trimTransparent, checkCancelled)
	val added = WorkspaceSourceLayer(
		id = LayerId(rawId),
		name = name,
		groupPath = request.groupPath.trim('/'),
		kind = SourceLayerKind.Raster,
		visible = request.visible,
		order = 0,
		bounds = normalized.bounds,
		opacity = request.opacity,
		clipped = false,
		blend = LayerBlend.Normal,
		channelMask = ChannelMask.ALL,
		raster = normalized.raster,
		sourceAssetId = asset.public.id,
		sourceSpatialReferenceId = asset.public.placement.sourceViewId,
		derived = true,
		rect = normalized.rect,
	)
	val painterOrder = source.layers.toMutableList()
	val insertionIndex = when (val insertion = request.insertion) {
		WorkspaceLayerInsertion.Top -> painterOrder.size
		WorkspaceLayerInsertion.Bottom -> 0
		is WorkspaceLayerInsertion.Above -> painterOrder.anchorIndex(insertion.layerId) + 1
		is WorkspaceLayerInsertion.Below -> painterOrder.anchorIndex(insertion.layerId)
	}
	painterOrder.add(insertionIndex, added)
	val ordered = painterOrder.mapIndexed { index, layer ->
		WorkspaceSourceLayer.copyOf(layer, order = painterOrder.lastIndex - index)
	}
	val nextSource = WorkspaceSourceArt(source.widthPx, source.heightPx, ordered, source.groups.toList())
	val override = LayerClassificationOverride(
		type = LayerType.PRESET,
		tag = tag,
		side = side,
	)
	val nextParents = if (request.parentDeformerId == null) parentOverrides else parentOverrides + (rawId to request.parentDeformerId)
	return copy(
		source = nextSource,
		layerVisibility = layerVisibility + (rawId to request.visible),
		deletedLayerIds = deletedLayerIds - rawId,
		layerOverrides = layerOverrides + (rawId to override),
		parentOverrides = nextParents,
	) to rawId
}

private fun MutableList<SourceLayer>.anchorIndex(layerId: String): Int {
	indexOfFirst { it.id.raw == layerId }.takeIf { it >= 0 }?.let { return it }
	val baseId = layerId.removeSuffix(":l").removeSuffix(":r")
	return indexOfFirst { it.id.raw == baseId }.takeIf { it >= 0 }
		?: throw IllegalArgumentException("Insertion anchor layer not found: $layerId")
}

internal fun WorkspaceDocument.replacePlacedLayer(layerId: String, asset: WorkspacePngAsset, checkCancelled: () -> Unit = {}): WorkspaceDocument {
    val normalized = normalizeAssetRaster(asset, true, checkCancelled)
    val next = source.layers.map { layer ->
        if (layer.id.raw != layerId) layer else (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
            bounds = normalized.bounds, raster = normalized.raster, rect = normalized.rect)
    }
    return copy(source = WorkspaceSourceArt(source.widthPx, source.heightPx, next, source.groups))
}

private data class NormalizedRaster(val bounds: LayerBounds, val raster: LayerRaster, val rect: LayerCanvasRect?)

/**
 * The asset's processed pixels at their own resolution, on the canvas rectangle its placement names.
 *
 * Nothing is resampled: the layer raster is the asset's pixels (cropped to their visible area when
 * [trimTransparent]), and the placement's canvas units per pixel become the layer's canvas rectangle, so a
 * 1024-pixel asset placed over 32 canvas units is a 32-unit layer holding 1024 pixels ([LayerSpace]).
 */
private fun normalizeAssetRaster(asset: WorkspacePngAsset, trimTransparent: Boolean, checkCancelled: () -> Unit): NormalizedRaster {
    checkCancelled()
	val rect = asset.public.placement.canvasRect
    require(listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() }) { "Asset placement is outside the canvas coordinate range" }
	val width = asset.public.pixelWidth; val height = asset.public.pixelHeight
	require(width > 0 && height > 0 && asset.rgba.size == width * height * 4) { "Invalid RGBA buffer length" }
	val crop = (if (trimTransparent) alphaBounds(width, height, asset.rgba, checkCancelled) else intArrayOf(0, 0, width, height))
		?: throw IllegalArgumentException("Generated PNG is fully transparent")
	val (cropLeft, cropTop, cropRight, cropBottom) = crop.toList()
	val unitsX = rect.width / width; val unitsY = rect.height / height
	val canvas = LayerCanvasRect(rect.left + cropLeft * unitsX, rect.top + cropTop * unitsY,
		(cropRight - cropLeft) * unitsX, (cropBottom - cropTop) * unitsY)
	LayerSizeBudget.require(cropRight - cropLeft, cropBottom - cropTop, canvas)
	val (bounds, stored) = LayerSizeBudget.enclosing(canvas)
	val rgba = if (cropLeft == 0 && cropTop == 0 && cropRight == width && cropBottom == height) asset.rgba.copyOf() else {
		val rowBytes = (cropRight - cropLeft) * 4
		ByteArray(rowBytes * (cropBottom - cropTop)).also { out ->
			for (y in cropTop until cropBottom) {
				if ((y - cropTop) % 256 == 0) checkCancelled()
				System.arraycopy(asset.rgba, (y * width + cropLeft) * 4, out, (y - cropTop) * rowBytes, rowBytes)
			}
		}
	}
	return NormalizedRaster(bounds, LayerRaster(cropRight - cropLeft, cropBottom - cropTop, rgba), stored)
}

/** The edges (left, top, right, bottom) of the pixels with non-zero alpha, or null when there are none. */
private fun alphaBounds(width: Int, height: Int, rgba: ByteArray, checkCancelled: () -> Unit): IntArray? {
	var minX = width
	var minY = height
	var maxX = -1
	var maxY = -1
	for (y in 0 until height) {
        checkCancelled()
		for (x in 0 until width) {
			if (rgba[(y * width + x) * 4 + 3].toInt() == 0) continue
			minX = minOf(minX, x)
			minY = minOf(minY, y)
			maxX = maxOf(maxX, x)
			maxY = maxOf(maxY, y)
		}
	}
	return if (maxX < minX || maxY < minY) null else intArrayOf(minX, minY, maxX + 1, maxY + 1)
}

private fun BufferedImage.toRgba(checkCancelled: () -> Unit = {}): ByteArray {
	val rgba = ByteArray(width * height * 4)
	var offset = 0
	for (y in 0 until height) for (x in 0 until width) {
        if (x == 0) checkCancelled()
		val argb = getRGB(x, y)
		rgba[offset++] = (argb ushr 16).toByte()
		rgba[offset++] = (argb ushr 8).toByte()
		rgba[offset++] = argb.toByte()
		rgba[offset++] = (argb ushr 24).toByte()
	}
	return rgba
}

private inline fun <reified T : Enum<T>> enumValue(raw: String, field: String): T =
	runCatching { enumValueOf<T>(raw.trim().uppercase()) }
		.getOrElse { throw IllegalArgumentException("Unknown $field: $raw") }

internal fun decodePngBase64(raw: String): ByteArray {
	val payload = raw.substringAfter("base64,", raw).filterNot(Char::isWhitespace)
	return runCatching { Base64.getDecoder().decode(payload) }
		.getOrElse { throw IllegalArgumentException("png_base64 is not valid Base64") }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString("") { "%02x".format(it.toInt() and 0xff) }
