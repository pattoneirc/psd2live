package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.*

class RotationCreationTest {
    private val parameter = ParameterId("pose")
    private fun <T> grid(a: T, b: T) = KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))),
        listOf(KeyformCell(intArrayOf(0), a), KeyformCell(intArrayOf(1), b)))
    private fun drawable(parent: DeformerId?, normalized: Boolean = false): Drawable {
        val points = if (normalized) floatArrayOf(.1f, .2f, .8f, .1f, .7f, .9f) else floatArrayOf(20f, 30f, 80f, 20f, 70f, 90f)
        return Drawable(DrawableId("mesh"), "Mesh", parent, BlendMode.Normal, emptyList(),
            DrawableMesh(points, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), intArrayOf(0, 1, 2)),
            grid(MeshDeltaForm(FloatArray(6)), MeshDeltaForm(FloatArray(6) { if (normalized) .03f else 3f })))
    }
    private fun command(originX: Float = 0.5f, originY: Float = 0.5f) = buildJsonObject {
        put("op", "canvas_create_rotation"); put("id", "new"); put("name", "New")
        put("preservePose", true); put("angle", 37f)
        put("add_to", "parent_of_selected")
        put("origin", JsonArray(listOf(JsonPrimitive(originX), JsonPrimitive(originY))))
        put("meshes", JsonArray(listOf(JsonPrimitive("mesh"))))
    }
    private fun verify(parent: Deformer?, originX: Float, originY: Float) {
        val mesh = drawable(parent?.id, parent is Deformer.Warp)
        val source = PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), listOfNotNull(parent),
            listOf(mesh), listOf(OrgChild.Drawable(mesh.id)), null)
        val created = CanvasEdits.apply(source, command(originX, originY))
        assertContentEquals(mesh.mesh!!.uvs, created.drawables.single().mesh!!.uvs)
        // New rotation sits between the mesh and its former parent.
        assertEquals(parent?.id, created.deformers.single { it.id.raw == "new" }.parent)
        assertEquals(DeformerId("new"), created.drawables.single().parentDeformerId)
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(0f, .25f, .5f, 1f)) {
            val before = evaluator.evaluate(source, mapOf(parameter to value)).worldPositions.getValue(mesh.id)
            val after = evaluator.evaluate(created, mapOf(parameter to value)).worldPositions.getValue(mesh.id)
            before.indices.forEach { assertEquals(before[it], after[it], .002f, "pose=$value component=$it") }
        }
        // The source is immutable; replay produces the same result.
        val replay = CanvasEdits.apply(source, command(originX, originY))
        assertContentEquals(created.drawables.single().mesh!!.positions, replay.drawables.single().mesh!!.positions)
    }
    @Test fun unparentedMeshKeepsItsPoses() = verify(null, 50f, 50f)
    /** Warp parent: mesh lands in pixel-scale rotation-local. Static lattice so mid-poses stay exact. */
    @Test fun warpedBranchKeepsItsPoses() = verify(Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
        KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(),
            WarpLatticeForm(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)))))), 0.5f, 0.5f)

    @Test fun rotatedBranchKeepsItsPoses() = verify(Deformer.Rotation(DeformerId("rotation"), "Rotation", null, null, 12f,
        grid(RotationPivotForm(13f, 9f, -20f, .7f), RotationPivotForm(30f, 6f, 65f, 1.3f))), 50f, 50f)

    @Test fun parentOfDeformerInsertsAboveAnchor() {
        val warp = Deformer.Warp(DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f))))))
        val mesh = drawable(warp.id, normalized = true)
        val source = PuppetModel(listOf(Parameter(parameter, "Pose", 0f, 1f, 0f)), emptyList(), listOf(warp),
            listOf(mesh), listOf(OrgChild.Drawable(mesh.id)), null)
        val cmd = buildJsonObject {
            put("op", "canvas_create_rotation"); put("id", "rot"); put("name", "Rot")
            put("preservePose", true); put("angle", 0f)
            put("add_to", "parent_of_deformer"); put("deformer_id", "warp")
            put("origin", JsonArray(listOf(JsonPrimitive(50f), JsonPrimitive(50f))))
        }
        val created = CanvasEdits.apply(source, cmd)
        val rot = created.deformers.single { it.id.raw == "rot" }
        assertNull(rot.parent)
        assertEquals(DeformerId("rot"), created.deformers.single { it.id.raw == "warp" }.parent)
        assertEquals(warp.id, created.drawables.single().parentDeformerId)
    }

    @Test fun rotationCrossing180KeepsContinuousKeyAngles() {
        val rotation = Deformer.Rotation(DeformerId("r"), "Rotation", null, null, 0f,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(0f, 0f, 179f, 1f)))))
        val source = PuppetModel(emptyList(), emptyList(), listOf(rotation), emptyList(), emptyList(), null)
        val angle = 181f * kotlin.math.PI.toFloat() / 180f
        val edit = buildJsonObject {
            put("op", "canvas_geometry"); put("id", "r"); put("kind", "rotation"); put("key", JsonObject(emptyMap()))
            put("points", JsonArray(listOf(0f, 0f, 100f * kotlin.math.cos(angle), 100f * kotlin.math.sin(angle)).map(::JsonPrimitive)))
        }
        val result = CanvasEdits.apply(source, edit).deformers.single() as Deformer.Rotation
        assertEquals(181f, result.geometryGrid!!.cells.single().form.angle, .001f)
    }

    /** Tip-rotate must not rewrite stored scale (that resizes every child under a Warp parent). */
    @Test
    fun tipRotateWithKeepScalePreservesScale() {
        val id = DeformerId("rot")
        val rotation = Deformer.Rotation(
            id, "Rot", null, null, 0f,
            KeyformGrid(
                emptyList(),
                listOf(KeyformCell(intArrayOf(), RotationPivotForm(0f, 0f, 0f, 2f))),
            ),
        )
        val model = PuppetModel(
            emptyList(), emptyList(), listOf(rotation), emptyList(), emptyList(), null,
        )
        // Arm length 200 with reference length 100 → scale 2. Rotate 90° with same length.
        val cmd = buildJsonObject {
            put("op", "canvas_geometry")
            put("kind", "rotation"); put("id", "rot")
            put("key", JsonObject(emptyMap()))
            put("keep_scale", true)
            put("points", JsonArray(listOf(0f, 0f, 0f, 200f).map(::JsonPrimitive)))
        }
        val next = CanvasEdits.apply(model, cmd)
        val form = (next.deformers.single() as Deformer.Rotation).geometryGrid!!.cells.single().form
        assertEquals(2f, form.scale, 1e-4f)
        assertEquals(90f, form.angle, 1e-3f)
    }

    @Test
    fun altScaleWithoutKeepScaleUpdatesScale() {
        val id = DeformerId("rot")
        val rotation = Deformer.Rotation(
            id, "Rot", null, null, 0f,
            KeyformGrid(
                emptyList(),
                listOf(KeyformCell(intArrayOf(), RotationPivotForm(0f, 0f, 0f, 1f))),
            ),
        )
        val model = PuppetModel(
            emptyList(), emptyList(), listOf(rotation), emptyList(), emptyList(), null,
        )
        val cmd = buildJsonObject {
            put("op", "canvas_geometry")
            put("kind", "rotation"); put("id", "rot")
            put("key", JsonObject(emptyMap()))
            put("points", JsonArray(listOf(0f, 0f, 200f, 0f).map(::JsonPrimitive)))
        }
        val next = CanvasEdits.apply(model, cmd)
        val form = (next.deformers.single() as Deformer.Rotation).geometryGrid!!.cells.single().form
        assertEquals(2f, form.scale, 1e-4f)
    }
}
