package io.github.psd2live.format.compile.document

import java.security.MessageDigest

/**
 * A content hash: SHA-256 over the canonical text of each part, length-prefixed so that part boundaries
 * cannot collide. The IR's values print canonically (data classes, content-printed arrays), so a hash of an
 * IR value identifies its content.
 */
public object ContentHash {
	public fun of(vararg parts: Any?): String {
		val digest = MessageDigest.getInstance("SHA-256")
		for (part in parts) {
			val text = part.toString().encodeToByteArray()
			digest.update(text.size.toString().encodeToByteArray()); digest.update(':'.code.toByte()); digest.update(text)
		}
		return java.util.HexFormat.of().formatHex(digest.digest())
	}
}

/**
 * A generator in the document's dependency graph: what it reads and what it owns, by stable id. A generator
 * is a pure function of its parameters and those inputs; it owns its outputs exclusively, and only one
 * generator may own a given object (others may read it downstream).
 */
public data class GeneratorNode(val id: String, val reads: Set<String>, val writes: Set<String>)

/** The evaluation order of a set of generators, and which of them a change makes stale. */
public class GeneratorGraph(nodes: List<GeneratorNode>) {
	/** Topological order: a node comes after every node that writes something it reads. Ties keep declaration order. */
	public val order: List<GeneratorNode>
	private val downstream: Map<String, Set<String>>

	init {
		require(nodes.map { it.id }.distinct().size == nodes.size) { "Duplicate generator ids" }
		val owner = HashMap<String, String>()
		for (node in nodes) for (output in node.writes) {
			val previous = owner.put(output, node.id)
			require(previous == null) { "$output is owned by both $previous and ${node.id}" }
		}
		val dependents = nodes.associate { node -> node.id to nodes.filter { other -> other.id != node.id && other.reads.any { it in node.writes } }.map { it.id } }
		val remaining = nodes.associate { node -> node.id to nodes.count { other -> other.id != node.id && node.reads.any { it in other.writes } } }.toMutableMap()
		val ordered = ArrayList<GeneratorNode>()
		val ready = nodes.filter { remaining.getValue(it.id) == 0 }.toMutableList()
		while (ready.isNotEmpty()) {
			val next = ready.removeAt(0)
			ordered += next
			for (dependent in dependents.getValue(next.id)) {
				val left = remaining.getValue(dependent) - 1
				remaining[dependent] = left
				if (left == 0) ready += nodes.single { it.id == dependent }
			}
			ready.sortBy { node -> nodes.indexOf(node) }
		}
		require(ordered.size == nodes.size) {
			"Generators depend on each other in a cycle: ${nodes.filter { it !in ordered }.joinToString { it.id }}"
		}
		order = ordered
		downstream = nodes.associate { node ->
			val seen = LinkedHashSet<String>()
			val queue = ArrayDeque(dependents.getValue(node.id))
			while (queue.isNotEmpty()) { val id = queue.removeFirst(); if (seen.add(id)) queue.addAll(dependents.getValue(id)) }
			node.id to seen
		}
	}

	/** The generators that must run again when [changed] objects change: their readers and everything after them. */
	public fun stale(changed: Set<String>): List<GeneratorNode> {
		val direct = order.filter { node -> node.reads.any { it in changed } }.map { it.id }
		val all = direct.toSet() + direct.flatMap { downstream.getValue(it) }
		return order.filter { it.id in all }
	}
}

/** Generator outputs by the content hash of their inputs; a bounded, least-recently-used store. */
public class GenerationCache<V>(private val capacity: Int = 64) {
	private val entries = object : LinkedHashMap<String, V>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > capacity
	}
	public var hits: Int = 0; private set
	public var misses: Int = 0; private set

	@Synchronized
	public fun getOrPut(key: String, compute: () -> V): V {
		entries[key]?.let { hits++; return it }
		misses++
		return compute().also { entries[key] = it }
	}
}

/**
 * The result of merging a regenerated value with a user's edit of the previous one. [conflicts] holds the
 * keys both sides changed differently; the user's value wins there and the conflict is reported.
 */
public data class MergeResult<K, V>(val merged: Map<K, V>, val conflicts: Set<K>)

/**
 * Three-way merge of generator output with user overrides, key by key. [base] is what the generator produced
 * when the user edited, [generated] what it produces now, [edited] the user's values. A key the user left at
 * base follows the generator; a key the generator left unchanged keeps the user's value; where both changed
 * to different values the user's value is kept and the key reported as a conflict. A missing value means the
 * object does not exist on that side.
 */
public object ThreeWayMerge {
	public fun <K, V> merge(base: Map<K, V>, generated: Map<K, V>, edited: Map<K, V>, same: (V?, V?) -> Boolean = { a, b -> a == b }): MergeResult<K, V> {
		val merged = LinkedHashMap<K, V>()
		val conflicts = LinkedHashSet<K>()
		for (key in (generated.keys + edited.keys + base.keys)) {
			val b = base[key]; val g = generated[key]; val e = edited[key]
			val value = when {
				same(e, b) -> g
				same(g, b) -> e
				same(g, e) -> g
				else -> { conflicts += key; e }
			}
			if (value != null) merged[key] = value
		}
		return MergeResult(merged, conflicts)
	}
}
