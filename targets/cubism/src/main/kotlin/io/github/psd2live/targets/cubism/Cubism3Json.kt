package io.github.psd2live.targets.cubism

import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.CurveSegment
import io.github.psd2live.format.model.CurveTarget
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.PhysicsSource

/**
 * motion3.json and physics3.json from the IR. The single writer of both: the editor's preview bundle and
 * every export go through it, so the files the editor plays are the files it exports.
 */
public object Cubism3Json {
	/**
	 * The motion3 JSON of [clip], keeping only curves on [availableParameters] (and part curves on [availableParts],
	 * all of them when null), with its curves on part and model opacity, the EyeBlink and LipSync effects, curve
	 * fades and user data events; null when nothing remains.
	 */
	public fun motion3(clip: Clip, availableParameters: Set<String>, availableParts: Set<String>? = null): String? {
		val parameterCurves = clip.curves.filter { it.parameter in availableParameters }
		val targetCurves = clip.targetCurves.filter { t -> (t.target as? CurveTarget.PartOpacity)?.let { availableParts == null || it.part in availableParts } ?: true }
		if (parameterCurves.isEmpty() && targetCurves.isEmpty()) return null
		val curves = parameterCurves.map { MotionCurve("Parameter", it.parameter, it.startTime, it.startValue, it.segments, it.fadeIn, it.fadeOut) } +
			targetCurves.map { t ->
				val (target, id) = when (val target = t.target) {
					is CurveTarget.PartOpacity -> "PartOpacity" to target.part
					CurveTarget.ModelOpacity -> "Model" to "Opacity"
					CurveTarget.EyeBlink -> "Model" to "EyeBlink"
					CurveTarget.LipSync -> "Model" to "LipSync"
				}
				MotionCurve(target, id, t.startTime, t.startValue, t.segments, t.fadeIn, t.fadeOut)
			}
		val segmentCount = curves.sumOf { it.segments.size }
		val pointCount = curves.sumOf { curve -> 1 + curve.segments.sumOf { if (it is CurveSegment.Bezier) 3 else 1 } }
		val events = clip.events.sortedBy { it.time }
		val fades = buildString {
			clip.fadeIn?.let { append(" \"FadeInTime\": $it,") }
			clip.fadeOut?.let { append(" \"FadeOutTime\": $it,") }
		}
		val userData = if (events.isEmpty()) "" else
			",\n  \"UserData\": [${events.joinToString(",") { "{\"Time\":${it.time},\"Value\":${quote(it.value)}}" }}]"
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "Duration": ${clip.duration},
		    "Fps": ${clip.fps.toDouble()},$fades
		    "Loop": ${clip.loop},
		    "AreBeziersRestricted": true,
		    "CurveCount": ${curves.size},
		    "TotalSegmentCount": $segmentCount,
		    "TotalPointCount": $pointCount,
		    "UserDataCount": ${events.size},
		    "TotalUserDataSize": ${events.sumOf { it.value.encodeToByteArray().size }}
		  },
		  "Curves": [${curves.joinToString(",", transform = ::curveJson)}]$userData
		}
		""".trimIndent()
	}

	/** A motion3 curve: its target and id, points, and fades of its own when it has them. */
	private class MotionCurve(
		val target: String, val id: String, val startTime: Float, val startValue: Float, val segments: List<CurveSegment>,
		val fadeIn: Float?, val fadeOut: Float?,
	)

	private fun curveJson(curve: MotionCurve): String {
		val segments = buildList<Number> {
			add(curve.startTime); add(curve.startValue)
			for (segment in curve.segments) {
				when (segment) {
					is CurveSegment.Linear -> add(0)
					is CurveSegment.Bezier -> { add(1); add(segment.c1Time); add(segment.c1Value); add(segment.c2Time); add(segment.c2Value) }
					is CurveSegment.Stepped -> add(2)
					is CurveSegment.InverseStepped -> add(3)
				}
				add(segment.time); add(segment.value)
			}
		}.joinToString(",") { number -> if (number is Int) number.toString() else number.toFloat().toString() }
		val fades = (curve.fadeIn?.let { ",\"FadeInTime\":$it" } ?: "") + (curve.fadeOut?.let { ",\"FadeOutTime\":$it" } ?: "")
		return """{"Target":"${curve.target}","Id":${quote(curve.id)}$fades,"Segments":[$segments]}"""
	}

	/** The physics3 JSON of [groups]; [fps] 0 leaves the frame rate to the runtime. Null when there are none. */
	public fun physics3(groups: List<PhysicsGroup>, fps: Int): String? {
		if (groups.isEmpty()) return null
		val dictionary = groups.map { "{ \"Id\": ${quote(it.id)}, \"Name\": ${quote(it.name)} }" }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "PhysicsSettingCount": ${groups.size},
		    "TotalInputCount": ${groups.sumOf { it.inputs.size }},
		    "TotalOutputCount": ${groups.sumOf { it.outputs.size }},
		    "VertexCount": ${groups.sumOf { it.segments.size + 1 }},
		    ${if (fps > 0) "\"Fps\": $fps," else ""}
		    "EffectiveForces": { "Gravity": { "X": 0, "Y": -1 }, "Wind": { "X": 0, "Y": 0 } },
		    "PhysicsDictionary": [${dictionary.joinToString(",")}]
		  },
		  "PhysicsSettings": [${groups.joinToString(",", transform = ::settingJson)}]
		}
		""".trimIndent()
	}

	/** Particle positions down the strand, root first; Cubism reads only the radii, but writes both. */
	public fun vertexY(group: PhysicsGroup): List<Float> = group.segments.runningFold(0f) { y, s -> y + s.length }

	private fun type(source: PhysicsSource) = when (source) {
		PhysicsSource.X -> "X"
		PhysicsSource.Y -> "Y"
		PhysicsSource.ANGLE -> "Angle"
	}

	private fun settingJson(group: PhysicsGroup): String {
		val inputs = group.inputs.joinToString(",\n") { input ->
			"""    { "Source": { "Target": "Parameter", "Id": ${quote(input.parameter)} }, "Weight": ${input.weight}, "Type": "${type(input.source)}", "Reflect": ${input.reflect} }"""
		}
		val ys = vertexY(group)
		val vertices = (listOf(null) + group.segments).mapIndexed { i, s ->
			"""    { "Position": { "X": 0, "Y": ${ys[i]} }, "Mobility": ${s?.mobility ?: 1f}, "Delay": ${s?.delay ?: 1f}, "Acceleration": ${s?.acceleration ?: 1f}, "Radius": ${s?.length ?: 0f} }"""
		}.joinToString(",\n")
		val outputs = group.outputs.joinToString(",\n") { output ->
			"""    { "Destination": { "Target": "Parameter", "Id": ${quote(output.parameter)} }, "VertexIndex": ${output.vertex}, "Scale": ${output.scale}, "Weight": ${output.weight}, "Type": "${type(output.source)}", "Reflect": ${output.reflect} }"""
		}
		val n = group.normalization
		return """
		{
		  "Id": ${quote(group.id)},
		  "Input": [
		$inputs
		  ],
		  "Output": [
		$outputs
		  ],
		  "Vertices": [
		$vertices
		  ],
		  "Normalization": {
		    "Position": { "Minimum": ${n.positionMin}, "Default": ${n.positionDefault}, "Maximum": ${n.positionMax} },
		    "Angle": { "Minimum": ${n.angleMin}, "Default": ${n.angleDefault}, "Maximum": ${n.angleMax} }
		  }
		}
		""".trimIndent()
	}

	private val prettyWriter = kotlinx.serialization.json.Json { prettyPrint = true }

	/** A JSON number in scientific notation, not a digit run inside an identifier. */
	private val exponent = Regex("""(?<![A-Za-z0-9_])-?(?:\d+\.\d*|\d*\.\d+|\d+)[eE][+-]?\d+""")

	/**
	 * Cubism Framework's JSON reader accepts a number only when a comma or a line break ends it, and only in
	 * plain decimal: an exponent is a syntax error. Pretty-printing supplies the break, and exponents are
	 * written out in full, so generated sidecars stay inside that subset.
	 */
	public fun normalize(source: String): String = exponent.replace(prettyWriter.encodeToString(
		kotlinx.serialization.json.JsonElement.serializer(), kotlinx.serialization.json.Json.parseToJsonElement(source))) { match ->
		java.math.BigDecimal(match.value).toPlainString()
	}

	/** A JSON string literal, escaped the way kotlinx.serialization's JsonPrimitive prints it. */
	internal fun quote(text: String): String = buildString {
		append('"')
		for (c in text) when (c) {
			'"' -> append("\\\"")
			'\\' -> append("\\\\")
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'\t' -> append("\\t")
			'\b' -> append("\\b")
			'\u000C' -> append("\\f")
			else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
		}
		append('"')
	}
}
