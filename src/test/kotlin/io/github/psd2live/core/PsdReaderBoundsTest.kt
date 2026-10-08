package io.github.psd2live.core

import org.umamo.format.psd.PsdReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*

class PsdReaderBoundsTest {
    /** A flattened PSD: header, empty colour mode data and resources, then [layerSection] and a merged image. */
    private fun flattened(layerSection: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            writeBytes("8BPS"); writeShort(1); write(ByteArray(6))
            writeShort(3); writeInt(4); writeInt(4); writeShort(8); writeShort(3)
            writeInt(0); writeInt(0)
            layerSection(this)
            // Merged image: raw compression, then bytes a layer parser must not read as records.
            writeShort(0); write(ByteArray(48) { 0x7F })
        }
        return bytes.toByteArray()
    }

    @Test fun anEmptyLayerAndMaskSectionHasNoLayers() {
        val parse = PsdReader.read(flattened { it.writeInt(0) })
        assertEquals(4, parse.widthPx)
        assertTrue(parse.layers.isEmpty())
    }

    @Test fun anEmptyLayerInfoHasNoLayers() {
        val parse = PsdReader.read(flattened { it.writeInt(8); it.writeInt(0); it.writeInt(0) })
        assertTrue(parse.layers.isEmpty())
    }

    @Test fun colourModeDataLongerThanTheFileIsRejected() {
        val bytes = flattened { it.writeInt(0) }
        // The colour mode data length follows the 26-byte header.
        bytes[26] = 0x7F
        assertFailsWith<IllegalArgumentException> { PsdReader.read(bytes) }
    }
}
