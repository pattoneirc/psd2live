package io.github.psd2live.ui.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import kotlin.test.*

class KeymapMouseBindingTest {
    private fun b(text: String) = assertNotNull(parseKeyBinding(text), text)

    @Test fun mouseBindingsRoundTripThroughTheirTextForm() {
        for (text in listOf("WheelUp", "Alt+WheelDown", "Ctrl+Shift+Alt+WheelLeft", "Shift+WheelRight",
            "MouseMiddle", "Ctrl+MouseMiddle", "Alt+MouseRight", "Shift+Alt+MouseLeft", "MouseBack", "MouseForward")) {
            val binding = b(text)
            assertNotNull(binding.mouse)
            assertNull(binding.key)
            assertEquals(text, binding.format())
        }
        assertEquals(MouseInput.WHEEL_UP, b("Alt+Shift+WheelUp").mouse)
        assertNull(parseKeyBinding("Alt+WheelSideways"))
        assertEquals("NumPadEnter", b("NumPadEnter").format())
    }

    @Test fun aShiftedWheelReadsAsTheVerticalNotchItCameFrom() {
        // Compose turns Shift + wheel into a horizontal scroll.
        assertEquals(MouseInput.WHEEL_UP, MouseInput.wheel(Offset(0f, -1f), shift = false))
        assertEquals(MouseInput.WHEEL_DOWN, MouseInput.wheel(Offset(0f, 1f), shift = false))
        assertEquals(MouseInput.WHEEL_UP, MouseInput.wheel(Offset(-1f, 0f), shift = true))
        assertEquals(MouseInput.WHEEL_DOWN, MouseInput.wheel(Offset(1f, 0f), shift = true))
        assertEquals(MouseInput.WHEEL_LEFT, MouseInput.wheel(Offset(-1f, 0f), shift = false))
        assertEquals(MouseInput.WHEEL_RIGHT, MouseInput.wheel(Offset(1f, 0f), shift = false))
        assertNull(MouseInput.wheel(Offset.Zero, shift = true))

        val altShift = PointerKeyboardModifiers(isAltPressed = true, isShiftPressed = true)
        assertEquals(b("Alt+Shift+WheelDown"), wheelBindingOf(Offset(1f, 0f), altShift))
        assertEquals(b("Alt+Shift+MouseRight"), buttonBindingOf(PointerButton.Secondary, altShift))
        assertEquals(b("MouseBack"), buttonBindingOf(PointerButton.Back, PointerKeyboardModifiers()))
    }

    @Test fun everyPresetIsFreeOfCollisionsAndUsesInputsItsActionsCanTake() {
        for (preset in KeymapPreset.entries) {
            val keymap = Keymap.of(preset)
            for (action in ShortcutAction.entries) {
                for (binding in keymap.bindingsFor(action)) {
                    assertEquals(emptyList(), keymap.conflictsFor(action, binding), "$preset $action ${binding.format()}")
                    val mouse = binding.mouse
                    when (action.kind) {
                        ShortcutKind.DRAG -> assertTrue(mouse != null && !mouse.isWheel, "$preset $action ${binding.format()}")
                        else -> assertTrue(mouse != MouseInput.LEFT && mouse != MouseInput.RIGHT, "$preset $action ${binding.format()}")
                    }
                }
            }
        }
    }

    @Test fun gesturesAndCommandsAreMatchedApart() {
        val keymap = Keymap.DEFAULT
        assertEquals(ShortcutAction.PAN_VIEW, keymap.gesture(b("MouseMiddle")))
        assertEquals(ShortcutAction.PAN_VIEW, keymap.gesture(b("Shift+MouseMiddle")))
        assertEquals(ShortcutAction.ZOOM_DRAG, keymap.gesture(b("Ctrl+MouseMiddle")))
        assertEquals(ShortcutAction.BRUSH_ADJUST_DRAG, keymap.gesture(b("Alt+MouseRight")))
        assertEquals(ShortcutAction.BRUSH_ADJUST_ALT_DRAG, keymap.gesture(b("Alt+Shift+MouseRight")))
        // Matching is exact: a plain right press is the context menu, not a gesture.
        assertNull(keymap.gesture(b("Alt+MouseMiddle")))
        assertNull(keymap.mouseCommand(b("MouseMiddle"), ShortcutScope.CANVAS))
        assertEquals(ShortcutAction.BRUSH_ROTATE_RIGHT, keymap.mouseCommand(b("Alt+WheelDown"), ShortcutScope.CANVAS))
        assertEquals(ShortcutAction.BRUSH_ROTATE_LEFT, keymap.mouseCommand(b("Alt+Shift+WheelUp"), ShortcutScope.CANVAS))
        assertNull(keymap.mouseCommand(b("Alt+WheelDown"), ShortcutScope.APP))
        assertNull(keymap.mouseCommand(null, ShortcutScope.CANVAS))

        val rebound = keymap.with(ShortcutAction.UNDO, listOf(b("MouseBack")))
        assertEquals(ShortcutAction.UNDO, rebound.mouseCommand(b("MouseBack"), ShortcutScope.APP))
        assertNull(rebound.gesture(b("MouseBack")))
    }

    @Test fun aCaptureRefusesInputsTheActionCannotUse() {
        val keymap = Keymap.DEFAULT
        val pan = ShortcutAction.PAN_VIEW
        assertIs<CaptureCheck.Unsupported>(keymap.validateCapture(pan, 2, b("P")))
        assertIs<CaptureCheck.Unsupported>(keymap.validateCapture(pan, 2, b("Alt+WheelUp")))
        assertEquals(CaptureCheck.Ok, keymap.validateCapture(pan, 2, b("Alt+MouseLeft")))
        assertEquals(CaptureCheck.Ok, keymap.validateCapture(pan, 2, b("MouseForward")))
        // A bare left or right press stays with the tools and the context menu, even for a gesture.
        assertEquals(CaptureCheck.Reserved, keymap.validateCapture(pan, 2, b("MouseRight")))
        assertEquals(CaptureCheck.Reserved, keymap.validateCapture(pan, 2, b("MouseLeft")))

        assertIs<CaptureCheck.Unsupported>(keymap.validateCapture(ShortcutAction.UNDO, 0, b("Ctrl+MouseRight")))
        assertIs<CaptureCheck.Unsupported>(keymap.validateCapture(ShortcutAction.TEMPORARY_SELECT, 0, b("Alt+WheelUp")))
        assertEquals(CaptureCheck.Ok, keymap.validateCapture(ShortcutAction.TEMPORARY_SELECT, 1, b("MouseBack")))
        // A bare wheel scrolls and zooms everywhere.
        assertEquals(CaptureCheck.Reserved, keymap.validateCapture(ShortcutAction.ZOOM_IN, 0, b("WheelUp")))
        assertEquals(CaptureCheck.Ok, keymap.validateCapture(ShortcutAction.ZOOM_IN, 0, b("Ctrl+Shift+WheelUp")))
        assertEquals(CaptureCheck.Ok, keymap.validateCapture(ShortcutAction.UNDO, 2, b("MouseBack")))
        // The root sees every press first, so an application command cannot take the canvas's pan button.
        assertEquals(CaptureCheck.Conflict(ShortcutAction.PAN_VIEW), keymap.validateCapture(ShortcutAction.UNDO, 2, b("MouseMiddle")))
        assertEquals(CaptureCheck.Conflict(ShortcutAction.PAN_VIEW), keymap.validateCapture(ShortcutAction.FRAME_VIEW, 1, b("MouseMiddle")))
    }

    @Test fun theCanvasAndTheTimelineMayShareChordsButNotWithTheApplication() {
        val keymap = Keymap.DEFAULT
        val del = b("Del")
        assertEquals(setOf(ShortcutAction.DELETE_SELECTION, ShortcutAction.MOTION_DELETE_KEYS), keymap.conflictIndex()[del]!!.toSet())
        assertEquals(emptyList(), keymap.conflictsFor(ShortcutAction.MOTION_DELETE_KEYS, del))
        assertEquals(CaptureCheck.Ok, keymap.validateCapture(ShortcutAction.MOTION_FIT_VIEW, 1, b("0")))
        assertEquals(CaptureCheck.Conflict(ShortcutAction.UNDO), keymap.validateCapture(ShortcutAction.MOTION_KEY_POSE, 1, b("Ctrl+Z")))
        // Space is the canvas's pan latch but plays the timeline.
        assertEquals(CaptureCheck.Reserved, keymap.validateCapture(ShortcutAction.FRAME_VIEW, 1, b("Space")))
        assertEquals(CaptureCheck.DuplicateSelf, keymap.validateCapture(ShortcutAction.MOTION_PLAY_PAUSE, 1, b("Space")))
        assertEquals(ShortcutAction.MOTION_PLAY_PAUSE, keymap.match(b("Space"), ShortcutScope.TIMELINE))
        assertNull(keymap.match(b("Space"), ShortcutScope.CANVAS))
    }
}
