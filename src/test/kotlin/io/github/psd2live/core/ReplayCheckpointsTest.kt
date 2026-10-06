package io.github.psd2live.core

import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimBakeResult
import io.github.psd2live.core.sim.SimBakedAxis
import io.github.psd2live.core.sim.SimBakedMode
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.*

/**
 * Replay checkpoints and generator reuse: every replay equals a full replay without them, appending replays
 * one entry, undo lands on a checkpoint, and a swing or simulation reruns only when what it reads changed.
 */
class ReplayCheckpointsTest {
	private val initial by lazy { PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd")) }
	private val base: PuppetModel by lazy { initial.baseRig.puppet }

	private fun meshOf(tag: SemanticTag) = initial.analysis.layers.associateBy { it.source.id.raw }.let { layers ->
		initial.rig.puppet.drawables.first { d -> d.mesh != null && layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == tag }
	}
	private val back by lazy { meshOf(SemanticTag.BACK_HAIR) }
	private val front by lazy { meshOf(SemanticTag.FRONT_HAIR) }

	private fun simulation(scale: Float): RigSimEdit {
		val count = back.mesh!!.vertexCount
		val sim = RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw), modes = 1, keys = 3, blendShapes = false)
		val parameter = SimGenerator.parameterId(sim, 1)
		val push = FloatArray(count * 2) { if (it % 2 == 0) scale * (it / 2 % 7) * 1e-3f else 0f }
		val axis = SimBakedAxis(parameter, floatArrayOf(-SimGenerator.MODE_RANGE, 0f, SimGenerator.MODE_RANGE),
			mapOf(back.id.raw to listOf(FloatArray(count * 2) { -push[it] }, FloatArray(count * 2), push)))
		return sim.copy(bake = SimBakeResult("synthetic", mapOf(back.id.raw to count), emptyList(), listOf(SimBakedMode(axis, 10f, 1f))))
	}

	/** The tml rig with a swing on the front hair and a baked back-hair simulation. */
	private val generated: RigEditOverlay by lazy {
		val start = initial.config.rigEdits
		val swing = RigSwingEdit.single("front", "Front", SwingKind.LATERAL, listOf(front.id.raw), listOf("ParamSwingFront"))
		SwingAuthoring.put(start, full(start), swing).copy(simEdits = listOf(simulation(1f)))
	}

	private fun <T> uncached(block: () -> T): T {
		val was = ReplayCheckpoints.enabled
		ReplayCheckpoints.enabled = false
		try { return block() } finally { ReplayCheckpoints.enabled = was }
	}

	private fun full(overlay: RigEditOverlay) = uncached { overlay.applyTo(base) }
	private fun hash(model: PuppetModel) = ContentHash.of(PuppetIr.toIr(model))

	/** A small move of a third of [id]'s points at the rest key, compiled against [model] as a commit is. */
	private fun move(model: PuppetModel, kind: String, id: String, random: Random): List<JsonObject> {
		val points = RigGeometryTools.geometry(model, kind, id, emptyMap()).points
		val extent = (points.max() - points.min()).coerceAtLeast(1e-3f)
		val dx = (random.nextFloat() - 0.5f) * extent * 0.01f; val dy = (random.nextFloat() - 0.5f) * extent * 0.01f
		val moved = points.copyOf()
		for (v in 0 until points.size / 2) if (random.nextInt(3) == 0) { moved[v * 2] += dx; moved[v * 2 + 1] += dy }
		val command = buildJsonObject {
			put("op", "canvas_geometry"); put("kind", kind); put("id", id)
			put("key", JsonObject(emptyMap())); put("pose", JsonObject(emptyMap())); put("preserve_image", true)
			put("points", JsonArray(moved.map(::JsonPrimitive)))
		}
		return RigAuthoringJournal.compile(model, JsonArray(listOf(command))).second
	}

	private fun opacity(model: PuppetModel, id: String, random: Random): List<JsonObject> {
		val parameter = model.parameters.first { it.id.raw == "ParamAngleX" }
		return listOf(buildJsonObject {
			put("op", "set"); put("target", "mesh:$id"); putJsonObject("key") { put(parameter.id.raw, parameter.max) }
			putJsonObject("channels") { put("opacity", 0.2f + random.nextFloat() * 0.8f) }
		})
	}

	private fun swingTarget(overlay: RigEditOverlay) = overlay.swingEdits.single().targets.single()

	@Test fun incrementalReplayEqualsAFullReplayAcrossEditsUndoAndBranches() {
		ReplayCheckpoints.clear(); GeneratorReuse.clear()
		val random = Random(20261006)
		val history = ArrayList<RigEditOverlay>().apply { add(generated) }
		var overlay = generated
		var model = overlay.applyTo(base)
		assertEquals(hash(full(overlay)), hash(model))
		val meshes = model.drawables.filter { it.mesh != null }.map { it.id.raw }
		val warps = model.deformers.filterIsInstance<Deformer.Warp>().map { it.id.raw }
		var applied = 0
		repeat(40) { step ->
			val choice = random.nextInt(12)
			val next = when {
				choice <= 3 -> overlay.copy(authoringJournal = overlay.authoringJournal + move(model, "mesh", meshes.random(random), random))
				choice == 4 -> overlay.copy(authoringJournal = overlay.authoringJournal + move(model, "mesh", back.id.raw, random))
				choice == 5 -> overlay.copy(authoringJournal = overlay.authoringJournal +
					move(model, "warp", if (random.nextBoolean()) swingTarget(overlay) else warps.random(random), random))
				choice == 6 -> overlay.copy(authoringJournal = overlay.authoringJournal + opacity(model, meshes.random(random), random))
				choice <= 8 && history.size > 1 -> {
					// Undo: back to an earlier document, with its own entry instances.
					repeat(minOf(history.size - 1, 1 + random.nextInt(3))) { history.removeAt(history.size - 1) }
					history.last()
				}
				choice == 9 && overlay.authoringJournal.size > generated.authoringJournal.size -> {
					// An earlier entry rewritten in place, as drag coalescing or override capture does.
					val i = generated.authoringJournal.size + random.nextInt(overlay.authoringJournal.size - generated.authoringJournal.size)
					val replacement = move(model, "mesh", meshes.random(random), random).singleOrNull() ?: overlay.authoringJournal[i]
					overlay.copy(authoringJournal = overlay.authoringJournal.toMutableList().also { it[i] = replacement })
				}
				choice == 10 -> overlay.copy(swingEdits = overlay.swingEdits.map { it.copy(tilt = random.nextFloat() * 20f - 10f) })
				else -> overlay.copy(simEdits = listOf(simulation(0.5f + random.nextFloat())))
			}
			val expected = runCatching { full(next) }
			val actual = runCatching { next.applyTo(base) }
			assertEquals(expected.isSuccess, actual.isSuccess, "step $step: both replays succeed or both fail (${expected.exceptionOrNull()})")
			if (expected.isSuccess) {
				assertEquals(hash(expected.getOrThrow()), hash(actual.getOrThrow()), "step $step: incremental replay equals the full replay")
				overlay = next; model = actual.getOrThrow(); applied++
				if (history.last() !== next) history += next
			}
		}
		assertTrue(applied >= 30, "most steps replay ($applied of 40)")
	}

	@Test fun appendingReplaysOneEntryAndUndoLandsOnACheckpoint() {
		ReplayCheckpoints.clear()
		val random = Random(3)
		var overlay = generated
		var model = overlay.applyTo(base)
		repeat(5) { overlay = overlay.copy(authoringJournal = overlay.authoringJournal + move(model, "mesh", front.id.raw, random)); model = overlay.applyTo(base) }
		val size = overlay.authoringJournal.size
		val appended = overlay.copy(authoringJournal = overlay.authoringJournal + move(model, "mesh", front.id.raw, random))
		val result = appended.applyTo(base)
		assertEquals(ReplayCheckpoints.Replayed(size, 1), ReplayCheckpoints.lastReplayed())
		assertEquals(hash(full(appended)), hash(result))

		// Undo replays nothing: the state before the last entry is kept.
		val undone = overlay.applyTo(base)
		assertEquals(ReplayCheckpoints.Replayed(size, 0), ReplayCheckpoints.lastReplayed())
		assertEquals(hash(model), hash(undone))

		// Rewriting an earlier entry misses every checkpoint from it on.
		val i = size - 3
		val rewritten = appended.copy(authoringJournal = appended.authoringJournal.toMutableList().also {
			it[i] = move(model, "mesh", back.id.raw, random).single()
		})
		val replayed = rewritten.applyTo(base)
		val from = ReplayCheckpoints.lastReplayed()!!.from
		assertTrue(from <= i, "replay starts at or before the rewritten entry, not at $from")
		assertEquals(hash(full(rewritten)), hash(replayed))
	}

	@Test fun generatorsRerunOnlyWhenWhatTheyReadChanged() {
		val random = Random(11)
		val model = generated.applyTo(base)
		val unrelated = model.drawables.first { it.mesh != null && it.id != back.id && it.id != front.id && it.id.raw.contains("Eye", ignoreCase = true) }
		fun counts(overlay: RigEditOverlay): Pair<Long, Long> {
			val hits = GeneratorReuse.hits; val misses = GeneratorReuse.misses
			val result = overlay.applyTo(base)
			assertEquals(hash(full(overlay)), hash(result))
			return (GeneratorReuse.hits - hits) to (GeneratorReuse.misses - misses)
		}
		// An unrelated mesh: both the swing and the simulation reuse their output.
		assertEquals(2L to 0L, counts(generated.copy(authoringJournal = generated.authoringJournal + move(model, "mesh", unrelated.id.raw, random))))
		// The swing's target Warp: the swing reruns, the simulation does not.
		assertEquals(1L to 1L, counts(generated.copy(authoringJournal = generated.authoringJournal + move(model, "warp", swingTarget(generated), random))))
		// The simulated mesh: the simulation reruns, the swing does not.
		assertEquals(1L to 1L, counts(generated.copy(authoringJournal = generated.authoringJournal + move(model, "mesh", back.id.raw, random))))
		// A retuned swing reruns that swing.
		assertEquals(1L to 1L, counts(generated.copy(swingEdits = generated.swingEdits.map { it.copy(tilt = 5f) })))
	}
}
