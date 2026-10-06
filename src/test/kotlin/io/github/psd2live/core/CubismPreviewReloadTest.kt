package io.github.psd2live.core

import com.sun.jna.Pointer
import org.umamo.runtime.model.ParameterId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CubismPreviewReloadTest {
	private val manifest = "model.model3.json"
	private val pages = listOf("model.4096/texture_00.png", "model.4096/texture_01.png")

	private fun bundle(moc: String = "moc-1", page0: String = "page-a", page1: String = "page-b", pagePaths: List<String> = pages): CubismRuntimeBundle {
		val manifestText = """{"Version":3,"FileReferences":{"Moc":"model.moc3","Textures":[${pagePaths.joinToString(",") { "\"$it\"" }}],""" +
			""""Motions":{"Idle":[{"File":"motions/model.idle.motion3.json"}]}}}"""
		return CubismRuntimeBundle(manifest, listOf(
			CubismRuntimeAsset(manifest, manifestText.encodeToByteArray()),
			CubismRuntimeAsset("model.moc3", moc.encodeToByteArray()),
			CubismRuntimeAsset(pagePaths[0], page0.encodeToByteArray()),
			CubismRuntimeAsset(pagePaths[1], page1.encodeToByteArray()),
			CubismRuntimeAsset("motions/model.idle.motion3.json", "{}".encodeToByteArray()),
		))
	}

	private fun plan(previous: CubismRuntimeBundle?, next: CubismRuntimeBundle, memory: Boolean = true, replace: Boolean = true) =
		planCubismPreviewReload(previous?.let { CubismBundleFingerprint.of(it) }, CubismBundleFingerprint.of(next), memory, replace)

	@Test fun sameBytesKeepTheModel() {
		assertEquals(CubismPreviewReload.Unchanged, plan(bundle(), bundle()))
	}

	@Test fun changedPagesAloneOnlyReplaceThosePages() {
		assertEquals(CubismPreviewReload.Textures(listOf(1)), plan(bundle(), bundle(page1 = "page-b2")))
		assertEquals(CubismPreviewReload.Textures(listOf(0, 1)), plan(bundle(), bundle(page0 = "x", page1 = "y")))
	}

	@Test fun anyOtherChangeRebuildsFromMemory() {
		assertEquals(CubismPreviewReload.Recreate, plan(bundle(), bundle(moc = "moc-2")))
		assertEquals(CubismPreviewReload.Recreate, plan(bundle(), bundle(moc = "moc-2", page0 = "x")))
		// A page that moved to another file is a layout change, not a texture swap.
		assertEquals(CubismPreviewReload.Recreate,
			plan(bundle(), bundle(pagePaths = listOf("model.2048/texture_00.png", "model.2048/texture_01.png"))))
		// Without the texture entry point a page change also rebuilds.
		assertEquals(CubismPreviewReload.Recreate, plan(bundle(), bundle(page0 = "x"), replace = false))
	}

	@Test fun firstLoadAndOldLibrariesLoadFromScratch() {
		assertEquals(CubismPreviewReload.Full, plan(null, bundle()))
		assertEquals(CubismPreviewReload.Full, plan(bundle(), bundle(), memory = false))
		assertEquals(CubismPreviewReload.Full, plan(bundle(), bundle(page0 = "x"), memory = false))
	}

	@Test fun texturePathsResolveAgainstTheManifestDirectory() {
		val text = """{"FileReferences":{"Textures":["./tex/a.png","tex\\b.png"]}}"""
		assertEquals(listOf("dir/tex/a.png", "dir/tex/b.png"), cubismManifestTexturePaths("dir/m.model3.json", text))
		assertEquals(listOf("tex/a.png", "tex/b.png"), cubismManifestTexturePaths("m.model3.json", text))
	}

	@Test fun encodedBundleCarriesEveryAssetBehindTheNativeHeader() {
		val source = bundle()
		val buffer = ByteBuffer.wrap(encodeCubismPreviewBundle(source)).order(ByteOrder.LITTLE_ENDIAN)
		val magic = ByteArray(9).also(buffer::get)
		assertEquals("QDPREVIEW", magic.decodeToString())
		assertEquals(1, buffer.int)
		val manifestLength = buffer.int
		assertEquals(source.assets.size, buffer.int)
		assertEquals(manifest, ByteArray(manifestLength).also(buffer::get).decodeToString())
		for (asset in source.assets) {
			val pathLength = buffer.int
			val dataLength = buffer.long
			assertEquals(asset.path, ByteArray(pathLength).also(buffer::get).decodeToString())
			assertContentEquals(asset.bytes, ByteArray(dataLength.toInt()).also(buffer::get))
		}
		assertFalse(buffer.hasRemaining())
	}

	@Test fun textureOnlyEditUploadsPagesIntoTheLiveModel() = withSession { native, session ->
		session.load(bundle(), parameters)
		session.render(request())
		session.awaitIdle()
		val handle = native.live.single()
		val renders = native.renders.size

		session.load(bundle(page1 = "page-b2"), parameters)
		session.awaitIdle()
		assertEquals(1, native.memoryCreates.size, "the model must not be rebuilt")
		assertEquals(listOf(Triple(handle, 1, "page-b2")), native.textureReplacements)
		assertEquals(CubismPreviewReload.Textures(listOf(1)), session.lastAppliedReload)
		assertEquals(listOf(handle), native.live)
		assertTrue(native.renders.size > renders, "the view renders its last request with the new page")
	}

	@Test fun aRefusedPageFallsBackToRebuildingFromMemory() = withSession { native, session ->
		session.load(bundle(), parameters)
		session.render(request())
		session.awaitIdle()
		native.refuseTextures = true
		session.load(bundle(page0 = "x"), parameters)
		session.awaitIdle()
		assertEquals(CubismPreviewReload.Recreate, session.lastAppliedReload)
		assertEquals(2, native.memoryCreates.size)
		assertEquals(1, native.live.size)
	}

	@Test fun unchangedBundleTouchesNothing() = withSession { native, session ->
		session.load(bundle(), parameters)
		session.render(request())
		session.awaitIdle()
		val calls = native.calls.size
		session.load(bundle(), parameters)
		session.awaitIdle()
		assertEquals(1, native.memoryCreates.size)
		assertTrue(native.textureReplacements.isEmpty())
		assertTrue(native.calls.drop(calls).none { it.startsWith("create") || it.startsWith("destroy") })
	}

	@Test fun mocChangeRebuildsInPlaceAndRestoresThePose() = withSession { native, session ->
		session.load(bundle(), parameters)
		session.render(request(mapOf(ParameterId("ParamA") to 0.75f)))
		session.awaitIdle()
		val old = native.live.single()
		assertEquals(0.75f, native.values(old)["ParamA"])

		session.load(bundle(moc = "moc-2"), parameters)
		session.awaitIdle()
		assertEquals(2, native.memoryCreates.size)
		assertTrue(native.memoryCreates.last().contains("moc-2"))
		val fresh = native.live.single()
		assertNotEquals(old, fresh)
		assertTrue(old in native.destroyed)
		assertEquals(0.75f, native.values(fresh)["ParamA"], "the new model starts at the old model's pose")
		assertTrue(native.fileCreates.isEmpty(), "the preview path writes no files")
	}

	@Test fun oldLibrariesKeepTheFileBasedReload() = withSession(memory = false, replace = false) { native, session ->
		session.load(bundle(), parameters)
		session.render(request())
		session.awaitIdle()
		session.load(bundle(page1 = "page-b2"), parameters)
		session.awaitIdle()
		assertEquals(2, native.fileCreates.size)
		assertTrue(native.memoryCreates.isEmpty())
		assertTrue(native.textureReplacements.isEmpty())
		assertEquals(1, native.live.size)
	}

	@Test fun reloadsQueuedBehindABusyThreadCoalesceToTheNewest() {
		val gate = CountDownLatch(1)
		withSession(blockFirstCreate = gate) { native, session ->
			session.load(bundle(), parameters)
			assertTrue(native.entered.await(5, TimeUnit.SECONDS))
			session.load(bundle(moc = "moc-2"), parameters)
			session.load(bundle(moc = "moc-3"), parameters)
			session.load(bundle(moc = "moc-4"), parameters)
			gate.countDown()
			session.awaitIdle()
			// The first load was already running; of the three queued behind it only the newest is applied.
			assertEquals(2, native.memoryCreates.size)
			assertTrue(native.memoryCreates.last().contains("moc-4"))
			assertEquals(1, native.live.size)
		}
	}

	private val parameters = listOf(ParameterId("ParamA"), ParameterId("ParamB"))

	private fun request(overrides: Map<ParameterId, Float> = emptyMap()) = CubismSdkPreviewSession.RenderRequest(
		width = 2, height = 2, scale = 1f, offsetX = 0f, offsetY = 0f, deltaTime = 0f,
		pointerX = 0f, pointerY = 0f, animationEnabled = false, nativeClock = false,
		parameterOverrides = overrides, viewId = "view",
	)

	private fun withSession(
		memory: Boolean = true,
		replace: Boolean = true,
		blockFirstCreate: CountDownLatch? = null,
		body: (FakeNative, CubismSdkPreviewSession) -> Unit,
	) {
		val native = FakeNative(listOf("ParamA", "ParamB"), blockFirstCreate)
		CubismSdkPreviewSession({}, {}, null) { CubismNativeBinding(native, memory, replace) }.use { body(native, it) }
	}

	private class FakeNative(private val parameterOrder: List<String>, private val gate: CountDownLatch?) : CubismNativeApi {
		val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
		val memoryCreates = mutableListOf<String>()
		val fileCreates = mutableListOf<String>()
		val textureReplacements = mutableListOf<Triple<Long, Int, String>>()
		val destroyed = mutableListOf<Long>()
		val renders = mutableListOf<Long>()
		val entered = CountDownLatch(1)
		@Volatile var refuseTextures = false
		private val models = linkedMapOf<Long, MutableMap<String, Float>>()
		private var nextHandle = 1L
		val live: List<Long> get() = models.keys.toList()
		fun values(handle: Long): Map<String, Float> = models.getValue(handle)

		private fun create(): Pointer {
			val handle = nextHandle++
			models[handle] = parameterOrder.associateWith { 0f }.toMutableMap()
			return Pointer(handle)
		}
		private fun Pointer.id() = Pointer.nativeValue(this)

		override fun Live2D_InitOffscreen() = 1
		override fun Live2D_Shutdown() { calls += "shutdown" }
		override fun Live2D_CreateModel(modelFilePath: String): Pointer? {
			calls += "create-file"; fileCreates += modelFilePath
			return create()
		}
		override fun Live2D_CreateModelFromMemory(bundle: ByteArray, size: Long): Pointer? {
			calls += "create-memory"
			assertEquals(bundle.size.toLong(), size)
			if (memoryCreates.isEmpty() && gate != null) {
				entered.countDown()
				check(gate.await(5, TimeUnit.SECONDS))
			}
			memoryCreates += bundle.decodeToString()
			return create()
		}
		override fun Live2D_ReplaceTexture(handle: Pointer, index: Int, data: ByteArray, size: Long, width: Int, height: Int): Int {
			calls += "replace"
			if (refuseTextures) return 0
			textureReplacements += Triple(handle.id(), index, data.decodeToString())
			return 1
		}
		override fun Live2D_DestroyModel(handle: Pointer) {
			calls += "destroy"; destroyed += handle.id(); models.remove(handle.id())
		}
		override fun Live2D_Update(handle: Pointer, deltaTime: Float) { calls += "update" }
		override fun Live2D_SetDragging(handle: Pointer, x: Float, y: Float) = Unit
		override fun Live2D_StartMotion(handle: Pointer, group: String, index: Int, priority: Int): Int { calls += "motion"; return 1 }
		override fun Live2D_SetParameterValue(handle: Pointer, parameterId: String, value: Float) {
			models.getValue(handle.id())[parameterId] = value
		}
		override fun Live2D_GetParameterValue(handle: Pointer, parameterId: String): Float = models.getValue(handle.id())[parameterId] ?: 0f
		override fun Live2D_RefreshModel(handle: Pointer) = Unit
		override fun Live2D_GetParameterCount(handle: Pointer): Int = parameterOrder.size
		override fun Live2D_CopyParameterValues(handle: Pointer, output: Pointer, capacity: Int): Int {
			val values = models.getValue(handle.id())
			val count = minOf(capacity, parameterOrder.size)
			for (index in 0 until count) output.setFloat(index * 4L, values.getValue(parameterOrder[index]))
			return count
		}
		override fun Live2D_RenderToRgba(handle: Pointer, width: Int, height: Int, scale: Float, offsetX: Float, offsetY: Float, output: Pointer): Int {
			calls += "render"; renders += handle.id()
			return 1
		}
		override fun Live2D_GetLastError(): Pointer? = null
	}
}
