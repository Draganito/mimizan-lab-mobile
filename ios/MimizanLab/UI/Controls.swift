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

/// The taking filters as one row over the paper strip, the width shared
/// in equal fields, one type size whichever process stands: in Mono the
/// seven glasses (Pan and the six Wratten numbers), in Color the three
/// ways to the plate. The chosen one is written in its own colour — the
/// glass in front of the lens, read without reading — with a line under
/// it that slides over on a change; the others grey. A tap chooses, a
/// finger drawn along the row scrubs through them with the picture
/// following. A field that cannot be used as it stands (Camera without
/// the lens's matrix yet) is dimmed, and still answers: the fallback
/// shows what it is.
struct FilterStrip: View {
    let labels: [String]
    let inks: [Color]
    let selected: Int
    var dimmed: Set<Int> = []
    let onSelect: (Int) -> Void

    static func mono(selected: Filter, onSelect: @escaping (Filter) -> Void) -> FilterStrip {
        FilterStrip(
            labels: Filter.all.map(Format.filterShort), inks: Filter.all.map(FilterStrip.ink),
            selected: Filter.all.firstIndex(of: selected) ?? 0,
            onSelect: { onSelect(Filter.all[$0]) }
        )
    }

    static func plate(selected: PlateFilters, hasMatrix: Bool, onSelect: @escaping (PlateFilters) -> Void) -> FilterStrip {
        FilterStrip(
            labels: PlateFilters.all.map(Format.plateFilters), inks: PlateFilters.all.map { _ in .white },
            selected: PlateFilters.all.firstIndex(of: selected) ?? 0,
            dimmed: hasMatrix ? [] : [PlateFilters.all.firstIndex(of: .camera) ?? 0],
            onSelect: { onSelect(PlateFilters.all[$0]) }
        )
    }

    /// The glass's own colour, as it looks held up to the light.
    static func ink(_ f: Filter) -> Color {
        switch f {
        case .none: return .white
        case .yellow8: return Color(red: 1.0, green: 0.85, blue: 0.2)
        case .yellowGreen11: return Color(red: 0.75, green: 0.9, blue: 0.25)
        case .orange16: return Color(red: 1.0, green: 0.6, blue: 0.2)
        case .red25: return Color(red: 1.0, green: 0.32, blue: 0.26)
        case .green58: return Color(red: 0.3, green: 0.85, blue: 0.4)
        case .blue47: return Color(red: 0.4, green: 0.58, blue: 1.0)
        }
    }

    var body: some View {
        let n = max(labels.count, 1)
        GeometryReader { g in
            let w = g.size.width / CGFloat(n)
            ZStack(alignment: .bottomLeading) {
                HStack(spacing: 0) {
                    ForEach(labels.indices, id: \.self) { i in
                        Text(labels[i])
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .foregroundStyle(i == selected ? inks[i] : Color.gray.opacity(dimmed.contains(i) ? 0.4 : 0.8))
                            .frame(width: w, height: g.size.height)
                            .contentShape(Rectangle())
                            .onTapGesture { onSelect(i) }
                    }
                }
                Rectangle()
                    .fill(inks[min(selected, inks.count - 1)])
                    .frame(width: w * 0.6, height: 2)
                    .offset(x: w * CGFloat(selected) + w * 0.2)
                    .animation(.easeOut(duration: 0.15), value: selected)
            }
            .highPriorityGesture(
                DragGesture(minimumDistance: 8)
                    .onChanged { v in
                        let i = min(max(Int(v.location.x / w), 0), n - 1)
                        if i != selected { onSelect(i) }
                    }
            )
        }
        .frame(height: 26)
    }
}

/// Settings, hand exposure and a paper off the base print are amber, so
/// a dark finder reads as a setting, not a fault.
extension Color {
    static let settingInk = Color(red: 1, green: 0.72, blue: 0.3)
}

/// The paper as one strip. It is drawn from the core's own paper table
/// (`paperLut` for the standing soft and hard exposures) over the scene,
/// a zone to the stop, from five stops under the grey card to the clip
/// two and a half above it, where the negative ends (`linearAt`): the
/// strip is the print of a grey wedge, every slice a grey of its own,
/// and what it shows is what the paper does to the picture. The ticks
/// are the zones; the card stands at the mark, two thirds along. One
/// finger drags the exposure: both exposures move by the same stops
/// (`shiftGrade`), the ramp slides under the mark, the
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
    /// Linear value of the grey card: the core's `zone::GRAY_CARD`.
    static let greyCard: Double = 0.18
    /// Stops of the scene the strip shows under the grey card.
    static let below: Double = 5
    /// Stops of the scene over the grey card: up to the clip, linear 1, where the negative ends.
    static let above: Double = -log2(greyCard)
    private static let samples = 192

    /// Where a grey `stops` from the grey card stands, as a part of the
    /// strip's width. The card itself is at `fracOf(0)`, the mark.
    static func fracOf(_ stops: Double) -> Double { (stops + below) / (below + above) }

    /// Linear grey at `frac` of the strip's width: `below` stops under
    /// the grey card at the left edge, the clip at the right. Twin of
    /// the Android `GradeMath.stripLinearAt`.
    static func linearAt(_ frac: Double) -> Double {
        min(max(greyCard * pow(2, frac * (below + above) - below), 0), 1)
    }

    /// The whole stops from the grey card that lie inside the strip, the edges left out: the ticks.
    static var ticks: [Double] { Array(stride(from: 1 - below.rounded(.up), to: above, by: 1)) }

    var body: some View {
        let off = abs(soft) >= 1.0 / 24 || abs(hard) >= 1.0 / 24
        let lut = PaperStrip.table(soft: soft, hard: hard)
        GeometryReader { g in
            let width = max(g.size.width, 1)
            Canvas { ctx, size in
                // The ramp: each slice the paper's value for the linear grey
                // of its stop.
                let n = PaperStrip.samples
                let w = size.width / CGFloat(n)
                for i in 0..<n {
                    let linear = PaperStrip.linearAt((Double(i) + 0.5) / Double(n))
                    let v = Double(PaperStrip.value(lut, at: linear))
                    ctx.fill(Path(CGRect(x: CGFloat(i) * w, y: 0, width: w + 0.5, height: size.height)), with: .color(Color(white: v)))
                }
                // The zones, whole stops from the grey card, as ticks from the top edge.
                for stops in PaperStrip.ticks {
                    let x = size.width * CGFloat(PaperStrip.fracOf(stops))
                    ctx.fill(Path(CGRect(x: x - 0.5, y: 0, width: 1, height: size.height * 0.3)), with: .color(.gray.opacity(0.6)))
                }
                // The mark: the grey card's place, never moving.
                let mx = size.width * CGFloat(PaperStrip.fracOf(0))
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
