package io.github.psd2live.core

import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Glue

/**
 * Split parts the skeleton skinned with the base rig. An `art_primitive` record creates its parts only where it
 * replays in the journal, after the skeleton baked the base, so a bake of the base alone never sees limbs split
 * per side and leaves their bones without meshes. The bake decodes the parts its bones bind from their records,
 * skins them with the rest and hands them here; each record then places these in place of decoding its own (see
 * [ArtPrimitiveJournal.replay]). Texture coordinates are canvas units, as the records store them. [glues] are the
 * welds the bake added between them and other skinned meshes.
 *
 * Compared by identity: replay checkpoints key on it alongside the base rig it was baked with.
 */
class PrimitiveSkins internal constructor(val drawables: Map<DrawableId, Drawable>, val glues: List<Glue>) {
	companion object {
		val None = PrimitiveSkins(emptyMap(), emptyList())
	}
}
