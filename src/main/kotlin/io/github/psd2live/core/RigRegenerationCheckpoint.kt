package io.github.psd2live.core

import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import org.umamo.format.art.SourceArt

/**
 * Where an edit changes what the generators make under the journal, the journal is not replayed on the new output:
 * the previous generated rig G, the new one G' and the authored rig M merge ([RigRegeneration]) and the result is
 * stored as a `rig_checkpoint` ([RigCheckpoint]) where the previous journal ends, before the entries the edit adds.
 * Replay then starts there; the entries before it stay as history.
 */
internal object RigRegenerationCheckpoint {
	/**
	 * [next] - a configuration whose edits continue [current]'s ([RigEditOverlay.continues]) - with a checkpoint where
	 * [current]'s journal ends, when the generated rig of [source] under [next], as that journal sees it, differs from
	 * [current]'s. Null when it does not: replaying the journal gives what it gave.
	 */
	fun checkpointed(pipeline: PSD2LivePipeline, current: RigPreviewModel, next: PipelineConfig, source: SourceArt,
	                 checkpoint: () -> Unit = {}): PipelineConfig? {
		val before = current.config.rigEdits
		val after = next.rigEdits
		val boundary = before.authoringJournal.size
		val (base, bindingKey) = pipeline.generatedBaseOf(source, next)
		if (current.sources.baseKnown && base === current.baseRig) return null
		checkpoint()
		// The new generation as the entries up to the boundary see it: later splits' parts not yet in place.
		val earlier = after.authoringJournal.subList(0, boundary).filter(ArtPrimitiveV2::isV2)
		val next2 = base.resolvedPuppet(earlier)
		val atlas = base.puppet.atlas
		val sources = base.puppet.sources
		val previous = current.baseRig.resolvedPuppet().reboundTo(atlas, sources)
		if (PuppetIr.toIr(previous) == PuppetIr.toIr(next2)) return null
		checkpoint()
		val authored = current.authored
		val result = RigRegeneration.merge(previous, next2, authored.rig.puppet.reboundTo(atlas, sources), checkpoint)
		val model = result.model
		val ids = model.drawables.mapTo(HashSet()) { it.id.raw }
		fun <V> merged(generated: Map<String, V>, user: Map<String, V>) = (user + generated).filterKeys { it in ids }
		val rig = base.copy(puppet = model,
			layerIdByDrawableId = merged(base.layerIdByDrawableId, authored.rig.layerIdByDrawableId),
			sourceBoundsByDrawableId = merged(base.sourceBoundsByDrawableId, authored.rig.sourceBoundsByDrawableId),
			pageByDrawableId = model.drawables.filter { it.id.raw in base.pageByDrawableId || it.id.raw in authored.rig.pageByDrawableId }
				.associate { it.id.raw to it.texturePage },
			supersededEntryNotes = authored.rig.supersededEntryNotes, overrideIssues = emptyList())
		val record = RigCheckpoint.encode(AuthoredRig(rig, authored.visibilityTargets.filter { it.first in ids }), bindingKey, result.issues)
		val journal = ArrayList<JsonObject>(after.authoringJournal).apply { add(boundary, record) }
		return next.copy(rigEdits = after.copy(authoringJournal = journal))
	}
}
