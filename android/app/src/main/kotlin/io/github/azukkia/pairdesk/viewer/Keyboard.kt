package io.github.azukkia.pairdesk.viewer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** The modifiers of the extra keys bar. */
enum class StickyModifier(val code: String) {
    CTRL("ControlLeft"),
    ALT("AltLeft"),
    SHIFT("ShiftLeft"),
    META("MetaLeft"),
}

enum class StickyState {
    OFF,

    /** Applies to the next key (or click), then turns off. */
    ONCE,

    /** Stays on until tapped again. */
    LOCKED,
}

/**
 * Sticky modifiers of the extra keys bar: a tap arms a modifier for the next
 * key or click, a long press locks it, another tap turns it off. Modifiers
 * are pressed on the remote only around the keys they apply to, so that
 * nothing stays stuck if the connection drops.
 */
class ModifierKeys {
    private val _states = MutableStateFlow(StickyModifier.entries.associateWith { StickyState.OFF })
    val states: StateFlow<Map<StickyModifier, StickyState>> = _states.asStateFlow()

    fun state(m: StickyModifier): StickyState = _states.value.getValue(m)

    private fun set(m: StickyModifier, s: StickyState) {
        _states.value = _states.value + (m to s)
    }

    /** Off → once, once or locked → off. */
    fun tap(m: StickyModifier) = set(m, if (state(m) == StickyState.OFF) StickyState.ONCE else StickyState.OFF)

    /** Locked ↔ off. */
    fun lock(m: StickyModifier) = set(m, if (state(m) == StickyState.LOCKED) StickyState.OFF else StickyState.LOCKED)

    /** Active modifiers, in the order they are pressed (Ctrl, Alt, Shift, Meta). */
    fun active(): List<StickyModifier> = StickyModifier.entries.filter { state(it) != StickyState.OFF }

    val any: Boolean get() = _states.value.values.any { it != StickyState.OFF }

    /** A key or click used them: "once" modifiers turn off. */
    fun consume() {
        if (_states.value.values.none { it == StickyState.ONCE }) return
        _states.value = _states.value.mapValues { (_, s) -> if (s == StickyState.ONCE) StickyState.OFF else s }
    }

    fun clear() {
        _states.value = StickyModifier.entries.associateWith { StickyState.OFF }
    }
}

/** Where the soft keyboard's input goes ([KeyboardController]). */
interface SoftKeyboardOutput {
    /** Text typed (may contain `\n` and `\t`). */
    fun text(text: String)

    /** A key pressed and released (`KeyboardEvent.code`: Backspace, Enter, ArrowLeft…). */
    fun key(code: String)
}

/**
 * Translates what an Android input method does to an editor
 * (InputConnection: commitText, setComposingText, deleteSurroundingText,
 * sendKeyEvent…) into text and key presses for the remote computer.
 *
 * The remote has no idea of "composing" text: what the IME composes is typed
 * right away, and every change of the composing text is applied as
 * Backspaces plus the new end (diff on the common prefix, in code points), so
 * that predictive keyboards and IMEs that compose (Chinese, Japanese…) work.
 */
class SoftKeyboard(private val out: SoftKeyboardOutput) {
    private var composing = ""

    /** The text being composed, as already typed on the remote. */
    val composingText: String get() = composing

    fun commitText(text: String) {
        replaceComposing(text)
        composing = ""
    }

    fun setComposingText(text: String) {
        replaceComposing(text)
        composing = text
    }

    /** The composing text is accepted as is (it is already on the remote). */
    fun finishComposingText() {
        composing = ""
    }

    fun deleteSurroundingText(before: Int, after: Int) {
        if (before > 0 && composing.isNotEmpty()) composing = dropLastCodePoints(composing, before)
        repeat(before.coerceIn(0, MAX_DELETE)) { out.key("Backspace") }
        repeat(after.coerceIn(0, MAX_DELETE)) { out.key("Delete") }
    }

    /**
     * A key event sent by the IME (`sendKeyEvent`, key down only): special keys
     * become key presses, characters become text. Returns false when ignored.
     */
    fun keyDown(keyCode: Int, unicodeChar: Int): Boolean {
        SPECIAL_KEYS[keyCode]?.let { code ->
            if (code == "Backspace" && composing.isNotEmpty()) composing = dropLastCodePoints(composing, 1)
            out.key(code)
            return true
        }
        if (unicodeChar > 0 && !Character.isISOControl(unicodeChar)) {
            out.text(String(Character.toChars(unicodeChar)))
            return true
        }
        val code = KeyCodes.forKeyCode(keyCode) ?: return false
        if (KeyCodes.isModifier(code)) return false
        out.key(code)
        return true
    }

    /** The editor action key (Enter on most keyboards). */
    fun editorAction() {
        out.key("Enter")
    }

    fun reset() {
        composing = ""
    }

    private fun replaceComposing(text: String) {
        val prefix = commonPrefixCodePoints(composing, text)
        val removed = composing.codePointCount(prefix.first, composing.length)
        repeat(removed) { out.key("Backspace") }
        val added = text.substring(prefix.second)
        if (added.isNotEmpty()) out.text(added)
    }

    companion object {
        /** Keys that are sent as key presses rather than text (Android KEYCODE_*). */
        private val SPECIAL_KEYS: Map<Int, String> = mapOf(
            67 to "Backspace", // DEL
            112 to "Delete", // FORWARD_DEL
            66 to "Enter", // ENTER
            160 to "Enter", // NUMPAD_ENTER
            61 to "Tab",
            111 to "Escape",
            19 to "ArrowUp",
            20 to "ArrowDown",
            21 to "ArrowLeft",
            22 to "ArrowRight",
            122 to "Home", // MOVE_HOME
            123 to "End", // MOVE_END
            92 to "PageUp",
            93 to "PageDown",
            124 to "Insert",
        )

        private const val MAX_DELETE = 256

        /** Index (in each string) where [a] and [b] stop sharing code points. */
        internal fun commonPrefixCodePoints(a: String, b: String): Pair<Int, Int> {
            var i = 0
            while (i < a.length && i < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(i)
                if (ca != cb) break
                i += Character.charCount(ca)
            }
            return i to i
        }

        internal fun dropLastCodePoints(s: String, n: Int): String {
            var end = s.length
            var left = n
            while (left > 0 && end > 0) {
                end = s.offsetByCodePoints(end, -1)
                left--
            }
            return s.substring(0, end)
        }
    }
}

/**
 * The remote keyboard: text and keys from the soft keyboard, the extra keys
 * bar (sticky modifiers, Esc, Tab, arrows…), key combinations and hardware
 * keyboards. Main thread only.
 *
 * - Text goes as `['t', text]` (any language, whatever the remote layout),
 *   `\n` and `\t` as Enter and Tab presses.
 * - With Ctrl, Alt or Win armed, typed characters become shortcuts: the
 *   physical key typing the character on the remote layout ([layout]) is
 *   pressed with the modifiers (Ctrl + "c" → ControlLeft, KeyC).
 * - Special keys go as `['k', code, 1]` then `['k', code, 0]`, with the armed
 *   modifiers pressed around them.
 */
class KeyboardController(
    private val sender: InputSender,
    val modifiers: ModifierKeys,
    private val layout: () -> RemoteLayout,
    private val canControl: () -> Boolean,
) : SoftKeyboardOutput, ModifierHold {

    override fun text(text: String) {
        if (!canControl() || text.isEmpty()) return
        val mods = modifiers.active()
        if (mods.none { it != StickyModifier.SHIFT }) {
            sendPlain(if (StickyModifier.SHIFT in mods) text.uppercase(Locale.ROOT) else text)
            modifiers.consume()
            return
        }
        val codes = mods.map { it.code }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val stroke = if (n == 1) layout().strokeFor(text[i]) else null
            if (stroke == null) {
                sendPlain(text.substring(i, i + n))
            } else {
                val withShift = if (stroke.shift && StickyModifier.SHIFT !in mods) codes + StickyModifier.SHIFT.code else codes
                stroke(stroke.code, withShift)
            }
            i += n
        }
        modifiers.consume()
    }

    override fun key(code: String) {
        if (!canControl()) return
        stroke(code, modifiers.active().map { it.code })
        modifiers.consume()
    }

    /** The Win key of the bar: Start menu (with the other armed modifiers, if any). */
    fun windowsKey() {
        if (!canControl()) return
        val mods = modifiers.active().filter { it != StickyModifier.META }.map { it.code }
        stroke(StickyModifier.META.code, mods)
        modifiers.consume()
    }

    /** A combination of the menu (keymap.js KEY_COMBOS): down in order, up in reverse order. */
    fun combo(combo: KeyCombo) {
        if (!canControl()) return
        sender.combo(combo.codes)
    }

    /** A hardware keyboard key, by physical position (like the desktop viewer). */
    fun hardwareKey(code: String, down: Boolean) {
        if (!canControl()) {
            if (!down && code in sender.keys) sender.key(code, false)
            return
        }
        sender.key(code, down)
    }

    /** `['a', action]` for Android hosts (back, home, recents). */
    fun androidAction(name: String) {
        if (canControl()) sender.action(name)
    }

    override fun pressModifiers(): List<String> {
        val codes = modifiers.active().map { it.code }
        if (codes.isEmpty()) return codes
        sender.keys(codes.map { it to true })
        modifiers.consume()
        return codes
    }

    override fun releaseModifiers(codes: List<String>) {
        if (codes.isNotEmpty()) sender.keys(codes.reversed().map { it to false })
    }

    private fun stroke(code: String, mods: List<String>) {
        sender.keys(mods.map { it to true } + listOf(code to true, code to false) + mods.reversed().map { it to false })
    }

    /** Text with its line breaks and tabs as key presses (`\r\n` is one Enter). */
    private fun sendPlain(text: String) {
        val chunk = StringBuilder()
        fun flush() {
            if (chunk.isNotEmpty()) sender.text(chunk.toString())
            chunk.clear()
        }
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '\r' -> {
                    flush()
                    stroke("Enter", emptyList())
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                '\n' -> {
                    flush()
                    stroke("Enter", emptyList())
                }
                '\t' -> {
                    flush()
                    stroke("Tab", emptyList())
                }
                else -> chunk.append(c)
            }
            i++
        }
        flush()
    }
}
