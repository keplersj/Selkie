//! Drives the compositor with a real Wayland client over its socket.

use selkie_compositor::{server, Input, MemorySink};
use std::os::fd::AsFd;
use std::os::unix::net::UnixStream;
use std::time::{Duration, Instant};
use wayland_client::protocol::{
    wl_buffer, wl_callback, wl_compositor, wl_keyboard, wl_registry, wl_seat, wl_shm, wl_shm_pool,
    wl_surface, wl_touch,
};
use wayland_client::{Connection, Dispatch, QueueHandle, WEnum};
use wayland_protocols::xdg::shell::client::{xdg_surface, xdg_toplevel, xdg_wm_base};

#[derive(Default)]
struct Client {
    compositor: Option<wl_compositor::WlCompositor>,
    shm: Option<wl_shm::WlShm>,
    wm: Option<xdg_wm_base::XdgWmBase>,
    seat: Option<wl_seat::WlSeat>,
    configures: Vec<(i32, i32)>,
    acked: u32,
    keymap_size: u32,
    keys: Vec<(u32, bool)>,
    mods: Vec<u32>,
    touches: Vec<(i32, f64, f64)>,
    frames_done: u32,
    released: u32,
}

impl Dispatch<wl_registry::WlRegistry, ()> for Client {
    fn event(
        c: &mut Self,
        r: &wl_registry::WlRegistry,
        e: wl_registry::Event,
        _: &(),
        _: &Connection,
        qh: &QueueHandle<Self>,
    ) {
        if let wl_registry::Event::Global {
            name, interface, ..
        } = e
        {
            match interface.as_str() {
                "wl_compositor" => c.compositor = Some(r.bind(name, 4, qh, ())),
                "wl_shm" => c.shm = Some(r.bind(name, 1, qh, ())),
                "xdg_wm_base" => c.wm = Some(r.bind(name, 2, qh, ())),
                "wl_seat" => c.seat = Some(r.bind(name, 5, qh, ())),
                _ => {}
            }
        }
    }
}

impl Dispatch<xdg_surface::XdgSurface, ()> for Client {
    fn event(
        c: &mut Self,
        s: &xdg_surface::XdgSurface,
        e: xdg_surface::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let xdg_surface::Event::Configure { serial } = e {
            s.ack_configure(serial);
            c.acked += 1;
        }
    }
}

impl Dispatch<xdg_toplevel::XdgToplevel, ()> for Client {
    fn event(
        c: &mut Self,
        _: &xdg_toplevel::XdgToplevel,
        e: xdg_toplevel::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let xdg_toplevel::Event::Configure { width, height, .. } = e {
            c.configures.push((width, height));
        }
    }
}

impl Dispatch<xdg_wm_base::XdgWmBase, ()> for Client {
    fn event(
        _: &mut Self,
        w: &xdg_wm_base::XdgWmBase,
        e: xdg_wm_base::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let xdg_wm_base::Event::Ping { serial } = e {
            w.pong(serial);
        }
    }
}

impl Dispatch<wl_seat::WlSeat, ()> for Client {
    fn event(
        _: &mut Self,
        s: &wl_seat::WlSeat,
        e: wl_seat::Event,
        _: &(),
        _: &Connection,
        qh: &QueueHandle<Self>,
    ) {
        if let wl_seat::Event::Capabilities {
            capabilities: WEnum::Value(caps),
        } = e
        {
            if caps.contains(wl_seat::Capability::Keyboard) {
                s.get_keyboard(qh, ());
            }
            if caps.contains(wl_seat::Capability::Touch) {
                s.get_touch(qh, ());
            }
        }
    }
}

impl Dispatch<wl_keyboard::WlKeyboard, ()> for Client {
    fn event(
        c: &mut Self,
        _: &wl_keyboard::WlKeyboard,
        e: wl_keyboard::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        match e {
            wl_keyboard::Event::Keymap { size, .. } => c.keymap_size = size,
            wl_keyboard::Event::Key { key, state, .. } => c.keys.push((
                key,
                matches!(state, WEnum::Value(wl_keyboard::KeyState::Pressed)),
            )),
            wl_keyboard::Event::Modifiers { mods_depressed, .. } => c.mods.push(mods_depressed),
            _ => {}
        }
    }
}

impl Dispatch<wl_touch::WlTouch, ()> for Client {
    fn event(
        c: &mut Self,
        _: &wl_touch::WlTouch,
        e: wl_touch::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let wl_touch::Event::Down { id, x, y, .. } = e {
            c.touches.push((id, x, y));
        }
    }
}

impl Dispatch<wl_callback::WlCallback, ()> for Client {
    fn event(
        c: &mut Self,
        _: &wl_callback::WlCallback,
        _: wl_callback::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        c.frames_done += 1;
    }
}

impl Dispatch<wl_buffer::WlBuffer, ()> for Client {
    fn event(
        c: &mut Self,
        _: &wl_buffer::WlBuffer,
        e: wl_buffer::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let wl_buffer::Event::Release = e {
            c.released += 1;
        }
    }
}

wayland_client::delegate_noop!(Client: ignore wl_compositor::WlCompositor);
wayland_client::delegate_noop!(Client: ignore wl_shm::WlShm);
wayland_client::delegate_noop!(Client: ignore wl_shm_pool::WlShmPool);
wayland_client::delegate_noop!(Client: ignore wl_surface::WlSurface);

fn until(
    q: &mut wayland_client::EventQueue<Client>,
    c: &mut Client,
    what: &str,
    f: impl Fn(&Client) -> bool,
) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while !f(c) {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        q.roundtrip(c).unwrap();
        std::thread::sleep(Duration::from_millis(10));
    }
}

fn wait_stats(h: &server::Handle, what: &str, f: impl Fn(&selkie_compositor::Stats) -> bool) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while !f(&h.stats.lock().unwrap()) {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn toplevel_frames_and_input() {
    let dir = tempfile::tempdir().unwrap();
    let socket = dir.path().join("wayland-0");
    let sink = MemorySink::default();
    let h = server::start(&socket, 64, 48, Some(Box::new(sink.clone()))).unwrap();

    let conn = Connection::from_socket(UnixStream::connect(&socket).unwrap()).unwrap();
    let mut q = conn.new_event_queue();
    let qh = q.handle();
    conn.display().get_registry(&qh, ());
    let mut c = Client::default();
    q.roundtrip(&mut c).unwrap();
    q.roundtrip(&mut c).unwrap();

    // full-screen configure at the output size
    let surface = c.compositor.as_ref().unwrap().create_surface(&qh, ());
    let xs = c.wm.as_ref().unwrap().get_xdg_surface(&surface, &qh, ());
    let _top = xs.get_toplevel(&qh, ());
    surface.commit();
    until(&mut q, &mut c, "configure", |c| c.acked > 0);
    assert_eq!(c.configures.last(), Some(&(64, 48)));
    assert!(c.keymap_size > 1000, "keymap sent");

    // shm buffer: left half red, right half blue (xrgb8888 little endian = b, g, r, x)
    let (w, h_) = (64usize, 48usize);
    let fd = rustix::fs::memfd_create("test", rustix::fs::MemfdFlags::CLOEXEC).unwrap();
    let mut px = Vec::with_capacity(w * h_ * 4);
    for _y in 0..h_ {
        for x in 0..w {
            px.extend(if x < w / 2 {
                [0, 0, 255, 0]
            } else {
                [255, 0, 0, 0]
            });
        }
    }
    rustix::io::write(&fd, &px).unwrap();
    let pool = c
        .shm
        .as_ref()
        .unwrap()
        .create_pool(fd.as_fd(), px.len() as i32, &qh, ());
    let buf = pool.create_buffer(
        0,
        w as i32,
        h_ as i32,
        (w * 4) as i32,
        wl_shm::Format::Xrgb8888,
        &qh,
        (),
    );
    surface.attach(Some(&buf), 0, 0);
    surface.damage_buffer(0, 0, w as i32, h_ as i32);
    surface.frame(&qh, ());
    surface.commit();
    until(&mut q, &mut c, "frame callback", |c| {
        c.frames_done == 1 && c.released == 1
    });
    let f = sink.0.lock().unwrap().clone().expect("frame presented");
    assert_eq!((f.width, f.height), (64, 48));
    assert_eq!(f.pixels[0], 0xffff_0000, "red, opaque");
    assert_eq!(f.pixels[63], 0xff00_00ff, "blue, opaque");
    wait_stats(&h, "stats", |s| {
        s.frame.is_some_and(|f| f.sampled_colors == 2) && s.toplevels == 1 && s.clients == 1
    });

    // keyboard: focus came with the first buffer; Shift updates the modifiers
    h.send(server::Command::Input(Input::Key {
        code: 42,
        pressed: true,
    }));
    h.send(server::Command::Input(Input::Key {
        code: 30,
        pressed: true,
    }));
    h.send(server::Command::Input(Input::Key {
        code: 30,
        pressed: false,
    }));
    h.send(server::Command::Input(Input::Key {
        code: 42,
        pressed: false,
    }));
    until(&mut q, &mut c, "keys", |c| c.keys.len() == 4);
    assert_eq!(
        c.keys,
        vec![(42, true), (30, true), (30, false), (42, false)]
    );
    assert_eq!(c.mods.last(), Some(&0));
    assert!(c.mods.contains(&1), "shift seen in {:?}", c.mods);

    // touch in view coordinates equals surface coordinates when the frame matches the output
    h.send(server::Command::Input(Input::TouchDown {
        id: 3,
        x: 10.0,
        y: 20.0,
    }));
    h.send(server::Command::Input(Input::TouchUp { id: 3 }));
    until(&mut q, &mut c, "touch", |c| !c.touches.is_empty());
    assert_eq!(c.touches[0], (3, 10.0, 20.0));

    // the view is resized: the toplevel is reconfigured; input maps from the new view onto the old frame
    h.send(server::Command::Output {
        width: 128,
        height: 96,
    });
    until(&mut q, &mut c, "reconfigure", |c| {
        c.configures.last() == Some(&(128, 96))
    });
    h.send(server::Command::Input(Input::TouchDown {
        id: 4,
        x: 64.0,
        y: 48.0,
    }));
    until(&mut q, &mut c, "scaled touch", |c| c.touches.len() == 2);
    assert_eq!(c.touches[1], (4, 32.0, 24.0));

    // no sink: frames are held (clients pause) until there is one again
    h.send(server::Command::Sink(None));
    q.roundtrip(&mut c).unwrap();
    surface.attach(Some(&buf), 0, 0);
    surface.frame(&qh, ());
    surface.commit();
    q.roundtrip(&mut c).unwrap();
    std::thread::sleep(Duration::from_millis(100));
    q.roundtrip(&mut c).unwrap();
    assert_eq!(c.frames_done, 1, "no frame callback without a sink");
    h.send(server::Command::Sink(Some(Box::new(sink.clone()))));
    until(&mut q, &mut c, "frame after the sink came back", |c| {
        c.frames_done == 2
    });

    // disconnecting (every proxy and the queue hold the connection) cleans up its surfaces
    drop((conn, q, qh, surface, xs, _top, pool, buf, c));
    wait_stats(&h, "client gone", |s| s.clients == 0);
    h.stop();
}

#[test]
fn stale_socket_is_replaced() {
    let dir = tempfile::tempdir().unwrap();
    let socket = dir.path().join("wayland-0");
    drop(std::os::unix::net::UnixListener::bind(&socket).unwrap());
    let h = server::start(&socket, 10, 10, None).unwrap();
    UnixStream::connect(&socket).expect("listening");
    h.stop();
}
