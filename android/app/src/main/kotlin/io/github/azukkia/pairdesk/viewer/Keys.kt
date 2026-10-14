package io.github.azukkia.pairdesk.viewer

import java.util.Locale

/**
 * Key codes of the protocol: `KeyboardEvent.code` values, the physical key
 * positions of src/shared/keymap.js (the remote keyboard layout decides which
 * character a key types, like the desktop viewer).
 */
object KeyCodes {
    /**
     * keymap.js: KeyboardEvent.code → Linux evdev code. Android reports the
     * evdev code of hardware keyboards as `KeyEvent.getScanCode()`, which gives
     * the physical position whatever keyboard layout the phone uses.
     */
    private val EVDEV: Map<String, Int> = linkedMapOf(
        "Escape" to 1,
        "Digit1" to 2, "Digit2" to 3, "Digit3" to 4, "Digit4" to 5, "Digit5" to 6,
        "Digit6" to 7, "Digit7" to 8, "Digit8" to 9, "Digit9" to 10, "Digit0" to 11,
        "Minus" to 12, "Equal" to 13, "Backspace" to 14, "Tab" to 15,
        "KeyQ" to 16, "KeyW" to 17, "KeyE" to 18, "KeyR" to 19, "KeyT" to 20,
        "KeyY" to 21, "KeyU" to 22, "KeyI" to 23, "KeyO" to 24, "KeyP" to 25,
        "BracketLeft" to 26, "BracketRight" to 27, "Enter" to 28, "ControlLeft" to 29,
        "KeyA" to 30, "KeyS" to 31, "KeyD" to 32, "KeyF" to 33, "KeyG" to 34,
        "KeyH" to 35, "KeyJ" to 36, "KeyK" to 37, "KeyL" to 38,
        "Semicolon" to 39, "Quote" to 40, "Backquote" to 41, "ShiftLeft" to 42, "Backslash" to 43,
        "KeyZ" to 44, "KeyX" to 45, "KeyC" to 46, "KeyV" to 47, "KeyB" to 48,
        "KeyN" to 49, "KeyM" to 50, "Comma" to 51, "Period" to 52, "Slash" to 53,
        "ShiftRight" to 54, "NumpadMultiply" to 55, "AltLeft" to 56, "Space" to 57, "CapsLock" to 58,
        "F1" to 59, "F2" to 60, "F3" to 61, "F4" to 62, "F5" to 63,
        "F6" to 64, "F7" to 65, "F8" to 66, "F9" to 67, "F10" to 68,
        "NumLock" to 69, "ScrollLock" to 70,
        "Numpad7" to 71, "Numpad8" to 72, "Numpad9" to 73, "NumpadSubtract" to 74,
        "Numpad4" to 75, "Numpad5" to 76, "Numpad6" to 77, "NumpadAdd" to 78,
        "Numpad1" to 79, "Numpad2" to 80, "Numpad3" to 81, "Numpad0" to 82, "NumpadDecimal" to 83,
        "IntlBackslash" to 86, "F11" to 87, "F12" to 88,
        "NumpadEqual" to 117,
        "F13" to 183, "F14" to 184, "F15" to 185, "F16" to 186, "F17" to 187,
        "F18" to 188, "F19" to 189, "F20" to 190, "F21" to 191, "F22" to 192,
        "F23" to 193, "F24" to 194,
        "KanaMode" to 93, "Lang2" to 123, "Lang1" to 122, "IntlRo" to 89,
        "Convert" to 92, "NonConvert" to 94, "IntlYen" to 124, "NumpadComma" to 121,
        "MediaTrackPrevious" to 165, "MediaTrackNext" to 163,
        "NumpadEnter" to 96, "ControlRight" to 97,
        "AudioVolumeMute" to 113, "LaunchApp2" to 140, "MediaPlayPause" to 164, "MediaStop" to 166,
        "AudioVolumeDown" to 114, "AudioVolumeUp" to 115, "BrowserHome" to 172,
        "NumpadDivide" to 98, "PrintScreen" to 99, "AltRight" to 100,
        "Home" to 102, "ArrowUp" to 103, "PageUp" to 104,
        "ArrowLeft" to 105, "ArrowRight" to 106,
        "End" to 107, "ArrowDown" to 108, "PageDown" to 109,
        "Insert" to 110, "Delete" to 111,
        "MetaLeft" to 125, "MetaRight" to 126, "ContextMenu" to 127,
        "BrowserSearch" to 217, "BrowserFavorites" to 156, "BrowserRefresh" to 173,
        "BrowserStop" to 128, "BrowserForward" to 159, "BrowserBack" to 158,
        "LaunchApp1" to 157, "LaunchMail" to 155,
        "Pause" to 119,
    )

    private val BY_EVDEV: Map<Int, String> = EVDEV.entries.associate { (code, evdev) -> evdev to code }

    /** The Linux evdev code of [code] (keymap.js), for tests. */
    internal fun evdevOf(code: String): Int? = EVDEV[code]

    /** Every code the desktop host can inject (keymap.js `KEYMAP`, without the OSLeft/OSRight aliases). */
    val SUPPORTED: Set<String> = EVDEV.keys

    val MODIFIERS: Set<String> = setOf("ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight", "AltLeft", "AltRight", "MetaLeft", "MetaRight")

    /** Android `KeyEvent.KEYCODE_*` → code, for keys without a usable scan code (soft keyboards, virtual devices). */
    private val ANDROID: Map<Int, String> = buildMap {
        for (i in 0..25) put(29 + i, "Key" + ('A' + i)) // KEYCODE_A..KEYCODE_Z
        for (i in 0..9) put(7 + i, "Digit$i") // KEYCODE_0..KEYCODE_9
        for (i in 0..9) put(144 + i, "Numpad$i") // KEYCODE_NUMPAD_0..9
        for (i in 0..11) put(131 + i, "F" + (i + 1)) // KEYCODE_F1..F12
        put(66, "Enter") // ENTER
        put(23, "Enter") // DPAD_CENTER
        put(67, "Backspace") // DEL
        put(112, "Delete") // FORWARD_DEL
        put(61, "Tab")
        put(62, "Space")
        put(111, "Escape")
        put(19, "ArrowUp") // DPAD_UP
        put(20, "ArrowDown")
        put(21, "ArrowLeft")
        put(22, "ArrowRight")
        put(122, "Home") // MOVE_HOME
        put(123, "End") // MOVE_END
        put(92, "PageUp")
        put(93, "PageDown")
        put(124, "Insert")
        put(59, "ShiftLeft")
        put(60, "ShiftRight")
        put(113, "ControlLeft")
        put(114, "ControlRight")
        put(57, "AltLeft")
        put(58, "AltRight")
        put(117, "MetaLeft")
        put(118, "MetaRight")
        put(115, "CapsLock")
        put(143, "NumLock")
        put(116, "ScrollLock")
        put(69, "Minus")
        put(70, "Equal")
        put(71, "BracketLeft")
        put(72, "BracketRight")
        put(73, "Backslash")
        put(74, "Semicolon")
        put(75, "Quote") // APOSTROPHE
        put(76, "Slash")
        put(55, "Comma")
        put(56, "Period")
        put(68, "Backquote") // GRAVE
        put(120, "PrintScreen") // SYSRQ
        put(121, "Pause") // BREAK
        put(82, "ContextMenu") // MENU
        put(154, "NumpadDivide")
        put(155, "NumpadMultiply")
        put(156, "NumpadSubtract")
        put(157, "NumpadAdd")
        put(158, "NumpadDecimal")
        put(159, "NumpadComma")
        put(160, "NumpadEnter")
        put(161, "NumpadEqual")
        put(85, "MediaPlayPause")
        put(86, "MediaStop")
        put(87, "MediaTrackNext")
        put(88, "MediaTrackPrevious")
        put(164, "AudioVolumeMute") // VOLUME_MUTE
        put(125, "BrowserForward") // FORWARD
        put(174, "BrowserFavorites") // BOOKMARK
        put(64, "LaunchApp1") // EXPLORER
        put(65, "LaunchMail") // ENVELOPE
        put(210, "LaunchApp2") // CALCULATOR
        put(213, "NonConvert") // MUHENKAN
        put(214, "Convert") // HENKAN
        put(215, "KanaMode") // KATAKANA_HIRAGANA
        put(216, "IntlYen") // YEN
        put(217, "IntlRo") // RO
    }

    /** Keys that stay with the phone: navigation, volume, power, camera, assistant… */
    private val PHONE_KEYS: Set<Int> = setOf(
        3, // HOME
        4, // BACK
        24, 25, // VOLUME_UP, VOLUME_DOWN
        26, // POWER
        27, // CAMERA
        79, // HEADSETHOOK
        80, // FOCUS
        84, // SEARCH
        91, // MUTE (microphone)
        187, // APP_SWITCH
        219, // ASSIST
        220, 221, // BRIGHTNESS_DOWN/UP
        223, 224, // SLEEP, WAKEUP
        231, // VOICE_ASSIST
        5, 6, // CALL, ENDCALL
    )

    /** The code of a hardware key ([scanCode] first: physical position), or of an Android [keyCode]; null when unknown. */
    fun forKeyEvent(keyCode: Int, scanCode: Int): String? {
        if (keyCode in PHONE_KEYS) return null
        if (scanCode > 0) BY_EVDEV[scanCode]?.let { return it }
        return ANDROID[keyCode]
    }

    /** The code of an Android key code (soft keyboard events), or null. */
    fun forKeyCode(keyCode: Int): String? = if (keyCode in PHONE_KEYS) null else ANDROID[keyCode]

    /** True for keys the phone keeps (navigation, volume…): never sent to the computer. */
    fun isPhoneKey(keyCode: Int): Boolean = keyCode in PHONE_KEYS

    fun isModifier(code: String): Boolean = code in MODIFIERS
}

/** The key combinations of the desktop viewer's "Actions" menu (keymap.js `KEY_COMBOS`, same order). */
enum class KeyCombo(val codes: List<String>) {
    CTRL_ALT_DEL(listOf("ControlLeft", "AltLeft", "Delete")),
    CTRL_SHIFT_ESC(listOf("ControlLeft", "ShiftLeft", "Escape")),
    ALT_TAB(listOf("AltLeft", "Tab")),
    ALT_F4(listOf("AltLeft", "F4")),
    WIN(listOf("MetaLeft")),
    WIN_R(listOf("MetaLeft", "KeyR")),
    WIN_D(listOf("MetaLeft", "KeyD")),
    WIN_E(listOf("MetaLeft", "KeyE")),
    WIN_L(listOf("MetaLeft", "KeyL")),
    PRINT_SCREEN(listOf("PrintScreen")),
    ;

    /** Down in order, then up in reverse order (viewer.js `sendCombo`). */
    val events: List<Pair<String, Boolean>> get() = codes.map { it to true } + codes.reversed().map { it to false }
}

/** A key to press, with Shift when the character needs it. */
data class KeyStroke(val code: String, val shift: Boolean = false)

/**
 * Keyboard layout of the remote computer, used only to turn characters typed
 * with a sticky modifier (Ctrl + "a"…) into physical keys: shortcuts follow
 * the layout (Ctrl+A on an AZERTY keyboard is the key at the QWERTY "Q"
 * position). Plain text is typed as text, whatever the layout.
 */
enum class RemoteLayout(val wire: String) {
    QWERTY("qwerty"),
    AZERTY("azerty"),
    QWERTZ("qwertz"),
    ;

    /** The physical key typing [ch] on this layout (letters, digits, space, common punctuation), or null. */
    fun strokeFor(ch: Char): KeyStroke? {
        if (ch == ' ') return KeyStroke("Space")
        if (ch == '\n') return KeyStroke("Enter")
        if (ch == '\t') return KeyStroke("Tab")
        if (ch in '0'..'9') return KeyStroke("Digit$ch")
        if (ch in 'a'..'z' || ch in 'A'..'Z') {
            val lower = ch.lowercaseChar()
            val code = letterCode(lower)
            return KeyStroke(code, shift = ch.isUpperCase())
        }
        val code = when (this) {
            QWERTY -> QWERTY_PUNCT[ch]
            AZERTY -> AZERTY_PUNCT[ch]
            QWERTZ -> QWERTZ_PUNCT[ch]
        } ?: return null
        return KeyStroke(code)
    }

    private fun letterCode(c: Char): String = when (this) {
        QWERTY -> "Key" + c.uppercaseChar()
        AZERTY -> when (c) {
            'a' -> "KeyQ"
            'q' -> "KeyA"
            'z' -> "KeyW"
            'w' -> "KeyZ"
            'm' -> "Semicolon"
            else -> "Key" + c.uppercaseChar()
        }
        QWERTZ -> when (c) {
            'y' -> "KeyZ"
            'z' -> "KeyY"
            else -> "Key" + c.uppercaseChar()
        }
    }

    companion object {
        private val QWERTY_PUNCT = mapOf(
            '-' to "Minus", '=' to "Equal", '[' to "BracketLeft", ']' to "BracketRight", '\\' to "Backslash",
            ';' to "Semicolon", '\'' to "Quote", '`' to "Backquote", ',' to "Comma", '.' to "Period", '/' to "Slash",
        )
        private val AZERTY_PUNCT = mapOf(',' to "KeyM", ';' to "Comma", ':' to "Period", '!' to "Slash", ')' to "Minus", '=' to "Equal")
        private val QWERTZ_PUNCT = mapOf(',' to "Comma", '.' to "Period", '-' to "Slash", '+' to "BracketRight", '#' to "Backslash")

        fun fromWire(value: String?): RemoteLayout? = entries.firstOrNull { it.wire == value }

        /** A guess from the phone's locale: AZERTY in France and Belgium, QWERTZ in German-speaking countries… */
        fun guess(locale: Locale): RemoteLayout {
            val lang = locale.language.lowercase(Locale.ROOT)
            val country = locale.country.uppercase(Locale.ROOT)
            return when {
                country == "CH" || lang in setOf("de", "cs", "sk", "hu", "sl", "hr") -> QWERTZ
                lang == "fr" && country != "CA" -> AZERTY
                country == "BE" || country == "FR" -> AZERTY
                else -> QWERTY
            }
        }
    }
}
