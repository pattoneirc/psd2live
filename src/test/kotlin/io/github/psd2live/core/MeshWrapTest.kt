package io.github.psd2live.core

import io.github.psd2live.application.mergeProjectSettings
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Wrap topology ([MeshWrap], [MeshSettings.wrap]): closing the traced mask merges fine protrusions into one envelope. */
class MeshWrapTest {
	private val settings = MeshSettings(maxEdgeDistance = 12f, interiorDensity = 18f)

	private fun layer(id: String, bounds: LayerBounds, raster: LayerRaster) = WorkspaceSourceLayer(
		LayerId(id), id, "", SourceLayerKind.Raster, true, 0, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
		raster, null, null, false)

	private fun raster(size: Int, inside: (Double, Double) -> Boolean) = LayerRaster(size, size, ByteArray(size * size * 4).also { rgba ->
		for (y in 0 until size) for (x in 0 until size) if (inside((x + 0.5) / size, (y + 0.5) / size)) rgba[(y * size + x) * 4 + 3] = -1
	})

	private fun nearSegment(u: Double, v: Double, ax: Double, ay: Double, bx: Double, by: Double, radius: Double): Boolean {
		val sx = bx - ax; val sy = by - ay
		val t = (((u - ax) * sx + (v - ay) * sy) / (sx * sx + sy * sy)).coerceIn(0.0, 1.0)
		val dx = u - ax - sx * t; val dy = v - ay - sy * t
		return dx * dx + dy * dy < radius * radius
	}

	/** An almond eye with nine lashes fanning from the upper lid. */
	private fun lashedEye(u: Double, v: Double): Boolean {
		val dx = (u - 0.5) / 0.4; val dy = (v - 0.6) / 0.25
		if (dx * dx + dy * dy <= 1.0) return true
		return (0 until 9).any { k ->
			val a = Math.PI * (0.12 + 0.76 * k / 8.0)
			val bx = 0.5 - 0.4 * cos(a); val by = 0.6 - 0.25 * sin(a)
			nearSegment(u, v, bx, by, bx - 0.16 * cos(a), by - 0.22 * sin(a) - 0.05, 0.004)
		}
	}

	private val eye = layer("eye", LayerBounds(0, 0, 64, 64), raster(512, ::lashedEye))

	private fun mesh(layer: WorkspaceSourceLayer, wrap: Float) =
		MeshResolution.mesh(MeshResolution.input(CanvasDensity.canvasLayer(layer), MeshTrace.TEXTURE, 1f), 8, settings.copy(wrap = wrap), null)

	/** Opaque texture pixels of [layer] whose centre lies in no triangle of [mesh]. */
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

	@Test fun theDistanceTransformIsExact() {
		val random = Random(11)
		repeat(20) {
			val w = 1 + random.nextInt(24); val h = 1 + random.nextInt(24)
			val feature = BooleanArray(w * h) { random.nextInt(9) == 0 }
			val d = MeshWrap.squaredDistance(w, h, feature)
			for (y in 0 until h) for (x in 0 until w) {
				var best = Int.MAX_VALUE / 4
				for (i in feature.indices) if (feature[i]) {
					val dx = i % w - x; val dy = i / w - y
					best = min(best, dx * dx + dy * dy)
				}
				assertEquals(best, d[y * w + x], "($x, $y) of $w x $h")
			}
		}
	}

	@Test fun closingFillsNarrowGapsKeepsWideOnesAndEveryPaintedPixel() {
		val w = 40; val h = 20
		fun bars(gap: Int) = ByteArray(w * h * 4).also { rgba ->
			// Two bars touching the raster's edges, [gap] pixels apart.
			for (y in 0 until h) for (x in 0 until w) if (x < 10 || x >= 10 + gap && x < 20 + gap) rgba[(y * w + x) * 4 + 3] = 77
		}
		val narrow = bars(4)
		val before = narrow.copyOf()
		assertTrue(MeshWrap.close(w, h, narrow, 3.0))
		for (i in 0 until w * h) {
			if (before[i * 4 + 3].toInt() != 0) assertEquals(77, narrow[i * 4 + 3].toInt(), "painted alpha is kept")
		}
		// Beyond the raster is transparent, so the gap stays open where it meets the top and bottom edges.
		for (y in 3 until h - 3) for (x in 10 until 14) assertEquals(-1, narrow[(y * w + x) * 4 + 3].toInt(), "gap pixel ($x, $y) is closed")
		val wide = bars(9)
		assertFalse(MeshWrap.close(w, h, wide, 3.0), "a gap wider than the diameter stays open")
		assertFalse(MeshWrap.close(w, h, bars(4), 0.3), "a radius under half a pixel does nothing")
	}

	@Test fun wrapZeroIsTheDrawnEdgeAndAConvexLayerIsUnchangedByWrap() {
		assertEquals(0f, MeshSettings().wrap)
		assertEquals(0f, PipelineConfig().meshWrap)
		assertEquals(0.0, MeshWrap.radius(0f, 4.0))
		val oval = layer("oval", LayerBounds(0, 0, 64, 64), raster(256) { u, v -> (u - 0.5) * (u - 0.5) / 0.16 + (v - 0.5) * (v - 0.5) / 0.09 < 1 })
		val plain = assertNotNull(mesh(oval, 0f))
		val wrapped = assertNotNull(mesh(oval, 8f))
		// Nothing to close on a convex outline: the very same mesh.
		assertContentEquals(plain.positions, wrapped.positions)
		assertContentEquals(plain.indices, wrapped.indices)
	}

	@Test fun lashesShareOneEnvelopeWithFewerVertices() {
		val plain = assertNotNull(mesh(eye, 0f))
		val wrapped = assertNotNull(mesh(eye, 16f))
		val again = assertNotNull(mesh(eye, 16f))
		assertContentEquals(wrapped.positions, again.positions, "wrapping is deterministic")
		assertContentEquals(wrapped.indices, again.indices)
		val before = plain.positions.size / 2; val after = wrapped.positions.size / 2
		assertTrue(after * 2 < before, "$before vertices with an outline per lash, $after wrapped")
		assertEquals(1, wrapped.boundaryLoops.size)
		val missed = uncovered(eye, wrapped)
		assertTrue(missed <= max(8, uncovered(eye, plain)), "$missed opaque texture pixels outside the wrapped mesh")
		// The lash tips stay inside: the envelope reaches as high as the drawn lashes.
		val top = (0 until wrapped.positions.size / 2).minOf { wrapped.positions[it * 2 + 1] }
		val drawnTop = (0 until plain.positions.size / 2).minOf { plain.positions[it * 2 + 1] }
		assertTrue(top <= drawnTop + 1f, "wrapped top $top, drawn top $drawnTop")
	}

	@Test fun theWrapSettingRoundTripsAndOlderSettingsReadAsNone() {
		val legacy = WorkspaceSettingsCodec.encode(PipelineConfig())
		assertFalse("meshWrap" in legacy, "no wrap writes the settings as before")
		assertEquals(0f, WorkspaceSettingsCodec.decode(legacy, PipelineConfig(meshWrap = 5f)).meshWrap)
		val config = PipelineConfig(meshWrap = 6f, meshOverrides = mapOf("eye" to MeshSettings(wrap = 12f), "body" to MeshSettings()))
		val encoded = WorkspaceSettingsCodec.encode(config)
		assertEquals(JsonPrimitive(6f), encoded["meshWrap"])
		assertFalse("wrap" in encoded.getValue("meshOverrides").jsonObject.getValue("body").jsonObject)
		val decoded = WorkspaceSettingsCodec.decode(encoded)
		assertEquals(6f, decoded.meshWrap)
		assertEquals(0f, WorkspaceSettingsCodec.decodeWrap(JsonPrimitive("x")))
		assertEquals(MeshWrap.range.endInclusive, WorkspaceSettingsCodec.decodeWrap(JsonPrimitive(1e6)))

		// A mesh baseline keeps the wrap its generator used, and one written before reads as none.
		for (wrap in listOf(0f, 4f)) for (trace in MeshTrace.entries) {
			val overlay = MeshGenerationBaseline.preserve(RigEditOverlay.Empty,
				PipelineConfig(meshWrap = wrap, meshTrace = trace, meshOverrides = mapOf("eye" to MeshSettings(wrap = wrap))))
			val restoredBaseline = MeshGenerationBaseline.restore(PipelineConfig(rigEdits = overlay, meshWrap = 9f))
			assertEquals(wrap, restoredBaseline.meshWrap); assertEquals(trace, restoredBaseline.meshTrace)
			assertEquals(wrap, restoredBaseline.meshOverrides.getValue("eye").wrap)
		}

		// A settings command validates the range and stores no wrap as an absent key.
		val on = mergeProjectSettings(legacy, buildJsonObject { put("meshWrap", 8) })
		assertEquals(JsonPrimitive(8f), on["meshWrap"])
		assertEquals(legacy, mergeProjectSettings(on, buildJsonObject { put("meshWrap", 0) }))
		assertFailsWith<IllegalArgumentException> { mergeProjectSettings(legacy, buildJsonObject { put("meshWrap", 65) }) }
	}

	@Test fun changingTheWrapRemeshesTheLayer() {
		val body = LayerRaster(40, 40, ByteArray(40 * 40 * 4) { if (it % 4 == 3) -1 else 90 })
		val source = WorkspaceSourceArt(128, 96, listOf(
			layer("body", LayerBounds(10, 20, 40, 40), body),
			layer("eye", LayerBounds(60, 10, 64, 64), raster(256, ::lashedEye)).copy(order = 1),
		), emptyList())
		val pipeline = PSD2LivePipeline()
		val config = PipelineConfig(atlasSize = 1024, meshOnly = true, generatePhysics = false)
		val model = pipeline.buildPreview(source, config)
		val wrapped = config.copy(meshWrap = 16f)
		assertTrue("eye" in pipeline.meshSettingsChangedDrawableIds(model, wrapped).map { model.rig.layerIdByDrawableId[it] })
		val rebuilt = pipeline.rebuildPreview(model, wrapped)
		fun eyeVertices(preview: RigPreviewModel) = preview.rig.puppet.drawables
			.single { preview.rig.layerIdByDrawableId[it.id.raw] == "eye" }.mesh!!.positions.size / 2
		assertTrue(eyeVertices(rebuilt) < eyeVertices(model), "${eyeVertices(model)} -> ${eyeVertices(rebuilt)}")
	}
}
