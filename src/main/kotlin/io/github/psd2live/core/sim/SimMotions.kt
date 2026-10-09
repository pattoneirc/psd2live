package io.github.psd2live.core.sim

import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionPresetSettings
import io.github.psd2live.core.MotionPresets
import io.github.psd2live.core.OneShotMotionPlayer
import io.github.psd2live.core.RigEditOverlay

/** The model's motions as a simulation trains on them or is checked against them. */
internal object SimMotions {
    /** How long a looping motion is played for, and the most of a one-shot one, seconds. */
    private const val LOOP_SECONDS = 8f
    private const val MAX_SECONDS = 10f
    /** After the motion: back to rest over this long, then held while the body settles. */
    private const val BACK = 0.3f
    private const val SETTLE = 1.5f

    /** The motion [name] names in [overlay]: a clip by its ID or export name, or a built-in one; null when none does. */
    fun resolve(overlay: RigEditOverlay, name: String): MotionClip? =
        overlay.motionClips.firstOrNull { it.id == name }
            ?: OneShotMotionPlayer.clipOf(name, overlay.skeleton, overlay.motionClips, overlay.motionPresets)
            ?: if (name.equals("Idle", ignoreCase = true))
                runCatching { MotionPresets.clip(name, name, overlay.skeleton, overlay.motionPresets[name] ?: MotionPresetSettings()) }.getOrNull()
            else null

    /**
     * [clip] at [fps], frame by frame per parameter: faded in as it fades in, once (a looping one for
     * [LOOP_SECONDS]), then back to rest - each parameter's [rest] value - and held while a body settles. The
     * caller keeps the parameters it reads and clamps them to their ranges.
     */
    fun sample(clip: MotionClip, fps: Int, rest: (String) -> Float = { 0f }): Map<String, FloatArray> {
        val length = if (clip.loop) LOOP_SECONDS else minOf(clip.duration, MAX_SECONDS)
        val n = (length * fps).toInt().coerceAtLeast(1)
        val back = (BACK * fps).toInt().coerceAtLeast(1)
        val total = n + back + (SETTLE * fps).toInt()
        val sampled = (0 until n).map { MotionClips.sampleAll(clip, it.toDouble() / fps, clip.loop) }
        fun ease(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        return sampled.flatMap { it.keys }.toSet().associate { id ->
            val home = rest(id.raw)
            val last = sampled.last()[id] ?: home
            id.raw to FloatArray(total) { f ->
                val fade = if (clip.fadeIn > 0f) ease(f / (clip.fadeIn * fps)) else 1f
                when {
                    f < n -> home + ((sampled[f][id] ?: home) - home) * fade
                    f < n + back -> home + (last - home) * (1f - ease((f - n + 1f) / back))
                    else -> home
                }
            }
        }
    }
}
