package org.umamo.edit

/** Per-keyform channel values; null leaves the existing value in place. */
data class KeyformChannelsEdit(
	val opacity: Float? = null,
	val drawOrder: Float? = null,
	val multiplyColor: List<Float>? = null,
	val screenColor: List<Float>? = null,
	val glueIntensity: Float? = null,
	val flipX: Boolean? = null,
	val flipY: Boolean? = null,
) {
	init {
		opacity?.let { require(it.isFinite()) { "Opacity must be finite" } }
		drawOrder?.let { require(it.isFinite()) { "Draw order must be finite" } }
		multiplyColor?.let {
			require(it.size == 3 && it.all { c -> c.isFinite() }) { "multiplyColor must be 3 finite floats" }
		}
		screenColor?.let {
			require(it.size == 3 && it.all { c -> c.isFinite() }) { "screenColor must be 3 finite floats" }
		}
		glueIntensity?.let { require(it.isFinite()) { "glueIntensity must be finite" } }
	}
}
