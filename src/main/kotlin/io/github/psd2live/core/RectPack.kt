package io.github.psd2live.core

/**
 * MaxRects packing of rectangles onto square pages: each page keeps its maximal free rectangles, a rectangle
 * goes into the free one a [Heuristic] scores best, on the first page with room. Everything is integer and in
 * a fixed order, so the same inputs always give the same spots.
 */
internal object RectPack {
	enum class Heuristic {
		/** The free rectangle the item leaves the least short-side room in: dense packing. */
		SHORT_SIDE,
		/** The lowest bottom edge, then the leftmost: fills a page from the top. */
		BOTTOM_LEFT,
	}

	/** Free space of one page, as maximal free rectangles `x, y, width, height`. */
	class Page(private val side: Int) {
		private var free = arrayListOf(intArrayOf(0, 0, side, side))

		/** The lowest bottom edge of anything placed; the page's used height. */
		var bottom = 0
			private set

		/** The best top-left corner for a [width] x [height] rectangle, or null when none is free. */
		fun find(width: Int, height: Int, heuristic: Heuristic): IntArray? {
			var best: IntArray? = null
			var first = Long.MAX_VALUE; var second = Long.MAX_VALUE
			for (f in free) {
				if (f[2] < width || f[3] < height) continue
				val a: Long; val b: Long
				when (heuristic) {
					Heuristic.SHORT_SIDE -> {
						val dx = f[2] - width; val dy = f[3] - height
						a = minOf(dx, dy).toLong(); b = maxOf(dx, dy).toLong()
					}
					Heuristic.BOTTOM_LEFT -> { a = (f[1] + height).toLong(); b = f[0].toLong() }
				}
				// Ties go to the upper, then the left spot, so the order of the free list does not matter.
				if (a < first || a == first && (b < second || b == second && best != null && (f[1] < best[1] || f[1] == best[1] && f[0] < best[0]))) {
					best = intArrayOf(f[0], f[1]); first = a; second = b
				}
			}
			return best
		}

		/** Marks the rectangle at ([x], [y]) used; the parts of it off the page are ignored. */
		fun occupy(x: Int, y: Int, width: Int, height: Int) {
			val left = maxOf(x, 0); val top = maxOf(y, 0)
			val right = minOf(x + width, side); val down = minOf(y + height, side)
			if (right <= left || down <= top) return
			bottom = maxOf(bottom, down)
			val next = ArrayList<IntArray>(free.size + 8)
			val added = ArrayList<IntArray>()
			for (f in free) {
				if (left >= f[0] + f[2] || right <= f[0] || top >= f[1] + f[3] || down <= f[1]) { next += f; continue }
				if (left > f[0]) added += intArrayOf(f[0], f[1], left - f[0], f[3])
				if (right < f[0] + f[2]) added += intArrayOf(right, f[1], f[0] + f[2] - right, f[3])
				if (top > f[1]) added += intArrayOf(f[0], f[1], f[2], top - f[1])
				if (down < f[1] + f[3]) added += intArrayOf(f[0], down, f[2], f[1] + f[3] - down)
			}
			// Only the new pieces can be inside another rectangle, or hold an old one; old ones never held each other.
			val kept = BooleanArray(added.size) { true }
			for (i in added.indices) {
				val a = added[i]
				if (next.any { contains(it, a) }) { kept[i] = false; continue }
				for (j in added.indices) if (j != i && kept[j] && contains(added[j], a) && (!contains(a, added[j]) || j < i)) { kept[i] = false; break }
			}
			val survivors = added.filterIndexed { i, _ -> kept[i] }
			if (survivors.isNotEmpty()) next.removeAll { old -> survivors.any { contains(it, old) } }
			next += survivors
			free = next
		}

		private fun contains(outer: IntArray, inner: IntArray) = inner[0] >= outer[0] && inner[1] >= outer[1] &&
			inner[0] + inner[2] <= outer[0] + outer[2] && inner[1] + inner[3] <= outer[1] + outer[3]
	}

	/** Where [pack] put each rectangle (`page, x, y`, in input order), and the pages it used. */
	class Packed(val spots: Array<IntArray>, val pageCount: Int, val lastBottom: Int)

	/**
	 * Packs [sizes] (`width, height`) in their order onto at most [maxPages] pages of [pageSize], around
	 * [obstacles] (`x, y, width, height` per page). Each goes onto the first page with room. Null when one does not fit.
	 */
	fun pack(sizes: List<IntArray>, pageSize: Int, maxPages: Int, obstacles: Map<Int, List<IntArray>>, heuristic: Heuristic): Packed? {
		val pages = ArrayList<Page>()
		fun page(index: Int) = pages.getOrNull(index) ?: Page(pageSize).also { page ->
			obstacles[index].orEmpty().forEach { page.occupy(it[0], it[1], it[2], it[3]) }
			pages += page
		}
		val fixedPages = (obstacles.keys.maxOrNull() ?: -1) + 1
		val spots = Array(sizes.size) { IntArray(3) }
		for ((i, size) in sizes.withIndex()) {
			if (size[0] > pageSize || size[1] > pageSize) return null
			var index = 0
			while (true) {
				if (index >= maxPages) return null
				val fresh = index >= pages.size && index >= fixedPages
				val at = page(index).find(size[0], size[1], heuristic)
				if (at != null) {
					pages[index].occupy(at[0], at[1], size[0], size[1])
					spots[i][0] = index; spots[i][1] = at[0]; spots[i][2] = at[1]
					break
				}
				if (fresh) return null
				index++
			}
		}
		val used = maxOf(spots.maxOfOrNull { it[0] + 1 } ?: 0, fixedPages, 1)
		return Packed(spots, used, pages.getOrNull(used - 1)?.bottom ?: 0)
	}
}
