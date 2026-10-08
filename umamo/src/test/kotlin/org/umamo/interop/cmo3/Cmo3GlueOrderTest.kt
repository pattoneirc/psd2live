package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.keyform.channelGridsOf
import org.umamo.runtime.model.*
import kotlin.test.*

/** Glues keep their order through a cmo3, written fresh and over a retained file, when welds interleave and animate. */
class Cmo3GlueOrderTest {
    @Test fun cmo3FreshAndRetainedExportsKeepInterleavedAnimatedWeldsInEvaluationOrder() {
        val original = model()
        val axis = ParameterId("Shape")
        val track = channelGridsOf(FormChannel.GLUE_INTENSITY to KeyformGrid(
            listOf(KeyformAxis(axis, floatArrayOf(-1f, 1f))),
            listOf(KeyformCell(intArrayOf(0), ChannelValue.Scalar(0.2f) as ChannelValue),
                KeyformCell(intArrayOf(1), ChannelValue.Scalar(0.8f) as ChannelValue))))
        val interleaved = Glue(original.drawables[1].id, original.drawables[0].id,
            listOf(GluePair(0, 1, 0.4f, 0.6f)), intensity = 0.6f, id = "interleaved")
        val input = original.copy(parameters = listOf(Parameter(axis, "Shape", -1f, 1f, 0f)),
            drawables = original.drawables.map { drawable -> drawable.copy(texturePage = 0,
                mesh = DrawableMesh(drawable.mesh!!.positions, floatArrayOf(0.1f, 0.1f, 0.8f, 0.1f, 0.1f, 0.8f), drawable.mesh.indices)) },
            glues = listOf(original.glues[0].copy(channelGrids = track), interleaved, original.glues[1], original.glues[2]),
            canvasWidth = 32f, canvasHeight = 32f)
        val png = java.io.ByteArrayOutputStream().also { output ->
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", output)
        }.toByteArray()
        val converted = Cmo3Conversion.freshCmo3(input,
            listOf(Cmo3Conversion.AtlasPage(png, 32, 32)),
            input.drawables.associate { it.id.raw to 0 }, "sequential", 0, 0x42)
        fun read() = Cmo3Import.fromModelSource(
            Cmo3.read(Cmo3.write(converted.model)).root as CModelSource)
        fun verify(expected: PuppetModel, actual: PuppetModel) {
            assertEquals(expected.glues.map { it.meshA to it.meshB }, actual.glues.map { it.meshA to it.meshB })
            fun pairs(model: PuppetModel) = model.glues.map { glue -> glue.pairs.map { pair ->
                listOf(pair.indexA, pair.indexB, pair.weightA, pair.weightB)
            } }
            assertEquals(pairs(expected), pairs(actual))
            val evaluator = CpuDeformationEvaluator()
            for (value in listOf(-1f, 0f, 0.45f, 1f)) {
                val pose = mapOf(axis to value)
                val a = evaluator.evaluate(expected, pose); val b = evaluator.evaluate(actual, pose)
                a.worldPositions.forEach { (id, points) -> points.indices.forEach { i ->
                    assertEquals(points[i], b.worldPositions.getValue(id)[i], 0.0001f)
                } }
            }
        }
        val fresh = read()
        verify(input, fresh)
        val reordered = fresh.copy(glues = listOf(fresh.glues[1], fresh.glues[0], fresh.glues[2], fresh.glues[3]))
        Cmo3Export.apply(reordered, converted.model)
        verify(reordered, read())
    }

    private fun model(): PuppetModel {
        val drawables = listOf("a", "b").mapIndexed { index, id ->
            Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(),
                DrawableMesh(floatArrayOf(index * 10f, 0f, 10f + index * 10f, 0f, index * 10f, 10f),
                    FloatArray(6), intArrayOf(0, 1, 2)), null)
        }
        return PuppetModel(emptyList(), emptyList(), emptyList(), drawables,
            drawables.map { OrgChild.Drawable(it.id) }, null, glues = listOf(
                Glue(drawables[0].id, drawables[1].id, listOf(GluePair(0, 0, 0.3f, 0.4f)), intensity = 0.7f, id = "first"),
                Glue(drawables[0].id, drawables[1].id, listOf(GluePair(1, 0, 0.6f, 0.2f)), intensity = 0.8f, id = "second"),
                Glue(drawables[0].id, drawables[0].id, listOf(GluePair(2, 0, 0.25f, 0f)), id = "follower")))
            .withDerivedRenderRoot()
    }
}
