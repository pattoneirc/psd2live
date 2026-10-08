package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import java.util.Collections
import java.util.WeakHashMap

/**
 * The `rig_checkpoint` journal record: the authored rig at its place in the journal, stored as data. Replay starts
 * from the last checkpoint - its rig, then the entries after it - so nothing before it is replayed again: those
 * entries stay as history only. A regeneration writes one ([RigRegeneration]) where the generated rig changed under
 * the journal, so later builds never replay old entries on a base they were not written for.
 *
 * Record: `{op, v: 1, authored: {header, frame, deformers, meshes}, generated: {...}, issues: [{kind, target, detail}]}`:
 * [MaterializedRigCodec] indexes of the authored rig and of the generated rig it was merged from (the generation as
 * the journal saw it there, [BuiltRig.resolvedPuppet]) - objects in [RigObjects], persisted with the document and
 * shared between the two where unchanged - and what the merge could not carry over cleanly ([RigRegeneration.Issue]).
 * The generated rig is what a later regeneration with a newer generator compares against ([RigRegenerationCheckpoint.updated]). Texture coordinates address the atlas the header's
 * binding key names; a build on another atlas re-binds them through canvas texture coordinates ([reboundTo]).
 */
internal object RigCheckpoint {
	const val OP = "rig_checkpoint"
	const val VERSION = 1

	fun isRecord(command: JsonObject) = command["op"]?.jsonPrimitive?.contentOrNull == OP

	fun encode(authored: AuthoredRig, bindingKey: String, issues: List<RigRegeneration.Issue> = emptyList(), generated: AuthoredRig? = null): JsonObject = buildJsonObject {
		put("op", OP); put("v", VERSION); put("authored", MaterializedRigCodec.index(authored, bindingKey))
		generated?.let { put("generated", MaterializedRigCodec.index(it, bindingKey)) }
		put("issues", JsonArray(issues.map { issue -> buildJsonObject {
			put("kind", issue.kind.code); put("target", issue.target); if (issue.detail.isNotEmpty()) put("detail", issue.detail)
		} }))
	}

	/** What the regeneration that wrote [record] reported. */
	fun issues(record: JsonObject): List<RigRegeneration.Issue> = record["issues"]?.jsonArray.orEmpty().mapNotNull { element ->
		val issue = element.jsonObject
		val kind = RigRegeneration.IssueKind.entries.firstOrNull { it.code == issue["kind"]?.jsonPrimitive?.contentOrNull } ?: return@mapNotNull null
		RigRegeneration.Issue(kind, issue["target"]?.jsonPrimitive?.contentOrNull.orEmpty(), issue["detail"]?.jsonPrimitive?.contentOrNull.orEmpty())
	}

	/** The index of the last checkpoint in [journal], or -1. */
	fun latest(journal: List<JsonObject>): Int = journal.indexOfLast(::isRecord)

	/** Every object hash the checkpoints in [journal] name. */
	fun hashes(journal: List<JsonObject>): List<String> = journal.filter(::isRecord).flatMap { record ->
		MaterializedRigCodec.hashes(record.getValue("authored").jsonObject) +
			(record["generated"] as? JsonObject)?.let(MaterializedRigCodec::hashes).orEmpty()
	}

	/** The generated rig [record] was merged from, or null when it stores none. */
	fun generated(record: JsonObject): MaterializedRigCodec.Decoded? =
		(record["generated"] as? JsonObject)?.let(MaterializedRigCodec::fromIndex)

	private val decoded = Collections.synchronizedMap(WeakHashMap<JsonObject, MaterializedRigCodec.Decoded>())

	/** The authored rig [record] stores. */
	fun decode(record: JsonObject): MaterializedRigCodec.Decoded {
		require(record["v"]?.jsonPrimitive?.intOrNull == VERSION) { "Unsupported rig checkpoint version" }
		decoded[record]?.let { return it }
		return MaterializedRigCodec.fromIndex(record.getValue("authored").jsonObject).also { decoded[record] = it }
	}

	/** A replay checkpoint's identity cache: the last re-binding, by record and target atlas. */
	@Volatile private var rebound: Triple<JsonObject, PuppetAtlas, PuppetModel>? = null

	/** [record]'s authored puppet bound to [base]'s atlas; the same instance for the same record and atlas. */
	fun authoredOn(record: JsonObject, base: PuppetModel): PuppetModel {
		val stored = decode(record).authored.rig.puppet
		rebound?.let { (key, atlas, model) -> if (key === record && atlas === base.atlas) return model }
		return stored.reboundTo(base.atlas, base.sources).also { rebound = Triple(record, base.atlas, it) }
	}
}

/**
 * This model, bound to its own atlas, bound to [atlas] and [sources] instead: every textured mesh's texture
 * coordinates go through canvas texture coordinates onto its tile there. The same instance when the atlas is the same;
 * a mesh whose tile [atlas] lacks keeps its coordinates.
 */
internal fun PuppetModel.reboundTo(atlas: PuppetAtlas, sources: List<ArtSource>): PuppetModel {
	if (this.atlas == atlas && this.sources == sources) return this
	val target = copy(atlas = atlas, sources = sources)
	return target.copy(drawables = drawables.map { drawable ->
		val mesh = drawable.mesh ?: return@map drawable
		val tile = drawable.atlasTileId?.let { atlas.tileById[it] } ?: return@map drawable
		val canvas = runCatching { RasterMeshJournal.TextureCoordinates(this, drawable).toCanvas(mesh.uvs) }.getOrNull() ?: return@map drawable
		val placed = drawable.copy(texturePage = tile.placement?.pageIndex ?: -1)
		placed.copy(mesh = DrawableMesh(mesh.positions, RasterMeshJournal.TextureCoordinates(target, placed).toUvs(canvas), mesh.indices))
	})
}

/** [authored] bound to [atlas] and [sources], its page map following. */
internal fun AuthoredRig.reboundTo(atlas: PuppetAtlas, sources: List<ArtSource>): AuthoredRig {
	val puppet = rig.puppet.reboundTo(atlas, sources)
	if (puppet === rig.puppet) return this
	val pages = rig.pageByDrawableId.toMutableMap()
	for (drawable in puppet.drawables) if (drawable.id.raw in pages) pages[drawable.id.raw] = drawable.texturePage
	return copy(rig = rig.copy(puppet = puppet, pageByDrawableId = pages))
}
