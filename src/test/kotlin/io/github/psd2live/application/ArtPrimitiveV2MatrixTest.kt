package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/**
 * Version 2 `art_primitive` records on the public tml example: four kinds of split (legs per side, an eye by polygon,
 * the mouth by polygon, a depth split), each followed by the changes that regenerate parts - a skeleton and a moved
 * joint, body strength, face tuning, the part's own and another layer's mesh settings, a part's classification. After
 * every step the committed rig equals a cold build of its document (with and without replay checkpoints), the
 * superseded original has no drawable, atlas tile or texture placement, and the parts carry what their generators
 * make; at the end of each chain the project reopens to the same IR, both exports read back to the same poses and
 * undo across the split returns to the rig before it.
 */
abstract class ArtPrimitiveV2Matrix {
	@TempDir lateinit var temporary: Path
	private val builder = WorkspacePreviewBuilder()
	private val flag: String? = System.getProperty(ArtPrimitiveV2.FLAG_PROPERTY)

	@BeforeEach fun enable() { System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true") }
	@AfterEach fun restore() { if (flag == null) System.clearProperty(ArtPrimitiveV2.FLAG_PROPERTY) else System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, flag) }

	companion object {
		const val LEGS = "lyid:4"; const val EYELASH = "lyid:19"; const val MOUTH = "lyid:29"; const val TOPWEAR = "lyid:10"
		const val NECK = "lyid:6"; const val FACE = "lyid:13"

		/** The imported tml document, shared by every test of this JVM. */
		private val imported: WorkspaceDocument by lazy {
			runBlocking {
				val runtime = WorkspaceRuntime<RigPreviewModel>({ WorkspacePreviewBuilder().build(it) })
				WorkspaceSourceImporter(runtime).importPsd(Path.of("examples/tml/psd-input/tml.psd").toAbsolutePath(), null, runtime.state.value.state)
				runtime.capture().document
			}
		}

		fun hash(model: RigPreviewModel): String = ContentHash.of(RigIrCompiler.compile(model)).toString()

		fun world(model: RigPreviewModel, pose: Map<String, Float>): Map<DrawableId, FloatArray> =
			CpuDeformationEvaluator().evaluate(model.rig.puppet, pose.mapKeys { ParameterId(it.key) }).worldPositions

		fun drawable(model: RigPreviewModel, layer: String) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == layer }

		fun polygon(layer: String, x: Int, names: List<String>, ids: List<String>) = WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
			put("layer_id", layer); put("names", JsonArray(names.map(::JsonPrimitive))); put("piece_ids", JsonArray(ids.map(::JsonPrimitive)))
			putJsonArray("polygon") { listOf(0 to 0, x to 0, x to 2100, 0 to 2100).forEach { (px, py) -> add(buildJsonArray { add(px); add(py) }) } }
		})

		fun maxDistance(a: FloatArray, b: FloatArray): Float {
			require(a.size == b.size) { "sizes ${a.size} vs ${b.size}" }
			return a.indices.maxOfOrNull { abs(a[it] - b[it]) } ?: 0f
		}
	}

	private suspend fun runtime(document: WorkspaceDocument = imported): WorkspaceRuntime<RigPreviewModel> {
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ next -> builder.build(next, runtime.capture().model) })
		runtime.install(runtime.state.value.state, "matrix", document, builder.build(document))
		return runtime
	}

	private suspend fun WorkspaceRuntime<RigPreviewModel>.run(label: String, vararg operations: WorkspaceDocumentOperation): WorkspaceCapture<RigPreviewModel> {
		val before = capture()
		return WorkspaceDocumentCommands(this).execute(before.projectId, before.state, label, operations.toList(), MutationAuthor.USER).capture
	}

	private suspend fun WorkspaceRuntime<RigPreviewModel>.split(vararg operations: WorkspaceDocumentOperation): WorkspaceCapture<RigPreviewModel> {
		val before = capture()
		return WorkspacePartitionCommands(this).execute(before.projectId, before.state, operations.toList(), "Split", MutationAuthor.USER).commit.capture
	}

	private fun settings(changes: JsonObject) = WorkspaceDocumentOperation("settings_update", buildJsonObject { put("changes", changes) })
	private fun meshUpdate(layer: String, margin: Int) = WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
		put("layer_id", layer); putJsonObject("changes") { put("outerMargin", margin) }
	})
	private fun classify(layer: String, role: String) = WorkspaceDocumentOperation("layer_classify", buildJsonObject {
		put("layer_id", layer); put("type", "preset"); put("role", role)
	})

	/** The split record's ghost (superseded drawable and layer) and its parts in record order. */
	private class Split(val ghost: DrawableId, val ghostLayer: String, val parts: List<DrawableId>, val partLayers: List<String>)

	private fun split(document: WorkspaceDocument, index: Int = 0): Split {
		val record = ArtPrimitiveJournal.commands(document.rigEdits)[index]
		assertTrue(ArtPrimitiveV2.isV2(record), "the split wrote version 1: ${record[ArtPrimitiveV2.FALLBACK]}")
		val primitives = ArtPrimitiveJournal.primitives(record)
		return Split(DrawableId(record.getValue("supersedes").jsonArray.single().jsonPrimitive.content),
			record.getValue("supersedes_layers").jsonArray.single().jsonPrimitive.content,
			primitives.map { DrawableId(it.getValue("id").jsonPrimitive.content) }, primitives.map { it.getValue("layer_id").jsonPrimitive.content })
	}

	private val report = StringBuilder()
	private fun note(line: String) { report.appendLine(line); println(line) }

	/**
	 * What every step must keep: no trace of the superseded original, the parts present, the committed rig a cold
	 * build of its document with and without replay checkpoints.
	 */
	private suspend fun verify(step: String, capture: WorkspaceCapture<RigPreviewModel>, split: Split) {
		val model = capture.model
		assertTrue(model.rig.puppet.drawables.none { it.id == split.ghost }, "$step: the ghost is drawn")
		assertTrue(split.ghostLayer !in model.rig.layerIdByDrawableId.values, "$step: the ghost layer is mapped")
		assertTrue(model.rig.puppet.atlas.tiles.none { it.source?.layerKey == split.ghostLayer }, "$step: the ghost has an atlas tile")
		assertTrue(split.ghostLayer !in model.atlas.placementByLayerId, "$step: the ghost's pixels are packed")
		for (part in split.parts) assertTrue(model.rig.puppet.drawables.any { it.id == part }, "$step: part ${part.raw} missing")
		assertTrue(model.rig.supersededEntryNotes.isEmpty(), "$step: ${model.rig.supersededEntryNotes}")
		// The committed rig is the incremental one (stage caches, replay checkpoints); the reference replays every
		// entry from the base on a fresh pipeline.
		val committed = hash(model)
		val previous = ReplayCheckpoints.enabled
		ReplayCheckpoints.enabled = false
		try {
			assertEquals(committed, hash(WorkspacePreviewBuilder().build(capture.document)), "$step: a cold replay without checkpoints differs")
		} finally { ReplayCheckpoints.enabled = previous }
		note("  ok  $step")
	}

	/** Reopens the saved project, exports moc3 and cmo3 and reads both back, undoes across the split and redoes. */
	private suspend fun finish(name: String, runtime: WorkspaceRuntime<RigPreviewModel>, beforeSplit: WorkspaceCapture<RigPreviewModel>,
	                           split: Split, poses: List<Map<String, Float>>) {
		val end = runtime.capture()
		val endHash = hash(end.model)
		val archive = temporary.resolve("$name.psd2live")
		ProjectRepository().save(ProjectSaveCapture(end.projectId, runtime.history(), JsonObject(emptyMap()), null,
			WorkspaceStore(temporary.resolve("store-$name"))), archive)
		val reopened = ProjectRepository().open(archive).use { WorkspacePreviewBuilder().build(it.history.head().snapshot) }
		assertEquals(endHash, hash(reopened), "$name: the reopened project differs")
		note("  ok  save and reopen")

		val files = PSD2LivePipeline().run(end.document.source, name, temporary.resolve("export-$name"),
			end.document.config().copy(exportMoc3 = true)).exportedFiles
		val cmo3 = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
		val moc3 = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".moc3") }.path)), null)
		val evaluator = CpuDeformationEvaluator()
		for ((label, exported) in listOf("cmo3" to cmo3, "moc3" to moc3)) {
			assertTrue(exported.drawables.none { it.id == split.ghost || it.name == end.model.baseRig.puppet.drawables.firstOrNull { d -> d.id == split.ghost }?.name && it.id !in split.parts },
				"$name: $label exports the ghost")
			for (part in split.parts) assertTrue(exported.drawables.any { it.id == part }, "$name: $label lacks ${part.raw}")
			// The moc3 drops hidden meshes as a Cubism bake does; the cmo3 keeps them.
			val expected = end.model.rig.puppet.drawables.filter { label == "cmo3" || it.isVisible }.map { it.id }.toSet()
			assertEquals(expected, exported.drawables.map { it.id }.toSet(), "$name: $label drawables")
			// Each part keeps its mesh: the same triangles and as many vertices.
			for (part in split.parts) {
				val mine = end.model.rig.puppet.drawables.single { it.id == part }.mesh!!; val theirs = exported.drawables.single { it.id == part }.mesh!!
				assertEquals(mine.vertexCount, theirs.vertexCount, "$name: $label ${part.raw} vertices")
				assertContentEquals(mine.indices, theirs.indices, "$name: $label ${part.raw} triangles")
			}
			// The moc3 reads back into the same deformation: the parts at every pose. (The cmo3 importer rebuilds the
			// editor's own deformer spaces, which this evaluator does not reproduce for the whole tml rig, so the cmo3
			// is checked for its inventory and meshes only.)
			if (label == "moc3") {
				var worst = 0f
				for (pose in poses) {
					val values = pose.mapKeys { ParameterId(it.key) }
					val a = evaluator.evaluate(end.model.rig.puppet, values).worldPositions
					val b = evaluator.evaluate(exported, values).worldPositions
					for (part in split.parts) worst = maxOf(worst, maxDistance(a.getValue(part), b.getValue(part)))
				}
				// The lowering re-bases rest meshes into canvas space (the mouth at MouthOpenY 1) and back: round-off of
				// a few hundredths of a pixel on tml, for split and unsplit meshes alike.
				assertTrue(worst < 0.1f, "$name: moc3 parts read back $worst px away")
				note("  ok  moc3 export read back (parts within $worst px at $poses)")
			} else note("  ok  cmo3 export read back (inventory and part meshes)")
		}

		val undone = runtime.checkout(end.projectId, end.state, beforeSplit.historyHead)
		assertEquals(hash(beforeSplit.model), hash(undone.model), "$name: undo across the split")
		assertTrue(undone.model.rig.puppet.drawables.any { it.id == split.ghost })
		val redone = runtime.checkout(undone.projectId, undone.state, end.historyHead)
		assertEquals(endHash, hash(redone.model), "$name: redo")
		note("  ok  undo across the split and redo")
		val directory = Path.of("build/tools/art-primitive-v2"); Files.createDirectories(directory)
		Files.writeString(directory.resolve("matrix-$name.txt"), report.toString())
	}

	/**
	 * The changes every chain goes through; [extra] runs after each with the chain's own checks. The skeleton steps
	 * run on a branch of their own from the current node: on tml a generation transition (body strength, face tuning)
	 * and a skeleton do not combine in either order, with or without a split (a separate, pre-existing failure).
	 */
	private suspend fun changes(runtime: WorkspaceRuntime<RigPreviewModel>, split: Split, tuning: JsonObject, other: String,
	                            extra: suspend (String, WorkspaceCapture<RigPreviewModel>, WorkspaceCapture<RigPreviewModel>) -> Unit) {
		suspend fun step(label: String, vararg operations: WorkspaceDocumentOperation) {
			val before = runtime.capture()
			val after = runtime.run(label, *operations)
			verify(label, after, split)
			extra(label, before, after)
		}
		val branch = runtime.capture()
		step("skeleton", WorkspaceDocumentOperation("skeleton_auto", buildJsonObject {}))
		val bone = runtime.capture().document.rigEdits.skeleton!!.bones.first { it.role == BoneRole.THIGH }
		step("skeleton joint moved", WorkspaceDocumentOperation("skeleton_move", buildJsonObject {
			put("bone_id", bone.id); put("end", "tail"); putJsonArray("point") { add(bone.tailX + 6f); add(bone.tailY - 4f) }
		}))
		val skeletal = runtime.capture()
		runtime.checkout(skeletal.projectId, skeletal.state, branch.historyHead)
		step("body strength", settings(buildJsonObject { put("bodyStrength", 0.6f) }))
		step("face tuning", settings(buildJsonObject { put("rigTuning", tuning) }))
		step("part mesh settings", meshUpdate(split.partLayers[0], 7))
		step("other mesh settings", meshUpdate(other, 7))
		try {
			step("part classification", classify(split.partLayers.last(), "objects"))
		} catch (failure: WorkspaceBatchEditException) {
			// On tml the generation transition of a classification fails for some layers with or without a split
			// ("Generation mesh neutral frame cannot be inverted", RigGenerationFrames): only a failure the unsplit
			// original does not share belongs to the split.
			val unsplit = runtime()
			val same = runCatching { unsplit.run("Classify original", classify(split.ghostLayer, "objects")) }.exceptionOrNull()
			assertNotNull(same, "classifying a part fails where the unsplit original does not: ${failure.cause?.message ?: failure.message}")
			assertEquals(failure.cause?.message, same.cause?.message)
			note("  --  part classification: the generation transition fails as it does for the unsplit original (${same.cause?.message})")
		}
	}

	protected fun legsSplitPerSide() = runBlocking<Unit> {
		val runtime = runtime(); val start = runtime.capture()
		note("legs (component split per side)")
		val after = runtime.split(WorkspaceDocumentOperation("source_split_components", buildJsonObject {
			put("layer_id", LEGS); put("names", JsonArray(listOf("Leg L", "Leg R").map(::JsonPrimitive)))
			put("piece_ids", JsonArray(listOf("leg-l", "leg-r").map(::JsonPrimitive))); put("sides", JsonArray(listOf("left", "right").map(::JsonPrimitive)))
		}))
		val split = split(after.document)
		verify("split", after, split)
		val parents = split.parts.map { id -> after.model.rig.puppet.drawables.single { it.id == id }.parentDeformerId?.raw }
		note("  parents of the parts: $parents (original: ${start.model.rig.puppet.drawables.single { it.id == split.ghost }.parentDeformerId?.raw})")
		// Generated, not pinned under the original's frame: the per-side parts join the leg pair.
		val original = start.model.rig.puppet.drawables.single { it.id == split.ghost }.parentDeformerId?.raw
		assertTrue(parents.all { it != null && it != original }, "the parts are generated as a pair of legs: $parents")
		changes(runtime, split, buildJsonObject { put("faceTurn", 12f) }, FACE) { label, before, now ->
			if (label == "skeleton" || label == "skeleton joint moved") {
				val spec = now.document.rigEdits.skeleton!!
				val legs = spec.bones.filter { it.role in setOf(BoneRole.THIGH, BoneRole.SHIN) }
				assertTrue(split.parts.all { part -> legs.any { part.raw in it.drawableIds } }, "$label: the leg bones bind both parts: ${legs.map { it.drawableIds }}")
				val puppet = now.model.rig.puppet
				val rest = world(now.model, emptyMap())
				for (leg in legs) {
					val parameter = puppet.parameters.single { it.id.raw == leg.parameterId }
					val posed = world(now.model, mapOf(leg.parameterId to parameter.max * 0.25f))
					assertTrue(split.parts.any { maxDistance(rest.getValue(it), posed.getValue(it)) > 2f }, "$label: ${leg.parameterId} moves no leg part")
				}
				note("      every leg bone moves a part")
			}
			if (label == "body strength") {
				val pose = mapOf("ParamBodyAngleX" to 10f)
				val moved = split.parts.map { maxDistance(world(before.model, pose).getValue(it), world(now.model, pose).getValue(it)) }
				assertTrue(moved.all { it > 0.01f }, "the parts follow the body strength: $moved")
				note("      parts moved by ${moved.map { "%.2f".format(it) }} px at BodyAngleX 10")
			}
		}
		finish("legs", runtime, start, split, listOf(emptyMap(), mapOf("ParamBodyAngleX" to 10f), mapOf("ParamAngleX" to -20f)))
	}

	protected fun eyelashSplitByPolygonFollowsTheEyeGenerator() = runBlocking<Unit> {
		val runtime = runtime(); val start = runtime.capture()
		note("eye (polygon split of the left eyelash)")
		val operation = polygon(EYELASH, 1102, listOf("Lash inner", "Lash outer"), listOf("lash-in", "lash-out"))
		val after = runtime.split(operation)
		val split = split(after.document)
		verify("split", after, split)
		val closed = mapOf("ParamEyeLOpen" to 0f)
		for (part in split.parts) assertTrue(after.model.rig.puppet.drawables.single { it.id == part }.geometryGrid!!.axes.any { it.parameterId.raw == "ParamEyeLOpen" },
			"${part.raw} has no eye-close keyforms")
		// Version 1 of the same split: the original's keyforms carried onto the parts - how the whole eye closes.
		System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
		val v1 = try { builder.build(WorkspacePartitionEdits.apply(operation, start.document, start.model)) }
			finally { System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true") }
		val deviation = split.parts.map { maxDistance(world(v1, closed).getValue(it), world(after.model, closed).getValue(it)) }
		note("  closed eye, v2 parts vs v1 parts: ${deviation.map { "%.3f".format(it) }} px")
		assertTrue(deviation.all { it < 1f }, "the parts close like the original eye: $deviation")

		changes(runtime, split, buildJsonObject { put("eyelashSquash", 40f) }, FACE) { label, before, now ->
			if (label == "part classification") {
				val reclassified = now.model.rig.puppet.drawables.single { it.id == split.parts.last() }
				assertTrue(reclassified.geometryGrid?.axes.orEmpty().none { it.parameterId.raw == "ParamEyeLOpen" }, "an object part does not blink")
			} else if (label != "other mesh settings") for (id in split.parts) assertTrue(
				now.model.rig.puppet.drawables.single { it.id == id }.geometryGrid!!.axes.any { it.parameterId.raw == "ParamEyeLOpen" }, "$label: ${id.raw} lost its blink")
			if (label == "face tuning") {
				val change = split.parts.drop(1).map { maxDistance(world(before.model, closed).getValue(it), world(now.model, closed).getValue(it)) }
				note("      eyelash squash moved the closed outer part by $change px")
				assertTrue(change.all { it > 0.01f }, "the eyelash squash regenerates the part")
			}
		}

		// A user edit of a generated cell of a part (two vertices of the closed eye) is captured as an override.
		val part = split.parts[0]
		val current = runtime.capture()
		val drawable = current.model.rig.puppet.drawables.single { it.id == part }
		val key = drawable.geometryGrid!!.axes.associate { axis ->
			axis.parameterId.raw to if (axis.parameterId.raw == "ParamEyeLOpen") 0f else current.model.rig.puppet.parameters.single { it.id == axis.parameterId }.default
		}
		val shown = RigGeometryTools.geometry(current.model.rig.puppet, "mesh", part.raw, key).points
		val moved = shown.copyOf().also { it[0] += 0.03f; it[1] -= 0.02f; it[2] += 0.03f }
		val edited = WorkspaceDocumentCommands(runtime).executeJournal(current.projectId, current.state, "Edit closed lash", JsonArray(listOf(buildJsonObject {
			put("op", "canvas_geometry"); put("kind", "mesh"); put("id", part.raw); put("preserve_image", false)
			put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) })); put("points", JsonArray(moved.map(::JsonPrimitive)))
		})), MutationAuthor.USER).capture
		val override = edited.document.rigEdits.authoringJournal.last()
		assertEquals(GeneratedOverrides.OP, override.getValue("op").jsonPrimitive.content, "an edit of a part's generated cell is an override")
		note("  override generator: ${override.getValue("generator").jsonPrimitive.content}")
		verify("override", edited, split)
		val editedClosed = RigGeometryTools.geometry(edited.model.rig.puppet, "mesh", part.raw, key).points
		assertEquals(moved[0], editedClosed[0], 1e-4f)

		// A deeper blink regenerates the closed eye: untouched vertices follow it, the edited ones keep the user's place.
		val tuned = runtime.run("Blink depth", settings(buildJsonObject { putJsonObject("rigTuning") { put("blinkDepth", 70f) } }))
		verify("face tuning", tuned, split)
		val regenerated = RigGeometryTools.geometry(tuned.model.rig.puppet, "mesh", part.raw, key).points
		val fresh = WorkspacePreviewBuilder().build(tuned.document.copy(rigEdits = tuned.document.rigEdits.copy(
			authoringJournal = tuned.document.rigEdits.authoringJournal.filterNot(GeneratedOverrides::isOverride))))
		val generated = RigGeometryTools.geometry(fresh.rig.puppet, "mesh", part.raw, key).points
		assertTrue(maxDistance(generated, editedClosed) > 1e-3f, "the blink depth changed the closed eye")
		assertEquals(moved[0], regenerated[0], 1e-4f); assertEquals(moved[1], regenerated[1], 1e-4f)
		for (i in 4 until regenerated.size) assertEquals(generated[i], regenerated[i], 1e-4f, "vertex ${i / 2} follows the regenerated blink")
		val issues = tuned.model.rig.overrideIssues
		note("  override issues after the blink change: ${issues.map { it.describe() }}")
		assertTrue(issues.any { it.kind == GeneratedOverrideIssueKind.CONFLICT && it.id == part.raw }, "the edited vertices the generator also moved are a conflict")

		finish("eye", runtime, start, split, listOf(emptyMap(), closed, mapOf("ParamAngleX" to 20f, "ParamEyeLOpen" to 0.5f)))
	}

	protected fun mouthSplitByPolygonFollowsTheMouthGenerator() = runBlocking<Unit> {
		val runtime = runtime(); val start = runtime.capture()
		note("mouth (polygon split, mouth outline on)")
		val operation = polygon(MOUTH, 1021, listOf("Mouth left", "Mouth right"), listOf("mouth-a", "mouth-b"))
		val after = runtime.split(operation)
		val split = split(after.document)
		verify("split", after, split)
		val open = mapOf("ParamMouthOpenY" to 1f)
		for (part in split.parts) assertTrue(after.model.rig.puppet.drawables.single { it.id == part }.geometryGrid!!.axes.any { it.parameterId.raw == "ParamMouthOpenY" },
			"${part.raw} does not open")
		System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
		val v1 = try { builder.build(WorkspacePartitionEdits.apply(operation, start.document, start.model)) }
			finally { System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true") }
		// The halves open and smile as the whole mouth did (version 1 keeps the original's keyforms): each takes the
		// aperture of both, so the cut does not tear. The outline contour, which version 2 parts do not get, accounts
		// for what remains.
		for (pose in listOf(open, mapOf("ParamMouthForm" to 1f, "ParamMouthOpenY" to 1f), mapOf("ParamMouthForm" to -1f, "ParamMouthOpenY" to 0.5f))) {
			val deviation = split.parts.map { maxDistance(world(v1, pose).getValue(it), world(after.model, pose).getValue(it)) }
			note("  mouth at $pose, v2 parts vs v1 parts: ${deviation.map { "%.3f".format(it) }} px")
			assertTrue(deviation.all { it < 1.5f }, "the mouth halves move as the whole mouth at $pose: $deviation")
		}
		// The ribbons generated from the mouth are still there, keyed to the original mouth.
		val ribbons = after.model.analysis.layers.filter { it.source is MouthLipLayer }.map { it.source.id.raw }
		note("  lip ribbons after the split: $ribbons")
		assertEquals(start.model.analysis.layers.count { it.source is MouthLipLayer }, ribbons.size)
		changes(runtime, split, buildJsonObject { put("mouthSmile", 20f) }, FACE) { label, before, now ->
			if (label == "face tuning") {
				val form = mapOf("ParamMouthForm" to 1f)
				val change = split.parts.map { maxDistance(world(before.model, form).getValue(it), world(now.model, form).getValue(it)) }
				note("      mouth smile moved the smiling parts by $change px")
				assertTrue(change.all { it > 0.01f }, "the mouth smile regenerates the parts")
			}
		}
		finish("mouth", runtime, start, split, listOf(emptyMap(), open, mapOf("ParamMouthForm" to 1f, "ParamMouthOpenY" to 0.5f)))
	}

	protected fun depthSplitSlicesFollowTheBody() = runBlocking<Unit> {
		val runtime = runtime(); val start = runtime.capture()
		note("depth (topwear around the neck)")
		val source = drawable(start.model, TOPWEAR); val neck = drawable(start.model, NECK)
		val after = runtime.split(WorkspaceDocumentOperation("source_split_depth", buildJsonObject {
			put("source_id", source.id.raw); putJsonArray("middle_ids") { add(neck.id.raw) }
			put("front_layer_id", "top-front"); put("front_mesh_id", "TopFront"); put("glue_id", "TopWeld")
			put("back_layer_id", "top-back"); put("back_mesh_id", "TopBack"); putJsonArray("names") { add("Top back"); add("Top front") }
		}))
		val split = split(after.document)
		verify("split", after, split)
		fun slicesTogether(label: String, model: RigPreviewModel) {
			for (pose in listOf(emptyMap(), mapOf("ParamBodyAngleX" to 10f), mapOf("ParamAngleZ" to 15f))) {
				val positions = world(model, pose)
				assertTrue(maxDistance(positions.getValue(split.parts[0]), positions.getValue(split.parts[1])) < 1e-3f, "$label: the slices part at $pose")
			}
		}
		slicesTogether("split", after.model)
		val evaluator = CpuDeformationEvaluator()
		val drift = listOf(emptyMap(), mapOf("ParamBodyAngleX" to 10f)).maxOf { pose ->
			val values = pose.mapKeys { ParameterId(it.key) }
			maxDistance(evaluator.evaluate(start.model.rig.puppet, values).worldPositions.getValue(source.id),
				evaluator.evaluate(after.model.rig.puppet, values).worldPositions.getValue(split.parts[0]))
		}
		note("  slices vs the original at rest and BodyAngleX 10: $drift px")
		assertTrue(drift < 0.01f, "the slices sit where the original was: $drift")
		changes(runtime, split, buildJsonObject { put("faceTurn", 12f) }, FACE) { label, _, now ->
			// The part mesh settings rebuild one slice only: from there the slices are separate meshes.
			if (label in setOf("skeleton", "skeleton joint moved", "body strength", "face tuning")) slicesTogether(label, now.model)
		}
		finish("depth", runtime, start, split, listOf(emptyMap(), mapOf("ParamBodyAngleX" to 10f)))
	}

	/** The key of [id]'s geometry with every axis at its default, except [values]. */
	private fun key(model: PuppetModel, grid: KeyformGrid<*>?, values: Map<String, Float> = emptyMap()) = grid?.axes.orEmpty().associate { axis ->
		axis.parameterId.raw to (values[axis.parameterId.raw] ?: model.parameters.single { it.id == axis.parameterId }.default)
	}

	private fun geometryEdit(kind: String, id: String, key: Map<String, Float>, points: FloatArray) = buildJsonObject {
		put("op", "canvas_geometry"); put("kind", kind); put("id", id); put("preserve_image", false)
		put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) })); put("points", JsonArray(points.map(::JsonPrimitive)))
	}

	/** The split of [operation] on [document] written as version 1, built. */
	private suspend fun versionOne(operation: WorkspaceDocumentOperation, capture: WorkspaceCapture<RigPreviewModel>): RigPreviewModel {
		System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
		return try { builder.build(WorkspacePartitionEdits.apply(operation, capture.document, capture.model)) }
		finally { System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true") }
	}

	/**
	 * Edits of the original before the split - a user shape key, an edit of the closed eye (a generator cell), a user
	 * path, a user Glue and a vertex group - land on the parts as version 1 would put them, and keep doing so after
	 * the eye generator changes: the closed eye follows the new blink except where the user moved it.
	 */
	protected fun editsOnTheOriginalSurviveTheSplitAndRegeneration() = runBlocking<Unit> {
		val runtime = runtime(); val root = runtime.capture()
		val lash = drawable(root.model, EYELASH); val brow = drawable(root.model, "lyid:20")
		val puppet = root.model.rig.puppet
		val closedKey = key(puppet, lash.geometryGrid, mapOf("ParamEyeLOpen" to 0f))
		val closedPoints = RigGeometryTools.geometry(puppet, "mesh", lash.id.raw, closedKey).points
		val count = lash.mesh!!.vertexCount
		val commands = WorkspaceDocumentCommands(runtime)
		commands.executeJournal(root.projectId, root.state, "Closed lash", JsonArray(listOf(geometryEdit("mesh", lash.id.raw, closedKey,
			closedPoints.copyOf().also { it[0] += 0.03f; it[1] += 0.02f; it[2] += 0.03f }))), MutationAuthor.USER)
		val positions = lash.mesh!!.positions
		val authored = runtime.run("Author lash",
			WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
			WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
				add(buildJsonObject { put("op", "set"); put("target", "mesh:${lash.id.raw}"); putJsonObject("key") { put("Shape", 1); put("ParamEyeLOpen", 1) }
					putJsonObject("geometry") { put("positionDeltas", JsonArray(List(count * 2) { JsonPrimitive(kotlin.math.sin(it.toDouble()).toFloat() * 0.02f) })) } })
			} }),
			WorkspaceDocumentOperation("path_put", buildJsonObject { put("id", "LashPath"); put("target", "mesh:${lash.id.raw}"); putJsonArray("points") {
				add(buildJsonArray { add(positions[0]); add(positions[1]) }); add(buildJsonArray { add(positions[positions.size - 2]); add(positions.last()) })
			} }),
			WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "LashWeld"); put("mesh_a", lash.id.raw); put("mesh_b", brow.id.raw); put("distance", 64) }),
			WorkspaceDocumentOperation("vertex_group_update", buildJsonObject { put("target", "mesh:${lash.id.raw}"); put("name", "Pins"); put("kind", "pin"); put("rule", "fill"); put("value", 0.5f) }))
		note("edits before the split (eyelash)")
		assertTrue(authored.model.rig.puppet.glues.any { it.id == "LashWeld" }, "the Glue welds the lash to the brow")
		val operation = polygon(EYELASH, 1102, listOf("Lash inner", "Lash outer"), listOf("lash-in", "lash-out"))
		val v1 = versionOne(operation, authored)
		val after = runtime.split(operation)
		val split = split(after.document)
		verify("split", after, split)
		val poses = listOf(emptyMap(), mapOf("ParamEyeLOpen" to 0f), mapOf("Shape" to 1f), mapOf("Shape" to 1f, "ParamEyeLOpen" to 0.3f), mapOf("ParamAngleX" to 20f))
		val deviation = poses.map { pose -> split.parts.maxOf { maxDistance(world(v1, pose).getValue(it), world(after.model, pose).getValue(it)) } }
		note("  v2 vs v1 parts at ${poses}: $deviation px")
		assertTrue(deviation.all { it < 0.05f }, "the parts keep the edits made before the split: $deviation")
		val model = after.model.rig.puppet
		assertTrue(model.glues.any { it.id == "LashWeld" && (it.meshA in split.parts || it.meshB in split.parts) }, "the user Glue: ${model.glues.map { it.id }}")
		assertTrue(model.deformPaths.any { it.id.endsWith("LashPath") && it.drawableId in split.parts }, "the user path: ${model.deformPaths.map { it.id }}")
		assertTrue(split.parts.all { part -> model.vertexGroups.any { it.drawableId == part && it.name == "Pins" && it.weights.all { w -> abs(w - 0.5f) < 1e-4f } } },
			"the vertex group: ${model.vertexGroups.map { it.drawableId.raw to it.name }}")
		val record = ArtPrimitiveJournal.commands(after.document.rigEdits).single()
		val overrides = after.document.rigEdits.authoringJournal.dropWhile { it !== record }.drop(1).filter(GeneratedOverrides::isOverride)
		note("  overrides written with the record: ${overrides.map { it.getValue("target").jsonPrimitive.content + " " + it.getValue("key") }}")
		assertTrue(overrides.isNotEmpty(), "the closed-eye edit becomes an override of the parts")

		// A deeper blink: the parts' closed eye follows it except at the vertices the user moved; the shape key stays.
		val tuned = runtime.run("Blink depth", settings(buildJsonObject { putJsonObject("rigTuning") { put("blinkDepth", 70f) } }))
		verify("blink depth", tuned, split)
		val plain = WorkspacePreviewBuilder().build(tuned.document.copy(rigEdits = tuned.document.rigEdits.copy(
			authoringJournal = tuned.document.rigEdits.authoringJournal.filterNot(GeneratedOverrides::isOverride))))
		val closed = mapOf("ParamEyeLOpen" to 0f)
		var kept = 0; var followed = 0
		for (part in split.parts) {
			val a = world(tuned.model, closed).getValue(part); val b = world(plain, closed).getValue(part)
			for (v in 0 until a.size / 2) if (abs(a[v * 2] - b[v * 2]) + abs(a[v * 2 + 1] - b[v * 2 + 1]) > 1e-3f) kept++ else followed++
		}
		note("  after the blink change: $kept part vertices keep the user's closed eye, $followed follow the generator")
		assertTrue(kept in 1..8 && followed > kept, "kept $kept, followed $followed")
		assertTrue(world(after.model, closed).let { before -> split.parts.any { maxDistance(before.getValue(it), world(tuned.model, closed).getValue(it)) > 0.1f } },
			"the deeper blink reaches the parts")
		// The user shape key moves the parts after the blink change as it moves the unsplit original after the same change.
		val shape = mapOf("Shape" to 1f)
		val partsMoved = split.parts.maxOf { maxDistance(world(after.model, shape).getValue(it), world(tuned.model, shape).getValue(it)) }
		val unsplit = runtime.checkout(tuned.projectId, tuned.state, authored.historyHead)
		val unsplitTuned = runtime.run("Blink depth", settings(buildJsonObject { putJsonObject("rigTuning") { put("blinkDepth", 70f) } }))
		val originalMoved = maxDistance(world(unsplit.model, shape).getValue(lash.id), world(unsplitTuned.model, shape).getValue(lash.id))
		note("  at Shape 1 the blink change moved the parts $partsMoved px and the unsplit original $originalMoved px")
		assertTrue(abs(partsMoved - originalMoved) < 0.05f, "the shape key pose follows the blink as the original's does")
	}

	/**
	 * An edit of the original's parent at rest before the split: version 2 records the parts' rest canvas positions,
	 * so a part under the same generated parent must not take the parent's edit twice.
	 */
	protected fun aParentEditBeforeTheSplitIsNotAppliedTwice() = runBlocking<Unit> {
		val runtime = runtime(); val root = runtime.capture()
		val lash = drawable(root.model, EYELASH)
		val parent = root.model.rig.puppet.deformers.single { it.id == lash.parentDeformerId } as Deformer.Warp
		val restKey = key(root.model.rig.puppet, parent.geometryGrid)
		val points = RigGeometryTools.geometry(root.model.rig.puppet, "warp", parent.id.raw, restKey).points
		val width = points.indices.step(2).maxOf { points[it] } - points.indices.step(2).minOf { points[it] }
		val shifted = points.copyOf().also { for (i in it.indices step 2) it[i] += width * 0.05f }
		val edited = WorkspaceDocumentCommands(runtime).executeJournal(root.projectId, root.state, "Shift eye", JsonArray(listOf(
			geometryEdit("warp", parent.id.raw, restKey, shifted))), MutationAuthor.USER).capture
		val moved = maxDistance(world(root.model, emptyMap()).getValue(lash.id), world(edited.model, emptyMap()).getValue(lash.id))
		note("parent edit before the split: the lash moved $moved px with ${parent.id.raw}")
		assertTrue(moved > 1f)
		val operation = polygon(EYELASH, 1102, listOf("Lash inner", "Lash outer"), listOf("lash-in", "lash-out"))
		val v1 = versionOne(operation, edited)
		val after = runtime.split(operation)
		val split = split(after.document)
		verify("split", after, split)
		for (pose in listOf(emptyMap(), mapOf("ParamEyeLOpen" to 0f), mapOf("ParamAngleX" to 20f))) {
			val deviation = split.parts.map { maxDistance(world(v1, pose).getValue(it), world(after.model, pose).getValue(it)) }
			note("  v2 vs v1 parts at $pose: $deviation px")
			assertTrue(deviation.all { it < 0.05f }, "the parts sit where the edited parent put the original: $deviation at $pose")
		}
	}

	/**
	 * A version 1 split (written with the flag off) upgraded to version 2: its parts now follow a generator change, as
	 * the parts of a direct version 2 split of the same layer do.
	 */
	protected fun anUpgradedVersionOneSplitFollowsTheGenerator() = runBlocking<Unit> {
		val operation = polygon(EYELASH, 1102, listOf("Lash inner", "Lash outer"), listOf("lash-in", "lash-out"))
		val blink = settings(buildJsonObject { putJsonObject("rigTuning") { put("blinkDepth", 70f) } })
		val closed = mapOf("ParamEyeLOpen" to 0f)
		note("upgrade of a version 1 split")
		val legacy = runtime()
		System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "false")
		val v1 = try { legacy.split(operation) } finally { System.setProperty(ArtPrimitiveV2.FLAG_PROPERTY, "true") }
		assertFalse(ArtPrimitiveV2.isV2(ArtPrimitiveJournal.commands(v1.document.rigEdits).single()))
		// Version 1 parts keep the snapshot: a deeper blink does not reach them.
		val v1Parts = ArtPrimitiveJournal.primitives(ArtPrimitiveJournal.commands(v1.document.rigEdits).single()).map { DrawableId(it.getValue("id").jsonPrimitive.content) }
		val frozen = legacy.run("Blink depth", blink)
		val stuck = v1Parts.map { maxDistance(world(v1.model, closed).getValue(it), world(frozen.model, closed).getValue(it)) }
		note("  version 1 parts after the blink change moved $stuck px")
		legacy.checkout(frozen.projectId, frozen.state, v1.historyHead)
		val current = legacy.capture()
		val upgraded = WorkspaceSplitUpgradeCommands(legacy).execute(current.projectId, current.state, null, "Upgrade", MutationAuthor.USER).commit.capture
		val split = split(upgraded.document)
		verify("upgrade", upgraded, split)
		val tuned = legacy.run("Blink depth", blink)
		verify("blink after the upgrade", tuned, split)
		val moved = split.parts.map { maxDistance(world(upgraded.model, closed).getValue(it), world(tuned.model, closed).getValue(it)) }
		note("  upgraded parts after the blink change moved $moved px")
		assertTrue(moved.any { it > 0.1f }, "the upgraded parts follow the blink: $moved")
		val direct = runtime(); direct.split(operation)
		val reference = direct.run("Blink depth", blink)
		val deviation = split.parts.map { maxDistance(world(reference.model, closed).getValue(it), world(tuned.model, closed).getValue(it)) }
		note("  upgraded vs direct version 2 split after the blink change: $deviation px")
		assertTrue(deviation.all { it < 1e-3f }, "the upgraded split regenerates like a direct one: $deviation")
	}

	/** A split that cannot be version 2 writes version 1 and says why. */
	protected fun anOriginalWithAHandEditedMeshFallsBackToVersionOne() = runBlocking<Unit> {
		val runtime = runtime(); val root = runtime.capture()
		val lash = drawable(root.model, EYELASH)
		val mesh = lash.mesh!!
		val edited = WorkspaceDocumentCommands(runtime).executeJournal(root.projectId, root.state, "Subdivide", JsonArray(listOf(buildJsonObject {
			put("op", "canvas_topology"); put("id", lash.id.raw); put("action", "subdivide")
			put("vertices", JsonArray((0..2).map { JsonPrimitive(mesh.indices[it].toInt()) }))
		})), MutationAuthor.USER).capture
		assertTrue(drawable(edited.model, EYELASH).mesh!!.vertexCount > mesh.vertexCount)
		val operation = polygon(EYELASH, 1102, listOf("Lash inner", "Lash outer"), listOf("lash-in", "lash-out"))
		val before = runtime.capture()
		val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state, listOf(operation), "Split", MutationAuthor.USER)
		val record = ArtPrimitiveJournal.commands(result.commit.capture.document.rigEdits).single()
		assertEquals(1, record.getValue("v").jsonPrimitive.int)
		val reason = record.getValue(ArtPrimitiveV2.FALLBACK).jsonObject
		note("hand-edited original: version 1, ${reason}")
		assertEquals(WorkspaceArtPrimitives.REASON_CAPTURE, reason.getValue(ArtPrimitiveV2.FALLBACK_REASON).jsonPrimitive.content)
		val version = WorkspaceArtPrimitives.recordVersion(before.document.rigEdits, result.commit.capture.document.rigEdits)
		assertEquals(1, version.getValue(ArtPrimitiveV2.RECORD_VERSION).jsonPrimitive.int)
		assertEquals(WorkspaceArtPrimitives.REASON_CAPTURE, version.getValue(ArtPrimitiveV2.RECORD_VERSION_REASON).jsonPrimitive.content)
		assertEquals(hash(result.commit.capture.model), hash(WorkspacePreviewBuilder().build(result.commit.capture.document)))
	}
}

// One class per chain, so the forks of the test task run them side by side.
@org.junit.jupiter.api.Tag("slow") class ArtPrimitiveV2LegsMatrixTest : ArtPrimitiveV2Matrix() { @Test fun legs() = legsSplitPerSide() }
@org.junit.jupiter.api.Tag("slow") class ArtPrimitiveV2EyeMatrixTest : ArtPrimitiveV2Matrix() { @Test fun eye() = eyelashSplitByPolygonFollowsTheEyeGenerator() }
@org.junit.jupiter.api.Tag("slow") class ArtPrimitiveV2MouthMatrixTest : ArtPrimitiveV2Matrix() { @Test fun mouth() = mouthSplitByPolygonFollowsTheMouthGenerator() }
@org.junit.jupiter.api.Tag("slow") class ArtPrimitiveV2DepthMatrixTest : ArtPrimitiveV2Matrix() { @Test fun depth() = depthSplitSlicesFollowTheBody() }
@org.junit.jupiter.api.Tag("slow") class ArtPrimitiveV2EditsMatrixTest : ArtPrimitiveV2Matrix() {
	@Test fun editsBeforeTheSplit() = editsOnTheOriginalSurviveTheSplitAndRegeneration()
	@Test fun parentEditBeforeTheSplit() = aParentEditBeforeTheSplitIsNotAppliedTwice()
	@Test fun handEditedOriginal() = anOriginalWithAHandEditedMeshFallsBackToVersionOne()
	@Test fun upgradedVersionOneSplit() = anUpgradedVersionOneSplitFollowsTheGenerator()
}
