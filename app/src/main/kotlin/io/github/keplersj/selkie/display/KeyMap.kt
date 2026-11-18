package io.github.keplersj.selkie.display

import android.view.KeyEvent

/**
 * Android key codes and characters to Linux evdev key codes, which is what Wayland clients receive
 * (with the compositor's US keymap). Hardware keyboards report the evdev code as the scan code; soft
 * keyboards and `adb input` don't, so their key codes and committed text are mapped here.
 */
object KeyMap {
    const val KEY_LEFTSHIFT = 42
    const val BTN_LEFT = 272
    const val BTN_RIGHT = 273
    const val BTN_MIDDLE = 274

    private val keyCodes: Map<Int, Int> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + (c - 'a'), 16 + i) }
        "asdfghjkl".forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + (c - 'a'), 30 + i) }
        "zxcvbnm".forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + (c - 'a'), 44 + i) }
        for (d in 1..9) put(KeyEvent.KEYCODE_0 + d, 1 + d)
        put(KeyEvent.KEYCODE_0, 11)
        putAll(mapOf(
            KeyEvent.KEYCODE_ESCAPE to 1, KeyEvent.KEYCODE_MINUS to 12, KeyEvent.KEYCODE_EQUALS to 13,
            KeyEvent.KEYCODE_DEL to 14, KeyEvent.KEYCODE_TAB to 15, KeyEvent.KEYCODE_LEFT_BRACKET to 26,
            KeyEvent.KEYCODE_RIGHT_BRACKET to 27, KeyEvent.KEYCODE_ENTER to 28, KeyEvent.KEYCODE_NUMPAD_ENTER to 96,
            KeyEvent.KEYCODE_CTRL_LEFT to 29, KeyEvent.KEYCODE_SEMICOLON to 39, KeyEvent.KEYCODE_APOSTROPHE to 40,
            KeyEvent.KEYCODE_GRAVE to 41, KeyEvent.KEYCODE_SHIFT_LEFT to 42, KeyEvent.KEYCODE_BACKSLASH to 43,
            KeyEvent.KEYCODE_COMMA to 51, KeyEvent.KEYCODE_PERIOD to 52, KeyEvent.KEYCODE_SLASH to 53,
            KeyEvent.KEYCODE_SHIFT_RIGHT to 54, KeyEvent.KEYCODE_ALT_LEFT to 56, KeyEvent.KEYCODE_SPACE to 57,
            KeyEvent.KEYCODE_CAPS_LOCK to 58, KeyEvent.KEYCODE_CTRL_RIGHT to 97, KeyEvent.KEYCODE_ALT_RIGHT to 100,
            KeyEvent.KEYCODE_MOVE_HOME to 102, KeyEvent.KEYCODE_DPAD_UP to 103, KeyEvent.KEYCODE_PAGE_UP to 104,
            KeyEvent.KEYCODE_DPAD_LEFT to 105, KeyEvent.KEYCODE_DPAD_RIGHT to 106, KeyEvent.KEYCODE_MOVE_END to 107,
            KeyEvent.KEYCODE_DPAD_DOWN to 108, KeyEvent.KEYCODE_PAGE_DOWN to 109, KeyEvent.KEYCODE_INSERT to 110,
            KeyEvent.KEYCODE_FORWARD_DEL to 111, KeyEvent.KEYCODE_META_LEFT to 125, KeyEvent.KEYCODE_META_RIGHT to 126,
        ))
        for (i in 0 until 10) put(KeyEvent.KEYCODE_F1 + i, 59 + i)
        put(KeyEvent.KEYCODE_F11, 87); put(KeyEvent.KEYCODE_F12, 88)
    }

    /** evdev code for a key event, or null if unknown. */
    fun evdev(keyCode: Int, scanCode: Int = 0): Int? = keyCodes[keyCode] ?: scanCode.takeIf { it > 0 }

    // Each shifted symbol is on the key of the unshifted one at the same index.
    private const val SHIFTED = "~!@#\$%^&*()_+{}|:\"<>?"
    private const val UNSHIFTED = "`1234567890-=[]\\;',./"

    /** Key presses that type [c] on a US layout: evdev code and whether Shift is needed. */
    fun forChar(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> keyCodes[KeyEvent.KEYCODE_A + (c - 'a')]!! to false
        in 'A'..'Z' -> keyCodes[KeyEvent.KEYCODE_A + (c - 'A')]!! to true
        ' ' -> 57 to false
        '\n' -> 28 to false
        '\t' -> 15 to false
        else -> {
            val i = SHIFTED.indexOf(c)
            val j = UNSHIFTED.indexOf(c)
            when {
                i >= 0 -> charCode(UNSHIFTED[i]) to true
                j >= 0 -> charCode(UNSHIFTED[j]) to false
                else -> null
            }
        }
    }

    private fun charCode(c: Char): Int = when (c) {
        '`' -> 41
        in '1'..'9' -> 1 + (c - '0')
        '0' -> 11
        '-' -> 12
        '=' -> 13
        '[' -> 26
        ']' -> 27
        '\\' -> 43
        ';' -> 39
        '\'' -> 40
        ',' -> 51
        '.' -> 52
        '/' -> 53
        else -> error("no key for $c")
    }
}
