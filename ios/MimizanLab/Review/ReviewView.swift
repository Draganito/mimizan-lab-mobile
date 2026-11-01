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
                VStack(spacing: 0) {
                    panelHeader
                    ScrollView {
                        controls(prefs: prefs)
                            .padding(.horizontal, 12)
                            .padding(.top, 4)
                            .padding(.bottom, 10)
                            .background {
                                GeometryReader { g in
                                    Color.clear.preference(key: PanelHeightKey.self, value: g.size.height)
                                }
                            }
                    }
                    .scrollIndicators(.hidden)
                    .frame(height: panelHeight > 0 ? min(panelHeight, 300) : 240)
                }
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

    /// Mono and Plate on the left, Export fixed on the right.
    private var panelHeader: some View {
        HStack(alignment: .center, spacing: 12) {
            ProcessChoice(plate: vm.plate != nil, onProcess: vm.setProcess)
            Spacer(minLength: 8)
            exportButton
        }
        .padding(.horizontal, 12)
        .padding(.top, 12)
        .padding(.bottom, 4)
    }

    private var exportButton: some View {
        let plate = vm.plate
        return Menu {
            let peak = vm.peakApplied.amount > 0 ? ", peak" : ""
            let rgb = plate.map { " \(Format.plateSpace($0.space))" } ?? ""
            Button("JPEG\(rgb)\(peak)") { vm.exportJpeg() }
            Button("TIFF 16-bit\(rgb)\(peak)") { vm.exportTiff() }
            Button(plate != nil ? "Plate (linear TIFF, three frames + mask)" : "Negative (linear TIFF + mask)") { vm.exportNegative() }
        } label: {
            Text(vm.exporting.map { "Saving \($0)…" } ?? "Export")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.black)
                .padding(.horizontal, 18)
                .padding(.vertical, 9)
                .background(Color.white, in: Capsule())
        }
        .disabled(vm.exporting != nil)
        .opacity(vm.exporting == nil ? 1 : 0.55)
    }

    @ViewBuilder
    private func controls(prefs: Prefs) -> some View {
        let plate = vm.plate
        VStack(alignment: .leading, spacing: 12) {
            if plate == nil {
                FilterRow(selected: vm.filter, onSelect: vm.setFilter)
            } else {
                PlateFilterRow(selected: prefs.plateFilters, hasMatrix: vm.info?.hasColourMatrix ?? false, onSelect: vm.setPlateFilters)
            }
            PaperSliders(soft: vm.soft, hard: vm.hard, onSoft: vm.setSoft, onHard: vm.setHard)
            ZoneStrip(soft: vm.soft, hard: vm.hard, onBoth: vm.setGrade)
            PeakRow(mode: prefs.peakMode, onMode: vm.setPeakMode)
            if prefs.peakMode == .custom {
                let lim = Prefs.peakLimits
                LabeledSlider(label: String(format: "peak σ %.2f", prefs.peakRadius), value: Binding(get: { prefs.peakRadius }, set: { vm.setPeak(radius: $0, amount: prefs.peakAmount) }), range: lim.radiusMin...lim.radiusMax)
                LabeledSlider(label: String(format: "peak ×%.2f", prefs.peakAmount), value: Binding(get: { prefs.peakAmount }, set: { vm.setPeak(radius: prefs.peakRadius, amount: $0) }), range: 0...lim.amountMax)
            }
            if let cur = vm.shotSettings, cur.binning == 2 {
                Button("Full size") { var s = cur; s.binning = 1; vm.develop(s) }
            }
        }
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
    /// Scale and offset at the moment the pinch began. The live values must
    /// not be the baseline, or a later drag starts from where the pinch began.
    @State private var gestureScale: CGFloat = 1
    @State private var magnifyOrigin: CGPoint = .zero
    @State private var pinching = false
    /// Offset when this drag began, and how much translation the pinch already used.
    @State private var dragOrigin: CGPoint?
    @State private var dragBias: CGSize = .zero

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
                        if !pinching {
                            pinching = true
                            gestureScale = scale
                            magnifyOrigin = offset
                            dragOrigin = nil
                        }
                        let new = min(max(gestureScale * g.magnification, 1), maxScale)
                        let k = gestureScale > 0 ? new / gestureScale : 1
                        let c = g.startLocation
                        let o0 = magnifyOrigin
                        offset = clamp(CGPoint(x: c.x - k * (c.x - o0.x), y: c.y - k * (c.y - o0.y)), new, view: view, fitW: fitW, fitH: fitH, base: base)
                        scale = new
                    }
                    .onEnded { _ in
                        pinching = false
                        gestureScale = scale
                    }
                    .simultaneously(with:
                        DragGesture()
                            .onChanged { g in
                                // A pinch also moves the fingers. That translation belongs
                                // to the pinch; the next one-finger drag starts from here.
                                if pinching {
                                    dragBias = g.translation
                                    dragOrigin = nil
                                    return
                                }
                                if dragOrigin == nil { dragOrigin = offset }
                                let o = dragOrigin ?? offset
                                offset = clamp(
                                    CGPoint(x: o.x + g.translation.width - dragBias.width, y: o.y + g.translation.height - dragBias.height),
                                    scale, view: view, fitW: fitW, fitH: fitH, base: base
                                )
                            }
                            .onEnded { _ in
                                dragOrigin = nil
                                dragBias = .zero
                            }
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
                pinching = false
                dragOrigin = nil
                dragBias = .zero
            }
            .onChange(of: ZoomKey(scale: scale, offset: offset, view: view)) { _, _ in
                report(view: view, fit: fit, base: base)
            }
            .onAppear { gestureScale = 1; pinching = false; dragOrigin = nil; dragBias = .zero }
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
