//! Runs the compositor without Android, for testing guest sessions on Linux:
//! `selkie-compositor-headless SOCKET WIDTHxHEIGHT OUT.ppm`. The latest frame is written to OUT.ppm
//! (at most once a second). Input comes from stdin, one command per line:
//! `key CODE 0|1`, `tap X Y`, `move X Y`, `button CODE 0|1`, `scroll DX DY`, `size W H`, `quit`.
//! `stats` prints the compositor's stats as JSON.

use selkie_compositor::{server, Frame, FrameSink, Input};
use std::io::BufRead;
use std::time::{Duration, Instant};

struct FileSink {
    path: std::path::PathBuf,
    last: Option<Instant>,
}

impl FrameSink for FileSink {
    fn present(&mut self, frame: &Frame) {
        if self
            .last
            .is_some_and(|t| t.elapsed() < Duration::from_secs(1))
        {
            return;
        }
        self.last = Some(Instant::now());
        let tmp = self.path.with_extension("tmp");
        if std::fs::write(&tmp, frame.to_ppm()).is_ok() {
            let _ = std::fs::rename(&tmp, &self.path);
        }
    }
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() != 4 {
        eprintln!("usage: {} SOCKET WIDTHxHEIGHT OUT.ppm", a[0]);
        std::process::exit(2);
    }
    let (w, h) = a[2]
        .split_once('x')
        .map(|(w, h)| (w.parse().unwrap(), h.parse().unwrap()))
        .expect("WIDTHxHEIGHT");
    let sink = FileSink {
        path: a[3].clone().into(),
        last: None,
    };
    let c = server::start(std::path::Path::new(&a[1]), w, h, Some(Box::new(sink))).expect("start");
    eprintln!("listening on {}", a[1]);
    for line in std::io::stdin().lock().lines() {
        let Ok(line) = line else { break };
        let f: Vec<&str> = line.split_whitespace().collect();
        let n = |i: usize| f.get(i).and_then(|v| v.parse::<f64>().ok()).unwrap_or(0.0);
        let input = match f.first().copied() {
            Some("key") => Input::Key {
                code: n(1) as u32,
                pressed: n(2) != 0.0,
            },
            Some("move") => Input::PointerMotion { x: n(1), y: n(2) },
            Some("button") => Input::PointerButton {
                button: n(1) as u32,
                pressed: n(2) != 0.0,
            },
            Some("scroll") => Input::PointerAxis { dx: n(1), dy: n(2) },
            Some("tap") => {
                c.send(server::Command::Input(Input::TouchDown {
                    id: 0,
                    x: n(1),
                    y: n(2),
                }));
                Input::TouchUp { id: 0 }
            }
            Some("size") => {
                c.send(server::Command::Output {
                    width: n(1) as u32,
                    height: n(2) as u32,
                });
                continue;
            }
            Some("stats") => {
                println!("{}", c.stats.lock().unwrap().to_json());
                continue;
            }
            Some("quit") => break,
            _ => continue,
        };
        c.send(server::Command::Input(input));
    }
    c.stop();
}
