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

/// The paper's two exposures, the plate's two lamps and the Peak checkbox
/// as they stand: read on the main actor, carried into the worker.
struct PaperSettings: Sendable {
    var soft: Double
    var hard: Double
    var lampRed: Double
    var lampBlue: Double
    var peakOn: Bool
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

    private init() {
        initLogging()
        // Frames from an earlier run are still in the cache: list them so
        // they can be developed again.
        let cache = cache
        Task.detached(priority: .utility) {
            let restored: [Shot] = cache.names().compactMap { name in
                cache.loadMeta(name).map {
                    Shot(name: name, meta: $0, settings: DevelopSettings(), status: .cached, thumbnail: cache.loadThumb(name))
                }
            }
            let bytes = cache.sizeBytes()
            await MainActor.run {
                if !restored.isEmpty { self.shots = restored + self.shots }
                self.cacheBytes = bytes
            }
        }
    }

    var paperSettings: PaperSettings {
        PaperSettings(soft: prefs.soft, hard: prefs.hard, lampRed: 0, lampBlue: 0, peakOn: prefs.peak)
    }

    // MARK: intake

    /// Called from the camera: persists the DNG, reads what it is, then
    /// queues the development.
    func submit(dng: Data, lens: String, capturedAt: Date, settings: DevelopSettings) {
        let name = Darkroom.nameFor(capturedAt)
        let provisional = ShotMeta(info: FrameInfo.empty, lens: lens, capturedAt: capturedAt, imported: false)
        shots.insert(Shot(name: name, meta: provisional, settings: settings, status: .saving), at: 0)
        let cache = cache
        Task.detached(priority: .userInitiated) {
            do {
                try cache.putRaw(name, dng: dng)
                let info = try frameInfo(path: cache.rawFile(name).path)
                let meta = ShotMeta(info: info, lens: lens, capturedAt: capturedAt, imported: false)
                try cache.commit(name, meta: meta)
                await MainActor.run {
                    self.updateShot(name) { $0.meta = meta }
                    self.syncWithCache()
                    self.enqueue(name, settings)
                }
            } catch {
                log.error("save \(name): \(error.localizedDescription)")
                await MainActor.run {
                    self.updateShot(name) { $0.status = .failed(error.localizedDescription) }
                }
            }
        }
    }

    /// A RAW file from Files or Photos: copied into the cache under a
    /// fresh name and developed in the standing process.
    func importFile(_ url: URL, settings: DevelopSettings) {
        let now = Date()
        let name = Darkroom.nameFor(now) + "_import"
        let cache = cache
        let scoped = url.startAccessingSecurityScopedResource()
        Task.detached(priority: .userInitiated) {
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                try cache.putRaw(name, copying: url)
                let info = try frameInfo(path: cache.rawFile(name).path)
                let meta = ShotMeta(info: info, lens: url.lastPathComponent, capturedAt: now, imported: true)
                try cache.commit(name, meta: meta)
                await MainActor.run {
                    self.shots.insert(Shot(name: name, meta: meta, settings: settings, status: .saving), at: 0)
                    self.syncWithCache()
                    self.enqueue(name, settings)
                }
            } catch {
                cache.remove(name)
                log.error("import \(url.lastPathComponent): \(error.localizedDescription)")
                await MainActor.run {
                    self.shots.insert(
                        Shot(name: name, meta: ShotMeta(info: FrameInfo.empty, lens: url.lastPathComponent, capturedAt: now, imported: true),
                             settings: settings, status: .failed(error.localizedDescription)),
                        at: 0
                    )
                }
            }
        }
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
        let bg = UIApplication.shared.beginBackgroundTask(withName: "develop \(name)") {}
        let params = developParams(for: shot.meta, shot.settings)
        let paper = paperSettings
        let path = cache.rawFile(name).path
        let settings = shot.settings
        let hadJpeg = shot.jpegURL != nil
        let exporter = exporter
        Task.detached(priority: .userInitiated) {
            let outcome = Darkroom.develop(
                name: name, path: path, params: params, settings: settings, paper: paper, exporter: exporter,
                wantJpeg: settings.autoJpeg && !hadJpeg
            )
            await MainActor.run {
                switch outcome {
                case let .success(result):
                    self.current = result.negative
                    if let thumb = result.thumbnail { self.cache.saveThumb(name, thumb) }
                    self.updateShot(name) {
                        $0.status = .ready(result.negative.info)
                        $0.thumbnail = result.thumbnail
                        if let url = result.jpeg { $0.jpegURL = url }
                    }
                    if let url = result.jpeg {
                        Task { _ = await exporter.addToPhotos(url) }
                    }
                case let .failure(error):
                    self.updateShot(name) { $0.status = .failed(error.localizedDescription) }
                }
                self.running = false
                self.developing = nil
                UIApplication.shared.endBackgroundTask(bg)
                self.pump()
            }
        }
    }

    private struct DevelopResult {
        var negative: Negative
        var thumbnail: CGImage?
        var jpeg: URL?
    }

    /// Off the main actor: the development, the plate if asked, Peak's
    /// measurement, the thumbnail and the automatic JPEG.
    nonisolated private static func develop(
        name: String, path: String, params: DevelopParams, settings: DevelopSettings, paper: PaperSettings,
        exporter: Exporter, wantJpeg: Bool
    ) -> Result<DevelopResult, Error> {
        do {
            let t0 = Date()
            let developed = try developFile(path: path, params: params)
            if settings.isPlate {
                // The plate is a re-mix of the separation just made; the
                // lamps start from the settings, as the paper does.
                let w = try developed.setPlate(filters: settings.plateFilters, space: settings.plateSpace)
                try developed.setLamps(red: paper.lampRed, blue: paper.lampBlue)
                log.info("plate \(Format.plateFilters(w.filters)): R \(Format.weights(w.red)) G \(Format.weights(w.green)) B \(Format.weights(w.blue))")
            }
            let info = developed.info()
            log.info("developed \(name) in \(Int(Date().timeIntervalSince(t0) * 1000)) ms: total \(info.timing.totalMs) ms")
            let thumb: CGImage?
            if info.plate != nil {
                thumb = Images.thumbnail(try developed.previewRgb(soft: paper.soft, hard: paper.hard, extraQuarterTurns: 0))
            } else {
                thumb = Images.thumbnail(try developed.preview(soft: paper.soft, hard: paper.hard, extraQuarterTurns: 0))
            }
            // Peak's radius and amount for this negative, as the desktop
            // measures them on opening a picture. Measured even with the
            // checkbox off: review can turn it on without a second pass.
            let peak: PeakMeasured?
            do {
                let m = try developed.measurePeak(soft: paper.soft, hard: paper.hard)
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
                    _ = try developed.exportJpeg(
                        path: p, soft: paper.soft, hard: paper.hard, extraQuarterTurns: 0,
                        peakRadius: peak?.radius ?? Prefs.peakLimits.radiusDefault,
                        peakAmount: paper.peakOn ? (peak?.amount ?? 0) : 0
                    )
                }
            }
            return .success(DevelopResult(negative: negative, thumbnail: thumb, jpeg: jpeg))
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
        let main = try exporter.place(fileName: "\(stem).tif") { p in
            _ = try d.exportNegative(path: p)
        }
        var out = [main]
        if let mask = exporter.placeSidecar("\(stem).mask.tif") { out.append(mask) }
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

extension FrameInfo {
    /// A placeholder until the file has been read.
    static let empty = FrameInfo(
        make: "", model: "", width: 0, height: 0, pattern: .rggb, orientation: 1, wbGains: nil, xyzToCam: nil,
        exposure: ExposureInfo(iso: nil, exposureNs: nil, fNumber: nil, focalMm: nil, lens: nil), captured: nil
    )
}
