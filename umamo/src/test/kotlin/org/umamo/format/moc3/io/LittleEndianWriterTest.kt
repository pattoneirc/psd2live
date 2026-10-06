package org.umamo.format.moc3.io

import kotlin.test.Test
import kotlin.test.assertContentEquals

class LittleEndianWriterTest {
	@Test fun bulkWritesMatchElementWrites() {
		val floats = floatArrayOf(1.5f, -0f, Float.NaN, Float.fromBits(0x7fc00001), -3.25e-7f)
		val ints = intArrayOf(0, -1, 0x12345678, Int.MIN_VALUE)
		val single = LittleEndianWriter(1).apply { floats.forEach(::writeFloat32); ints.forEach(::writeInt32) }
		val bulk = LittleEndianWriter(1).apply { writeFloats(floats); writeInts(ints) }
		assertContentEquals(single.toByteArray(), bulk.toByteArray())
	}

	@Test fun anExactlySizedBufferHandedOutIsNeverChangedByLaterWrites() {
		val writer = LittleEndianWriter(8)
		writer.writeInt32(1); writer.writeInt32(2)
		val first = writer.toByteArray()
		val copy = first.copyOf()
		writer.patchInt32(0, 7)
		writer.writeInt32(3)
		writer.zeroPad(4)
		assertContentEquals(copy, first)
		assertContentEquals(byteArrayOf(7, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0), writer.toByteArray())

		val full = LittleEndianWriter(4).apply { writeInt32(5) }
		val handed = full.toByteArray()
		full.patchInt32(0, 9)
		assertContentEquals(byteArrayOf(5, 0, 0, 0), handed)
		assertContentEquals(byteArrayOf(9, 0, 0, 0), full.toByteArray())
	}
}
