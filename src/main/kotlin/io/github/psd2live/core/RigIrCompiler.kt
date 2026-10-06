package io.github.psd2live.core

import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.ParameterRole
import io.github.psd2live.format.model.Physics
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.TexturePage
import io.github.psd2live.format.model.TileArt
import io.github.psd2live.targets.cubism.PuppetIr

/**
 * Compiles an evaluated rig into the neutral [RigIR] every exporter reads: the rig itself, its packed
 * texture pages, the active physics and the motions the export settings select, already compiled from
 * their presets into curves.
 */
internal object RigIrCompiler {
	fun compile(preview: RigPreviewModel, tileArt: Boolean = false): RigIR = compile(preview.analysis, preview.atlas, preview.rig, preview.config, tileArt)

	/** [tileArt] adds every tile's unpacked source pixels, which only formats with editable source layers need. */
	fun compile(analysis: PipelineAnalysis, atlas: PackedAtlas, rig: BuiltRig, config: PipelineConfig, tileArt: Boolean = false): RigIR {
		val parameterIds = rig.puppet.parameters.mapTo(linkedSetOf()) { it.id.raw }
		val physicsGroups = PhysicsCatalog.active(analysis, config, parameterIds)
		val base = PuppetIr.toIr(
			rig.puppet,
			physics = Physics(physicsGroups.map(Physics3Json::group), config.rigEdits.physicsFps.toFloat()),
			clips = clips(rig, config, physicsGroups, parameterIds),
			parameterRoles = buildList {
				if (config.motionBasic && config.motionBlink && !config.meshOnly) add(ParameterRole("EyeBlink", listOf("ParamEyeLOpen", "ParamEyeROpen")))
				add(ParameterRole("LipSync", listOf("ParamMouthOpenY")))
			},
		)
		return base.copy(
			textures = base.textures.copy(
				pages = atlas.pages.map { TexturePage(it.image.width, it.image.height, Bytes.wrap(it.png)) },
				bindings = rig.pageByDrawableId,
				tileArt = if (!tileArt) emptyList() else PuppetSourceAtlas.rastersByTile(analysis).map { (tile, raster) ->
				TileArt(tile.raw, raster.width, raster.height, Bytes.wrap(raster.rgba))
			}),
			// The open mouth keeps its texture coordinates over the whole artwork in a canvas-space base mesh.
			restPose = if (config.rigEdits.importedCmo3 != null) emptyMap() else mapOf(StandardParameters.MOUTH_OPEN.raw to 1f),
		)
	}

	/**
	 * The motions an export carries, in the order the runtime manifest lists them: the basic presets, the
	 * skeleton presets, then the user's own clips. An edited preset exports its clip in place of the generated
	 * one; a deleted one is left out, as is any clip with no curve on an existing parameter.
	 */
	private fun clips(rig: BuiltRig, config: PipelineConfig, physicsGroups: List<RigPhysicsEdit>, parameterIds: Set<String>): List<Clip> {
		if (!config.exportMotions || config.meshOnly) return emptyList()
		val clips = MotionClips.reconcileParameters(config.rigEdits.motionClips, rig.puppet.parameters)
		val skeleton = config.rigEdits.skeleton
		val result = ArrayList<Clip>()
		fun add(clip: Clip?) {
			if (clip != null && clip.curves.any { it.parameter in parameterIds }) result += clip.copy(curves = clip.curves.filter { it.parameter in parameterIds })
		}
		fun authored(group: String, file: String, clip: MotionClip) = Clip(clip.id, clip.name, group, file, clip.duration, clip.fps, clip.loop,
			clip.fadeIn, clip.fadeOut, clip.curves.map(MotionGenerator::curve))
		fun builtin(group: String, name: String, exclude: Set<String> = emptySet()) {
			val settings = config.rigEdits.motionPresets[name] ?: MotionPresetSettings()
			if (settings.deleted) return
			val file = name.replaceFirstChar(Char::lowercase)
			val override = MotionClips.overrideOf(clips, name)
			if (override != null) return add(authored(group, file, override))
			val tracks = MotionPresets.tracks(name, skeleton, settings, exclude)
			if (tracks.isEmpty()) return
			add(Clip(name, name, group, file, MotionPresets.duration(name, tracks, settings), 30f, MotionPresets.loops(name),
				curves = tracks.map(MotionGenerator::curve)))
		}
		if (config.motionBasic) {
			if (config.motionIdle) {
				// Parameters the exported physics drives stay out of the idle.
				val physicsDriven = if (config.exportIncludePhysics) physicsGroups.flatMapTo(HashSet()) { it.outputParameters } else emptySet()
				builtin("Idle", "Idle", physicsDriven)
			}
			if (config.motionBlink) builtin("Blink", "Blink")
			if (config.motionNod) builtin("Nod", "Nod")
			if (config.motionShake) builtin("Shake", "Shake")
		}
		if (config.motionSkeleton) {
			for (preset in SkeletonMotions.presets) {
				if (config.rigEdits.motionPresets[preset.name]?.disabled == true) continue
				// A looping preset is another idle, played from the idle group beside the plain one.
				builtin(if (preset.loop) "Idle" else preset.name, preset.name)
			}
		}
		// The user's own motions: a loop joins the idles, a one-shot is its own group under its name.
		val stems = MotionClips.exportStems(clips)
		for (clip in clips.filter { it.builtin == null && it.enabled })
			add(authored(if (clip.loop) "Idle" else clip.name, stems.getValue(clip.id), clip))
		return result
	}
}
