package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

/*
 * Photoshop's colour picker, for painting: a saturation/brightness field beside a vertical hue strip, the new colour
 * over the current one, the HSB and RGB channels and the hex code, and the colours used last.
 */

/** A colour as hue, saturation and brightness, each 0..1. */
internal data class Hsb(val h: Float, val s: Float, val b: Float) {
	val rgb: Int get() = java.awt.Color.HSBtoRGB(h, s, b) and 0xFFFFFF
	val color: Color get() = Color(0xFF000000L or rgb.toLong())

	companion object {
		/** [rgb] as HSB; a grey, which has no hue of its own, keeps [hue], and black keeps [saturation] too. */
		fun of(rgb: Int, hue: Float = 0f, saturation: Float = 0f): Hsb {
			val hsb = java.awt.Color.RGBtoHSB((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, null)
			return Hsb(if (hsb[1] == 0f || hsb[2] == 0f) hue else hsb[0], if (hsb[2] == 0f) saturation else hsb[1], hsb[2])
		}
	}
}

/** The colour's RGB without its alpha. */
internal fun Color.rgb(): Int = toArgb() and 0xFFFFFF

/** The colours painted with last, newest first, shared by every paint colour control of the session. */
object RecentPaintColors {
	private const val LIMIT = 12
	val colors = mutableStateListOf<Color>()

	fun push(color: Color) {
		val opaque = color.copy(alpha = 1f)
		colors.remove(opaque)
		colors.add(0, opaque)
		while (colors.size > LIMIT) colors.removeAt(colors.lastIndex)
	}
}

/**
 * The HSB the controls edit, following [color] when it changes from outside (a swap, the eyedropper) but keeping its
 * own hue through greys, which RGB alone would lose.
 */
@Composable
private fun rememberHsb(color: Color): androidx.compose.runtime.MutableState<Hsb> {
	val hsb = remember { mutableStateOf(Hsb.of(color.rgb())) }
	LaunchedEffect(color.rgb()) {
		val current = hsb.value
		if (current.rgb != color.rgb()) hsb.value = Hsb.of(color.rgb(), current.h, current.s)
	}
	return hsb
}

/**
 * The saturation/brightness field and the vertical hue strip beside it, editing [color]. [onRelease] runs when a drag
 * on either ends, so the colour can be remembered once rather than at every step.
 */
@Composable
fun PsColorField(
	color: Color,
	onColorChange: (Color) -> Unit,
	modifier: Modifier = Modifier,
	height: Dp = 150.dp,
	onRelease: (Color) -> Unit = {},
) {
	val colors = LocalToolColors.current
	val state = rememberHsb(color)
	val hsb = state.value
	val change by rememberUpdatedState(onColorChange)
	val release by rememberUpdatedState(onRelease)
	fun set(next: Hsb) { state.value = next; change(next.color) }

	Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Box(
			Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(3.dp)).border(1.dp, colors.border, RoundedCornerShape(3.dp))
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)))
		) {
			Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					fun at(p: Offset) = set(state.value.copy(s = (p.x / size.width).coerceIn(0f, 1f), b = (1f - p.y / size.height).coerceIn(0f, 1f)))
					down.consume(); at(down.position)
					drag(down.id) { it.consume(); at(it.position) }
					release(state.value.color)
				}
			}) {
				val pure = Color(java.awt.Color.HSBtoRGB(hsb.h, 1f, 1f))
				drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
				drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
				val at = Offset(hsb.s * size.width, (1f - hsb.b) * size.height)
				// The ring turns dark over light colours, as Photoshop's does.
				val ring = if (hsb.b > 0.6f && hsb.s < 0.45f) Color.Black else Color.White
				drawCircle(ring.copy(alpha = 0.35f), 6.5.dp.toPx(), at, style = Stroke(2.5.dp.toPx()))
				drawCircle(ring, 5.dp.toPx(), at, style = Stroke(1.3.dp.toPx()))
			}
		}
		// The hue strip, red at both ends, with Photoshop's pair of arrows marking the hue.
		Box(Modifier.width(22.dp).fillMaxSize()) {
			Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					fun at(p: Offset) = set(state.value.copy(h = (1f - p.y / size.height).coerceIn(0f, 1f)))
					down.consume(); at(down.position)
					drag(down.id) { it.consume(); at(it.position) }
					release(state.value.color)
				}
			}.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))) {
				val inset = 5.dp.toPx()
				val strip = Size(size.width - inset * 2, size.height)
				drawRect(Brush.verticalGradient(HUES), Offset(inset, 0f), strip)
				drawRect(colors.border, Offset(inset, 0f), strip, style = Stroke(1f))
				val y = (1f - hsb.h) * size.height
				val arrow = 4.5.dp.toPx()
				drawPath(Path().apply { moveTo(0f, y - arrow); lineTo(inset, y); lineTo(0f, y + arrow); close() }, colors.textPrimary)
				drawPath(Path().apply { moveTo(size.width, y - arrow); lineTo(size.width - inset, y); lineTo(size.width, y + arrow); close() },
					colors.textPrimary)
			}
		}
	}
}

/** Red at the top through the hues back to red at the bottom: the strip reads 360° at the top, 0° at the bottom. */
private val HUES = listOf(Color(0xFFFF0000), Color(0xFFFF00FF), Color(0xFF0000FF), Color(0xFF00FFFF), Color(0xFF00FF00),
	Color(0xFFFFFF00), Color(0xFFFF0000))

/** The new colour over the [current] one, as Photoshop shows them; clicking the current one goes back to it. */
@Composable
fun PsColorCompare(new: Color, current: Color, onRevert: () -> Unit, modifier: Modifier = Modifier, width: Dp = 52.dp) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
		Text(tr("editor.paint.colorNew"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
		Column(Modifier.padding(top = 2.dp).fillMaxWidth().border(1.dp, colors.border)) {
			Box(Modifier.fillMaxWidth().height(22.dp).background(new.copy(alpha = 1f)))
			Box(Modifier.fillMaxWidth().height(22.dp).background(current.copy(alpha = 1f)).clickable(onClick = onRevert)
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))))
		}
		Text(tr("editor.paint.colorCurrent"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted,
			modifier = Modifier.padding(top = 2.dp))
	}
}

/** The channels of [color]: H S B and R G B, three a column, and the hex code under them. */
@Composable
fun PsColorChannels(color: Color, onColorChange: (Color) -> Unit, modifier: Modifier = Modifier) {
	val state = rememberHsb(color)
	val hsb = state.value
	val rgb = color.rgb()
	fun setHsb(next: Hsb) { state.value = next; onColorChange(next.color) }
	fun setRgb(next: Int) = onColorChange(Color(0xFF000000L or (next and 0xFFFFFF).toLong()))
	Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
				ChannelField("H", Math.round(hsb.h * 360f) % 360, 359, "°") { setHsb(hsb.copy(h = it / 360f)) }
				ChannelField("S", Math.round(hsb.s * 100f), 100, "%") { setHsb(hsb.copy(s = it / 100f)) }
				ChannelField("B", Math.round(hsb.b * 100f), 100, "%") { setHsb(hsb.copy(b = it / 100f)) }
			}
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
				ChannelField("R", (rgb shr 16) and 0xFF, 255, "") { setRgb((rgb and 0x00FFFF) or (it shl 16)) }
				ChannelField("G", (rgb shr 8) and 0xFF, 255, "") { setRgb((rgb and 0xFF00FF) or (it shl 8)) }
				ChannelField("B", rgb and 0xFF, 255, "") { setRgb((rgb and 0xFFFF00) or it) }
			}
		}
		HexField(color, onColorChange)
	}
}

/** The hex code of [color], `#` and six digits; a code typed in full sets the colour. */
@Composable
fun HexField(color: Color, onColorChange: (Color) -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text("#", color = colors.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(14.dp))
		CompactTextField(
			value = "%06X".format(color.rgb()),
			onValueChange = { text ->
				val clean = text.trim().removePrefix("#")
				if (clean.length == 6) clean.toIntOrNull(16)?.let { onColorChange(Color(0xFF000000L or it.toLong())) }
			},
			isMono = true,
			selectAllOnFocus = true,
			height = 22.dp,
			modifier = Modifier.weight(1f),
		)
	}
}

@Composable
private fun ChannelField(label: String, value: Int, max: Int, unit: String, onValue: (Int) -> Unit) {
	val colors = LocalToolColors.current
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(label, color = colors.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(14.dp))
		CompactTextField(
			value = "$value",
			onValueChange = { text -> text.trim().toIntOrNull()?.let { onValue(it.coerceIn(0, max)) } },
			isMono = true,
			selectAllOnFocus = true,
			height = 20.dp,
			modifier = Modifier.weight(1f),
		)
		Text(unit, color = colors.textMuted, fontSize = 10.sp, modifier = Modifier.width(12.dp).padding(start = 2.dp))
	}
}

/** A row of colour chips; the one equal to [selected] is ringed in the accent. */
@Composable
fun PaintSwatchRow(swatches: List<Color>, selected: Color?, onPick: (Color) -> Unit, modifier: Modifier = Modifier, chip: Dp = 16.dp) {
	val colors = LocalToolColors.current
	Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
		for (swatch in swatches) {
			val chosen = selected != null && swatch.rgb() == selected.rgb()
			Box(
				Modifier.size(chip).clip(RoundedCornerShape(2.dp)).background(swatch)
					.border(if (chosen) 1.5.dp else 0.5.dp, if (chosen) colors.accent else colors.border, RoundedCornerShape(2.dp))
					.clickable { onPick(swatch) }
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			)
		}
	}
}

/** The paint swatches every paint colour control offers. */
val PAINT_SWATCHES = listOf(
	Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF7F7F7F), Color(0xFFC3C3C3), Color(0xFF482C32), Color(0xFF8A454E),
	Color(0xFFFFDFC4), Color(0xFFED1C24), Color(0xFFFF7F27), Color(0xFFFFF200), Color(0xFF22B14C), Color(0xFF00A2E8),
	Color(0xFF3F48CC), Color(0xFFA349A4), Color(0xFFFFAEC9), Color(0xFFB97A57),
)

/**
 * Photoshop's colour picker in a popup: the field and hue strip, the new colour over the one it opened with, the
 * channels, the swatches and the colours used last. Every change goes to [onColorChanged] at once; the colour is
 * remembered among the recent ones when the popup closes.
 */
@Composable
fun PsColorPickerPopupContent(
	initial: Color,
	onColorChanged: (Color) -> Unit,
	onDismiss: () -> Unit,
	title: String = tr("mouth.colorPicker"),
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val original = remember { initial.copy(alpha = 1f) }
	var current by remember { mutableStateOf(original) }
	fun pick(color: Color) { current = color; onColorChanged(color) }
	DisposableEffect(Unit) { onDispose { if (current.rgb() != original.rgb()) RecentPaintColors.push(current) } }

	Surface(color = colors.panelElevated, border = BorderStroke(1.dp, colors.border), shape = RoundedCornerShape(6.dp), elevation = 8.dp) {
		Column(Modifier.width(300.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Text(title, style = typography.title.copy(fontSize = 12.sp), color = colors.textPrimary, modifier = Modifier.weight(1f))
				CompactIconButton(onClick = onDismiss, size = 18.dp) { IconClose(Modifier.size(9.dp), tint = colors.textMuted) }
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				PsColorField(current, ::pick, Modifier.weight(1f), height = 168.dp)
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					PsColorCompare(current, original, onRevert = { pick(original) })
				}
			}
			PsColorChannels(current, ::pick)
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(tr("editor.paint.swatches"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
				PaintSwatchRow(PAINT_SWATCHES.take(8), current, ::pick, chip = 18.dp)
				PaintSwatchRow(PAINT_SWATCHES.drop(8), current, ::pick, chip = 18.dp)
				val recent = RecentPaintColors.colors.take(12)
				if (recent.isNotEmpty()) {
					Spacer(Modifier.height(2.dp))
					Text(tr("editor.paint.recentColors"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
					PaintSwatchRow(recent, current, ::pick, chip = 18.dp)
				}
			}
		}
	}
}
