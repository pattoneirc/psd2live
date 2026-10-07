package io.github.psd2live.ui.views.texture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.state.shownTiles
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The atlas page edits in real time: a gesture shows while the pointer moves, also after earlier commits; released, it
 * goes to the edit session, which shows it at once and commits nothing until it is applied - then as one history node,
 * landing as the session showed it.
 */
class AtlasRealtimeEditTest {
	@OptIn(ExperimentalComposeUiApi::class)
	@Test fun gesturesShowLiveAndApplyAsOneStep() = kotlinx.coroutines.runBlocking<Unit> {
		val saved = AppSettings.softwareCanvas
		AppSettings.softwareCanvas = true
		val temp = Files.createTempDirectory("atlas-realtime")
		fun png(name: String, argb: Int) = temp.resolve("$name.png").also { path ->
			val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
			for (y in 0 until 24) for (x in 0 until 24) image.setRGB(x, y, argb)
			javax.imageio.ImageIO.write(image, "png", path.toFile())
		}
		try {
			PSD2LiveViewModel().use { vm ->
				DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
					vm.attachWorkspace(workspace)
					workspace.createArtwork(buildJsonObject {
						put("width", JsonPrimitive(64)); put("height", JsonPrimitive(64))
						put("layers", buildJsonArray {
							for ((name, argb) in listOf("red" to 0xffff0000.toInt(), "blue" to 0xff0000ff.toInt())) add(buildJsonObject {
								put("path", JsonPrimitive(png(name, argb).toString())); put("name", JsonPrimitive(name)); put("role", JsonPrimitive("objects"))
							})
						})
					})
					// A small page, so a tile spans many view pixels. It is set before the view is composed: the gesture
					// handler is keyed on the page size, and a change would hand it fresh state the bug below never sees.
					vm.setAtlasBudget(requireNotNull(vm.textureSnapshot()), pageSize = 256)
					vm.awaitTextureEdits()
					val first = requireNotNull(vm.textureSnapshot())
					val red = first.atlas.tiles.single { first.layer(it.layerId)?.name == "red" }
					vm.selectLayer(red.layerId)
					val scene = ImageComposeScene(400, 400, density = Density(1f)) {
						CompactToolTheme(colors = ToolColors.Dark) {
							val state by vm.state.collectAsState()
							Box(Modifier.fillMaxSize()) { AtlasPageView(state, vm) }
						}
					}
					var clock = 0L
					fun render(): BufferedImage {
						clock += 16_000_000L
						val image = scene.render(clock)
						return org.jetbrains.skia.Bitmap.makeFromImage(image).let { bitmap ->
							val out = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
							for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) out.setRGB(x, y, bitmap.getColor(x, y))
							bitmap.close(); image.close(); out
						}
					}
					fun settle() { repeat(20) { render(); Thread.sleep(20) } }
					fun view(x: Float, y: Float): Offset { val c = AtlasPageProbe.camera; return Offset(c[0] + x * c[2], c[1] + y * c[2]) }
					fun isRed(image: BufferedImage, at: Offset): Boolean {
						val c = image.getRGB(at.x.toInt(), at.y.toInt())
						return (c ushr 16 and 0xff) > 200 && (c ushr 8 and 0xff) < 60 && (c and 0xff) < 60
					}
					settle()

					// A first applied session, so the gestures below run on a later version than the view was first composed with.
					vm.setTextureDensity(first, listOf(red.layerId), 0.5f)
					vm.applyTextureSession()
					vm.awaitTextureEdits()
					settle()
					// The two squares were packed side by side, a padding apart: giving them their meshes' cells to keep must
					// not make them meet by the cells' rounding and push one away.
					assertNull(vm.state.value.textureWorkspace.error)
					assertEquals(0.5f, requireNotNull(vm.textureSnapshot()).layer(red.layerId)?.override?.density)
					val nodes = vm.state.value.historySnapshot?.nodes?.size
					val tile = requireNotNull(vm.textureSnapshot()).tilesByLayer.getValue(red.layerId)
					val pageSize = requireNotNull(vm.textureSnapshot()).atlas.pages[tile.page].width
					val target = pageSize - tile.width * 3f to pageSize - tile.height * 3f
					val from = view(tile.x + tile.width / 2f, tile.y + tile.height / 2f)
					val to = view(target.first + tile.width / 2f, target.second + tile.height / 2f)
					val primary = PointerButtons(isPrimaryPressed = true)
					scene.sendPointerEvent(PointerEventType.Move, from)
					scene.sendPointerEvent(PointerEventType.Press, from, buttons = primary, button = PointerButton.Primary)
					for (i in 1..10) { scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (i / 10f), buttons = primary); render() }
					assertTrue(isRed(render(), to), "the dragged tile shows under the pointer while it moves")
					assertTrue(!isRed(render(), from), "and no longer where it was")

					scene.sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary)
					// Released into the session: shown where it was dropped, nothing committed.
					assertTrue(vm.state.value.textureWorkspace.session.containsKey(red.layerId), "the move is in the session")
					assertTrue(isRed(render(), to), "the dropped tile shows at once")
					assertFalse(vm.state.value.textureWorkspace.busy, "nothing commits while the session is open")
					assertEquals(nodes, vm.state.value.historySnapshot?.nodes?.size)
					// Back to full size where it was dropped, in free space, still in the session; a step back and forth.
					vm.scaleTextureDensity(requireNotNull(vm.textureSnapshot()), listOf(red.layerId), 2f)
					assertNull(vm.state.value.textureWorkspace.error)
					vm.undoTextureSession()
					assertEquals(0.5f, vm.state.value.textureWorkspace.session.getValue(red.layerId).density, "the undo steps back to the move")
					vm.redoTextureSession()
					assertEquals(1f, vm.state.value.textureWorkspace.session.getValue(red.layerId).density)

					// A corner drag on the session's tile: it shrinks under the pointer from its opposite corner.
					val shown = requireNotNull(vm.textureSnapshot()).let { s -> s.shownTiles(tile.page, vm.state.value.textureWorkspace.shown).single { it.layerId == red.layerId } }
					settle()
					val grip = view((shown.x + shown.width).toFloat(), (shown.y + shown.height).toFloat())
					val anchor = view(shown.x.toFloat(), shown.y.toFloat())
					val outward = anchor + (grip - anchor) * 0.6f
					scene.sendPointerEvent(PointerEventType.Move, grip)
					scene.sendPointerEvent(PointerEventType.Press, grip, buttons = primary, button = PointerButton.Primary)
					AtlasPageProbe.liftedDraws.set(0)
					for (i in 1..10) { scene.sendPointerEvent(PointerEventType.Move, grip + (outward - grip) * (i / 10f), buttons = primary); render() }
					assertTrue(AtlasPageProbe.liftedDraws.get() > 0, "the corner drag draws its tiles at their new size")
					val leftBehind = anchor + (grip - anchor) * 0.8f
					assertTrue(!isRed(render(), leftBehind), "the shrinking tile shows at its new size while the corner moves")
					assertTrue(isRed(render(), anchor + (grip - anchor) * 0.3f))
					scene.sendPointerEvent(PointerEventType.Release, outward, button = PointerButton.Primary)

					// Half a quarter turn: the turned square leaves its upright corners empty.
					assertTrue(vm.rotateTextureTile(requireNotNull(vm.textureSnapshot()), red.layerId, 45f))
					settle()
					val turned = requireNotNull(vm.textureSnapshot()).let { s -> s.shownTiles(tile.page, vm.state.value.textureWorkspace.shown).single { it.layerId == red.layerId } }
					val centre = Offset(turned.x + turned.width / 2f, turned.y + turned.height / 2f)
					val corner = view(centre.x - turned.width * 0.42f, centre.y + turned.height * 0.42f)
					assertTrue(isRed(render(), view(centre.x, centre.y)), "the turned tile shows")
					assertFalse(isRed(render(), corner), "turned: its upright bottom left corner is empty")

					// Applied: one step, everything as the session showed it.
					vm.applyTextureSession()
					assertTrue(vm.state.value.textureWorkspace.session.isEmpty())
					assertTrue(vm.state.value.textureWorkspace.pending.containsKey(red.layerId), "shown as applied until its version lands")
					vm.awaitTextureEdits()
					settle()
					assertNull(vm.state.value.textureWorkspace.error)
					assertEquals(nodes?.plus(1), vm.state.value.historySnapshot?.nodes?.size, "one history node for the whole session")
					val landed = requireNotNull(vm.textureSnapshot()).tilesByLayer.getValue(red.layerId)
					assertEquals(Triple(turned.x, turned.y, 45f), Triple(landed.x, landed.y, landed.rotation), "landed as the session showed it")
					assertEquals(turned.width, landed.width)
					assertTrue(vm.state.value.textureWorkspace.pending.isEmpty())
					assertFalse(isRed(render(), corner), "and the committed page shows it turned")
					scene.close()
				}
			}
		} finally {
			AppSettings.softwareCanvas = saved
		}
	}

	/**
	 * Two triangles whose rectangles overlap but whose meshes do not: one may stand on the other's rectangle, and lands
	 * exactly where it was dropped; where their meshes meet the drop is refused. A corner drag scales without steps
	 * and keeps the opposite corner where it was, as its preview showed.
	 */
	@OptIn(ExperimentalComposeUiApi::class)
	@Test fun tilesCollideByTheirMeshesAndScaleWithoutSteps() = kotlinx.coroutines.runBlocking<Unit> {
		val saved = AppSettings.softwareCanvas
		AppSettings.softwareCanvas = true
		val temp = Files.createTempDirectory("atlas-mesh")
		fun triangle(name: String, lower: Boolean) = temp.resolve("$name.png").also { path ->
			val image = BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB)
			// A wide diagonal band between the two halves stays empty.
			for (y in 0 until 96) for (x in 0 until 96) if (if (lower) y - x > 24 else x - y > 24) image.setRGB(x, y, 0xff40a040.toInt())
			javax.imageio.ImageIO.write(image, "png", path.toFile())
		}
		try {
			PSD2LiveViewModel().use { vm ->
				DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
					vm.attachWorkspace(workspace)
					workspace.createArtwork(buildJsonObject {
						put("width", JsonPrimitive(128)); put("height", JsonPrimitive(128))
						put("layers", buildJsonArray {
							for ((name, lower) in listOf("lower" to true, "upper" to false)) add(buildJsonObject {
								put("path", JsonPrimitive(triangle(name, lower).toString())); put("name", JsonPrimitive(name)); put("role", JsonPrimitive("objects"))
							})
						})
					})
					vm.setAtlasBudget(requireNotNull(vm.textureSnapshot()), pageSize = 512)
					vm.awaitTextureEdits()
					val snapshot = requireNotNull(vm.textureSnapshot())
					fun tile(name: String) = requireNotNull(vm.textureSnapshot()).let { s -> s.atlas.tiles.single { s.layer(it.layerId)?.name == name } }
					val lower = tile("lower"); val upper = tile("upper")

					// On the other's very rectangle: the boxes overlap entirely, the meshes not at all.
					val onTop = assertNotNull(vm.draggedTextureTile(snapshot, upper.layerId, lower.x.toFloat(), lower.y.toFloat()))
					assertFalse(onTop.collides, "rectangles overlap but meshes do not")
					assertTrue(vm.moveTextureTile(snapshot, onTop))
					vm.applyTextureSession()
					vm.awaitTextureEdits()
					assertNull(vm.state.value.textureWorkspace.error)
					val landed = tile("upper")
					assertEquals(lower.x to lower.y, landed.x to landed.y, "the tile lands exactly where it was dropped")
					assertEquals(lower.x to lower.y, tile("lower").let { it.x to it.y }, "and the other stays")

					// Shifted down and left, the upper triangle's mesh runs into the lower one's: refused.
					val after = requireNotNull(vm.textureSnapshot())
					val into = assertNotNull(vm.draggedTextureTile(after, upper.layerId, lower.x.toFloat(), lower.y + 40f))
					assertTrue(into.collides, "meshes overlap")
					assertFalse(vm.moveTextureTile(after, into))

					// Scaling the lower tile by its top left grip keeps its bottom right corner and takes no step.
					val shown = tile("lower")
					val scene = ImageComposeScene(400, 400, density = Density(1f)) {
						CompactToolTheme(colors = ToolColors.Dark) {
							val state by vm.state.collectAsState()
							Box(Modifier.fillMaxSize()) { AtlasPageView(state, vm) }
						}
					}
					var clock = 0L
					fun render() { clock += 16_000_000L; scene.render(clock).close() }
					// Out of the other's way first, so a grown tile has room.
					// Turned half round on the other's rectangle, the upper triangle would lie on the lower one: refused.
					assertFalse(vm.rotateTextureTile(after, upper.layerId, 180f), "turned meshes overlap")
					vm.moveTextureTile(after, assertNotNull(vm.draggedTextureTile(after, upper.layerId, 300f, 300f)))
					vm.applyTextureSession()
					vm.awaitTextureEdits()
					vm.selectLayer(shown.layerId)
					repeat(10) { render(); Thread.sleep(20) }
					fun view(x: Float, y: Float): Offset { val c = AtlasPageProbe.camera; return Offset(c[0] + x * c[2], c[1] + y * c[2]) }
					val grip = view(shown.x.toFloat(), shown.y.toFloat())
					val anchor = view((shown.x + shown.width).toFloat(), (shown.y + shown.height).toFloat())
					val target = anchor + (grip - anchor) * 0.83f
					val primary = PointerButtons(isPrimaryPressed = true)
					scene.sendPointerEvent(PointerEventType.Move, grip)
					scene.sendPointerEvent(PointerEventType.Press, grip, buttons = primary, button = PointerButton.Primary)
					for (i in 1..10) { scene.sendPointerEvent(PointerEventType.Move, grip + (target - grip) * (i / 10f), buttons = primary); render() }
					scene.sendPointerEvent(PointerEventType.Release, target, button = PointerButton.Primary)
					vm.applyTextureSession()
					vm.awaitTextureEdits()
					assertNull(vm.state.value.textureWorkspace.error)
					val scaled = tile("lower")
					val density = requireNotNull(vm.textureSnapshot()).layer(scaled.layerId)?.override?.density ?: 1f
					assertEquals(0.83f, density, 0.02f, "the density follows the pointer")
					val quarter = Math.round(TextureDensity.log2(density) * 4f) / 4f
					assertTrue(kotlin.math.abs(TextureDensity.log2(density) - quarter) > 0.01f, "no quarter-power step: $density")
					assertTrue(kotlin.math.abs(scaled.x + scaled.width - (shown.x + shown.width)) <= 1 &&
						kotlin.math.abs(scaled.y + scaled.height - (shown.y + shown.height)) <= 1, "the opposite corner stays: $shown -> $scaled")
					scene.close()
				}
			}
		} finally {
			AppSettings.softwareCanvas = saved
		}
	}
}
