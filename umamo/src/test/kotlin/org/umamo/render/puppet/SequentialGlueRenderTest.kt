package org.umamo.render.puppet

import org.umamo.render.PuppetTextures
import org.umamo.render.device.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.lang.reflect.Proxy
import kotlin.test.*

class SequentialGlueRenderTest {
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

    /** Capture the actual backend seam: resources, uploaded vertices and draw uniforms.
     * The test executes PuppetRenderer rather than testing a parallel rendering implementation. */
    private class Device {
        val created = ArrayList<GpuMesh>()
        val positions = HashMap<GpuMesh, FloatArray>()
        var capturePasses = 0
        var glueDraws = 0
        var directDraws = 0
        var uniformsCorrect = true
        private fun proxy(type: Class<*>, action: (String, Array<out Any?>) -> Any?): Any =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { value, method, arguments ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(value)
                    "equals" -> value === arguments!![0]
                    "toString" -> type.simpleName
                    else -> action(method.name, arguments ?: emptyArray())
                }
            }
        val device: RenderDevice = proxy(RenderDevice::class.java) { name, args -> when (name) {
            "createMesh" -> object : GpuMesh {}.also { mesh -> created += mesh; positions[mesh] = (args[0] as MeshSpec).restPositions.copyOf() }
            "updateMeshPositions" -> { positions[args[0] as GpuMesh] = (args[1] as FloatArray).copyOf(); null }
            "createTexture", "createFloatTexture" -> object : GpuTexture {}
            "createRenderPipeline" -> object : RenderPipeline {}
            "createDeformCapturePipeline" -> object : DeformCapturePipeline {}
            "createDeformedPositionStore" -> object : DeformedPositionStore {}
            "createRenderTarget" -> object : RenderTarget { override val sampledTexture = object : GpuTexture {} }
            "beginFrame" -> proxy(FrameEncoder::class.java) { frameMethod, _ -> when (frameMethod) {
                "beginDeformCapturePass" -> { capturePasses++; error("Sequential glue must bypass the one-partner capture pass") }
                "beginRenderPass" -> proxy(RenderPassEncoder::class.java) { passMethod, draw ->
                    when (passMethod) {
                        "drawGlueMesh" -> glueDraws++
                        "drawPuppetMesh" -> {
                            directDraws++
                            val uniforms = draw[1] as DeformUniforms
                            uniformsCorrect = uniformsCorrect && uniforms.cornerCount == 0 && uniforms.blendCount == 0 && uniforms.parentType == 0
                        }
                    }
                    null
                }
                else -> null
            } }
            else -> null
        } } as RenderDevice
    }

    @Test fun repeatedAndSelfWeldsUploadOrderedCpuGeometryAndDrawWithoutASecondDeformation() {
        val model = model(); assertTrue(requiresSequentialGlue(model))
        val device = Device()
        val renderer = PuppetRenderer(model, PuppetTextures(emptyList(), emptyMap(), false), device.device)
        renderer.initGl(); renderer.setPose(emptyMap())
        val expected = CpuDeformationEvaluator().evaluate(model, emptyMap())
        for ((index, drawable) in model.drawables.withIndex()) {
            val uploaded = device.positions.getValue(device.created[index])
            val world = expected.worldPositions.getValue(drawable.id)
            uploaded.indices.forEach { assertEquals(if (it % 2 == 0) world[it] else -world[it], uploaded[it]) }
        }
        renderer.render(object : RenderTarget { override val sampledTexture: GpuTexture? = null }, 80, 80)
        assertEquals(0, device.capturePasses); assertEquals(0, device.glueDraws)
        assertEquals(2, device.directDraws); assertTrue(device.uniformsCorrect)
        val plain = model.copy(glues = emptyList())
        renderer.updateModel(plain); renderer.setPose(emptyMap())
        assertFalse(requiresSequentialGlue(plain))
        for ((index, drawable) in plain.drawables.withIndex())
            assertContentEquals(drawable.mesh!!.positions, device.positions.getValue(device.created.takeLast(plain.drawables.size)[index]))
        renderer.disposeGl()
    }
}
