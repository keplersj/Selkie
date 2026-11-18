//! Selkie's in-app Wayland compositor.
//!
//! Guest programs under proot connect to `$XDG_RUNTIME_DIR/wayland-0` (Selkie's `run/user/<uid>`
//! directory on the host) and their full-screen toplevel is drawn into an Android `Surface`. Clients
//! are nested session compositors (KWin, cosmic-comp, gamescope, ...) or `droidc bridge` for
//! headless ones (gnome-shell); see droidc's `docs/design-mvp.md`, "Graphical session".
//!
//! [`server`] is platform independent (tested on Linux); `android` adds the JNI entry points and the
//! `ANativeWindow` sink.

#[cfg(target_os = "android")]
mod android;
pub mod server;

/// A client frame, `0xAARRGGBB` per pixel (alpha forced to opaque for xrgb buffers), tightly packed.
#[derive(Clone, PartialEq)]
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u32>,
}

impl Frame {
    pub fn summary(&self) -> FrameSummary {
        let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
        let mut colors = std::collections::HashSet::new();
        for (i, p) in self.pixels.iter().enumerate() {
            hash = (hash ^ *p as u64).wrapping_mul(0x100_0000_01b3);
            if i % 97 == 0 && colors.len() < 4096 {
                colors.insert(*p & 0x00ff_ffff);
            }
        }
        FrameSummary {
            width: self.width,
            height: self.height,
            hash,
            sampled_colors: colors.len(),
        }
    }

    /// Binary PPM (P6), for tests and the headless tool.
    pub fn to_ppm(&self) -> Vec<u8> {
        let mut out = format!("P6\n{} {}\n255\n", self.width, self.height).into_bytes();
        for p in &self.pixels {
            out.extend([(p >> 16) as u8, (p >> 8) as u8, *p as u8]);
        }
        out
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct FrameSummary {
    pub width: u32,
    pub height: u32,
    pub hash: u64,
    /// Distinct colours among sampled pixels: 1 means a uniform (blank) frame.
    pub sampled_colors: usize,
}

/// Where frames go: an Android window, memory (tests), files (headless tool).
pub trait FrameSink: Send {
    fn present(&mut self, frame: &Frame);
}

/// Input in view (output) pixel coordinates; key codes are evdev codes, buttons are evdev
/// `BTN_*` codes.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Input {
    TouchDown { id: i32, x: f64, y: f64 },
    TouchMotion { id: i32, x: f64, y: f64 },
    TouchUp { id: i32 },
    TouchCancel,
    PointerMotion { x: f64, y: f64 },
    PointerButton { button: u32, pressed: bool },
    PointerAxis { dx: f64, dy: f64 },
    Key { code: u32, pressed: bool },
}

/// What the compositor is showing; read by tests and Selkie's status/e2e hooks.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Stats {
    pub clients: usize,
    pub toplevels: usize,
    pub commits: u64,
    pub presented: u64,
    pub output: (u32, u32),
    pub frame: Option<FrameSummary>,
}

impl Stats {
    pub fn to_json(&self) -> String {
        let f = match &self.frame {
            Some(f) => format!(
                "{{\"width\":{},\"height\":{},\"hash\":\"{:016x}\",\"sampled_colors\":{}}}",
                f.width, f.height, f.hash, f.sampled_colors
            ),
            None => "null".into(),
        };
        format!(
            "{{\"clients\":{},\"toplevels\":{},\"commits\":{},\"presented\":{},\"output\":[{},{}],\"frame\":{}}}",
            self.clients, self.toplevels, self.commits, self.presented, self.output.0, self.output.1, f
        )
    }
}

/// Keeps the latest frame in memory.
#[derive(Clone, Default)]
pub struct MemorySink(pub std::sync::Arc<std::sync::Mutex<Option<Frame>>>);

impl FrameSink for MemorySink {
    fn present(&mut self, frame: &Frame) {
        *self.0.lock().unwrap() = Some(frame.clone());
    }
}
