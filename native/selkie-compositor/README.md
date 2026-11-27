# selkie-compositor

Selkie's in-app Wayland compositor (Rust, `wayland-server` pure-Rust backend, no C dependencies).
Guest programs under proot connect to `$XDG_RUNTIME_DIR/wayland-0`, which is Selkie's
`run/user/<uid>/wayland-0` on the host, and their full-screen toplevel is drawn into the Android
`Surface` of `DesktopScreen`.

Clients are either a nested session compositor (KWin, cosmic-comp, gamescope, weston, ...) or
`droidc bridge`, which presents a headless one (gnome-shell). See droidc's `docs/design-mvp.md`,
"Graphical session".

Supported: `wl_compositor`, `wl_subcompositor` (accepted, not composited), `wl_shm` (argb8888,
xrgb8888), `wl_output`, `wl_seat` (pointer, keyboard with a bundled US keymap, touch), `xdg_wm_base`
(toplevels full screen at the view size; popups are dismissed), `wl_data_device_manager` (inert).
One toplevel is shown at a time: the most recently mapped. Buffers are copied on commit and released
at once; frame callbacks complete after the frame is on screen, so clients pause while the app has no
surface.

**App mode** (`server::start_app`, `NativeCompositor.startApp`) shows one ordinary app on its own,
for an app opened from a home screen shortcut. The output has a scale (pass Android's density):
`wl_output.scale` rounded up, `wl_surface.preferred_buffer_scale` (`wl_compositor` v6),
`wp_fractional_scale_v1` and `wp_viewporter`. The toplevel is configured maximized and tiled at the
logical size (a 1080x2340 phone at 2.625 is 411x891), so adaptive apps take their phone layout.
Subsurfaces and `xdg_popup`s (menus, popovers, tooltips) are placed from their positioners and drawn
with it into one frame; a touch or click outside a grabbing popup dismisses it. Session mode is
unchanged.

```
cargo test                                   # protocol tests with a real wayland-client
cargo ndk -t arm64-v8a -t x86_64 --platform 26 build --release
cargo run --release --bin selkie-compositor-headless -- /run/user/$UID/wayland-9 1280x800 out.ppm
cargo run --release --bin selkie-compositor-headless -- /run/user/$UID/wayland-9 1080x2340 out.ppm 2.625
```

The headless tool runs the compositor on Linux and writes the shown frame to a PPM (a fourth
argument runs app mode at that scale); stdin takes `key CODE 0|1`, `tap X Y`, `move X Y`,
`button CODE 0|1`, `scroll DX DY`, `size W H`, `save`, `stats`, `quit`.

`src/us.xkb` is the US layout compiled with `xkbcomp` from xkeyboard-config (evdev keycodes,
`pc+us+inet(evdev)`); xkeyboard-config is under the MIT/X11 license.
