//! JNI entry points for `io.github.keplersj.selkie.display.NativeCompositor` and the
//! `ANativeWindow` frame sink.

use crate::{server, Frame, FrameSink, Input};
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;
use std::ffi::c_void;

#[repr(C)]
struct ANativeWindow {
    _private: [u8; 0],
}

#[repr(C)]
struct ANativeWindowBuffer {
    width: i32,
    height: i32,
    stride: i32,
    format: i32,
    bits: *mut c_void,
    reserved: [u32; 6],
}

/// `WINDOW_FORMAT_RGBX_8888`: bytes R, G, B, X.
const RGBX_8888: i32 = 2;

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(
        env: *mut jni::sys::JNIEnv,
        surface: jni::sys::jobject,
    ) -> *mut ANativeWindow;
    fn ANativeWindow_release(window: *mut ANativeWindow);
    fn ANativeWindow_setBuffersGeometry(
        window: *mut ANativeWindow,
        w: i32,
        h: i32,
        format: i32,
    ) -> i32;
    fn ANativeWindow_lock(
        window: *mut ANativeWindow,
        out: *mut ANativeWindowBuffer,
        dirty: *mut c_void,
    ) -> i32;
    fn ANativeWindow_unlockAndPost(window: *mut ANativeWindow) -> i32;
}

/// Draws frames into a SurfaceView's window. The buffer has the frame's size and the system
/// compositor scales it to the view.
struct WindowSink {
    window: *mut ANativeWindow,
    geometry: (u32, u32),
}

// SAFETY: ANativeWindow is reference counted and usable from any thread; only the compositor
// thread uses it after creation.
unsafe impl Send for WindowSink {}

impl Drop for WindowSink {
    fn drop(&mut self) {
        // SAFETY: we hold the reference acquired by ANativeWindow_fromSurface.
        unsafe { ANativeWindow_release(self.window) };
    }
}

impl FrameSink for WindowSink {
    fn present(&mut self, frame: &Frame) {
        // SAFETY: valid window; the locked buffer is written within its stride and height.
        unsafe {
            if self.geometry != (frame.width, frame.height) {
                if ANativeWindow_setBuffersGeometry(
                    self.window,
                    frame.width as i32,
                    frame.height as i32,
                    RGBX_8888,
                ) != 0
                {
                    return;
                }
                self.geometry = (frame.width, frame.height);
            }
            let mut b: ANativeWindowBuffer = std::mem::zeroed();
            if ANativeWindow_lock(self.window, &mut b, std::ptr::null_mut()) != 0 {
                return;
            }
            let w = (b.width.max(0) as usize).min(frame.width as usize);
            let h = (b.height.max(0) as usize).min(frame.height as usize);
            let dst = b.bits as *mut u32;
            for y in 0..h {
                let src = &frame.pixels[y * frame.width as usize..][..w];
                let row = std::slice::from_raw_parts_mut(dst.add(y * b.stride as usize), w);
                for (d, s) in row.iter_mut().zip(src) {
                    // 0xAARRGGBB -> little-endian R, G, B, X
                    *d = 0xff00_0000 | ((s >> 16) & 0xff) | (s & 0xff00) | ((s & 0xff) << 16);
                }
            }
            ANativeWindow_unlockAndPost(self.window);
        }
    }
}

fn handle<'a>(h: jlong) -> Option<&'a server::Handle> {
    // SAFETY: `h` is a pointer returned by nativeStart and not yet passed to nativeStop.
    (h != 0).then(|| unsafe { &*(h as *const server::Handle) })
}

#[no_mangle]
pub extern "system" fn Java_io_github_keplersj_selkie_display_NativeCompositor_nativeStart<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    socket: JString<'l>,
    width: jint,
    height: jint,
) -> jlong {
    let path: String = match env.get_string(&socket) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    match server::start(
        std::path::Path::new(&path),
        width.max(1) as u32,
        height.max(1) as u32,
        None,
    ) {
        Ok(h) => Box::into_raw(Box::new(h)) as jlong,
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", e.to_string());
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_keplersj_selkie_display_NativeCompositor_nativeSetSurface<
    'l,
>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    surface: JObject<'l>,
    width: jint,
    height: jint,
) {
    let Some(h) = handle(h) else { return };
    if surface.is_null() {
        h.send(server::Command::Sink(None));
        return;
    }
    // SAFETY: a live Surface from the caller; the returned window carries its own reference.
    let window = unsafe { ANativeWindow_fromSurface(env.get_raw(), surface.as_raw()) };
    if window.is_null() {
        h.send(server::Command::Sink(None));
        return;
    }
    h.send(server::Command::Output {
        width: width.max(1) as u32,
        height: height.max(1) as u32,
    });
    h.send(server::Command::Sink(Some(Box::new(WindowSink {
        window,
        geometry: (0, 0),
    }))));
}

/// `kind`: 0 touch down, 1 touch motion, 2 touch up, 3 touch cancel, 4 pointer motion,
/// 5 pointer button (`code` = BTN_*), 6 pointer axis (`x`, `y` = scroll deltas), 7 key (`code` = evdev).
#[no_mangle]
pub extern "system" fn Java_io_github_keplersj_selkie_display_NativeCompositor_nativeInput<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
    kind: jint,
    code: jint,
    pressed: jboolean,
    x: jfloat,
    y: jfloat,
) {
    let Some(h) = handle(h) else { return };
    let (x, y, pressed) = (x as f64, y as f64, pressed == JNI_TRUE);
    let input = match kind {
        0 => Input::TouchDown { id: code, x, y },
        1 => Input::TouchMotion { id: code, x, y },
        2 => Input::TouchUp { id: code },
        3 => Input::TouchCancel,
        4 => Input::PointerMotion { x, y },
        5 => Input::PointerButton {
            button: code as u32,
            pressed,
        },
        6 => Input::PointerAxis { dx: x, dy: y },
        7 => Input::Key {
            code: code as u32,
            pressed,
        },
        _ => return,
    };
    h.send(server::Command::Input(input));
}

#[no_mangle]
pub extern "system" fn Java_io_github_keplersj_selkie_display_NativeCompositor_nativeStats<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) -> jstring {
    let json = handle(h)
        .map(|h| h.stats.lock().unwrap().to_json())
        .unwrap_or_else(|| "null".into());
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_keplersj_selkie_display_NativeCompositor_nativeStop<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    h: jlong,
) {
    if h != 0 {
        // SAFETY: the pointer from nativeStart; the Kotlin side zeroes its copy before calling.
        let h = unsafe { Box::from_raw(h as *mut server::Handle) };
        h.stop();
    }
}
