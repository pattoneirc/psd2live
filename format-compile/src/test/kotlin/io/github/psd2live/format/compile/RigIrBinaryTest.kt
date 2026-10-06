package io.github.psd2live.format.compile

import io.github.psd2live.format.model.*
import java.io.IOException
import kotlin.test.*

/** The binary rig encoding returns exactly the value it stored and rejects anything else. */
class RigIrBinaryTest {
	private fun sample(): RigIR {
		val channels: Channels = linkedMapOf(
			Channel.OPACITY to KeyGrid(listOf(KeyAxis("ParamA", Floats.values(-1f, 0f, 1f))),
				listOf(KeyCell(Ints.values(0), ChannelValue.Scalar(0.25f)), KeyCell(Ints.values(2), ChannelValue.Scalar(Float.NaN)))),
			Channel.MULTIPLY_COLOR to KeyGrid.single(ChannelValue.Color(Rgb(0.1f, 0.2f, 0.3f))),
			Channel.FLIP_X to KeyGrid.single(ChannelValue.Flag(true)),
		)
		val limit = listOf(BlendLimit("ParamB", listOf(BlendLimitPoint(0f, 1f), BlendLimitPoint(1f, 0.5f))))
		return RigIR(
			canvas = Canvas(1024f, 2048f, 512f, -1024f, null),
			parameters = listOf(Parameter("ParamA", "A", -1f, 1f, 0f), Parameter("ParamB", "B", 0f, 1f, 0f, blend = true, repeat = true, keys = Floats.values(0f, 1f))),
			parameterLinks = listOf(ParameterLink("ParamA", "ParamB")),
			parameterTree = listOf(ParameterNode.Group("g", "Group", true, listOf(ParameterNode.Param("ParamA")), GroupLabel.Preset("RED")),
				ParameterNode.Group("h", "H", false, emptyList(), GroupLabel.Custom(0x7f123456)), ParameterNode.Param("ParamB")),
			parameterRoles = listOf(ParameterRole("EyeBlink", listOf("ParamA"))),
			parts = listOf(Part("part", "Part", listOf(ChildRef.MeshRef("mesh"), ChildRef.PartRef("sub")), groupMode = GroupMode.ISOLATED, drawOrder = 600,
				channels = channels, composite = Composite(ColorBlend.SCREEN, AlphaBlend.ATOP, listOf("mesh"), listOf("sub"), true, 0.5f),
				shapes = listOf(BlendBinding("ParamB", Floats.values(0f, 1f), 0, listOf(null, PartShape(1f, 0.5f, Rgb.White, Rgb.Black)), limit)))),
			rootChildren = listOf(ChildRef.PartRef("part")),
			rootPart = "part",
			deformers = listOf(
				Deformer.Warp("warp", "Warp", null, "part", 2, 3, true,
					KeyGrid(listOf(KeyAxis("ParamA", Floats.values(-1f, 1f))), listOf(KeyCell(Ints.values(1), LatticePoints(Floats.values(1f, 2f, 3f))))),
					channels, shapes = listOf(BlendBinding("ParamB", Floats.values(0f, 1f), 0, listOf(null, LatticeShape(Floats.values(-0f, 4f), 1f, Rgb.White, Rgb.Black))))),
				Deformer.Rotation("rot", "Rot", "warp", null, 15f, KeyGrid.single(Pivot(0.5f, 0.5f, 10f, 1f)), flipX = true, enabled = false,
					shapes = listOf(BlendBinding("ParamB", Floats.values(0f, 1f), 1, listOf(PivotShape(1f, 2f, 3f, 1f, false, true, 1f, Rgb.White, Rgb.Black), null))),
					handleLength = 42f),
			),
			meshes = listOf(Mesh("mesh", "Mesh", "rot", ColorBlend.MULTIPLY, AlphaBlend.OUT, listOf("other"), true,
				MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2)),
				KeyGrid.single(MeshOffsets(Floats.values(0f, 0f, 0.5f, 0f, 0f, 0f))), channels, 501.5f, 0.75f, culling = true, textureSource = "other",
				page = 1, tile = "tile", shapes = listOf(BlendBinding("ParamB", Floats.values(0f, 1f), 0, listOf(null, MeshShape(Floats.values(1f), 0f, 1f, Rgb.White, Rgb.Black)))),
				userData = "data"), Mesh("other", "Other", null, geometry = null, offsets = null)),
			glues = listOf(Glue(null, "mesh", "other", listOf(GluePair(0, 1, 0.5f, 0.5f)), channels, 0.8f), Glue("g1", "other", "mesh", emptyList())),
			renderRoot = RenderGroup(null, 500, listOf(RenderMesh("other"), RenderGroup("part", 600, listOf(RenderMesh("mesh")), channels, Composite()))),
			textures = Textures(listOf(TexturePage(4, 4, Bytes.of(byteArrayOf(1, 2, 3)))), linkedMapOf("mesh" to 0), listOf(PageSize(4, 4)),
				listOf(TextureTile("tile", "Tile", 2, 2, TilePlacement(0, 1f, 1f, 0.5f, 0.5f, 90f), SourceRef("src", "layer", true), true, "old"),
					TextureTile("bare", "Bare", 1, 1)), false, 3, 1, listOf(TileArt("tile", 1, 1, Bytes.of(byteArrayOf(9, 9, 9, 9))))),
			physics = Physics(listOf(PhysicsGroup("p", "P", listOf(PhysicsInput("ParamA", 1f, PhysicsSource.ANGLE, true)),
				listOf(PhysicsOutput("ParamB", 1, 2f, 1f, PhysicsSource.X, false)), listOf(PhysicsSegment(1f, 0.9f, 0.8f, 1.5f)),
				PhysicsNormalization(-10f, 0f, 10f, -10f, 0f, 10f))), 30f),
			clips = listOf(Clip("c", "Clip", "Idle", "idle.motion3.json", 2f, 30f, true, 0.5f, null, listOf(Curve("ParamA", 0f, 0f, listOf(
				CurveSegment.Linear(0.5f, 1f), CurveSegment.Bezier(0.6f, 1f, 0.7f, 0f, 1f, 0f), CurveSegment.Stepped(1.5f, 1f), CurveSegment.InverseStepped(2f, 0f)))))),
			restPose = linkedMapOf("ParamA" to 1f),
			authoring = Authoring("Cubism42", true,
				listOf(SourceFile("src", "Source", null, "psd", listOf(SourceLayer("layer", "Layer", "a/b", 1, 2, 3, 4, true, hash = "h", replaced = true)), "hash", 123L)),
				listOf(DeformPath("path", "mesh", listOf(PathPoint(0, 1, 2, 0.2f, 0.3f, 0.5f, true)), 4f, 0.5f, true, 2)),
				listOf(VertexGroup("pin", "mesh", "pin", Floats.values(1f, 0f, 0.5f)))),
		)
	}

	@Test fun aRigRoundTripsExactly() {
		val ir = sample()
		val bytes = RigIrBinary.encode(ir)
		val decoded = RigIrBinary.decode(bytes)
		assertEquals(ir, decoded)
		// Raw float bits survive, so a value compared bit for bit (or hashed by text) matches too.
		assertEquals(ir.toString(), decoded.toString())
		assertContentEquals(bytes, RigIrBinary.encode(decoded))
		val warp = decoded.deformers.first() as Deformer.Warp
		assertEquals(java.lang.Float.floatToRawIntBits(-0f), java.lang.Float.floatToRawIntBits(warp.shapes.single().shapes[1]!!.points[0]))
	}

	@Test fun damagedOrForeignBytesAreRejected() {
		val bytes = RigIrBinary.encode(sample())
		assertFailsWith<IOException> { RigIrBinary.decode(bytes.copyOf(bytes.size - 7)) }
		assertFailsWith<IOException> { RigIrBinary.decode(bytes + byteArrayOf(0)) }
		assertFailsWith<IOException> { RigIrBinary.decode(byteArrayOf()) }
		assertFailsWith<IOException> { RigIrBinary.decode(bytes.copyOf().also { it[7] = 99 }) } // another version
		assertFailsWith<IOException> { RigIrBinary.decode(bytes.copyOf().also { it[0] = 0 }) }
		// A corrupted length is caught before allocating for it.
		assertFailsWith<IOException> { RigIrBinary.decode(bytes.copyOf().also { for (i in 25 until 29) it[i] = 0x7f }) }
	}
}
