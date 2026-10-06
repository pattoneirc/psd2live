package io.github.psd2live.core

import io.github.psd2live.format.compile.FrameRenderer
import io.github.psd2live.format.compile.FrameSession
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.RasterImage
import io.github.psd2live.format.model.PhysicsSource
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer

/**
 * Renders the IR for raster exports: geometry from the engine's CPU evaluator, colors from the IR, pixels
 * from the software rasterizer (texture-alpha masks, multiply and screen colors, every blend mode), and the
 * editor's pendulum physics stepped between frames. Group composites are drawn as their meshes.
 */
internal object IrFrameRenderer : FrameRenderer {
	override fun open(ir: RigIR, physics: Boolean): FrameSession {
		val rasterizer = SoftwareRasterizer(ir)
		val colors = IrColors(ir)
		val geometry = IrGeometryEvaluator.open(ir)
		val engine = if (!physics || ir.physics.groups.isEmpty()) null else PhysicsEngine(
			ir.physics.groups.map { group -> RigPhysicsEdit(group.id, group.name,
				group.inputs.map { PhysicsInput(it.parameter, it.weight, type(it.source), it.reflect) },
				group.outputs.map { PhysicsOutput(it.parameter, it.vertex, it.scale, it.weight, type(it.source), it.reflect) },
				group.segments.map { PhysicsSegment(it.length, it.mobility, it.delay, it.acceleration) },
				group.normalization.let { PhysicsNormalization(it.positionMin, it.positionDefault, it.positionMax, it.angleMin, it.angleDefault, it.angleMax) }) },
			ir.parameters.associate { it.id to PhysicsEngine.Range(it.min, it.max, it.default) }, ir.physics.fps?.takeIf { it > 0f })
		return object : FrameSession {
			override val simulatesPhysics: Boolean = engine != null

			override fun render(parameters: Map<String, Float>, deltaSeconds: Float, frame: FrameSpec, meshes: Set<String>?): RasterImage {
				val values = parameters + engine?.step(parameters, deltaSeconds).orEmpty()
				return rasterizer.render(geometry.evaluate(values), colors.at(values), frame, meshes)
			}

			override fun close() = geometry.close()
		}
	}

	private fun type(source: PhysicsSource) = if (source == PhysicsSource.ANGLE) PhysicsSourceType.ANGLE else PhysicsSourceType.X
}
