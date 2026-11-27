//! The Wayland server: just enough of the core protocol and xdg-shell for full-screen clients
//! (a nested session compositor such as KWin, cosmic-comp or gamescope, or `droidc bridge`).
//!
//! One toplevel is shown at a time, full screen at the output size: the most recently mapped one.
//! Its buffer is copied on commit (wl_shm only) and handed to a [`FrameSink`]; the buffer is released
//! right away. Frame callbacks complete after the sink has shown the frame, so clients render at the
//! sink's pace and stop while there is nowhere to draw.

use crate::{Frame, FrameSink, Input, Stats};
use std::collections::HashMap;
use std::os::fd::{AsFd, OwnedFd};
use std::path::Path;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::Instant;
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
        .spawn(move || run(display, listener, rx, wake_r, width, height, sink, st))?;
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
    sink: Option<Box<dyn FrameSink>>,
    stats: Arc<Mutex<Stats>>,
) {
    let dh = display.handle();
    dh.create_global::<State, wl_compositor::WlCompositor, ()>(5, ());
    dh.create_global::<State, wl_subcompositor::WlSubcompositor, ()>(1, ());
    dh.create_global::<State, wl_shm::WlShm, ()>(1, ());
    dh.create_global::<State, wl_output::WlOutput, ()>(4, ());
    dh.create_global::<State, wl_seat::WlSeat, ()>(7, ());
    dh.create_global::<State, xdg_wm_base::XdgWmBase, ()>(5, ());
    dh.create_global::<State, wl_data_device_manager::WlDataDeviceManager, ()>(3, ());
    let mut state = State::new(width, height, sink, stats);
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
        }
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
        for o in self.outputs.clone() {
            send_output(&o, width, height);
        }
        for i in 0..self.toplevels.len() {
            self.configure(i);
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
            Some(f) => (
                x * f.width as f64 / self.width as f64,
                y * f.height as f64 / self.height as f64,
            ),
            None => (x, y),
        }
    }

    fn input(&mut self, i: Input) {
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

fn send_output(o: &wl_output::WlOutput, width: u32, height: u32) {
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
        o.scale(1);
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
            wl_surface::Request::Commit => st.commit(&id),
            _ => {}
        }
    }

    fn destroyed(st: &mut Self, _: ClientId, s: &wl_surface::WlSurface, _: &()) {
        let id = s.id();
        st.surfaces.remove(&id);
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
        _: &mut Self,
        _: &Client,
        _: &wl_subcompositor::WlSubcompositor,
        req: wl_subcompositor::Request,
        _: &(),
        _: &DisplayHandle,
        di: &mut DataInit<'_, Self>,
    ) {
        // Subsurfaces are accepted but not composited: full-screen clients draw into one surface.
        if let wl_subcompositor::Request::GetSubsurface { id, .. } = req {
            di.init(id, ());
        }
    }
}

impl Dispatch<wl_subsurface::WlSubsurface, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &wl_subsurface::WlSubsurface,
        _: wl_subsurface::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
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
        send_output(&o, st.width, st.height);
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

impl Dispatch<xdg_positioner::XdgPositioner, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &xdg_positioner::XdgPositioner,
        _: xdg_positioner::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
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
            xdg_surface::Request::GetPopup { id, .. } => {
                // Popups are not shown; tell the client it was dismissed.
                let p = di.init(id, ());
                p.popup_done();
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

impl Dispatch<xdg_popup::XdgPopup, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &xdg_popup::XdgPopup,
        _: xdg_popup::Request,
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
