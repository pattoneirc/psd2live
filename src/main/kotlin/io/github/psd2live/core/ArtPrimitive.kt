package io.github.psd2live.core

import kotlinx.serialization.json.JsonObject

/** Why a document-owned art primitive exists. */
enum class ArtPrimitiveOrigin {
	/** One part of a layer split into several primitives. */
	SPLIT,

	/** A primitive created from nothing, such as the first stroke on a transparent layer. */
	CREATED,

	/** A primitive that replaces the generated one of the same layer. */
	REPLACED,
}

/**
 * A primitive's mesh. [positions] are in the parent deformer's space, [triangles] index vertices,
 * and [luv] holds per-vertex layer units: `0..1` across the layer rectangle (see [LayerSpace]), so
 * the mesh does not depend on where the atlas places the layer's pixels.
 */
class ArtPrimitiveMesh(
	val positions: FloatArray,
	val triangles: IntArray,
	val luv: FloatArray,
) {
	val vertexCount: Int get() = positions.size / 2

	override fun equals(other: Any?): Boolean = other is ArtPrimitiveMesh &&
		positions.contentEquals(other.positions) && triangles.contentEquals(other.triangles) && luv.contentEquals(other.luv)

	override fun hashCode(): Int =
		(positions.contentHashCode() * 31 + triangles.contentHashCode()) * 31 + luv.contentHashCode()
}

/**
 * A document-owned piece of art: the full authored state of one drawable that the automatic rig
 * does not regenerate, such as the parts of a split layer or a newly created mesh. Ordinary layers
 * stay generated from the source art; only primitives the document owns are recorded this way.
 *
 * Placeholder for the planned `art_primitive` journal record, which generalises the
 * `canvas_mesh_create` record. Nothing reads or writes it yet. The edit payloads ([keyforms],
 * [blendShapes], [paths], [vertexGroups], [glue], [simulationTargets]) keep their journal JSON
 * shape until they get typed counterparts.
 *
 * @property id The drawable this primitive defines.
 * @property layerId The source layer whose pixels the primitive shows.
 * @property supersedes Primitives (or generated drawables) removed where this record replays.
 * @property parent The deformer the mesh hangs under; null for canvas space.
 */
data class ArtPrimitive(
	val id: String,
	val layerId: String,
	val origin: ArtPrimitiveOrigin,
	val supersedes: List<String> = emptyList(),
	val parent: String? = null,
	val mesh: ArtPrimitiveMesh? = null,
	val keyforms: List<JsonObject> = emptyList(),
	val blendShapes: List<JsonObject> = emptyList(),
	val paths: List<JsonObject> = emptyList(),
	val vertexGroups: List<JsonObject> = emptyList(),
	val glue: List<JsonObject> = emptyList(),
	val simulationTargets: List<JsonObject> = emptyList(),
)
