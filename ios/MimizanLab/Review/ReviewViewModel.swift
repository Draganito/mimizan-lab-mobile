// One developed negative under the sliders: the mix, the filter, the
// paper's two exposures, Peak's pair, the 1:1 window and the exports.
// The plate's lamps stay at zero. Mirrors `ReviewViewModel.kt`.
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
    /// This frame's print, grade and Peak: opened from the roll, written back to it on every move.
    private(set) var print = Print(grade: .base, peak: .standing(.auto))
    /// The plate process as the development stands; nil is the monochrome negative.
    private(set) var plate: PlateState?
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
    /// The core's `Developed` is not re-entrant with itself: one call at a
    /// time, on the gate's own thread, never on the main one (an export
    /// holds the core's lock for seconds).
    private let gate = Gate(label: "ch.bojovic.mimizanlab.review")

    var shot: Shot? { darkroom.shots.first { $0.name == name } }
    var shotSettings: DevelopSettings? { shot?.settings }
    /// Radius and amount the 1:1 view and the exports apply.
    var peakApplied: (radius: Double, amount: Double) { print.peak.applied(peakMeasured) }

    /// Bind to a shot; follows `Darkroom.current` so a (re)development shows up.
    func open(_ name: String) {
        if self.name == name, observing {
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
        // The frame's own print; a frame the roll does not know opens at the standing one.
        print = shot?.print ?? darkroom.prefs.standingPrint
        if !observing {
            observing = true
            observeCurrent()
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

    private func bind(_ neg: Negative?) {
        if let neg, neg.name == name {
            if developed === neg.developed { return }
            developed = neg.developed
            // info.weights already include the filter of the first mix; the
            // sliders start from the camera's base weights. The plate is the
            // process the frame was developed in. Lamps stay at zero.
            info = neg.info
            weights = neg.info.defaultWeights
            effective = neg.info.weights
            filter = neg.info.filter
            plate = neg.info.plate
            peakMeasured = neg.peak
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
        var s = darkroom.withStandingProcess(settings ?? shot.settings)
        s.autoJpeg = false
        darkroom.redevelop(shot.name, settings: s)
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

    /// This print's Peak mode. Custom starts from the measurement of this
    /// negative when it is entered. The 1:1 view follows; the preview is
    /// never sharpened. Kept with the frame, like the grade.
    func setPeakMode(_ mode: PeakMode) {
        var peak = print.peak
        if mode == .custom, peak.mode != .custom, let m = peakMeasured {
            peak = Prefs.customPeak(radius: m.radius, amount: m.amount)
        } else {
            peak.mode = mode
        }
        setPeak(peak)
    }

    /// Peak's radius and amount by hand, on this print.
    func setPeak(radius: Double, amount: Double) {
        setPeak(Prefs.customPeak(radius: radius, amount: amount))
    }

    private func setPeak(_ peak: Peak) {
        print.peak = peak
        darkroom.setPrint(name, print)
        if let r = detailRegion { requestDetail(x: r.0, y: r.1, w: r.2, h: r.3) }
    }

    /// This print's grade as one number; the exposure (the strip) stays.
    func setContrast(_ stops: Double) {
        let g = Prefs.withContrast(soft: print.grade.soft, hard: print.grade.hard, contrast: stops)
        setGrade(soft: g.soft, hard: g.hard)
    }

    /// Both exposures of this print. The frame on the roll takes the new
    /// grade at once; the standing grade in the settings stays where it
    /// is, so the next frame does not inherit this correction.
    func setGrade(soft: Double, hard: Double) {
        print.grade = Prefs.snapGrade(soft: soft, hard: hard)
        darkroom.setPrint(name, print)
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
        /// The same picture reduced for the roll.
        var thumbnail: CGImage?
        var histogram: [Float]
        var histogramRgb: [Float]
    }

    /// Draw the picture as it stands. `remix` applies the sliders and the
    /// standing process first (the mix, or the plate's filters and
    /// primaries). A plate is always shown with the lamps at zero. The
    /// roll's thumbnail follows the picture, so it shows the frame as the
    /// developer last left it.
    private func renderNow(remix: Bool = false) async {
        guard let d = developed else { return }
        busy = true
        defer { busy = false }
        let s = (grade: print.grade, weights: weights, filter: filter, plate: plate)
        let standing = (process: darkroom.prefs.process, filters: darkroom.prefs.plateFilters, space: darkroom.prefs.plateSpace)
        let result: Result<Rendered, Error> = await gate.run { () -> Result<Rendered, Error> in
            do {
                var effective: Weights?
                if remix {
                    if standing.process == .plate {
                        _ = try d.setPlate(filters: standing.filters, space: standing.space)
                        try d.setLamps(red: 0, blue: 0)
                    } else {
                        effective = try d.remix(weights: normaliseWeights(w: s.weights), space: .balanced, filter: s.filter)
                    }
                } else if s.plate != nil {
                    try d.setLamps(red: 0, blue: 0)
                }
                let info = d.info()
                let hist = Images.normalised(try d.histogram(soft: s.grade.soft, hard: s.grade.hard))
                let image: CGImage?
                let thumb: CGImage?
                let rgb: [Float]
                if info.plate != nil {
                    let p = try d.previewRgb(soft: s.grade.soft, hard: s.grade.hard, extraQuarterTurns: 0)
                    image = Images.cgImage(p)
                    thumb = Images.thumbnail(p)
                    rgb = Images.normalised(try d.histogramRgb(soft: s.grade.soft, hard: s.grade.hard))
                } else {
                    let p = try d.preview(soft: s.grade.soft, hard: s.grade.hard, extraQuarterTurns: 0)
                    image = Images.cgImage(p)
                    thumb = Images.thumbnail(p)
                    rgb = []
                }
                return .success(Rendered(info: info, effective: effective, preview: image, thumbnail: thumb, histogram: hist, histogramRgb: rgb))
            } catch {
                return .failure(error)
            }
        }
        guard developed === d else { return }
        switch result {
        case let .success(r):
            info = r.info
            plate = r.info.plate
            if let e = r.effective { effective = e }
            preview = r.preview
            if let t = r.thumbnail { darkroom.setThumbnail(name, t) }
            histogram = r.histogram
            histogramRgb = r.histogramRgb
            detail = nil
            // A zoomed-in view has to follow the new tones.
            if let reg = detailRegion { requestDetail(x: reg.0, y: reg.1, w: reg.2, h: reg.3) }
        case let .failure(error):
            log.error("render: \(error.localizedDescription)")
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
            let pair = self.peakApplied
            let soft = print.grade.soft, hard = print.grade.hard, radius = pair.radius, amount = pair.amount, isPlate = self.plate != nil
            let image: CGImage? = await gate.run { () -> CGImage? in
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

    /// The full-size JPEG and the two TIFFs are the one purchase; the JPEG at half the edge is free.
    var fullResolution: Bool { darkroom.entitlements.fullResolution }
    /// A locked export was asked for: the screen offers the purchase.
    var unlockRequested = false

    func exportJpeg() {
        let pair = peakApplied
        let (soft, hard, radius, amount) = (print.grade.soft, print.grade.hard, pair.radius, pair.amount)
        let binned = !fullResolution
        export(binned ? "JPEG (half size)" : "JPEG") { d, name, suffix, exporter in
            try [Darkroom.exportJpeg(d, name: name, suffix: suffix, soft: soft, hard: hard, peakRadius: radius, peakAmount: amount, binned: binned, exporter: exporter)]
        }
    }

    func exportTiff() {
        guard fullResolution else { unlockRequested = true; return }
        let pair = peakApplied
        let (soft, hard, radius, amount) = (print.grade.soft, print.grade.hard, pair.radius, pair.amount)
        export("TIFF 16-bit") { d, name, suffix, exporter in
            try [Darkroom.exportTiff(d, name: name, suffix: suffix, soft: soft, hard: hard, peakRadius: radius, peakAmount: amount, exporter: exporter)]
        }
    }

    func exportNegative() {
        guard fullResolution else { unlockRequested = true; return }
        export(plate != nil ? "plate" : "negative") { d, name, _, exporter in
            try Darkroom.exportNegative(d, name: name, exporter: exporter)
        }
    }

    private func export(_ label: String, _ f: @escaping @Sendable (Developed, String, String, Exporter) throws -> [URL]) {
        guard let d = developed else { return }
        let suffix = plate.map { Darkroom.suffix(for: $0) } ?? Darkroom.suffix(for: filter)
        let name = self.name
        let exporter = darkroom.exporter
        exporting = label
        Task {
            let result: Result<[URL], Error> = await gate.run { Result<[URL], Error> { try f(d, name, suffix, exporter) } }
            switch result {
            case let .success(urls):
                lastExport = urls.first
                exporting = nil
                message = "Saved \(label) to Files › Mimizan Lab"
                if label.hasPrefix("JPEG"), let u = urls.first {
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