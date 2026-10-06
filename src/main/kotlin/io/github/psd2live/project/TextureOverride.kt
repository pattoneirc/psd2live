package io.github.psd2live.project

import kotlinx.serialization.json.*

/**
 * A fixed atlas position for one layer's tile: texture pixel ([x], [y]) on page [page].
 */
data class TexturePin(
	val page: Int,
	val x: Int,
	val y: Int,
) {
	init {
		require(page >= 0 && x >= 0 && y >= 0) { "Texture pin must not be negative" }
	}
}

/**
 * One layer's texture settings, overriding the automatic atlas budget.
 *
 * The atlas holds `raster pixels x density x fit` for each layer, where fit (at most 1) is solved
 * once for all unlocked layers so the atlas stays within its budget.
 *
 * Stored in [WorkspaceDocument.textureOverrides]; the packer does not read it yet.
 *
 * @property density Texture pixels per raster pixel; null keeps the default of 1.
 * @property lock Whether the layer keeps its density when the atlas has to shrink to fit.
 * @property pin A fixed atlas position; null lets the packer place the tile.
 */
data class TextureOverride(
	val density: Float? = null,
	val lock: Boolean = false,
	val pin: TexturePin? = null,
) {
	init {
		require(density == null || (density.isFinite() && density > 0f)) { "Texture density must be positive" }
	}

	/** Whether this override changes nothing, so it need not be stored. */
	val isDefault: Boolean get() = density == null && !lock && pin == null
}

/** JSON form of [TextureOverride] in the document's `textureOverrides`; only non-default fields are written. */
internal object TextureOverrideCodec {
	fun encode(value: TextureOverride): JsonObject = buildJsonObject {
		value.density?.let { put("density", it) }
		if (value.lock) put("lock", true)
		value.pin?.let { pin -> putJsonObject("pin") { put("page", pin.page); put("x", pin.x); put("y", pin.y) } }
	}

	fun decode(value: JsonObject): TextureOverride = TextureOverride(
		density = value["density"]?.let { it.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("Invalid texture density") },
		lock = value["lock"]?.let { it.jsonPrimitive.booleanOrNull ?: throw IllegalArgumentException("Invalid texture lock") } ?: false,
		pin = value["pin"]?.jsonObject?.let { pin ->
			fun int(name: String) = pin[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Invalid texture pin $name")
			TexturePin(int("page"), int("x"), int("y"))
		},
	)

	/** The non-default overrides sorted by layer ID, or null when there is none to store. */
	fun encodeAll(values: Map<String, TextureOverride>): JsonObject? = values.filterValues { !it.isDefault }
		.takeIf { it.isNotEmpty() }?.toSortedMap()?.let { sorted -> JsonObject(sorted.mapValues { encode(it.value) }) }

	fun decodeAll(value: JsonObject?): Map<String, TextureOverride> =
		value?.mapValues { decode(it.value.jsonObject) }?.filterValues { !it.isDefault }.orEmpty()

	/** Canonical text for revision identity. */
	fun canonical(value: TextureOverride): String = buildString {
		append(value.density ?: "-").append(',').append(value.lock).append(',')
		value.pin?.let { append(it.page).append('/').append(it.x).append('/').append(it.y) } ?: append('-')
	}
}
