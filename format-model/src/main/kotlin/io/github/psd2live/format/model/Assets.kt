package io.github.psd2live.format.model

/**
 * Texture pages and the tiles packed onto them.
 *
 * [pages] are the packed images exports write; [bindings] names the page each mesh samples there (a mesh
 * without a binding uses its own [Mesh.page]). A page's [TexturePage.png] may be empty when the rig is
 * described without pixels; exporters that write textures require it. [tilePages] are the page sizes the
 * tiles' placements refer to, which an editor may keep apart from the packed pages.
 */
public data class Textures(
	val pages: List<TexturePage> = emptyList(),
	val bindings: Map<String, Int> = emptyMap(),
	val tilePages: List<PageSize> = emptyList(),
	val tiles: List<TextureTile> = emptyList(),
	/** Whether stored mesh UVs address the pages (true) or the tiles' own rasters. */
	val uvsAddressPages: Boolean = true,
	val alphaThreshold: Int = 1,
	val extrude: Int = 2,
	/** Unpacked source pixels per tile, for formats that keep editable source layers. */
	val tileArt: List<TileArt> = emptyList(),
)

/** A tile's source pixels as straight RGBA, row by row. */
public data class TileArt(val tile: String, val width: Int, val height: Int, val rgba: Bytes)

public data class TexturePage(val width: Int, val height: Int, val png: Bytes = Bytes.Empty)

public data class PageSize(val width: Int, val height: Int)

/** Where a tile's art sits on a page: translation, scale and rotation in degrees. */
public data class TilePlacement(
	val page: Int, val x: Float, val y: Float, val scaleX: Float, val scaleY: Float, val rotation: Float,
)

public data class TextureTile(
	val id: String, val name: String, val width: Int, val height: Int,
	val placement: TilePlacement? = null,
	val source: SourceRef? = null,
	val pinned: Boolean = false,
	val replaces: String? = null,
)

public data class SourceRef(val source: String, val layer: String, val stableKey: Boolean)

/** A rig of pendulums driven by parameters and writing parameters. [fps] null means the runtime default. */
public data class Physics(val groups: List<PhysicsGroup> = emptyList(), val fps: Float? = null)

/** X is sideways travel of the root (or of a vertex); ANGLE is tilt (or a segment's angle). */
public enum class PhysicsSource { X, ANGLE }

public data class PhysicsInput(val parameter: String, val weight: Float, val source: PhysicsSource, val reflect: Boolean)

/** [vertex] is 1-based along the chain. */
public data class PhysicsOutput(
	val parameter: String, val vertex: Int, val scale: Float, val weight: Float, val source: PhysicsSource, val reflect: Boolean,
)

public data class PhysicsSegment(val length: Float, val mobility: Float, val delay: Float, val acceleration: Float)

public data class PhysicsNormalization(
	val positionMin: Float, val positionDefault: Float, val positionMax: Float,
	val angleMin: Float, val angleDefault: Float, val angleMax: Float,
)

public data class PhysicsGroup(
	val id: String, val name: String,
	val inputs: List<PhysicsInput>, val outputs: List<PhysicsOutput>,
	val segments: List<PhysicsSegment>, val normalization: PhysicsNormalization,
)

/**
 * A motion: keyframed parameter curves. [group] is the runtime motion group (Idle plays in a loop).
 * Fades are in seconds, or null for the runtime default.
 */
public data class Clip(
	val id: String, val name: String, val group: String, val file: String,
	val duration: Float, val fps: Float, val loop: Boolean,
	val fadeIn: Float? = null, val fadeOut: Float? = null,
	val curves: List<Curve>,
)

/** A parameter curve: its first point, then segments each ending at a new point. */
public data class Curve(val parameter: String, val startTime: Float, val startValue: Float, val segments: List<CurveSegment>)

public sealed interface CurveSegment {
	public val time: Float
	public val value: Float

	public data class Linear(override val time: Float, override val value: Float) : CurveSegment
	/** Cubic Bezier with absolute (time, value) control points. */
	public data class Bezier(
		val c1Time: Float, val c1Value: Float, val c2Time: Float, val c2Value: Float,
		override val time: Float, override val value: Float,
	) : CurveSegment
	/** Holds the previous value until [time]. */
	public data class Stepped(override val time: Float, override val value: Float) : CurveSegment
	/** Jumps to [value] at the segment's start. */
	public data class InverseStepped(override val time: Float, override val value: Float) : CurveSegment
}

/**
 * Editor-side content that travels with the rig but that no runtime format plays: the artwork inventory,
 * deform paths, vertex weight groups and the runtime the document was authored for.
 */
public data class Authoring(
	val runtime: String = "Cubism50",
	val rendersFromSourceLayers: Boolean = false,
	val sources: List<SourceFile> = emptyList(),
	val paths: List<DeformPath> = emptyList(),
	val vertexGroups: List<VertexGroup> = emptyList(),
)

public data class SourceFile(
	val id: String, val name: String, val path: String?, val format: String,
	val layers: List<SourceLayer> = emptyList(),
	val hash: String? = null, val lastModified: Long? = null,
)

public data class SourceLayer(
	val key: String, val name: String, val groupPath: String,
	val left: Int, val top: Int, val width: Int, val height: Int, val visible: Boolean,
	val present: Boolean = true, val hash: String? = null,
	val empty: Boolean = false, val replaced: Boolean = false, val ignored: Boolean = false,
)

/** A point on a mesh by barycentric weights over triangle (a, b, c). */
public data class PathPoint(val a: Int, val b: Int, val c: Int, val wa: Float, val wb: Float, val wc: Float, val corner: Boolean = false)

public data class DeformPath(
	val id: String, val mesh: String, val points: List<PathPoint>,
	val width: Float, val hardness: Float, val closed: Boolean, val editLevel: Int,
)

/** Per-vertex weights of [kind] (pin, stiffness, mass, damping, wind, goal) over one mesh. */
public data class VertexGroup(val name: String, val mesh: String, val kind: String, val weights: Floats)
