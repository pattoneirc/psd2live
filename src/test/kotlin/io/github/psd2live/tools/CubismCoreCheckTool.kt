package io.github.psd2live.tools

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.psd2live.core.PSD2LivePipeline
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hands the preview bundle's moc3 of each bundled sample (tml, ds) to the official Cubism Core's consistency check
 * (csmHasMocConsistency). MocDefaultColorsTest checks small fresh models the same way.
 *
 * PSD2LIVE_TOOLS=1 PSD2LIVE_TEST_CUBISM_CORE=<path to the Core library> ./gradlew test --tests '*CubismCoreCheckTool'
 * Skipped without PSD2LIVE_TEST_CUBISM_CORE. Writes nothing; a sample the Core rejects fails the run.
 */
class CubismCoreCheckTool {
	interface Core : Library {
		fun csmHasMocConsistency(moc: Pointer, size: Int): Int
	}

	@Test fun previewBundles() {
		requireTools()
		val corePath = System.getenv("PSD2LIVE_TEST_CUBISM_CORE")
		assumeTrue(!corePath.isNullOrBlank(), "Set PSD2LIVE_TEST_CUBISM_CORE to the official Core library")
		val core = Native.load(corePath, Core::class.java)
		for (sample in listOf("tml", "ds")) {
			val preview = PSD2LivePipeline().buildPreview(Path.of("examples/$sample/psd-input/$sample.psd"))
			val bytes = preview.runtimeBundle.assets.single { it.path.endsWith(".moc3") }.bytes
			// The Core reads a moc aligned to 64 bytes.
			Memory(bytes.size.toLong() + 63).use { memory ->
				val aligned = memory.share((64 - Pointer.nativeValue(memory) % 64) % 64)
				aligned.write(0, bytes, 0, bytes.size)
				assertEquals(1, core.csmHasMocConsistency(aligned, bytes.size), "$sample preview rejected by Core")
			}
			println("$sample: the Core accepts the preview moc3 (${bytes.size} bytes)")
		}
	}
}
