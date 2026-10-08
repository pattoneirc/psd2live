package io.github.psd2live.ui.views

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass
import io.github.psd2live.ui.tooloptions.menuOptions
import io.github.psd2live.ui.views.tooloptions.ToolOptionMenu
import kotlin.math.roundToInt

/** Whether a right press on [editor]'s canvas has a menu to open: the tool in hand has options or actions for it. */
internal fun canvasContextMenuHasContent(editor: CanvasEditor): Boolean = menuOptions(editor).isNotEmpty()

/**
 * The canvas context menu, opened where the right press landed: the tool in hand's actions and its most used
 * values - the same options the tool options bar shows, from the same definitions (see [menuOptions]). It moves
 * the way the mode menu does: fading in, scaling up from the press and rising the last few pixels into place,
 * its rows arriving one after another.
 */
@Composable
internal fun CanvasContextMenu(
    editor: CanvasEditor,
    expanded: Boolean,
    clickOffset: Offset,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit = {},
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState) return
    if (!expanded && !canvasContextMenuHasContent(editor)) return

    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val density = LocalDensity.current
    val provider = remember(clickOffset, density) { PressPositionProvider(clickOffset, with(density) { 8.dp.roundToPx() }) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        CompositionLocalProvider(LocalDensity provides density, LocalToolColors provides colors, LocalToolTypography provides typography) {
            val transition = rememberTransition(visibility, "CanvasContextMenu")
            val alpha by transition.animateFloat({
                if (false isTransitioningTo true) tween(120, easing = LinearOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
            }, "alpha") { if (it) 1f else 0f }
            val scale by transition.animateFloat({
                if (false isTransitioningTo true) tween(100, easing = LinearOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
            }, "scale") { if (it) 1f else 0.92f }
            val lift by transition.animateFloat({
                if (false isTransitioningTo true) tween(100, easing = FastOutSlowInEasing) else tween(80, easing = FastOutLinearInEasing)
            }, "lift") { if (it) 0f else -6f }
            Box(
                Modifier
                    .graphicsLayer {
                        this.alpha = alpha; scaleX = scale; scaleY = scale
                        translationY = lift * density.density
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .frostedGlass(RoundedCornerShape(7.dp), isHovered = true, elevation = 12.dp, baseColor = colors.panelElevated, alpha = 0.9f)
                    .border(0.5.dp, colors.borderHover.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
            ) {
                ToolOptionMenu(editor, onDismiss = onDismissRequest, onAction = onAction)
            }
        }
    }
}

/** Opens a popup at the press, flipping to the left of it or above it where the window runs out. */
private class PressPositionProvider(private val press: Offset, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x0 = anchorBounds.left + press.x.roundToInt()
        val y0 = anchorBounds.top + press.y.roundToInt()
        var x = x0 + 2
        if (x + popupContentSize.width > windowSize.width - margin) x = x0 - popupContentSize.width - 2
        var y = y0 + 2
        if (y + popupContentSize.height > windowSize.height - margin) y = y0 - popupContentSize.height - 2
        return IntOffset(
            x.coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin)),
            y.coerceIn(margin, maxOf(margin, windowSize.height - popupContentSize.height - margin)),
        )
    }
}
