package io.github.psd2live.format.model

/**
 * The flat, evaluated rig every exporter consumes: what a document compiles to, with every generator
 * already applied. Its capabilities are the union of the targets'; each exporter lowers it to what its
 * format can hold and reports what it lost.
 *
 * Geometry follows one convention throughout: a mesh's rest positions and every deformer's forms live
 * in the space of their parent deformer (canvas pixels, y down, at the root); a warp's children use its
 * normalized 0..1 lattice space, a rotation's children its local pixel frame.
 *
 * Every id is stable across regeneration (see the format design's stable-id rule).
 */
public data class RigIR(
	val canvas: Canvas,
	val parameters: List<Parameter>,
	val parameterLinks: List<ParameterLink> = emptyList(),
	val parameterTree: List<ParameterNode> = emptyList(),
	/** Named parameter roles a runtime drives by itself, such as EyeBlink and LipSync. */
	val parameterRoles: List<ParameterRole> = emptyList(),
	val parts: List<Part> = emptyList(),
	val rootChildren: List<ChildRef> = emptyList(),
	val rootPart: String? = null,
	val deformers: List<Deformer> = emptyList(),
	val meshes: List<Mesh> = emptyList(),
	val glues: List<Glue> = emptyList(),
	val renderRoot: RenderGroup = RenderGroup(null, DEFAULT_DRAW_ORDER, emptyList()),
	val textures: Textures = Textures(),
	val physics: Physics = Physics(),
	val clips: List<Clip> = emptyList(),
	/**
	 * Parameter values at which formats that store a canvas-space base mesh bake it (for example a mouth
	 * kept open so its texture coordinates cover the whole artwork). Empty means every default.
	 */
	val restPose: Map<String, Float> = emptyMap(),
	val authoring: Authoring = Authoring(),
) {
	public companion object {
		public const val DEFAULT_DRAW_ORDER: Int = 500
	}
}

/**
 * The document canvas in pixels and the world origin (canvas x, negated canvas y). [pixelsPerUnit] is the
 * bake scale a runtime format records, or null when the document has none of its own.
 */
public data class Canvas(
	val width: Float, val height: Float,
	val originX: Float = 0f, val originY: Float = 0f,
	val pixelsPerUnit: Float? = null,
)

public data class Parameter(
	val id: String, val name: String, val min: Float, val max: Float, val default: Float,
	/** A blend parameter drives additive shapes rather than keyform grids. */
	val blend: Boolean = false,
	/** Values wrap around the range (a full turn) instead of clamping. */
	val repeat: Boolean = false,
	/** Values the parameter snaps to, or null when it is continuous. */
	val keys: Floats? = null,
)

/** Two parameters shown as one 2D pad. */
public data class ParameterLink(val horizontal: String, val vertical: String)

public sealed interface ParameterNode {
	public data class Param(val id: String) : ParameterNode
	public data class Group(
		val id: String, val name: String, val open: Boolean, val children: List<ParameterNode>,
		val label: GroupLabel = GroupLabel.None,
	) : ParameterNode
}

public sealed interface GroupLabel {
	public data object None : GroupLabel
	/** A named swatch: RED, ORANGE, YELLOW, GREEN, BLUE or PURPLE. */
	public data class Preset(val name: String) : GroupLabel
	public data class Custom(val argb: Int) : GroupLabel
}

public data class ParameterRole(val role: String, val parameters: List<String>)

public sealed interface ChildRef {
	public data class PartRef(val id: String) : ChildRef
	public data class MeshRef(val id: String) : ChildRef
}

/** How a part's children composite: through to the parent, as a group, or isolated. */
public enum class GroupMode { PASS_THROUGH, GROUPED, ISOLATED }

/** How an isolated part's rendered group blends, and what masks it. */
public data class Composite(
	val blend: ColorBlend = ColorBlend.NORMAL,
	val alphaBlend: AlphaBlend = AlphaBlend.OVER,
	val maskedBy: List<String> = emptyList(),
	val maskedByParts: List<String> = emptyList(),
	val invertMask: Boolean = false,
	val opacity: Float = 1f,
	val multiply: Rgb = Rgb.White,
	val screen: Rgb = Rgb.Black,
)

public data class Part(
	val id: String, val name: String, val children: List<ChildRef>,
	val visible: Boolean = true, val sketch: Boolean = false, val selectable: Boolean = true,
	val groupMode: GroupMode = GroupMode.PASS_THROUGH,
	val drawOrder: Int = RigIR.DEFAULT_DRAW_ORDER,
	val channels: Channels = emptyMap(),
	val composite: Composite = Composite(),
	val shapes: List<BlendBinding<PartShape>> = emptyList(),
)

public sealed interface Deformer {
	public val id: String
	public val name: String
	public val parent: String?
	public val part: String?
	public val channels: Channels
	public val opacity: Float
	public val multiply: Rgb
	public val screen: Rgb
	public val selectable: Boolean
	public val visible: Boolean
	public val enabled: Boolean

	/** A lattice of (columns + 1) x (rows + 1) control points; [bilinear] interpolates each cell as a quad. */
	public data class Warp(
		override val id: String, override val name: String, override val parent: String?, override val part: String?,
		val rows: Int, val columns: Int, val bilinear: Boolean,
		val lattice: KeyGrid<LatticePoints>?,
		override val channels: Channels = emptyMap(),
		override val opacity: Float = 1f, override val multiply: Rgb = Rgb.White, override val screen: Rgb = Rgb.Black,
		override val selectable: Boolean = true, override val visible: Boolean = true, override val enabled: Boolean = true,
		val shapes: List<BlendBinding<LatticeShape>> = emptyList(),
	) : Deformer

	public data class Rotation(
		override val id: String, override val name: String, override val parent: String?, override val part: String?,
		val baseAngle: Float,
		val pivot: KeyGrid<Pivot>?,
		override val channels: Channels = emptyMap(),
		override val opacity: Float = 1f, override val multiply: Rgb = Rgb.White, override val screen: Rgb = Rgb.Black,
		val flipX: Boolean = false, val flipY: Boolean = false,
		override val selectable: Boolean = true, override val visible: Boolean = true, override val enabled: Boolean = true,
		val shapes: List<BlendBinding<PivotShape>> = emptyList(),
		/** Length of the editor handle, or null for the default. */
		val handleLength: Float? = null,
	) : Deformer
}

/** Rest geometry: positions in the parent's space, UVs on the texture page, triangle indices. */
public data class MeshGeometry(val positions: Floats, val uvs: Floats, val indices: Ints)

public data class Mesh(
	val id: String, val name: String, val parent: String?,
	val blend: ColorBlend = ColorBlend.NORMAL,
	val alphaBlend: AlphaBlend = AlphaBlend.OVER,
	val maskedBy: List<String> = emptyList(),
	val invertMask: Boolean = false,
	val geometry: MeshGeometry?,
	val offsets: KeyGrid<MeshOffsets>?,
	val channels: Channels = emptyMap(),
	val drawOrder: Float = RigIR.DEFAULT_DRAW_ORDER.toFloat(),
	val opacity: Float = 1f,
	val multiply: Rgb = Rgb.White,
	val screen: Rgb = Rgb.Black,
	val culling: Boolean = false,
	val visible: Boolean = true,
	val selectable: Boolean = true,
	/** Another mesh whose texture this one samples instead of its own. */
	val textureSource: String? = null,
	/** Texture page index, or -1 when unassigned. */
	val page: Int = -1,
	val tile: String? = null,
	val shapes: List<BlendBinding<MeshShape>> = emptyList(),
	val userData: String = "",
)

/** Vertex [a] of mesh A and [b] of mesh B pulled together with the given weights. */
public data class GluePair(val a: Int, val b: Int, val weightA: Float, val weightB: Float)

public data class Glue(
	val id: String?, val meshA: String, val meshB: String, val pairs: List<GluePair>,
	val channels: Channels = emptyMap(), val intensity: Float = 1f,
)

public sealed interface RenderNode

public data class RenderMesh(val id: String) : RenderNode

/** The draw-order tree: a group sorts its children by draw order and composites as one when isolated. */
public data class RenderGroup(
	val part: String?, val drawOrder: Int, val children: List<RenderNode>,
	val channels: Channels = emptyMap(), val composite: Composite? = null,
) : RenderNode
