package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.AppPrompt
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/** What a modal reports; sets the badge before the title and, for an error, the frame's border. */
enum class ModalTone { NONE, SUCCESS, WARNING, ERROR }

/**
 * The frame every in-window modal shares: the scrim, a panel with one radius, border and shadow, a title row
 * with an optional tone badge and the close button, a scrolling body and a footer row of actions under a divider.
 *
 * [dismissible] false (while the dialog's work runs) disables the scrim, the close button and Escape together.
 * Escape closes, Enter runs [onConfirm] when given, and [onKeyDown] sees every key first (a dialog's own
 * shortcut, as the root dispatcher no longer does once the panel has focus); the panel takes focus when it opens.
 * [fit] sizes the panel from the room the window leaves (a large dialog that grows with it) instead of [width]
 * and [maxHeight]; [titleTrailing] sits after the title (a version, a status chip). A dialog with its own layout,
 * a sidebar say, turns [scrollable] off and zeroes [bodyPadding].
 * [footerStart] sits at the footer's left (a "don't show again" box, a reset), [footer] holds the actions at its
 * right, primary last.
 */
@Composable
internal fun ModalDialogFrame(
	width: Dp = 440.dp,
	maxHeight: Dp = 720.dp,
	fit: ((maxWidth: Dp, maxHeight: Dp) -> DpSize)? = null,
	title: String,
	onDismiss: () -> Unit,
	dismissible: Boolean = true,
	tone: ModalTone = ModalTone.NONE,
	subtitle: String? = null,
	onConfirm: (() -> Unit)? = null,
	onKeyDown: ((KeyEvent) -> Boolean)? = null,
	scrollable: Boolean = true,
	scrollState: ScrollState? = null,
	headerDivider: Boolean = false,
	bodyPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 14.dp),
	bodySpacing: Dp = 10.dp,
	titleTrailing: (@Composable RowScope.() -> Unit)? = null,
	footerStart: (@Composable RowScope.() -> Unit)? = null,
	footer: (@Composable RowScope.() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	Box(
		Modifier.fillMaxSize().background(colors.scrim).scrimDismiss(enabled = dismissible, onDismiss = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
			val fitted = fit?.invoke(this.maxWidth, this.maxHeight)
			ModalPanel(
				modifier = if (fitted != null) Modifier.size(fitted) else
					Modifier.width(width).heightIn(max = minOf(maxHeight, this.maxHeight)),
				fillBody = fitted != null,
				title = title, onDismiss = onDismiss, dismissible = dismissible, tone = tone, subtitle = subtitle,
				onConfirm = onConfirm, onKeyDown = onKeyDown, scrollable = scrollable, scrollState = scrollState,
				headerDivider = headerDivider, bodyPadding = bodyPadding, bodySpacing = bodySpacing,
				titleTrailing = titleTrailing, footerStart = footerStart, footer = footer, content = content,
			)
		}
	}
}

/**
 * The panel of [ModalDialogFrame] without its scrim, for a dialog that brings its own window (a Compose
 * [androidx.compose.ui.window.Dialog]); [modifier] sizes it, and [fillBody] stretches the body to a fixed height.
 */
@Composable
internal fun ModalPanel(
	modifier: Modifier = Modifier,
	fillBody: Boolean = false,
	title: String,
	onDismiss: () -> Unit,
	dismissible: Boolean = true,
	tone: ModalTone = ModalTone.NONE,
	subtitle: String? = null,
	onConfirm: (() -> Unit)? = null,
	onKeyDown: ((KeyEvent) -> Boolean)? = null,
	scrollable: Boolean = true,
	scrollState: ScrollState? = null,
	headerDivider: Boolean = false,
	bodyPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 14.dp),
	bodySpacing: Dp = 10.dp,
	titleTrailing: (@Composable RowScope.() -> Unit)? = null,
	footerStart: (@Composable RowScope.() -> Unit)? = null,
	footer: (@Composable RowScope.() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val shape = RoundedCornerShape(8.dp)
	val focus = remember { FocusRequester() }
	LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

	Column(
		modifier
			.shadow(18.dp, shape)
			.clip(shape)
			.background(colors.panelBackground)
			.border(BorderStroke(1.dp, if (tone == ModalTone.ERROR) colors.error.copy(alpha = 0.7f) else colors.border), shape)
			.clickable(enabled = false) {}
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
				if (onKeyDown?.invoke(event) == true) return@onPreviewKeyEvent true
				when (event.key) {
					Key.Escape -> { if (dismissible) onDismiss(); true }
					Key.Enter, Key.NumPadEnter -> onConfirm?.let { it(); true } ?: false
					else -> false
				}
			}
			.focusRequester(focus)
			.focusable(),
	) {
		Row(
			Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			ModalToneBadge(tone)
			Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(
						title,
						style = typography.title.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
						color = if (tone == ModalTone.ERROR) colors.error else colors.textPrimary,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					if (subtitle != null) Text(
						subtitle, style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted,
						maxLines = 2, overflow = TextOverflow.Ellipsis,
					)
				}
				titleTrailing?.invoke(this)
			}
			ModalCloseButton(onDismiss, enabled = dismissible)
		}
		if (headerDivider) Divider(color = colors.divider, thickness = 1.dp)
		Column(
			Modifier.weight(1f, fill = fillBody).fillMaxWidth()
				.then(if (scrollable) Modifier.verticalScroll(scrollState ?: rememberScrollState()) else Modifier)
				.padding(bodyPadding),
			verticalArrangement = Arrangement.spacedBy(bodySpacing),
			content = content,
		)
		if (footer != null || footerStart != null) {
			Divider(color = colors.divider, thickness = 1.dp)
			Row(
				Modifier.fillMaxWidth().background(colors.panelElevated).padding(horizontal = 16.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				footerStart?.invoke(this)
				Spacer(Modifier.weight(1f))
				footer?.invoke(this)
			}
		}
	}
}

/** The title row's close button: no frame until hovered, so it does not compete with the footer's actions. */
@Composable
private fun ModalCloseButton(onClick: () -> Unit, enabled: Boolean) {
	val colors = LocalToolColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Box(
		Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
			.background(if (hovered && enabled) colors.controlHover else Color.Transparent)
			.hoverable(interaction)
			.clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
			.pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
		contentAlignment = Alignment.Center,
	) {
		IconClose(Modifier.size(10.dp), tint = when {
			!enabled -> colors.textDisabled
			hovered -> colors.textPrimary
			else -> colors.textMuted
		})
	}
}

@Composable
private fun ModalToneBadge(tone: ModalTone) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val color = when (tone) {
		ModalTone.NONE -> return
		ModalTone.SUCCESS -> colors.success
		ModalTone.WARNING -> colors.warning
		ModalTone.ERROR -> colors.error
	}
	Box(Modifier.size(18.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
		if (tone == ModalTone.SUCCESS) IconCheck(Modifier.size(11.dp), tint = color)
		else Text("!", style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = color)
	}
}

/** A message, as in the failure and save-error dialogs; [message] keeps its line breaks. */
@Composable
internal fun ModalMessage(message: String, color: Color = LocalToolColors.current.textPrimary) {
	Text(message, style = LocalToolTypography.current.body.copy(fontSize = 11.5.sp, lineHeight = 17.sp), color = color)
}

/**
 * The "Don't show again" box a prompt carries at its footer's left. Checking it turns [prompt] off at once and
 * unchecking it back on; Settings › Prompts lists it too.
 */
@Composable
internal fun DontShowAgainCheckbox(
	prompt: AppPrompt,
	muted: Boolean,
	onMutedChange: (AppPrompt, Boolean) -> Unit,
	label: String = tr("prompt.dontShowAgain"),
) {
	CompactCheckbox(checked = muted, onCheckedChange = { onMutedChange(prompt, it) }, label = label)
}
