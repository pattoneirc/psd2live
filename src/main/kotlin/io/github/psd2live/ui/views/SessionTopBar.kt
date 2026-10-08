package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.checkMark
import io.github.psd2live.ui.components.cross
import io.github.psd2live.ui.components.undoArrow
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.BarChip
import io.github.psd2live.ui.views.texture.BarDivider
import io.github.psd2live.ui.views.texture.BarTooltip
import io.github.psd2live.ui.views.texture.FloatingBar

/** One button of a [SessionTopBar]: its label, the hint its tooltip adds, and whether it can run. */
internal class SessionAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val hint: String? = null)

/**
 * The bar of an open edit session - the atlas's tile adjustments, a paint session - that drops in from the top of
 * its canvas while there is something to apply: what the session changed, then undo, redo, discard and apply.
 *
 * It sits centred between the canvas's top-left and top-right bars ([startInset], [endInset]); where the labelled
 * buttons do not fit there it shows icons alone, with the labels in their tooltips, and where even those do not fit
 * it takes a row of its own under the bars.
 */
@Composable
internal fun BoxScope.SessionTopBar(
	visible: Boolean,
	summary: String,
	badge: String,
	undo: SessionAction,
	redo: SessionAction,
	discard: SessionAction,
	apply: SessionAction,
	startInset: Dp = 0.dp,
	endInset: Dp = 0.dp,
	top: Dp = 8.dp,
) {
	// The bar keeps saying what it said while it leaves, instead of "0 changed" on its way out.
	val said = remember { arrayOf(summary, badge) }
	if (visible) { said[0] = summary; said[1] = badge }
	AnimatedVisibility(
		visible = visible,
		modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
		enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(160)),
		exit = slideOutVertically(tween(160, easing = FastOutLinearInEasing)) { -it } + fadeOut(tween(120)),
	) {
		SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
			val loose = Constraints(maxHeight = constraints.maxHeight)
			val margin = 8.dp.roundToPx()
			val start = startInset.roundToPx() + margin
			val room = constraints.maxWidth - start - endInset.roundToPx() - margin
			val full = subcompose(false) { SessionBarContent(false, said[0], said[1], undo, redo, discard, apply) }.map { it.measure(loose) }
			val chosen = if (full.maxOf { it.width } <= room) full
				else subcompose(true) { SessionBarContent(true, said[0], said[1], undo, redo, discard, apply) }.map { it.measure(loose) }
			val width = chosen.maxOf { it.width }
			val height = chosen.maxOf { it.height }
			val fits = width <= room
			// Between the bars where it fits; otherwise centred on a row of its own under them.
			val x = if (fits) start + (room - width) / 2 else ((constraints.maxWidth - width) / 2).coerceAtLeast(0)
			val y = top.roundToPx() + if (fits) 0 else height + 6.dp.roundToPx()
			layout(constraints.maxWidth, y + height) { chosen.forEach { it.place(x, y) } }
		}
	}
}

@Composable
private fun SessionBarContent(compact: Boolean, summary: String, badge: String, undo: SessionAction, redo: SessionAction,
                              discard: SessionAction, apply: SessionAction) {
	val colors = LocalToolColors.current
	FloatingBar {
		BarTooltip(if (compact) summary else null) {
			androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(start = 6.dp, end = 4.dp)) {
				PendingDot(colors.highlight)
				Text(if (compact) badge else summary, color = colors.textPrimary, fontSize = 11.sp, maxLines = 1,
					fontWeight = FontWeight.Medium, overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(start = 6.dp).widthIn(max = 220.dp))
			}
		}
		BarDivider()
		SessionChip(undo, compact) { IconSessionUndo(it) }
		SessionChip(redo, compact) { IconSessionRedo(it) }
		BarDivider()
		SessionChip(discard, compact) { IconSessionDiscard(it) }
		AccentButton(if (compact) null else apply.label, apply.onClick, enabled = apply.enabled,
			tooltip = tooltip(apply, compact), icon = { IconSessionApply(it) })
	}
}

@Composable
private fun SessionChip(action: SessionAction, compact: Boolean, icon: @Composable (Color) -> Unit) {
	BarChip(if (compact) null else action.label, false, action.onClick, enabled = action.enabled, tooltip = tooltip(action, compact), icon = icon)
}

/** With the label gone, the tooltip says it before the hint. */
private fun tooltip(action: SessionAction, compact: Boolean): String? = when {
	!compact -> action.hint
	action.hint == null -> action.label
	else -> "${action.label} - ${action.hint}"
}

/** A dot that breathes while the session holds changes not yet applied. */
@Composable
private fun PendingDot(color: Color) {
	val breath by rememberInfiniteTransition("pending").animateFloat(0.45f, 1f,
		infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), "breath")
	Canvas(Modifier.size(7.dp)) {
		drawCircle(color.copy(alpha = 0.25f * breath), size.minDimension / 2f)
		drawCircle(color.copy(alpha = 0.6f + 0.4f * breath), size.minDimension / 2f - 1.5f)
	}
}

/** Undo: the app's one undo arrow. */
@Composable
internal fun IconSessionUndo(tint: Color, modifier: Modifier = Modifier) = GridIcon(modifier.size(14.dp), tint) { undoArrow() }

/** Redo: [IconSessionUndo] mirrored. */
@Composable
internal fun IconSessionRedo(tint: Color, modifier: Modifier = Modifier) =
	GridIcon(modifier.size(14.dp), tint) { mirrored { undoArrow() } }

/** Discard: a cross in a ring. */
@Composable
internal fun IconSessionDiscard(tint: Color, modifier: Modifier = Modifier) = GridIcon(modifier.size(14.dp), tint) {
	ring(9f, 9f, 7.2f)
	cross(reach = 2.9f)
}

/** Apply: a check mark. */
@Composable
internal fun IconSessionApply(tint: Color, modifier: Modifier = Modifier) = GridIcon(modifier.size(14.dp), tint) { checkMark() }
