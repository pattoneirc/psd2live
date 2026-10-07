package io.github.psd2live.ui.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed as isPointerAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as isPointerCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as isPointerMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import kotlin.math.abs

/**
 * A mouse input a binding can name in place of a key. The wheel's four directions are discrete
 * notches; the buttons are a click for a command, or the button that starts a drag for a gesture
 * ([ShortcutKind.DRAG]).
 */
enum class MouseInput(val token: String, val isWheel: Boolean) {
    WHEEL_UP("WheelUp", true),
    WHEEL_DOWN("WheelDown", true),
    WHEEL_LEFT("WheelLeft", true),
    WHEEL_RIGHT("WheelRight", true),
    LEFT("MouseLeft", false),
    RIGHT("MouseRight", false),
    MIDDLE("MouseMiddle", false),
    BACK("MouseBack", false),
    FORWARD("MouseForward", false),
    ;

    companion object {
        fun fromToken(token: String): MouseInput? = entries.firstOrNull { it.token == token }

        /** The button input a press of [button] is, or null for a button no binding can name. */
        fun of(button: PointerButton?): MouseInput? = when (button) {
            PointerButton.Primary -> LEFT
            PointerButton.Secondary -> RIGHT
            PointerButton.Tertiary -> MIDDLE
            PointerButton.Back -> BACK
            PointerButton.Forward -> FORWARD
            else -> null
        }

        /**
         * The wheel direction one scroll event is. Compose turns a Shift + wheel into a horizontal
         * scroll (and Windows reports a tilted wheel as Shift + wheel), so a horizontal delta with
         * Shift held reads as the vertical notch it came from: `Shift+WheelUp` is then what the user
         * did, both when it is recorded and when it fires.
         */
        fun wheel(delta: Offset, shift: Boolean): MouseInput? = when {
            delta.y != 0f && abs(delta.y) >= abs(delta.x) -> if (delta.y < 0f) WHEEL_UP else WHEEL_DOWN
            delta.x == 0f -> null
            shift -> if (delta.x < 0f) WHEEL_UP else WHEEL_DOWN
            else -> if (delta.x < 0f) WHEEL_LEFT else WHEEL_RIGHT
        }
    }
}

/**
 * A single binding: one key or one mouse input, plus an exact modifier combination. Exactly one of
 * [key] and [mouse] is set.
 *
 * The serialized form is also the display form (`"Ctrl+Shift+Z"`, `"["`, `"Shift+["`, `"NumPad0"`,
 * `"Alt+WheelUp"`, `"MouseMiddle"`), so there is exactly one printer to keep in sync with the parser.
 *
 * Match is *exact*: extra modifiers do not match. A binding of `Ctrl+W` is not triggered by
 * `Ctrl+Shift+W`.
 */
data class KeyBinding(
    val key: Key?,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val mouse: MouseInput? = null,
) {
    init {
        require((key == null) != (mouse == null)) { "A binding names exactly one key or one mouse input" }
    }

    /**
     * Canonical text form. Modifier order is fixed so that two equal bindings always format
     * identically — the conflict index relies on this being a stable map key.
     */
    fun format(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        append(mouse?.token ?: nameOf(key!!))
    }

    val hasModifier: Boolean get() = ctrl || shift || alt

    /** Exact-modifier match against a Compose key event. */
    fun matches(event: KeyEvent): Boolean = keyBindingOf(event) == this
}

/**
 * The binding a key event represents, ignoring nothing. Routing both the dispatchers and the
 * capture flow through this one function is what keeps "what fires" and "what the conflict index
 * sees" from drifting apart.
 */
internal fun keyBindingOf(event: KeyEvent): KeyBinding = KeyBinding(
    key = event.key,
    // One binding table serves both platforms: on macOS the Command key satisfies "Ctrl".
    ctrl = if (IS_MAC) event.isCtrlPressed || event.isMetaPressed else event.isCtrlPressed,
    shift = event.isShiftPressed,
    alt = event.isAltPressed,
)

/** The binding a mouse [input] with these pointer [modifiers] represents; the pointer twin of [keyBindingOf]. */
internal fun mouseBindingOf(input: MouseInput, modifiers: PointerKeyboardModifiers): KeyBinding = KeyBinding(
    key = null,
    ctrl = if (IS_MAC) modifiers.isPointerCtrlPressed || modifiers.isPointerMetaPressed else modifiers.isPointerCtrlPressed,
    shift = modifiers.isPointerShiftPressed,
    alt = modifiers.isPointerAltPressed,
    mouse = input,
)

/** The binding of one scroll event, or null for a scroll with no direction. */
internal fun wheelBindingOf(delta: Offset, modifiers: PointerKeyboardModifiers): KeyBinding? =
    MouseInput.wheel(delta, modifiers.isPointerShiftPressed)?.let { mouseBindingOf(it, modifiers) }

/** The binding of a press of [button], or null for a button no binding can name. */
internal fun buttonBindingOf(button: PointerButton?, modifiers: PointerKeyboardModifiers): KeyBinding? =
    MouseInput.of(button)?.let { mouseBindingOf(it, modifiers) }

/** Parses the canonical form produced by [KeyBinding.format]. Returns null on anything malformed. */
internal fun parseKeyBinding(text: String): KeyBinding? {
    val tokens = text.split('+')
    if (tokens.size < 2 && tokens.firstOrNull().isNullOrEmpty()) return null
    val mouse = MouseInput.fromToken(tokens.last())
    val key = if (mouse != null) null else NAME_TO_KEY[tokens.last()] ?: return null
    var ctrl = false
    var shift = false
    var alt = false
    for (token in tokens.dropLast(1)) {
        when (token) {
            "Ctrl" -> ctrl = true
            "Shift" -> shift = true
            "Alt" -> alt = true
            else -> return null
        }
    }
    return KeyBinding(key, ctrl, shift, alt, mouse)
}

/**
 * Keys the user may bind. This doubles as the recorder's whitelist: capture can only produce a
 * binding that is in this table, which is exactly the set the dispatchers know how to match.
 *
 * No name may contain '+' — that is what makes [parseKeyBinding]'s split unambiguous, and why
 * `Key.Plus` is spelled `"Plus"` rather than `"+"`. No name may equal a [MouseInput.token] either.
 */
private val NAME_TO_KEY: Map<String, Key> = buildMap {
    put("A", Key.A); put("B", Key.B); put("C", Key.C); put("D", Key.D); put("E", Key.E); put("F", Key.F)
    put("G", Key.G); put("H", Key.H); put("I", Key.I); put("J", Key.J); put("K", Key.K); put("L", Key.L)
    put("M", Key.M); put("N", Key.N); put("O", Key.O); put("P", Key.P); put("Q", Key.Q); put("R", Key.R)
    put("S", Key.S); put("T", Key.T); put("U", Key.U); put("V", Key.V); put("W", Key.W); put("X", Key.X)
    put("Y", Key.Y); put("Z", Key.Z)
    put("0", Key.Zero); put("1", Key.One); put("2", Key.Two); put("3", Key.Three); put("4", Key.Four)
    put("5", Key.Five); put("6", Key.Six); put("7", Key.Seven); put("8", Key.Eight); put("9", Key.Nine)
    put("F1", Key.F1); put("F2", Key.F2); put("F3", Key.F3); put("F4", Key.F4); put("F5", Key.F5)
    put("F6", Key.F6); put("F7", Key.F7); put("F8", Key.F8); put("F9", Key.F9); put("F10", Key.F10)
    put("F11", Key.F11); put("F12", Key.F12)
    put("Tab", Key.Tab)
    put("Enter", Key.Enter)
    put("NumPadEnter", Key.NumPadEnter)
    put("Esc", Key.Escape)
    put("Del", Key.Delete)
    put("Backspace", Key.Backspace)
    put("Space", Key.Spacebar)
    put("Home", Key.MoveHome)
    put("End", Key.MoveEnd)
    put("PageUp", Key.PageUp)
    put("PageDown", Key.PageDown)
    put("Insert", Key.Insert)
    put("[", Key.LeftBracket)
    put("]", Key.RightBracket)
    put("-", Key.Minus)
    put("=", Key.Equals)
    put("Plus", Key.Plus)
    put(",", Key.Comma)
    put(".", Key.Period)
    put("/", Key.Slash)
    put("\\", Key.Backslash)
    put(";", Key.Semicolon)
    put("'", Key.Apostrophe)
    put("`", Key.Grave)
    put("Left", Key.DirectionLeft)
    put("Right", Key.DirectionRight)
    put("Up", Key.DirectionUp)
    put("Down", Key.DirectionDown)
    put("NumPad0", Key.NumPad0); put("NumPad1", Key.NumPad1); put("NumPad2", Key.NumPad2)
    put("NumPad3", Key.NumPad3); put("NumPad4", Key.NumPad4); put("NumPad5", Key.NumPad5)
    put("NumPad6", Key.NumPad6); put("NumPad7", Key.NumPad7); put("NumPad8", Key.NumPad8)
    put("NumPad9", Key.NumPad9)
    put("NumPadAdd", Key.NumPadAdd)
    put("NumPadSubtract", Key.NumPadSubtract)
    put("NumPadMultiply", Key.NumPadMultiply)
    put("NumPadDivide", Key.NumPadDivide)
    put("NumPadDot", Key.NumPadDot)
}

private val KEY_TO_NAME: Map<Key, String> = NAME_TO_KEY.entries.associate { (name, key) -> key to name }

internal fun nameOf(key: Key): String = KEY_TO_NAME[key] ?: "?"

/** Bare modifier presses carry no meaning on their own and are never valid as a binding key. */
internal fun isModifierKey(key: Key): Boolean = when (key) {
    Key.ShiftLeft, Key.ShiftRight,
    Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight,
    -> true
    else -> false
}

/**
 * Bindings the registry refuses in [scope].
 *
 * `Esc` is how a capture is abandoned, so no Escape chord can be recorded. A bare `Space` is a held
 * latch in the canvas (press starts panning, release stops) rather than a discrete event, and
 * letting it be swallowed by a recorder would leave the latch stuck on; `Ctrl+Space` and friends
 * stay free, and so does a bare Space in the timeline, which has no latch and plays with it.
 *
 * A bare wheel scrolls every list and zooms every view, a bare left press is what every tool and
 * control is used with, and a bare right press opens the context menus: none of the three can be
 * given away.
 */
internal fun isReservedBinding(binding: KeyBinding, scope: ShortcutScope = ShortcutScope.APP): Boolean {
    val mouse = binding.mouse
    if (mouse != null) {
        if (binding.hasModifier) return false
        return mouse.isWheel || mouse == MouseInput.LEFT || mouse == MouseInput.RIGHT
    }
    val key = binding.key!!
    if (isModifierKey(key)) return true
    if (key == Key.Escape) return true
    if (key == Key.Spacebar && !binding.hasModifier && scope != ShortcutScope.TIMELINE) return true
    return false
}

private val IS_MAC: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")
