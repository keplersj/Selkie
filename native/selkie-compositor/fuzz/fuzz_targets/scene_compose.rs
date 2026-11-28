//! `scene::compose` with guest-chosen surface placement: a client picks each surface's buffer size,
//! where it goes (`dst`, from xdg/subsurface positions and wp_viewport destinations) and which part
//! of the buffer is shown (`src`, from wp_viewport). None of that may crash the compositor or draw
//! outside the frame.
#![no_main]

use arbitrary::Arbitrary;
use libfuzzer_sys::fuzz_target;
use selkie_compositor::scene::{compose, Layer, Rect};
use selkie_compositor::Frame;
use std::sync::Arc;

#[derive(Arbitrary, Debug)]
struct Input {
    width: u16,
    height: u16,
    /// The output scale comes from Android, not the guest: 0.5 to about 8.5 in 1/32 steps.
    scale_step: u8,
    layers: Vec<Surface>,
}

#[derive(Arbitrary, Debug)]
struct Surface {
    buffer_w: u8,
    buffer_h: u8,
    fill: u32,
    dst: (i32, i32, i32, i32),
    src: Option<(f64, f64, f64, f64)>,
}

fuzz_target!(|input: Input| {
    // Outputs up to 1024x1024 keep each run fast; the arithmetic is the same at any size.
    let width = u32::from(input.width % 1025);
    let height = u32::from(input.height % 1025);
    let scale = 0.5 + f64::from(input.scale_step) / 32.0;
    let layers: Vec<Layer> = input
        .layers
        .into_iter()
        .take(8)
        .map(|s| Layer {
            content: Arc::new(Frame {
                width: u32::from(s.buffer_w),
                height: u32::from(s.buffer_h),
                pixels: vec![s.fill; usize::from(s.buffer_w) * usize::from(s.buffer_h)],
            }),
            dst: Rect::new(s.dst.0, s.dst.1, s.dst.2, s.dst.3),
            src: s.src,
        })
        .collect();
    let frame = compose(width, height, scale, &layers);
    assert_eq!(frame.width, width);
    assert_eq!(frame.height, height);
    assert_eq!(frame.pixels.len(), width as usize * height as usize);
});
