package io.github.psd2live.core

import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.*

/** Converts unbound canvas meshes through their actual parent's neutral transform. */
internal object RasterMeshPlacement {
    fun underParents(generated: BuiltRig, current: PuppetModel, parent: (DrawableId) -> DeformerId?,
                     checkCancelled: () -> Unit = {}): BuiltRig {
        val puppet = generated.puppet.copy(deformers = current.deformers, parameters = current.parameters,
            drawables = generated.puppet.drawables.map { it.copy(parentDeformerId = parent(it.id)) })
        val converted = puppet.copy(drawables = puppet.drawables.map { drawable ->
            checkCancelled()
            val mesh = requireNotNull(drawable.mesh)
            drawable.copy(mesh = DrawableMesh(underParent(puppet, drawable.id, mesh.positions), mesh.uvs, mesh.indices))
        })
        return generated.copy(puppet = converted)
    }

    /** [canvas] positions of [id] in the neutral space of the deformer it hangs under in [model]. */
    fun underParent(model: PuppetModel, id: DrawableId, canvas: FloatArray): FloatArray =
        requireNotNull(placed(model, id, canvas)) { "Parent transform cannot place the imported mesh" }

    /**
     * [canvas] positions of [drawable] in the neutral space of its parent among [model]'s deformers, or null
     * when that parent is missing or cannot reproduce them. [drawable] need not be in [model] yet.
     */
    fun underParent(model: PuppetModel, drawable: Drawable, canvas: FloatArray): FloatArray? =
        placed(model.copy(drawables = model.drawables.filter { it.id != drawable.id } + drawable), drawable.id, canvas)

    private fun placed(model: PuppetModel, id: DrawableId, canvas: FloatArray): FloatArray? {
        val world = canvas.copyOf().also { values -> for (i in 1 until values.size step 2) values[i] = -values[i] }
        val mapping = drawableSpaceMapping(model, emptyMap(), id) ?: return null
        val local = mapping.worldToLocalLinearized(world, FloatArray(world.size) { 0.5f }, world, (0 until world.size / 2).toSet())
        val actual = mapping.localToWorld(local)
        return local.takeIf { local.all(Float::isFinite) && actual.indices.all { kotlin.math.abs(actual[it] - world[it]) < 0.05f } }
    }
}
