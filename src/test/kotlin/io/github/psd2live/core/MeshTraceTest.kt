package io.github.psd2live.core

import io.github.psd2live.core.mesh.Point
import io.github.psd2live.core.mesh.hasSelfIntersection
import io.github.psd2live.core.mesh.loopsTouch
import io.github.psd2live.core.mesh.pointOnSegment
import io.github.psd2live.core.mesh.segmentsProperlyIntersect
import io.github.psd2live.project.LayerCanvasRect
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import java.util.concurrent.CompletableFuture
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What a layer's mesh is traced from ([MeshTrace]) and the generator's faster paths giving the same meshes. */
class MeshTraceTest {
	private val settings = MeshSettings(maxEdgeDistance = 12f, interiorDensity = 18f)

	private fun layer(bounds: LayerBounds, raster: LayerRaster, rect: LayerCanvasRect? = null) = WorkspaceSourceLayer(
		LayerId("eye"), "eye", "", SourceLayerKind.Raster, true, 0, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		raster, null, null, false, rect)

	private fun raster(size: Int, inside: (Double, Double) -> Boolean) = LayerRaster(size, size, ByteArray(size * size * 4).also { rgba ->
		for (y in 0 until size) for (x in 0 until size) if (inside((x + 0.5) / size, (y + 0.5) / size)) rgba[(y * size + x) * 4 + 3] = -1
	})

	/** An eye with lashes a sixteenth of a canvas unit wide at 256 pixels on 32 canvas units. */
	private fun lashedEye(u: Double, v: Double): Boolean {
		val dx = (u - 0.5) / 0.38; val dy = (v - 0.62) / 0.22
		if (dx * dx + dy * dy <= 1.0) return true
		return (0 until 5).any { k ->
			val x = 0.25 + 0.125 * k
			kotlin.math.abs(u - x) < 1.0 / 512 && v in 0.12..0.55
		}
	}

	private val dense = layer(LayerBounds(10, 20, 32, 32), raster(256, ::lashedEye))

	private fun mesh(layer: WorkspaceSourceLayer, trace: MeshTrace, cache: PreviewMeshCache? = null) =
		MeshResolution.mesh(MeshResolution.input(CanvasDensity.canvasLayer(layer), trace, 1f), 8, settings, cache)

	/** Opaque texture pixels of [layer] whose centre lies in no triangle of [mesh] (canvas units from its bounds). */
	private fun uncovered(layer: WorkspaceSourceLayer, mesh: AdaptiveMeshGenerator.Result): Int {
		val raster = layer.raster
		val space = LayerSpace.of(layer)
		val covered = BooleanArray(raster.width * raster.height)
		val p = mesh.positions
		fun x(i: Int) = (p[i * 2] + layer.bounds.left - space.left) * space.scaleX.toDouble()
		fun y(i: Int) = (p[i * 2 + 1] + layer.bounds.top - space.top) * space.scaleY.toDouble()
		for (t in mesh.indices.indices step 3) {
			val (a, b, c) = Triple(mesh.indices[t], mesh.indices[t + 1], mesh.indices[t + 2])
			val d = (x(b) - x(a)) * (y(c) - y(a)) - (y(b) - y(a)) * (x(c) - x(a))
			if (d == 0.0) continue
			for (py in max(0, minOf(y(a), y(b), y(c)).toInt() - 1)..min(raster.height - 1, maxOf(y(a), y(b), y(c)).toInt() + 1))
				for (px in max(0, minOf(x(a), x(b), x(c)).toInt() - 1)..min(raster.width - 1, maxOf(x(a), x(b), x(c)).toInt() + 1)) {
					val cx = px + 0.5; val cy = py + 0.5
					val w0 = ((x(b) - cx) * (y(c) - cy) - (y(b) - cy) * (x(c) - cx)) / d
					val w1 = ((x(c) - cx) * (y(a) - cy) - (y(c) - cy) * (x(a) - cx)) / d
					if (w0 >= -1e-6 && w1 >= -1e-6 && w0 + w1 <= 1 + 1e-6) covered[py * raster.width + px] = true
				}
		}
		return covered.indices.count { (raster.rgba[it * 4 + 3].toInt() and 0xff) >= 8 && !covered[it] }
	}

	@Test fun theTextureTraceKeepsStrokesThinnerThanACanvasUnitInsideTheMesh() {
		val canvas = assertNotNull(mesh(dense, MeshTrace.CANVAS))
		val texture = assertNotNull(mesh(dense, MeshTrace.TEXTURE))
		// Averaged to canvas resolution the lashes fade below the threshold and fall outside the mesh. Traced from
		// the texture they stay inside it; what is left out is a sliver of a long chord, under one canvas unit
		// (64 texture pixels) in all.
		val cut = uncovered(dense, canvas)
		val missed = uncovered(dense, texture)
		assertTrue(cut > 100, "the canvas trace is expected to cut the lashes: $cut")
		assertTrue(missed <= 64 && missed * 5 < cut, "$missed opaque texture pixels outside the texture-traced mesh")
		// Vertex density still follows the mesh settings in canvas units, not the texture's resolution.
		val vertices = texture.positions.size / 2
		assertTrue(vertices in 8..160, "$vertices vertices for a 32-unit eye")
		val p = texture.positions
		for (i in p.indices step 2) assertTrue(p[i] in -2f..34f && p[i + 1] in -2f..34f, "vertex (${p[i]}, ${p[i + 1]}) left the layer")
	}

	@Test fun theTextureTraceIsDeterministicAndSharedThroughTheCache() {
		val first = assertNotNull(mesh(dense, MeshTrace.TEXTURE))
		val cache = PreviewMeshCache()
		val tasks = List(4) { CompletableFuture.supplyAsync { mesh(dense, MeshTrace.TEXTURE, cache) } }
		for (task in tasks) {
			val again = assertNotNull(task.get())
			assertContentEquals(first.positions, again.positions)
			assertContentEquals(first.indices, again.indices)
		}
		val input = MeshResolution.input(CanvasDensity.canvasLayer(dense), MeshTrace.TEXTURE, 1f)
		cache.prefetch(listOf(input to settings, input to settings.copy(maxEdgeDistance = 16f)), 8)
		assertContentEquals(first.positions, assertNotNull(mesh(dense, MeshTrace.TEXTURE, cache)).positions)
	}

	@Test fun aLayerAtCanvasResolutionMeshesAlikeUnderEitherTrace() {
		val plain = layer(LayerBounds(0, 0, 48, 48), raster(48) { u, v -> (u - 0.5) * (u - 0.5) + (v - 0.5) * (v - 0.5) < 0.18 })
		val canvas = assertNotNull(mesh(plain, MeshTrace.CANVAS))
		val texture = assertNotNull(mesh(plain, MeshTrace.TEXTURE))
		assertContentEquals(canvas.positions, texture.positions)
		assertContentEquals(canvas.indices, texture.indices)
		assertEquals(1f, MeshResolution.detail(48, 48, 1f))
	}

	@Test fun aFractionalRectangleIsTracedFromTheTextureLaidOverItsBounds() {
		// Two texture pixels per canvas unit on a rectangle half a unit inside its bounds.
		val placed = layer(LayerBounds(10, 20, 33, 33), raster(64, ::lashedEye), LayerCanvasRect(10.5f, 20.5f, 32f, 32f))
		val aligned = CanvasDensity.alignedRaster(placed)
		assertEquals(66, aligned.width)
		// Its first column and row sit outside the rectangle, the texture's first pixel one pixel in.
		assertEquals(0, aligned.rgba[3].toInt())
		val pinned = layer(placed.bounds, aligned)
		assertContentEquals(aligned.rgba, CanvasDensity.alignedRaster(pinned).rgba)
		val a = assertNotNull(mesh(placed, MeshTrace.TEXTURE)); val b = assertNotNull(mesh(pinned, MeshTrace.TEXTURE))
		assertContentEquals(a.positions, b.positions, "a pinned copy meshes like the layer it pins")
	}

	@Test fun detailStaysWithinItsBudget() {
		assertEquals(MeshResolution.MAX_DETAIL, MeshResolution.detail(1024, 1024, 16f))
		assertEquals(2.5f, MeshResolution.detail(500, 500, 2.5f))
		// A layer as large as the working budget in mesh units is traced at the mesh unit.
		assertEquals(1f, MeshResolution.detail(3072, 3072, 3f))
		val partial = MeshResolution.detail(1536, 1536, 2f)
		assertTrue(partial > 1f && partial < 2f && (1536 / 2f * partial).let { it * it } <= MeshResolution.MAX_WORKING_PIXELS * 1.01, "$partial")
	}

	@Test fun settingsSavedBeforeTheTraceKeepTheCanvasTrace() {
		val legacy = WorkspaceSettingsCodec.encode(PipelineConfig(meshTrace = MeshTrace.CANVAS))
		assertFalse("meshTrace" in legacy, "a canvas-traced project writes its settings as before")
		assertEquals(MeshTrace.CANVAS, WorkspaceSettingsCodec.decode(legacy).meshTrace)
		val fresh = WorkspaceSettingsCodec.encode(PipelineConfig())
		assertEquals(JsonPrimitive("TEXTURE"), fresh["meshTrace"])
		assertEquals(MeshTrace.TEXTURE, WorkspaceSettingsCodec.decode(fresh).meshTrace)
		assertEquals(MeshTrace.TEXTURE, WorkspaceSettingsCodec.decode(JsonObject(emptyMap())).meshTrace)

		// A mesh baseline records the trace its generator used, and one written before reads as the canvas trace.
		for (trace in MeshTrace.entries) {
			val config = PipelineConfig(meshTrace = trace, meshMaxEdgeDistance = 9f)
			val overlay = MeshGenerationBaseline.preserve(RigEditOverlay.Empty, config)
			val restored = MeshGenerationBaseline.restore(PipelineConfig(rigEdits = overlay))
			assertEquals(trace, restored.meshTrace); assertEquals(9f, restored.meshMaxEdgeDistance)
		}
	}

	@Test fun gridPredicatesAnswerLikeEveryPair() {
		val random = Random(7)
		fun loop(n: Int, cx: Double, cy: Double, jitter: Double) = List(n) { i ->
			val a = Math.PI * 2 * i / n
			val r = 10 + random.nextDouble() * jitter
			Point(Math.round((cx + r * kotlin.math.cos(a)) * 4) / 4.0, Math.round((cy + r * kotlin.math.sin(a)) * 4) / 4.0)
		}
		fun touchAllPairs(a: List<Point>, b: List<Point>) = a.indices.any { i -> b.indices.any { j ->
			val p = a[i]; val q = a[(i + 1) % a.size]; val r = b[j]; val s = b[(j + 1) % b.size]
			segmentsProperlyIntersect(p, q, r, s) || pointOnSegment(p, r, s) || pointOnSegment(q, r, s) || pointOnSegment(r, p, q) || pointOnSegment(s, p, q)
		} }
		fun crossesAllPairs(loop: List<Point>): Boolean {
			for (first in loop.indices) for (second in first + 1 until loop.size) {
				val firstNext = (first + 1) % loop.size; val secondNext = (second + 1) % loop.size
				if (firstNext == second || secondNext == first || (first == 0 && secondNext == 0)) continue
				if (segmentsProperlyIntersect(loop[first], loop[firstNext], loop[second], loop[secondNext])) return true
			}
			return false
		}
		var touching = 0; var crossing = 0
		repeat(300) {
			val a = loop(6 + random.nextInt(60), 0.0, 0.0, random.nextDouble() * 12)
			val b = loop(6 + random.nextInt(60), random.nextDouble() * 30 - 15, random.nextDouble() * 30 - 15, random.nextDouble() * 6)
			val expected = touchAllPairs(a, b)
			assertEquals(expected, loopsTouch(a, b)); if (expected) touching++
			// Swapping two vertices of a star-shaped loop usually makes it cross itself.
			val tangled = a.toMutableList().also { if (random.nextBoolean()) java.util.Collections.swap(it, random.nextInt(it.size), random.nextInt(it.size)) }
			val self = crossesAllPairs(tangled)
			assertEquals(self, hasSelfIntersection(tangled)); if (self) crossing++
		}
		assertTrue(touching in 1..299 && crossing in 1..299, "both answers occur: $touching touching, $crossing crossing")
	}

	@Test fun aMeshRebuildMeshesADenseLayerInCanvasUnits() {
		val body = LayerRaster(40, 40, ByteArray(40 * 40 * 4) { if (it % 4 == 3) -1 else 90 })
		val source = WorkspaceSourceArt(128, 96, listOf(
			layer(LayerBounds(10, 20, 40, 40), body).copy(id = LayerId("body"), name = "body"),
			layer(LayerBounds(70, 30, 32, 32), raster(256, ::lashedEye)).copy(order = 1),
		), emptyList())
		val pipeline = PSD2LivePipeline()
		val config = PipelineConfig(atlasSize = 1024, meshOnly = true, generatePhysics = false)
		val model = pipeline.buildPreview(source, config)
		val rebuilt = pipeline.rebuildPreview(model, config.copy(meshMaxEdgeDistance = 30f, meshInteriorDensity = 60f))
		fun eye(preview: RigPreviewModel) = org.umamo.render.restMeshesToCanvasSpace(preview.rig.puppet).drawables
			.single { preview.rig.layerIdByDrawableId[it.id.raw] == "eye" }.mesh!!.positions
		val canvas = eye(rebuilt)
		assertFalse(canvas.contentEquals(eye(model)), "the rebuild meshes the eye again")
		for (i in canvas.indices step 2) assertTrue(canvas[i] in 66f..106f && canvas[i + 1] in 26f..66f,
			"rebuilt vertex (${canvas[i]}, ${canvas[i + 1]}) is outside the eye's canvas rectangle")
	}
}
