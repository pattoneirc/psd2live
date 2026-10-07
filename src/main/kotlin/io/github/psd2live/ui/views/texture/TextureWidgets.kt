package io.github.psd2live.ui.views.texture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.AtlasBudget
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.TextureDensity
import io.github.psd2live.ui.state.TextureSnapshot
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

private val HEAT_LOW = Color(0xFF3D7BE0)
private val HEAT_MID = Color(0xFF4CB86A)
private val HEAT_HIGH = Color(0xFFE5683B)

/** The heatmap colour of [heat] in -1..1: blue below one texel per canvas unit, green at one, orange above. */
internal fun heatColor(heat: Float): Color =
	if (heat < 0f) lerp(HEAT_MID, HEAT_LOW, -heat) else lerp(HEAT_MID, HEAT_HIGH, heat)

/**
 * The heatmap's scale: atlas texture pixels per canvas unit, a quarter to four. [marker], a heat in -1..1, marks
 * one value on it - the selection's effective density in the texture panel.
 */
@Composable
internal fun HeatLegend(modifier: Modifier = Modifier, marker: Float? = null, showUnit: Boolean = true) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(modifier) {
		Canvas(Modifier.fillMaxWidth().height(if (marker != null) 10.dp else 6.dp)) {
			val bar = if (marker != null) size.height * 0.6f else size.height
			drawRect(Brush.horizontalGradient(listOf(HEAT_LOW, HEAT_MID, HEAT_HIGH)), Offset(0f, (size.height - bar) / 2f), Size(size.width, bar))
			if (marker != null) {
				val x = (marker.coerceIn(-1f, 1f) + 1f) / 2f * size.width
				drawRect(colors.textPrimary, Offset(x - 1.5f, 0f), Size(3f, size.height))
			}
		}
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			for (label in listOf("¼", "½", "1", "2", "4")) {
				Text(label, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
			}
		}
		if (showUnit) Text(tr("texture.legend.unit"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
	}
}

/** The heatmap toggle's glyph: a ramp of the heat colours. */
@Composable
internal fun IconHeatmap(modifier: Modifier = Modifier) {
	Canvas(modifier.size(14.dp)) {
		val cell = size.width / 2f - 1f
		val ramp = listOf(HEAT_LOW, HEAT_MID, HEAT_MID, HEAT_HIGH)
		for (i in 0 until 4) drawRect(ramp[i], Offset((i % 2) * (cell + 2f), (i / 2) * (cell + 2f)), Size(cell, cell))
	}
}

/** The outline toggle's glyph: two tile outlines. */
@Composable
internal fun IconTileOutlines(tint: Color, modifier: Modifier = Modifier) {
	Canvas(modifier.size(14.dp)) {
		val stroke = Stroke(1.2f)
		drawRect(tint, Offset(1f, 1f), Size(size.width * 0.55f, size.height * 0.55f), style = stroke)
		drawRect(tint, Offset(size.width * 0.38f, size.height * 0.38f), Size(size.width * 0.6f - 1f, size.height * 0.6f - 1f), style = stroke)
	}
}

/**
 * The atlas budget - page size, page count and padding - as one block. The atlas view's budget menu and the
 * texture panel without a selection both show this block, so the budget has one editor.
 */
@Composable
internal fun AtlasBudgetControls(vm: PSD2LiveViewModel, snapshot: TextureSnapshot, enabled: Boolean, labelWidth: Dp = 92.dp) {
	val budget = snapshot.atlas.budget
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			FieldLabel(tr("texture.atlas.pageSize"), labelWidth)
			CompactDropdown(
				items = (ATLAS_PAGE_SIZES + budget.pageSize).distinct().sorted(),
				selectedItem = budget.pageSize,
				onItemSelected = { vm.setAtlasBudget(snapshot, pageSize = it) },
				itemLabel = { "$it × $it" },
				enabled = enabled,
				modifier = Modifier.width(120.dp),
				height = 22.dp,
			)
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			FieldLabel(tr("texture.atlas.maxPages"), labelWidth)
			CommitNumberField(budget.maxPages.toDouble(), { vm.setAtlasBudget(snapshot, maxPages = it.toInt()) },
				Modifier.width(120.dp), min = 1.0, max = 64.0, enabled = enabled)
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			FieldLabel(tr("texture.atlas.padding"), labelWidth)
			CommitNumberField(budget.padding.toDouble(), { vm.setAtlasBudget(snapshot, padding = it.toInt()) },
				Modifier.width(120.dp), min = 0.0, max = 32.0, unit = "px", enabled = enabled)
		}
		val defaults = AtlasBudget()
		if (budget != defaults) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Spacer(Modifier.width(labelWidth))
				CompactButton(tr("texture.atlas.resetBudget"), onClick = { vm.resetAtlasBudget(snapshot) }, enabled = enabled, height = 20.dp)
			}
		}
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

/** The lock command for [locked] of [total] layers: lock them, unlock them all, or lock the rest. */
internal fun lockLabel(locked: Int, total: Int): String = when (locked) {
	0 -> tr("texture.inspector.lock")
	total -> tr("texture.inspector.unlock")
	else -> tr("texture.inspector.lockMixed", locked, total)
}

/** A small coloured box, as the heatmap draws a tile. */
@Composable
internal fun Swatch(color: Color, modifier: Modifier = Modifier) {
	Box(modifier) { Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(color) } }
}
