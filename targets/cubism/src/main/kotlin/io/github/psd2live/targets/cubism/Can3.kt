package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.Feature
import io.github.psd2live.format.compile.Handling
import io.github.psd2live.format.compile.LossEntry
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.CurveSegment
import io.github.psd2live.format.model.CurveTarget
import io.github.psd2live.format.model.RigIR
import org.umamo.format.cmo3.Cmo3Author
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.caff.CaffEntry
import org.umamo.format.cmo3.caff.CompressOption
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A Cubism Animator project (`.can3`): every clip of the rig as a scene on the cmo3 written beside it.
 *
 * The can3 holds no model of its own. Its one resource links `<base>.cmo3` by file name, its tracks key
 * parameters by id and parts by id and GUID, so it opens next to the cmo3 of the same export: [write] takes
 * that cmo3's part GUIDs. Each scene has the Animator's fixed track layout (a root group and the model track
 * with its visual, parameter, part opacity, lip sync and blink effects); parameter curves go to the parameter
 * effect, part opacity curves to the part effect and the rig's opacity to the visual opacity. Keys land on
 * whole frames and Bezier handles keep their times. Curves on the EyeBlink and LipSync effects have no
 * Animator track and are reported by [losses].
 */
public object Can3 {
	/**
	 * The can3 of [ir]'s clips beside `<baseName>.cmo3`, or null when no clip has a curve it can hold. [partGuids]
	 * maps part ids to the cmo3's part GUIDs.
	 */
	public fun write(
		ir: RigIR, baseName: String, partGuids: Map<String, String>,
		fileFormatVersion: String = Cmo3Author.FRESH_FILE_FORMAT_VERSION,
	): ByteArray? {
		val xml = mainXml(ir, baseName, partGuids, fileFormatVersion) ?: return null
		val entry = CaffEntry("main.xml", CaffArchive.TAG_MAIN_XML, xml.encodeToByteArray(), CompressOption.FAST, obfuscated = true)
		return CaffCodec.write(CaffArchive(obfuscateKey = 0x42, entries = listOf(entry)))
	}

	/** What [write] leaves out of [ir]'s clips: curves on what the rig lacks, and on the blink and lip sync effects. */
	public fun losses(ir: RigIR): List<LossEntry> {
		val parameters = ir.parameters.mapTo(HashSet()) { it.id }
		val parts = ir.parts.mapTo(HashSet()) { it.id }
		return ir.clips.flatMap { clip ->
			clip.curves.filter { it.parameter !in parameters }.map { loss(clip, "Curve on unknown parameter ${it.parameter} is not written") } +
				clip.targetCurves.mapNotNull { curve ->
					when (val target = curve.target) {
						is CurveTarget.PartOpacity -> if (target.part in parts) null else loss(clip, "Curve on unknown part ${target.part} is not written")
						CurveTarget.ModelOpacity -> null
						CurveTarget.EyeBlink -> loss(clip, "EyeBlink curve has no Animator track and is not written")
						CurveTarget.LipSync -> loss(clip, "LipSync curve has no Animator track and is not written")
					}
				}
		}
	}

	private fun loss(clip: Clip, note: String) = LossEntry(clip.id, Feature.TIMELINE, Handling.DROPPED, note = note)

	/** The main.xml text of [write], prologue included; null when there is no scene. */
	internal fun mainXml(ir: RigIR, baseName: String, partGuids: Map<String, String>, fileFormatVersion: String): String? {
		val scenes = ir.clips.mapNotNull { scene(it, ir, baseName) }
		if (scenes.isEmpty()) return null
		val root = Document(ir, baseName, partGuids).build(scenes, fileFormatVersion)
		val body = StringBuilder()
		root.write(body)
		return prologue(root) + body
	}

	// ---- Scenes from clips ----

	internal enum class CurveType { LINEAR, STEP, INVERSE_STEP, BEZIER }

	/** A key on a whole frame; the handle frames are fractional, 0 where [fillHandles] places them. */
	internal class Key(
		val frame: Int, val value: Double, var curve: CurveType = CurveType.LINEAR,
		var prevFrame: Double = 0.0, var prevValue: Double? = null,
		var nextFrame: Double = 0.0, var nextValue: Double? = null,
	)

	private sealed interface Owner {
		/** A curve on the parameter [id]. */
		data class Parameter(val id: String) : Owner
		/** A curve on the opacity of part [id]. */
		data class Part(val id: String) : Owner
	}

	/** A curve's keys and fades; [owner] is null for the rig's opacity. */
	private class Track(val owner: Owner?, val keys: List<Key>, val fadeInMs: Int?, val fadeOutMs: Int?)

	private class Scene(
		val name: String, val fps: Double, val frames: Int, val loop: Boolean, val fadeInMs: Int, val fadeOutMs: Int,
		val tracks: List<Track>, val opacity: Track?, val userData: Map<Int, String>,
	)

	private fun scene(clip: Clip, ir: RigIR, baseName: String): Scene? {
		val parameters = ir.parameters.mapTo(HashSet()) { it.id }
		val parts = ir.parts.mapTo(HashSet()) { it.id }
		val fps = seconds(clip.fps).takeIf { it > 0.0 } ?: 30.0
		val seen = HashSet<Owner>()
		val tracks = ArrayList<Track>()
		var opacity: Track? = null
		fun track(owner: Owner?, startTime: Float, startValue: Float, segments: List<CurveSegment>, fadeIn: Float?, fadeOut: Float?) =
			Track(owner, keys(startTime, startValue, segments, fps), fadeIn?.let(::millis), fadeOut?.let(::millis))
		for (curve in clip.curves) {
			val owner = Owner.Parameter(curve.parameter)
			if (curve.parameter in parameters && seen.add(owner))
				tracks += track(owner, curve.startTime, curve.startValue, curve.segments, curve.fadeIn, curve.fadeOut)
		}
		for (curve in clip.targetCurves) {
			when (val target = curve.target) {
				is CurveTarget.PartOpacity -> {
					val owner = Owner.Part(target.part)
					if (target.part in parts && seen.add(owner))
						tracks += track(owner, curve.startTime, curve.startValue, curve.segments, curve.fadeIn, curve.fadeOut)
				}
				CurveTarget.ModelOpacity -> if (opacity == null)
					opacity = track(null, curve.startTime, curve.startValue, curve.segments, curve.fadeIn, curve.fadeOut)
				CurveTarget.EyeBlink, CurveTarget.LipSync -> Unit
			}
		}
		if (tracks.isEmpty() && opacity == null) return null
		val lastKey = (tracks + listOfNotNull(opacity)).maxOf { track -> track.keys.maxOfOrNull { it.frame } ?: 0 }
		val frames = if (clip.duration > 0f) max(1L, (seconds(clip.duration) * fps).roundToLong()).toInt() else max(1, lastKey)
		val userData = sortedMapOf<Int, String>()
		for (event in clip.events) userData[max(0, (seconds(event.time) * fps).toInt())] = event.value
		return Scene(
			"$baseName.${clip.file}", fps, frames, clip.loop, clip.fadeIn?.let(::millis) ?: -1, clip.fadeOut?.let(::millis) ?: -1,
			tracks, opacity, userData,
		)
	}

	private fun millis(time: Float): Int = (seconds(time) * 1000.0).toInt()

	/** [time] as the decimal the motion3 writer prints, so 0.35 s at 30 fps is frame 10.5 and rounds up. */
	private fun seconds(time: Float): Double = time.toString().toDouble()

	/**
	 * The keys of a motion curve: each point on its nearest frame, the curve type of the segment leaving it, and
	 * a Bezier segment's control points as the handles of its two ends, their times kept as fractional frames.
	 */
	internal fun keys(startTime: Float, startValue: Float, segments: List<CurveSegment>, fps: Double): List<Key> {
		fun frame(time: Float) = max(0L, (seconds(time) * fps).roundToLong()).toInt()
		fun frameF(time: Float) = max(0.0, seconds(time) * fps)
		val keys = ArrayList<Key>(segments.size + 1)
		keys += Key(frame(startTime), startValue.toDouble(), prevFrame = frameF(startTime), nextFrame = frameF(startTime))
		for (segment in segments) {
			val last = keys.last()
			when (segment) {
				is CurveSegment.Linear -> last.curve = CurveType.LINEAR
				is CurveSegment.Bezier -> {
					last.curve = CurveType.BEZIER
					last.nextFrame = frameF(segment.c1Time)
					last.nextValue = segment.c1Value.toDouble()
				}
				is CurveSegment.Stepped -> last.curve = CurveType.STEP
				is CurveSegment.InverseStepped -> last.curve = CurveType.INVERSE_STEP
			}
			keys += Key(frame(segment.time), segment.value.toDouble()).also { key ->
				if (segment is CurveSegment.Bezier) {
					key.prevFrame = frameF(segment.c2Time)
					key.prevValue = segment.c2Value.toDouble()
				}
			}
		}
		val sorted = keys.sortedBy { it.frame }
		val kept = ArrayList<Key>(sorted.size)
		for (key in sorted) {
			val previous = kept.lastOrNull()
			if (previous == null || previous.frame != key.frame || abs(previous.value - key.value) >= 1e-6) kept += key
		}
		kept.last().curve = CurveType.LINEAR
		fillHandles(kept)
		return kept
	}

	/** Places the handles a key has none for a third of the way to its neighbours, and the ends' outer handles on the key. */
	private fun fillHandles(keys: List<Key>) {
		for ((index, key) in keys.withIndex()) {
			if (key.prevFrame == 0.0) key.prevFrame = if (index > 0) (keys[index - 1].frame + key.frame * 2.0) / 3.0 else key.frame.toDouble()
			if (key.nextFrame == 0.0) key.nextFrame = if (index + 1 < keys.size) (key.frame * 2.0 + keys[index + 1].frame) / 3.0 else key.frame.toDouble()
		}
	}

	// ---- The document ----

	/** One XML element; a shared object gets its `xs.id` when made and its `xs.idx` when written. */
	private class Node(val tag: String) {
		val attrs = ArrayList<Pair<String, String>>()
		val children = ArrayList<Node>()
		var text: String? = null
		var id: String? = null
		var idx: Int? = null

		fun attr(name: String, value: String): Node = apply { attrs += name to value }
		fun add(child: Node): Node = child.also { children += it }

		fun write(out: StringBuilder) {
			out.append('<').append(tag)
			for ((name, value) in attrs) out.append(' ').append(name).append("=\"").append(escape(value)).append('"')
			id?.let { out.append(" xs.id=\"").append(it).append('"') }
			idx?.let { out.append(" xs.idx=\"").append(it).append('"') }
			val text = text
			if (children.isEmpty() && text == null) { out.append("/>"); return }
			out.append('>')
			if (text != null) out.append(escape(text))
			for (child in children) child.write(out)
			out.append("</").append(tag).append('>')
		}

		fun visit(block: (Node) -> Unit) {
			block(this)
			for (child in children) child.visit(block)
		}
	}

	private class Document(val ir: RigIR, val baseName: String, val partGuids: Map<String, String>) {
		private var nextId = 1
		private val shared = ArrayList<Node>()
		private val parameters = ir.parameters.associateBy { it.id }
		private val modelFile = "$baseName.cmo3"
		private val animationFile = "$baseName.can3"

		/** A shared object, listed under `<shared>` and referenced everywhere else. */
		private fun shared(tag: String): Node = Node(tag).also { it.id = "#${nextId++}"; shared += it }

		private val animation = shared("CAnimation")
		private val resourceManager = shared("CResourceManager")
		private val resourceGuid = shared("CResourceGuid")
			.attr("note", "$baseName resource").attr("uuid", uuid("resource", baseName))
		private val resourceGroupGuid = shared("CResourceGroupGuid")
			.attr("note", "Root Resource Group").attr("uuid", ROOT_RESOURCE_GROUP_UUID)
		private val resourceData = shared("CResourceData")

		fun build(scenes: List<Scene>, fileFormatVersion: String): Node {
			val built = scenes.map(::scene)
			animation(built)
			resources(built.first())
			val root = Node("root").attr("fileFormatVersion", fileFormatVersion)
			val sharedNode = root.add(Node("shared"))
			val order = SHARED_ORDER.withIndex().associate { (index, tag) -> tag to index }
			for ((index, node) in shared.sortedBy { order.getValue(it.tag) }.withIndex()) {
				node.idx = index
				sharedNode.add(node)
			}
			root.add(Node("main")).add(ref("CAnimation", animation))
			return root
		}

		private class BuiltScene(val source: Node, val guid: Node)

		private fun animation(scenes: List<BuiltScene>) {
			animation.add(s("name", "Untitled Animation"))
			animation.add(text("file", "file", animationFile))
			animation.add(list("carray_list", "_scenes", scenes.map { ref("CSceneSource", it.source) }))
			animation.add(ref("CSceneSource", scenes.first().source, "currentScene"))
			animation.add(ref("CResourceManager", resourceManager, "resourceManager"))
			animation.add(named("EditorEdition", "editorEdition")).add(i("edition", 15))
			val blending = animation.add(named("CSceneBlendingSettingsSource", "sceneBlendingSettings"))
			blending.add(named("CSceneBlendingSettingsGuid", "guid").attr("uuid", uuid("scene-blending", animationFile)).attr("note", "(no debug info)"))
			val playlist = Node("PlaylistData")
			playlist.add(named("CPlaylistGuid", "guid").attr("uuid", uuid("playlist", animationFile)).attr("note", "(no debug info)"))
			playlist.add(s("name", "test"))
			playlist.add(list("carray_list", "list", scenes.map { scene ->
				Node("PlaylistItemData").also { item ->
					item.add(named("ASceneBlendingData", "super"))
					item.add(ref("CSceneGuid", scene.guid, "guid"))
				}
			}))
			blending.add(list("carray_list", "playlists", listOf(playlist)))
			blending.add(list("carray_list", "sceneGroups", emptyList()))
			animation.add(b("hideAbsolutePathIfLinkError", false))
			animation.add(named("Animation", "targetVersion").attr("v", "FOR_UNITY_SDK"))
		}

		private fun resources(first: BuiltScene) {
			val group = resourceManager.add(named("CResourceGroup", "rootGroup"))
			group.add(named("ACResourceEntry", "super")).apply {
				add(nul("parentGuid"))
				add(ref("CResourceManager", resourceManager, "_resourceManager"))
			}
			group.add(ref("CResourceGroupGuid", resourceGroupGuid, "guid"))
			group.add(list("carray_list", "_childGuids", listOf(ref("CResourceGuid", resourceGuid))))
			group.add(s("name", "Resources"))
			resourceManager.add(list("carray_list", "_resourceRefList", listOf(ref("CResourceData", resourceData))))
			resourceManager.add(map("resourceGuidMap", listOf(Node("entry").also {
				it.add(ref("CResourceGuid", resourceGuid, "key"))
				it.add(ref("CResourceData", resourceData, "value"))
			})))
			resourceManager.add(list("carray_list", "_resourceGroupList", emptyList()))
			resourceManager.add(map("resourceGroupGuidMap", emptyList()))
			resourceManager.add(ref("CSceneSource", first.source, "_sceneSource"))

			resourceData.add(named("ACResourceEntry", "super")).apply {
				add(ref("CResourceGroupGuid", resourceGroupGuid, "parentGuid"))
				add(ref("CResourceManager", resourceManager, "_resourceManager"))
			}
			resourceData.add(nul("customName"))
			val file = resourceData.add(named("CResource_Linked_Model", "resourceRef")).add(named("ACResource_File", "super"))
			file.add(text("file", "srcFile", modelFile))
			file.add(ref("CResourceGuid", resourceGuid, "guid"))
			file.add(s("name", modelFile))
		}

		private fun scene(scene: Scene): BuiltScene {
			val rootTrack = shared("CMvTrack_Group_Source")
			val source = shared("CSceneSource")
				.attr("exportMotionFile", "true").attr("loopMotionEditAssist", scene.loop.toString())
			val rootTrackGuid = shared("CTrackGuid")
				.attr("note", "Root CMvTrack_Group_Source").attr("uuid", uuid("root-track", scene.name))
			val modelTrackGuid = shared("CTrackGuid")
				.attr("note", "$baseName CMvTrack_Live2DModel_Source").attr("uuid", uuid("track", scene.name))
			val sceneGuid = shared("CSceneGuid").attr("note", "${scene.name} CSceneSource").attr("uuid", uuid("scene", scene.name))
			val modelTrack = shared("CMvTrack_Live2DModel_Source")
			val visual = shared("CMvEffect_VisualDefault")
			val parameterEffect = shared("CMvEffect_Live2DParameter")
			val partsEffect = shared("CMvEffect_Live2DPartsVisible")
			val lipSync = shared("CMvEffect_LipSync")
			val eyeBlink = shared("CMvEffect_EyeBlink")
			val adaptType = shared("AdaptType").also { it.add(s("name", "DEFAULT")) }
			val context = AttrContext(modelTrack, adaptType)

			// The root group track.
			rootTrack.add(trackSource(rootTrack, "Root", rootTrackGuid, scene.frames, source, null, emptyList(), null, emptyMap()))
			rootTrack.add(list("carray_list", "_childTrackGuids", listOf(ref("CTrackGuid", modelTrackGuid))))
			rootTrack.add(rect(640f, 480f))

			// The scene.
			source.add(s("sceneName", scene.name))
			source.add(named("CImageCanvas", "canvas")).apply {
				add(i("pixelWidth", canvasWidth))
				add(i("pixelHeight", canvasHeight))
				add(named("CColor", "background"))
			}
			source.add(ref("CSceneGuid", sceneGuid, "guid"))
			source.add(s("tag", scene.name))
			source.add(named("CTrackSourceSet", "trackSourceSet"))
				.add(list("carray_list", "_sources", listOf(ref("CMvTrack_Group_Source", rootTrack), ref("CMvTrack_Live2DModel_Source", modelTrack))))
			source.add(ref("CMvTrack_Group_Source", rootTrack, "rootTrack"))
			source.add(named("CMvMovieInfo", "movieInfo").attr("durationLock", "false")).apply {
				add(i("width", 320)); add(i("height", 240)); add(i("duration", scene.frames)); add(d("fps", scene.fps))
				add(i("workspaceStart", 0)); add(i("workspaceEnd", scene.frames))
				add(named("CColor", "background"))
				add(i("fadeInMSec", scene.fadeInMs)); add(i("fadeOutMSec", scene.fadeOutMs))
				add(b("isBezierRestricted", true)); add(b("isLoopMotion", scene.loop))
				add(i("startFrame", 0))
				add(named("CFrameIndexType", "frameIndexType").attr("v", "ZERO_INDEX"))
			}
			source.add(ref("CAnimation", animation, "_animation"))
			source.add(map("marker", emptyList()))
			source.add(named("CCurveType", "defaultParameterCurveType").attr("v", "SMOOTH"))
			source.add(named("CCurveType", "defaultPartCurveType").attr("v", "STEP"))
			source.add(b("fixAspect", false))
			source.add(named("Animation", "targetVersion").attr("v", "FOR_UNITY_SDK"))
			source.add(b("lockMarker", false))
			source.add(named("array_list", "onionSkinMarker").attr("count", "0"))
			source.add(b("lockOnionSkinMarker", false))
			source.add(named("ParameterBookmarkLabelCarrierTrackSet", "parameterBookmarkCarrierTrackSet")).apply {
				add(ref("CSceneSource", source, "_owner"))
				add(list("carray_list", "_trackSortInfo", emptyList()))
			}

			// The model track and its effects.
			val effects = listOf(
				ref("CMvEffect_EyeBlink", eyeBlink), ref("CMvEffect_LipSync", lipSync), ref("CMvEffect_Live2DParameter", parameterEffect),
				ref("CMvEffect_Live2DPartsVisible", partsEffect), ref("CMvEffect_VisualDefault", visual),
			)
			val linked = modelTrack.add(named("ICMvTrack_Linked", "super"))
			linked.add(trackSource(modelTrack, baseName, modelTrackGuid, scene.frames, source, visual, effects, rootTrackGuid, scene.userData))
			linked.add(ref("CResourceGuid", resourceGuid, "_resourceGuid"))
			modelTrack.add(ref("CMvEffect_Live2DParameter", parameterEffect, "keyParamEffect"))
			modelTrack.add(ref("CMvEffect_Live2DPartsVisible", partsEffect, "partsVisibleEffect"))
			modelTrack.add(ref("CMvEffect_LipSync", lipSync, "lipSyncEffect"))
			modelTrack.add(ref("CMvEffect_EyeBlink", eyeBlink, "eyeBlinkEffect"))
			modelTrack.add(nul("formEditEffect"))
			modelTrack.add(named("FormAnimationSet", "formAnimationSet")).apply {
				add(map("formMapOnGlobal", emptyList()))
				add(map("formMapOnLocal", emptyList()))
				add(ref("CMvTrack_Live2DModel_Source", modelTrack, "trackSource"))
			}
			modelTrack.add(rect(canvasWidth.toFloat(), canvasHeight.toFloat()))
			modelTrack.add(named("ParameterBookmarkLabelSet", "parameterBookmarkLabelSet")).add(list("carray_list", "labels", emptyList()))

			// Visual defaults: placement, opacity (the rig's opacity curve when the clip has one) and frame step.
			val xy = context.point("xy", "XY")
			val scaleX = context.static("scalex", "Scale X", 100.0)
			val scaleY = context.static("scaley", "Scale Y", 100.0)
			val rotate = context.static("rotate", "Rotate", 0.0)
			val shear = context.static("shear", "Shear", 0.0)
			val anchor = context.point("anchor", "Anchor")
			val opacity = scene.opacity?.let { context.animated("opacity", "Opacity", null, fades(it), it.keys, 0.0, 1.0, 1.0) }
				?: context.static("opacity", "Opacity", 1.0, 0.0, 1.0)
			val artPathWidth = context.static("artPathWidth", "Art Path Width", 100.0, 0.0, Double.MAX_VALUE)
			val frameStep = context.frameStep()
			effect(visual, "VisualDefault", false, modelTrack,
				listOf(xy, anchor, scaleX, scaleY, rotate, shear, opacity, artPathWidth, frameStep),
				listOf("xy" to xy, "scalex" to scaleX, "scaley" to scaleY, "rotate" to rotate, "shear" to shear, "anchor" to anchor,
					"opacity" to opacity, "artPathWidth" to artPathWidth, "frameStep" to frameStep))
			visual.add(ref(xy.tag, xy, "attrXY"))
			visual.add(ref(scaleX.tag, scaleX, "attrScaleX"))
			visual.add(ref(scaleY.tag, scaleY, "attrScaleY"))
			visual.add(ref(rotate.tag, rotate, "attrRotate"))
			visual.add(ref(anchor.tag, anchor, "attrAnchorXY"))
			visual.add(ref(shear.tag, shear, "attrShear"))
			visual.add(ref(opacity.tag, opacity, "attrOpacity"))
			visual.add(ref(frameStep.tag, frameStep, "attrFrameStep"))
			visual.add(ref(artPathWidth.tag, artPathWidth, "attrArtPathWidth"))

			// Parameter and part opacity curves.
			val parameterAttrs = ArrayList<Pair<String, Node>>()
			val partAttrs = ArrayList<Pair<String, Node>>()
			for (track in scene.tracks) {
				when (val owner = track.owner) {
					is Owner.Parameter -> {
						val parameter = parameters.getValue(owner.id)
						val idstr = PARAM_PREFIX + owner.id
						val options = fades(track) + s("KEY_PARAM_ID", PARAM_VALUE_PREFIX + owner.id)
						parameterAttrs += idstr to context.animated(idstr, parameter.name.ifBlank { owner.id }, null, options, track.keys,
							parameter.min.toDouble(), parameter.max.toDouble(), parameter.default.toDouble())
					}
					is Owner.Part -> {
						val guid = named("CPartGuid", "guid").attr("note", "${owner.id} CPartGuid")
							.attr("uuid", partGuids[owner.id] ?: uuid("part", owner.id))
						val options = fades(track) + s("KEY_PARTS_VISIBLE_ID", owner.id)
						val attr = context.animated(PARTS_OPACITY_PREFIX + owner.id, owner.id, guid, options, track.keys, 0.0, 1.0, 1.0)
						partAttrs += PARTS_OPACITY_PREFIX + partKey(owner.id) to attr
					}
					null -> Unit
				}
			}
			effect(parameterEffect, "Effects:Live2DParam", false, modelTrack, parameterAttrs.map { it.second }, parameterAttrs)
			// The Animator writes the part effect's map keys without a field name.
			effect(partsEffect, PARTS_OPACITY_PREFIX, false, modelTrack, partAttrs.map { it.second }, partAttrs, keyName = null)

			// Lip sync and blink effects, at rest.
			val lipSyncAttrs = listOf(
				"soundLevel" to context.static("soundLevel", "Sound Level", 0.0),
				"lipSyncScale" to context.static("lipSyncScale", "Lip Sync Scale", 1.0),
				"lipSyncBase" to context.static("lipSyncBase", "Lip Sync Base", 0.0),
				"lipSyncLevel" to context.static("lipSyncLevel", "Lip Sync Level", 0.0),
			)
			effect(lipSync, "Effects:LipSync", true, modelTrack, lipSyncAttrs.map { it.second }, lipSyncAttrs)
			lipSync.add(list("carray_list", "effectParameterAttrIds", role("LipSync", LIP_SYNC_DEFAULT).map { Node("CAttrId").attr("idstr", PARAM_PREFIX + it) }))
			lipSync.add(nul("syncTrackGuid"))
			lipSync.add(b("isInvert", false))
			lipSync.add(b("isRelative", true))
			val eyeBlinkAttrs = listOf(
				"eyeOpen" to context.static("eyeOpen", "Eye Open", 1.0),
				"effectLevel" to context.static("effectLevel", "Effect Level", 1.0),
			)
			effect(eyeBlink, "Effects:EyeBlink", true, modelTrack, eyeBlinkAttrs.map { it.second }, eyeBlinkAttrs)
			eyeBlink.add(list("carray_list", "effectParameterAttrIds", role("EyeBlink", EYE_BLINK_DEFAULT).map { Node("CAttrId").attr("idstr", PARAM_PREFIX + it) }))
			eyeBlink.add(b("invert", false))
			eyeBlink.add(b("relative", true))
			return BuiltScene(source, sceneGuid)
		}

		private val canvasWidth = max(1, ir.canvas.width.toInt())
		private val canvasHeight = max(1, ir.canvas.height.toInt())

		/** The rig's parameters in [role], or the Cubism standard ones when it names none. */
		private fun role(role: String, default: List<String>): List<String> =
			ir.parameterRoles.filter { it.role == role }.flatMap { it.parameters }.filter(parameters::containsKey).ifEmpty { default }

		private fun trackSource(
			track: Node, name: String, guid: Node, frames: Int, scene: Node, visual: Node?, effects: List<Node>, parent: Node?,
			userData: Map<Int, String>,
		): Node = named("ICMvTrack_Source", "super").apply {
			add(s("name", name))
			add(b("isUserRenamed", false))
			add(ref("CTrackGuid", guid, "guid"))
			add(i("start", 0)); add(i("internalOffset", 0)); add(i("duration", frames))
			add(b("editable", true)); add(b("visible", true)); add(b("mute", false))
			add(b("isGuide", false)); add(b("isRepeat", false)); add(b("soloSwitch", false))
			add(named("CVisualHandler", "visualHandler")).add(ref(track.tag, track, "track"))
			add(named("CSoundHandler", "soundHandler")).add(ref(track.tag, track, "track"))
			add(nul("soundEffect"))
			add(if (visual == null) nul("visualEffect") else ref(visual.tag, visual, "visualEffect"))
			add(named("CMvEffectManager", "effectManager")).add(list("array", "effectList", effects, "ICMvEffect"))
			add(if (parent == null) nul("parentGuid") else ref("CTrackGuid", parent, "parentGuid"))
			add(ref("CSceneSource", scene, "_sceneSource"))
			add(map("userData", userData.map { (frame, value) -> Node("entry").also { it.add(i("key", frame)); it.add(s("value", value)) } }))
			add(nul("keys"))
		}

		private fun effect(
			effect: Node, id: String, canDelete: Boolean, track: Node, attrs: List<Node>, entries: List<Pair<String, Node>>,
			keyName: String? = "key",
		) {
			val base = effect.add(named("ICMvEffect", "super"))
			base.add(named("CEffectId", "id").attr("idstr", id))
			base.add(b("isActive", true))
			base.add(b("canDelete", canDelete))
			base.add(list("array", "attrList", attrs.map { ref(it.tag, it) }, "ICMvAttr"))
			base.add(Node("hash_map").attr("xs.n", "attrMap").attr("count", entries.size.toString()).apply {
				for ((key, attr) in entries) add(Node("entry")).apply {
					add(Node("CAttrId").apply { keyName?.let { attr("xs.n", it) } }.attr("idstr", key))
					add(ref(attr.tag, attr, "value"))
				}
			})
			base.add(ref(track.tag, track, "track"))
		}

		private fun fades(track: Track): List<Node> =
			listOfNotNull(track.fadeOutMs?.takeIf { it >= 0 }?.let { i("KEY_ATTR_FADE_OUT", it) }, track.fadeInMs?.takeIf { it >= 0 }?.let { i("KEY_ATTR_FADE_IN", it) })

		/** Makes a scene's attributes, all on its model track and adapt type. */
		private inner class AttrContext(val track: Node, val adaptType: Node) {
			private fun base(attr: Node, idstr: String, name: String, guid: Node?, options: List<Node>) {
				attr.add(named("ICMvAttr", "super")).apply {
					add(b("isShyMode", false))
					add(b("isFreezeMode", false))
					add(named("CAttrId", "id").attr("idstr", idstr))
					add(s("name", name))
					add(guid ?: nul("guid"))
					add(b("isActive", true))
					add(ref("AdaptType", adaptType, "adaptType"))
					add(Node("hash_map").attr("xs.n", "optionParam").attr("count", options.size.toString()).attr("keyType", "string").apply { options.forEach(::add) })
					add(ref(track.tag, track, "track"))
				}
			}

			fun point(idstr: String, name: String): Node = shared("CMvAttrPt").also { attr ->
				base(attr, idstr, name, null, emptyList())
				attr.add(named("CXY_TNSSequence", "valueDataXY")).apply {
					add(list("array", "points", emptyList(), "CPtTNS"))
					add(named("GVector2", "basePt")).apply { add(f("x", 0.0)); add(f("y", 0.0)) }
					add(ref("CMvAttrPt", attr, "attr"))
				}
			}

			fun frameStep(): Node = shared("CMvAttrI").also { attr ->
				base(attr, "frameStep", "Frame Step", null, emptyList())
				attr.add(named("CIntSequence", "valueData")).apply {
					add(sequence(attr, 0.0, 0.0, 0, emptyList(), Int.MAX_VALUE, Int.MIN_VALUE, Double.NaN, -1, 1.0))
					add(list("array", "points", emptyList(), "CSeqPt"))
				}
				attr.add(i("rangeMin", 0))
				attr.add(i("rangeMax", 100))
			}

			fun static(idstr: String, name: String, base: Double, rangeMin: Double = -Double.MAX_VALUE, rangeMax: Double = Double.MAX_VALUE): Node =
				shared("CMvAttrF").also { attr ->
					base(attr, idstr, name, null, emptyList())
					attr.add(named("CMutableSequence", "valueData")).apply {
						add(sequence(attr, 0.0, 0.0, 0, emptyList(), 0, 0, base, 0, base))
						add(list("array", "points", emptyList(), "CBezierPt"))
						add(list("carray_list", "curveTypes", emptyList()))
					}
					range(attr, rangeMin, rangeMax)
				}

			fun animated(
				idstr: String, name: String, guid: Node?, options: List<Node>, keys: List<Key>, rangeMin: Double, rangeMax: Double, default: Double,
			): Node = shared("CMvAttrF").also { attr ->
				base(attr, idstr, name, guid, options)
				val values = keys.flatMap { listOf(it.prevValue ?: it.value, it.value, it.nextValue ?: it.value) }
				val low = values.min()
				val high = values.max()
				val first = keys.first()
				val last = keys.last()
				val baseValue = if (first.frame == 0) first.value else default
				attr.add(named("CMutableSequence", "valueData")).apply {
					add(sequence(attr, low, high, first.frame, keys.map { it.frame }, first.frame, last.frame, last.value, last.frame, baseValue))
					add(list("array", "points", keys.map(::bezierPoint), "CBezierPt"))
					add(list("carray_list", "curveTypes", keys.map { Node("CCurveType").attr("v", it.curve.name) }))
				}
				range(attr, min(rangeMin, low), max(rangeMax, high))
			}

			private fun range(attr: Node, rangeMin: Double, rangeMax: Double) {
				attr.add(d("rangeMin", rangeMin))
				attr.add(d("rangeMax", rangeMax))
				attr.add(b("isRepeat", false))
				attr.add(d("repeatMin", -Double.MAX_VALUE))
				attr.add(d("repeatMax", Double.MAX_VALUE))
				attr.add(nul("linked_keyFormsForObject"))
			}

			private fun sequence(
				attr: Node, curMin: Double, curMax: Double, posStart: Int, frames: List<Int>, keyMin: Int, keyMax: Int,
				lastValue: Double, lastPos: Int, baseValue: Double,
			): Node = named("ACValueSequence", "super").apply {
				add(d("curMin", curMin))
				add(d("curMax", curMax))
				add(i("posStart", posStart))
				add(named("int-array", "keyPts2").attr("count", frames.size.toString()).also { array ->
					if (frames.isNotEmpty()) array.text = frames.joinToString(" ")
				})
				add(i("keyMin", keyMin))
				add(i("keyMax", keyMax))
				add(d("lastValue", lastValue))
				add(i("lastPos", lastPos))
				add(ref(attr.tag, attr, "attr"))
				add(d("baseValue", baseValue))
			}

			private fun bezierPoint(key: Key): Node = Node("CBezierPt").apply {
				add(named("CSeqPt", "anchor")).apply {
					add(b("isCorner", false))
					add(i("pos", key.frame))
					add(d("doubleValue", key.value))
				}
				add(control("next", key.nextFrame, key.nextValue ?: key.value))
				add(control("prev", key.prevFrame, key.prevValue ?: key.value))
			}

			private fun control(name: String, frame: Double, value: Double): Node = named("CBezierCtrlPt", name).apply {
				add(f("posF", frame))
				add(i("pos", 0))
				add(d("doubleValue", value))
				add(b("isPosOptimized", false))
			}
		}
	}

	// ---- Elements ----

	private fun named(tag: String, name: String): Node = Node(tag).attr("xs.n", name)
	private fun text(tag: String, name: String, value: String): Node = named(tag, name).also { it.text = value }
	private fun s(name: String, value: String): Node = text("s", name, value)
	private fun b(name: String, value: Boolean): Node = text("b", name, value.toString())
	private fun i(name: String, value: Int): Node = text("i", name, value.toString())
	private fun d(name: String, value: Double): Node = text("d", name, number(value))
	private fun f(name: String, value: Double): Node = text("f", name, number(value))
	private fun nul(name: String): Node = named("null", name)
	private fun rect(width: Float, height: Float): Node = named("GRectF", "bounds").apply {
		add(f("x", 0.0)); add(f("y", 0.0)); add(f("width", width.toDouble())); add(f("height", height.toDouble()))
	}

	/** A reference to the shared [target], as field [name] or as a list item. */
	private fun ref(tag: String, target: Node, name: String? = null): Node =
		Node(tag).apply { name?.let { attr("xs.n", it) } }.attr("xs.ref", requireNotNull(target.id))

	private fun list(tag: String, name: String, items: List<Node>, type: String? = null): Node =
		named(tag, name).attr("count", items.size.toString()).apply {
			type?.let { attr("type", it) }
			items.forEach(::add)
		}

	/** A map of entries; an empty one names its key type as the Animator does. */
	private fun map(name: String, entries: List<Node>): Node =
		named("hash_map", name).attr("count", entries.size.toString()).apply {
			if (entries.isEmpty()) attr("keyType", "string")
			entries.forEach(::add)
		}

	/** A number as the Animator writes it: whole values with one decimal, others with at most six. */
	internal fun number(value: Double): String = when {
		value == -Double.MAX_VALUE -> "-1.7976931348623157E308"
		value == Double.MAX_VALUE -> "1.7976931348623157E308"
		value.isNaN() -> "NaN"
		value.isFinite() && abs(value - Math.rint(value)) < 1e-6 -> String.format(java.util.Locale.ROOT, "%.1f", Math.rint(value) + 0.0)
		else -> String.format(java.util.Locale.ROOT, "%.6f", value).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
	}

	private fun escape(value: String): String = buildString(value.length) {
		for (c in value) when (c) {
			'&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
			else -> append(c)
		}
	}

	/** A GUID for [key] of [kind], the same on every export. */
	private fun uuid(kind: String, key: String): String = UUID.nameUUIDFromBytes("can3:$kind\u0000$key".encodeToByteArray()).toString()

	/**
	 * The part effect's map key of [part]: its words upper-cased and joined by underscores, with a leading
	 * `PART` made `PARTS` (`PartArm` is `PARTS_ARM`).
	 */
	internal fun partKey(part: String): String {
		val words = ArrayList<String>()
		val word = StringBuilder()
		fun flush() { if (word.isNotEmpty()) { words += word.toString(); word.clear() } }
		for ((index, c) in part.withIndex()) {
			if (c !in 'a'..'z' && c !in 'A'..'Z' && c !in '0'..'9') { flush(); continue }
			val previous = part.getOrNull(index - 1)
			val next = part.getOrNull(index + 1)
			if (word.isNotEmpty() && previous != null && (
					(previous.isLetter() && c.isDigit()) || (previous.isDigit() && c.isLetter()) ||
						(previous.isLowerCase() && c.isUpperCase()) ||
						(previous.isUpperCase() && c.isUpperCase() && next != null && next in 'a'..'z'))) flush()
			word.append(c.uppercaseChar())
		}
		flush()
		if (words.firstOrNull() == "PART") words[0] = "PARTS"
		return words.joinToString("_")
	}

	/** The XML declaration, the class versions and an import for every Animator class the document uses. */
	private fun prologue(root: Node): String {
		val present = HashSet<String>()
		root.visit { node ->
			present += node.tag
			for ((_, value) in node.attrs) present += value
			node.text?.let { present += it }
		}
		return buildString {
			append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
			for ((name, version) in VERSIONS) append("<?version ").append(name).append(':').append(version).append("?>")
			append("<?import ").append(TARGET_VERSION_IMPORT).append("?>")
			for (fqcn in IMPORTS) {
				val short = fqcn.substringAfterLast('.').substringAfterLast('$')
				if (short in present) append("<?import ").append(fqcn).append("?>")
			}
		}
	}

	private const val PARAM_PREFIX = "live2dParam_"
	private const val PARAM_VALUE_PREFIX = "live2dParam:"
	private const val PARTS_OPACITY_PREFIX = "live2DPartsOpacity_"
	private const val ROOT_RESOURCE_GROUP_UUID = "2d05fd78-9ac1-4eb0-bb6a-5cf21147e7b7"
	private val LIP_SYNC_DEFAULT = listOf("ParamMouthOpenY")
	private val EYE_BLINK_DEFAULT = listOf("ParamEyeLOpen", "ParamEyeROpen")

	/** The order the Animator lists shared objects in, by class. */
	private val SHARED_ORDER = listOf(
		"CMvTrack_Group_Source", "CSceneSource", "CTrackGuid", "CMvTrack_Live2DModel_Source", "CMvAttrPt", "AdaptType", "CMvAttrF",
		"CMvAttrI", "CMvEffect_VisualDefault", "CMvEffect_Live2DParameter", "CMvEffect_Live2DPartsVisible", "CMvEffect_LipSync",
		"CMvEffect_EyeBlink", "CAnimation", "CResourceGuid", "CResourceManager", "CResourceGroupGuid", "CResourceData", "CSceneGuid",
	)

	private val VERSIONS = listOf(
		"CSceneSource" to 3, "CAnimation" to 4, "CMvParameter_Group" to 1, "SerializeFormatVersion" to 2,
		"CMvEffect_VisualDefault" to 1, "CMvMovieInfo" to 3, "CBezierCtrlPt" to 2,
	)

	private const val TARGET_VERSION_IMPORT = "com.live2d.cubism.CETargetVersion\$Animation"
	private const val ANIMATION = "com.live2d.cubism.doc.animation"
	private const val EFFECT = "$ANIMATION.movie.effect"
	private const val SCENE_BLENDING = "com.live2d.cubism.doc.modeling.ui.viewer.sceneBlending.viewerData_SceneBlending"

	/** Every class import the Animator may need, in its order; one is written when its short name occurs. */
	private val IMPORTS = listOf(
		"$ANIMATION.CAnimation", "$ANIMATION.CSceneSource", "$ANIMATION.formAnimation.FormAnimationSet",
		"$ANIMATION.movie.core.CMvMovieInfo",
		"$EFFECT.CMvEffect_EyeBlink", "$EFFECT.CMvEffect_LipSync", "$EFFECT.CMvEffect_Live2DParameter",
		"$EFFECT.CMvEffect_Live2DPartsVisible", "$EFFECT.CMvEffect_VisualDefault", "$EFFECT.CMvParameter_Group",
		"$EFFECT.CSoundHandler", "$EFFECT.CVisualHandler", "$EFFECT.ICMvEffect",
		"$EFFECT.attr.CMvAttrF", "$EFFECT.attr.CMvAttrI", "$EFFECT.attr.CMvAttrPt", "$EFFECT.attr.ICMvAttr", "$EFFECT.attr.ICMvAttr\$AdaptType",
		"$EFFECT.attr.value.ACValueSequence", "$EFFECT.attr.value.CBezierCtrlPt", "$EFFECT.attr.value.CBezierPt",
		"$EFFECT.attr.value.CCurveType", "$EFFECT.attr.value.CFrameIndexType", "$EFFECT.attr.value.CMutableSequence",
		"$EFFECT.attr.value.CSeqPt",
		"$ANIMATION.movie.track.ICMvTrack_Linked", "$ANIMATION.movie.track.ICMvTrack_Source",
		"com.live2d.graphics.CImageCanvas", "com.live2d.cubism.doc.model.options.edition.EditorEdition",
		"$SCENE_BLENDING.ASceneBlendingData", "com.live2d.cubism.doc.model.id.CEffectId", "com.live2d.type.CResourceGuid",
		"$ANIMATION.movie.res.CResourceManager", "$ANIMATION.movie.track.CMvEffectManager",
		"$ANIMATION.movie.track.CMvTrack_Group_Source", "$ANIMATION.movie.track.CMvTrack_Live2DModel_Source",
		"com.live2d.type.CTrackGuid", "com.live2d.cubism.doc.model.deformer.CTrackSourceSet",
		"com.live2d.type.CPlaylistGuid", "com.live2d.type.CSceneBlendingSettingsGuid",
		"$SCENE_BLENDING.CSceneBlendingSettingsSource", "com.live2d.type.CSceneGuid",
		"$SCENE_BLENDING.PlaylistData", "$SCENE_BLENDING.PlaylistItemData",
		"com.live2d.type.CPartGuid", "com.live2d.type.CColor", "com.live2d.cubism.doc.model.id.CAttrId",
		"com.live2d.graphics3d.type.GRectF", "com.live2d.graphics3d.type.GVector2",
		"com.live2d.cubism.view.palette.scene.parameterBookmark.ParameterBookmarkLabelSet",
		"com.live2d.cubism.view.palette.scene.parameterBookmark.ParameterBookmarkLabelCarrierTrackSet",
		"$EFFECT.attr.value.CIntSequence", "$EFFECT.attr.xy.CPtTNS", "$EFFECT.attr.xy.CXY_TNSSequence",
		"$ANIMATION.movie.res.ACResourceEntry", "$ANIMATION.movie.res.CResourceData", "$ANIMATION.movie.res.CResourceGroup",
		"$ANIMATION.movie.track.resource.ACResource_File", "$ANIMATION.movie.track.resource.CResource_Linked_Model",
		"com.live2d.type.CResourceGroupGuid",
	)
}
