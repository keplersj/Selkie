package io.github.keplersj.selkie.display

/**
 * JNI binding for `libselkie_compositor.so` (`native/selkie-compositor`): Selkie's in-app Wayland
 * compositor. Guest clients connect to its socket; the shown toplevel is drawn into the Surface given
 * to [setSurface].
 */
class NativeCompositor private constructor(@Volatile private var handle: Long) {
    val running: Boolean get() = handle != 0L

    /** The Surface to draw into (null: none, clients pause) and the view size in pixels. */
    fun setSurface(surface: android.view.Surface?, width: Int, height: Int) {
        if (handle != 0L) nativeSetSurface(handle, surface, width, height)
    }

    fun input(kind: Int, code: Int = 0, pressed: Boolean = false, x: Float = 0f, y: Float = 0f) {
        if (handle != 0L) nativeInput(handle, kind, code, pressed, x, y)
    }

    /** JSON: clients, toplevels, commits, presented, output, frame {width, height, hash, sampled_colors}. */
    fun stats(): String = if (handle != 0L) nativeStats(handle) else "null"

    @Synchronized fun stop() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeStop(h)
    }

    companion object {
        const val TOUCH_DOWN = 0
        const val TOUCH_MOTION = 1
        const val TOUCH_UP = 2
        const val TOUCH_CANCEL = 3
        const val POINTER_MOTION = 4
        const val POINTER_BUTTON = 5
        const val POINTER_AXIS = 6
        const val KEY = 7

        init { System.loadLibrary("selkie_compositor") }

        /** Listens on [socket] (a stale socket is replaced). Throws IOException if it can't. */
        fun start(socket: java.io.File, width: Int, height: Int): NativeCompositor {
            val h = nativeStart(socket.path, width, height)
            if (h == 0L) throw java.io.IOException("compositor did not start on $socket")
            return NativeCompositor(h)
        }

        /**
         * Like [start], in app mode: one Linux app shown on its own at [scale] pixels per logical
         * pixel (pass the display density), with its menus and popovers.
         */
        fun startApp(socket: java.io.File, width: Int, height: Int, scale: Float): NativeCompositor {
            val h = nativeStartApp(socket.path, width, height, scale)
            if (h == 0L) throw java.io.IOException("compositor did not start on $socket")
            return NativeCompositor(h)
        }

        @JvmStatic private external fun nativeStart(socket: String, width: Int, height: Int): Long
        @JvmStatic private external fun nativeStartApp(socket: String, width: Int, height: Int, scale: Float): Long
        @JvmStatic private external fun nativeSetSurface(handle: Long, surface: android.view.Surface?, width: Int, height: Int)
        @JvmStatic private external fun nativeInput(handle: Long, kind: Int, code: Int, pressed: Boolean, x: Float, y: Float)
        @JvmStatic private external fun nativeStats(handle: Long): String
        @JvmStatic private external fun nativeStop(handle: Long)
    }
}
