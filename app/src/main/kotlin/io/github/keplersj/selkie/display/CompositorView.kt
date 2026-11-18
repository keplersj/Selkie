package io.github.keplersj.selkie.display

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Shows the compositor's output and sends it touch, mouse and keyboard input (view pixels; the
 * compositor maps them onto the shown client).
 */
@SuppressLint("ViewConstructor")
class CompositorView(context: Context, private val compositor: () -> NativeCompositor?) :
    SurfaceView(context), SurfaceHolder.Callback {

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private var size: Pair<Int, Int>? = null

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        size = width to height
        compositor()?.setSurface(h.surface, width, height)
    }

    /** Hands the current surface to the compositor; for a compositor started after the surface existed. */
    fun attach() {
        val (w, h) = size ?: return
        if (holder.surface?.isValid == true) compositor()?.setSurface(holder.surface, w, h)
    }
    override fun surfaceDestroyed(h: SurfaceHolder) {
        size = null
        compositor()?.setSurface(null, 0, 0)
    }

    private fun send(kind: Int, code: Int = 0, pressed: Boolean = false, x: Float = 0f, y: Float = 0f) =
        compositor()?.input(kind, code, pressed, x, y)

    private var buttons = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(e)
        requestFocus()
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                send(NativeCompositor.TOUCH_DOWN, e.getPointerId(i), x = e.getX(i), y = e.getY(i))
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount)
                send(NativeCompositor.TOUCH_MOTION, e.getPointerId(p), x = e.getX(p), y = e.getY(p))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                send(NativeCompositor.TOUCH_UP, e.getPointerId(i))
            MotionEvent.ACTION_CANCEL -> send(NativeCompositor.TOUCH_CANCEL)
        }
        return true
    }

    private fun mouse(e: MotionEvent): Boolean {
        send(NativeCompositor.POINTER_MOTION, x = e.x, y = e.y)
        val now = e.buttonState
        for ((android, evdev) in listOf(MotionEvent.BUTTON_PRIMARY to KeyMap.BTN_LEFT,
                MotionEvent.BUTTON_SECONDARY to KeyMap.BTN_RIGHT, MotionEvent.BUTTON_TERTIARY to KeyMap.BTN_MIDDLE)) {
            val was = buttons and android != 0
            val is_ = now and android != 0
            if (was != is_) send(NativeCompositor.POINTER_BUTTON, evdev, is_)
        }
        buttons = now
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> send(NativeCompositor.POINTER_MOTION, x = e.x, y = e.y)
            // wl_pointer.axis: about 10 units per wheel notch, positive is down/right.
            MotionEvent.ACTION_SCROLL -> send(NativeCompositor.POINTER_AXIS,
                x = e.getAxisValue(MotionEvent.AXIS_HSCROLL) * 10f, y = -e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 10f)
            else -> return super.onGenericMotionEvent(e)
        }
        return true
    }

    private fun key(e: KeyEvent, pressed: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return false
        val code = KeyMap.evdev(e.keyCode, e.scanCode)
        android.util.Log.i("SelkieKey", "view key ${KeyEvent.keyCodeToString(e.keyCode)} pressed=$pressed evdev=$code")
        code ?: return false
        send(NativeCompositor.KEY, code, pressed)
        return true
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent) = key(e, true) || super.onKeyDown(keyCode, e)
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = key(e, false) || super.onKeyUp(keyCode, e)

    /** Types [text] as key presses (soft keyboard commits). */
    fun type(text: CharSequence) {
        for (c in text) {
            val (code, shift) = KeyMap.forChar(c) ?: continue
            if (shift) send(NativeCompositor.KEY, KeyMap.KEY_LEFTSHIFT, true)
            send(NativeCompositor.KEY, code, true)
            send(NativeCompositor.KEY, code, false)
            if (shift) send(NativeCompositor.KEY, KeyMap.KEY_LEFTSHIFT, false)
        }
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { type(text); return true }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { send(NativeCompositor.KEY, 14, true); send(NativeCompositor.KEY, 14, false) }
                repeat(after) { send(NativeCompositor.KEY, 111, true); send(NativeCompositor.KEY, 111, false) }
                return true
            }
        }
    }
}
