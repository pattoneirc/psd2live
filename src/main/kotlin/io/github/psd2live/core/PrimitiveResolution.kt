package io.github.psd2live.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.DrawableId

/**
 * How the base generation reads a document's version 2 `art_primitive` records ([ArtPrimitiveV2]): the resolved
 * layer set and the two kinds of layers it treats apart.
 *
 * - Parts: every primitive of a v2 record. Its layer is generated in the base on the record's pinned mesh (never
 *   re-meshed), with the keyforms, channels, masks and skin every layer gets, then parked in [PrimitiveSkins] for
 *   the record to place. A part a later v2 record supersedes is a part and a stub.
 * - Stubs (ghosts): layers v2 records supersede. Each is still built in its slot, as a passenger: per layer only,
 *   reading the resolved aggregates but never feeding them, so journal entries before the record can address it.
 *
 * The resolved set R - what every aggregate stage (anchors, face rig, frames, stance, deformers, eye whites,
 * masks as source, lips, skeleton binding) and the visible analysis read - is the generation layers without the
 * stubs, plus the parts that are not stubs themselves. [Inactive] for documents without v2 records: every caller
 * then takes its existing path unchanged.
 */
internal class PrimitiveResolution private constructor(
	val resolved: ResolvedLayers,
	/** Part layer → its primitive. */
	val partByLayer: Map<String, ResolvedPart>,
	/** Part drawable → the drawable its record's `replace` says it takes the place of. */
	val replaces: Map<DrawableId, DrawableId>,
	/** Layers of version 1 records: never generated, as before. */
	val legacyOwnedLayers: Set<String>,
	/** Parts of a split lip ribbon: a ribbon's motion comes from the lip generator of its mouth, which a part does not get. */
	val ribbonParts: Set<DrawableId> = emptySet(),
) {
	val active: Boolean get() = !resolved.isEmpty()
	val parts: List<ResolvedPart> get() = resolved.parts

	fun isStubLayer(id: String): Boolean = id in resolved.stubLayers
	fun isPartLayer(id: String): Boolean = id in partByLayer

	/** Whether layer [id] reaches the aggregate stages: not a stub. */
	fun feedsAggregates(id: String): Boolean = !isStubLayer(id)

	/** [layers] without the stubs: what the aggregate stages read. The same list when inactive. */
	fun aggregates(layers: List<ClassifiedLayer>): List<ClassifiedLayer> =
		if (!active) layers else layers.filter { feedsAggregates(it.source.id.raw) }

	/**
	 * The drawable of the base rig that stands for [id] while its records have not replayed: a part names the
	 * drawable it replaces, followed back through earlier records until one the base builds in its slot.
	 */
	fun baseStandIn(id: DrawableId, base: Set<DrawableId>): DrawableId {
		var current = id
		val seen = HashSet<DrawableId>()
		while (current !in base && seen.add(current)) current = replaces[current] ?: return current
		return current
	}

	/** The part [layer] classified from its layer override, or the record's classification when the layer has none. */
	fun classify(layer: ClassifiedLayer, config: PipelineConfig): ClassifiedLayer {
		val part = partByLayer[layer.source.id.raw] ?: return layer
		if (config.layerOverrides.containsKey(layer.source.id.raw)) return layer
		val recorded = part.classification
		return layer.copy(semantic = layer.semantic.copy(tag = recorded.tag, side = recorded.side, confidence = 1f, type = recorded.type,
			parameter = recorded.parameter, switchId = recorded.switchId))
	}

	companion object {
		val Inactive = PrimitiveResolution(ResolvedLayers.Empty, emptyMap(), emptyMap(), emptySet())

		private var last: Pair<List<JsonObject>, PrimitiveResolution>? = null

		/** The resolution of [overlay]'s journal; the last one is kept, keyed by the journal's identity. */
		fun of(overlay: RigEditOverlay): PrimitiveResolution {
			val journal = overlay.authoringJournal
			synchronized(this) { last?.let { (key, value) -> if (key === journal) return value } }
			val value = compute(journal)
			synchronized(this) { last = journal to value }
			return value
		}

		private fun compute(journal: List<JsonObject>): PrimitiveResolution {
			if (journal.none(ArtPrimitiveV2::isV2)) return Inactive
			val overlay = RigEditOverlay.Empty.copy(authoringJournal = journal)
			val resolved = ArtPrimitiveV2.resolve(overlay)
			if (resolved.isEmpty()) return Inactive
			val replaces = LinkedHashMap<DrawableId, DrawableId>()
			val legacy = LinkedHashSet<String>()
			val ribbons = LinkedHashSet<DrawableId>()
			for (command in journal) {
				if (!ArtPrimitiveJournal.isRecord(command)) continue
				if (!ArtPrimitiveV2.isV2(command)) {
					ArtPrimitiveJournal.primitives(command).forEach { legacy += it.getValue(ArtPrimitiveV2.LAYER_ID).jsonPrimitive.content }
					continue
				}
				if (command[ArtPrimitiveV2.SUPERSEDES_LAYERS]?.jsonArray.orEmpty().any { MouthLipLayer.isLipId(it.jsonPrimitive.content) })
					ArtPrimitiveJournal.primitives(command).forEach { ribbons += DrawableId(it.getValue(ArtPrimitiveV2.ID).jsonPrimitive.content) }
				command[ArtPrimitiveV2.REPLACE]?.jsonObject?.forEach { (superseded, parts) ->
					parts.jsonArray.forEach { replaces[DrawableId(it.jsonPrimitive.content)] = DrawableId(superseded) }
				}
			}
			val byLayer = LinkedHashMap<String, ResolvedPart>()
			for (part in resolved.parts) byLayer[part.layerId] = part
			return PrimitiveResolution(resolved, byLayer, replaces, legacy, ribbons)
		}
	}
}
