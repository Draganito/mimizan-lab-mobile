// App-private copy of every frame: `<name>.dng` as AVFoundation wrote it
// (or as the user imported it) and `<name>.json`, the metadata the roll
// shows. A shot can be developed again later from the file alone. Oldest
// frames go first when the cache exceeds the budget.
import CoreGraphics
import Foundation
import ImageIO
import MimizanCore
import UniformTypeIdentifiers

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
    /// The developed preview, already reduced, kept so the roll can show it without developing again.
    func thumbFile(_ name: String) -> URL { dir.appendingPathComponent("\(name).jpg") }

    /// Pixels first (`putRaw`), metadata last (`commit`), each written
    /// atomically: a frame only counts (`has`, `names`) once both files
    /// are complete, so a crash mid-write leaves at most an orphan `.dng`
    /// that the next trim sweeps away.
    func putRaw(_ name: String, dng: Data) throws {
        try dng.write(to: rawFile(name), options: .atomic)
    }

    /// The same for a file that already exists (an import): copied in.
    func putRaw(_ name: String, copying source: URL) throws {
        let dst = rawFile(name)
        try? fm.removeItem(at: dst)
        try fm.copyItem(at: source, to: dst)
    }

    func commit(_ name: String, meta: ShotMeta) throws {
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        let data = try enc.encode(meta)
        try data.write(to: metaFile(name), options: .atomic)
        trim(keep: name)
    }

    func has(_ name: String) -> Bool {
        fm.fileExists(atPath: rawFile(name).path) && fm.fileExists(atPath: metaFile(name).path)
    }

    /// The metadata alone; nil when the frame is not cached or its file is damaged.
    func loadMeta(_ name: String) -> ShotMeta? {
        guard has(name), let data = try? Data(contentsOf: metaFile(name)) else { return nil }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .iso8601
        return try? dec.decode(ShotMeta.self, from: data)
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
        try? fm.removeItem(at: thumbFile(name))
    }

    func removeAll() {
        for f in files() { try? fm.removeItem(at: f) }
    }

    /// Bytes on disk for all cached frames.
    func sizeBytes() -> Int64 {
        files().reduce(0) { $0 + size(of: $1) }
    }

    /// Names of cached frames, newest first.
    func names() -> [String] {
        files()
            .filter { $0.pathExtension == "json" }
            .sorted { modified($0) > modified($1) }
            .map { $0.deletingPathExtension().lastPathComponent }
    }

    private func trim(keep: String) {
        let raws = files().filter { $0.pathExtension == "dng" }.sorted { modified($0) < modified($1) }
        var total = raws.reduce(0) { $0 + size(of: $1) }
        for f in raws {
            let name = f.deletingPathExtension().lastPathComponent
            if name == keep { continue }
            // An orphan from an interrupted put() is never a frame; it goes regardless of the budget.
            if total <= budgetBytes && fm.fileExists(atPath: metaFile(name).path) { continue }
            total -= size(of: f)
            remove(name)
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
