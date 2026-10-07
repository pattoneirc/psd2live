package io.github.psd2live.core

import org.umamo.runtime.model.DeformPath
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.ParameterId

/**
 * Split parts the skeleton skinned with the base rig. An `art_primitive` record creates its parts only where it
 * replays in the journal, after the skeleton baked the base, so a bake of the base alone never sees limbs split
 * per side and leaves their bones without meshes. The bake decodes the parts its bones bind from their records,
 * skins them with the rest and hands them here; each record then places these in place of decoding its own (see
 * [ArtPrimitiveJournal.replay]). Texture coordinates are canvas units, as the records store them. [glues] are the
 * welds the bake added between them and other skinned meshes.
 *
 * Version 2 records ([ArtPrimitiveV2]) build every part with the base and hold it back here: [drawables] then
 * holds each v2 primitive whole (mesh from the record, generated keyforms, channels, skin), [paths] the deform
 * paths generated on them, [generatedAxes] the generator parameters of each part's keyform grids (what replay
 * composes with the record's authored layer) and [stubs] the superseded drawables the base keeps in their slots
 * only so journal entries before the record can address them. v2 replay requires every primitive of the record
 * to be present in [drawables]; a missing one is an error, never a fallback to decoding the record. All three
 * are empty for documents without v2 records, as today.
 *
 * Compared by identity: replay checkpoints key on it alongside the base rig it was baked with.
 */
class PrimitiveSkins internal constructor(
	val drawables: Map<DrawableId, Drawable>,
	val glues: List<Glue>,
	/** Deform paths the base generated on held-back v2 parts; their records place them with the part. */
	val paths: List<DeformPath> = emptyList(),
	/** Per held-back v2 part, the generator parameters its generated keyform grids span. */
	val generatedAxes: Map<DrawableId, Set<ParameterId>> = emptyMap(),
	/** Superseded drawables the base built as stubs for later v2 records; excluded from [BuiltRig.resolvedPuppet]. */
	val stubs: Set<DrawableId> = emptySet(),
) {
	companion object {
		val None = PrimitiveSkins(emptyMap(), emptyList())
	}
}
