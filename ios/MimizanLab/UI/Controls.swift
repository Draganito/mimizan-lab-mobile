// Small pieces the viewfinder and the review share: chips, labelled
// sliders, the process and filter rows, the paper sliders, the histogram.
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
    var step: Double? = nil
    var onEditingChanged: (Bool) -> Void = { _ in }

    var body: some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.caption.monospaced())
                .frame(width: 96, alignment: .leading)
            if let step {
                Slider(value: $value, in: range, step: step, onEditingChanged: onEditingChanged)
            } else {
                Slider(value: $value, in: range, onEditingChanged: onEditingChanged)
            }
        }
    }
}

struct SettingRow: View {
    let label: String
    @Binding var on: Bool

    var body: some View {
        Toggle(label, isOn: $on).padding(.vertical, 2)
    }
}

/// The darkroom's process, *Mono · Plate*, and, for the plate, its taking
/// filters and primaries. One scrolling row the viewfinder and the review share.
struct ProcessRow: View {
    let plate: Bool
    let filters: PlateFilters
    let space: PlateSpace
    let hasMatrix: Bool
    let onProcess: (Process) -> Void
    let onFilters: (PlateFilters) -> Void
    let onSpace: (PlateSpace) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                Chip(label: "Mono", selected: !plate) { onProcess(.mono) }
                Chip(label: "Plate", selected: plate, tint: Color(red: 0.29, green: 0.23, blue: 0.16)) { onProcess(.plate) }
                if plate {
                    Spacer().frame(width: 6)
                    ForEach(PlateFilters.all, id: \.self) { f in
                        Chip(label: f == .camera && !hasMatrix ? "Camera (no matrix)" : Format.plateFilters(f), selected: f == filters) { onFilters(f) }
                    }
                    Spacer().frame(width: 6)
                    ForEach(PlateSpace.all, id: \.self) { sp in
                        Chip(label: Format.plateSpace(sp), selected: sp == space) { onSpace(sp) }
                    }
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
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
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
        }
    }
}

struct PaperSliders: View {
    let soft: Double
    let hard: Double
    let onSoft: (Double) -> Void
    let onHard: (Double) -> Void

    var body: some View {
        let range = -Prefs.stopLimit...Prefs.stopLimit
        VStack(alignment: .leading, spacing: 2) {
            if let g = try? paperGrade(soft: soft, hard: hard) {
                Text(String(format: "≈ grade %@ · %.1f stops of scene", g.label, g.sceneStops))
                    .font(.caption.monospaced())
            }
            LabeledSlider(label: String(format: "soft %+.2f", soft), value: Binding(get: { soft }, set: onSoft), range: range, step: 1.0 / 12)
            Text("filter 00").font(.caption2).foregroundStyle(.gray)
            LabeledSlider(label: String(format: "hard %+.2f", hard), value: Binding(get: { hard }, set: onHard), range: range, step: 1.0 / 12)
            Text("filter 5").font(.caption2).foregroundStyle(.gray)
        }
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
