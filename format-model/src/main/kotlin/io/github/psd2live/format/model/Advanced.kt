package io.github.psd2live.format.model

/**
 * What the PSD2Live runtime's advanced mode adds on top of the rig every target exports: data the Cubism
 * rig has already baked into keyforms and pendulums, kept so the runtime can play it live. Other targets
 * ignore it.
 */
public data class AdvancedIR(
	/**
	 * Skeleton bones the rig folded into its meshes' keyforms (no mesh hangs from them), as rotations the runtime
	 * evaluates only to skin those meshes. A parent is a deformer of the rig or an earlier virtual bone.
	 */
	val virtualBones: List<Deformer.Rotation> = emptyList(),
	val simulations: List<SimulationIR> = emptyList(),
	val colliders: List<ColliderIR> = emptyList(),
) {
	val isEmpty: Boolean get() = virtualBones.isEmpty() && simulations.isEmpty() && colliders.isEmpty()
}

/**
 * A cloth or hair simulation as the editor's XPBD solver runs it, particle for particle: one particle per
 * vertex of each target mesh in [targets] order. Positions are world space (canvas x, negated canvas y).
 * Compliances may be [Float.POSITIVE_INFINITY] (no constraint).
 */
public data class SimulationIR(
	val id: String,
	/** Solver steps per second; each step runs [substeps] substeps. */
	val fps: Float,
	val substeps: Int,
	val gravityX: Float, val gravityY: Float,
	val windX: Float, val windY: Float,
	val pinCompliance: Float,
	val targets: List<SimTarget>,
	val particles: SimParticles,
	val stretch: SimStretch,
	val triangles: SimTriangles,
	val bends: SimBends,
	val welds: SimWelds,
	val longRange: SimLongRange,
	/** The baked mode parameters the simulation replaces: held at their defaults while it runs. */
	val parameters: List<String>,
	/** The baked pendulums driving them: skipped while it runs. */
	val physicsGroups: List<String>,
	/** Corrections the bake added to the targets' keyforms on other parameters, taken back out while it runs. */
	val statics: List<SimStatic>,
	/** Colliders (by id) the particles keep out of. */
	val colliders: List<String> = emptyList(),
)

public data class SimTarget(val mesh: String, val vertexCount: Int)

public data class SimParticles(
	val invMass: Floats, val damping: Floats, val windFactor: Floats, val pinWeight: Floats, val goalCompliance: Floats,
	val goalOffsetX: Floats, val goalOffsetY: Floats,
	/** Per particle the mesh its pin follows ("" for its own position) and that mesh's vertex. */
	val anchorMesh: List<String>, val anchorVertex: Ints,
)

public data class SimStretch(val a: Ints, val b: Ints, val rest: Floats, val compliance: Floats, val compressionCompliance: Floats)
public data class SimTriangles(val a: Ints, val b: Ints, val c: Ints, val areaCompliance: Floats)
public data class SimBends(val t1: Ints, val t2: Ints, val compliance: Floats)
public data class SimWelds(val a: Ints, val b: Ints, val weightA: Floats, val weightB: Floats, val compliance: Floats)
public data class SimLongRange(val particle: Ints, val root: Ints, val maxDistance: Floats)

/** A static correction: at each of [keys] of [parameter], what each target mesh (by id) adds, in its parent's space. */
public data class SimStatic(val parameter: String, val keys: Floats, val offsets: Map<String, List<Floats>>)

/**
 * A circle ([ax], [ay] with [radiusA]) or a capsule from it to ([bx], [by]) with [radiusB], that simulated
 * particles stay out of. It rides [deformer] (a rig deformer or virtual bone; its local space), or with
 * [mesh] set runs between that mesh's vertices [vertexA] and [vertexB] as they are evaluated; with neither,
 * it stays put in canvas space. [friction] 0..1 slows particles sliding along it.
 */
public data class ColliderIR(
	val id: String,
	val capsule: Boolean,
	val deformer: String? = null,
	val mesh: String? = null,
	val vertexA: Int = 0, val vertexB: Int = 0,
	val ax: Float = 0f, val ay: Float = 0f, val bx: Float = 0f, val by: Float = 0f,
	val radiusA: Float, val radiusB: Float = radiusA,
	val friction: Float = 0f,
)
