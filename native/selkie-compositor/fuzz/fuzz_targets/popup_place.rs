//! `Positioner::place` with a guest's xdg_positioner: size, anchor rectangle, anchor, gravity,
//! constraint adjustment and offset all come straight from the client's requests, unchecked.
//! Placing the popup must not crash the compositor.
#![no_main]

use libfuzzer_sys::fuzz_target;
use selkie_compositor::scene::{Positioner, Rect};

fuzz_target!(|input: (
    (i32, i32),
    (i32, i32, i32, i32),
    u32,
    u32,
    u32,
    (i32, i32),
    (i32, i32, i32, i32)
)| {
    let (size, anchor_rect, anchor, gravity, constraint_adjustment, offset, bounds) = input;
    let positioner = Positioner {
        size,
        anchor_rect: Rect::new(anchor_rect.0, anchor_rect.1, anchor_rect.2, anchor_rect.3),
        anchor,
        gravity,
        constraint_adjustment,
        offset,
    };
    let _ = positioner.place(Rect::new(bounds.0, bounds.1, bounds.2, bounds.3));
});
