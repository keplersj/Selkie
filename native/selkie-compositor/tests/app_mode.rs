//! App mode: one app at a scale, with a popup, driven by a real Wayland client.

use selkie_compositor::{server, Input, MemorySink};
use std::os::fd::AsFd;
use std::os::unix::net::UnixStream;
use std::time::{Duration, Instant};
use wayland_client::protocol::{
    wl_buffer, wl_compositor, wl_output, wl_registry, wl_seat, wl_shm, wl_shm_pool, wl_surface,
    wl_touch,
};
use wayland_client::{Connection, Dispatch, Proxy, QueueHandle, WEnum};
use wayland_protocols::wp::fractional_scale::v1::client::{
    wp_fractional_scale_manager_v1, wp_fractional_scale_v1,
};
use wayland_protocols::wp::viewporter::client::{wp_viewport, wp_viewporter};
use wayland_protocols::xdg::shell::client::{
    xdg_popup, xdg_positioner, xdg_surface, xdg_toplevel, xdg_wm_base,
};

#[derive(Default)]
struct Client {
    compositor: Option<wl_compositor::WlCompositor>,
    shm: Option<wl_shm::WlShm>,
    wm: Option<xdg_wm_base::XdgWmBase>,
    seat: Option<wl_seat::WlSeat>,
    viewporter: Option<wp_viewporter::WpViewporter>,
    fractional: Option<wp_fractional_scale_manager_v1::WpFractionalScaleManagerV1>,
    output_scale: i32,
    preferred_buffer_scale: i32,
    preferred_scale: u32,
    configures: Vec<(i32, i32, Vec<u32>)>,
    popup_configures: Vec<(i32, i32, i32, i32)>,
    popup_done: bool,
    acked: u32,
    /// (surface, x, y) of each touch down.
    touches: Vec<(wl_surface::WlSurface, f64, f64)>,
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
            name,
            interface,
            version,
        } = e
        {
            match interface.as_str() {
                "wl_compositor" => c.compositor = Some(r.bind(name, version.min(6), qh, ())),
                "wl_shm" => c.shm = Some(r.bind(name, 1, qh, ())),
                "xdg_wm_base" => c.wm = Some(r.bind(name, 3, qh, ())),
                "wl_seat" => c.seat = Some(r.bind(name, 5, qh, ())),
                "wl_output" => {
                    r.bind::<wl_output::WlOutput, _, _>(name, 2, qh, ());
                }
                "wp_viewporter" => c.viewporter = Some(r.bind(name, 1, qh, ())),
                "wp_fractional_scale_manager_v1" => c.fractional = Some(r.bind(name, 1, qh, ())),
                _ => {}
            }
        }
    }
}

impl Dispatch<wl_output::WlOutput, ()> for Client {
    fn event(
        c: &mut Self,
        _: &wl_output::WlOutput,
        e: wl_output::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let wl_output::Event::Scale { factor } = e {
            c.output_scale = factor;
        }
    }
}

impl Dispatch<wl_surface::WlSurface, ()> for Client {
    fn event(
        c: &mut Self,
        _: &wl_surface::WlSurface,
        e: wl_surface::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let wl_surface::Event::PreferredBufferScale { factor } = e {
            c.preferred_buffer_scale = factor;
        }
    }
}

impl Dispatch<wp_fractional_scale_v1::WpFractionalScaleV1, ()> for Client {
    fn event(
        c: &mut Self,
        _: &wp_fractional_scale_v1::WpFractionalScaleV1,
        e: wp_fractional_scale_v1::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        if let wp_fractional_scale_v1::Event::PreferredScale { scale } = e {
            c.preferred_scale = scale;
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
        if let xdg_toplevel::Event::Configure {
            width,
            height,
            states,
        } = e
        {
            let states = states
                .chunks_exact(4)
                .map(|b| u32::from_ne_bytes([b[0], b[1], b[2], b[3]]))
                .collect();
            c.configures.push((width, height, states));
        }
    }
}

impl Dispatch<xdg_popup::XdgPopup, ()> for Client {
    fn event(
        c: &mut Self,
        _: &xdg_popup::XdgPopup,
        e: xdg_popup::Event,
        _: &(),
        _: &Connection,
        _: &QueueHandle<Self>,
    ) {
        match e {
            xdg_popup::Event::Configure {
                x,
                y,
                width,
                height,
            } => c.popup_configures.push((x, y, width, height)),
            xdg_popup::Event::PopupDone => c.popup_done = true,
            _ => {}
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
            if caps.contains(wl_seat::Capability::Touch) {
                s.get_touch(qh, ());
            }
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
        if let wl_touch::Event::Down { surface, x, y, .. } = e {
            c.touches.push((surface, x, y));
        }
    }
}

wayland_client::delegate_noop!(Client: ignore wl_compositor::WlCompositor);
wayland_client::delegate_noop!(Client: ignore wl_shm::WlShm);
wayland_client::delegate_noop!(Client: ignore wl_shm_pool::WlShmPool);
wayland_client::delegate_noop!(Client: ignore wl_buffer::WlBuffer);
wayland_client::delegate_noop!(Client: ignore xdg_positioner::XdgPositioner);
wayland_client::delegate_noop!(Client: ignore wp_viewporter::WpViewporter);
wayland_client::delegate_noop!(Client: ignore wp_viewport::WpViewport);
wayland_client::delegate_noop!(Client: ignore wp_fractional_scale_manager_v1::WpFractionalScaleManagerV1);

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

fn frame_until(sink: &MemorySink, what: &str, f: impl Fn(&selkie_compositor::Frame) -> bool) {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if let Some(fr) = sink.0.lock().unwrap().as_ref() {
            if f(fr) {
                return;
            }
        }
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// A solid xrgb8888 buffer.
fn solid(
    c: &Client,
    qh: &QueueHandle<Client>,
    w: i32,
    h: i32,
    bgrx: [u8; 4],
) -> wl_buffer::WlBuffer {
    let fd = rustix::fs::memfd_create("test", rustix::fs::MemfdFlags::CLOEXEC).unwrap();
    let px: Vec<u8> = (0..w * h).flat_map(|_| bgrx).collect();
    rustix::io::write(&fd, &px).unwrap();
    let pool = c
        .shm
        .as_ref()
        .unwrap()
        .create_pool(fd.as_fd(), px.len() as i32, qh, ());
    pool.create_buffer(0, w, h, w * 4, wl_shm::Format::Xrgb8888, qh, ())
}

const RED: u32 = 0xffff_0000;
const BLUE: u32 = 0xff00_00ff;

#[test]
fn app_at_scale_with_a_popup() {
    let dir = tempfile::tempdir().unwrap();
    let socket = dir.path().join("wayland-0");
    let sink = MemorySink::default();
    // 120x240 pixels at 2x: 60x120 logical.
    let h = server::start_app(&socket, 120, 240, 2.0, Some(Box::new(sink.clone()))).unwrap();

    let conn = Connection::from_socket(UnixStream::connect(&socket).unwrap()).unwrap();
    let mut q = conn.new_event_queue();
    let qh = q.handle();
    conn.display().get_registry(&qh, ());
    let mut c = Client::default();
    q.roundtrip(&mut c).unwrap();
    q.roundtrip(&mut c).unwrap();
    assert_eq!(c.output_scale, 2, "wl_output.scale");
    assert!(c.viewporter.is_some() && c.fractional.is_some());

    let surface = c.compositor.as_ref().unwrap().create_surface(&qh, ());
    let _fs = c
        .fractional
        .as_ref()
        .unwrap()
        .get_fractional_scale(&surface, &qh, ());
    let xs = c.wm.as_ref().unwrap().get_xdg_surface(&surface, &qh, ());
    let _top = xs.get_toplevel(&qh, ());
    surface.commit();
    until(&mut q, &mut c, "configure", |c| c.acked > 0);
    assert_eq!(c.preferred_buffer_scale, 2);
    assert_eq!(c.preferred_scale, 240, "2.0 in 120ths");
    let (w, h_, states) = c.configures.last().unwrap().clone();
    assert_eq!((w, h_), (60, 120), "logical size");
    assert!(states.contains(&1), "maximized, not fullscreen: {states:?}");
    assert!(!states.contains(&2));

    // A 2x buffer fills the output one to one.
    let red = solid(&c, &qh, 120, 240, [0, 0, 255, 0]);
    surface.set_buffer_scale(2);
    surface.attach(Some(&red), 0, 0);
    surface.commit();
    q.roundtrip(&mut c).unwrap();
    frame_until(&sink, "toplevel frame", |f| {
        f.pixels[0] == RED && f.pixels[119 + 239 * 120] == RED
    });
    // Selkie's emulator test reads the scale from the stats.
    let json = h.stats.lock().unwrap().to_json();
    assert!(json.contains("\"app_scale\":2,"), "{json}");

    // A popup under a button at (10, 10, 10x10), drawn through a viewport.
    let pos = c.wm.as_ref().unwrap().create_positioner(&qh, ());
    pos.set_size(20, 10);
    pos.set_anchor_rect(10, 10, 10, 10);
    pos.set_anchor(xdg_positioner::Anchor::Bottom);
    pos.set_gravity(xdg_positioner::Gravity::Bottom);
    let ps = c.compositor.as_ref().unwrap().create_surface(&qh, ());
    let vp = c.viewporter.as_ref().unwrap().get_viewport(&ps, &qh, ());
    let pxs = c.wm.as_ref().unwrap().get_xdg_surface(&ps, &qh, ());
    let popup = pxs.get_popup(Some(&xs), &pos, &qh, ());
    popup.grab(c.seat.as_ref().unwrap(), 0);
    ps.commit();
    until(&mut q, &mut c, "popup configure", |c| {
        !c.popup_configures.is_empty()
    });
    assert_eq!(c.popup_configures[0], (5, 20, 20, 10));
    let blue = solid(&c, &qh, 40, 20, [255, 0, 0, 0]);
    vp.set_destination(20, 10);
    ps.attach(Some(&blue), 0, 0);
    ps.commit();
    q.roundtrip(&mut c).unwrap();
    // Logical (5, 20)..(25, 30) is output (10, 40)..(50, 60).
    frame_until(&sink, "popup drawn", |f| {
        f.pixels[10 + 40 * 120] == BLUE && f.pixels[49 + 59 * 120] == BLUE && f.pixels[0] == RED
    });

    // A touch on the popup goes to it, in its coordinates.
    h.send(server::Command::Input(Input::TouchDown {
        id: 1,
        x: 12.0,
        y: 42.0,
    }));
    h.send(server::Command::Input(Input::TouchUp { id: 1 }));
    until(&mut q, &mut c, "touch on popup", |c| !c.touches.is_empty());
    assert_eq!(c.touches[0].0.id(), ps.id());
    assert_eq!((c.touches[0].1, c.touches[0].2), (1.0, 1.0));

    // A touch outside dismisses it and is swallowed.
    h.send(server::Command::Input(Input::TouchDown {
        id: 2,
        x: 100.0,
        y: 200.0,
    }));
    h.send(server::Command::Input(Input::TouchUp { id: 2 }));
    until(&mut q, &mut c, "popup_done", |c| c.popup_done);
    assert_eq!(c.touches.len(), 1, "the dismissing touch isn't delivered");
    frame_until(&sink, "popup gone", |f| f.pixels[10 + 40 * 120] == RED);

    // Then touches reach the toplevel, in logical coordinates.
    h.send(server::Command::Input(Input::TouchDown {
        id: 3,
        x: 100.0,
        y: 200.0,
    }));
    until(&mut q, &mut c, "touch on toplevel", |c| {
        c.touches.len() == 2
    });
    assert_eq!(c.touches[1].0.id(), surface.id());
    assert_eq!((c.touches[1].1, c.touches[1].2), (50.0, 100.0));

    popup.destroy();
    h.stop();
}
