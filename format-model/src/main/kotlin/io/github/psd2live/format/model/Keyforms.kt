package io.github.psd2live.format.model

/** One parameter axis of a keyform grid and the parameter values it is keyed at, ascending. */
public data class KeyAxis(val parameter: String, val keys: Floats)

/** The form stored at one grid point; [coordinate] holds one key index per axis. */
public data class KeyCell<F>(val coordinate: Ints, val form: F)

/**
 * Values keyed over the cartesian product of parameter axes and interpolated between keys. An object
 * with no axes holds exactly one cell. Cells may be sparse; a missing combination is not authored.
 */
public data class KeyGrid<F>(val axes: List<KeyAxis>, val cells: List<KeyCell<F>>) {
	public companion object {
		public fun <F> single(form: F): KeyGrid<F> = KeyGrid(emptyList(), listOf(KeyCell(Ints.Empty, form)))
	}
}

/** Per-vertex offsets added to a mesh's rest positions, in its parent's space. */
public data class MeshOffsets(val deltas: Floats)

/** The absolute control points of a warp lattice, in its parent's space. */
public data class LatticePoints(val points: Floats)

/** A rotation deformer's pivot, angle in degrees and uniform scale, in its parent's space. */
public data class Pivot(val x: Float, val y: Float, val angle: Float, val scale: Float)

/** Scalar, color and flag properties that can be keyed independently of geometry. */
public enum class Channel { DRAW_ORDER, OPACITY, MULTIPLY_COLOR, SCREEN_COLOR, FLIP_X, FLIP_Y, GLUE_INTENSITY }

public sealed interface ChannelValue {
	public data class Scalar(val value: Float) : ChannelValue
	public data class Color(val color: Rgb) : ChannelValue
	/** Not interpolated: a flag snaps to the nearer key. */
	public data class Flag(val value: Boolean) : ChannelValue
}

/** Keyed channels of one object; an absent channel keeps the object's static value. */
public typealias Channels = Map<Channel, KeyGrid<ChannelValue>>

/** Additive shape of a mesh at one blend key: offsets plus channel values. */
public data class MeshShape(
	val deltas: Floats, val drawOrder: Float, val opacity: Float, val multiply: Rgb, val screen: Rgb,
)

/** Additive shape of a part at one blend key. */
public data class PartShape(val drawOrder: Float, val opacity: Float, val multiply: Rgb, val screen: Rgb)

/** Additive shape of a warp lattice at one blend key. */
public data class LatticeShape(val points: Floats, val opacity: Float, val multiply: Rgb, val screen: Rgb)

/** Additive shape of a rotation deformer at one blend key. */
public data class PivotShape(
	val x: Float, val y: Float, val angle: Float, val scale: Float, val flipX: Boolean, val flipY: Boolean,
	val opacity: Float, val multiply: Rgb, val screen: Rgb,
)

/** Where a blend's weight is limited, as a piecewise-linear function of another parameter. */
public data class BlendLimit(val parameter: String, val points: List<BlendLimitPoint>)

public data class BlendLimitPoint(val value: Float, val weight: Float)

/**
 * An additive shape keyed on one parameter. [shapes] holds one entry per key, null where the object has
 * no shape at that key; the key at [neutralIndex] contributes nothing.
 */
public data class BlendBinding<S>(
	val parameter: String, val keys: Floats, val neutralIndex: Int, val shapes: List<S?>,
	val limits: List<BlendLimit> = emptyList(),
)
