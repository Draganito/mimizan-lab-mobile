// A paired record of what the viewfinder showed and what the development
// produced, one entry per frame, written to `Documents/Mimizan Lab/
// preview-debug.json`. The two halves measure the same scene a fraction of
// a second apart, so the difference between them is the finder's error:
// the preview side is the ISP's picture through the shader, the result
// side is the DNG through the paper. Shares sum to 1, percentiles are on
// the 0...1 axis. Mailed as is and read back to correct the finder.
import Foundation
import MimizanCore
import Observation
import simd

struct DebugEntry: Codable {
    var name: String
    var capturedAt: String
    var device: String
    var lens: String
    var preview: DebugPreview
    var result: DebugResult?
}

/// The finder frame at the moment of release.
struct DebugPreview: Codable {
    var iso: Int?
    var exposureSeconds: Double?
    var biasStops: Double
    var wbGains: [Double]?
    var rotationQuarters: Int
    var process: String
    var filter: String
    var paperSoft: Double
    var paperHard: Double
    /// The mix weights on linear sRGB the shader applied.
    var mixWeights: [Float]
    /// The plate's three taking filters on linear sRGB, rows R, G, B; nil for mono.
    var plateMix: [[Float]]?
    var lampGain: [Float]
    var xyzToCam: [Double]?
    var frameWidth: Int
    var frameHeight: Int
    var samples: Int
    /// Linear mix before the paper, 64 bins.
    var linearHistogram: [Float]
    var linearMean: Float
    var linearP05: Float
    var linearP25: Float
    var linearP50: Float
    var linearP75: Float
    var linearP95: Float
    /// Display value after the paper.
    var displayMean: Float
    var displayP50: Float
    /// Linear R, G, B before the mix.
    var channelMean: [Float]
    /// Mean linear mix over a 16 by 12 grid, row-major, sensor orientation.
    var spatialLinear: [Float]
    var spatialWidth: Int
    var spatialHeight: Int
    /// The plate's three filters, 64 bins each, before the lamps.
    var plateLinearHistogram: [Float]?

    init?(measurement m: FinderMeasurement, iso: Int?, exposureSeconds: Double?, biasStops: Double,
          wbGains: [Double]?, rotationQuarters: Int, process: String, filter: String, paperSoft: Double,
          paperHard: Double, tone: FinderTone, xyzToCam: [Double]?) {
        guard m.count > 0 else { return nil }
        self.iso = iso
        self.exposureSeconds = exposureSeconds
        self.biasStops = biasStops
        self.wbGains = wbGains
        self.rotationQuarters = rotationQuarters
        self.process = process
        self.filter = filter
        self.paperSoft = paperSoft
        self.paperHard = paperHard
        mixWeights = [tone.v.x, tone.v.y, tone.v.z]
        plateMix = tone.plate.map { [[$0.0.x, $0.0.y, $0.0.z], [$0.1.x, $0.1.y, $0.1.z], [$0.2.x, $0.2.y, $0.2.z]] }
        lampGain = [tone.lampGain.x, tone.lampGain.y, tone.lampGain.z]
        self.xyzToCam = xyzToCam
        frameWidth = m.frameWidth
        frameHeight = m.frameHeight
        samples = m.count
        linearHistogram = DebugStats.share(m.linearCounts)
        linearMean = DebugStats.mean(m.linearCounts)
        linearP05 = DebugStats.percentile(m.linearCounts, 0.05)
        linearP25 = DebugStats.percentile(m.linearCounts, 0.25)
        linearP50 = DebugStats.percentile(m.linearCounts, 0.50)
        linearP75 = DebugStats.percentile(m.linearCounts, 0.75)
        linearP95 = DebugStats.percentile(m.linearCounts, 0.95)
        displayMean = DebugStats.mean(m.displayCounts)
        displayP50 = DebugStats.percentile(m.displayCounts, 0.50)
        channelMean = [m.channelSum.x, m.channelSum.y, m.channelSum.z].map { $0 / Float(m.count) }
        spatialWidth = FinderMeasurement.spatialWidth
        spatialHeight = FinderMeasurement.spatialHeight
        spatialLinear = zip(m.spatialSum, m.spatialCount).map { c in c.1 > 0 ? c.0 / Float(c.1) : 0 }
        plateLinearHistogram = m.plateCounts.map { $0.flatMap(DebugStats.share) }
    }
}

/// The developed frame.
struct DebugResult: Codable {
    var width: Int
    var height: Int
    var orientation: Int
    var separation: String
    var binning: Int
    var filter: String
    var wbApplied: [Double]
    var weights: [Double]
    var defaultWeights: [Double]
    var saturatedFraction: Double
    var hasColourMatrix: Bool
    var paperSoft: Double
    var paperHard: Double
    var plate: Bool
    var lampRed: Double?
    var lampBlue: Double?
    var fileIso: Int?
    var fileExposureNs: Int64?
    var fileFNumber: Double?
    var fileWbGains: [Double]?
    /// Display value of the print, 256 bins.
    var displayHistogram: [Float]
    var displayMean: Float
    var displayP05: Float
    var displayP50: Float
    var displayP95: Float
    /// The plate's three channels, 256 bins each, each summing to 1.
    var channelHistogram: [Float]?

    static func build(info: DevelopInfo, paper: PaperSettings, display: [UInt64], rgb: [UInt64]?) -> DebugResult {
        let counts = display.map { Int($0) }
        var channels: [Float]?
        if let raw = rgb, raw.count >= 3, raw.count % 3 == 0 {
            let n = raw.count / 3
            channels = (0..<3).flatMap { c in DebugStats.share((0..<n).map { Int(raw[c * n + $0]) }) }
        }
        return DebugResult(
            width: Int(info.uprightWidth), height: Int(info.uprightHeight), orientation: Int(info.orientation),
            separation: info.separation.name, binning: Int(info.binning), filter: info.filter.name,
            wbApplied: info.wb, weights: [info.weights.r, info.weights.g, info.weights.b],
            defaultWeights: [info.defaultWeights.r, info.defaultWeights.g, info.defaultWeights.b],
            saturatedFraction: info.saturatedFraction, hasColourMatrix: info.hasColourMatrix,
            paperSoft: paper.soft, paperHard: paper.hard, plate: info.plate != nil,
            lampRed: info.plate?.lampRed, lampBlue: info.plate?.lampBlue,
            fileIso: nil, fileExposureNs: nil, fileFNumber: nil, fileWbGains: nil,
            displayHistogram: DebugStats.share(counts), displayMean: DebugStats.mean(counts),
            displayP05: DebugStats.percentile(counts, 0.05), displayP50: DebugStats.percentile(counts, 0.50),
            displayP95: DebugStats.percentile(counts, 0.95), channelHistogram: channels
        )
    }
}

enum DebugStats {
    /// Bin counts as shares of the total.
    static func share(_ counts: [Int]) -> [Float] {
        let total = counts.reduce(0, +)
        guard total > 0 else { return counts.map { _ in 0 } }
        return counts.map { Float($0) / Float(total) }
    }

    /// Mean bin position on the 0...1 axis, bins taken as equal steps.
    static func mean(_ counts: [Int]) -> Float {
        let total = counts.reduce(0, +)
        guard total > 0, !counts.isEmpty else { return 0 }
        let sum = counts.enumerated().reduce(0) { $0 + $1.offset * $1.element }
        return (Float(sum) / Float(total) + 0.5) / Float(counts.count)
    }

    /// The value on the 0...1 axis at which the cumulative share reaches `p`.
    static func percentile(_ counts: [Int], _ p: Float) -> Float {
        let total = counts.reduce(0, +)
        guard total > 0, !counts.isEmpty else { return 0 }
        let target = p * Float(total)
        var cum = 0
        for (i, c) in counts.enumerated() {
            let next = cum + c
            if Float(next) >= target {
                let frac = c > 0 ? (target - Float(cum)) / Float(c) : 0
                return (Float(i) + min(max(frac, 0), 1)) / Float(counts.count)
            }
            cum = next
        }
        return 1
    }
}

@MainActor
@Observable
final class DebugLog {
    private(set) var entries: [DebugEntry] = []
    let url: URL
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("Mimizan Lab", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("preview-debug.json")
        if let data = try? Data(contentsOf: url), let loaded = try? JSONDecoder().decode([DebugEntry].self, from: data) {
            entries = loaded
        }
    }

    func add(_ entry: DebugEntry) {
        entries.append(entry)
        if entries.count > 40 { entries.removeFirst(entries.count - 40) }
        save()
    }

    /// Fill in the development for a frame recorded at capture. No entry, no work.
    func complete(name: String, result: DebugResult) {
        guard let i = entries.lastIndex(where: { $0.name == name }) else { return }
        entries[i].result = result
        save()
    }

    func clear() {
        entries.removeAll()
        save()
    }

    private func save() {
        guard let data = try? encoder.encode(entries) else { return }
        try? data.write(to: url, options: .atomic)
    }

    static func stamp(_ date: Date) -> String {
        ISO8601DateFormatter().string(from: date)
    }
}
