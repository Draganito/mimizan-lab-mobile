// The review screen: the print fitted to the whole screen, and the
// paper, Peak and the mix on a milky panel over it. Mirrors
// `ReviewScreen.kt`.
import MimizanCore
import SwiftUI

struct ReviewView: View {
    let name: String
    @State private var vm = ReviewViewModel()
    @State private var showInfo = false
    /// Height of the controls, so the panel wraps them and only scrolls past this cap.
    @State private var panelHeight: CGFloat = 0

    var body: some View {
        let shot = vm.shot
        let prefs = vm.darkroom.prefs
        ZStack(alignment: .bottom) {
            ZStack {
                if let pv = vm.preview, let info = vm.info {
                    ZoomableNegative(
                        preview: pv, fullWidth: Int(info.uprightWidth), fullHeight: Int(info.uprightHeight), detail: vm.detail,
                        onVisibleRegion: { x, y, w, h in vm.requestDetail(x: x, y: y, w: w, h: h) },
                        onZoomedOut: { vm.clearDetail() }
                    )
                } else if vm.needsDevelop {
                    DevelopPrompt(status: shot?.status) { vm.develop() }
                } else {
                    ProgressView().tint(.white)
                }
                if vm.busy || vm.exporting != nil {
                    VStack { ProgressView().progressViewStyle(.linear).tint(.white); Spacer() }
                }
                if !vm.histogram.isEmpty {
                    VStack {
                        HStack {
                            Spacer()
                            HistogramView(bins: vm.histogram, rgb: vm.histogramRgb).frame(width: 128, height: 48).padding(8)
                        }
                        Spacer()
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea()

            if vm.info != nil {
                ScrollView {
                    controls(prefs: prefs)
                        .padding(.horizontal, 12)
                        .padding(.top, 12)
                        .padding(.bottom, 8)
                        .background {
                            GeometryReader { g in
                                Color.clear.preference(key: PanelHeightKey.self, value: g.size.height)
                            }
                        }
                }
                .scrollIndicators(.hidden)
                .frame(height: panelHeight > 0 ? min(panelHeight, 360) : 360)
                .background {
                    ZStack {
                        Rectangle().fill(.ultraThinMaterial)
                        Color.black.opacity(0.22)
                    }
                }
                .overlay(alignment: .top) {
                    Rectangle().fill(Color.white.opacity(0.14)).frame(height: 0.5)
                }
            }
        }
        .onPreferenceChange(PanelHeightKey.self) { panelHeight = $0 }
        .background(Color.black.ignoresSafeArea())
        .foregroundStyle(.white)
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(name).font(.caption.monospaced())
                    if let m = shot?.meta {
                        Text("\(m.lens) · \(Format.iso(m.iso)) · \(Format.shutter(m.exposureNs))" + (m.fNumber.map { String(format: " · f/%.1f", $0) } ?? ""))
                            .font(.caption2).foregroundStyle(.gray)
                    }
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { showInfo = true } label: { Image(systemName: "info.circle") }.disabled(vm.info == nil)
                if let u = vm.shareURL {
                    ShareLink(item: u) { Image(systemName: "square.and.arrow.up") }
                } else {
                    Image(systemName: "square.and.arrow.up").foregroundStyle(.gray)
                }
            }
        }
        .sheet(isPresented: $showInfo) {
            if let info = vm.info {
                NavigationStack {
                    ScrollView {
                        Text(Format.prettyJson(info.json)).font(.caption.monospaced()).padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .navigationTitle("Development")
                    .navigationBarTitleDisplayMode(.inline)
                }
            }
        }
        .toast($vm.message, tint: Color(red: 0.12, green: 0.19, blue: 0.12))
        .task(id: name) { vm.open(name) }
    }

    @ViewBuilder
    private func controls(prefs: Prefs) -> some View {
        let plate = vm.plate
        ProcessRow(
            plate: plate != nil, filters: prefs.plateFilters, space: prefs.plateSpace, hasMatrix: vm.info?.hasColourMatrix ?? false,
            onProcess: vm.setProcess, onFilters: vm.setPlateFilters, onSpace: vm.setPlateSpace
        )
        if plate == nil {
            FilterRow(selected: vm.filter, onSelect: vm.setFilter)
            let w = vm.weights
            LabeledSlider(label: String(format: "R %.2f", w.r), value: Binding(get: { w.r }, set: { vm.setWeightsRB(r: $0, b: w.b) }))
            LabeledSlider(label: String(format: "B %.2f", w.b), value: Binding(get: { w.b }, set: { vm.setWeightsRB(r: w.r, b: $0) }))
            HStack(spacing: 12) {
                let e = vm.effective
                Text(String(format: "G %.2f", w.g) + (vm.filter != .none ? String(format: "  →  %.2f/%.2f/%.2f", e.r, e.g, e.b) : ""))
                    .font(.caption.monospaced())
                Button("Reset") { vm.resetWeights() }
                Spacer()
            }
        }
        PaperSliders(soft: vm.soft, hard: vm.hard, onSoft: vm.setSoft, onHard: vm.setHard)
        SettingRow(label: "Peak", on: Binding(get: { vm.peakOn }, set: { vm.darkroom.prefs.setPeak($0) }))
        if vm.peakOn {
            // Peak's pair, started from the measurement on this negative; the
            // 1:1 view shows the result, the preview is never sharpened.
            let lim = Prefs.peakLimits
            LabeledSlider(label: String(format: "peak σ %.2f", vm.peakRadius), value: Binding(get: { vm.peakRadius }, set: { vm.setPeak(radius: $0, amount: vm.peakAmount) }), range: lim.radiusMin...lim.radiusMax)
            LabeledSlider(label: String(format: "peak ×%.2f", vm.peakAmount), value: Binding(get: { vm.peakAmount }, set: { vm.setPeak(radius: vm.peakRadius, amount: $0) }), range: 0...lim.amountMax)
            HStack(spacing: 12) {
                let m = vm.peakMeasured
                Text(
                    m == nil ? "not measured"
                        : !(m!.found) ? "no clean edge measured: left as it is"
                        : String(format: "measured σ %.2f ×%.2f", m!.radius, m!.amount)
                )
                .font(.caption).foregroundStyle(.gray)
                Button("Measured") { vm.resetPeak() }
                    .disabled(m == nil || (m!.radius == vm.peakRadius && m!.amount == vm.peakAmount))
                Spacer()
            }
        }
        if let cur = vm.shotSettings, cur.binning == 2 {
            HStack {
                Button("Full size") { var s = cur; s.binning = 1; vm.develop(s) }
                Spacer()
            }
        }
        HStack(spacing: 8) {
            Menu {
                let peak = vm.peakOn && vm.peakAmount > 0 ? ", peak" : ""
                let rgb = plate.map { " \(Format.plateSpace($0.space))" } ?? ""
                Button("JPEG\(rgb)\(peak)") { vm.exportJpeg() }
                Button("TIFF 16-bit\(rgb)\(peak)") { vm.exportTiff() }
                Button(plate != nil ? "Plate (linear TIFF, three frames + mask)" : "Negative (linear TIFF + mask)") { vm.exportNegative() }
            } label: {
                Text(vm.exporting.map { "Saving \($0)…" } ?? "Export")
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Color(white: 0.2), in: Capsule())
            }
            .disabled(vm.exporting != nil)
            Spacer()
        }
        .padding(.top, 4)
    }
}

private struct PanelHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct DevelopPrompt: View {
    let status: ShotStatus?
    let onDevelop: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            switch status {
            case .saving: ProgressView().tint(.white); Text("Saving frame…")
            case .queued: ProgressView().tint(.white); Text("Queued")
            case .developing: ProgressView().tint(.white); Text("Developing…")
            case let .failed(m):
                Text(m).foregroundStyle(Color(red: 1, green: 0.5, blue: 0.5)).multilineTextAlignment(.center)
                Button("Develop", action: onDevelop).buttonStyle(.borderedProminent)
            case .cached:
                Text("Frame in cache, not developed in this session.").foregroundStyle(.gray)
                Button("Develop", action: onDevelop).buttonStyle(.borderedProminent)
            default:
                Text("The negative is no longer in memory.").foregroundStyle(.gray)
                Button("Develop again", action: onDevelop).buttonStyle(.borderedProminent)
            }
        }
        .padding()
    }
}

/// Pinch/drag viewer. The preview (<= 2048 px) is the base layer; beyond
/// its resolution the visible window is requested at 1:1 and drawn on top.
struct ZoomableNegative: View {
    let preview: CGImage
    let fullWidth: Int
    let fullHeight: Int
    let detail: Detail?
    let onVisibleRegion: (Int, Int, Int, Int) -> Void
    let onZoomedOut: () -> Void

    @State private var scale: CGFloat = 1
    @State private var offset: CGPoint = .zero
    @State private var gestureScale: CGFloat = 1
    @State private var gestureOffset: CGPoint = .zero

    var body: some View {
        GeometryReader { geo in
            let view = geo.size
            let fit = min(view.width / CGFloat(fullWidth), view.height / CGFloat(fullHeight))
            let fitW = CGFloat(fullWidth) * fit, fitH = CGFloat(fullHeight) * fit
            let base = CGPoint(x: (view.width - fitW) / 2, y: (view.height - fitH) / 2)
            let maxScale = max(1, 2 / fit) // up to 200 % of the sensor pixels
            let s = scale
            let o = offset
            Canvas { ctx, _ in
                ctx.translateBy(x: o.x, y: o.y)
                ctx.scaleBy(x: s, y: s)
                ctx.draw(Image(decorative: preview, scale: 1), in: CGRect(x: base.x, y: base.y, width: fitW, height: fitH))
                if let d = detail {
                    let dx = base.x + CGFloat(d.x) * fit, dy = base.y + CGFloat(d.y) * fit
                    ctx.draw(Image(decorative: d.image, scale: 1), in: CGRect(x: dx, y: dy, width: CGFloat(d.image.width) * fit, height: CGFloat(d.image.height) * fit))
                }
            }
            .clipped()
            .contentShape(Rectangle())
            .gesture(
                MagnifyGesture()
                    .onChanged { g in
                        let new = min(max(gestureScale * g.magnification, 1), maxScale)
                        let k = new / scale
                        let c = g.startLocation
                        offset = clamp(CGPoint(x: c.x - k * (c.x - offset.x), y: c.y - k * (c.y - offset.y)), new, view: view, fitW: fitW, fitH: fitH, base: base)
                        scale = new
                    }
                    .onEnded { _ in gestureScale = scale }
                    .simultaneously(with:
                        DragGesture()
                            .onChanged { g in
                                offset = clamp(CGPoint(x: gestureOffset.x + g.translation.width, y: gestureOffset.y + g.translation.height), scale, view: view, fitW: fitW, fitH: fitH, base: base)
                            }
                            .onEnded { _ in gestureOffset = offset }
                    )
            )
            .onTapGesture(count: 2) { p in
                if scale > 1.01 {
                    scale = 1; offset = .zero
                } else {
                    let s2 = min(maxScale, max(2, 1 / fit)) // 1:1 on screen, or 2x
                    scale = s2
                    offset = clamp(CGPoint(x: p.x - s2 * p.x, y: p.y - s2 * p.y), s2, view: view, fitW: fitW, fitH: fitH, base: base)
                }
                gestureScale = scale
                gestureOffset = offset
            }
            .onChange(of: ZoomKey(scale: scale, offset: offset, view: view)) { _, _ in
                report(view: view, fit: fit, base: base)
            }
            .onAppear { gestureScale = 1; gestureOffset = .zero }
        }
    }

    private struct ZoomKey: Equatable { var scale: CGFloat; var offset: CGPoint; var view: CGSize }

    // screen = offset + s * content; the picture occupies content [base, base + fit size].
    private func clamp(_ o: CGPoint, _ s: CGFloat, view: CGSize, fitW: CGFloat, fitH: CGFloat, base: CGPoint) -> CGPoint {
        let w = fitW * s, h = fitH * s
        let x = w <= view.width ? (view.width - w) / 2 - base.x * s : min(max(o.x, view.width - w - base.x * s), -base.x * s)
        let y = h <= view.height ? (view.height - h) / 2 - base.y * s : min(max(o.y, view.height - h - base.y * s), -base.y * s)
        return CGPoint(x: x, y: y)
    }

    /// Screen px per full-res pixel; above the preview's own density the 1:1 window is asked for.
    private func report(view: CGSize, fit: CGFloat, base: CGPoint) {
        guard view.width > 0 else { return }
        let pxPerFull = fit * scale
        let previewPxPerFull = CGFloat(preview.width) / CGFloat(fullWidth)
        if pxPerFull <= previewPxPerFull * 1.05 {
            onZoomedOut()
            return
        }
        let originX = base.x * scale + offset.x
        let originY = base.y * scale + offset.y
        let x0 = min(max(Int((0 - originX) / pxPerFull), 0), fullWidth - 1)
        let y0 = min(max(Int((0 - originY) / pxPerFull), 0), fullHeight - 1)
        let x1 = min(max(Int(((view.width - originX) / pxPerFull).rounded()), x0 + 1), fullWidth)
        let y1 = min(max(Int(((view.height - originY) / pxPerFull).rounded()), y0 + 1), fullHeight)
        onVisibleRegion(x0, y0, x1 - x0, y1 - y0)
    }
}
