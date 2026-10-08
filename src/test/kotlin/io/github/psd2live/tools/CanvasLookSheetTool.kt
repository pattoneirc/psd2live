package io.github.psd2live.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.HandleShape
import io.github.psd2live.ui.views.IconSmoothTracking
import io.github.psd2live.ui.views.MeshLook
import io.github.psd2live.ui.views.drawMeshHandle
import io.github.psd2live.ui.views.drawMeshWires
import io.github.psd2live.ui.views.drawReachRing
import io.github.psd2live.ui.views.drawSelectedFace
import io.github.psd2live.ui.views.drawSelectedWires
import io.github.psd2live.ui.views.drawToolIcon
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test

/**
 * Sheets of what the canvas draws, for checking it by eye: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*CanvasLookSheetTool'.
 * Writes build/tools/canvas-look/: [meshLook] the editable-geometry look (MeshLook) in each state over dark, mid, light
 * and saturated art (mesh-look.png); [toolIcons] every canvas tool icon and the preview rail's toggles, in both themes
 * (tool-icons-{dark,light}.png).
 */
class CanvasLookSheetTool {
	@Test fun meshLook() {
		requireTools()
		val accent = ToolColors.Dark.accent
		renderPng(File(output("canvas-look"), "mesh-look.png"), 1200, 300) {
			Canvas(Modifier.fillMaxSize()) {
				listOf(Color(0xFF26282E), Color(0xFF8A7F78), Color(0xFFF1E6DA), Color(0xFF4A78D8)).forEachIndexed { panel, art ->
					val ox = panel * 300f
					drawRect(art, Offset(ox, 0f), Size(300f, 300f))
					val step = 40f
					fun at(r: Int, c: Int) = Offset(ox + 30f + c * step, 30f + r * step)
					val wires = ArrayList<Offset>(); val chosen = ArrayList<Offset>()
					for (r in 0..5) for (c in 0..5) {
						if (c < 5) (if (r == 1 && c in 1..2) chosen else wires).let { it += at(r, c); it += at(r, c + 1) }
						if (r < 5) { wires += at(r, c); wires += at(r + 1, c) }
					}
					drawSelectedFace(at(3, 0), at(3, 1), at(4, 0), accent)
					drawMeshWires(wires, MeshLook.Structure)
					drawSelectedWires(chosen, accent)
					val cursor = at(4, 4) + Offset(10f, -6f)
					for (r in 0..5) for (c in 0..5) {
						val p = at(r, c)
						drawMeshHandle(p, MeshLook.Structure, accent, selected = (r == 1 && c in 1..3) || (r == 0 && c == 5),
							hovered = (r == 0 && c == 4) || (r == 0 && c == 5), shape = if (c == 0 && r < 3) HandleShape.SQUARE else HandleShape.ROUND)
						val d = hypot(p.x - cursor.x, p.y - cursor.y)
						drawReachRing(p, (1f - d / 90f).coerceIn(0f, 1f), MeshLook.Reach)
					}
					drawCircle(Color.Black.copy(alpha = 0.7f), 90f, cursor, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
					drawCircle(Color.White, 90f, cursor, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
				}
			}
		}
	}

	@Test fun toolIcons() {
		requireTools()
		val directory = output("canvas-look")
		val tools = CanvasTool.entries
		for ((name, theme) in listOf("dark" to ToolColors.Dark, "light" to ToolColors.Light)) {
			renderPng(File(directory, "tool-icons-$name.png"), 88 * 10 + 32, 88 * ((tools.size + 9) / 10 + 1) + 32, colors = theme, density = 4f) {
				IconSheet(tools)
			}
		}
	}

	@Composable private fun IconSheet(tools: List<CanvasTool>) {
		val colors = LocalToolColors.current
		Column(Modifier.background(colors.windowBackground).padding(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
			tools.chunked(10).forEach { row ->
				Row { row.forEach { tool -> Cell { Canvas(Modifier.size(18.dp)) { drawToolIcon(tool, colors.textPrimary) } } } }
			}
			Row {
				Cell { IconMouse(active = false, tint = colors.textPrimary) }
				Cell { IconSmoothTracking(colors.textPrimary) }
				Cell { IconPhysics(active = false, tint = colors.textPrimary) }
				Cell { IconSmoothTracking(colors.accent) }
			}
		}
	}

	@Composable private fun Cell(content: @Composable () -> Unit) =
		Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { content() }
}
