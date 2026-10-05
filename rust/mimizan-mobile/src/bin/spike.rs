//! Device spike: time and peak memory of the development on a synthetic
//! Pixel-9-sized frame (4080x3072 RGGB, 10 bit). Runs anywhere; on the phone
//! via `scripts/device_spike.sh` (adb push + run in /data/local/tmp).
//!
//! Usage: spike [width height] [--repeat N]

use mimizan_mobile::*;
use std::time::Instant;

fn peak_rss_mb() -> Option<f64> {
    let s = std::fs::read_to_string("/proc/self/status").ok()?;
    let line = s.lines().find(|l| l.starts_with("VmHWM:"))?;
    let kb: f64 = line.split_whitespace().nth(1)?.parse().ok()?;
    Some(kb / 1024.0)
}

fn synthetic(w: u32, h: u32) -> RawInput {
    // Smooth colour gradients plus a fine checker in one quadrant: enough
    // structure for the adaptive separation to do real work.
    let (black, white) = (64.0f64, 1023.0f64);
    let mut data = Vec::with_capacity((w * h * 2) as usize);
    for y in 0..h {
        for x in 0..w {
            let fx = x as f64 / w as f64;
            let fy = y as f64 / h as f64;
            let (r, g, b) = (0.2 + 0.6 * fx, 0.3 + 0.5 * fy, 0.6 - 0.4 * fx * fy);
            let checker = if x > w / 2 && y > h / 2 && ((x / 3) + (y / 3)) % 2 == 0 { 0.15 } else { 0.0 };
            let v = match (x & 1, y & 1) {
                (0, 0) => r,
                (1, 1) => b,
                _ => g,
            } + checker;
            let dn = (black + v.clamp(0.0, 1.0) * (white - black)).round() as u16;
            data.extend_from_slice(&dn.to_le_bytes());
        }
    }
    RawInput {
        make: "Google".into(),
        model: "Pixel 9".into(),
        width: w,
        height: h,
        pattern: BayerPattern::Rggb,
        black_tile: vec![black; 4],
        white_level: white,
        crop: None,
        orientation: 6,
        wb_gains: Some(vec![2.1, 1.0, 1.0, 1.6]),
        xyz_to_cam: None,
        exposure: None,
        data,
    }
}

fn run(name: &str, input: RawInput, params: DevelopParams) {
    let t = Instant::now();
    let dev = develop(input, params).expect("develop");
    let ms = t.elapsed().as_millis();
    let info = dev.info();
    let t = Instant::now();
    let _ = dev.preview(look_reference(), 0);
    let preview_ms = t.elapsed().as_millis();
    let t = Instant::now();
    let _ = dev.remix(Weights { r: 0.4, g: 0.4, b: 0.2 }, WeightSpace::Balanced, Filter::Yellow8);
    let _ = dev.preview(look_reference(), 0);
    let remix_ms = t.elapsed().as_millis();
    println!(
        "{name:<10} {}x{}  develop {ms:>6} ms (ingest {} / separate {} / mix {} / preview planes {})  preview {preview_ms} ms  remix+preview {remix_ms} ms  peak RSS {:.0} MB",
        info.width,
        info.height,
        info.timing.ingest_ms,
        info.timing.separate_ms,
        info.timing.mix_ms,
        info.timing.preview_ms,
        peak_rss_mb().unwrap_or(f64::NAN)
    );
    drop(dev);
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut dims = (4080u32, 3072u32);
    let mut repeat = 1;
    let mut i = 0;
    while i < args.len() {
        if args[i] == "--repeat" {
            repeat = args.get(i + 1).and_then(|s| s.parse().ok()).unwrap_or(1);
            i += 2;
        } else if i + 1 < args.len() {
            dims = (args[i].parse().unwrap_or(dims.0), args[i + 1].parse().unwrap_or(dims.1));
            i += 2;
        } else {
            i += 1;
        }
    }
    println!("mimizan-mobile {} / core {} / {} threads", version(), core_version(), worker_threads());
    for _ in 0..repeat {
        let base = DevelopParams::default();
        run("dubois", synthetic(dims.0, dims.1), base.clone());
        run("mask-off", synthetic(dims.0, dims.1), DevelopParams { separation: SeparationMode::Off, ..base.clone() });
        run("binned-2x", synthetic(dims.0, dims.1), DevelopParams { binning: 2, ..base.clone() });
    }
}
