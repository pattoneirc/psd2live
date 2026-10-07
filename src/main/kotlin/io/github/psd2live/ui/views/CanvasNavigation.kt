package io.github.psd2live.ui.views

import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import io.github.psd2live.ui.state.ShortcutAction
import kotlin.math.pow

/**
 * The navigation and selection language every zoomable editing view shares with the edit canvas, so the
 * atlas page answers the mouse and keys the way the canvas does:
 *
 * - the wheel zooms about the pointer, one notch by [WHEEL_STEP], between [MIN_ZOOM] and [MAX_ZOOM];
 * - the pan gesture drags the view (ShortcutAction.PAN_VIEW, the middle button by default), and so does
 *   the primary button while Space is held; the zoom gesture (ShortcutAction.ZOOM_DRAG) zooms as it moves;
 * - the right button opens the view's context menu;
 * - a click or a box selects, Shift adds to the selection and Alt takes away from it;
 * - the camera shortcuts frame the selection and reset the view (ShortcutAction.FRAME_VIEW / RESET_CAMERA).
 */
internal object CanvasNavigation {
	const val WHEEL_STEP = 1.15
	const val MIN_ZOOM = 0.05
	const val MAX_ZOOM = 64.0

	/** [zoom] after the wheel turned by [wheelDelta] (negative zooms in). */
	fun wheelZoom(zoom: Double, wheelDelta: Float): Double = (zoom * WHEEL_STEP.pow(-wheelDelta.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)

	/**
	 * Whether a press drags the view: one that starts the pan [gesture] (Keymap.gesture of the press), or the
	 * primary button with Space held.
	 */
	fun pans(gesture: ShortcutAction?, button: PointerButton?, spaceHeld: Boolean): Boolean =
		gesture == ShortcutAction.PAN_VIEW || (button == PointerButton.Primary && spaceHeld)

	enum class SelectMode { REPLACE, ADD, SUBTRACT }

	/** How a click or a box combines with the selection: Alt subtracts, Shift adds, otherwise it replaces. */
	fun selectMode(modifiers: PointerKeyboardModifiers): SelectMode = when {
		modifiers.isAltPressed -> SelectMode.SUBTRACT
		modifiers.isShiftPressed -> SelectMode.ADD
		else -> SelectMode.REPLACE
	}
}
