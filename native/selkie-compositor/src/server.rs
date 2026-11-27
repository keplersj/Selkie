//! The Wayland server: just enough of the core protocol and xdg-shell for full-screen clients
//! (a nested session compositor such as KWin, cosmic-comp or gamescope, or `droidc bridge`).
//!
//! One toplevel is shown at a time, full screen at the output size: the most recently mapped one.
//! Its buffer is copied on commit (wl_shm only) and handed to a [`FrameSink`]; the buffer is released
//! right away. Frame callbacks complete after the sink has shown the frame, so clients render at the
//! sink's pace and stop while there is nowhere to draw.
//!
//! App mode ([`start_app`]) is for one ordinary app shown on its own instead: the output has a
//! scale (`wl_output.scale`, `wl_surface.preferred_buffer_scale`, `wp_fractional_scale_v1` with
//! `wp_viewporter`), the toplevel is configured maximized at the output's logical size, and its
//! subsurfaces and popups (menus, popovers) are drawn with it into one output-sized frame. A touch or
//! click outside a grabbing popup dismisses it.

use crate::scene::{self, Layer, Positioner, Rect};
use crate::{Frame, FrameSink, Input, Stats};
use std::collections::HashMap;
use std::os::fd::{AsFd, OwnedFd};
use std::path::Path;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Instant;
use wayland_protocols::wp::fractional_scale::v1::server::{
    wp_fractional_scale_manager_v1, wp_fractional_scale_v1,
};
use wayland_protocols::wp::viewporter::server::{wp_viewport, wp_viewporter};
use wayland_protocols::xdg::shell::server::{
    xdg_popup, xdg_positioner, xdg_surface, xdg_toplevel, xdg_wm_base,
};
use wayland_server::backend::{ClientData, ClientId, DisconnectReason, ObjectId};
use wayland_server::protocol::{
    wl_buffer, wl_callback, wl_compositor, wl_data_device, wl_data_device_manager, wl_data_source,
    wl_keyboard, wl_output, wl_pointer, wl_region, wl_seat, wl_shm, wl_shm_pool, wl_subcompositor,
    wl_subsurface, wl_surface, wl_touch,
};
use wayland_server::{
    Client, DataInit, Dispatch, Display, DisplayHandle, GlobalDispatch, ListeningSocket, New,
    Resource, WEnum,
};

const KEYMAP: &str = include_str!("us.xkb");

/// Commands from the platform side to the compositor thread.
pub enum Command {
    /// Output size in pixels (the Android view); toplevels are reconfigured to it.
    Output {
        width: u32,
        height: u32,
    },
    Sink(Option<Box<dyn FrameSink>>),
    Input(Input),
    Stop,
}

/// A running compositor: its thread listens on `socket` until [`Handle::stop`].
pub struct Handle {
    tx: Sender<Command>,
    wake: OwnedFd,
    thread: Option<std::thread::JoinHandle<()>>,
    pub stats: Arc<Mutex<Stats>>,
}

impl Handle {
    pub fn send(&self, c: Command) {
        if self.tx.send(c).is_ok() {
            let _ = rustix::io::write(&self.wake, &[1]);
        }
    }

    pub fn stop(mut self) {
        self.send(Command::Stop);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

impl Drop for Handle {
    fn drop(&mut self) {
        if let Some(t) = self.thread.take() {
            let _ = self.tx.send(Command::Stop);
            let _ = rustix::io::write(&self.wake, &[1]);
            let _ = t.join();
        }
    }
}

/// Binds `socket` (an absolute path; a stale socket left by a crashed run is replaced) and starts the
/// compositor thread.
pub fn start(
    socket: &Path,
    width: u32,
    height: u32,
    sink: Option<Box<dyn FrameSink>>,
) -> std::io::Result<Handle> {
    start_with(socket, width, height, None, sink)
}

/// Like [`start`], in app mode: one app shown on its own at `scale` output pixels per logical pixel
/// (Android's density, so the app sees about as many logical pixels as an Android app sees dp).
pub fn start_app(
    socket: &Path,
    width: u32,
    height: u32,
    scale: f64,
    sink: Option<Box<dyn FrameSink>>,
) -> std::io::Result<Handle> {
    let scale = if scale.is_finite() && scale > 0.0 {
        scale.clamp(1.0, 8.0)
    } else {
        1.0
    };
    start_with(socket, width, height, Some(scale), sink)
}

fn start_with(
    socket: &Path,
    width: u32,
    height: u32,
    app: Option<f64>,
    sink: Option<Box<dyn FrameSink>>,
) -> std::io::Result<Handle> {
    let listener = ListeningSocket::bind_absolute(socket.to_path_buf())
        .map_err(|e| std::io::Error::other(format!("{}: {e}", socket.display())))?;
    let display: Display<State> = Display::new().map_err(std::io::Error::other)?;
    let (tx, rx) = std::sync::mpsc::channel();
    let (wake_r, wake_w) = rustix::pipe::pipe_with(
        rustix::pipe::PipeFlags::CLOEXEC | rustix::pipe::PipeFlags::NONBLOCK,
    )?;
    let stats = Arc::new(Mutex::new(Stats::default()));
    let st = stats.clone();
    let thread = std::thread::Builder::new()
        .name("selkie-compositor".into())
        .spawn(move || run(display, listener, rx, wake_r, width, height, app, sink, st))?;
    Ok(Handle {
        tx,
        wake: wake_w,
        thread: Some(thread),
        stats,
    })
}

struct ClientState;
impl ClientData for ClientState {
    fn initialized(&self, _: ClientId) {}
    fn disconnected(&self, _: ClientId, _: DisconnectReason) {}
}

#[allow(clippy::too_many_arguments)]
fn run(
    mut display: Display<State>,
    listener: ListeningSocket,
    rx: Receiver<Command>,
    wake: OwnedFd,
    width: u32,
    height: u32,
    app: Option<f64>,
    sink: Option<Box<dyn FrameSink>>,
    stats: Arc<Mutex<Stats>>,
) {
    let dh = display.handle();
    // Version 6 adds wl_surface.preferred_buffer_scale, which only app mode sends.
    dh.create_global::<State, wl_compositor::WlCompositor, ()>(
        if app.is_some() { 6 } else { 5 },
        (),
    );
    dh.create_global::<State, wl_subcompositor::WlSubcompositor, ()>(1, ());
    dh.create_global::<State, wl_shm::WlShm, ()>(1, ());
    dh.create_global::<State, wl_output::WlOutput, ()>(4, ());
    dh.create_global::<State, wl_seat::WlSeat, ()>(7, ());
    dh.create_global::<State, xdg_wm_base::XdgWmBase, ()>(5, ());
    dh.create_global::<State, wl_data_device_manager::WlDataDeviceManager, ()>(3, ());
    if app.is_some() {
        dh.create_global::<State, wp_viewporter::WpViewporter, ()>(1, ());
        dh.create_global::<State, wp_fractional_scale_manager_v1::WpFractionalScaleManagerV1, ()>(
            1,
            (),
        );
    }
    let mut state = State::new(width, height, sink, stats);
    state.app = app;
    loop {
        let mut fds = [
            rustix::event::PollFd::new(&listener, rustix::event::PollFlags::IN),
            rustix::event::PollFd::new(&wake, rustix::event::PollFlags::IN),
            rustix::event::PollFd::from_borrowed_fd(
                display.backend().poll_fd(),
                rustix::event::PollFlags::IN,
            ),
        ];
        match rustix::event::poll(&mut fds, None) {
            Ok(_) | Err(rustix::io::Errno::INTR) => {}
            Err(e) => {
                eprintln!("selkie-compositor: poll: {e}");
                return;
            }
        }
        let new_client = !fds[0].revents().is_empty();
        if new_client {
            while let Ok(Some(stream)) = listener.accept() {
                if let Err(e) = dh.clone().insert_client(stream, Arc::new(ClientState)) {
                    eprintln!("selkie-compositor: client: {e}");
                }
            }
        }
        let mut b = [0u8; 64];
        while matches!(rustix::io::read(&wake, &mut b), Ok(n) if n > 0) {}
        if let Err(e) = display.dispatch_clients(&mut state) {
            eprintln!("selkie-compositor: dispatch: {e}");
        }
        for c in rx.try_iter() {
            match c {
                Command::Stop => {
                    let _ = display.flush_clients();
                    return;
                }
                Command::Output { width, height } => state.set_output(width, height),
                Command::Sink(s) => state.set_sink(s),
                Command::Input(i) => state.input(i),
            }
        }
        state.update_stats();
        let _ = display.flush_clients();
    }
}

/// A wl_shm_pool mapping, shared by its buffers (which outlive the pool object).
struct Pool {
    fd: OwnedFd,
    ptr: *mut u8,
    len: usize,
}
// SAFETY: the mapping is only touched from the compositor thread.
unsafe impl Send for Pool {}
unsafe impl Sync for Pool {}

impl Pool {
    fn map(fd: OwnedFd, len: usize) -> Option<Pool> {
        // SAFETY: read-only shared mapping of a client-provided fd; contents may change under us,
        // which only affects pixels.
        let ptr = unsafe {
            rustix::mm::mmap(
                std::ptr::null_mut(),
                len,
                rustix::mm::ProtFlags::READ,
                rustix::mm::MapFlags::SHARED,
                &fd,
                0,
            )
        }
        .ok()? as *mut u8;
        Some(Pool { fd, ptr, len })
    }
}

impl Drop for Pool {
    fn drop(&mut self) {
        // SAFETY: unmapping our own mapping.
        unsafe {
            let _ = rustix::mm::munmap(self.ptr as *mut _, self.len);
        }
    }
}

struct BufferInfo {
    pool: Arc<Mutex<Pool>>,
    offset: usize,
    width: u32,
    height: u32,
    stride: usize,
    format: wl_shm::Format,
}

#[derive(Default)]
struct Surface {
    resource: Option<wl_surface::WlSurface>,
    pending_buffer: Option<Option<wl_buffer::WlBuffer>>,
    pending_callbacks: Vec<wl_callback::WlCallback>,
    toplevel: Option<ObjectId>,
    mapped: bool,
    // App mode only (session mode reads the toplevel's buffer straight into the shown frame).
    /// The committed buffer's pixels.
    content: Option<Arc<Frame>>,
    buffer_scale: i32,
    pending_scale: Option<i32>,
    /// wp_viewport source (buffer pixels) and destination (logical size).
    vp_src: Option<(f64, f64, f64, f64)>,
    vp_dst: Option<(i32, i32)>,
    pending_vp_src: Option<Option<(f64, f64, f64, f64)>>,
    pending_vp_dst: Option<Option<(i32, i32)>>,
    /// xdg_surface window geometry (surface-local).
    geometry: Option<Rect>,
    pending_geometry: Option<Rect>,
    /// As a subsurface: its parent and position relative to the parent's origin.
    sub_parent: Option<ObjectId>,
    sub_pos: (i32, i32),
    pending_pos: Option<(i32, i32)>,
    /// Subsurfaces stacked below and above this surface, bottom to top.
    below: Vec<ObjectId>,
    above: Vec<ObjectId>,
    /// The xdg_popup this surface is, if any.
    popup: Option<ObjectId>,
    entered: bool,
}

impl Surface {
    /// Logical size of the committed content.
    fn size(&self) -> Option<(i32, i32)> {
        let c = self.content.as_ref()?;
        Some(match (self.vp_dst, self.vp_src) {
            (Some(d), _) => d,
            (None, Some((_, _, w, h))) => (w as i32, h as i32),
            (None, None) => {
                let s = self.buffer_scale.max(1);
                (c.width as i32 / s, c.height as i32 / s)
            }
        })
    }

    fn geometry_origin(&self) -> (i32, i32) {
        self.geometry.map(|g| (g.x, g.y)).unwrap_or((0, 0))
    }
}

/// An xdg_popup (a menu, popover or tooltip), placed relative to its parent's window geometry.
struct Popup {
    popup: xdg_popup::XdgPopup,
    xdg_surface: xdg_surface::XdgSurface,
    surface: ObjectId,
    parent: ObjectId,
    positioner: Positioner,
    rect: Rect,
    grab: bool,
}

struct Toplevel {
    toplevel: xdg_toplevel::XdgToplevel,
    xdg_surface: xdg_surface::XdgSurface,
}

pub struct State {
    started: Instant,
    serial: u32,
    width: u32,
    height: u32,
    sink: Option<Box<dyn FrameSink>>,
    stats: Arc<Mutex<Stats>>,
    surfaces: HashMap<ObjectId, Surface>,
    toplevels: Vec<Toplevel>,
    buffers: HashMap<ObjectId, BufferInfo>,
    pools: HashMap<ObjectId, Arc<Mutex<Pool>>>,
    outputs: Vec<wl_output::WlOutput>,
    pointers: Vec<wl_pointer::WlPointer>,
    keyboards: Vec<wl_keyboard::WlKeyboard>,
    touches: Vec<wl_touch::WlTouch>,
    /// Surface of the shown toplevel.
    focus: Option<ObjectId>,
    pointer_inside: bool,
    frame: Option<Frame>,
    /// Summary of the last frame shown (kept when the surface goes away).
    summary: Option<crate::FrameSummary>,
    /// Frame callbacks of the shown surface, completed once its frame is shown.
    waiting: Vec<wl_callback::WlCallback>,
    mods_depressed: u32,
    mods_locked: u32,
    commits: u64,
    presented: u64,
    inputs: u64,
    /// App mode: output pixels per logical pixel.
    app: Option<f64>,
    positioners: HashMap<ObjectId, Positioner>,
    /// Open popups, bottom to top.
    popups: Vec<Popup>,
    /// App mode: the surface with keyboard focus (the topmost grabbing popup, else the toplevel).
    kb_focus: Option<ObjectId>,
    /// App mode: the surface under the pointer, with its origin in logical output coordinates.
    pointer_target: Option<Target>,
    /// App mode: where each touch went (`None`: it dismissed popups and is swallowed).
    touch_targets: HashMap<i32, Option<Target>>,
    /// App mode: a press dismissed popups, so its release is swallowed too.
    swallow_release: bool,
    /// App mode: last pointer position, logical.
    pointer_pos: (f64, f64),
}

impl State {
    fn new(
        width: u32,
        height: u32,
        sink: Option<Box<dyn FrameSink>>,
        stats: Arc<Mutex<Stats>>,
    ) -> State {
        State {
            started: Instant::now(),
            serial: 0,
            width: width.max(1),
            height: height.max(1),
            sink,
            stats,
            surfaces: HashMap::new(),
            toplevels: Vec::new(),
            buffers: HashMap::new(),
            pools: HashMap::new(),
            outputs: Vec::new(),
            pointers: Vec::new(),
            keyboards: Vec::new(),
            touches: Vec::new(),
            focus: None,
            pointer_inside: false,
            frame: None,
            summary: None,
            waiting: Vec::new(),
            mods_depressed: 0,
            mods_locked: 0,
            commits: 0,
            presented: 0,
            inputs: 0,
            app: None,
            positioners: HashMap::new(),
            popups: Vec::new(),
            kb_focus: None,
            pointer_target: None,
            touch_targets: HashMap::new(),
            swallow_release: false,
            pointer_pos: (0.0, 0.0),
        }
    }

    /// wl_output.scale: the app-mode scale rounded up (1 in session mode).
    fn int_scale(&self) -> i32 {
        self.app.map(|s| s.ceil() as i32).unwrap_or(1).max(1)
    }

    fn next_serial(&mut self) -> u32 {
        self.serial = self.serial.wrapping_add(1);
        self.serial
    }

    fn time(&self) -> u32 {
        self.started.elapsed().as_millis() as u32
    }

    fn update_stats(&self) {
        let mut s = self.stats.lock().unwrap();
        s.clients = self
            .surfaces
            .values()
            .filter_map(|s| s.resource.as_ref())
            .filter_map(|r| r.client())
            .map(|c| c.id())
            .collect::<std::collections::HashSet<_>>()
            .len();
        s.toplevels = self.toplevels.len();
        s.commits = self.commits;
        s.presented = self.presented;
        s.inputs = self.inputs;
        s.output = (self.width, self.height);
        s.frame = self.summary;
    }

    fn set_output(&mut self, width: u32, height: u32) {
        if (width, height) == (self.width, self.height) || width == 0 || height == 0 {
            return;
        }
        self.width = width;
        self.height = height;
        let scale = self.int_scale();
        for o in self.outputs.clone() {
            send_output(&o, width, height, scale);
        }
        for i in 0..self.toplevels.len() {
            self.configure(i);
        }
        if self.app.is_some() {
            self.dismiss_popups(0);
            self.recompose();
        }
    }

    fn set_sink(&mut self, sink: Option<Box<dyn FrameSink>>) {
        self.sink = sink;
        if self.sink.is_some() {
            self.show();
        }
    }

    fn configure(&mut self, i: usize) {
        let serial = self.next_serial();
        let t = &self.toplevels[i];
        if let Some(scale) = self.app {
            // Maximized (1), activated (4) and tiled on every edge (5-8): no shadows or rounded
            // corners, and adaptive apps lay out for the logical size (a phone's is ~400 wide).
            let (w, h) = scene::logical_size(self.width, self.height, scale);
            let states: Vec<u8> = [1u32, 4, 5, 6, 7, 8]
                .iter()
                .filter(|s| **s < 5 || t.toplevel.version() >= 2)
                .flat_map(|s| s.to_ne_bytes())
                .collect();
            t.toplevel.configure(w, h, states);
            t.xdg_surface.configure(serial);
            return;
        }
        // xdg_toplevel states: 2 = fullscreen, 4 = activated.
        let states: Vec<u8> = [2u32, 4].iter().flat_map(|s| s.to_ne_bytes()).collect();
        t.toplevel
            .configure(self.width as i32, self.height as i32, states);
        t.xdg_surface.configure(serial);
    }

    /// Shows the current frame on the sink and completes the waiting frame callbacks.
    fn show(&mut self) {
        let (Some(sink), Some(frame)) = (self.sink.as_mut(), self.frame.as_ref()) else {
            return;
        };
        sink.present(frame);
        self.presented += 1;
        self.summary = Some(frame.summary());
        let t = self.time();
        for cb in self.waiting.drain(..) {
            cb.done(t);
        }
    }

    fn commit(&mut self, id: &ObjectId) {
        if self.app.is_some() {
            return self.app_commit(id);
        }
        self.commits += 1;
        let Some(surf) = self.surfaces.get_mut(id) else {
            return;
        };
        let callbacks = std::mem::take(&mut surf.pending_callbacks);
        let pending = surf.pending_buffer.take();
        let is_toplevel = surf.toplevel.is_some();
        let mut new_frame = None;
        let mut unmapped = false;
        let mut first_map = false;
        match pending {
            Some(Some(buf)) => {
                if is_toplevel {
                    new_frame = self.buffers.get(&buf.id()).and_then(read_frame);
                    if new_frame.is_some() && !surf.mapped {
                        surf.mapped = true;
                        first_map = true;
                    }
                }
                buf.release();
            }
            Some(None) => {
                unmapped = surf.mapped;
                surf.mapped = false;
            }
            None => {}
        }
        if first_map {
            self.set_focus(Some(id.clone()));
        }
        if unmapped && self.focus.as_ref() == Some(id) {
            let next = self
                .surfaces
                .iter()
                .find(|(k, s)| s.mapped && *k != id)
                .map(|(k, _)| k.clone());
            self.set_focus(next);
        }
        if self.focus.as_ref() == Some(id) {
            self.waiting.extend(callbacks);
            if let Some(f) = new_frame {
                self.frame = Some(f);
                self.show();
            }
        } else {
            // Not shown: let it keep running.
            let t = self.time();
            for cb in callbacks {
                cb.done(t);
            }
        }
    }

    fn focused_surface(&self) -> Option<wl_surface::WlSurface> {
        self.focus
            .as_ref()
            .and_then(|f| self.surfaces.get(f))
            .and_then(|s| s.resource.clone())
    }

    fn same_client<R: Resource>(r: &R, s: &wl_surface::WlSurface) -> bool {
        matches!((r.client(), s.client()), (Some(a), Some(b)) if a.id() == b.id())
    }

    fn set_focus(&mut self, focus: Option<ObjectId>) {
        if focus == self.focus {
            return;
        }
        if self.app.is_some() {
            return self.app_set_focus(focus);
        }
        if let Some(old) = self.focused_surface() {
            let serial = self.next_serial();
            for k in self
                .keyboards
                .iter()
                .filter(|k| Self::same_client(*k, &old))
            {
                k.leave(serial, &old);
            }
            if self.pointer_inside {
                for p in self.pointers.iter().filter(|p| Self::same_client(*p, &old)) {
                    p.leave(serial, &old);
                }
            }
        }
        self.pointer_inside = false;
        let t = self.time();
        for cb in self.waiting.drain(..) {
            cb.done(t);
        }
        self.focus = focus;
        self.frame = None;
        if let Some(new) = self.focused_surface() {
            let serial = self.next_serial();
            for k in self
                .keyboards
                .iter()
                .filter(|k| Self::same_client(*k, &new))
            {
                k.enter(serial, &new, Vec::new());
                k.modifiers(serial, self.mods_depressed, 0, self.mods_locked, 0);
            }
        }
    }

    /// Maps view coordinates to coordinates on the shown surface (the frame is stretched to the view).
    fn to_surface(&self, x: f64, y: f64) -> (f64, f64) {
        match &self.frame {
            Some(f) if crate::unscaled((f.width, f.height), (self.width, self.height)) => (
                x.min(f.width as f64 - 1.0).max(0.0),
                y.min(f.height as f64 - 1.0).max(0.0),
            ),
            Some(f) => (
                x * f.width as f64 / self.width as f64,
                y * f.height as f64 / self.height as f64,
            ),
            None => (x, y),
        }
    }

    fn input(&mut self, i: Input) {
        if self.app.is_some() {
            return self.app_input(i);
        }
        let Some(surface) = self.focused_surface() else {
            return;
        };
        self.inputs += 1;
        let t = self.time();
        match i {
            Input::TouchDown { id, x, y } => {
                let (x, y) = self.to_surface(x, y);
                let serial = self.next_serial();
                for tch in self
                    .touches
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    tch.down(serial, t, &surface, id, x, y);
                    tch.frame();
                }
            }
            Input::TouchMotion { id, x, y } => {
                let (x, y) = self.to_surface(x, y);
                for tch in self
                    .touches
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    tch.motion(t, id, x, y);
                    tch.frame();
                }
            }
            Input::TouchUp { id } => {
                let serial = self.next_serial();
                for tch in self
                    .touches
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    tch.up(serial, t, id);
                    tch.frame();
                }
            }
            Input::TouchCancel => {
                for tch in self
                    .touches
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    tch.cancel();
                }
            }
            Input::PointerMotion { x, y } => {
                let (x, y) = self.to_surface(x, y);
                let enter = !self.pointer_inside;
                let serial = if enter { self.next_serial() } else { 0 };
                self.pointer_inside = true;
                for p in self
                    .pointers
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    if enter {
                        p.enter(serial, &surface, x, y);
                    } else {
                        p.motion(t, x, y);
                    }
                    if p.version() >= 5 {
                        p.frame();
                    }
                }
            }
            Input::PointerButton { button, pressed } => {
                let serial = self.next_serial();
                let st = if pressed {
                    wl_pointer::ButtonState::Pressed
                } else {
                    wl_pointer::ButtonState::Released
                };
                for p in self
                    .pointers
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    p.button(serial, t, button, st);
                    if p.version() >= 5 {
                        p.frame();
                    }
                }
            }
            Input::PointerAxis { dx, dy } => {
                for p in self
                    .pointers
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    if dy != 0.0 {
                        p.axis(t, wl_pointer::Axis::VerticalScroll, dy);
                    }
                    if dx != 0.0 {
                        p.axis(t, wl_pointer::Axis::HorizontalScroll, dx);
                    }
                    if p.version() >= 5 {
                        p.frame();
                    }
                }
            }
            Input::Key { code, pressed } => {
                let serial = self.next_serial();
                let st = if pressed {
                    wl_keyboard::KeyState::Pressed
                } else {
                    wl_keyboard::KeyState::Released
                };
                for k in self
                    .keyboards
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    k.key(serial, t, code, st);
                }
                if self.update_mods(code, pressed) {
                    let serial = self.next_serial();
                    for k in self
                        .keyboards
                        .iter()
                        .filter(|r| Self::same_client(*r, &surface))
                    {
                        k.modifiers(serial, self.mods_depressed, 0, self.mods_locked, 0);
                    }
                }
            }
        }
    }

    /// Tracks the modifier masks of the bundled keymap (Shift, Lock, Control, Mod1 = Alt,
    /// Mod4 = Super). Returns whether they changed.
    fn update_mods(&mut self, code: u32, pressed: bool) -> bool {
        let mask = match code {
            42 | 54 => 1,    // KEY_LEFTSHIFT, KEY_RIGHTSHIFT
            29 | 97 => 4,    // KEY_LEFTCTRL, KEY_RIGHTCTRL
            56 | 100 => 8,   // KEY_LEFTALT, KEY_RIGHTALT
            125 | 126 => 64, // KEY_LEFTMETA, KEY_RIGHTMETA
            58 => {
                // KEY_CAPSLOCK toggles Lock on press.
                if pressed {
                    self.mods_locked ^= 2;
                    return true;
                }
                return false;
            }
            _ => return false,
        };
        let before = self.mods_depressed;
        if pressed {
            self.mods_depressed |= mask;
        } else {
            self.mods_depressed &= !mask;
        }
        before != self.mods_depressed
    }
}

// ---- app mode ----

type Target = (wl_surface::WlSurface, (f64, f64));

impl State {
    fn app_commit(&mut self, id: &ObjectId) {
        self.commits += 1;
        let Some(surf) = self.surfaces.get_mut(id) else {
            return;
        };
        if let Some(s) = surf.pending_scale.take() {
            surf.buffer_scale = s.max(1);
        }
        if let Some(v) = surf.pending_vp_src.take() {
            surf.vp_src = v;
        }
        if let Some(v) = surf.pending_vp_dst.take() {
            surf.vp_dst = v;
        }
        if let Some(g) = surf.pending_geometry.take() {
            surf.geometry = Some(g);
        }
        if let Some(p) = surf.pending_pos.take() {
            surf.sub_pos = p;
        }
        let callbacks = std::mem::take(&mut surf.pending_callbacks);
        let mut first_map = false;
        let mut unmapped = false;
        let mut enter = false;
        match surf.pending_buffer.take() {
            Some(Some(buf)) => {
                if let Some(f) = self.buffers.get(&buf.id()).and_then(read_frame) {
                    surf.content = Some(Arc::new(f));
                    if surf.toplevel.is_some() && !surf.mapped {
                        surf.mapped = true;
                        first_map = true;
                    }
                    if !surf.entered {
                        surf.entered = true;
                        enter = true;
                    }
                }
                buf.release();
            }
            Some(None) => {
                surf.content = None;
                unmapped = surf.mapped;
                surf.mapped = false;
            }
            None => {}
        }
        if enter {
            if let Some(r) = surf.resource.clone() {
                for o in self.outputs.iter().filter(|o| Self::same_client(*o, &r)) {
                    r.enter(o);
                }
            }
        }
        if first_map {
            // The most recently mapped toplevel is shown.
            self.set_focus(Some(id.clone()));
        }
        if unmapped && self.focus.as_ref() == Some(id) {
            let next = self
                .surfaces
                .iter()
                .find(|(k, s)| s.mapped && *k != id)
                .map(|(k, _)| k.clone());
            self.set_focus(next);
        }
        if self.is_shown(id) {
            self.waiting.extend(callbacks);
            self.recompose();
        } else {
            let t = self.time();
            for cb in callbacks {
                cb.done(t);
            }
        }
    }

    /// Whether `id` is drawn: the shown toplevel, or a subsurface or popup of it.
    fn is_shown(&self, id: &ObjectId) -> bool {
        let mut cur = id.clone();
        for _ in 0..64 {
            if self.focus.as_ref() == Some(&cur) {
                return true;
            }
            let Some(s) = self.surfaces.get(&cur) else {
                return false;
            };
            if let Some(p) = &s.sub_parent {
                cur = p.clone();
            } else if let Some(p) = self.popup_of(&cur) {
                cur = p.parent.clone();
            } else {
                return false;
            }
        }
        false
    }

    fn popup_of(&self, surface: &ObjectId) -> Option<&Popup> {
        self.popups.iter().find(|p| &p.surface == surface)
    }

    /// Where `id`'s window geometry starts, in logical output coordinates (the toplevel's is the
    /// output's corner; a popup's is its parent's plus its placement).
    fn geometry_origin(&self, id: &ObjectId, depth: u32) -> (i32, i32) {
        if depth > 32 {
            return (0, 0);
        }
        match self.popup_of(id) {
            Some(p) => {
                let (x, y) = self.geometry_origin(&p.parent, depth + 1);
                (x + p.rect.x, y + p.rect.y)
            }
            None => (0, 0),
        }
    }

    /// Where `id`'s surface origin is, in logical output coordinates.
    fn surface_origin(&self, id: &ObjectId) -> (i32, i32) {
        let (x, y) = self.geometry_origin(id, 0);
        let (gx, gy) = self
            .surfaces
            .get(id)
            .map(|s| s.geometry_origin())
            .unwrap_or((0, 0));
        (x - gx, y - gy)
    }

    fn push_layers(&self, id: &ObjectId, origin: (i32, i32), layers: &mut Vec<Layer>, depth: u32) {
        let Some(s) = self.surfaces.get(id) else {
            return;
        };
        let (Some(content), Some((w, h))) = (s.content.clone(), s.size()) else {
            return;
        };
        if depth > 32 {
            return;
        }
        let child = |c: &ObjectId, layers: &mut Vec<Layer>| {
            let pos = self.surfaces.get(c).map(|c| c.sub_pos).unwrap_or((0, 0));
            self.push_layers(c, (origin.0 + pos.0, origin.1 + pos.1), layers, depth + 1);
        };
        for c in &s.below {
            child(c, layers);
        }
        layers.push(Layer {
            content,
            dst: Rect::new(origin.0, origin.1, w, h),
            src: s.vp_src,
        });
        for c in &s.above {
            child(c, layers);
        }
    }

    /// Draws the shown toplevel and its popups into one output-sized frame and shows it.
    fn recompose(&mut self) {
        let Some(scale) = self.app else {
            return;
        };
        let Some(focus) = self.focus.clone() else {
            return;
        };
        let mut layers = Vec::new();
        self.push_layers(&focus, self.surface_origin(&focus), &mut layers, 0);
        if layers.is_empty() {
            return;
        }
        for p in &self.popups {
            if self.is_shown(&p.surface) {
                self.push_layers(&p.surface, self.surface_origin(&p.surface), &mut layers, 0);
            }
        }
        self.frame = Some(scene::compose(self.width, self.height, scale, &layers));
        self.show();
    }

    fn app_set_focus(&mut self, focus: Option<ObjectId>) {
        self.dismiss_popups(0);
        let t = self.time();
        for cb in self.waiting.drain(..) {
            cb.done(t);
        }
        self.set_pointer_target(None, 0.0, 0.0);
        self.touch_targets.clear();
        self.focus = focus;
        self.frame = None;
        self.update_kb_focus();
        self.recompose();
    }

    /// Keyboard focus follows the topmost grabbing popup, else the shown toplevel.
    fn update_kb_focus(&mut self) {
        let want = self
            .popups
            .iter()
            .rev()
            .find(|p| p.grab && self.is_shown(&p.surface))
            .map(|p| p.surface.clone())
            .or_else(|| self.focus.clone());
        if want == self.kb_focus {
            return;
        }
        let res = |st: &Self, id: &Option<ObjectId>| {
            id.as_ref()
                .and_then(|i| st.surfaces.get(i))
                .and_then(|s| s.resource.clone())
        };
        if let Some(old) = res(self, &self.kb_focus) {
            let serial = self.next_serial();
            for k in self
                .keyboards
                .iter()
                .filter(|k| Self::same_client(*k, &old))
            {
                k.leave(serial, &old);
            }
        }
        self.kb_focus = want;
        if let Some(new) = res(self, &self.kb_focus) {
            let serial = self.next_serial();
            for k in self
                .keyboards
                .iter()
                .filter(|k| Self::same_client(*k, &new))
            {
                k.enter(serial, &new, Vec::new());
                k.modifiers(serial, self.mods_depressed, 0, self.mods_locked, 0);
            }
        }
    }

    /// Sends popup_done to the popups from the top down to (not including) index `keep`.
    fn dismiss_popups(&mut self, keep: usize) {
        if self.popups.len() <= keep {
            return;
        }
        let gone: Vec<Popup> = self.popups.drain(keep..).collect();
        for p in gone.iter().rev() {
            p.popup.popup_done();
            if let Some(s) = self.surfaces.get_mut(&p.surface) {
                s.popup = None;
            }
        }
        if let Some((t, _)) = &self.pointer_target {
            if gone.iter().any(|p| p.surface == t.id()) {
                self.set_pointer_target(None, 0.0, 0.0);
            }
        }
        self.update_kb_focus();
        self.recompose();
    }

    /// The surface under logical point (x, y), with its origin: popups top to bottom, then the
    /// toplevel.
    fn hit(&self, x: f64, y: f64) -> Option<(usize, Target)> {
        for (i, p) in self.popups.iter().enumerate().rev() {
            if !self.is_shown(&p.surface) {
                continue;
            }
            let Some(s) = self.surfaces.get(&p.surface) else {
                continue;
            };
            let (Some((w, h)), Some(r)) = (s.size(), s.resource.clone()) else {
                continue;
            };
            let (ox, oy) = self.surface_origin(&p.surface);
            if Rect::new(ox, oy, w, h).contains(x, y) {
                return Some((i + 1, (r, (ox as f64, oy as f64))));
            }
        }
        let f = self.focus.as_ref()?;
        let r = self.surfaces.get(f)?.resource.clone()?;
        let (ox, oy) = self.surface_origin(f);
        Some((0, (r, (ox as f64, oy as f64))))
    }

    /// Whether a press at logical (x, y) lands outside every grabbing popup. If so, the popups
    /// above the surface it lands on are dismissed and the press is swallowed.
    fn press_dismisses(&mut self, x: f64, y: f64) -> bool {
        if !self.popups.iter().any(|p| p.grab) {
            return false;
        }
        let keep = self.hit(x, y).map(|(i, _)| i).unwrap_or(0);
        if keep >= self.popups.len() {
            return false;
        }
        self.dismiss_popups(keep);
        true
    }

    fn set_pointer_target(&mut self, target: Option<Target>, x: f64, y: f64) {
        let same = match (&self.pointer_target, &target) {
            (Some((a, _)), Some((b, _))) => a == b,
            (None, None) => true,
            _ => false,
        };
        if same {
            if let Some((t, o)) = &target {
                self.pointer_target = Some((t.clone(), *o));
            }
            return;
        }
        if let Some((old, _)) = self.pointer_target.take() {
            let serial = self.next_serial();
            for p in self.pointers.iter().filter(|p| Self::same_client(*p, &old)) {
                p.leave(serial, &old);
                if p.version() >= 5 {
                    p.frame();
                }
            }
        }
        if let Some((new, o)) = &target {
            let serial = self.next_serial();
            for p in self.pointers.iter().filter(|p| Self::same_client(*p, new)) {
                p.enter(serial, new, x - o.0, y - o.1);
                if p.version() >= 5 {
                    p.frame();
                }
            }
        }
        self.pointer_target = target;
    }

    fn app_input(&mut self, i: Input) {
        let Some(scale) = self.app else {
            return;
        };
        if self.focus.is_none() {
            return;
        }
        self.inputs += 1;
        let t = self.time();
        match i {
            Input::TouchDown { id, x, y } => {
                let (x, y) = (x / scale, y / scale);
                if self.press_dismisses(x, y) {
                    self.touch_targets.insert(id, None);
                    return;
                }
                let Some((_, (s, o))) = self.hit(x, y) else {
                    return;
                };
                let serial = self.next_serial();
                for tch in self.touches.iter().filter(|r| Self::same_client(*r, &s)) {
                    tch.down(serial, t, &s, id, x - o.0, y - o.1);
                    tch.frame();
                }
                self.touch_targets.insert(id, Some((s, o)));
            }
            Input::TouchMotion { id, x, y } => {
                let (x, y) = (x / scale, y / scale);
                let Some(Some((s, o))) = self.touch_targets.get(&id).cloned() else {
                    return;
                };
                for tch in self.touches.iter().filter(|r| Self::same_client(*r, &s)) {
                    tch.motion(t, id, x - o.0, y - o.1);
                    tch.frame();
                }
            }
            Input::TouchUp { id } => {
                let Some(Some((s, _))) = self.touch_targets.remove(&id) else {
                    return;
                };
                let serial = self.next_serial();
                for tch in self.touches.iter().filter(|r| Self::same_client(*r, &s)) {
                    tch.up(serial, t, id);
                    tch.frame();
                }
            }
            Input::TouchCancel => {
                let targets: Vec<Target> =
                    self.touch_targets.drain().filter_map(|(_, v)| v).collect();
                for tch in &self.touches {
                    if targets.iter().any(|(s, _)| Self::same_client(tch, s)) {
                        tch.cancel();
                    }
                }
            }
            Input::PointerMotion { x, y } => {
                let (x, y) = (x / scale, y / scale);
                let target = self.hit(x, y).map(|(_, t)| t);
                let entering = match (&self.pointer_target, &target) {
                    (Some((a, _)), Some((b, _))) => a != b,
                    _ => true,
                };
                self.set_pointer_target(target, x, y);
                if !entering {
                    if let Some((s, o)) = self.pointer_target.clone() {
                        for p in self.pointers.iter().filter(|p| Self::same_client(*p, &s)) {
                            p.motion(t, x - o.0, y - o.1);
                            if p.version() >= 5 {
                                p.frame();
                            }
                        }
                    }
                }
                self.pointer_pos = (x, y);
            }
            Input::PointerButton { button, pressed } => {
                if pressed {
                    let (x, y) = self.pointer_pos;
                    if self.press_dismisses(x, y) {
                        self.swallow_release = true;
                        return;
                    }
                } else if std::mem::take(&mut self.swallow_release) {
                    return;
                }
                let Some((s, _)) = self.pointer_target.clone() else {
                    return;
                };
                let serial = self.next_serial();
                let st = if pressed {
                    wl_pointer::ButtonState::Pressed
                } else {
                    wl_pointer::ButtonState::Released
                };
                for p in self.pointers.iter().filter(|p| Self::same_client(*p, &s)) {
                    p.button(serial, t, button, st);
                    if p.version() >= 5 {
                        p.frame();
                    }
                }
            }
            Input::PointerAxis { dx, dy } => {
                let Some((s, _)) = self.pointer_target.clone() else {
                    return;
                };
                for p in self.pointers.iter().filter(|p| Self::same_client(*p, &s)) {
                    if dy != 0.0 {
                        p.axis(t, wl_pointer::Axis::VerticalScroll, dy / scale);
                    }
                    if dx != 0.0 {
                        p.axis(t, wl_pointer::Axis::HorizontalScroll, dx / scale);
                    }
                    if p.version() >= 5 {
                        p.frame();
                    }
                }
            }
            Input::Key { code, pressed } => {
                let surface = self
                    .kb_focus
                    .as_ref()
                    .and_then(|f| self.surfaces.get(f))
                    .and_then(|s| s.resource.clone());
                let changed = self.update_mods(code, pressed);
                let Some(surface) = surface else {
                    return;
                };
                let serial = self.next_serial();
                let st = if pressed {
                    wl_keyboard::KeyState::Pressed
                } else {
                    wl_keyboard::KeyState::Released
                };
                for k in self
                    .keyboards
                    .iter()
                    .filter(|r| Self::same_client(*r, &surface))
                {
                    k.key(serial, t, code, st);
                }
                if changed {
                    let serial = self.next_serial();
                    for k in self
                        .keyboards
                        .iter()
                        .filter(|r| Self::same_client(*r, &surface))
                    {
                        k.modifiers(serial, self.mods_depressed, 0, self.mods_locked, 0);
                    }
                }
            }
        }
    }

    /// Places popup `i` from its positioner and sends it a configure.
    fn place_popup(&mut self, i: usize) {
        let (lw, lh) = scene::logical_size(self.width, self.height, self.app.unwrap_or(1.0));
        let parent = self.popups[i].parent.clone();
        let (px, py) = self.geometry_origin(&parent, 0);
        let rect = self.popups[i].positioner.place(Rect::new(-px, -py, lw, lh));
        let serial = self.next_serial();
        let p = &mut self.popups[i];
        p.rect = rect;
        p.popup.configure(rect.x, rect.y, rect.w, rect.h);
        p.xdg_surface.configure(serial);
    }
}

fn read_frame(b: &BufferInfo) -> Option<Frame> {
    let pool = b.pool.lock().unwrap();
    let row = b.width as usize * 4;
    if b.stride < row || b.offset + b.stride * (b.height as usize - 1) + row > pool.len {
        return None;
    }
    let opaque = b.format == wl_shm::Format::Xrgb8888;
    let mut pixels = Vec::with_capacity(b.width as usize * b.height as usize);
    for y in 0..b.height as usize {
        // SAFETY: bounds checked above against the mapping length.
        let src = unsafe {
            std::slice::from_raw_parts(pool.ptr.add(b.offset + y * b.stride) as *const u8, row)
        };
        pixels.extend(src.as_chunks::<4>().0.iter().map(|p| {
            let v = u32::from_le_bytes(*p);
            if opaque {
                v | 0xff00_0000
            } else {
                v
            }
        }));
    }
    Some(Frame {
        width: b.width,
        height: b.height,
        pixels,
    })
}

fn send_output(o: &wl_output::WlOutput, width: u32, height: u32, scale: i32) {
    o.geometry(
        0,
        0,
        0,
        0,
        wl_output::Subpixel::Unknown,
        "Selkie".into(),
        "Android".into(),
        wl_output::Transform::Normal,
    );
    o.mode(
        wl_output::Mode::Current | wl_output::Mode::Preferred,
        width as i32,
        height as i32,
        60_000,
    );
    if o.version() >= 2 {
        o.scale(scale);
    }
    if o.version() >= 4 {
        o.name("SELKIE-1".into());
        o.description("Selkie".into());
    }
    if o.version() >= 2 {
        o.done();
    }
}

/// The keymap as a sealed memfd (with the trailing NUL the protocol expects).
fn keymap_fd() -> Option<(OwnedFd, u32)> {
    let fd = rustix::fs::memfd_create("selkie-keymap", rustix::fs::MemfdFlags::CLOEXEC).ok()?;
    let mut data = KEYMAP.as_bytes().to_vec();
    data.push(0);
    let mut off = 0;
    while off < data.len() {
        off += rustix::io::write(&fd, &data[off..]).ok()?;
    }
    Some((fd, data.len() as u32))
}

// ---- globals ----

impl GlobalDispatch<wl_compositor::WlCompositor, ()> for State {
    fn bind(
        _: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<wl_compositor::WlCompositor>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        di.init(r, ());
    }
}

impl Dispatch<wl_compositor::WlCompositor, ()> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        _: &wl_compositor::WlCompositor,
        req: wl_compositor::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        match req {
            wl_compositor::Request::CreateSurface { id } => {
                let s = di.init(id, ());
                if st.app.is_some() && s.version() >= 6 {
                    s.preferred_buffer_scale(st.int_scale());
                }
                st.surfaces.insert(
                    s.id(),
                    Surface {
                        resource: Some(s),
                        ..Default::default()
                    },
                );
            }
            wl_compositor::Request::CreateRegion { id } => {
                di.init(id, ());
            }
            _ => {}
        }
    }
}

impl Dispatch<wl_region::WlRegion, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_region::WlRegion,
        _: wl_region::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
}

impl Dispatch<wl_surface::WlSurface, ()> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        s: &wl_surface::WlSurface,
        req: wl_surface::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        let id = s.id();
        match req {
            wl_surface::Request::Attach { buffer, .. } => {
                if let Some(surf) = st.surfaces.get_mut(&id) {
                    surf.pending_buffer = Some(buffer);
                }
            }
            wl_surface::Request::Frame { callback } => {
                let cb = di.init(callback, ());
                if let Some(surf) = st.surfaces.get_mut(&id) {
                    surf.pending_callbacks.push(cb);
                }
            }
            wl_surface::Request::SetBufferScale { scale } => {
                if let Some(surf) = st.surfaces.get_mut(&id) {
                    surf.pending_scale = Some(scale);
                }
            }
            wl_surface::Request::Commit => st.commit(&id),
            _ => {}
        }
    }

    fn destroyed(st: &mut Self, _: ClientId, s: &wl_surface::WlSurface, _: &()) {
        let id = s.id();
        if let Some(gone) = st.surfaces.remove(&id) {
            if let Some(p) = gone.sub_parent.and_then(|p| st.surfaces.get_mut(&p)) {
                p.below.retain(|c| c != &id);
                p.above.retain(|c| c != &id);
            }
        }
        if st.kb_focus.as_ref() == Some(&id) {
            st.kb_focus = None;
        }
        if matches!(&st.pointer_target, Some((t, _)) if t == s) {
            st.pointer_target = None;
        }
        st.touch_targets
            .retain(|_, t| !matches!(t, Some((t, _)) if t == s));
        if st.focus.as_ref() == Some(&id) {
            st.focus = None;
            st.frame = None;
            st.waiting.clear();
            let next = st
                .surfaces
                .iter()
                .find(|(_, s)| s.mapped)
                .map(|(k, _)| k.clone());
            st.set_focus(next);
        } else if st.app.is_some() {
            st.update_kb_focus();
            st.recompose();
        }
    }
}

impl Dispatch<wl_callback::WlCallback, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_callback::WlCallback,
        _: wl_callback::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
}

impl GlobalDispatch<wl_subcompositor::WlSubcompositor, ()> for State {
    fn bind(
        _: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<wl_subcompositor::WlSubcompositor>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        di.init(r, ());
    }
}

impl Dispatch<wl_subcompositor::WlSubcompositor, ()> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        _: &wl_subcompositor::WlSubcompositor,
        req: wl_subcompositor::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        // Session mode accepts subsurfaces but doesn't draw them (full-screen clients draw into one
        // surface). App mode draws them with their parent, always as if desynchronized.
        if let wl_subcompositor::Request::GetSubsurface {
            id,
            surface,
            parent,
        } = req
        {
            let child = surface.id();
            di.init(id, child.clone());
            if st.app.is_some() {
                if let Some(c) = st.surfaces.get_mut(&child) {
                    c.sub_parent = Some(parent.id());
                }
                if let Some(p) = st.surfaces.get_mut(&parent.id()) {
                    p.above.push(child);
                }
            }
        }
    }
}

impl Dispatch<wl_subsurface::WlSubsurface, ObjectId> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        _: &wl_subsurface::WlSubsurface,
        req: wl_subsurface::Request,
        child: &ObjectId,
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
        if st.app.is_none() {
            return;
        }
        let Some(parent) = st.surfaces.get(child).and_then(|c| c.sub_parent.clone()) else {
            return;
        };
        let (sibling, above) = match req {
            wl_subsurface::Request::SetPosition { x, y } => {
                if let Some(c) = st.surfaces.get_mut(child) {
                    c.pending_pos = Some((x, y));
                }
                return;
            }
            wl_subsurface::Request::PlaceAbove { sibling } => (sibling.id(), true),
            wl_subsurface::Request::PlaceBelow { sibling } => (sibling.id(), false),
            _ => return,
        };
        let Some(p) = st.surfaces.get_mut(&parent) else {
            return;
        };
        p.below.retain(|c| c != child);
        p.above.retain(|c| c != child);
        if sibling == parent {
            if above {
                p.above.insert(0, child.clone());
            } else {
                p.below.push(child.clone());
            }
        } else {
            let list = if p.below.contains(&sibling) {
                &mut p.below
            } else {
                &mut p.above
            };
            let i = list
                .iter()
                .position(|c| c == &sibling)
                .unwrap_or(list.len());
            list.insert(
                if above { (i + 1).min(list.len()) } else { i },
                child.clone(),
            );
        }
    }

    fn destroyed(st: &mut Self, _: ClientId, _: &wl_subsurface::WlSubsurface, child: &ObjectId) {
        let parent = st.surfaces.get_mut(child).and_then(|c| c.sub_parent.take());
        if let Some(p) = parent.and_then(|p| st.surfaces.get_mut(&p)) {
            p.below.retain(|c| c != child);
            p.above.retain(|c| c != child);
        }
        st.recompose();
    }
}

impl GlobalDispatch<wl_shm::WlShm, ()> for State {
    fn bind(
        _: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<wl_shm::WlShm>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        let shm = di.init(r, ());
        shm.format(wl_shm::Format::Argb8888);
        shm.format(wl_shm::Format::Xrgb8888);
    }
}

impl Dispatch<wl_shm::WlShm, ()> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        shm: &wl_shm::WlShm,
        req: wl_shm::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        if let wl_shm::Request::CreatePool { id, fd, size } = req {
            match Pool::map(fd, size.max(0) as usize) {
                Some(p) => {
                    let pool = di.init(id, ());
                    st.pools.insert(pool.id(), Arc::new(Mutex::new(p)));
                }
                None => shm.post_error(wl_shm::Error::InvalidFd, "cannot map the pool"),
            }
        }
    }
}

impl Dispatch<wl_shm_pool::WlShmPool, ()> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        pool: &wl_shm_pool::WlShmPool,
        req: wl_shm_pool::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        let Some(p) = st.pools.get(&pool.id()).cloned() else {
            return;
        };
        match req {
            wl_shm_pool::Request::CreateBuffer {
                id,
                offset,
                width,
                height,
                stride,
                format,
            } => {
                let format = match format {
                    WEnum::Value(f @ (wl_shm::Format::Argb8888 | wl_shm::Format::Xrgb8888)) => f,
                    _ => {
                        pool.post_error(wl_shm::Error::InvalidFormat, "only argb8888 and xrgb8888");
                        return;
                    }
                };
                if offset < 0 || width <= 0 || height <= 0 || stride < width * 4 {
                    pool.post_error(wl_shm::Error::InvalidStride, "bad buffer geometry");
                    return;
                }
                let b = di.init(id, ());
                st.buffers.insert(
                    b.id(),
                    BufferInfo {
                        pool: p,
                        offset: offset as usize,
                        width: width as u32,
                        height: height as u32,
                        stride: stride as usize,
                        format,
                    },
                );
            }
            wl_shm_pool::Request::Resize { size } => {
                let mut g = p.lock().unwrap();
                let fd = g.fd.as_fd().try_clone_to_owned();
                if let Some(np) = fd.ok().and_then(|fd| Pool::map(fd, size.max(0) as usize)) {
                    *g = np;
                }
            }
            _ => {}
        }
    }

    fn destroyed(st: &mut Self, _: ClientId, pool: &wl_shm_pool::WlShmPool, _: &()) {
        st.pools.remove(&pool.id());
    }
}

impl Dispatch<wl_buffer::WlBuffer, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_buffer::WlBuffer,
        _: wl_buffer::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }

    fn destroyed(st: &mut Self, _: ClientId, b: &wl_buffer::WlBuffer, _: &()) {
        st.buffers.remove(&b.id());
    }
}

impl GlobalDispatch<wl_output::WlOutput, ()> for State {
    fn bind(
        st: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<wl_output::WlOutput>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        let o = di.init(r, ());
        send_output(&o, st.width, st.height, st.int_scale());
        st.outputs.push(o);
    }
}

impl Dispatch<wl_output::WlOutput, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_output::WlOutput,
        _: wl_output::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }

    fn destroyed(st: &mut Self, _: ClientId, o: &wl_output::WlOutput, _: &()) {
        st.outputs.retain(|x| x != o);
    }
}

impl GlobalDispatch<wl_seat::WlSeat, ()> for State {
    fn bind(
        _: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<wl_seat::WlSeat>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        let s = di.init(r, ());
        s.capabilities(
            wl_seat::Capability::Pointer
                | wl_seat::Capability::Keyboard
                | wl_seat::Capability::Touch,
        );
        if s.version() >= 2 {
            s.name("seat0".into());
        }
    }
}

impl Dispatch<wl_seat::WlSeat, ()> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        _: &wl_seat::WlSeat,
        req: wl_seat::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        match req {
            wl_seat::Request::GetPointer { id } => st.pointers.push(di.init(id, ())),
            wl_seat::Request::GetKeyboard { id } => {
                let k = di.init(id, ());
                if let Some((fd, size)) = keymap_fd() {
                    k.keymap(wl_keyboard::KeymapFormat::XkbV1, fd.as_fd(), size);
                }
                if k.version() >= 4 {
                    k.repeat_info(25, 600);
                }
                if let Some(s) = st.focused_surface() {
                    if State::same_client(&k, &s) {
                        let serial = st.next_serial();
                        k.enter(serial, &s, Vec::new());
                        k.modifiers(serial, st.mods_depressed, 0, st.mods_locked, 0);
                    }
                }
                st.keyboards.push(k);
            }
            wl_seat::Request::GetTouch { id } => st.touches.push(di.init(id, ())),
            _ => {}
        }
    }
}

impl Dispatch<wl_pointer::WlPointer, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_pointer::WlPointer,
        _: wl_pointer::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
    fn destroyed(st: &mut Self, _: ClientId, r: &wl_pointer::WlPointer, _: &()) {
        st.pointers.retain(|x| x != r);
    }
}

impl Dispatch<wl_keyboard::WlKeyboard, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_keyboard::WlKeyboard,
        _: wl_keyboard::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
    fn destroyed(st: &mut Self, _: ClientId, r: &wl_keyboard::WlKeyboard, _: &()) {
        st.keyboards.retain(|x| x != r);
    }
}

impl Dispatch<wl_touch::WlTouch, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_touch::WlTouch,
        _: wl_touch::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
    fn destroyed(st: &mut Self, _: ClientId, r: &wl_touch::WlTouch, _: &()) {
        st.touches.retain(|x| x != r);
    }
}

impl GlobalDispatch<xdg_wm_base::XdgWmBase, ()> for State {
    fn bind(
        _: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<xdg_wm_base::XdgWmBase>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        di.init(r, ());
    }
}

impl Dispatch<xdg_wm_base::XdgWmBase, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &xdg_wm_base::XdgWmBase,
        req: xdg_wm_base::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        match req {
            xdg_wm_base::Request::GetXdgSurface { id, surface } => {
                di.init(id, surface.id());
            }
            xdg_wm_base::Request::CreatePositioner { id } => {
                di.init(id, ());
            }
            _ => {}
        }
    }
}

fn wenum<T: Into<u32>>(e: WEnum<T>) -> u32 {
    match e {
        WEnum::Value(v) => v.into(),
        WEnum::Unknown(u) => u,
    }
}

impl Dispatch<xdg_positioner::XdgPositioner, ()> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        r: &xdg_positioner::XdgPositioner,
        req: xdg_positioner::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
        use xdg_positioner::Request as R;
        let p = st.positioners.entry(r.id()).or_default();
        match req {
            R::SetSize { width, height } => p.size = (width, height),
            R::SetAnchorRect {
                x,
                y,
                width,
                height,
            } => p.anchor_rect = Rect::new(x, y, width, height),
            R::SetAnchor { anchor } => p.anchor = wenum(anchor),
            R::SetGravity { gravity } => p.gravity = wenum(gravity),
            R::SetConstraintAdjustment {
                constraint_adjustment,
            } => p.constraint_adjustment = wenum(constraint_adjustment),
            R::SetOffset { x, y } => p.offset = (x, y),
            _ => {}
        }
    }

    fn destroyed(st: &mut Self, _: ClientId, r: &xdg_positioner::XdgPositioner, _: &()) {
        st.positioners.remove(&r.id());
    }
}

impl Dispatch<xdg_surface::XdgSurface, ObjectId> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        xs: &xdg_surface::XdgSurface,
        req: xdg_surface::Request,
        surface: &ObjectId,
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        match req {
            xdg_surface::Request::GetToplevel { id } => {
                let t = di.init(id, surface.clone());
                if let Some(s) = st.surfaces.get_mut(surface) {
                    s.toplevel = Some(t.id());
                }
                st.toplevels.push(Toplevel {
                    toplevel: t,
                    xdg_surface: xs.clone(),
                });
                let i = st.toplevels.len() - 1;
                st.configure(i);
            }
            xdg_surface::Request::GetPopup {
                id,
                parent,
                positioner,
            } => {
                let p = di.init(id, surface.clone());
                let parent = parent.and_then(|x| x.data::<ObjectId>().cloned());
                let (Some(_), Some(parent)) = (st.app, parent) else {
                    // Session mode doesn't show popups: tell the client it was dismissed.
                    p.popup_done();
                    return;
                };
                if let Some(s) = st.surfaces.get_mut(surface) {
                    s.popup = Some(p.id());
                }
                let positioner = st
                    .positioners
                    .get(&positioner.id())
                    .copied()
                    .unwrap_or_default();
                st.popups.push(Popup {
                    popup: p,
                    xdg_surface: xs.clone(),
                    surface: surface.clone(),
                    parent,
                    positioner,
                    rect: Rect::new(0, 0, 0, 0),
                    grab: false,
                });
                let i = st.popups.len() - 1;
                st.place_popup(i);
            }
            xdg_surface::Request::SetWindowGeometry {
                x,
                y,
                width,
                height,
            } => {
                if let Some(s) = st.surfaces.get_mut(surface) {
                    s.pending_geometry = Some(Rect::new(x, y, width, height));
                }
            }
            _ => {}
        }
    }
}

impl Dispatch<xdg_toplevel::XdgToplevel, ObjectId> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &xdg_toplevel::XdgToplevel,
        _: xdg_toplevel::Request,
        _: &ObjectId,
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }

    fn destroyed(st: &mut Self, _: ClientId, t: &xdg_toplevel::XdgToplevel, surface: &ObjectId) {
        st.toplevels.retain(|x| &x.toplevel != t);
        if let Some(s) = st.surfaces.get_mut(surface) {
            s.toplevel = None;
            s.mapped = false;
        }
        if st.focus.as_ref() == Some(surface) {
            let next = st
                .surfaces
                .iter()
                .find(|(_, s)| s.mapped)
                .map(|(k, _)| k.clone());
            st.set_focus(next);
        }
    }
}

impl Dispatch<xdg_popup::XdgPopup, ObjectId> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        p: &xdg_popup::XdgPopup,
        req: xdg_popup::Request,
        _: &ObjectId,
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
        let Some(i) = st.popups.iter().position(|x| &x.popup == p) else {
            return;
        };
        match req {
            xdg_popup::Request::Grab { .. } => {
                st.popups[i].grab = true;
                st.update_kb_focus();
            }
            xdg_popup::Request::Reposition { positioner, token } => {
                if let Some(pos) = st.positioners.get(&positioner.id()).copied() {
                    st.popups[i].positioner = pos;
                }
                if p.version() >= 3 {
                    p.repositioned(token);
                }
                st.place_popup(i);
            }
            _ => {}
        }
    }

    fn destroyed(st: &mut Self, _: ClientId, p: &xdg_popup::XdgPopup, surface: &ObjectId) {
        if let Some(i) = st.popups.iter().position(|x| &x.popup == p) {
            // Child popups go first (xdg-shell requires it); dismiss any left over.
            st.dismiss_popups(i + 1);
            st.popups.remove(i);
        }
        if let Some(s) = st.surfaces.get_mut(surface) {
            s.popup = None;
            s.content = None;
        }
        st.update_kb_focus();
        st.recompose();
    }
}

// ---- app mode scaling: wp_viewporter and wp_fractional_scale_v1 ----

impl GlobalDispatch<wp_viewporter::WpViewporter, ()> for State {
    fn bind(
        _: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<wp_viewporter::WpViewporter>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        di.init(r, ());
    }
}

impl Dispatch<wp_viewporter::WpViewporter, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wp_viewporter::WpViewporter,
        req: wp_viewporter::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        if let wp_viewporter::Request::GetViewport { id, surface } = req {
            di.init(id, surface.id());
        }
    }
}

impl Dispatch<wp_viewport::WpViewport, ObjectId> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        _: &wp_viewport::WpViewport,
        req: wp_viewport::Request,
        surface: &ObjectId,
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
        let Some(s) = st.surfaces.get_mut(surface) else {
            return;
        };
        match req {
            wp_viewport::Request::SetSource {
                x,
                y,
                width,
                height,
            } => {
                s.pending_vp_src =
                    Some((width > 0.0 && height > 0.0).then_some((x, y, width, height)));
            }
            wp_viewport::Request::SetDestination { width, height } => {
                s.pending_vp_dst = Some((width > 0 && height > 0).then_some((width, height)));
            }
            _ => {}
        }
    }

    fn destroyed(st: &mut Self, _: ClientId, _: &wp_viewport::WpViewport, surface: &ObjectId) {
        if let Some(s) = st.surfaces.get_mut(surface) {
            s.pending_vp_src = Some(None);
            s.pending_vp_dst = Some(None);
        }
    }
}

impl GlobalDispatch<wp_fractional_scale_manager_v1::WpFractionalScaleManagerV1, ()> for State {
    fn bind(
        _: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<wp_fractional_scale_manager_v1::WpFractionalScaleManagerV1>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        di.init(r, ());
    }
}

impl Dispatch<wp_fractional_scale_manager_v1::WpFractionalScaleManagerV1, ()> for State {
    fn request(
        st: &mut Self,
        _: &Client,
        _: &wp_fractional_scale_manager_v1::WpFractionalScaleManagerV1,
        req: wp_fractional_scale_manager_v1::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        if let wp_fractional_scale_manager_v1::Request::GetFractionalScale { id, .. } = req {
            let f = di.init(id, ());
            // In 120ths, as the protocol counts.
            f.preferred_scale((st.app.unwrap_or(1.0) * 120.0).round() as u32);
        }
    }
}

impl Dispatch<wp_fractional_scale_v1::WpFractionalScaleV1, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wp_fractional_scale_v1::WpFractionalScaleV1,
        _: wp_fractional_scale_v1::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
}

// Clipboard and drag and drop: present (many toolkits expect the global) but inert for now.
impl GlobalDispatch<wl_data_device_manager::WlDataDeviceManager, ()> for State {
    fn bind(
        _: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        r: New<wl_data_device_manager::WlDataDeviceManager>,
        _: &(),
        di: &mut DataInit<'_, Self>,
    ) {
        di.init(r, ());
    }
}

impl Dispatch<wl_data_device_manager::WlDataDeviceManager, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_data_device_manager::WlDataDeviceManager,
        req: wl_data_device_manager::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        match req {
            wl_data_device_manager::Request::CreateDataSource { id } => {
                di.init(id, ());
            }
            wl_data_device_manager::Request::GetDataDevice { id, .. } => {
                di.init(id, ());
            }
            _ => {}
        }
    }
}

impl Dispatch<wl_data_source::WlDataSource, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_data_source::WlDataSource,
        _: wl_data_source::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
}

impl Dispatch<wl_data_device::WlDataDevice, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_data_device::WlDataDevice,
        _: wl_data_device::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
}
