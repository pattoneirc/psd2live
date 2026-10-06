package io.github.psd2live.format.model

/** An immutable float vector compared by content, so IR values are plain data. */
public class Floats private constructor(private val values: FloatArray) {
	public val size: Int get() = values.size
	public operator fun get(index: Int): Float = values[index]
	public fun toArray(): FloatArray = values.copyOf()
	/** The backing array without a copy, for readers that never modify it. */
	public fun shared(): FloatArray = values
	public fun isEmpty(): Boolean = values.isEmpty()

	override fun equals(other: Any?): Boolean = other is Floats && values.contentEquals(other.values)
	override fun hashCode(): Int = values.contentHashCode()
	override fun toString(): String = values.contentToString()

	public companion object {
		public val Empty: Floats = Floats(FloatArray(0))
		public fun of(values: FloatArray): Floats = Floats(values.copyOf())
		/** Adopts [values] without copying; the caller must never modify the array afterwards. */
		public fun wrap(values: FloatArray): Floats = Floats(values)
		public fun values(vararg values: Float): Floats = Floats(values.copyOf())
	}
}

/** An immutable int vector compared by content. */
public class Ints private constructor(private val values: IntArray) {
	public val size: Int get() = values.size
	public operator fun get(index: Int): Int = values[index]
	public fun toArray(): IntArray = values.copyOf()
	/** The backing array without a copy, for readers that never modify it. */
	public fun shared(): IntArray = values

	override fun equals(other: Any?): Boolean = other is Ints && values.contentEquals(other.values)
	override fun hashCode(): Int = values.contentHashCode()
	override fun toString(): String = values.contentToString()

	public companion object {
		public val Empty: Ints = Ints(IntArray(0))
		public fun of(values: IntArray): Ints = Ints(values.copyOf())
		/** Adopts [values] without copying; the caller must never modify the array afterwards. */
		public fun wrap(values: IntArray): Ints = Ints(values)
		public fun values(vararg values: Int): Ints = Ints(values.copyOf())
	}
}

/** Immutable bytes (encoded images) compared by content. */
public class Bytes private constructor(private val values: ByteArray) {
	public val size: Int get() = values.size
	public fun toArray(): ByteArray = values.copyOf()
	/** The backing array without a copy, for readers that never modify it. */
	public fun shared(): ByteArray = values

	override fun equals(other: Any?): Boolean = other is Bytes && values.contentEquals(other.values)
	override fun hashCode(): Int = values.contentHashCode()
	override fun toString(): String = "Bytes($size)"

	public companion object {
		public val Empty: Bytes = Bytes(ByteArray(0))
		public fun of(values: ByteArray): Bytes = Bytes(values.copyOf())
		/** Adopts [values] without copying; the caller must never modify the array afterwards. */
		public fun wrap(values: ByteArray): Bytes = Bytes(values)
	}
}

/** Linear RGB, each component nominally 0..1. */
public data class Rgb(val red: Float, val green: Float, val blue: Float) {
	public companion object {
		public val White: Rgb = Rgb(1f, 1f, 1f)
		public val Black: Rgb = Rgb(0f, 0f, 0f)
	}
}

/** How a drawable's color combines with what lies under it. */
public enum class ColorBlend {
	NORMAL, ADD_PREMULTIPLIED, MULTIPLY_PREMULTIPLIED,
	ADD, ADD_GLOW, DARKEN, MULTIPLY, COLOR_BURN, LINEAR_BURN, LIGHTEN, SCREEN, COLOR_DODGE,
	OVERLAY, SOFT_LIGHT, HARD_LIGHT, LINEAR_LIGHT, HUE, COLOR,
}

/** How a drawable's alpha combines with what lies under it. */
public enum class AlphaBlend { OVER, ATOP, OUT, CONJOINT, DISJOINT }
