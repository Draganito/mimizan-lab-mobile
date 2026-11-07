// Small pieces the viewfinder and the review share: chips, labelled
// sliders, the process and filter rows, the paper strip, the histogram.
import MimizanCore
import SwiftUI

struct Chip: View {
    let label: String
    let selected: Bool
    var tint: Color = Color(white: 0.23)
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.footnote)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(selected ? tint : Color(white: 0.1), in: Capsule())
                .overlay(Capsule().stroke(selected ? Color.white.opacity(0.5) : Color.white.opacity(0.15), lineWidth: 1))
                .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
    }
}

struct LabeledSlider: View {
    let label: String
    @Binding var value: Double
    var range: ClosedRange<Double> = 0...1

    var body: some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.caption.monospaced())
                .frame(width: 96, alignment: .leading)
            Slider(value: $value, in: range)
        }
    }
}

/// A shot, out of `modes`: JPEG, Developer, or Raw under a paper finder;
/// Developer or Raw where the finder is the camera's picture.
struct ShotRow: View {
    let mode: ShotMode
    let modes: [ShotMode]
    let onMode: (ShotMode) -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text("A shot")
            Spacer(minLength: 8)
            ForEach(modes, id: \.self) { m in
                Chip(label: m.label, selected: mode == m) { onMode(m) }
            }
        }
    }
}

/// Peak's mode out of `modes`: Off and Auto for the standing Peak in the
/// settings, Custom too in the developer, where its two sliders follow.
struct PeakRow: View {
    let mode: PeakMode
    let modes: [PeakMode]
    let onMode: (PeakMode) -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text("Peak")
            Spacer(minLength: 8)
            ForEach(modes, id: \.self) { m in
                Chip(label: m.label, selected: mode == m) { onMode(m) }
            }
        }
    }
}

/// Mono and Color (the plate process). The filters sit on their own row under this.
struct ProcessChoice: View {
    let plate: Bool
    let onProcess: (Process) -> Void

    var body: some View {
        HStack(spacing: 6) {
            Chip(label: "Mono", selected: !plate) { onProcess(.mono) }
            Chip(label: "Color", selected: plate, tint: Color(red: 0.29, green: 0.23, blue: 0.16)) { onProcess(.plate) }
        }
    }
}

struct FilterRow: View {
    let selected: Filter
    let onSelect: (Filter) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(Filter.all, id: \.self) { f in
                    Chip(label: Format.filterShort(f), selected: f == selected) { onSelect(f) }
                }
            }
            .padding(.vertical, 4)
        }
    }
}

struct PlateFilterRow: View {
    let selected: PlateFilters
    let hasMatrix: Bool
    let onSelect: (PlateFilters) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(PlateFilters.all, id: \.self) { f in
                    Chip(label: f == .camera && !hasMatrix ? "Camera (no matrix)" : Format.plateFilters(f), selected: f == selected) { onSelect(f) }
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// Settings, hand exposure and a paper off the base print are amber, so
/// a dark finder reads as a setting, not a fault.
extension Color {
    static let settingInk = Color(red: 1, green: 0.72, blue: 0.3)
}

/// The paper as one strip. It is drawn from the core's own paper table
/// (`paperLut` for the standing soft and hard exposures) over a linear
/// axis of `zones` stops around the grey card, so what the strip shows
/// is what the paper does to the picture; the ticks are the zones. One
/// finger drags the exposure: both exposures move by the same stops
/// (`shiftGrade`), the ramp slides under the mark in the middle, the
/// width of the strip is three stops. Two fingers spread the grade: the
/// ramp widens (softer) or closes (harder) around the middle, the mean
/// held (`withContrast`); the grade is kept within ±`contrastLimit` here
/// — a pair from before that lies beyond shows at the end and is clipped
/// only when touched. Both gestures together are allowed. A double tap
/// is the base print; a second double tap on a strip already there
/// brings the pair back. Behind it are the same two numbers as ever,
/// soft and hard, and the print is the same. White at the base print,
/// amber away from it.
struct PaperStrip: View {
    let soft: Double
    let hard: Double
    let onBoth: (Double, Double) -> Void
    /// The pair as a drag began; the grade as a spread began. Each
    /// gesture its own, since both may run at once and end apart.
    @State private var origin: (Double, Double)?
    @State private var pinchBase: Double?
    @State private var remembered: (Double, Double)?

    /// Stops of grade the strip reaches, each way: the core allows twice
    /// that; the phone's sensors never want it.
    static let contrastLimit: Double = Prefs.stopLimit
    /// Stops of linear grey the strip spans; the grey card in the middle.
    static let zones: Double = 10
    private static let samples = 192

    var body: some View {
        let off = abs(soft) >= 1.0 / 24 || abs(hard) >= 1.0 / 24
        let lut = PaperStrip.table(soft: soft, hard: hard)
        GeometryReader { g in
            let width = max(g.size.width, 1)
            Canvas { ctx, size in
                // The ramp: each slice the paper's value for the linear grey
                // of its stop, the grey card at 0.18 in the middle.
                let n = PaperStrip.samples
                let w = size.width / CGFloat(n)
                for i in 0..<n {
                    let stops = (Double(i) + 0.5) / Double(n) * PaperStrip.zones - PaperStrip.zones / 2
                    let linear = min(max(0.18 * pow(2, stops), 0), 1)
                    let v = Double(PaperStrip.value(lut, at: linear))
                    ctx.fill(Path(CGRect(x: CGFloat(i) * w, y: 0, width: w + 0.5, height: size.height)), with: .color(Color(white: v)))
                }
                // The zones, one stop apart, as ticks from the top edge.
                for z in 1..<Int(PaperStrip.zones) {
                    let x = size.width * CGFloat(Double(z) / PaperStrip.zones)
                    ctx.fill(Path(CGRect(x: x - 0.5, y: 0, width: 1, height: size.height * 0.3)), with: .color(.gray.opacity(0.6)))
                }
                // The mark: the grey card's place, never moving.
                let mx = size.width / 2
                var mark = Path()
                mark.move(to: CGPoint(x: mx - 5, y: size.height))
                mark.addLine(to: CGPoint(x: mx + 5, y: size.height))
                mark.addLine(to: CGPoint(x: mx, y: size.height - 7))
                mark.closeSubpath()
                ctx.fill(mark, with: .color(off ? .settingInk : .white))
            }
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(off ? Color.settingInk : Color.white.opacity(0.35), lineWidth: off ? 2 : 1))
            .contentShape(Rectangle())
            .onTapGesture(count: 2) {
                if off {
                    remembered = (soft, hard)
                    onBoth(0, 0)
                } else if let r = remembered {
                    onBoth(r.0, r.1)
                }
            }
            .highPriorityGesture(
                DragGesture(minimumDistance: 4)
                    .onChanged { v in
                        let base = origin ?? (soft, hard)
                        if origin == nil { origin = base }
                        let delta = -Double(v.translation.width) / Double(width) * Prefs.stopLimit
                        let pair = Prefs.shiftGrade(soft: base.0, hard: base.1, by: delta)
                        onBoth(pair.0, pair.1)
                    }
                    .onEnded { _ in origin = nil }
                    .simultaneously(with:
                        MagnificationGesture()
                            .onChanged { scale in
                                let base = pinchBase ?? Prefs.contrast(soft: soft, hard: hard)
                                if pinchBase == nil { pinchBase = base }
                                // Spreading the ramp is the softer paper: the
                                // grade falls by two stops per doubling. The
                                // mean is the current one, so a drag at the
                                // same time keeps its say.
                                let c = base - 2 * log2(max(Double(scale), 0.05))
                                let lim = PaperStrip.contrastLimit
                                let g = Prefs.withContrast(soft: soft, hard: hard, contrast: min(max(c, -lim), lim))
                                onBoth(g.soft, g.hard)
                            }
                            .onEnded { _ in pinchBase = nil }
                    )
            )
        }
        .frame(height: 36)
    }

    /// The paper over linear grey for this pair, from the core; a straight
    /// line only if the core refuses (it does not, inside ±`stopLimit`).
    private static func table(soft: Double, hard: Double) -> [Float] {
        (try? paperLut(soft: soft, hard: hard, n: 256)) ?? (0...256).map { Float($0) / 256 }
    }

    /// The table read at `linear`, between entries.
    private static func value(_ lut: [Float], at linear: Double) -> Float {
        guard lut.count > 1 else { return Float(linear) }
        let x = linear * Double(lut.count - 1)
        let i = min(Int(x), lut.count - 2)
        let f = Float(x - Double(i))
        return lut[i] * (1 - f) + lut[i + 1] * f
    }
}

struct ShutterButton: View {
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Circle()
                .fill(enabled ? Color.white : Color(white: 0.3))
                .padding(6)
                .overlay(Circle().stroke(enabled ? Color.white : Color.gray, lineWidth: 3))
                .frame(width: 76, height: 76)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// The luminance's histogram as bars; for a plate, `rgb` (three runs of
/// equal length, R then G then B) drawn as outlines over it.
struct HistogramView: View {
    let bins: [Float]
    var rgb: [Float] = []

    var body: some View {
        Canvas { ctx, size in
            guard !bins.isEmpty else { return }
            let w = size.width / CGFloat(bins.count)
            let alpha = rgb.isEmpty ? 0.85 : 0.45
            var bars = Path()
            for (i, v) in bins.enumerated() {
                let h = CGFloat(min(max(v, 0), 1)) * size.height
                bars.addRect(CGRect(x: CGFloat(i) * w, y: size.height - h, width: w, height: h))
            }
            ctx.fill(bars, with: .color(.white.opacity(alpha)))
            if rgb.count >= 3, rgb.count % 3 == 0 {
                let n = rgb.count / 3
                let step = size.width / CGFloat(n)
                let colours: [Color] = [Color(red: 1, green: 0.38, blue: 0.38), Color(red: 0.38, green: 1, blue: 0.38), Color(red: 0.44, green: 0.56, blue: 1)]
                for c in 0..<3 {
                    var p = Path()
                    for i in 0..<n {
                        let x = CGFloat(i) * step + step / 2
                        let y = size.height - CGFloat(min(max(rgb[c * n + i], 0), 1)) * size.height
                        if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
                    }
                    ctx.stroke(p, with: .color(colours[c]), lineWidth: 1.5)
                }
            }
        }
        .padding(2)
        .background(Color.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 4))
    }
}

/// A short notice at the bottom that goes away on its own.
struct Toast: ViewModifier {
    @Binding var message: String?
    var tint = Color(red: 0.25, green: 0.12, blue: 0.12)

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let m = message {
                Text(m)
                    .foregroundStyle(.white)
                    .padding(12)
                    .background(tint.opacity(0.85), in: RoundedRectangle(cornerRadius: 8))
                    .padding(.bottom, 120)
                    .task(id: m) {
                        try? await Task.sleep(for: .seconds(4))
                        if message == m { message = nil }
                    }
                    .transition(.opacity)
            }
        }
    }
}

extension View {
    func toast(_ message: Binding<String?>, tint: Color = Color(red: 0.25, green: 0.12, blue: 0.12)) -> some View {
        modifier(Toast(message: message, tint: tint))
    }
}
