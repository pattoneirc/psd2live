package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RectPackTest {
	@Test fun packsDisjointRectanglesInsideThePage() {
		val sizes = (0 until 40).map { intArrayOf(20 + (it * 37) % 90, 15 + (it * 53) % 70) }
		for (heuristic in RectPack.Heuristic.entries) {
			val packed = assertNotNull(RectPack.pack(sizes, 512, Int.MAX_VALUE, emptyMap(), heuristic))
			for (i in sizes.indices) {
				val a = packed.spots[i]
				assertTrue(a[1] >= 0 && a[2] >= 0 && a[1] + sizes[i][0] <= 512 && a[2] + sizes[i][1] <= 512)
				for (j in i + 1 until sizes.size) {
					val b = packed.spots[j]
					if (a[0] != b[0]) continue
					assertTrue(a[1] + sizes[i][0] <= b[1] || b[1] + sizes[j][0] <= a[1] || a[2] + sizes[i][1] <= b[2] || b[2] + sizes[j][1] <= a[2],
						"$heuristic: $i and $j overlap")
				}
			}
		}
	}
}
