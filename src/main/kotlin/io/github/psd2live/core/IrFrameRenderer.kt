package io.github.psd2live.core

import io.github.psd2live.format.compile.FrameRenderer
import io.github.psd2live.format.compile.FrameSession
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.RasterImage
import io.github.psd2live.format.model.PhysicsSource
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Renders the IR for raster exports with the engine's CPU evaluator and the editor's software painter, and
 * steps the editor's pendulum physics between frames.
 *
 * Approximations of the software painter: blend modes draw as normal, multiply/screen colors are ignored
 * and masks clip to their geometry rather than their texture alpha.
 */
internal object IrFrameRenderer : FrameRenderer {
	override fun open(ir: RigIR, physics: Boolean): FrameSession {
		val puppet = PuppetIr.toPuppet(ir)
		val pages = ir.textures.pages.mapIndexed { index, page ->
			require(page.png.size > 0) { "Texture page $index has no pixels" }
			requireNotNull(ImageIO.read(page.png.shared().inputStream())) { "Texture page $index is not a PNG" }
		}
		val engine = if (!physics || ir.physics.groups.isEmpty()) null else PhysicsEngine(
			ir.physics.groups.map { group -> RigPhysicsEdit(group.id, group.name,
				group.inputs.map { PhysicsInput(it.parameter, it.weight, type(it.source), it.reflect) },
				group.outputs.map { PhysicsOutput(it.parameter, it.vertex, it.scale, it.weight, type(it.source), it.reflect) },
				group.segments.map { PhysicsSegment(it.length, it.mobility, it.delay, it.acceleration) },
				group.normalization.let { PhysicsNormalization(it.positionMin, it.positionDefault, it.positionMax, it.angleMin, it.angleDefault, it.angleMax) }) },
			PhysicsEngine.ranges(puppet.parameters), ir.physics.fps?.takeIf { it > 0f })
		val evaluator = CpuDeformationEvaluator()
		return object : FrameSession {
			override val simulatesPhysics: Boolean = engine != null

			override fun render(parameters: Map<String, Float>, deltaSeconds: Float, frame: FrameSpec, meshes: Set<String>?): RasterImage {
				val driven = engine?.step(parameters, deltaSeconds).orEmpty()
				val values = (parameters + driven).mapKeys { ParameterId(it.key) }
				val geometry = evaluator.evaluate(puppet, values)
				val scale = minOf(frame.outputWidth / frame.width, frame.outputHeight / frame.height).toDouble()
				val viewport = CanvasViewport(scale, -frame.left * scale, -frame.top * scale, ir.canvas.width, ir.canvas.height)
				val image = BufferedImage(frame.outputWidth, frame.outputHeight, BufferedImage.TYPE_INT_ARGB)
				val g = image.createGraphics()
				try {
					if (frame.background != 0) { g.color = java.awt.Color(frame.background, true); g.fillRect(0, 0, image.width, image.height) }
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
					RigCanvasSupport.paintPuppet(g, puppet, pages, geometry, viewport, meshes) { ir.textures.bindings[it.id.raw] ?: it.texturePage }
				} finally {
					g.dispose()
				}
				return RasterImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
			}

			override fun close() {}
		}
	}

	private fun type(source: PhysicsSource) = if (source == PhysicsSource.ANGLE) PhysicsSourceType.ANGLE else PhysicsSourceType.X
}
