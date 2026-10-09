package io.github.psd2live.ui

import io.github.psd2live.ui.views.HierarchySort
import io.github.psd2live.ui.views.HierarchySortMode
import io.github.psd2live.ui.views.MeshBounds
import io.github.psd2live.ui.views.NaturalNameOrder
import io.github.psd2live.ui.views.sortHierarchyChildren
import org.umamo.runtime.model.*
import kotlin.test.*

class HierarchySortTest {
	private fun drawable(id: String, parent: String?, order: Float = 500f) = Drawable(DrawableId(id), id, parent?.let(::DeformerId), BlendMode.Normal, emptyList(),
		DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), FloatArray(6), intArrayOf(0, 1, 2)), null, drawOrder = order)

	private fun warp(id: String, parent: String?) = Deformer.Warp(DeformerId(id), id, parent?.let(::DeformerId), null, 1, 1, false,
		KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(FloatArray(8))))))

	// Canvas y grows downward: the hair sits above the face, the shoes below everything.
	private val bounds = mapOf(
		"Shoes" to MeshBounds(0f, 900f, 100f, 1000f),
		"Hair" to MeshBounds(0f, 0f, 100f, 200f),
		"Face" to MeshBounds(200f, 150f, 300f, 300f),
		"Body" to MeshBounds(0f, 400f, 100f, 700f),
	)
	private val deformers = listOf(warp("Legs", null), warp("Head", null), warp("Empty", null))
	private val drawables = listOf(
		drawable("Shoes", "Legs", 400f), drawable("Body", null, 450f), drawable("Face", "Head", 600f), drawable("Hair", "Head", 700f),
	)

	private fun sorted(sort: HierarchySort) =
		sortHierarchyChildren(deformers.groupBy { it.parent?.raw }, drawables.groupBy { it.parentDeformerId?.raw }, sort, bounds) { it.drawOrder }

	private fun Map<String?, List<Deformer>>.ids(parent: String?) = get(parent).orEmpty().map { it.id.raw }
	private fun Map<String?, List<Drawable>>.names(parent: String?) = get(parent).orEmpty().map { it.name }

	@Test fun heightPutsWhatIsHigherOnTheCanvasFirst() {
		val (deformerChildren, drawableChildren) = sorted(HierarchySort(HierarchySortMode.HEIGHT))
		// A deformer stands where the box of its meshes does; one with no mesh keeps its place after the rest.
		assertEquals(listOf("Head", "Legs", "Empty"), deformerChildren.ids(null))
		assertEquals(listOf("Hair", "Face"), drawableChildren.names("Head"))
		val (reversed, _) = sorted(HierarchySort(HierarchySortMode.HEIGHT, reversed = true))
		assertEquals(listOf("Legs", "Head", "Empty"), reversed.ids(null))
	}

	@Test fun drawOrderAndNameAndModelOrders() {
		val (byOrder, drawablesByOrder) = sorted(HierarchySort(HierarchySortMode.DRAW_ORDER))
		assertEquals(listOf("Head", "Legs"), byOrder.ids(null).take(2))
		assertEquals(listOf("Hair", "Face"), drawablesByOrder.names("Head"))
		assertEquals(listOf("Empty", "Head", "Legs"), sorted(HierarchySort(HierarchySortMode.NAME)).first.ids(null))
		assertEquals(listOf("Empty", "Head", "Legs"), sorted(HierarchySort(reversed = true)).first.ids(null))
		val (model, _) = sorted(HierarchySort())
		assertEquals(listOf("Legs", "Head", "Empty"), model.ids(null))
	}

	@Test fun horizontalPutsTheLeftmostFirst() {
		val (_, drawableChildren) = sorted(HierarchySort(HierarchySortMode.HORIZONTAL))
		assertEquals(listOf("Hair", "Face"), drawableChildren.names("Head"))
	}

	@Test fun boundsAreReadInCanvasSpace() {
		// World y grows upward; the box flips it so that top is the smaller canvas y.
		assertEquals(MeshBounds(-1f, -5f, 3f, 2f), MeshBounds.ofWorld(floatArrayOf(-1f, -2f, 3f, 5f)))
		assertNull(MeshBounds.ofWorld(FloatArray(0)))
	}

	@Test fun namesCompareTheirNumbersAsNumbers() {
		assertEquals(listOf("hair1", "Hair2", "hair10", "hair10b"), listOf("hair10", "hair10b", "Hair2", "hair1").sortedWith(NaturalNameOrder))
	}

	@Test fun aSortSurvivesItsEncoding() {
		val sort = HierarchySort(HierarchySortMode.HEIGHT, reversed = true)
		assertEquals(sort, HierarchySort.decode(sort.encode()))
		assertEquals(HierarchySort(HierarchySortMode.NAME), HierarchySort.decode("NAME"))
		assertEquals(HierarchySort(), HierarchySort.decode("BOGUS:reversed"))
		assertEquals(HierarchySort(), HierarchySort.decode(null))
	}
}
