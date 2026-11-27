//! Drawing an app's surfaces into one frame, and placing its popups (app mode, see
//! [`crate::server::start_app`]).
//!
//! Surfaces live in logical coordinates; the output is `scale` output pixels per logical pixel.
//! Each surface's buffer is copied on commit and sampled here (nearest pixel) into an output-sized
//! frame, so a buffer drawn at the output's scale lands one to one.

use crate::Frame;
use std::sync::Arc;

/// A rectangle in logical pixels.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

impl Rect {
    pub fn new(x: i32, y: i32, w: i32, h: i32) -> Rect {
        Rect { x, y, w, h }
    }

    pub fn contains(&self, x: f64, y: f64) -> bool {
        x >= self.x as f64
            && y >= self.y as f64
            && x < (self.x + self.w) as f64
            && y < (self.y + self.h) as f64
    }
}

/// One surface's picture: `content` drawn over `dst` (logical), taken from `src` of the buffer
/// (buffer pixels, `None` = all of it).
pub struct Layer {
    pub content: Arc<Frame>,
    pub dst: Rect,
    pub src: Option<(f64, f64, f64, f64)>,
}

/// The logical size of an output of `width`x`height` pixels at `scale`.
pub fn logical_size(width: u32, height: u32, scale: f64) -> (i32, i32) {
    let s = if scale.is_finite() && scale > 0.0 {
        scale
    } else {
        1.0
    };
    (
        ((width as f64 / s).floor() as i32).max(1),
        ((height as f64 / s).floor() as i32).max(1),
    )
}

/// Draws `layers` (back to front) over black into a `width`x`height` frame at `scale`.
/// Buffers are premultiplied ARGB (wl_shm's argb8888), so blending is `src + dst * (1 - a)`.
pub fn compose(width: u32, height: u32, scale: f64, layers: &[Layer]) -> Frame {
    let (w, h) = (width as usize, height as usize);
    let mut pixels = vec![0xff00_0000u32; w * h];
    for l in layers {
        let c = &l.content;
        if c.width == 0 || c.height == 0 || l.dst.w <= 0 || l.dst.h <= 0 {
            continue;
        }
        let (sx, sy, sw, sh) = l.src.unwrap_or((0.0, 0.0, c.width as f64, c.height as f64));
        // Output pixel bounds of the destination.
        let ox0 = (l.dst.x as f64 * scale).round();
        let oy0 = (l.dst.y as f64 * scale).round();
        let ox1 = ((l.dst.x + l.dst.w) as f64 * scale).round();
        let oy1 = ((l.dst.y + l.dst.h) as f64 * scale).round();
        let (dw, dh) = (ox1 - ox0, oy1 - oy0);
        if dw <= 0.0 || dh <= 0.0 {
            continue;
        }
        let x_start = ox0.max(0.0) as usize;
        let x_end = (ox1.min(w as f64)).max(0.0) as usize;
        let y_start = oy0.max(0.0) as usize;
        let y_end = (oy1.min(h as f64)).max(0.0) as usize;
        if x_start >= x_end || y_start >= y_end {
            continue;
        }
        // Buffer column for each output column, row for each output row (pixel centres).
        let cols: Vec<usize> = (x_start..x_end)
            .map(|x| {
                let t = (x as f64 + 0.5 - ox0) / dw;
                ((sx + t * sw).floor().max(0.0) as usize).min(c.width as usize - 1)
            })
            .collect();
        for y in y_start..y_end {
            let t = (y as f64 + 0.5 - oy0) / dh;
            let by = ((sy + t * sh).floor().max(0.0) as usize).min(c.height as usize - 1);
            let src_row = &c.pixels[by * c.width as usize..(by + 1) * c.width as usize];
            let dst_row = &mut pixels[y * w + x_start..y * w + x_end];
            for (d, &bx) in dst_row.iter_mut().zip(&cols) {
                *d = over(src_row[bx], *d);
            }
        }
    }
    Frame {
        width,
        height,
        pixels,
    }
}

/// Premultiplied `src` over opaque `dst`.
fn over(src: u32, dst: u32) -> u32 {
    let a = src >> 24;
    if a == 0xff {
        return src;
    }
    if a == 0 {
        return dst;
    }
    let inv = 255 - a;
    let ch = |shift: u32| {
        let s = (src >> shift) & 0xff;
        let d = (dst >> shift) & 0xff;
        (s + (d * inv + 127) / 255).min(255) << shift
    };
    0xff00_0000 | ch(16) | ch(8) | ch(0)
}

/// xdg_positioner state (see xdg-shell). Anchor and gravity use the protocol's enum values.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Positioner {
    pub size: (i32, i32),
    pub anchor_rect: Rect,
    pub anchor: u32,
    pub gravity: u32,
    pub constraint_adjustment: u32,
    pub offset: (i32, i32),
}

// xdg_positioner.anchor / gravity: none, top, bottom, left, right, top_left, bottom_left,
// top_right, bottom_right.
fn horizontal(edge: u32) -> i32 {
    match edge {
        3 | 5 | 6 => -1, // left
        4 | 7 | 8 => 1,  // right
        _ => 0,
    }
}

fn vertical(edge: u32) -> i32 {
    match edge {
        1 | 5 | 7 => -1, // top
        2 | 6 | 8 => 1,  // bottom
        _ => 0,
    }
}

fn edge(h: i32, v: i32) -> u32 {
    match (h, v) {
        (0, 0) => 0,
        (0, -1) => 1,
        (0, 1) => 2,
        (-1, 0) => 3,
        (1, 0) => 4,
        (-1, -1) => 5,
        (-1, 1) => 6,
        (1, -1) => 7,
        _ => 8,
    }
}

const SLIDE_X: u32 = 1;
const SLIDE_Y: u32 = 2;
const FLIP_X: u32 = 4;
const FLIP_Y: u32 = 8;
const RESIZE_X: u32 = 16;
const RESIZE_Y: u32 = 32;

impl Positioner {
    /// The popup's rectangle relative to its parent's window geometry, before constraints.
    fn unconstrained(&self, anchor: u32, gravity: u32, offset: (i32, i32)) -> Rect {
        let a = self.anchor_rect;
        let ax = match horizontal(anchor) {
            -1 => a.x,
            1 => a.x + a.w,
            _ => a.x + a.w / 2,
        };
        let ay = match vertical(anchor) {
            -1 => a.y,
            1 => a.y + a.h,
            _ => a.y + a.h / 2,
        };
        let (w, h) = self.size;
        let x = match horizontal(gravity) {
            -1 => ax - w,
            1 => ax,
            _ => ax - w / 2,
        };
        let y = match vertical(gravity) {
            -1 => ay - h,
            1 => ay,
            _ => ay - h / 2,
        };
        Rect::new(x + offset.0, y + offset.1, w, h)
    }

    /// Places the popup within `bounds` (both relative to the parent's window geometry), following
    /// the constraint adjustments the client allows: flip, then slide, then resize.
    pub fn place(&self, bounds: Rect) -> Rect {
        let mut r = self.unconstrained(self.anchor, self.gravity, self.offset);
        let ca = self.constraint_adjustment;
        let out_x = |r: &Rect| r.x < bounds.x || r.x + r.w > bounds.x + bounds.w;
        let out_y = |r: &Rect| r.y < bounds.y || r.y + r.h > bounds.y + bounds.h;
        if out_x(&r) && ca & FLIP_X != 0 {
            let anchor = edge(-horizontal(self.anchor), vertical(self.anchor));
            let gravity = edge(-horizontal(self.gravity), vertical(self.gravity));
            let f = self.unconstrained(anchor, gravity, (-self.offset.0, self.offset.1));
            if !out_x(&f) {
                r.x = f.x;
            }
        }
        if out_y(&r) && ca & FLIP_Y != 0 {
            let anchor = edge(horizontal(self.anchor), -vertical(self.anchor));
            let gravity = edge(horizontal(self.gravity), -vertical(self.gravity));
            let f = self.unconstrained(anchor, gravity, (self.offset.0, -self.offset.1));
            if !out_y(&f) {
                r.y = f.y;
            }
        }
        if out_x(&r) && ca & SLIDE_X != 0 {
            r.x = r.x.min(bounds.x + bounds.w - r.w).max(bounds.x);
        }
        if out_y(&r) && ca & SLIDE_Y != 0 {
            r.y = r.y.min(bounds.y + bounds.h - r.h).max(bounds.y);
        }
        if ca & RESIZE_X != 0 {
            let x0 = r.x.max(bounds.x);
            let x1 = (r.x + r.w).min(bounds.x + bounds.w);
            if x1 > x0 {
                r.x = x0;
                r.w = x1 - x0;
            }
        }
        if ca & RESIZE_Y != 0 {
            let y0 = r.y.max(bounds.y);
            let y1 = (r.y + r.h).min(bounds.y + bounds.h);
            if y1 > y0 {
                r.y = y0;
                r.h = y1 - y0;
            }
        }
        r
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(w: u32, h: u32, p: u32) -> Arc<Frame> {
        Arc::new(Frame {
            width: w,
            height: h,
            pixels: vec![p; (w * h) as usize],
        })
    }

    #[test]
    fn logical_size_of_a_phone() {
        assert_eq!(logical_size(1080, 2340, 2.625), (411, 891));
        assert_eq!(logical_size(2560, 1600, 2.0), (1280, 800));
        assert_eq!(logical_size(800, 600, 0.0), (800, 600));
    }

    #[test]
    fn buffer_at_scale_lands_one_to_one() {
        // A 2x buffer of a 4x2 logical surface on an 8x4 output at 2x.
        let mut px = vec![0u32; 32];
        for (i, p) in px.iter_mut().enumerate() {
            *p = 0xff00_0000 | i as u32;
        }
        let c = Arc::new(Frame {
            width: 8,
            height: 4,
            pixels: px.clone(),
        });
        let f = compose(
            8,
            4,
            2.0,
            &[Layer {
                content: c,
                dst: Rect::new(0, 0, 4, 2),
                src: None,
            }],
        );
        assert_eq!(f.pixels, px);
    }

    #[test]
    fn one_x_buffer_is_scaled_up_and_rest_is_black() {
        let f = compose(
            6,
            6,
            3.0,
            &[Layer {
                content: solid(1, 1, 0xffff_0000),
                dst: Rect::new(0, 0, 1, 1),
                src: None,
            }],
        );
        assert_eq!(f.pixels[0], 0xffff_0000);
        assert_eq!(f.pixels[2 * 6 + 2], 0xffff_0000);
        assert_eq!(f.pixels[3], 0xff00_0000);
        assert_eq!(f.pixels[3 * 6], 0xff00_0000);
    }

    #[test]
    fn popup_blends_over_toplevel() {
        let f = compose(
            4,
            4,
            1.0,
            &[
                Layer {
                    content: solid(4, 4, 0xffff_ffff),
                    dst: Rect::new(0, 0, 4, 4),
                    src: None,
                },
                Layer {
                    content: solid(2, 2, 0x8000_0000),
                    dst: Rect::new(1, 1, 2, 2),
                    src: None,
                },
            ],
        );
        assert_eq!(f.pixels[0], 0xffff_ffff);
        let mid = f.pixels[5];
        assert!((0xff7e_7e7e..=0xff80_8080).contains(&mid), "{mid:08x}");
    }

    #[test]
    fn popup_below_a_button() {
        let p = Positioner {
            size: (200, 300),
            anchor_rect: Rect::new(300, 10, 40, 40),
            anchor: 2,  // bottom
            gravity: 2, // bottom
            constraint_adjustment: SLIDE_X | FLIP_Y | SLIDE_Y,
            offset: (0, 0),
        };
        // Centred under the button, slid back inside a 360 wide screen.
        assert_eq!(
            p.place(Rect::new(0, 0, 360, 800)),
            Rect::new(160, 50, 200, 300)
        );
    }

    #[test]
    fn popup_flips_above_near_the_bottom() {
        let p = Positioner {
            size: (100, 200),
            anchor_rect: Rect::new(50, 700, 40, 40),
            anchor: 2,
            gravity: 2,
            constraint_adjustment: FLIP_Y,
            offset: (0, 4),
        };
        assert_eq!(
            p.place(Rect::new(0, 0, 360, 800)),
            Rect::new(20, 496, 100, 200)
        );
    }
}
