// One developed negative under the sliders: the mix, the filter, the
// paper's two exposures, the plate's lamps, Peak's pair, the 1:1 window
// and the exports. Mirrors `ReviewViewModel.kt`.
import CoreGraphics
import Foundation
import MimizanCore
import Observation
import os

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "review")

/// A 1:1 crop of the negative and where it sits in the upright picture.
struct Detail {
    let x: Int
    let y: Int
    let image: CGImage
}

@MainActor
@Observable
final class ReviewViewModel {
    let darkroom = Darkroom.shared

    private(set) var name = ""
    private(set) var info: DevelopInfo?
    /// Balanced-space weights the sliders show (before the filter).
    private(set) var weights = Weights.native
    /// What the mix actually uses: `weights` with the filter applied.
    private(set) var effective = Weights.native
    private(set) var filter = Filter.none
    private(set) var soft = 0.0
    private(set) var hard = 0.0
    /// The plate process as the development stands; nil is the monochrome negative.
    private(set) var plate: PlateState?
    private(set) var lampRed = 0.0
    private(set) var lampBlue = 0.0
    private(set) var peakRadius = Prefs.peakLimits.radiusDefault
    private(set) var peakAmount = 0.0
    private(set) var peakMeasured: PeakMeasured?
    /// Grey, or the plate in colour.
    private(set) var preview: CGImage?
    private(set) var histogram: [Float] = []
    private(set) var histogramRgb: [Float] = []
    private(set) var detail: Detail?
    private(set) var busy = false
    private(set) var exporting: String?
    private(set) var lastExport: URL?
    var message: String?
    /// The negative is not in memory: offer to develop it (again).
    private(set) var needsDevelop = false

    private var developed: Developed?
    private var renderTask: Task<Void, Never>?
    private var detailTask: Task<Void, Never>?
    /// Last 1:1 window asked for, so a slider change can refresh it.
    private var detailRegion: (Int, Int, Int, Int)?
    private var observing = false
    /// The core's `Developed` is not re-entrant with itself: one call at a time.
    private let gate = Gate()

    var shot: Shot? { darkroom.shots.first { $0.name == name } }
    var shotSettings: DevelopSettings? { shot?.settings }
    var peakOn: Bool { darkroom.prefs.peak }

    /// Bind to a shot; follows `Darkroom.current` so a (re)development shows up.
    func open(_ name: String) {
        if self.name == name, observing {
            soft = darkroom.prefs.soft
            hard = darkroom.prefs.hard
            render()
            return
        }
        self.name = name
        detailRegion = nil
        detail = nil
        preview = nil
        histogram = []
        histogramRgb = []
        info = nil
        plate = nil
        soft = darkroom.prefs.soft
        hard = darkroom.prefs.hard
        lampRed = 0
        lampBlue = 0
        if !observing {
            observing = true
            observeCurrent()
            observePeak()
        }
        bind(darkroom.current)
    }

    private func observeCurrent() {
        withObservationTracking {
            _ = darkroom.current
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.bind(self.darkroom.current)
                self.observeCurrent()
            }
        }
    }

    /// The Peak checkbox lives in the settings; a zoomed-in view follows it.
    private func observePeak() {
        withObservationTracking {
            _ = darkroom.prefs.peak
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let r = self.detailRegion { self.requestDetail(x: r.0, y: r.1, w: r.2, h: r.3) }
                self.observePeak()
            }
        }
    }

    private func bind(_ neg: Negative?) {
        if let neg, neg.name == name {
            if developed === neg.developed { return }
            developed = neg.developed
            // info.weights already include the filter of the first mix; the
            // sliders start from the camera's base weights. The plate (and
            // its lamps) is the process the frame was developed in.
            info = neg.info
            weights = neg.info.defaultWeights
            effective = neg.info.weights
            filter = neg.info.filter
            plate = neg.info.plate
            lampRed = neg.info.plate?.lampRed ?? lampRed
            lampBlue = neg.info.plate?.lampBlue ?? lampBlue
            peakMeasured = neg.peak
            peakRadius = neg.peak?.radius ?? Prefs.peakLimits.radiusDefault
            peakAmount = neg.peak?.amount ?? 0
            needsDevelop = false
            detail = nil
            render()
        } else {
            developed = nil
            needsDevelop = true
            preview = nil
            detail = nil
        }
    }

    /// Develop (again) from the cache, in the process the darkroom stands in now.
    func develop(_ settings: DevelopSettings? = nil) {
        guard let shot else { return }
        darkroom.redevelop(shot.name, settings: darkroom.withStandingProcess(settings ?? shot.settings))
    }

    // MARK: controls

    func setFilter(_ f: Filter) {
        filter = f
        remixAndRender()
    }

    /// Switch the process: the plate with the standing filters and
    /// primaries, or back to the monochrome mix. A re-mix of the same
    /// separation; the roll remembers the choice for the next shot.
    func setProcess(_ p: Process) {
        darkroom.prefs.setProcess(p)
        if (p == .plate) == (plate != nil) { return }
        remixAndRender()
    }

    func setPlateFilters(_ f: PlateFilters) {
        darkroom.prefs.setPlateFilters(f)
        if plate != nil { remixAndRender() }
    }

    func setPlateSpace(_ s: PlateSpace) {
        darkroom.prefs.setPlateSpace(s)
        if plate != nil { remixAndRender() }
    }

    /// The two lamps, in twelfths of a stop; green is the reference.
    func setLamps(red: Double, blue: Double) {
        lampRed = Prefs.snapLamp(red)
        lampBlue = Prefs.snapLamp(blue)
        schedule { await self.renderNow(lamps: true) }
    }

    func resetLamps() { setLamps(red: 0, blue: 0) }

    /// Peak's radius and amount by hand; only the 1:1 view has to follow (the preview is never sharpened).
    func setPeak(radius: Double, amount: Double) {
        peakRadius = Prefs.clampPeakRadius(radius)
        peakAmount = Prefs.clampPeakAmount(amount)
        if let r = detailRegion { requestDetail(x: r.0, y: r.1, w: r.2, h: r.3) }
    }

    /// Back to the pair the algorithm measured.
    func resetPeak() {
        guard let m = peakMeasured else { return }
        setPeak(radius: m.radius, amount: m.amount)
    }

    /// The amount the exports and the 1:1 view apply: zero while the Peak checkbox is off.
    private var peakAmountApplied: Double { peakOn ? peakAmount : 0 }

    /// Sliders give R and B; G takes the rest so the sum stays 1.
    func setWeightsRB(r: Double, b: Double) {
        let rr = min(max(r, 0), 1)
        let bb = min(max(b, 0), 1 - rr)
        weights = Weights(r: rr, g: 1 - rr - bb, b: bb)
        remixAndRender()
    }

    func resetWeights() {
        guard let d = info?.defaultWeights else { return }
        weights = d
        filter = .none
        remixAndRender()
    }

    func setSoft(_ stops: Double) {
        soft = Prefs.snapStops(stops)
        schedule { await self.renderNow() }
    }

    func setHard(_ stops: Double) {
        hard = Prefs.snapStops(stops)
        schedule { await self.renderNow() }
    }

    private func remixAndRender() {
        schedule { await self.renderNow(remix: true) }
    }

    /// Coalesce slider drags: 60 ms, then one render.
    private func schedule(_ work: @escaping @MainActor () async -> Void) {
        renderTask?.cancel()
        renderTask = Task {
            try? await Task.sleep(for: .milliseconds(60))
            if Task.isCancelled { return }
            await work()
        }
    }

    private func render() {
        renderTask?.cancel()
        renderTask = Task { await renderNow() }
    }

    private struct Rendered {
        var info: DevelopInfo
        var effective: Weights?
        var preview: CGImage?
        var histogram: [Float]
        var histogramRgb: [Float]
    }

    /// Draw the picture as it stands. `remix` applies the sliders and the
    /// standing process first (the mix, or the plate's filters and
    /// primaries); `lamps` pushes the two lamps to the development.
    private func renderNow(remix: Bool = false, lamps: Bool = false) async {
        guard let d = developed else { return }
        busy = true
        let s = (soft: soft, hard: hard, lampRed: lampRed, lampBlue: lampBlue, weights: weights, filter: filter, plate: plate)
        let standing = (process: darkroom.prefs.process, filters: darkroom.prefs.plateFilters, space: darkroom.prefs.plateSpace)
        let gate = self.gate
        let result: Result<Rendered, Error> = await Task.detached(priority: .userInitiated) {
            await gate.run { () -> Result<Rendered, Error> in
                do {
                    var effective: Weights?
                    if remix {
                        if standing.process == .plate {
                            _ = try d.setPlate(filters: standing.filters, space: standing.space)
                            try d.setLamps(red: s.lampRed, blue: s.lampBlue)
                        } else {
                            effective = try d.remix(weights: normaliseWeights(w: s.weights), space: .balanced, filter: s.filter)
                        }
                    }
                    if lamps, s.plate != nil {
                        try d.setLamps(red: s.lampRed, blue: s.lampBlue)
                    }
                    let info = d.info()
                    let hist = Images.normalised(try d.histogram(soft: s.soft, hard: s.hard))
                    let image: CGImage?
                    let rgb: [Float]
                    if info.plate != nil {
                        image = Images.cgImage(try d.previewRgb(soft: s.soft, hard: s.hard, extraQuarterTurns: 0))
                        rgb = Images.normalised(try d.histogramRgb(soft: s.soft, hard: s.hard))
                    } else {
                        image = Images.cgImage(try d.preview(soft: s.soft, hard: s.hard, extraQuarterTurns: 0))
                        rgb = []
                    }
                    return .success(Rendered(info: info, effective: effective, preview: image, histogram: hist, histogramRgb: rgb))
                } catch {
                    return .failure(error)
                }
            }
        }.value
        guard developed === d else { return }
        switch result {
        case let .success(r):
            info = r.info
            plate = r.info.plate
            if let e = r.effective { effective = e }
            preview = r.preview
            histogram = r.histogram
            histogramRgb = r.histogramRgb
            detail = nil
            busy = false
            // A zoomed-in view has to follow the new tones.
            if let reg = detailRegion { requestDetail(x: reg.0, y: reg.1, w: reg.2, h: reg.3) }
        case let .failure(error):
            log.error("render: \(error.localizedDescription)")
            busy = false
            message = error.localizedDescription
        }
    }

    /// Ask for a 1:1 window (upright full-resolution coordinates).
    /// Debounced; the view calls this whenever the visible region changes at high zoom.
    func requestDetail(x: Int, y: Int, w: Int, h: Int) {
        detailRegion = (x, y, w, h)
        detailTask?.cancel()
        detailTask = Task {
            try? await Task.sleep(for: .milliseconds(120))
            if Task.isCancelled { return }
            guard let d = developed else { return }
            let soft = self.soft, hard = self.hard, radius = self.peakRadius, amount = self.peakAmountApplied, isPlate = self.plate != nil
            let gate = self.gate
            let image: CGImage? = await Task.detached(priority: .userInitiated) {
                await gate.run { () -> CGImage? in
                    do {
                        if isPlate {
                            return Images.cgImage(try d.detailRgb(x: UInt32(x), y: UInt32(y), width: UInt32(w), height: UInt32(h), soft: soft, hard: hard, extraQuarterTurns: 0, peakRadius: radius, peakAmount: amount))
                        }
                        return Images.cgImage(try d.detail(x: UInt32(x), y: UInt32(y), width: UInt32(w), height: UInt32(h), soft: soft, hard: hard, extraQuarterTurns: 0, peakRadius: radius, peakAmount: amount))
                    } catch {
                        log.warning("detail: \(error.localizedDescription)")
                        return nil
                    }
                }
            }.value
            if Task.isCancelled || developed !== d { return }
            if let image { detail = Detail(x: x, y: y, image: image) }
        }
    }

    func clearDetail() {
        detailRegion = nil
        detailTask?.cancel()
        detail = nil
    }

    // MARK: exports

    func exportJpeg() {
        let (soft, hard, radius, amount) = (self.soft, self.hard, self.peakRadius, self.peakAmountApplied)
        export("JPEG") { d, name, suffix, exporter in
            try [Darkroom.exportJpeg(d, name: name, suffix: suffix, soft: soft, hard: hard, peakRadius: radius, peakAmount: amount, exporter: exporter)]
        }
    }

    func exportTiff() {
        let (soft, hard, radius, amount) = (self.soft, self.hard, self.peakRadius, self.peakAmountApplied)
        export("TIFF 16-bit") { d, name, suffix, exporter in
            try [Darkroom.exportTiff(d, name: name, suffix: suffix, soft: soft, hard: hard, peakRadius: radius, peakAmount: amount, exporter: exporter)]
        }
    }

    func exportNegative() {
        export(plate != nil ? "plate" : "negative") { d, name, _, exporter in
            try Darkroom.exportNegative(d, name: name, exporter: exporter)
        }
    }

    private func export(_ label: String, _ f: @escaping @Sendable (Developed, String, String, Exporter) throws -> [URL]) {
        guard let d = developed else { return }
        let suffix = plate.map { Darkroom.suffix(for: $0) } ?? Darkroom.suffix(for: filter)
        let name = self.name
        let exporter = darkroom.exporter
        let gate = self.gate
        exporting = label
        Task {
            let result: Result<[URL], Error> = await Task.detached(priority: .userInitiated) {
                await gate.run { Result { try f(d, name, suffix, exporter) } }
            }.value
            switch result {
            case let .success(urls):
                lastExport = urls.first
                exporting = nil
                message = "Saved \(label) to Files › Mimizan Lab"
                if label == "JPEG", let u = urls.first {
                    Task { _ = await exporter.addToPhotos(u) }
                }
            case let .failure(error):
                log.error("export: \(error.localizedDescription)")
                exporting = nil
                message = error.localizedDescription
            }
        }
    }

    /// What the share sheet offers: the last export, else the automatic JPEG.
    var shareURL: URL? { lastExport ?? shot?.jpegURL }
}

/// A serial gate for the core's calls on one `Developed`.
actor Gate {
    func run<T>(_ body: () -> T) -> T { body() }
}
