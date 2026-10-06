package io.github.psd2live.core

import io.github.psd2live.targets.cubism.PuppetIr
import org.junit.jupiter.api.Tag
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.test.*

/** The engine model and the neutral IR convert both ways without losing a field. */
class RigIrRoundTripTest {
	private fun assertRoundTrip(model: PuppetModel) {
		val ir = PuppetIr.toIr(model)
		val back = PuppetIr.toPuppet(ir)
		val diff = diffPuppetModels(model, back)
		assertTrue(diff.isEmpty, "Round trip changed the model: $diff")
		// The diff does not cover editor-only content; the IR's structural equality does.
		assertEquals(ir, PuppetIr.toIr(back))
		assertEquals(model.deformPaths, back.deformPaths)
		assertEquals(model.vertexGroups, back.vertexGroups)
		assertEquals(model.renderRoot.children.size, back.renderRoot.children.size)
		assertEquals(model.pixelsPerUnit, back.pixelsPerUnit)
		assertEquals(model.rendersFromSourceLayers, back.rendersFromSourceLayers)
		assertEquals(model.sources, back.sources)
	}

	private fun grid(id: String, vararg keys: Float) = KeyformAxis(ParameterId(id), keys)
	private fun color(v: Float) = ColorRgb(v, 1f - v, 0.5f)

	/** A hand-built model that sets every field to a non-default value. */
	private fun everyField(): PuppetModel {
		val axis = grid("A", -1f, 0f, 1f)
		val channels = ChannelGrids(linkedMapOf(
			FormChannel.OPACITY to KeyformGrid(listOf(axis), (0..2).map { KeyformCell(intArrayOf(it), ChannelValue.Scalar(0.3f * it) as ChannelValue) }),
			FormChannel.MULTIPLY_COLOR to KeyformGrid(listOf(axis), (0..2).map { KeyformCell(intArrayOf(it), ChannelValue.Color(color(0.2f * it)) as ChannelValue) }),
			FormChannel.FLIP_X to KeyformGrid(listOf(axis), (0..2).map { KeyformCell(intArrayOf(it), ChannelValue.Flag(it == 2) as ChannelValue) }),
		))
		val limits = listOf(BlendWeightLimit(ParameterId("A"), listOf(BlendWeightLimitPoint(0f, 1f), BlendWeightLimitPoint(1f, 0.25f))))
		val warp = Deformer.Warp(DeformerId("W"), "Warp", null, PartId("P"), 1, 1, true,
			KeyformGrid(listOf(axis), (0..2).map { KeyformCell(intArrayOf(it), WarpLatticeForm(floatArrayOf(0f, 0f, 10f + it, 0f, 0f, 10f, 10f, 10f))) }),
			channels, 0.9f, color(0.1f), color(0.2f), false, false, false,
			listOf(BlendShapeBinding(ParameterId("B"), floatArrayOf(0f, 1f), 0, listOf(null, WarpForm(FloatArray(8) { 0.5f }, 0.8f, color(0.3f), color(0.4f))), limits)))
		val rotation = Deformer.Rotation(DeformerId("R"), "Rotation", DeformerId("W"), null, 12f,
			KeyformGrid(listOf(axis), (0..2).map { KeyformCell(intArrayOf(it), RotationPivotForm(0.5f, 0.5f, 10f * it, 1f + it)) }),
			ChannelGrids.Empty, 0.7f, color(0.5f), color(0.6f), true, true, false, true, false,
			listOf(BlendShapeBinding(ParameterId("B"), floatArrayOf(0f, 1f), 0, listOf(null, RotationForm(1f, 2f, 3f, 1.5f, true, false, 0.5f, color(0.7f), color(0.8f))))),
			handleLength = 42f)
		val mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))
		fun drawable(id: String, parent: DeformerId?) = Drawable(DrawableId(id), id, parent, BlendMode.Overlay, listOf(DrawableId("M2")).filter { it.raw != id },
			mesh, KeyformGrid(listOf(axis), (0..2).map { KeyformCell(intArrayOf(it), MeshDeltaForm(FloatArray(6) { 0.1f * it })) }),
			channels, 412f, 0.6f, color(0.9f), color(0.15f), true, AlphaBlendMode.Atop, true, false, false,
			DrawableId("M2").takeIf { id != "M2" }, 1, AtlasTileId("tile~1"),
			listOf(BlendShapeBinding(ParameterId("B"), floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(FloatArray(6) { 0.2f }, 300f, 0.4f, color(0.2f), color(0.3f))), limits)),
			userData = "note")
		val composite = PartComposite(BlendMode.Screen, AlphaBlendMode.Out, listOf(DrawableId("M1")), listOf(PartId("Q")), true, 0.5f, color(0.1f), color(0.9f))
		val part = Part(PartId("P"), "Part", listOf(OrgChild.Drawable(DrawableId("M1")), OrgChild.Part(PartId("Q"))), false, true, false,
			PartGroupMode.Isolated, 321, channels, composite,
			listOf(BlendShapeBinding(ParameterId("B"), floatArrayOf(0f, 1f), 0, listOf(null, PartForm(100f, 0.5f, color(0.4f), color(0.6f))))))
		val inner = Part(PartId("Q"), "Inner", listOf(OrgChild.Drawable(DrawableId("M2"))))
		return PuppetModel(
			parameters = listOf(Parameter(ParameterId("A"), "Axis", -1f, 1f, 0f), Parameter(ParameterId("B"), "Blend", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE, true, listOf(0f, 0.5f, 1f))),
			parts = listOf(part, inner),
			deformers = listOf(warp, rotation),
			drawables = listOf(drawable("M1", DeformerId("W")), drawable("M2", DeformerId("R"))),
			rootChildren = listOf(OrgChild.Part(PartId("P"))),
			rootPartId = PartId("P"),
			glues = listOf(Glue(DrawableId("M1"), DrawableId("M2"), listOf(GluePair(0, 1, 0.3f, 0.7f)), channels, 0.8f, "Glue_1")),
			renderRoot = RenderGroup(null, 500, listOf(RenderGroup(PartId("P"), 321, listOf(RenderDrawable(DrawableId("M1")), RenderDrawable(DrawableId("M2"))),
				channels, composite))),
			parameterLinks = listOf(ParameterLink(ParameterId("A"), ParameterId("B"))),
			parameterTree = listOf(ParameterNode.Group(ParameterGroupId("G"), "Group", false, listOf(ParameterNode.Param(ParameterId("A"))),
				ParameterLabelColor.Preset(ParameterLabelColor.Preset.Kind.GREEN)), ParameterNode.Param(ParameterId("B"))),
			canvasWidth = 640f, canvasHeight = 480f, worldOriginX = 320f, worldOriginY = -240f, pixelsPerUnit = 2.5f,
			runtimeTarget = RuntimeTarget.Cubism42, rendersFromSourceLayers = true,
			atlas = PuppetAtlas(listOf(AtlasPage(256, 128)), listOf(AtlasTile(AtlasTileId("tile~1"), "Tile", 10, 20,
				AtlasPlacement(0, 1f, 2f, 1.5f, 1.5f, 90f), SourceLayerRef(ArtSourceId("S"), "layer", true), true, AtlasTileId("tile"))),
				storedUvsAddressPages = false, composition = AtlasComposition(3, 4)),
			sources = listOf(ArtSource(ArtSourceId("S"), "art", "/art.psd", "psd", listOf(ArtSourceLayer("layer", "Layer", "a/b", 1, 2, 3, 4, false,
				false, "hash", true, true, true)), "h", 7L)),
			deformPaths = listOf(DeformPath("path", DrawableId("M1"), listOf(DeformPathPoint(0, 1, 2, 0.2f, 0.3f, 0.5f, true), DeformPathPoint(0, 1, 2, 0.6f, 0.2f, 0.2f), DeformPathPoint(0, 1, 2, 0.1f, 0.1f, 0.8f)), 30f, 40f, true, 3)),
			vertexGroups = listOf(VertexGroup("pin", DrawableId("M1"), VertexGroupKind.MASS, floatArrayOf(0f, 0.5f, 1f))),
		)
	}

	@Test fun everyFieldOfAHandBuiltModelSurvivesTheRoundTrip() = assertRoundTrip(everyField())

	@Test fun tableBackedEnumsCoverEveryValue() {
		for (mode in BlendMode.entries) {
			val model = everyField().let { m -> m.copy(drawables = m.drawables.map { it.copy(blendMode = mode) }) }
			assertEquals(mode, PuppetIr.toPuppet(PuppetIr.toIr(model)).drawables.first().blendMode)
		}
		for (mode in AlphaBlendMode.entries) {
			val model = everyField().let { m -> m.copy(drawables = m.drawables.map { it.copy(alphaBlendMode = mode) }) }
			assertEquals(mode, PuppetIr.toPuppet(PuppetIr.toIr(model)).drawables.first().alphaBlendMode)
		}
		for (target in RuntimeTarget.entries) assertEquals(target, PuppetIr.toPuppet(PuppetIr.toIr(everyField().copy(runtimeTarget = target))).runtimeTarget)
	}

	@Tag("slow")
	@Test fun generatedRigsWithSkeletonSurviveTheRoundTrip() {
		val plain = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		assertRoundTrip(plain.rig.puppet)
		val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(
			rigEdits = plain.config.rigEdits.copy(skeleton = SkeletonAutoBuilder.build(plain.analysis, plain.rig))))
		assertRoundTrip(skeletal.rig.puppet)
		// The compiled IR keeps the packed pages' page assignment of every mesh.
		val ir = RigIrCompiler.compile(skeletal)
		assertEquals(skeletal.rig.pageByDrawableId, ir.textures.bindings)
		assertEquals(skeletal.atlas.pages.size, ir.textures.pages.size)
		assertTrue(ir.textures.pages.all { it.png.size > 0 })
		assertTrue(ir.clips.isNotEmpty() && ir.physics.groups.isNotEmpty())
	}
}
