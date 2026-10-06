package io.github.psd2live.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*

/** Live updates through the real native bridge; skipped unless a locally built renderer is installed. */
@Tag("slow")
@EnabledOnOs(OS.WINDOWS)
class CubismPreviewHotReloadNativeTest {
	private fun requireNative() {
		assumeTrue(Files.isRegularFile(Path.of("src/main/resources/cubism/windows-x86_64/live2d_renderer.dll")) ||
			listOf(System.getProperty("psd2live.cubism.path"), System.getenv("CUBISM_SDK_PATH")).any { !it.isNullOrBlank() },
			"Optional Cubism runtime is not installed")
	}

	@Test fun texturePagesAndMotionsUpdateTheLiveModel() {
		requireNative()
		val preview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val bundle = preview.runtimeBundle
		val parameters = preview.rig.puppet.parameters.map { it.id }
		val pages = cubismManifestTexturePaths(bundle.manifestPath,
			bundle.assets.first { it.path == bundle.manifestPath }.bytes.decodeToString())
		val tinted = bundle.copy(assets = bundle.assets.map { asset ->
			if (asset.path != pages.first()) asset else asset.copy(bytes = tint(asset.bytes))
		})
		val motion = bundle.assets.first { it.path.endsWith(".motion3.json") }.path
		val retimed = tinted.copy(assets = tinted.assets.map { asset ->
			if (asset.path != motion) asset else asset.copy(bytes = asset.bytes + " ".encodeToByteArray())
		})

		val frames = LinkedBlockingQueue<CubismSdkFrame>()
		val statuses = LinkedBlockingQueue<String>()
		var swappedDigest = 0
		CubismSdkPreviewSession({ frames += it }, { statuses += it ?: "" }).use { session ->
			fun frame(): BufferedImage {
				session.awaitIdle()
				SwingUtilities.invokeAndWait {}
				frames.clear()
				session.render(request())
				session.awaitIdle()
				return assertNotNull(frames.poll(10, TimeUnit.SECONDS), "no frame (status ${statuses.toList()})").image
			}
			session.load(bundle, parameters)
			val original = frame()
			assertTrue(opaquePixels(original) > 0, "the model renders (status ${statuses.toList()})")

			session.load(tinted, parameters)
			val swapped = frame()
			assertEquals(CubismPreviewReload.Textures(listOf(0)), session.lastAppliedReload)
			assertNotEquals(digest(original), digest(swapped), "the replaced page shows")
			swappedDigest = digest(swapped)

			session.load(retimed, parameters)
			val rebuilt = frame()
			assertEquals(CubismPreviewReload.Recreate, session.lastAppliedReload)
			assertEquals(digest(swapped), digest(rebuilt), "rebuilding from memory keeps the replaced page")
			assertTrue(statuses.none { it.isNotEmpty() && it != "ready" }, "status ${statuses.toList()}")
		}
		// A fresh session that loads the tinted bundle from scratch draws what the live swap drew.
		CubismSdkPreviewSession({ frames += it }, {}).use { session ->
			session.load(tinted, parameters)
			session.awaitIdle()
			SwingUtilities.invokeAndWait {}
			frames.clear()
			session.render(request())
			session.awaitIdle()
			val fresh = assertNotNull(frames.poll(10, TimeUnit.SECONDS)).image
			assertEquals(swappedDigest, digest(fresh))
		}
	}

	private fun request() = CubismSdkPreviewSession.RenderRequest(
		width = 192, height = 256, scale = 1f, offsetX = 0f, offsetY = 0f, deltaTime = 0f,
		pointerX = 0f, pointerY = 0f, animationEnabled = false, nativeClock = false,
		parameterOverrides = emptyMap(), viewId = "native-test", frameTimeNanos = System.nanoTime(),
	)

	private fun tint(png: ByteArray): ByteArray {
		val image = ImageIO.read(png.inputStream())
		val argb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until image.height) for (x in 0 until image.width) {
			val alpha = image.getRGB(x, y) ushr 24
			argb.setRGB(x, y, (alpha shl 24) or 0xff0000)
		}
		return ByteArrayOutputStream().also { ImageIO.write(argb, "png", it) }.toByteArray()
	}

	private fun opaquePixels(image: BufferedImage): Int =
		image.getRGB(0, 0, image.width, image.height, null, 0, image.width).count { it ushr 24 != 0 }

	private fun digest(image: BufferedImage): Int =
		image.getRGB(0, 0, image.width, image.height, null, 0, image.width).contentHashCode()
}
