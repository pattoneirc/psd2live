package io.github.psd2live.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.HandleShape
import io.github.psd2live.ui.views.MeshLook
import io.github.psd2live.ui.views.drawMeshHandle
import io.github.psd2live.ui.views.drawMeshWires
import io.github.psd2live.ui.views.drawReachRing
import io.github.psd2live.ui.views.drawSelectedFace
import io.github.psd2live.ui.views.drawSelectedWires
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test

/** The editable-geometry look (MeshLook) in each state over dark, mid and light art: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*MeshLookSheetTool'. */
class MeshLookSheetTool {
	@Test fun renderSheet() {
		requireTools()
		val accent = ToolColors.Dark.accent
		val scene = ImageComposeScene(900, 300, density = Density(1f)) {
			Canvas(Modifier.fillMaxSize()) {
				listOf(Color(0xFF26282E), Color(0xFF8A7F78), Color(0xFFF1E6DA)).forEachIndexed { panel, art ->
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
						drawReachRing(p, (1f - d / 70f).coerceIn(0f, 1f), MeshLook.Reach)
					}
					drawCircle(Color.Black.copy(alpha = 0.7f), 70f, cursor, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
					drawCircle(Color.White, 70f, cursor, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
				}
			}
		}
		try {
			scene.render().close()
			val image = scene.render(16_000_000)
			try { File(output("mesh-look"), "sheet.png").writeBytes(requireNotNull(image.encodeToData()).bytes) } finally { image.close() }
		} finally { scene.close() }
	}
}
