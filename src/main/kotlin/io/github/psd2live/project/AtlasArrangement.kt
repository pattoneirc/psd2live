package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.util.BitSet

/**
 * Where a layer's meshes lie on its texture raster: square cells of [cell] raster pixels, [columns] x [rows]
 * of them covering the raster, row by row in [bits]. A tile with a footprint owns only those cells of its
 * rectangle on the page, so tiles whose rectangles overlap can share a page as long as their meshes do not.
 */
class TextureFootprint(val cell: Int, val columns: Int, val rows: Int, val bits: BitSet) {
	init {
		require(cell > 0 && columns > 0 && rows > 0) { "Texture footprint must have cells" }
		require(bits.length() <= columns * rows) { "Texture footprint bits exceed its cells" }
	}

	operator fun get(column: Int, row: Int): Boolean = bits[row * columns + column]

	/** How many cells the meshes cover. */
	val area: Int get() = bits.cardinality()

	override fun equals(other: Any?): Boolean = other is TextureFootprint && cell == other.cell && columns == other.columns &&
		rows == other.rows && bits == other.bits
	override fun hashCode(): Int = ((cell * 31 + columns) * 31 + rows) * 31 + bits.hashCode()
}

/** One tile's stored spot: texture pixel ([x], [y]) on [page], and the cells of it its meshes use. */
data class ArrangedTile(val page: Int, val x: Int, val y: Int, val footprint: TextureFootprint? = null) {
	init { require(page >= 0 && x >= 0 && y >= 0) { "Arranged tile must not be negative" } }
}

/**
 * A stored atlas layout. Without one the atlas is packed afresh on every build (automatic arrangement); with
 * one every listed tile keeps its spot and the common fit stays [fitStep] / [FIT_STEPS], so a paint, a density
 * change or a new layer never moves the other tiles. Tiles it does not list - new layers, or ones that no
 * longer fit where they were - are placed into free space with a notice. It changes only when the user
 * arranges the atlas, moves a tile or turns the automatic arrangement back on.
 */
data class AtlasArrangement(val fitStep: Int, val tiles: Map<String, ArrangedTile>) {
	init { require(fitStep in 1..FIT_STEPS) { "Atlas arrangement fit must lie in 1..$FIT_STEPS" } }

	val fit: Double get() = fitStep.toDouble() / FIT_STEPS

	companion object {
		const val FIT_STEPS = 4096
	}
}

/** JSON form of [AtlasArrangement] under the document setting [KEY]; tiles sorted by layer ID. */
object AtlasArrangementCodec {
	const val KEY = "atlasArrangement"

	fun encode(value: AtlasArrangement): JsonObject = buildJsonObject {
		put("fitStep", value.fitStep)
		putJsonObject("tiles") {
			for ((id, tile) in value.tiles.toSortedMap()) putJsonObject(id) {
				put("page", tile.page); put("x", tile.x); put("y", tile.y)
				tile.footprint?.let { footprint -> putJsonObject("footprint") {
					put("cell", footprint.cell); put("columns", footprint.columns); put("rows", footprint.rows)
					put("bits", java.util.Base64.getEncoder().encodeToString(footprint.bits.toByteArray()))
				} }
			}
		}
	}

	fun decode(settings: JsonObject): AtlasArrangement? {
		val value = settings[KEY] as? JsonObject ?: return null
		fun JsonObject.int(name: String) = this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Invalid atlas arrangement $name")
		val tiles = (value["tiles"] as? JsonObject ?: throw IllegalArgumentException("Invalid atlas arrangement tiles")).mapValues { (_, entry) ->
			val tile = entry.jsonObject
			val footprint = (tile["footprint"] as? JsonObject)?.let {
				TextureFootprint(it.int("cell"), it.int("columns"), it.int("rows"),
					BitSet.valueOf(java.util.Base64.getDecoder().decode(it["bits"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Invalid footprint bits"))))
			}
			ArrangedTile(tile.int("page"), tile.int("x"), tile.int("y"), footprint)
		}
		return AtlasArrangement(value.int("fitStep"), tiles)
	}

	/** [settings] with [value] stored, or without any arrangement when null. */
	fun with(settings: JsonObject, value: AtlasArrangement?): JsonObject =
		JsonObject(if (value == null) settings - KEY else settings + (KEY to encode(value)))
}
