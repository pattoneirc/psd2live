package io.github.psd2live.ui.views.texture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

private val HEAT_LOW = Color(0xFF3D7BE0)
private val HEAT_MID = Color(0xFF4CB86A)
private val HEAT_HIGH = Color(0xFFE5683B)

/** The heatmap colour of [heat] in -1..1: blue below one texel per canvas unit, green at one, orange above. */
internal fun heatColor(heat: Float): Color =
	if (heat < 0f) lerp(HEAT_MID, HEAT_LOW, -heat) else lerp(HEAT_MID, HEAT_HIGH, heat)

/** The heatmap's scale: atlas texture pixels per canvas unit, a quarter to four. */
@Composable
internal fun HeatLegend(modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(modifier) {
		Canvas(Modifier.fillMaxWidth().height(6.dp)) {
			drawRect(Brush.horizontalGradient(listOf(HEAT_LOW, HEAT_MID, HEAT_HIGH)))
		}
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			for (label in listOf("¼", "½", "1", "2", "4")) {
				Text(label, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
			}
		}
		Text(tr("texture.legend.unit"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
	}
}

/** A fixed-width label in front of a field. */
@Composable
internal fun FieldLabel(text: String, width: Dp = 92.dp) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(text, style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted, maxLines = 1,
		overflow = TextOverflow.Ellipsis, modifier = Modifier.width(width))
}

/** A read-only value beside its label. */
@Composable
internal fun ValueRow(label: String, value: String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(verticalAlignment = Alignment.CenterVertically) {
		FieldLabel(label)
		Text(value, style = typography.mono.copy(fontSize = 11.sp), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/**
 * A number field that commits once per edit session: typing and the step arrows change a local draft, and
 * [onCommit] runs when the session ends with a value different from [value].
 */
@Composable
internal fun CommitNumberField(
	value: Double,
	onCommit: (Double) -> Unit,
	modifier: Modifier = Modifier,
	min: Double = -100000.0,
	max: Double = 100000.0,
	step: Double = 1.0,
	decimals: Int = 0,
	unit: String = "",
	enabled: Boolean = true,
) {
	var draft by remember(value) { mutableStateOf(value) }
	CompactNumberSpinner(
		value = draft,
		onValueChange = { draft = it },
		modifier = modifier,
		min = min, max = max, step = step, decimals = decimals, unit = unit, enabled = enabled,
		onEditEnd = { if (draft != value) onCommit(draft) },
	)
}

/** "1.00×", or "0.25×", for a density multiplier. */
internal fun multiplier(value: Float): String = TextureDensity.format(value) + "×"

/** A small coloured box, as the heatmap draws a tile. */
@Composable
internal fun Swatch(color: Color, modifier: Modifier = Modifier) {
	Box(modifier) { Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(color) } }
}
