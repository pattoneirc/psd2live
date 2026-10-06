package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceCapture
import io.github.psd2live.application.WorkspaceDocumentCommands
import io.github.psd2live.application.WorkspaceDocumentEdits
import io.github.psd2live.application.WorkspacePaintRaster
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterCommands
import io.github.psd2live.application.WorkspaceRasterEdits
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.application.sourceLayerImage
import io.github.psd2live.core.*
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimAuthoring
import io.github.psd2live.core.sim.SimBaker
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceRevisions
import io.github.psd2live.project.WorkspaceStore
import io.github.psd2live.project.config
import io.github.psd2live.targets.cubism.Moc3Target
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private fun since(start: Long) = (System.nanoTime() - start) / 1e6

private inline fun <T> timed(block: () -> T): Pair<T, Double> {
	val start = System.nanoTime()
	return block() to since(start)
}

/** Mean of [runs] calls after one warm-up call. */
private inline fun mean(runs: Int, block: () -> Unit): Double {
	block()
	val start = System.nanoTime()
	repeat(runs) { block() }
	return since(start) / runs
}

private fun List<Double>.median(): Double = sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

/**
 * Stage-by-stage timings of the commit paths, measured from the test side by calling each stage the way the
 * application path does. Stage rows are means of repeated warm calls; commit rows are wall times through
 * [WorkspaceDocumentCommands] / [WorkspaceRasterCommands] with the history persisted after each commit, as the
 * desktop does. The native Cubism reload needs a GL context and is not measured; writing the runtime files it
 * loads ("materialize") is.
 */
internal class CommitBaseline(private val sample: Sample, private val out: File) {
	private val builder = WorkspacePreviewBuilder()
	private var lastRebuild = 0.0
	private lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
	private val storeRoot = File(out, "store").apply { deleteRecursively() }
	private val store = WorkspaceStore(storeRoot.toPath())
	private val materializeRoot = File(out, "materialize").apply { deleteRecursively(); mkdirs() }
	private val probe = PSD2LivePipeline()
	private val validate = PSD2LivePipeline::class.java.getDeclaredMethod("validateBundle", Moc3Sidecars.Bundle::class.java)
		.apply { isAccessible = true }
	private val report = LinkedHashMap<String, JsonElement>()
	private val table = StringBuilder()

	private fun row(name: String, value: Double, note: String = "") {
		table.appendLine("| $name | %.1f | $note |".format(value))
		println("baseline: $name = %.1f ms $note".format(value))
	}

	private fun section(title: String) {
		table.appendLine().appendLine("### $title").appendLine().appendLine("| stage | ms | note |").appendLine("| --- | ---: | --- |")
	}

	private fun stagesJson(stages: Map<String, Double>) = buildJsonObject { stages.forEach { (k, v) -> put(k, r1(v)) } }
	private fun r1(value: Double) = Math.round(value * 10) / 10.0

	fun run() {
		// Measure the preview path as the app runs it: test JVMs read preview bundles back, the app does not.
		System.setProperty("psd2live.validatePreviewBundles", "false")
		val commands: WorkspaceDocumentCommands
		runtime = WorkspaceRuntime({ document ->
			val start = System.nanoTime()
			builder.build(document, runtime.capture().model).also { lastRebuild = since(start) }
		})
		commands = WorkspaceDocumentCommands(runtime)
		val raster = WorkspaceRasterCommands(runtime)
		runBlocking {
			// Setup: import, auto skeleton, two swings and one baked simulation (scenario a).
			val (_, importMs) = timed {
				WorkspaceSourceImporter(runtime).importPsd(sample.path.toAbsolutePath(), null, runtime.state.value.state, initialConfig = PipelineConfig())
			}
			val plain = runtime.capture()
			val spec = SkeletonAutoBuilder.build(plain.model.analysis, plain.model.rig)
			val skeletalDocument = plain.document.copy(rigEdits = plain.document.rigEdits.copy(skeleton = spec))
			SkeletonRig.clearCache()
			val (skeletal, skeletalMs) = timed { builder.build(skeletalDocument) }
			val base = skeletal.baseRig.puppet
			val layers = skeletal.analysis.layers.associateBy { it.source.id.raw }
			fun meshes(tag: SemanticTag) = skeletal.rig.puppet.drawables.filter { d ->
				d.mesh != null && layers[skeletal.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == tag
			}
			val front = meshes(SemanticTag.FRONT_HAIR)
			val back = meshes(SemanticTag.BACK_HAIR).first()
			val accent = front.getOrNull(1) ?: listOf(SemanticTag.HEADWEAR, SemanticTag.NECKWEAR, SemanticTag.EARWEAR, SemanticTag.TOPWEAR)
				.firstNotNullOf { meshes(it).firstOrNull() }
			var overlay = skeletalDocument.rigEdits
			overlay = SwingAuthoring.put(overlay, overlay.applyTo(base), RigSwingEdit("front", "Front", listOf(front.first().id.raw), listOf(
				SwingMotion(SwingKind.VERTICAL, listOf("ParamSwingFront_1", "ParamSwingFront_2"), SwingShape(magnitude = 0.1f)),
				SwingMotion(SwingKind.LATERAL, listOf("ParamSwingFrontX"), SwingShape(magnitude = 0.2f, parallel = 0.8f)))))
			overlay = SwingAuthoring.put(overlay, overlay.applyTo(base), RigSwingEdit.single("accent", "Accent", SwingKind.LATERAL,
				listOf(accent.id.raw), listOf("ParamSwingAccent"), shape = SwingShape(magnitude = 0.2f, parallel = 0.7f)))
			val world = CpuDeformationEvaluator().evaluate(skeletal.rig.puppet, emptyMap()).worldPositions.getValue(back.id)
			val ys = (1 until world.size step 2).map { world[it] }
			val top = ys.max(); val bottom = ys.min()
			val pin = VertexGroup("pin", back.id, VertexGroupKind.PIN,
				FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
			overlay = overlay.copy(authoringJournal = overlay.authoringJournal + VertexGroupJournal.encode(pin))
			overlay = SimAuthoring.put(overlay, overlay.applyTo(base), RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw),
				modes = 1, keys = 3, inputs = RigSimEdit.defaultInputs(base.parameters.mapTo(HashSet()) { it.id.raw }, SimKind.HAIR)))
			val (bake, bakeMs) = timed { SimBaker.bake(SimAuthoring.unbakedModel(overlay, base, "back"), overlay.simEdits.single()) }
			overlay = SimAuthoring.withBake(overlay, "back", bake)
			val documentA = skeletalDocument.copy(rigEdits = overlay)
			val (modelA, buildAMs) = timed { builder.build(documentA) }
			val installed = runtime.install(runtime.state.value.state, plain.projectId, documentA, modelA, discardUnsaved = true)
			val (_, firstPersistMs) = timed { store.persistHistory(installed.projectId, runtime.history()) }
			report["sample"] = JsonPrimitive(sample.name)
			report["setup"] = buildJsonObject {
				put("import_ms", r1(importMs)); put("build_with_skeleton_cold_ms", r1(skeletalMs))
				put("simulation_bake_ms", r1(bakeMs)); put("build_scenario_a_ms", r1(buildAMs))
				put("persist_first_snapshot_ms", r1(firstPersistMs))
				put("drawables", modelA.rig.puppet.drawables.size); put("source_layers", documentA.source.layers.size)
				put("atlas_pages", modelA.atlas.pages.size); put("atlas_page_px", modelA.atlas.pages.first().image.width)
				put("swings", overlay.swingEdits.size); put("simulations", overlay.simEdits.size)
				put("swing_targets", JsonArray(listOf(front.first().id.raw, accent.id.raw).map(::JsonPrimitive)))
				put("simulation_target", back.id.raw)
			}
			table.appendLine("# Commit baseline (${sample.name})").appendLine()
			table.appendLine("drawables ${modelA.rig.puppet.drawables.size}, source layers ${documentA.source.layers.size}, " +
				"atlas ${modelA.atlas.pages.size}×${modelA.atlas.pages.first().image.width}px; import %.0f ms, cold skeleton build %.0f ms, simulation bake %.0f ms"
					.format(importMs, skeletalMs, bakeMs))

			// (a) Every stage of a full rebuild of the scenario document.
			val full = fullStages(documentA, modelA)
			report["full_rebuild_stages"] = stagesJson(full)
			section("(a) full rebuild stages: skeleton + 2 swings + baked simulation")
			full.forEach { (k, v) -> row(k, v) }

			// (b) Geometry commits on the largest mesh.
			val target = modelA.rig.puppet.drawables.filter { it.mesh != null }.maxBy { it.mesh!!.positions.size }
			fun geometry(model: RigPreviewModel, id: String, round: Int): JsonArray {
				val current = model.rig.puppet.drawables.single { it.id.raw == id }.mesh!!.positions.copyOf()
				// Translate the whole mesh back and forth: a real edit that never degenerates a triangle.
				val delta = if (round % 2 == 0) 0.3f else -0.3f
				for (i in current.indices) current[i] += delta
				return JsonArray(listOf(buildJsonObject {
					put("op", "canvas_geometry"); put("kind", "mesh"); put("id", id)
					put("key", JsonObject(emptyMap())); put("pose", JsonObject(emptyMap())); put("preserve_image", true)
					put("points", JsonArray(current.map(::JsonPrimitive)))
				}))
			}
			report["geometry_commit"] = scenario("(b) geometry commit (${target.id.raw}, ${target.mesh!!.positions.size / 2} vertices)", 8,
				commit = { before, round -> commands.executeJournal(before.projectId, before.state, "Geometry $round", geometry(before.model, target.id.raw, round), MutationAuthor.USER) },
				candidate = { before, round -> WorkspaceDocumentEdits.journal(before.document, before.model, geometry(before.model, target.id.raw, round)) },
				full = false)

			// (c) Paint a small square on a small layer; (d) replace a mid-size layer's image.
			val meshLayers = modelA.rig.puppet.drawables.filter { it.mesh != null }.mapNotNullTo(HashSet()) { modelA.rig.layerIdByDrawableId[it.id.raw] }
			val candidates = documentA.source.layers.filter { it.id.raw in meshLayers && it.raster.width * it.raster.height >= 32 * 32 }
				.sortedBy { it.raster.width * it.raster.height }
			val small = candidates.first()
			val middle = candidates[candidates.size / 2]
			fun paint(document: WorkspaceDocument, round: Int): WorkspacePaintRaster {
				val image = document.sourceLayerImage(small.id.raw)
				val cx = small.bounds.left + small.bounds.width / 2; val cy = small.bounds.top + small.bounds.height / 2
				for (y in cy - 3 until cy + 3) for (x in cx - 3 until cx + 3) image.setRGB(x, y, 0xff3366aa.toInt() + round * 0x10)
				return WorkspacePaintRaster.capture(small.id.raw, image, rebuildMesh = false)
			}
			report["paint_commit"] = scenario("(c) paint 6×6 px on a small layer (${small.id.raw}, ${small.raster.width}×${small.raster.height})", 5,
				commit = { before, round -> raster.commitRaster(before.projectId, before.state, paint(before.document, round), "Paint $round", MutationAuthor.USER).commit },
				candidate = { before, round -> WorkspaceRasterEdits.prepare(before.document, before.model, paint(before.document, round)) },
				full = true)
			fun replace(document: WorkspaceDocument, round: Int, rebuild: Boolean): WorkspacePaintRaster {
				val image = document.sourceLayerImage(middle.id.raw)
				val b = middle.bounds
				val copy = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
				copy.setRGB(0, 0, image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), 0, image.width)
				for (y in b.top until b.top + b.height) for (x in b.left until b.left + b.width) {
					// Mirror on rebuild (a new shape), recolour otherwise (the same shape, new pixels).
					val sx = if (rebuild) b.left + b.width - 1 - (x - b.left) else x
					val pixel = image.getRGB(sx, y)
					copy.setRGB(x, y, if (rebuild) pixel else (pixel and 0xff000000.toInt()) or ((pixel xor (0x203040 * (round + 1))) and 0xffffff))
				}
				return WorkspacePaintRaster.capture(middle.id.raw, copy, rebuildMesh = rebuild)
			}
			report["replace_image_commit"] = scenario("(d1) replace image, same shape (${middle.id.raw}, ${middle.raster.width}×${middle.raster.height})", 4,
				commit = { before, round -> raster.commitRaster(before.projectId, before.state, replace(before.document, round, false), "Replace $round", MutationAuthor.USER).commit },
				candidate = { before, round -> WorkspaceRasterEdits.prepare(before.document, before.model, replace(before.document, round, false)) },
				full = true)
			report["replace_image_rebuild_mesh_commit"] = scenario("(d2) replace image, mirrored shape with mesh rebuild (${middle.id.raw})", 2,
				commit = { before, round -> raster.commitRaster(before.projectId, before.state, replace(before.document, round, true), "Replace mesh $round", MutationAuthor.USER).commit },
				candidate = { before, round -> WorkspaceRasterEdits.prepare(before.document, before.model, replace(before.document, round, true)) },
				full = true)

			// (f) An unrelated topology edit, then the rebuilds that follow it (reopen, history checkout, paint).
			val unrelated = (meshes(SemanticTag.IRIDES) + meshes(SemanticTag.FACE_DETAIL) + meshes(SemanticTag.EYEBROW)).first()
			val beforeTopology = runtime.capture()
			val mesh = beforeTopology.model.rig.puppet.drawables.single { it.id == unrelated.id }.mesh!!
			val topology = JsonArray(listOf(buildJsonObject {
				put("op", "canvas_topology"); put("id", unrelated.id.raw); put("action", "subdivide")
				put("vertices", JsonArray((0..2).map { JsonPrimitive(mesh.indices[it].toInt()) }))
			}))
			val hits0 = SkeletonRig.cacheHits; val misses0 = SkeletonRig.cacheMisses
			val (_, topologyMs) = timed { commands.executeJournal(beforeTopology.projectId, beforeTopology.state, "Topology", topology, MutationAuthor.USER) }
			val commitHits = SkeletonRig.cacheHits - hits0; val commitMisses = SkeletonRig.cacheMisses - misses0
			val afterTopology = runtime.capture()
			suspend fun reopen(document: WorkspaceDocument): Triple<Double, Int, Int> {
				val h = SkeletonRig.cacheHits; val m = SkeletonRig.cacheMisses
				val ms = timed { WorkspacePreviewBuilder().build(document) }.second
				return Triple(ms, SkeletonRig.cacheHits - h, SkeletonRig.cacheMisses - m)
			}
			val reopenBefore = reopen(beforeTopology.document)
			val reopenAfter = reopen(afterTopology.document)
			val reopenAgain = reopen(afterTopology.document)
			report["unrelated_topology"] = buildJsonObject {
				put("mesh", unrelated.id.raw); put("commit_ms", r1(topologyMs))
				put("commit_skeleton_hits", commitHits); put("commit_skeleton_misses", commitMisses)
				for ((name, value) in listOf("full_rebuild_before_edit" to reopenBefore, "full_rebuild_after_edit" to reopenAfter,
						"full_rebuild_after_edit_again" to reopenAgain)) put(name, buildJsonObject {
					put("ms", r1(value.first)); put("skeleton_hits", value.second); put("skeleton_misses", value.third)
				})
			}
			section("(f) unrelated canvas_topology on ${unrelated.id.raw}")
			row("commit (fast path)", topologyMs, "skeleton hits $commitHits, misses $commitMisses")
			row("full rebuild of the document before the edit", reopenBefore.first, "skeleton hits ${reopenBefore.second}, misses ${reopenBefore.third}")
			row("full rebuild after the edit (reopen / checkout / paint)", reopenAfter.first, "skeleton hits ${reopenAfter.second}, misses ${reopenAfter.third}")
			row("same rebuild again", reopenAgain.first, "skeleton hits ${reopenAgain.second}, misses ${reopenAgain.third}")

			// (e) Grow the journal by 200 geometry commits.
			// The (b) mesh: others can fail the geometry gate at sampled poses once moved under skinning.
			val cycle = listOf(target.id.raw)
			val growth = buildJsonArray {
				val totals = ArrayList<Double>(); val rebuilds = ArrayList<Double>(); val persists = ArrayList<Double>()
				for (step in 1..200) {
					val before = runtime.capture()
					val (_, total) = timed { commands.executeJournal(before.projectId, before.state, "Grow $step", geometry(before.model, cycle[step % cycle.size], step), MutationAuthor.USER) }
					totals += total; rebuilds += lastRebuild
					persists += timed { store.persistHistory(before.projectId, runtime.history()) }.second
					if (step in setOf(1, 50, 200)) {
						val after = runtime.capture()
						val journal = after.document.rigEdits.authoringJournal
						val window = totals.takeLast(minOf(5, totals.size))
						add(buildJsonObject {
							put("appended", step); put("journal_entries", journal.size)
							put("journal_chars", JsonArray(journal).toString().length)
							put("rig_edits_chars", after.document.rigEdits.toString().length)
							put("commit_ms_median_last5", r1(window.median()))
							put("rebuild_ms_median_last5", r1(rebuilds.takeLast(window.size).median()))
							put("persist_ms_median_last5", r1(persists.takeLast(window.size).median()))
							put("revision_hash_ms", r1(mean(5) { WorkspaceRevisions.of(after.document) }))
							put("replay_ms", r1(mean(3) { after.model.baseRig.withRigEdits(after.model.config.rigEdits, after.model.config.layerVisibility, after.model.config.drawOrderOverrides) }))
							put("replay_no_checkpoints_ms", r1(withoutCheckpoints { mean(3) { after.model.baseRig.withRigEdits(after.model.config.rigEdits, after.model.config.layerVisibility, after.model.config.drawOrderOverrides) } }))
							put("replay_appended_ms", r1(appendedReplay(after.model.baseRig, after.model.config)))
							put("snapshot_store_bytes", File(storeRoot, after.projectId).walk().filter { it.isFile }.sumOf { it.length() })
						})
					}
				}
			}
			report["journal_growth"] = growth
			table.appendLine().appendLine("### (e) journal growth: appended canvas_geometry commits").appendLine()
			table.appendLine("| appended | entries | journal chars | commit ms | rebuild ms | replay ms | replay ms, no checkpoints | replay ms, one appended | revision hash ms | persist ms | store bytes |")
			table.appendLine("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
			growth.forEach { e ->
				val o = e.jsonObject
				fun v(k: String) = o.getValue(k).jsonPrimitive.content
				table.appendLine("| ${v("appended")} | ${v("journal_entries")} | ${v("journal_chars")} | ${v("commit_ms_median_last5")} | ${v("rebuild_ms_median_last5")} | ${v("replay_ms")} | ${v("replay_no_checkpoints_ms")} | ${v("replay_appended_ms")} | ${v("revision_hash_ms")} | ${v("persist_ms_median_last5")} | ${v("snapshot_store_bytes")} |")
			}
		}
		report["not_measured"] = JsonArray(listOf(JsonPrimitive("native Cubism model reload (CubismSdkPreviewSession.load): needs the native runtime and a GL context; only writing its runtime files is timed as materialize")))
		report["machine"] = buildJsonObject {
			put("os", System.getProperty("os.name")); put("cores", Runtime.getRuntime().availableProcessors())
			put("java", System.getProperty("java.version")); put("max_heap_mb", Runtime.getRuntime().maxMemory() / (1 shl 20))
		}
		val pretty = Json { prettyPrint = true }
		File(out, "baseline.json").writeText(pretty.encodeToString(JsonObject.serializer(), JsonObject(report)))
		File(out, "baseline.md").writeText(table.toString())
		println(table)
	}

	/**
	 * Commits [runs] times and splits the last one: the candidate edit, mesh normalization, the rebuild the
	 * runtime ran, geometry safety, revision hashes (prepareDraft and commitPrepared each hash both sides) and
	 * persistence, then the rebuild's own stages on the committed document.
	 */
	private suspend fun scenario(
		title: String, runs: Int,
		commit: suspend (WorkspaceCapture<RigPreviewModel>, Int) -> Any,
		candidate: (WorkspaceCapture<RigPreviewModel>, Int) -> WorkspaceDocument,
		full: Boolean,
	): JsonObject {
		val totals = ArrayList<Double>(); val rebuilds = ArrayList<Double>(); val persists = ArrayList<Double>()
		val skeleton = ArrayList<String>()
		lateinit var before: WorkspaceCapture<RigPreviewModel>
		for (round in 0 until runs) {
			before = runtime.capture()
			val hits = SkeletonRig.cacheHits; val misses = SkeletonRig.cacheMisses
			totals += timed { commit(before, round) }.second
			rebuilds += lastRebuild
			skeleton += "${SkeletonRig.cacheHits - hits}/${SkeletonRig.cacheMisses - misses}"
			persists += timed { store.persistHistory(before.projectId, runtime.history()) }.second
		}
		val after = runtime.capture()
		check(after.document != before.document) { "$title did not change the document" }
		val split = LinkedHashMap<String, Double>()
		val (draft, candidateMs) = timed { candidate(before, runs - 1) }
		split["candidate edit (journal compile / paint prepare)"] = candidateMs
		split["normalizeMeshEdits"] = timed { builder.normalizeMeshEdits(draft, before.model) }.second
		split["rebuild (runtime, last commit)"] = lastRebuild
		split["geometry safety"] = timed { GeometrySafetyEvaluator.evaluate(before.model.rig.puppet, after.model.rig.puppet, blockFoldovers = false,
			scope = io.github.psd2live.application.WorkspaceGeometrySafety.scope(before.document, after.document)) }.second
		val revision = mean(5) { WorkspaceRevisions.of(after.document) }
		split["revision hash ×4 (warm raster digests)"] = revision * 4
		split["persist history"] = persists.last()
		split["materialize runtime files"] = materialize(after.model)
		val stages = if (full) fullStages(after.document, after.model) else fastStages(after.model)
		section("$title — $runs commits")
		row("commit wall time, median", totals.median(), "all: " + totals.joinToString { "%.0f".format(it) })
		row("rebuild inside commit, median", rebuilds.median(), "skeleton hits/misses per commit: " + skeleton.joinToString())
		split.forEach { (k, v) -> row(k, v) }
		stages.forEach { (k, v) -> row("  rebuild stage: $k", v) }
		return buildJsonObject {
			put("title", title)
			put("commit_ms", JsonArray(totals.map { JsonPrimitive(r1(it)) }))
			put("commit_ms_median", r1(totals.median()))
			put("rebuild_ms", JsonArray(rebuilds.map { JsonPrimitive(r1(it)) }))
			put("skeleton_hits_misses", JsonArray(skeleton.map(::JsonPrimitive)))
			put("split", stagesJson(split))
			put("rebuild_stages", stagesJson(stages))
			put("revision_hash_ms", r1(revision))
		}
	}

	/** The fast path: replay onto the cached base rig, then compile and validate the runtime bundle. */
	private fun fastStages(model: RigPreviewModel): LinkedHashMap<String, Double> {
		val stages = replayStages(model.baseRig, model.config)
		stages.putAll(bundleStages(model))
		return stages
	}

	/** buildPreview's stages, in its order: analysis, atlas, base rig with the skeleton, replay, bundle. */
	private fun fullStages(document: WorkspaceDocument, model: RigPreviewModel): LinkedHashMap<String, Double> {
		val stages = LinkedHashMap<String, Double>()
		val config = document.config()
		stages["settings decode"] = mean(5) { document.config() }
		lateinit var analysis: PipelineAnalysis
		stages["analysis"] = mean(2) { analysis = RigGenerationSource.analyze(document.source, config) }
		val baseline = RigGenerationBaseline.restore(MeshGenerationBaseline.restore(config))
		lateinit var analyses: RigGenerationSource.Analyses
		stages["analysis prepare (geometry/texture inputs)"] = mean(2) { analyses = RigGenerationSource.prepare(analysis, baseline, config) }
		lateinit var atlas: PackedAtlas
		stages["atlas layout (page recipe cache warm)"] = mean(2) { atlas = AtlasLayout.pack(analyses.textures.layers, config.atlasSize, config.texturePadding, config.textureUpscale) }
		stages["  preview PNG strips, every page from scratch"] = mean(2) {
			atlas.pages.forEach { AtlasPagePng.assemble(it.image.width, it.image.height, AtlasPagePng.strips(it.image, null, null)) }
		}
		stages["  canonical PNG (exports only)"] = mean(2) { atlas.pages.forEach { ImageIO.write(it.image, "png", ByteArrayOutputStream()) } }
		val geometryAtlas = if (config.generationSource == null) atlas
			else AtlasLayout.pack(analyses.geometry.layers, config.atlasSize, config.texturePadding, config.textureUpscale)
		val generation = baseline.copy(parentOverrides = config.parentOverrides)
		val plain = generation.copy(rigEdits = generation.rigEdits.copy(skeleton = null))
		val noSkeleton = mean(2) { RigBuilder.build(analyses.geometry, geometryAtlas, plain, probe.meshCache) }
		lateinit var built: BuiltRig
		val hit = mean(2) { built = RigBuilder.build(analyses.geometry, geometryAtlas, generation, probe.meshCache) }
		SkeletonRig.clearCache()
		val miss = timed { RigBuilder.build(analyses.geometry, geometryAtlas, generation, probe.meshCache) }.second
		stages["base rig without skeleton (mesh cache warm)"] = noSkeleton
		stages["skeleton, cache hit (key hash + lookup)"] = hit - noSkeleton
		stages["skeleton, cache miss (bake)"] = miss - noSkeleton
		if (config.generationSource != null) stages["repack geometry rig onto texture atlas"] =
			mean(2) { RigGenerationSource.repack(built, analyses.geometry, geometryAtlas, analyses.textures, atlas) }
		stages.putAll(replayStages(model.baseRig, config))
		stages.putAll(bundleStages(model))
		stages["revision hash (warm raster digests)"] = mean(5) { WorkspaceRevisions.of(document) }
		stages["revision hash (cold raster digests)"] = mean(2) {
			WorkspaceRevisions.of(document.copy(source = io.github.psd2live.project.WorkspaceSourceArt(document.source.widthPx, document.source.heightPx,
				document.source.layers.map { l -> io.github.psd2live.project.WorkspaceSourceLayer(l.id, l.name, l.groupPath, l.kind, l.visible, l.order, l.bounds,
					l.opacity, l.clipped, l.blend, l.channelMask, org.umamo.format.art.LayerRaster(l.raster.width, l.raster.height, l.raster.rgba.copyOf()),
					null, null, false) }, document.source.groups)))
		}
		stages["materialize runtime files"] = materialize(model)
		stages["whole buildPreview (fresh builder, skeleton cached)"] = timed { runBlocking { WorkspacePreviewBuilder().build(document) } }.second
		return stages
	}

	private fun replayStages(base: BuiltRig, config: PipelineConfig): LinkedHashMap<String, Double> {
		val stages = LinkedHashMap<String, Double>()
		val overlay = config.rigEdits
		stages["replay total (withRigEdits)"] = mean(3) { base.withRigEdits(overlay, config.layerVisibility, config.drawOrderOverrides) }
		stages["replay total, no checkpoints or generator reuse"] = withoutCheckpoints { mean(3) { base.withRigEdits(overlay, config.layerVisibility, config.drawOrderOverrides) } }
		stages["replay total, last entry appended"] = appendedReplay(base, config)
		lateinit var replayed: PuppetModel
		stages["  journal + static edits"] = mean(3) { replayed = overlay.copy(swingEdits = emptyList(), simEdits = emptyList()).applyTo(base.puppet) }
		lateinit var generated: PuppetModel
		stages["  swings + simulation write-back"] = mean(3) { generated = DocumentGenerators.generate(replayed, overlay) }
		stages["  generated overrides"] = mean(5) { GeneratedOverrides.applyAll(generated, overlay.authoringJournal) }
		return stages
	}

	private fun <T> withoutCheckpoints(block: () -> T): T {
		val was = ReplayCheckpoints.enabled
		ReplayCheckpoints.enabled = false
		try { return block() } finally { ReplayCheckpoints.enabled = was }
	}

	/** The replay of a journal whose prefix (all but the last entry) was replayed just before. */
	private fun appendedReplay(base: BuiltRig, config: PipelineConfig): Double {
		val overlay = config.rigEdits
		if (overlay.authoringJournal.isEmpty()) return 0.0
		val prefix = overlay.copy(authoringJournal = overlay.authoringJournal.dropLast(1))
		val runs = (0 until 3).map {
			ReplayCheckpoints.clear()
			base.withRigEdits(prefix, config.layerVisibility, config.drawOrderOverrides)
			timed { base.withRigEdits(overlay, config.layerVisibility, config.drawOrderOverrides) }.second
		}
		return runs.average()
	}

	private fun bundleStages(model: RigPreviewModel): LinkedHashMap<String, Double> {
		val stages = LinkedHashMap<String, Double>()
		lateinit var ir: io.github.psd2live.format.model.RigIR
		stages["IR compile"] = mean(3) { ir = RigIrCompiler.compile(model.analysis, model.atlas, model.rig, model.config) }
		lateinit var bundle: Moc3Sidecars.Bundle
		stages["moc3 bundle"] = mean(3) { bundle = Moc3Target.bundle(ir, probe.moc3ExportOptions("psd2live-preview", model.config)) }
		stages["validateBundle"] = mean(5) { validate.invoke(probe, bundle) }
		return stages
	}

	/** What the preview session writes before the native reload: every runtime file into a fresh folder. */
	private fun materialize(model: RigPreviewModel): Double = mean(3) {
		val folder = File(materializeRoot, System.nanoTime().toString()).apply { mkdirs() }
		for (asset in model.runtimeBundle.assets) File(folder, asset.path).apply { parentFile.mkdirs() }.writeBytes(asset.bytes)
	}
}
