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
    private(set) var soft = 0.0
    private(set) var hard = 0.0
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
    /// This negative's zone shift at the base exposures, in stops. It does
    /// not depend on the grade (both exposures move together), only on the
    /// mix, so it is measured once per mix, in the gate, and nil until then.
    private var zoneShift: Double?
    /// The stops the placement actually added to both sliders (the shift
    /// and the compensation after the snap and the clamp), so exactly those
    /// come back off when the user moves a slider.
    private var placedShift: Double = 0

    var shot: Shot? { darkroom.shots.first { $0.name == name } }
    var shotSettings: DevelopSettings? { shot?.settings }
    /// Radius and amount the 1:1 view and the exports apply.
    var peakApplied: (radius: Double, amount: Double) {
        Prefs.peakApplied(mode: darkroom.prefs.peakMode, radius: darkroom.prefs.peakRadius, amount: darkroom.prefs.peakAmount, measured: peakMeasured)
    }

    /// Bind to a shot; follows `Darkroom.current` so a (re)development shows up.
    func open(_ name: String) {
        if self.name == name, observing {
            showStandingPaper()
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

    /// Peak lives in the settings; a zoomed-in view follows the mode and the custom pair.
    private func observePeak() {
        withObservationTracking {
            _ = darkroom.prefs.peakMode
            _ = darkroom.prefs.peakRadius
            _ = darkroom.prefs.peakAmount
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
            // The shift is measured by the first render; until then the sliders show the stored grade.
            zoneShift = nil
            showStandingPaper()
            render()
        } else {
            developed = nil
            zoneShift = nil
            placedShift = 0
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

    /// Custom, started from this negative's measurement. The 1:1 view follows; the preview is never sharpened.
    func setPeakMode(_ mode: PeakMode) {
        if mode == .custom, darkroom.prefs.peakMode != .custom, let m = peakMeasured {
            darkroom.prefs.setPeak(radius: m.radius, amount: m.amount)
        }
        darkroom.prefs.setPeakMode(mode)
        if let r = detailRegion { requestDetail(x: r.0, y: r.1, w: r.2, h: r.3) }
    }

    /// Peak's radius and amount by hand. Remembered for the next picture and the automatic JPEG.
    func setPeak(radius: Double, amount: Double) {
        darkroom.prefs.setPeak(radius: radius, amount: amount)
        if let r = detailRegion { requestDetail(x: r.0, y: r.1, w: r.2, h: r.3) }
    }

    /// The stored grade, then the balance when it is on: this negative's
    /// shift (measured at zero, where both sliders have the full ±3 of
    /// room, so the clamp does not eat it) and the compensation, as one
    /// delta through `shiftGrade`. Nothing is written back. Before the
    /// shift is measured the sliders show the stored grade. The same
    /// arithmetic as the Android `GradeMath.place`.
    private func showStandingPaper() {
        let stored = (soft: darkroom.prefs.soft, hard: darkroom.prefs.hard)
        if darkroom.prefs.zoneAuto, let shift = zoneShift {
            let pair = Prefs.shiftGrade(soft: stored.soft, hard: stored.hard, by: shift + darkroom.prefs.zoneBias)
            soft = pair.0
            hard = pair.1
            placedShift = pair.0 - stored.soft
        } else {
            soft = stored.soft
            hard = stored.hard
            placedShift = 0
        }
    }

    func setSoft(_ stops: Double) {
        setGrade(soft: stops, hard: hard)
    }

    func setHard(_ stops: Double) {
        setGrade(soft: soft, hard: stops)
    }

    /// Both exposures as they stand on this print. With the balance on,
    /// the sliders include this negative's shift and the compensation;
    /// exactly the stops the placement added (`placedShift`) come back off
    /// before the grade is stored (each exposure snapped and clamped on
    /// its own, `Prefs.setSoft`), so the next picture adds its own shift
    /// once. Then the pair is placed again from what was stored. The same
    /// arithmetic as the Android `GradeMath.unplace`.
    func setGrade(soft: Double, hard: Double) {
        if darkroom.prefs.zoneAuto, developed != nil {
            darkroom.prefs.setSoft(soft - placedShift)
            darkroom.prefs.setHard(hard - placedShift)
            showStandingPaper()
        } else {
            darkroom.prefs.setSoft(soft)
            darkroom.prefs.setHard(hard)
            self.soft = darkroom.prefs.soft
            self.hard = darkroom.prefs.hard
        }
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
        /// The zone shift measured in this pass, when one was.
        var shift: Double?
    }

    /// Draw the picture as it stands. `remix` applies the sliders and the
    /// standing process first (the mix, or the plate's filters and
    /// primaries). A plate is always shown with the lamps at zero. With
    /// the balance on and the shift not yet measured (a fresh negative, or
    /// a new mix), the shift is measured here, after the remix, and the
    /// picture is drawn at the placed exposures.
    private func renderNow(remix: Bool = false) async {
        guard let d = developed else { return }
        busy = true
        defer { busy = false }
        // The mix moves the middle of the picture: measure the shift again
        // after a remix. The old value stays in place until the new one is
        // in, so a slider moved meanwhile is still un-placed with a shift.
        let needShift = darkroom.prefs.zoneAuto && (remix || zoneShift == nil)
        let stored = (soft: darkroom.prefs.soft, hard: darkroom.prefs.hard, bias: darkroom.prefs.zoneBias)
        let s = (soft: soft, hard: hard, weights: weights, filter: filter, plate: plate)
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
                var shift: Double?
                var paper = (soft: s.soft, hard: s.hard)
                if needShift {
                    // Measured at zero, as `showStandingPaper` expects; a failure counts as no shift.
                    let z = (try? d.zoneShift(soft: 0, hard: 0)) ?? 0
                    shift = z
                    let placed = Prefs.shiftGrade(soft: stored.soft, hard: stored.hard, by: z + stored.bias)
                    paper = (soft: placed.0, hard: placed.1)
                }
                let info = d.info()
                let hist = Images.normalised(try d.histogram(soft: paper.soft, hard: paper.hard))
                let image: CGImage?
                let rgb: [Float]
                if info.plate != nil {
                    image = Images.cgImage(try d.previewRgb(soft: paper.soft, hard: paper.hard, extraQuarterTurns: 0))
                    rgb = Images.normalised(try d.histogramRgb(soft: paper.soft, hard: paper.hard))
                } else {
                    image = Images.cgImage(try d.preview(soft: paper.soft, hard: paper.hard, extraQuarterTurns: 0))
                    rgb = []
                }
                return .success(Rendered(info: info, effective: effective, preview: image, histogram: hist, histogramRgb: rgb, shift: shift))
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
            if let z = r.shift {
                // The picture was drawn at this placement; the sliders follow it.
                zoneShift = z
                showStandingPaper()
            }
            preview = r.preview
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
            let soft = self.soft, hard = self.hard, radius = pair.radius, amount = pair.amount, isPlate = self.plate != nil
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

    func exportJpeg() {
        let pair = peakApplied
        let (soft, hard, radius, amount) = (self.soft, self.hard, pair.radius, pair.amount)
        export("JPEG") { d, name, suffix, exporter in
            try [Darkroom.exportJpeg(d, name: name, suffix: suffix, soft: soft, hard: hard, peakRadius: radius, peakAmount: amount, exporter: exporter)]
        }
    }

    func exportTiff() {
        let pair = peakApplied
        let (soft, hard, radius, amount) = (self.soft, self.hard, pair.radius, pair.amount)
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
        exporting = label
        Task {
            let result: Result<[URL], Error> = await gate.run { Result<[URL], Error> { try f(d, name, suffix, exporter) } }
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