package io.github.azukkia.pairdesk.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.azukkia.pairdesk.session.ViewerSession
import io.github.azukkia.pairdesk.viewer.InputMode
import io.github.azukkia.pairdesk.viewer.KeyCodes
import io.github.azukkia.pairdesk.viewer.MouseButton
import io.github.azukkia.pairdesk.viewer.SoftKeyboard
import io.github.azukkia.pairdesk.viewer.TouchAction
import io.github.azukkia.pairdesk.viewer.TouchEvent
import io.github.azukkia.pairdesk.viewer.TouchPointer
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.math.roundToInt

/**
 * The remote screen: a [SurfaceViewRenderer] laid out where the picture is
 * (letterboxed, zoomed and panned by the session's viewport: the compositor
 * scales the video, no extra copy), the touchpad cursor drawn above it, and
 * every input of the phone — touches (gesture engine), a physical mouse,
 * hardware keyboards and the soft keyboard (its own InputConnection).
 */
@SuppressLint("ViewConstructor")
class RemoteScreenView(
    context: Context,
    private val session: ViewerSession,
    eglContext: EglBase.Context,
) : ViewGroup(context) {
    private val renderer = SurfaceViewRenderer(context)
    private val viewport = session.viewport
    private val density = resources.displayMetrics.density
    private var released = false

    /** True while the soft keyboard is wanted (the view is then a text editor). */
    var keyboardWanted = false
        private set

    private val onViewportChange: (Boolean) -> Unit = { layout ->
        if (layout) requestLayout()
        invalidate()
    }

    private val cursorPath = Path().apply {
        // Classic arrow, tip at (0, 0), in dp.
        moveTo(0f, 0f)
        lineTo(0f, 17f)
        lineTo(4.2f, 13.2f)
        lineTo(7f, 19.6f)
        lineTo(9.8f, 18.4f)
        lineTo(7f, 12.2f)
        lineTo(12.6f, 12.2f)
        close()
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF000000.toInt()
        strokeWidth = 1.3f
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        renderer.init(
            eglContext,
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    post { if (!released) session.core.onFirstFrame() }
                }

                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    val (w, h) = if (rotation % 180 == 0) width to height else height to width
                    post { if (!released) viewport.setFrameSize(w, h) }
                }
            },
        )
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
        addView(renderer)
        session.rtc?.addVideoSink(renderer)
        session.hapticFeedback = { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }

    /** Detaches the video and frees the renderer (its EGL surface): call once, when the view goes away. */
    fun release() {
        if (released) return
        released = true
        session.rtc?.removeVideoSink(renderer)
        session.hapticFeedback = {}
        renderer.release()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewport.addListener(onViewportChange)
    }

    override fun onDetachedFromWindow() {
        viewport.removeListener(onViewportChange)
        super.onDetachedFromWindow()
    }

    // ───────────────────────────── layout ─────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val p = viewport.placement()
        val rw = p?.width?.roundToInt()?.coerceAtLeast(1) ?: w
        val rh = p?.height?.roundToInt()?.coerceAtLeast(1) ?: h
        renderer.measure(MeasureSpec.makeMeasureSpec(rw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rh, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val p = viewport.placement()
        if (p == null) {
            renderer.layout(0, 0, r - l, b - t)
        } else {
            val left = p.left.roundToInt()
            val top = p.top.roundToInt()
            renderer.layout(left, top, left + renderer.measuredWidth, top + renderer.measuredHeight)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewport.setViewSize(w, h)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (viewport.mode != InputMode.TOUCHPAD || !session.core.canControl) return
        val at = viewport.cursorInView() ?: return
        val scale = density * 1.15f
        canvas.save()
        canvas.translate(at.x, at.y)
        canvas.scale(scale, scale)
        canvas.drawPath(cursorPath, cursorFill)
        canvas.drawPath(cursorPath, cursorStroke)
        canvas.restore()
    }

    // ───────────────────────────── touch and mouse ─────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            // Buttons arrive as ACTION_BUTTON_PRESS / RELEASE (generic motion); moves with a button held here.
            if (event.actionMasked == MotionEvent.ACTION_MOVE) session.pointer.mouseMove(event.x, event.y, buttonsDown = event.buttonState != 0)
            return true
        }
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            else -> return true
        }
        if (action == TouchAction.DOWN && !hasFocus()) requestFocus()
        val actionId = event.getPointerId(event.actionIndex)
        if (action == TouchAction.MOVE) {
            // Batched samples first: finer movement and better fling velocity.
            for (h in 0 until event.historySize) {
                val pointers = List(event.pointerCount) { i ->
                    TouchPointer(event.getPointerId(i), event.getHistoricalX(i, h), event.getHistoricalY(i, h))
                }
                session.gestures.onTouch(TouchEvent(TouchAction.MOVE, pointers, actionId, event.getHistoricalEventTime(h)))
            }
        }
        val pointers = List(event.pointerCount) { i -> TouchPointer(event.getPointerId(i), event.getX(i), event.getY(i)) }
        session.gestures.onTouch(TouchEvent(action, pointers, actionId, event.eventTime))
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> session.pointer.mouseMove(event.x, event.y, buttonsDown = false)
            MotionEvent.ACTION_SCROLL -> session.pointer.mouseWheel(
                event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                event.x,
                event.y,
            )
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = mouseButton(event.actionButton) ?: return true
                session.pointer.mouseButton(button, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS, event.x, event.y)
            }
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }

    private fun mouseButton(androidButton: Int): Int? = when (androidButton) {
        MotionEvent.BUTTON_PRIMARY -> MouseButton.LEFT
        MotionEvent.BUTTON_TERTIARY -> MouseButton.MIDDLE
        MotionEvent.BUTTON_SECONDARY -> MouseButton.RIGHT
        MotionEvent.BUTTON_BACK -> MouseButton.BACK
        MotionEvent.BUTTON_FORWARD -> MouseButton.FORWARD
        else -> null
    }

    // ───────────────────────────── hardware keyboard ─────────────────────────────

    private fun isHardwareKey(event: KeyEvent): Boolean =
        event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && event.isFromSource(InputDevice.SOURCE_KEYBOARD) && event.device?.isVirtual != true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isHardwareKey(event)) return super.onKeyDown(keyCode, event)
        val code = KeyCodes.forKeyEvent(keyCode, event.scanCode) ?: return super.onKeyDown(keyCode, event)
        session.keyboard.hardwareKey(code, down = true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isHardwareKey(event)) return super.onKeyUp(keyCode, event)
        val code = KeyCodes.forKeyEvent(keyCode, event.scanCode) ?: return super.onKeyUp(keyCode, event)
        session.keyboard.hardwareKey(code, down = false)
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Like the desktop's window blur: nothing stays pressed over there.
        if (!hasWindowFocus) session.core.input.releaseAll()
    }

    // ───────────────────────────── soft keyboard ─────────────────────────────

    /** Shows or hides the soft keyboard (the view becomes a text editor while it is wanted). */
    fun setKeyboardVisible(visible: Boolean) {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        keyboardWanted = visible
        if (visible) {
            requestFocus()
            imm.restartInput(this)
            post { imm.showSoftInput(this, 0) }
        } else {
            session.softKeyboard.reset()
            imm.hideSoftInputFromWindow(windowToken, 0)
            imm.restartInput(this)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = keyboardWanted

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!keyboardWanted) return null
        // Every key goes to the computer as it is typed: no suggestions, no
        // learning (passwords are typed here), no full-screen editor.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        session.softKeyboard.reset()
        return RemoteInputConnection(this, session.softKeyboard)
    }
}

/**
 * The editor the input method talks to. Its text is only a buffer for the
 * word being composed: everything is typed on the computer at once (see
 * [SoftKeyboard]) and the buffer is emptied after each commit.
 */
private class RemoteInputConnection(view: View, private val keyboard: SoftKeyboard) : BaseInputConnection(view, true) {
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        keyboard.commitText(text?.toString() ?: "")
        super.commitText(text, newCursorPosition)
        editable?.clear()
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        keyboard.setComposingText(text?.toString() ?: "")
        return super.setComposingText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        keyboard.finishComposingText()
        super.finishComposingText()
        editable?.clear()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        keyboard.deleteSurroundingText(beforeLength, afterLength)
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    @Suppress("DEPRECATION") // ACTION_MULTIPLE: old input methods still send text this way
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyboard.keyDown(event.keyCode, event.getUnicodeChar(event.metaState))
            KeyEvent.ACTION_MULTIPLE -> event.characters?.let { keyboard.commitText(it) }
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        keyboard.editorAction()
        return true
    }
}
