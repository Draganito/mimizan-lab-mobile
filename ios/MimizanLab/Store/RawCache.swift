// App-private copy of every frame: `<name>.dng` as AVFoundation wrote it,
// `<name>.json`, the metadata the roll shows, and `<name>.grade`, the
// print (grade and Peak). A shot can be developed again later from the
// file alone. Oldest frames go first when the cache exceeds the budget.
import CoreGraphics
import Foundation
import ImageIO
import MimizanCore
import os
import UniformTypeIdentifiers

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "raw-cache")

/// What the roll knows about a frame without decoding it again.
struct ShotMeta: Codable, Equatable {
    var make: String
    var model: String
    var width: Int
    var height: Int
    var pattern: String
    var orientation: Int
    var lens: String
    var iso: Int?
    var exposureNs: Int64?
    var fNumber: Double?
    var focalMm: Double?
    /// XYZ → camera, 9 values row-major, when the file carries a matrix.
    var xyzToCam: [Double]?
    var wbGains: [Double]?
    var capturedAt: Date
    /// The file came from Files or Photos, not from the camera.
    var imported: Bool

    init(info: FrameInfo, lens: String, capturedAt: Date, imported: Bool) {
        make = info.make
        model = info.model
        width = Int(info.width)
        height = Int(info.height)
        pattern = info.pattern.name
        orientation = Int(info.orientation)
        self.lens = lens
        iso = info.exposure.iso.map { Int($0) }
        exposureNs = info.exposure.exposureNs.map { Int64($0) }
        fNumber = info.exposure.fNumber
        focalMm = info.exposure.focalMm
        xyzToCam = info.xyzToCam
        wbGains = info.wbGains
        self.capturedAt = capturedAt
        self.imported = imported
    }

    var bayerPattern: BayerPattern { BayerPattern.fromName(pattern) ?? .rggb }
}

/// `<name>.grade` on disk: the grade's two exposures, `peak`, and the
/// process and filter the frame was last developed or re-mixed in, as
/// the Android `Grade.kt` writes them (`PROCESS`/`FILTER` enum names).
/// Every field past `soft` and `hard` is optional, so an older file reads.
private struct RecipeFile: Codable {
    var soft: Double
    var hard: Double
    var peak: Peak?
    var process: String?
    var filter: String?
    var plateFilters: String?

    init(print: Print, settings: DevelopSettings) {
        soft = print.grade.soft
        hard = print.grade.hard
        peak = print.peak
        process = settings.process.rawValue
        filter = settings.filter.name
        plateFilters = settings.plateFilters.name
    }

    func print(standingPeak: Peak) -> Print {
        Print(grade: Prefs.snapGrade(soft: soft, hard: hard), peak: peak ?? standingPeak)
    }

    var settings: DevelopSettings {
        var s = DevelopSettings()
        s.process = process.flatMap(Process.init(rawValue:)) ?? .mono
        s.filter = Filter.fromName(filter) ?? .none
        s.plateFilters = PlateFilters.fromName(plateFilters) ?? .camera
        return s
    }
}

final class RawCache: @unchecked Sendable {
    let dir: URL
    private let budgetBytes: Int64
    private let fm = FileManager.default

    init(budgetBytes: Int64 = 1_500 << 20) {
        let base = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("raw", isDirectory: true)
        self.budgetBytes = budgetBytes
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        // The cache is re-creatable from nothing but a loss; never in a backup.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(values)
    }

    func rawFile(_ name: String) -> URL { dir.appendingPathComponent("\(name).dng") }
    func metaFile(_ name: String) -> URL { dir.appendingPathComponent("\(name).json") }
    /// This frame's print, as the developer last left it: the desktop's recipe beside the RAW.
    func printFile(_ name: String) -> URL { dir.appendingPathComponent("\(name).grade") }
    /// The developed preview, already reduced, kept so the roll can show it without developing again.
    func thumbFile(_ name: String) -> URL { dir.appendingPathComponent("\(name).jpg") }

    /// Pixels first (`putRaw`), metadata last (`commit`), each written
    /// atomically: a frame only counts (`has`, `names`) once both files
    /// are complete, so a crash mid-write leaves at most an orphan `.dng`
    /// that a later trim sweeps away once it is a minute old.
    func putRaw(_ name: String, dng: Data) throws {
        try dng.write(to: rawFile(name), options: .atomic)
    }

    func commit(_ name: String, meta: ShotMeta, print: Print, settings: DevelopSettings) throws {
        try saveRecipe(name, print: print, settings: settings)
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        let data = try enc.encode(meta)
        try data.write(to: metaFile(name), options: .atomic)
        trim(keep: name)
    }

    /// The frame's recipe, overwritten in place: the file is a few
    /// numbers and names, and the old ones are no loss.
    func saveRecipe(_ name: String, print: Print, settings: DevelopSettings) throws {
        try JSONEncoder().encode(RecipeFile(print: print, settings: settings)).write(to: printFile(name), options: .atomic)
    }

    /// The frame's recipe; nil when none was written (a frame from before
    /// 0.15) or the file is damaged. A file with the grade alone (0.15.0)
    /// takes `standingPeak`; one without the process and filter (0.17.9
    /// and earlier) reads as Mono, Pan.
    func loadRecipe(_ name: String, standingPeak: Peak) -> (print: Print, settings: DevelopSettings)? {
        guard let data = try? Data(contentsOf: printFile(name)), let f = try? JSONDecoder().decode(RecipeFile.self, from: data) else { return nil }
        return (f.print(standingPeak: standingPeak), f.settings)
    }

    func has(_ name: String) -> Bool {
        fm.fileExists(atPath: rawFile(name).path) && fm.fileExists(atPath: metaFile(name).path)
    }

    /// The metadata alone; nil when the frame is not cached. A `.json` that
    /// is there but does not decode is moved beside itself as
    /// `<name>.bad-<stamp>.json` (twin of `FormatDump.readOrSetAside`), so
    /// the damaged file is not lost and the roll no longer lists a frame
    /// it cannot open.
    func loadMeta(_ name: String) -> ShotMeta? {
        guard has(name) else { return nil }
        let url = metaFile(name)
        guard let data = try? Data(contentsOf: url) else { return nil }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        if let meta = try? dec.decode(ShotMeta.self, from: data) { return meta }
        let stamp = Int(Date().timeIntervalSince1970)
        let aside = url.deletingPathExtension().appendingPathExtension("bad-\(stamp).json")
        do {
            try fm.moveItem(at: url, to: aside)
            log.warning("\(url.lastPathComponent) did not decode; set aside as \(aside.lastPathComponent)")
        } catch {
            log.warning("could not set aside damaged \(url.lastPathComponent): \(error.localizedDescription)")
        }
        return nil
    }

    /// A JPEG of the thumbnail the development already made. Nothing is decoded from the raw.
    func saveThumb(_ name: String, _ image: CGImage) {
        let url = thumbFile(name)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.55] as CFDictionary)
        CGImageDestinationFinalize(dest)
    }

    func loadThumb(_ name: String) -> CGImage? {
        let url = thumbFile(name)
        guard fm.fileExists(atPath: url.path),
              let src = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary)
        else { return nil }
        return CGImageSourceCreateImageAtIndex(src, 0, nil)
    }

    func remove(_ name: String) {
        try? fm.removeItem(at: rawFile(name))
        try? fm.removeItem(at: metaFile(name))
        try? fm.removeItem(at: printFile(name))
        try? fm.removeItem(at: thumbFile(name))
    }

    func removeAll() {
        for f in files() { try? fm.removeItem(at: f) }
    }

    /// Bytes on disk for all cached frames.
    func sizeBytes() -> Int64 {
        files().reduce(0) { $0 + size(of: $1) }
    }

    /// Names of cached frames (both files present), newest first.
    func names() -> [String] {
        files()
            .filter { $0.pathExtension == "json" }
            .sorted { modified($0) > modified($1) }
            .map { $0.deletingPathExtension().lastPathComponent }
            .filter(has)
    }

    /// A `.dng` without its `.json` is an orphan only once it is old: a
    /// frame whose `putRaw` has run and whose `commit` is still reading
    /// the file looks the same for a moment, and must not be swept by a
    /// sibling's trim.
    private static let orphanAge: TimeInterval = 60

    private func trim(keep: String) {
        let raws = files().filter { $0.pathExtension == "dng" }.sorted { modified($0) < modified($1) }
        var total = raws.reduce(0) { $0 + size(of: $1) }
        let now = Date()
        for f in raws {
            let name = f.deletingPathExtension().lastPathComponent
            if name == keep { continue }
            if fm.fileExists(atPath: metaFile(name).path) {
                // A frame: goes only while the cache is over budget, oldest first.
                if total <= budgetBytes { continue }
            } else if now.timeIntervalSince(modified(f)) < RawCache.orphanAge {
                // A frame still being written, whatever the budget says.
                continue
            }
            // An old orphan from an interrupted put() is never a frame; it goes regardless of the budget.
            total -= size(of: f)
            remove(name)
        }
        // Sidecars whose frame is gone (a `commit` that landed after a
        // `removeAll`, a DNG swept earlier) are never a frame either.
        let dngs = Set(files().filter { $0.pathExtension == "dng" }.map { $0.deletingPathExtension().lastPathComponent })
        for f in files() where f.pathExtension != "dng" {
            let name = f.deletingPathExtension().lastPathComponent
            if name != keep, !dngs.contains(name), now.timeIntervalSince(modified(f)) >= RawCache.orphanAge { try? fm.removeItem(at: f) }
        }
    }

    private func files() -> [URL] {
        (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? []
    }

    private func size(of url: URL) -> Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }

    private func modified(_ url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }
}
