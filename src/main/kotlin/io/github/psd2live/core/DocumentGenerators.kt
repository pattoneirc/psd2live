package io.github.psd2live.core

import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.format.compile.document.GeneratorGraph
import io.github.psd2live.format.compile.document.GeneratorNode
import org.umamo.runtime.model.PuppetModel

/**
 * The generators of a document as a dependency graph: what each reads and owns, by stable id.
 *
 * The rig builder makes the base rig from the source art and settings; the skeleton stage bakes the authored
 * skeleton into it; the journal replays the user's edits; each swing and each baked simulation then adds the
 * keyforms of its own parameters; generated overrides merge the user's edits of those keyforms; physics and
 * the generated motions read the finished rig. Replay runs the swings and simulations in the graph's order,
 * and an edit of a keyform is recorded as an override of the generator that owns it.
 *
 * Object ids: `document:<part>` for document state, `rig:<stage>` for whole-rig stages, `parameter:<id>` for
 * a generated parameter, `keyform:<kind>:<target>@<parameter>` for the keyforms a generator adds on
 * [kind] (`warp` or `mesh`) [target] along [parameter], `physics` and `motions` for the compiled groups and clips.
 */
internal object DocumentGenerators {
	const val RIG = "rig"
	const val SKELETON = "skeleton"
	const val JOURNAL = "journal"
	const val OVERRIDES = "overrides"
	const val PHYSICS = "physics"
	const val MOTIONS = "motions"
	private const val SWING = "swing:"
	private const val SIMULATION = "simulation:"

	fun keyform(kind: String, target: String, parameter: String) = "keyform:$kind:$target@$parameter"
	fun swingId(swing: RigSwingEdit) = SWING + swing.id
	fun simulationId(sim: io.github.psd2live.core.sim.RigSimEdit) = SIMULATION + sim.id

	/**
	 * The graph of [overlay]'s generators. A generated object only one generator may own; should a document
	 * give two the same parameter, the earlier keeps it and the later one's claim is dropped, so replay still
	 * runs (the generators report the clash themselves).
	 */
	fun graph(overlay: RigEditOverlay): GeneratorGraph {
		val owned = HashSet<String>()
		fun claim(ids: Collection<String>) = ids.filterTo(LinkedHashSet()) { owned.add(it) }
		val nodes = ArrayList<GeneratorNode>()
		nodes += GeneratorNode(RIG, setOf("document:source", "document:settings", "document:layers"), claim(listOf("rig:base")))
		nodes += GeneratorNode(SKELETON, setOf("rig:base", "document:skeleton"), claim(listOf("rig:skeleton")))
		nodes += GeneratorNode(JOURNAL, setOf("rig:skeleton", "document:journal"), claim(listOf("rig:authored")))
		val keyforms = LinkedHashSet<String>()
		val parameters = LinkedHashSet<String>()
		for (swing in overlay.swingEdits) {
			val writes = if (swing.baked) emptySet() else claim(swing.parameterIds.map { "parameter:$it" } +
				swing.targets.flatMap { target -> swing.parameterIds.map { keyform("warp", target, it) } })
			nodes += GeneratorNode(swingId(swing), setOf("rig:authored", "document:${swingId(swing)}"), writes)
			keyforms += writes.filter { it.startsWith("keyform:") }; parameters += writes.filter { it.startsWith("parameter:") }
		}
		for (sim in overlay.simEdits) {
			val bake = sim.bake?.takeIf { sim.enabled }
			val outputs = sim.outputParameters
			val writes = if (bake == null) emptySet() else claim(outputs.map { "parameter:$it" } +
				bake.vertexCounts.keys.flatMap { mesh -> outputs.map { keyform("mesh", mesh, it) } })
			nodes += GeneratorNode(simulationId(sim), setOf("rig:authored", "document:${simulationId(sim)}"), writes)
			keyforms += writes.filter { it.startsWith("keyform:") }; parameters += writes.filter { it.startsWith("parameter:") }
		}
		nodes += GeneratorNode(OVERRIDES, keyforms + "rig:authored" + "document:overrides" + "document:generators", claim(listOf("rig:final")))
		nodes += GeneratorNode(PHYSICS, parameters + "rig:final" + "document:physics" + "document:skeleton", claim(listOf("physics")))
		nodes += GeneratorNode(MOTIONS, setOf("rig:final", "physics", "document:motions", "document:skeleton"), claim(listOf("motions")))
		return GeneratorGraph(nodes)
	}

	/** The generator that owns [objectId] in [graph], if any. */
	fun owner(graph: GeneratorGraph, objectId: String): GeneratorNode? = graph.order.firstOrNull { objectId in it.writes }

	/**
	 * Runs the generators that write onto the replayed rig - swings, then simulations - in the graph's order.
	 * Each is a pure function of its settings and the rig before it.
	 */
	fun generate(model: PuppetModel, overlay: RigEditOverlay, graph: GeneratorGraph = graph(overlay)): PuppetModel {
		val swings = overlay.swingEdits.associateBy(::swingId)
		val sims = overlay.simEdits.associateBy(::simulationId)
		var current = model
		for (node in graph.order) {
			swings[node.id]?.let { current = SwingGenerator.apply(current, listOf(it)) }
			sims[node.id]?.let { current = SimGenerator.apply(current, listOf(it)) }
		}
		return current
	}

	/** The document parts that differ between [before] and [after], as the graph's `document:` ids. */
	fun changed(before: RigEditOverlay, after: RigEditOverlay): Set<String> = buildSet {
		if (before.skeleton != after.skeleton) add("document:skeleton")
		// Adding or removing a swing or simulation changes which keyforms are generated at all.
		if (before.swingEdits.map(::swingId) + before.simEdits.map(::simulationId) != after.swingEdits.map(::swingId) + after.simEdits.map(::simulationId))
			add("document:generators")
		val journal = { overlay: RigEditOverlay -> overlay.authoringJournal.filterNot { GeneratedOverrides.isOverride(it) } }
		if (journal(before) != journal(after) || before.parameterEdits != after.parameterEdits || before.structureEdits != after.structureEdits ||
			before.keyformSetEdits != after.keyformSetEdits || before.keyformCopyEdits != after.keyformCopyEdits ||
			before.keyformDeleteEdits != after.keyformDeleteEdits || before.warpEdits != after.warpEdits ||
			before.deletedParameterIds != after.deletedParameterIds) add("document:journal")
		if (before.authoringJournal.filter(GeneratedOverrides::isOverride) != after.authoringJournal.filter(GeneratedOverrides::isOverride))
			add("document:overrides")
		val swingsBefore = before.swingEdits.associateBy(::swingId); val swingsAfter = after.swingEdits.associateBy(::swingId)
		for (id in swingsBefore.keys + swingsAfter.keys) if (swingsBefore[id] != swingsAfter[id]) add("document:$id")
		val simsBefore = before.simEdits.associateBy(::simulationId); val simsAfter = after.simEdits.associateBy(::simulationId)
		for (id in simsBefore.keys + simsAfter.keys) if (simsBefore[id]?.toJson() != simsAfter[id]?.toJson()) add("document:$id")
		if (before.physicsEdits != after.physicsEdits || before.disabledPhysicsIds != after.disabledPhysicsIds ||
			before.physicsOrder != after.physicsOrder || before.physicsFps != after.physicsFps) add("document:physics")
		if (before.motionClips != after.motionClips || before.motionPresets != after.motionPresets) add("document:motions")
	}

	/** The generators [after] must run again over [before]: those reading a changed part, and everything downstream. */
	fun stale(before: RigEditOverlay, after: RigEditOverlay): List<String> {
		val changed = changed(before, after)
		// What a changed or removed generator wrote before changes too.
		val dropped = graph(before).order.filter { "document:${it.id}" in changed }.flatMap { it.writes }
		return graph(after).stale(changed + dropped).map { it.id }
	}
}
