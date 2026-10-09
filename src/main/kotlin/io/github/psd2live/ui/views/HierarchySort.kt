package io.github.psd2live.ui.views

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable

/** What the hierarchy tree orders siblings by; deformers stay above meshes under each parent. */
internal enum class HierarchySortMode(val labelKey: String) {
	/** Highest on the canvas first: hair before feet. The tree's default. */
	HEIGHT("canvas.hierarchy.sort.height"),
	/** The order the model lists them in. */
	MODEL("canvas.hierarchy.sort.model"),
	/** Leftmost on the canvas first. */
	HORIZONTAL("canvas.hierarchy.sort.horizontal"),
	/** Frontmost first; a deformer stands where its frontmost mesh does. */
	DRAW_ORDER("canvas.hierarchy.sort.drawOrder"),
	/** Natural name order: "Hair2" before "Hair10". */
	NAME("canvas.hierarchy.sort.name"),
	;

	/** Whether the order reads where meshes lie on the canvas. */
	val needsBounds: Boolean get() = this == HEIGHT || this == HORIZONTAL
}

internal data class HierarchySort(val mode: HierarchySortMode = HierarchySortMode.HEIGHT, val reversed: Boolean = false) {
	val isDefault: Boolean get() = this == HierarchySort()

	fun encode(): String = if (reversed) "${mode.name}:reversed" else mode.name

	companion object {
		fun decode(text: String?): HierarchySort {
			if (text.isNullOrBlank()) return HierarchySort()
			val mode = HierarchySortMode.entries.firstOrNull { it.name == text.substringBefore(':') } ?: return HierarchySort()
			return HierarchySort(mode, text.substringAfter(':', "") == "reversed")
		}
	}
}

/** A mesh's rest-pose box in canvas units, y growing downward. */
internal data class MeshBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
	val centerX: Float get() = (left + right) / 2f
	val centerY: Float get() = (top + bottom) / 2f

	fun union(other: MeshBounds) =
		MeshBounds(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

	companion object {
		/** The box of world positions (x, y pairs, y up), or null for an empty mesh. */
		fun ofWorld(positions: FloatArray): MeshBounds? {
			if (positions.size < 2) return null
			var left = Float.POSITIVE_INFINITY; var right = Float.NEGATIVE_INFINITY
			var top = Float.POSITIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
			for (i in 0 until positions.size - 1 step 2) {
				val x = positions[i]; val y = -positions[i + 1]
				if (!x.isFinite() || !y.isFinite()) continue
				left = minOf(left, x); right = maxOf(right, x)
				top = minOf(top, y); bottom = maxOf(bottom, y)
			}
			return if (left <= right && top <= bottom) MeshBounds(left, top, right, bottom) else null
		}
	}
}

/**
 * The children maps with every sibling list in [sort]'s order. A deformer is placed by the meshes under it: their
 * joined box for a position, the frontmost for draw order. Siblings the order cannot place (a deformer with no mesh,
 * a mesh with no box) keep their model order after the rest, whichever way the order runs.
 */
internal fun sortHierarchyChildren(
	deformerChildren: Map<String?, List<Deformer>>,
	drawableChildren: Map<String?, List<Drawable>>,
	sort: HierarchySort,
	bounds: Map<String, MeshBounds>,
	drawOrder: (Drawable) -> Float,
): Pair<Map<String?, List<Deformer>>, Map<String?, List<Drawable>>> {
	if (sort == HierarchySort(HierarchySortMode.MODEL)) return deformerChildren to drawableChildren

	val subtreeBounds = HashMap<String, MeshBounds?>()
	val subtreeOrder = HashMap<String, Float?>()
	val visiting = HashSet<String>()

	fun boundsUnder(id: String): MeshBounds? = subtreeBounds.getOrPut(id) {
		if (!visiting.add(id)) return null
		val boxes = drawableChildren[id].orEmpty().mapNotNull { bounds[it.id.raw] } +
			deformerChildren[id].orEmpty().mapNotNull { boundsUnder(it.id.raw) }
		visiting.remove(id)
		boxes.reduceOrNull(MeshBounds::union)
	}

	fun frontmostUnder(id: String): Float? = subtreeOrder.getOrPut(id) {
		if (!visiting.add(id)) return null
		val orders = drawableChildren[id].orEmpty().map(drawOrder) +
			deformerChildren[id].orEmpty().mapNotNull { frontmostUnder(it.id.raw) }
		visiting.remove(id)
		orders.maxOrNull()
	}

	fun deformerKey(deformer: Deformer): Float? = when (sort.mode) {
		HierarchySortMode.HEIGHT -> boundsUnder(deformer.id.raw)?.centerY
		HierarchySortMode.HORIZONTAL -> boundsUnder(deformer.id.raw)?.centerX
		HierarchySortMode.DRAW_ORDER -> frontmostUnder(deformer.id.raw)?.let { -it }
		HierarchySortMode.MODEL, HierarchySortMode.NAME -> 0f
	}

	fun drawableKey(drawable: Drawable): Float? = when (sort.mode) {
		HierarchySortMode.HEIGHT -> bounds[drawable.id.raw]?.centerY
		HierarchySortMode.HORIZONTAL -> bounds[drawable.id.raw]?.centerX
		HierarchySortMode.DRAW_ORDER -> -drawOrder(drawable)
		HierarchySortMode.MODEL, HierarchySortMode.NAME -> 0f
	}

	return deformerChildren.mapValues { (_, list) -> list.sortedFor(sort, ::deformerKey) { it.name } } to
		drawableChildren.mapValues { (_, list) -> list.sortedFor(sort, ::drawableKey) { it.name } }
}

private fun <T> List<T>.sortedFor(sort: HierarchySort, key: (T) -> Float?, name: (T) -> String): List<T> {
	val (placed, unplaced) = partition { key(it) != null }
	val ordered = when (sort.mode) {
		HierarchySortMode.MODEL -> placed
		HierarchySortMode.NAME -> placed.sortedWith(compareBy(NaturalNameOrder, name))
		else -> placed.sortedBy { key(it)!! }
	}
	return (if (sort.reversed) ordered.asReversed() else ordered) + unplaced
}

/** Case-insensitive, with runs of digits compared as numbers. */
internal object NaturalNameOrder : Comparator<String> {
	private val chunk = Regex("\\d+|\\D+")

	override fun compare(a: String, b: String): Int {
		val left = chunk.findAll(a).map { it.value }.iterator()
		val right = chunk.findAll(b).map { it.value }.iterator()
		while (left.hasNext() && right.hasNext()) {
			val x = left.next(); val y = right.next()
			val byChunk = if (x[0].isDigit() && y[0].isDigit()) {
				val xs = x.trimStart('0'); val ys = y.trimStart('0')
				if (xs.length != ys.length) xs.length - ys.length else xs.compareTo(ys)
			} else x.compareTo(y, ignoreCase = true)
			if (byChunk != 0) return byChunk
		}
		return when {
			left.hasNext() -> 1
			right.hasNext() -> -1
			else -> a.compareTo(b)
		}
	}
}
