package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletonArapTest {
	private val points = floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f, 10f, 10f)
	private val triangles = intArrayOf(0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4)

	@Test fun rigidTransformIsRecoveredWithExactHandles() {
		val solver = SkeletonArap(points, triangles, booleanArrayOf(false, false, false, false, true))
		for (angle in listOf(-150.0, -90.0, 0.0, 120.0, 150.0)) {
			val target = FloatArray(points.size)
			for (i in points.indices step 2) {
				val p = SkeletonIk.rotate(points[i].toDouble(), points[i + 1].toDouble(), 0.0, 0.0, angle)
				target[i] = p[0].toFloat() + 30f; target[i + 1] = p[1].toFloat() - 40f
			}
			val seed = target.copyOf().also { it[8] += 3f; it[9] -= 2f }
			val actual = solver.solve(target, seed)
			for (i in target.indices) assertEquals(target[i], actual[i], 0.01f, "coordinate $i at $angle")
		}
	}

	@Test fun disconnectedUnconstrainedIslandRetainsItsSkinningTarget() {
		val solver = SkeletonArap(points, triangles, BooleanArray(5) { true })
		val target = points.map { it * 0.8f + 10f }.toFloatArray()
		val actual = solver.solve(target)
		for (i in target.indices) assertEquals(target[i], actual[i])
		assertTrue(actual.all(Float::isFinite))
	}

	@Test fun degenerateMeshDoesNotProduceNonFiniteCoordinates() {
		val solver = SkeletonArap(floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f), intArrayOf(0, 1, 2), booleanArrayOf(false, true, false))
		assertTrue(solver.solve(floatArrayOf(0f, 0f, 10f, 2f, 20f, 0f)).all(Float::isFinite))
	}

	@Test fun poseGuideMovesFreeRegionAndKeepsRigidHandles() {
		val solver = SkeletonArap(points, triangles, booleanArrayOf(false, false, false, false, true))
		val guide = points.copyOf().also { it[8] = 13f; it[9] = 12f }
		val actual = solver.solve(points, points, guide, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 100.0))
		for (i in 0..7) assertEquals(points[i], actual[i])
		assertEquals(13f, actual[8], .05f); assertEquals(12f, actual[9], .05f)
		val impossible = guide.copyOf().also { it[8] = 1000f }
		val safe = solver.solve(points, points, impossible, doubleArrayOf(0.0, 0.0, 0.0, 0.0, 100.0))
		assertTrue(safe[8] < 20f, "guide cannot pull the centre across the rigid boundary")
	}

	@Test fun contactVertexMayCrossItsAdjacentFacesWithoutMovingRigidHandles() {
		val solver = SkeletonArap(points, triangles, booleanArrayOf(false, false, false, false, true))
		val guide = points.copyOf().also { it[8] = 25f; it[9] = 10f }
		val penalty = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 100.0)
		val protected = solver.solve(points, points, guide, penalty)
		val overlap = solver.solve(points, points, guide, penalty, booleanArrayOf(false, false, false, false, true))
		assertTrue(protected[8] < 20f)
		assertTrue(overlap[8] > 20f, "one fold vertex must be able to cross the adjacent triangle")
		for (i in 0..7) assertEquals(points[i], overlap[i], "rigid handle $i")
		assertTrue(overlap.all(Float::isFinite))
	}

	@Test fun reflectedFoldRetainsItsMaterialCoordinatesWithoutRotationWrinkles() {
		val rest = floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f, 5f, 10f, 15f, 10f)
		val faces = intArrayOf(0, 1, 4, 1, 5, 4, 1, 2, 5, 2, 3, 5, 3, 4, 5, 3, 0, 4)
		val solver = SkeletonArap(rest, faces, booleanArrayOf(false, false, false, false, true, true))
		val mirrored = FloatArray(rest.size) { if (it % 2 == 0) -rest[it] else rest[it] }
		val result = solver.solve(mirrored, mirrored, folding = BooleanArray(6) { true })
		for (i in mirrored.indices) assertEquals(mirrored[i], result[i], .001f, "reflected material coordinate $i")
	}

	/** The factored global solve gives what the conjugate gradient solve gives, to well under a pixel, on a bending strip. */
	@Test fun factoredSolveMatchesConjugateGradients() {
		val columns = 7; val rows = 41
		val rest = FloatArray(columns * rows * 2) { i -> val v = i / 2; if (i % 2 == 0) (v % columns) * 6f else (v / columns) * 5f }
		val faces = ArrayList<Int>()
		for (r in 0 until rows - 1) for (c in 0 until columns - 1) {
			val a = r * columns + c; val b = a + 1; val d = a + columns; val e = d + 1
			faces += listOf(a, b, d, b, e, d)
		}
		val triangles = faces.toIntArray()
		val weight = FloatArray(columns * rows) { v -> (((v / columns) * 5f - 70f) / 60f).coerceIn(0f, 1f) }
		val movable = BooleanArray(weight.size) { weight[it] > 0f && weight[it] < 1f }
		val factored = SkeletonArap(rest, triangles, movable)
		val reference = SkeletonArap(rest, triangles, movable, factored = false)
		for (angle in listOf(-130.0, -45.0, 20.0, 90.0, 140.0)) {
			val target = FloatArray(rest.size); val seed = FloatArray(rest.size)
			for (v in weight.indices) {
				val full = SkeletonIk.rotate(rest[v * 2].toDouble(), rest[v * 2 + 1].toDouble(), 18.0, 100.0, angle)
				val partial = SkeletonIk.rotate(rest[v * 2].toDouble(), rest[v * 2 + 1].toDouble(), 18.0, 100.0, angle * weight[v])
				for (axis in 0..1) {
					target[v * 2 + axis] = (rest[v * 2 + axis] + (full[axis] - rest[v * 2 + axis]) * weight[v]).toFloat()
					seed[v * 2 + axis] = partial[axis].toFloat()
				}
			}
			val penalty = DoubleArray(weight.size) { v -> 4.0 * weight[v] * (1 - weight[v]) * (1 + v % 3) }
			val folding = BooleanArray(weight.size) { v -> movable[v] && v % columns < 2 }
			val expected = reference.solve(target, seed, target, penalty, folding)
			val actual = factored.solve(target, seed, target, penalty, folding)
			for (i in expected.indices) assertEquals(expected[i], actual[i], 1e-3f, "coordinate $i at $angle")
		}
	}
}
