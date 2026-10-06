package io.github.psd2live.format.compile.document

import kotlin.test.*

class GeneratorsTest {
	@Test fun hashesSeparatePartBoundariesAndFollowContent() {
		assertEquals(ContentHash.of("ab", "c"), ContentHash.of("ab", "c"))
		assertNotEquals(ContentHash.of("ab", "c"), ContentHash.of("a", "bc"))
		assertNotEquals(ContentHash.of(listOf(1f, 2f)), ContentHash.of(listOf(1f, 2.0001f)))
	}

	private val face = GeneratorNode("face", reads = setOf("head"), writes = setOf("faceWarp"))
	private val swing = GeneratorNode("swing", reads = setOf("hairWarp", "faceWarp"), writes = setOf("ParamSwing"))
	private val physics = GeneratorNode("physics", reads = setOf("ParamSwing"), writes = setOf("physics:hair"))
	private val unrelated = GeneratorNode("breath", reads = setOf("body"), writes = setOf("ParamBreath"))

	@Test fun generatorsRunAfterWhatTheyRead() {
		val graph = GeneratorGraph(listOf(physics, unrelated, swing, face))
		assertEquals(listOf("breath", "face", "swing", "physics"), graph.order.map { it.id })
	}

	@Test fun aChangeMakesItsReadersAndTheirDownstreamStale() {
		val graph = GeneratorGraph(listOf(face, swing, physics, unrelated))
		assertEquals(listOf("face", "swing", "physics"), graph.stale(setOf("head")).map { it.id })
		assertEquals(listOf("swing", "physics"), graph.stale(setOf("hairWarp")).map { it.id })
		assertEquals(emptyList(), graph.stale(setOf("legs")))
	}

	@Test fun cyclesAndSharedOwnershipAreRejected() {
		assertFailsWith<IllegalArgumentException> {
			GeneratorGraph(listOf(GeneratorNode("a", setOf("y"), setOf("x")), GeneratorNode("b", setOf("x"), setOf("y"))))
		}
		assertFailsWith<IllegalArgumentException> {
			GeneratorGraph(listOf(GeneratorNode("a", emptySet(), setOf("x")), GeneratorNode("b", emptySet(), setOf("x"))))
		}
	}

	@Test fun theCacheComputesOncePerContent() {
		val cache = GenerationCache<Int>(capacity = 2)
		var runs = 0
		repeat(3) { cache.getOrPut("a") { ++runs } }
		assertEquals(1, runs); assertEquals(2, cache.hits)
		cache.getOrPut("b") { ++runs }; cache.getOrPut("c") { ++runs }
		cache.getOrPut("a") { ++runs }
		assertEquals(4, runs, "the least recently used entry was evicted")
	}

	@Test fun mergesKeepUntouchedSidesAndReportRealConflicts() {
		val base = mapOf("a" to 1, "b" to 1, "c" to 1, "d" to 1, "gone" to 1)
		val generated = mapOf("a" to 2, "b" to 1, "c" to 3, "d" to 5, "new" to 7)
		val edited = mapOf("a" to 1, "b" to 9, "c" to 4, "d" to 5, "gone" to 1)
		val result = ThreeWayMerge.merge(base, generated, edited)
		assertEquals(mapOf("a" to 2, "b" to 9, "c" to 4, "d" to 5, "new" to 7), result.merged)
		assertEquals(setOf("c"), result.conflicts)
	}
}
