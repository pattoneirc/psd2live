package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsSourceType
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

/** Baking input by input: what the rig says each input does to the body, and a bake on a small strand. */
class SimDofTest {
    private val columns = 1
    private val rows = 8
    private val cell = 10f

    private val mesh: DrawableMesh = run {
        val positions = ArrayList<Float>()
        for (r in 0..rows) for (c in 0..columns) { positions += c * cell; positions += r * cell }
        val indices = ArrayList<Int>()
        for (r in 0 until rows) for (c in 0 until columns) {
            val a = r * (columns + 1) + c
            indices += listOf(a, a + 1, a + columns + 2, a, a + columns + 2, a + columns + 1)
        }
        val p = positions.toFloatArray()
        DrawableMesh(p, FloatArray(p.size) { p[it] / 1000f }, indices.toIntArray())
    }

    /**
     * A strand pinned along its top, and parameters that carry it sideways and up, turn it about its root, bend its
     * lower half alone, and one that moves nothing.
     */
    private val model: PuppetModel = run {
        val p = mesh.positions
        fun form(move: (x: Float, y: Float) -> Pair<Float, Float>) = MeshDeltaForm(FloatArray(p.size) { k ->
            val (x, y) = move(p[k - k % 2], p[k - k % 2 + 1]); if (k % 2 == 0) x - p[k] else y - p[k]
        })
        val shift = ParameterId("Shift"); val lift = ParameterId("Lift"); val turn = ParameterId("Turn"); val bend = ParameterId("Bend")
        fun axis(id: ParameterId, at: (Float) -> MeshDeltaForm) = Triple(id, floatArrayOf(-1f, 0f, 1f), at)
        val axes = listOf(
            axis(shift) { v -> form { x, y -> (x + 20f * v) to y } },
            axis(lift) { v -> form { x, y -> x to (y + 20f * v) } },
            axis(turn) { v -> form { x, y -> val a = 0.3f * v; (5f + (x - 5f) * cos(a) - y * sin(a)) to ((x - 5f) * sin(a) + y * cos(a)) } },
            axis(bend) { v -> form { x, y -> (if (y > rows * cell / 2) x + 20f * v * (y - rows * cell / 2) / (rows * cell / 2) else x) to y } },
        )
        // The four axes as one dense grid whose cells add up each parameter's move.
        val grid = KeyformGrid(axes.map { KeyformAxis(it.first, it.second) }, buildList {
            for (i in 0 until 3) for (j in 0 until 3) for (k in 0 until 3) for (l in 0 until 3) {
                val index = intArrayOf(i, j, k, l)
                val sum = FloatArray(p.size)
                for ((a, axis) in axes.withIndex()) { val d = axis.third(axis.second[index[a]]).positionDeltas; for (n in sum.indices) sum[n] += d[n] }
                add(KeyformCell(index, MeshDeltaForm(sum)))
            }
        })
        val drawable = Drawable(DrawableId("strand"), "strand", null, BlendMode.Normal, emptyList(), mesh, grid)
        val pin = VertexGroup("pin", drawable.id, VertexGroupKind.PIN, FloatArray(mesh.vertexCount) { if (it <= columns) 1f else 0f })
        PuppetModel((axes.map { it.first } + ParameterId("Other")).map { Parameter(it, it.raw, -1f, 1f, 0f) }, emptyList(), emptyList(),
            listOf(drawable), listOf(OrgChild.Drawable(drawable.id)), null, vertexGroups = listOf(pin))
    }

    private val edit = RigSimEdit("strand", "Strand", SimKind.HAIR, listOf("strand"), modes = 1, keys = 3,
        inputs = listOf("Shift", "Lift", "Turn", "Bend", "Other").map { PhysicsInput(it, 50f, PhysicsSourceType.X) })

    private fun analysis(): SimKinematics.Analysis {
        val calibrated = SimScene.build(model, edit)
        return SimKinematics.analyze(model, edit, { SimScene.build(model, edit).also { it.adopt(calibrated) } }, model.parameters, parallel = false)
    }

    @Test fun theRigTellsHowEachInputMovesTheBody() {
        val inputs = analysis().inputs.associateBy { it.id }
        val shift = inputs.getValue("Shift"); val lift = inputs.getValue("Lift"); val turn = inputs.getValue("Turn")
        val bend = inputs.getValue("Bend"); val other = inputs.getValue("Other")
        assertTrue(shift.moves && shift.rigid, "a shift carries the strand: reshape ${shift.reshape} of ${shift.motion}")
        assertEquals(20f, abs(shift.slope(SimKinematics.X)), 0.5f)
        assertEquals(0f, shift.slope(SimKinematics.TURN), 0.01f)
        assertTrue(lift.moves && lift.rigid)
        assertEquals(20f, abs(lift.slope(SimKinematics.Y)), 0.5f)
        assertTrue(turn.moves && turn.rigid, "a turn about the root is rigid: reshape ${turn.reshape} of ${turn.motion}")
        assertEquals(0.3f, abs(turn.slope(SimKinematics.TURN)), 0.02f)
        assertTrue(bend.moves && !bend.rigid, "bending the lower half reshapes it: reshape ${bend.reshape} of ${bend.motion}")
        assertFalse(other.moves, "a parameter on nothing moves nothing: ${other.motion} px")
    }

    @Test fun inputsGoToTheGroupTheyMoveTheBodyIn() {
        val (side, up) = SimDofTraining.groups(analysis(), null)
        assertEquals(setOf("Shift", "Turn", "Bend"), side.map { it.id }.toSet())
        assertEquals(listOf("Lift"), up.map { it.id })
        assertTrue(SimDofTraining.groups(analysis(), false).second.isEmpty(), "with the up-and-down parameter off nothing goes there")
    }

    @Test fun aRigidResidualIsTheOffsetTurnedBackIntoTheRestFrame() {
        val scene = SimScene.build(model, edit).also { it.reset(model, emptyMap()) }
        val space = SimSpace(model, scene)
        val s = scene.state
        val angle = 0.4f
        // Every particle off its goal by (3, 4) in the body's frame, the body turned by angle.
        for (i in 0 until s.count) { s.x[i] = s.goalX[i] + 3f * cos(angle) - 4f * sin(angle); s.y[i] = s.goalY[i] + 3f * sin(angle) + 4f * cos(angle) }
        val local = space.residualRigid(scene, angle)
        // Local mesh space is world with y turned over.
        for (i in 0 until s.count) { assertEquals(3f, local[i * 2], 1e-3f); assertEquals(-4f, local[i * 2 + 1], 1e-3f) }
    }

    @Test fun aBakeInputByInputLeavesOutWhatDoesNotMoveTheBody() {
        val bake = SimBaker.bake(model, edit.copy(inputs = edit.inputs.filter { it.parameter in setOf("Shift", "Other") }),
            SimBaker.Options(method = SimBaker.Method.DOF, duration = 0.5f))
        val read = bake.pendulums.flatMap { it.inputs }.map { it.parameter }.toSet()
        assertTrue("Shift" in read, "the shift drives the pendulum: $read")
        assertFalse("Other" in read, "an input that moves nothing takes no part: $read")
        assertTrue(bake.modes.isNotEmpty())
    }

    @Test fun theBakeSaysWhatItReadOfEachInputAndHowItFollowsUnseenMotion() {
        val bake = SimBaker.bake(model, edit.copy(inputs = edit.inputs.filter { it.parameter in setOf("Shift", "Lift", "Other") }),
            SimBaker.Options(method = SimBaker.Method.DOF, duration = 0.5f))
        val read = bake.inputs.associateBy { it.parameter }
        assertEquals(SimInputCheck.SIDEWAYS, read.getValue("Shift").group)
        assertEquals(SimInputCheck.VERTICAL, read.getValue("Lift").group)
        assertTrue(read.getValue("Other").dropped)
        assertEquals("dof", bake.method)
        assertTrue(bake.visual.any { it.motion == SimBaker.HELD_OUT }, "the sideways group is checked on its held-out motion")
        // All of it survives the archive's round trip, and equal bakes stay equal.
        val reread = SimBakeResult.fromJson(bake.toJson())
        assertEquals(bake, reread)
        assertEquals(bake.inputs.map { it.toJson() }, reread.inputs.map { it.toJson() })
        assertTrue(bake.summary().containsKey("visual") && bake.summary().containsKey("inputs"))
    }

    @Test fun aKeptInputBakesHoweverLittleItMovesAndGoesWithItsInput() {
        val kept = edit.copy(inputs = edit.inputs.filter { it.parameter in setOf("Shift", "Other") }, forcedInputs = listOf("Other"))
        val bake = SimBaker.bake(model, kept, SimBaker.Options(method = SimBaker.Method.DOF, duration = 0.5f))
        assertTrue(bake.inputs.single { it.parameter == "Other" }.let { it.forced && !it.dropped })
        assertEquals(kept, RigSimEdit.fromJson(kept.toJson()))
        assertEquals(emptyList(), kept.withInputs(kept.inputs.filter { it.parameter == "Shift" }).forcedInputs)
        assertFailsWith<IllegalArgumentException> { kept.copy(forcedInputs = listOf("Lift")) }
    }

    @Test fun aBakeIsComparedWithItsReferenceOnTheModelsOwnMotion() {
        val sway = io.github.psd2live.core.MotionClip("sway", "Sway", duration = 2f, fadeIn = 0f, curves = listOf(io.github.psd2live.core.MotionCurve("Shift",
            listOf(io.github.psd2live.core.MotionKey(0f, 0f), io.github.psd2live.core.MotionKey(0.5f, 1f), io.github.psd2live.core.MotionKey(1f, -1f), io.github.psd2live.core.MotionKey(1.5f, 0f)))))
        val shifted = edit.copy(inputs = edit.inputs.filter { it.parameter == "Shift" }, trainingClips = mapOf("sway" to 0.5f))
        val overlay = io.github.psd2live.core.RigEditOverlay(motionClips = listOf(sway), simEdits = listOf(shifted))
        // The motion trains with it, and the bake is then checked on it.
        val motions = SimAuthoring.trainingMotions(overlay, shifted, model)
        assertEquals(listOf("sway"), motions.map { it.name })
        val bake = SimBaker.bake(model, shifted, SimBaker.Options(method = SimBaker.Method.DOF, duration = 0.5f, trainingMotions = motions))
        val baked = overlay.copy(simEdits = listOf(shifted.copy(bake = bake)))
        val result = SimCompare.compare(baked, model, "strand", listOf("sway")).single()
        assertEquals("sway", result.motion)
        assertTrue(result.check.motionPx > 1f, "the strand swings under the motion: ${result.check.motionPx} px")
        assertFailsWith<IllegalArgumentException> { SimCompare.compare(baked, model, "strand", listOf("nothing")) }
    }

    @Test fun aBodyNoInputMovesCannotBake() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SimBaker.bake(model, edit.copy(inputs = listOf(PhysicsInput("Other"))), SimBaker.Options(method = SimBaker.Method.DOF, duration = 0.5f))
        }
        assertTrue("add inputs" in failure.message.orEmpty(), failure.message)
    }
}
