package io.github.psd2live.core

/** The Cubism-safe JSON subset; see [io.github.psd2live.targets.cubism.Cubism3Json.normalize]. */
internal object CubismJson {
	fun normalize(source: String): String = io.github.psd2live.targets.cubism.Cubism3Json.normalize(source)
}
