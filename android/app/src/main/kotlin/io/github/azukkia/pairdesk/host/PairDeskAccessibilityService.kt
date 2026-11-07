package io.github.azukkia.pairdesk.host

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The accessibility service that applies the input of a computer controlling
 * this phone (docs/PROTOCOL.md 5.2): gestures (dispatchGesture), Back / Home
 * / Recent apps / notifications (global actions) and text in the focused
 * field. It does nothing by itself: [HostInput] drives it during an accepted
 * session; it reads no window content apart from the focused text field
 * being edited.
 *
 * Text: on Android 13+ through the input connection an accessibility service
 * gets (`flagInputMethodEditor`: text, keys and shortcuts like a keyboard,
 * password fields included); otherwise with node actions (`ACTION_SET_TEXT`
 * computed by [TextEditing], paste for password fields).
 */
class PairDeskAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (current.value === this) current.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (current.value === this) current.value = null
        super.onDestroy()
    }

    /** An [InputInjector] calling back on [handler]'s thread. */
    fun injector(handler: Handler): InputInjector = Injector(handler)

    private inner class Injector(private val handler: Handler) : InputInjector {
        override fun dispatch(stroke: Stroke, onDone: () -> Unit): Boolean {
            val first = stroke.points.firstOrNull() ?: return false
            val path = Path().apply {
                moveTo(first.x, first.y)
                // A single point: a zero-length line, which Android treats as a tap.
                if (stroke.points.size == 1) lineTo(first.x, first.y)
                for (p in stroke.points.drop(1)) lineTo(p.x, p.y)
            }
            val gesture = try {
                val duration = stroke.durationMs.coerceIn(1, GestureDescription.getMaxGestureDuration())
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            } catch (e: IllegalArgumentException) {
                return false
            }
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onDone()

                override fun onCancelled(gestureDescription: GestureDescription?) = onDone()
            }
            return dispatchGesture(gesture, callback, handler)
        }

        override fun navigate(action: NavAction): Boolean = performGlobalAction(
            when (action) {
                NavAction.BACK -> GLOBAL_ACTION_BACK
                NavAction.HOME -> GLOBAL_ACTION_HOME
                NavAction.RECENTS -> GLOBAL_ACTION_RECENTS
                NavAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            },
        )

        override fun edit(command: EditCommand): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) connectionEdit(command)?.let { return it }
            return nodeEdit(command)
        }
    }

    // ───────────────────────────── Android 13+: input connection ─────────────────────────────

    /** Null when no field has the input focus (then node actions are tried). */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun connectionEdit(command: EditCommand): Boolean? {
        val method = inputMethod ?: return null
        val ic = method.currentInputConnection ?: return null
        val info: EditorInfo? = method.currentInputEditorInfo
        fun key(code: Int, meta: Int = 0) {
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta))
        }
        when (command) {
            is EditCommand.Type -> ic.commitText(command.text, 1, null)
            EditCommand.Enter -> {
                val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val multiLine = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                val noAction = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                // Single-line fields run their action (search, send, go…), like the Enter key of a soft keyboard.
                if (!multiLine && !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    key(KeyEvent.KEYCODE_ENTER)
                }
            }
            EditCommand.Backspace -> key(KeyEvent.KEYCODE_DEL)
            EditCommand.Delete -> key(KeyEvent.KEYCODE_FORWARD_DEL)
            EditCommand.Tab -> key(KeyEvent.KEYCODE_TAB)
            is EditCommand.Move -> {
                val meta = if (command.extend) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
                key(
                    when (command.move) {
                        CaretMove.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                        CaretMove.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                        CaretMove.UP -> KeyEvent.KEYCODE_DPAD_UP
                        CaretMove.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                        CaretMove.HOME -> KeyEvent.KEYCODE_MOVE_HOME
                        CaretMove.END -> KeyEvent.KEYCODE_MOVE_END
                    },
                    meta,
                )
            }
            EditCommand.SelectAll -> ic.performContextMenuAction(android.R.id.selectAll)
            EditCommand.Copy -> ic.performContextMenuAction(android.R.id.copy)
            EditCommand.Cut -> ic.performContextMenuAction(android.R.id.cut)
            EditCommand.Paste -> ic.performContextMenuAction(android.R.id.paste)
        }
        return true
    }

    // ───────────────────────────── node actions ─────────────────────────────

    private fun nodeEdit(command: EditCommand): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (command == EditCommand.Tab) {
            return node.focusSearch(View.FOCUS_FORWARD)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ?: false
        }
        if (!node.isEditable) return false
        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val field = TextEditing.field(text, node.textSelectionStart, node.textSelectionEnd)
        return when (command) {
            is EditCommand.Type ->
                // A password field shows dots to accessibility services: its text cannot be rebuilt.
                if (node.isPassword) paste(node, command.text) else setText(node, TextEditing.insert(field, command.text)) || paste(node, command.text)
            EditCommand.Enter -> when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    node.actionList.any { it.id == android.R.id.accessibilityActionImeEnter } ->
                    node.performAction(android.R.id.accessibilityActionImeEnter)
                node.isMultiLine && !node.isPassword -> setText(node, TextEditing.insert(field, "\n"))
                else -> false
            }
            EditCommand.Backspace -> if (node.isPassword) false else TextEditing.backspace(field)?.let { setText(node, it) } ?: false
            EditCommand.Delete -> if (node.isPassword) false else TextEditing.delete(field)?.let { setText(node, it) } ?: false
            is EditCommand.Move -> when (command.move) {
                CaretMove.UP, CaretMove.DOWN -> node.performAction(
                    if (command.move == CaretMove.UP) {
                        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                    } else {
                        AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                    },
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
                        putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, command.extend)
                    },
                )
                else -> TextEditing.move(field, command.move, command.extend)?.let { select(node, it) } ?: false
            }
            EditCommand.SelectAll -> select(node, TextEditing.selectAll(field))
            EditCommand.Copy -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            EditCommand.Cut -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            EditCommand.Paste -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            EditCommand.Tab -> false
        }
    }

    private fun setText(node: AccessibilityNodeInfo, f: TextField): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, f.text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        select(node, f)
        return true
    }

    private fun select(node: AccessibilityNodeInfo, f: TextField): Boolean = node.performAction(
        AccessibilityNodeInfo.ACTION_SET_SELECTION,
        Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, f.start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, f.end)
        },
    )

    /** Types [text] through the clipboard (the phone's clipboard then holds it). */
    private fun paste(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("PairDesk", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: RuntimeException) {
            false
        }
    }

    companion object {
        private val current = MutableStateFlow<PairDeskAccessibilityService?>(null)

        /** The running service; null while it is not enabled in the accessibility settings. */
        val instance: StateFlow<PairDeskAccessibilityService?> = current.asStateFlow()

        /** The accessibility settings, where the user enables "PairDesk". */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * The app's details, where Android 13+ hides "Allow restricted settings"
         * (needed before enabling the service of an app not installed from a store).
         */
        fun appDetailsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
