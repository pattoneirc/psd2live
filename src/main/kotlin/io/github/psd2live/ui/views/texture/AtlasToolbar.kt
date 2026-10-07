package io.github.psd2live.ui.views.texture

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass

/*
 * The atlas page's floating controls, in the edit canvas's language: a frosted bar like its mode bar, an accent
 * button like its mode button, chips like its deform-level chips and a menu that scales in like its mode menu.
 */

/** A frosted bar of controls floating over a canvas; it lifts while hovered, as the edit canvas's mode bar does. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FloatingBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
	val interactionSource = remember { MutableInteractionSource() }
	val hoveredBySource by interactionSource.collectIsHoveredAsState()
	var hoveredByEvent by remember { mutableStateOf(false) }
	val hovered = hoveredBySource || hoveredByEvent
	val elevation by animateDpAsState(if (hovered) 8.dp else 2.dp, tween(200))
	Row(
		modifier
			.frostedGlass(RoundedCornerShape(6.dp), isHovered = hovered, elevation = elevation, alpha = if (hovered) 0.88f else 0.78f)
			.hoverable(interactionSource)
			.onPointerEvent(PointerEventType.Enter) { hoveredByEvent = true }
			.onPointerEvent(PointerEventType.Exit) { hoveredByEvent = false }
			.padding(horizontal = 4.dp, vertical = 3.dp)
			.animateContentSize(tween(200, easing = FastOutSlowInEasing)),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(3.dp),
		content = content,
	)
}

/** A short vertical rule between groups of a [FloatingBar]. */
@Composable
internal fun BarDivider() {
	Box(Modifier.padding(horizontal = 2.dp).height(14.dp).width(1.dp).background(LocalToolColors.current.border.copy(alpha = 0.45f)))
}

/** A hover tooltip in the canvas bars' style. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BarTooltip(text: String?, content: @Composable () -> Unit) {
	if (text.isNullOrBlank()) { content(); return }
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	TooltipArea(
		tooltip = {
			Surface(color = colors.panelElevated, shape = RoundedCornerShape(4.dp), border = BorderStroke(0.5.dp, colors.border), elevation = 4.dp) {
				Text(text, style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textPrimary,
					modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 7.dp, vertical = 4.dp))
			}
		},
		delayMillis = 450,
	) { content() }
}

/**
 * A chip of a [FloatingBar]: an optional icon and a label, accent-filled while [selected] - the edit canvas's
 * deform-level chip. [chevron] marks one that opens a menu.
 */
@Composable
internal fun BarChip(
	label: String?,
	selected: Boolean,
	onClick: () -> Unit,
	enabled: Boolean = true,
	tooltip: String? = null,
	chevron: Boolean = false,
	open: Boolean = false,
	icon: (@Composable (Color) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val background by animateColorAsState(when {
		selected -> colors.accent.copy(alpha = 0.22f)
		open || hovered && enabled -> colors.controlHover.copy(alpha = 0.7f)
		else -> Color.Transparent
	}, tween(80))
	val tint by animateColorAsState(when {
		!enabled -> colors.textMuted.copy(alpha = 0.5f)
		selected -> colors.accent
		hovered || open -> colors.textPrimary
		else -> colors.textMuted
	}, tween(80))
	BarTooltip(tooltip) {
		Row(
			Modifier
				.height(24.dp)
				.defaultMinSize(minWidth = 24.dp)
				.clip(RoundedCornerShape(4.dp))
				.background(background)
				.border(0.5.dp, if (selected) colors.accent.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(4.dp))
				.hoverable(interactionSource)
				.clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
				.semantics { contentDescription = label ?: tooltip ?: "" }
				.padding(horizontal = if (label == null && !chevron) 5.dp else 7.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			if (icon != null) icon(tint)
			if (icon != null && label != null) Spacer(Modifier.width(5.dp))
			if (label != null) Text(label, color = tint, fontSize = 11.sp, maxLines = 1,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
			if (chevron) { Spacer(Modifier.width(4.dp)); Chevron(tint, open) }
		}
	}
}

/** The menu chevron of the mode button: it turns over while its menu is open. */
@Composable
internal fun Chevron(tint: Color, open: Boolean) {
	val turn by animateFloatAsState(if (open) 180f else 0f, tween(100, easing = FastOutSlowInEasing))
	Canvas(Modifier.size(10.dp).rotate(turn)) {
		val w = size.width; val h = size.height
		drawLine(tint, Offset(w * 0.2f, h * 0.38f), Offset(w * 0.5f, h * 0.66f), 1.4f, StrokeCap.Round)
		drawLine(tint, Offset(w * 0.5f, h * 0.66f), Offset(w * 0.8f, h * 0.38f), 1.4f, StrokeCap.Round)
	}
}

/**
 * The bar's primary action, shaped like the edit canvas's mode button: an accent body that runs [onClick] and a
 * chevron that opens its options. [onMenu] toggles the options; [open] says they show.
 */
@Composable
internal fun AccentSplitButton(
	label: String,
	icon: @Composable (Color) -> Unit,
	onClick: () -> Unit,
	open: Boolean,
	onMenu: () -> Unit,
	enabled: Boolean = true,
	tooltip: String? = null,
	menuTooltip: String? = null,
) {
	val colors = LocalToolColors.current
	val body = remember { MutableInteractionSource() }
	val menu = remember { MutableInteractionSource() }
	val bodyHovered by body.collectIsHoveredAsState()
	val menuHovered by menu.collectIsHoveredAsState()
	val tint = if (enabled) colors.accent else colors.textMuted.copy(alpha = 0.6f)
	fun fill(hovered: Boolean, pressed: Boolean) = when {
		!enabled -> colors.accent.copy(alpha = 0.08f)
		pressed -> colors.accent.copy(alpha = 0.3f)
		hovered -> colors.accent.copy(alpha = 0.26f)
		else -> colors.accent.copy(alpha = 0.18f)
	}
	val bodyFill by animateColorAsState(fill(bodyHovered, false), tween(80))
	val menuFill by animateColorAsState(fill(menuHovered, open), tween(80))
	Row(
		Modifier
			.height(24.dp)
			.clip(RoundedCornerShape(4.dp))
			.border(0.5.dp, colors.accent.copy(alpha = if (open) 0.75f else if (enabled) 0.5f else 0.2f), RoundedCornerShape(4.dp)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		BarTooltip(tooltip) {
			Row(
				Modifier
					.height(24.dp)
					.background(bodyFill)
					.hoverable(body)
					.clickable(interactionSource = body, indication = null, enabled = enabled, onClick = onClick)
					.semantics { contentDescription = label }
					.padding(start = 6.dp, end = 7.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				icon(tint)
				Spacer(Modifier.width(5.dp))
				Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
			}
		}
		Box(Modifier.width(0.5.dp).height(24.dp).background(colors.accent.copy(alpha = if (enabled) 0.4f else 0.15f)))
		BarTooltip(menuTooltip) {
			Box(
				Modifier
					.height(24.dp)
					.background(menuFill)
					.hoverable(menu)
					.clickable(interactionSource = menu, indication = null, enabled = enabled, onClick = onMenu)
					.semantics { contentDescription = menuTooltip ?: label }
					.padding(horizontal = 5.dp),
				contentAlignment = Alignment.Center,
			) { Chevron(tint, open) }
		}
	}
}

/** The bar's one-click primary action, accent-filled like [AccentSplitButton]'s body. */
@Composable
internal fun AccentButton(label: String, onClick: () -> Unit, enabled: Boolean = true, tooltip: String? = null) {
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val tint = if (enabled) colors.accent else colors.textMuted.copy(alpha = 0.6f)
	val fill by animateColorAsState(when {
		!enabled -> colors.accent.copy(alpha = 0.08f)
		hovered -> colors.accent.copy(alpha = 0.3f)
		else -> colors.accent.copy(alpha = 0.2f)
	}, tween(80))
	BarTooltip(tooltip) {
		Box(
			Modifier
				.height(24.dp)
				.clip(RoundedCornerShape(4.dp))
				.background(fill)
				.border(0.5.dp, colors.accent.copy(alpha = if (enabled) 0.55f else 0.2f), RoundedCornerShape(4.dp))
				.hoverable(interaction)
				.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
				.semantics { contentDescription = label }
				.padding(horizontal = 10.dp),
			contentAlignment = Alignment.Center,
		) { Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
	}
}

/**
 * A menu dropping from a bar control, in the mode menu's style: frosted, scaling in from its anchor's corner.
 * Put it in the anchor's Box; [alignment] TopStart drops it under the anchor's left edge, TopEnd under its right.
 */
@Composable
internal fun FloatingMenu(
	expanded: Boolean,
	onDismiss: () -> Unit,
	width: Dp = 220.dp,
	alignment: Alignment = Alignment.TopStart,
	content: @Composable ColumnScope.() -> Unit,
) {
	val visibility = remember { MutableTransitionState(false) }
	visibility.targetState = expanded
	if (!visibility.currentState && !visibility.targetState) return
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val density = LocalDensity.current
	Popup(
		alignment = alignment,
		offset = with(density) { IntOffset(0, 28.dp.roundToPx()) },
		onDismissRequest = onDismiss,
		properties = PopupProperties(focusable = true),
	) {
		CompositionLocalProvider(LocalDensity provides density, LocalToolColors provides colors, LocalToolTypography provides typography) {
			val transition = rememberTransition(visibility, "FloatingMenu")
			val alpha by transition.animateFloat({
				if (false isTransitioningTo true) tween(120, easing = LinearOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
			}, "alpha") { if (it) 1f else 0f }
			val scale by transition.animateFloat({
				if (false isTransitioningTo true) tween(100, easing = LinearOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
			}, "scale") { if (it) 1f else 0.92f }
			val lift by transition.animateFloat({
				if (false isTransitioningTo true) tween(100, easing = FastOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
			}, "lift") { if (it) 0f else -6f }
			Column(
				Modifier
					.graphicsLayer {
						this.alpha = alpha; scaleX = scale; scaleY = scale
						translationY = lift * density.density
						transformOrigin = TransformOrigin(if (alignment == Alignment.TopEnd) 0.88f else 0.12f, 0f)
					}
					.width(width)
					.frostedGlass(RoundedCornerShape(7.dp), isHovered = true, elevation = 12.dp, baseColor = colors.panelElevated, alpha = 0.9f)
					.border(0.5.dp, colors.borderHover.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
					.padding(4.dp),
				verticalArrangement = Arrangement.spacedBy(1.dp),
				content = content,
			)
		}
	}
}

/** A caption over a group of [FloatingMenu] rows. */
@Composable
internal fun FloatingMenuSection(text: String) {
	Text(text.uppercase(), color = LocalToolColors.current.textMuted.copy(alpha = 0.8f), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
		letterSpacing = 0.4.sp, modifier = Modifier.padding(start = 8.dp, top = 5.dp, bottom = 2.dp))
}

/** A rule between groups of [FloatingMenu] rows. */
@Composable
internal fun FloatingMenuDivider() {
	Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).height(0.5.dp).background(LocalToolColors.current.border.copy(alpha = 0.5f)))
}

/**
 * A row of a [FloatingMenu], like the mode menu's: a bar at its edge while [selected] (unless [bar] is off, for
 * rows whose [control] shows the state), an icon, a label and an optional hint under it, a trailing note or control.
 */
@Composable
internal fun FloatingMenuRow(
	label: String,
	onClick: () -> Unit,
	selected: Boolean = false,
	enabled: Boolean = true,
	hint: String? = null,
	trailing: String? = null,
	bar: Boolean = true,
	icon: (@Composable (Color) -> Unit)? = null,
	control: (@Composable () -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val tint by animateColorAsState(when {
		!enabled -> colors.textMuted.copy(alpha = 0.5f)
		selected -> colors.accent
		hovered -> colors.textPrimary
		else -> colors.textMuted
	}, tween(80))
	val background by animateColorAsState(if (hovered && enabled) colors.controlHover.copy(alpha = 0.75f) else Color.Transparent, tween(80))
	val edge by animateFloatAsState(if (selected && bar) 1f else 0f, tween(80, easing = FastOutSlowInEasing))
	Row(
		Modifier
			.fillMaxWidth()
			.defaultMinSize(minHeight = 28.dp)
			.clip(RoundedCornerShape(5.dp))
			.background(background)
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
			.semantics { contentDescription = label }
			.padding(horizontal = 6.dp, vertical = 3.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(2.dp).height(14.dp * edge).clip(CircleShape).background(colors.accent.copy(alpha = edge)))
		Spacer(Modifier.width(5.dp))
		if (icon != null) {
			Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon(tint) }
			Spacer(Modifier.width(7.dp))
		} else Spacer(Modifier.width(2.dp))
		Column(Modifier.weight(1f)) {
			Text(label, color = if (enabled && (selected || hovered)) colors.textPrimary else tint, fontSize = 11.5.sp,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (hint != null) Text(hint, color = colors.textMuted.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
		if (trailing != null) Text(trailing, color = colors.textMuted.copy(alpha = 0.7f), fontSize = 9.5.sp, maxLines = 1,
			modifier = Modifier.padding(start = 6.dp))
		if (control != null) Box(Modifier.padding(start = 8.dp, end = 2.dp)) { control() }
	}
}

/** One choice of a group in a [FloatingMenu]: a radio mark at the end, filled while [selected]. */
@Composable
internal fun FloatingMenuRadio(label: String, selected: Boolean, onSelect: () -> Unit, enabled: Boolean = true, hint: String? = null,
                               icon: (@Composable (Color) -> Unit)? = null) {
	FloatingMenuRow(label, onSelect, selected = selected, enabled = enabled, hint = hint, bar = false, icon = icon,
		control = { RadioMark(selected, enabled) })
}

/** An on/off setting in a [FloatingMenu]: the label and hint, and a switch at the end that slides on while [checked]. */
@Composable
internal fun FloatingMenuSwitch(label: String, checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true, hint: String? = null) {
	FloatingMenuRow(label, onToggle, enabled = enabled, hint = hint, bar = false, control = { SwitchTrack(checked, enabled) })
}

/** A radio mark: a ring, with an accent dot while [selected]. */
@Composable
internal fun RadioMark(selected: Boolean, enabled: Boolean = true) {
	val colors = LocalToolColors.current
	val dot by animateFloatAsState(if (selected) 1f else 0f, tween(120, easing = FastOutSlowInEasing))
	val ring = if (selected) colors.accent else colors.textMuted.copy(alpha = 0.8f)
	Canvas(Modifier.size(12.dp).alpha(if (enabled) 1f else 0.5f)) {
		val r = size.minDimension / 2f
		drawCircle(ring, r - 0.75f, style = Stroke(1.3f))
		if (dot > 0f) drawCircle(colors.accent, (r - 3f) * dot)
	}
}

/** An on/off switch: a track whose knob slides to the end, and which fills with the accent, while [checked]. */
@Composable
internal fun SwitchTrack(checked: Boolean, enabled: Boolean = true) {
	val colors = LocalToolColors.current
	val knob by animateFloatAsState(if (checked) 1f else 0f, tween(140, easing = FastOutSlowInEasing))
	val track by animateColorAsState(if (checked) colors.accent.copy(alpha = if (enabled) 0.9f else 0.4f) else colors.border.copy(alpha = 0.9f), tween(140))
	Canvas(Modifier.width(24.dp).height(13.dp).alpha(if (enabled) 1f else 0.6f)) {
		val r = size.height / 2f
		drawRoundRect(track, cornerRadius = CornerRadius(r))
		drawCircle(Color.White.copy(alpha = 0.95f), r - 2f, Offset(r + (size.width - r * 2) * knob, r))
	}
}

/**
 * A switch of a [FloatingBar] for a mode: the track and its label, the label in the accent while [checked]. Unlike a
 * [BarChip] it reads as a setting that stays on, not a button that runs something.
 */
@Composable
internal fun BarSwitch(label: String, checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true, tooltip: String? = null) {
	val colors = LocalToolColors.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val background by animateColorAsState(if (hovered && enabled) colors.controlHover.copy(alpha = 0.7f) else Color.Transparent, tween(80))
	val tint by animateColorAsState(when {
		!enabled -> colors.textMuted.copy(alpha = 0.5f)
		checked -> colors.accent
		hovered -> colors.textPrimary
		else -> colors.textMuted
	}, tween(80))
	BarTooltip(tooltip) {
		Row(
			Modifier
				.height(24.dp)
				.clip(RoundedCornerShape(4.dp))
				.background(background)
				.hoverable(interactionSource)
				.clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onToggle)
				.semantics { contentDescription = label }
				.padding(horizontal = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SwitchTrack(checked, enabled)
			Spacer(Modifier.width(6.dp))
			Text(label, color = tint, fontSize = 11.sp, maxLines = 1, fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium)
		}
	}
}

/** The notices of the last command and the layout, one per line, in a frosted banner the user can close. */
@Composable
internal fun NoticeBanner(messages: List<Pair<String, Color>>, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val accent = messages.firstOrNull()?.second ?: colors.warning
	Row(
		modifier
			.widthIn(max = 480.dp)
			.frostedGlass(RoundedCornerShape(6.dp), elevation = 4.dp, alpha = 0.9f)
			.border(0.5.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
			.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.Top,
	) {
		Canvas(Modifier.padding(top = 1.dp).size(14.dp)) {
			// A warning triangle with its mark.
			val w = size.width; val h = size.height
			val path = androidx.compose.ui.graphics.Path().apply { moveTo(w / 2f, 1f); lineTo(w - 1f, h - 1.5f); lineTo(1f, h - 1.5f); close() }
			drawPath(path, accent.copy(alpha = 0.2f)); drawPath(path, accent, style = Stroke(1.2f))
			drawLine(accent, Offset(w / 2f, h * 0.38f), Offset(w / 2f, h * 0.62f), 1.4f, StrokeCap.Round)
			drawCircle(accent, 0.9f, Offset(w / 2f, h * 0.76f))
		}
		Column(Modifier.weight(1f, fill = false).padding(start = 6.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			for ((message, color) in messages) {
				Text(message, style = typography.caption.copy(fontSize = 10.5.sp), color = if (color == colors.error) color else colors.textPrimary,
					maxLines = 3, overflow = TextOverflow.Ellipsis)
			}
		}
		if (onClose != null) Box(Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onClose).padding(2.dp)) { IconClose(tint = colors.textMuted) }
	}
}

/** Arranged by mesh shape: two triangles nested in one square. */
@Composable
internal fun IconArrangeMesh(tint: Color, modifier: Modifier = Modifier) {
	Canvas(modifier.size(14.dp)) {
		val s = size.width
		val a = androidx.compose.ui.graphics.Path().apply { moveTo(1f, 1f); lineTo(1f, s - 1f); lineTo(s - 2.5f, s - 1f); close() }
		val b = androidx.compose.ui.graphics.Path().apply { moveTo(2.5f, 1f); lineTo(s - 1f, 1f); lineTo(s - 1f, s - 2.5f); close() }
		drawPath(a, tint); drawPath(b, tint.copy(alpha = 0.5f))
	}
}

/** Arranged by rectangle: two rectangles side by side. */
@Composable
internal fun IconArrangeRect(tint: Color, modifier: Modifier = Modifier) {
	Canvas(modifier.size(14.dp)) {
		val s = size.width
		drawRect(tint, Offset(1f, 2f), Size(s * 0.5f - 1.5f, s - 4f))
		drawRect(tint.copy(alpha = 0.5f), Offset(s * 0.5f + 0.5f, 2f), Size(s * 0.5f - 1.5f, s * 0.55f))
	}
}

/** Only the selection: a dashed frame round one tile. */
@Composable
internal fun IconArrangeSelection(tint: Color, modifier: Modifier = Modifier) {
	Canvas(modifier.size(14.dp)) {
		val s = size.width
		drawRect(tint, Offset(1f, 1f), Size(s - 2f, s - 2f),
			style = Stroke(1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 2f))))
		drawRect(tint, Offset(s * 0.3f, s * 0.3f), Size(s * 0.4f, s * 0.4f))
	}
}

/** The atlas budget: a stack of pages. */
@Composable
internal fun IconAtlasPages(tint: Color, modifier: Modifier = Modifier) {
	Canvas(modifier.size(14.dp)) {
		val s = size.width
		drawRect(tint.copy(alpha = 0.5f), Offset(3.5f, 0.5f), Size(s - 4f, s - 4f), style = Stroke(1f))
		drawRect(tint, Offset(0.5f, 3.5f), Size(s - 4f, s - 4f), style = Stroke(1.1f))
		drawLine(tint, Offset(0.5f, s * 0.6f), Offset(s - 3.5f, s * 0.6f), 0.8f)
		drawLine(tint, Offset(s * 0.45f, 3.5f), Offset(s * 0.45f, s - 0.5f), 0.8f)
	}
}
