package io.github.psd2live.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.ToolColors
import io.github.psd2live.ui.views.IconSmoothTracking
import io.github.psd2live.ui.views.drawToolIcon
import java.io.File
import kotlin.test.Test

/** Every canvas tool icon and the preview rail's toggles on one sheet, in both themes: PSD2LIVE_TOOLS=1 ./gradlew test --tests '*ToolIconSheetTool'. */
class ToolIconSheetTool {
	@Test fun renderSheet() {
		requireTools()
		val directory = output("tool-icons")
		for ((name, theme) in listOf("dark" to ToolColors.Dark, "light" to ToolColors.Light)) {
			val tools = CanvasTool.entries
			val scene = ImageComposeScene(88 * 10 + 32, 88 * ((tools.size + 9) / 10 + 1) + 32, density = Density(4f)) {
				CompactToolTheme(colors = theme) { Sheet(tools) }
			}
			try {
				scene.render().close()
				val image = scene.render(16_000_000)
				try { File(directory, "$name.png").writeBytes(requireNotNull(image.encodeToData()).bytes) } finally { image.close() }
			} finally { scene.close() }
		}
	}

	@Composable private fun Sheet(tools: List<CanvasTool>) {
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
