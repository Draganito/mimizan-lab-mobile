// Process-wide state of the roll: what was shot, what is developed and the
// one `Developed` negative that is kept in memory (the rest can be
// developed again from the cache). The raw frame lives only in the app;
// Files and Photos receive finished pictures. Mirrors `Darkroom.kt`.
import CoreGraphics
import Foundation
import MimizanCore
import Observation
import os
import UIKit

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "darkroom")

/// A developed frame plus the identity it belongs to, and Peak's radius
/// and amount as measured on it (nil when the measurement failed).
final class Negative: @unchecked Sendable {
    let name: String
    let developed: Developed
    let info: DevelopInfo
    let settings: DevelopSettings
    let peak: PeakMeasured?

    init(name: String, developed: Developed, info: DevelopInfo, settings: DevelopSettings, peak: PeakMeasured?) {
        self.name = name
        self.developed = developed
        self.info = info
        self.settings = settings
        self.peak = peak
    }
}

/// The frame's print and the plate's two lamps: read on the main actor,
/// carried into the worker. A Custom Peak keeps its own radius and
/// amount; Auto uses the measurement made below.
struct PaperSettings: Sendable {
    var print: Print
    var lampRed: Double
    var lampBlue: Double
}

@MainActor
@Observable
final class Darkroom {
    static let shared = Darkroom()

    let cache = RawCache()
    let exporter = Exporter()
    let cameraFiles = CameraFiles()
    let prefs = Prefs()

    /// Newest first.
    private(set) var shots: [Shot] = []
    /// Size of the raw cache on disk.
    private(set) var cacheBytes: Int64 = 0
    /// The developed negative held in memory (at most one).
    private(set) var current: Negative?
    /// What the worker is doing, for the capture screen.
    private(set) var developing: String?

    private var queue: [String] = []
    private var running = false
    /// The development runs here: a thread of its own, not Swift's
    /// cooperative pool, which a call of several seconds would block.
    private let worker = Gate(label: "ch.bojovic.mimizanlab.develop")
    /// Writing and reading the frame just taken, one at a time: the
    /// cache's orphan sweep then never sees a second frame half written.
    private let intake = Gate(label: "ch.bojovic.mimizanlab.intake")

    private init() {
        initLogging()
        // Frames from an earlier run are still in the cache: list them so
        // they can be developed again.
        let cache = cache
        let standing = prefs.standingPrint
        Task {
            let (restored, bytes) = await intake.run { () -> ([Shot], Int64) in
                let restored: [Shot] = cache.names().compactMap { name in
                    cache.loadMeta(name).map {
                        Shot(
                            name: name, meta: $0, settings: DevelopSettings(),
                            print: cache.loadPrint(name, standingPeak: standing.peak) ?? standing,
                            status: .cached, thumbnail: cache.loadThumb(name)
                        )
                    }
                }
                return (restored, cache.sizeBytes())
            }
            if !restored.isEmpty { self.shots = restored + self.shots }
            self.cacheBytes = bytes
        }
    }

    // MARK: intake

    /// Called from the camera: persists the DNG, reads what it is, then
    /// queues the development. The name is ready at once, before the file
    /// is written. `finder` is the paper finder's measurement of the
    /// moment, matched against the negative once it is developed.
    @discardableResult
    func submit(dng: Data, lens: String, capturedAt: Date, settings: DevelopSettings, developNow: Bool = true, finder: FinderSample? = nil) -> String {
        let name = Darkroom.nameFor(capturedAt)
        let provisional = ShotMeta(info: FrameInfo.empty, lens: lens, capturedAt: capturedAt, imported: false)
        let print = prefs.standingPrint
        shots.insert(Shot(name: name, meta: provisional, settings: settings, print: print, status: .saving, finder: finder), at: 0)
        let cache = cache
        Task {
            let saved: Result<ShotMeta, Error> = await intake.run {
                Result<ShotMeta, Error> {
                    try cache.putRaw(name, dng: dng)
                    let info = try frameInfo(path: cache.rawFile(name).path)
                    let meta = ShotMeta(info: info, lens: lens, capturedAt: capturedAt, imported: false)
                    try cache.commit(name, meta: meta, print: print)
                    return meta
                }
            }
            switch saved {
            case let .success(meta):
                self.updateShot(name) { $0.meta = meta }
                if let m = meta.xyzToCam { self.prefs.setXyzToCam(m, lens: lens) }
                self.syncWithCache()
                if developNow {
                    self.enqueue(name, settings)
                } else {
                    self.updateShot(name) { $0.status = .cached }
                }
            case let .failure(error):
                log.error("save \(name): \(error.localizedDescription)")
                self.updateShot(name) { $0.status = .failed(error.localizedDescription) }
            }
        }
        return name
    }

    /// Forget a frame: the DNG and its metadata leave the cache, the
    /// negative leaves memory if it is this one. Exported pictures stay.
    func delete(_ name: String) {
        queue.removeAll { $0 == name }
        cache.remove(name)
        if current?.name == name { current = nil }
        shots.removeAll { $0.name == name }
        refreshCacheSize()
    }

    /// Empty the roll: every cached frame goes, the exports are untouched.
    func deleteAll() {
        queue.removeAll()
        cache.removeAll()
        current = nil
        shots.removeAll()
        refreshCacheSize()
    }

    /// Drop list entries whose frame the cache has trimmed away.
    private func syncWithCache() {
        let names = Set(cache.names())
        shots.removeAll { !(names.contains($0.name) || $0.status == .saving) }
        refreshCacheSize()
    }

    private func refreshCacheSize() {
        cacheBytes = cache.sizeBytes()
    }

    /// Fresh settings in the standing process (what a frame from an earlier run is developed in).
    func standingSettings() -> DevelopSettings { withStandingProcess(DevelopSettings()) }

    /// `s` in the process the darkroom stands in now (a second development follows the switch made in review).
    func withStandingProcess(_ s: DevelopSettings) -> DevelopSettings {
        var out = s
        out.process = prefs.process
        out.plateFilters = prefs.plateFilters
        out.plateSpace = prefs.plateSpace
        return out
    }

    /// Develop (again) from the cache, e.g. with other settings.
    func redevelop(_ name: String, settings: DevelopSettings) {
        enqueue(name, settings)
    }

    /// The developer moved this frame's print: the roll's entry and the
    /// file beside the frame follow. The standing grade and Peak are not touched.
    func setPrint(_ name: String, _ print: Print) {
        updateShot(name) { $0.print = print }
        do {
            try cache.savePrint(name, print)
        } catch {
            log.warning("print file \(name): \(error.localizedDescription)")
        }
    }

    /// The roll's thumbnail of a frame whose grade moved in the developer.
    func setThumbnail(_ name: String, _ image: CGImage) {
        updateShot(name) { $0.thumbnail = image }
        cache.saveThumb(name, image)
    }

    private func enqueue(_ name: String, _ settings: DevelopSettings) {
        if !queue.contains(name) { queue.append(name) }
        updateShot(name) {
            $0.settings = settings
            $0.status = .queued
        }
        pump()
    }

    // MARK: the worker

    /// One development at a time: the separation needs several hundred
    /// megabytes at 12 MP and a second one beside it is what gets an app
    /// killed. The system gets a few seconds past backgrounding to finish.
    private func pump() {
        guard !running, let name = queue.first else { return }
        queue.removeFirst()
        guard let shot = shots.first(where: { $0.name == name }) else { pump(); return }
        running = true
        developing = name
        updateShot(name) { $0.status = .developing }
        let bg = BackgroundTask(name: "develop \(name)")
        let params = developParams(for: shot.meta, shot.settings)
        let paper = PaperSettings(print: shot.print, lampRed: 0, lampBlue: 0)
        let path = cache.rawFile(name).path
        let settings = shot.settings
        let hadJpeg = shot.jpegURL != nil
        let exporter = exporter
        let finder = shot.finder
        Task {
            let outcome = await worker.run {
                Darkroom.develop(
                    name: name, path: path, params: params, settings: settings, paper: paper, exporter: exporter,
                    wantJpeg: settings.autoJpeg && !hadJpeg, matchFinder: finder != nil
                )
            }
            switch outcome {
            case let .success(result):
                if self.shots.contains(where: { $0.name == name }) {
                    self.current = result.negative
                    if let thumb = result.thumbnail { self.cache.saveThumb(name, thumb) }
                    self.updateShot(name) {
                        $0.status = .ready(result.negative.info)
                        $0.thumbnail = result.thumbnail
                        if let url = result.jpeg { $0.jpegURL = url }
                        $0.finder = nil
                    }
                    if let url = result.jpeg {
                        Task { _ = await exporter.addToPhotos(url) }
                    }
                    if let finder, let negative = result.negativeMedian { self.recordMatch(name, finder: finder, negativeMedian: negative) }
                } else {
                    // Deleted while it developed: the negative is dropped here
                    // (the handle frees the core's memory when its last reference
                    // goes), and no thumbnail is written for a frame that is gone.
                    log.info("developed \(name) after its deletion; dropped")
                }
            case let .failure(error):
                self.updateShot(name) { $0.status = .failed(error.localizedDescription) }
            }
            self.running = false
            self.developing = nil
            bg.end()
            self.pump()
        }
    }

    private struct DevelopResult {
        var negative: Negative
        var thumbnail: CGImage?
        var jpeg: URL?
        /// The median of the negative's linear mix, when the finder's is to be matched.
        var negativeMedian: Double?
    }

    /// The paper finder's frame against its negative: one more measurement
    /// for the finder, one line in the file and the log.
    private func recordMatch(_ name: String, finder: FinderSample, negativeMedian: Double) {
        guard finder.median > 0, negativeMedian > 0 else { return }
        let stops = log2(negativeMedian / finder.median)
        prefs.addFinderMatch(stops)
        let meta = shots.first { $0.name == name }?.meta
        let record = FinderMatchRecord(
            at: ISO8601DateFormatter().string(from: Date()), frame: name, device: FinderMatchLog.deviceModel,
            ios: UIDevice.current.systemVersion, lens: meta?.lens ?? "", iso: meta?.iso,
            exposureNs: meta?.exposureNs, format: finder.format, finderMedian: finder.median,
            negativeMedian: negativeMedian, stops: stops, applied: finder.applied, match: prefs.finderMatch.stops
        )
        Task { await intake.run { FinderMatchLog.append(record) } }
    }

    /// Off the main actor: the development, the plate if asked, Peak's
    /// measurement, the thumbnail and the automatic JPEG; the negative's
    /// median when the finder's is waiting for it.
    nonisolated private static func develop(
        name: String, path: String, params: DevelopParams, settings: DevelopSettings, paper: PaperSettings,
        exporter: Exporter, wantJpeg: Bool, matchFinder: Bool
    ) -> Result<DevelopResult, Error> {
        do {
            let t0 = Date()
            let developed = try developFile(path: path, params: params)
            var negativeMedian: Double?
            if matchFinder {
                do {
                    negativeMedian = try developed.mixMedian()
                } catch {
                    log.warning("mix median \(name): \(error.localizedDescription)")
                }
            }
            if settings.isPlate {
                // The plate is a re-mix of the separation just made; the
                // lamps start from the settings, as the paper does.
                let w = try developed.setPlate(filters: settings.plateFilters, space: settings.plateSpace)
                try developed.setLamps(red: paper.lampRed, blue: paper.lampBlue)
                log.info("plate \(Format.plateFilters(w.filters)): R \(Format.weights(w.red)) G \(Format.weights(w.green)) B \(Format.weights(w.blue))")
            }
            let info = developed.info()
            log.info("developed \(name) in \(Int(Date().timeIntervalSince(t0) * 1000)) ms: total \(info.timing.totalMs) ms")
            let grade = paper.print.grade
            let thumb: CGImage?
            if info.plate != nil {
                thumb = Images.thumbnail(try developed.previewRgb(soft: grade.soft, hard: grade.hard, extraQuarterTurns: 0))
            } else {
                thumb = Images.thumbnail(try developed.preview(soft: grade.soft, hard: grade.hard, extraQuarterTurns: 0))
            }
            // Peak's radius and amount for this negative, as the desktop
            // measures them on opening a picture. Measured even with the
            // checkbox off: review can turn it on without a second pass.
            let peak: PeakMeasured?
            do {
                let m = try developed.measurePeak(soft: grade.soft, hard: grade.hard)
                log.info("peak \(name): radius \(m.radius) px, amount \(m.amount), found \(m.found)")
                peak = m
            } catch {
                log.warning("peak \(name): \(error.localizedDescription)")
                peak = nil
            }
            let negative = Negative(name: name, developed: developed, info: info, settings: settings, peak: peak)
            var jpeg: URL?
            if wantJpeg {
                let suffix = suffix(for: settings, info: info)
                jpeg = try exporter.place(fileName: "\(name)\(suffix).jpg") { p in
                    let peakPair = paper.print.peak.applied(peak)
                    _ = try developed.exportJpeg(
                        path: p, soft: grade.soft, hard: grade.hard, extraQuarterTurns: 0,
                        peakRadius: peakPair.radius, peakAmount: peakPair.amount
                    )
                }
            }
            return .success(DevelopResult(negative: negative, thumbnail: thumb, jpeg: jpeg, negativeMedian: negativeMedian))
        } catch {
            log.error("develop \(name): \(error.localizedDescription)")
            return .failure(error)
        }
    }

    // MARK: parameters

    /// Camera file by make, model and the sensor's CFA, plus the settings.
    func developParams(for meta: ShotMeta, _ s: DevelopSettings) -> DevelopParams {
        developParams(make: meta.make, model: meta.model, pattern: meta.bayerPattern, s)
    }

    /// The same, before any frame exists.
    func developParams(make: String, model: String, pattern: BayerPattern, _ s: DevelopSettings) -> DevelopParams {
        var p = defaultDevelopParams()
        p.separation = s.separation
        p.binning = UInt32(s.binning)
        p.filter = s.filter
        p.whiteBalance = s.whiteBalance
        p.cameraFileJson = cameraFiles.forModel(make: make, model: model, pattern: pattern)
        return p
    }

    // MARK: exports (call off the main actor)

    /// The full-size JPEG into Files (and Photos), Peak's high-pass
    /// applied when `peakAmount` > 0. In the plate process the file is RGB
    /// with the plate's profile.
    nonisolated static func exportJpeg(
        _ d: Developed, name: String, suffix: String, soft: Double, hard: Double, peakRadius: Double, peakAmount: Double,
        exporter: Exporter
    ) throws -> URL {
        try exporter.place(fileName: "\(name)\(suffix).jpg") { p in
            _ = try d.exportJpeg(path: p, soft: soft, hard: hard, extraQuarterTurns: 0, peakRadius: peakRadius, peakAmount: peakAmount)
        }
    }

    /// The same picture as a 16-bit TIFF (RGB for a plate).
    nonisolated static func exportTiff(
        _ d: Developed, name: String, suffix: String, soft: Double, hard: Double, peakRadius: Double, peakAmount: Double,
        exporter: Exporter
    ) throws -> URL {
        try exporter.place(fileName: "\(name)\(suffix).tif") { p in
            _ = try d.exportTiff16(path: p, soft: soft, hard: hard, extraQuarterTurns: 0, peakRadius: peakRadius, peakAmount: peakAmount)
        }
    }

    /// Linear negative + saturation mask, the desktop interchange format.
    /// In the plate process the stacked three-frame plate
    /// (`<name>.plate.tif`), which `mimizan print` prints in colour.
    nonisolated static func exportNegative(_ d: Developed, name: String, exporter: Exporter) throws -> [URL] {
        let stem = d.info().plate != nil ? "\(name).plate" : "\(name).negative"
        let fileName = "\(stem).tif"
        let main = try exporter.place(fileName: fileName) { p in
            _ = try d.exportNegative(path: p)
        }
        var out = [main]
        // The core wrote `<stem>.mask.tif` beside the scratch file; it goes
        // beside the final file under the final name, so a `(2)` pair stays a pair.
        if let mask = exporter.placeSidecar(scratchOf: fileName, beside: main) { out.append(mask) }
        return out
    }

    // MARK: names

    private func updateShot(_ name: String, _ f: (inout Shot) -> Void) {
        guard let i = shots.firstIndex(where: { $0.name == name }) else { return }
        f(&shots[i])
    }

    nonisolated static func nameFor(_ date: Date) -> String { "MLM_" + Format.stamp.string(from: date) }

    nonisolated static func suffix(for filter: Filter) -> String {
        switch filter {
        case .none: return ""
        case .yellow8: return "_Y8"
        case .yellowGreen11: return "_YG11"
        case .orange16: return "_O16"
        case .red25: return "_R25"
        case .green58: return "_G58"
        case .blue47: return "_B47"
        }
    }

    /// The plate in the file name, by the set the frame resolved to.
    nonisolated static func suffix(for plate: PlateState) -> String {
        switch plate.weights.filters {
        case .camera: return "_plate"
        case .wratten: return "_plate_wratten"
        case .channels: return "_plate_channels"
        }
    }

    /// The file-name suffix of a development as it stands: the plate it is in, else its filter.
    nonisolated static func suffix(for settings: DevelopSettings, info: DevelopInfo) -> String {
        info.plate.map { suffix(for: $0) } ?? suffix(for: settings.filter)
    }
}

/// A serial queue for the core's calls: one `Developed` is used from one
/// `Gate`, so its calls never overlap, and the work runs on a thread of
/// the queue's own rather than on Swift's cooperative pool, which a call
/// of several seconds would otherwise block.
final class Gate: @unchecked Sendable {
    private let queue: DispatchQueue

    init(label: String = "ch.bojovic.mimizanlab.gate") {
        queue = DispatchQueue(label: label, qos: .userInitiated)
    }

    func run<T>(_ body: @escaping () -> T) async -> T {
        await withCheckedContinuation { (cont: CheckedContinuation<T, Never>) in
            queue.async { cont.resume(returning: body()) }
        }
    }
}

/// One `beginBackgroundTask`, ended exactly once: by `end()` when the work
/// is done, or by the expiry handler, which must end it or the system
/// terminates the app. The development itself cannot be cut short; ended
/// on expiry, the app is suspended instead and the development finishes
/// when it comes back.
@MainActor
final class BackgroundTask {
    private var id: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}

extension FrameInfo {
    /// A placeholder until the file has been read.
    static let empty = FrameInfo(
        make: "", model: "", width: 0, height: 0, pattern: .rggb, orientation: 1, wbGains: nil, xyzToCam: nil,
        exposure: ExposureInfo(iso: nil, exposureNs: nil, fNumber: nil, focalMm: nil, lens: nil), captured: nil
    )
}
