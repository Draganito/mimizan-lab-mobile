// The paper finder's exposure against the negative's. The Apple Log frame
// is scene-linear with the grey card at 0.18 of the ISP's exposure; the
// negative is linear with 1 at the sensor's clip. The stops between the
// two are not published, so the app measures them: on every frame taken
// with the paper finder, the median of the finder's linear mix at the
// shutter against the median of the developed negative's mix (the core's
// `mixMedian`, the same cut of near-black and clip). Each measurement
// goes to `Documents/finder-match.json` and to the log; the median of
// the last eight is what the finder applies from the next tone on.
//
// The stops differ by lens (the 17 Pro's ultra-wide sat half a stop
// below its main camera over twenty frames, the tele was all over the
// place indoors), so each lens keeps its own eight. A lens not yet
// measured starts from the median of every lens's frames on this
// phone, the prior only before the first frame of all.
import Foundation
import os

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "finder-match")

struct FinderMatch: Equatable {
    /// The last measurements, oldest first.
    private(set) var samples: [Double]

    /// Before any measurement: Apple Log's code 1 taken as the sensor's
    /// clip, so the Log axis is the negative's over 2^((1 − δ)/γ) − β ≈ 12.
    static let prior = Double(-log2(AppleLog.linear(1)))
    static let window = 8

    init(samples: [Double]) {
        self.samples = Array(samples.suffix(FinderMatch.window))
    }

    var count: Int { samples.count }

    /// Stops the finder adds to the Log axis: the median of the
    /// measurements, the prior before the first.
    var stops: Double {
        guard !samples.isEmpty else { return FinderMatch.prior }
        let s = samples.sorted()
        return s.count % 2 == 1 ? s[s.count / 2] : (s[s.count / 2 - 1] + s[s.count / 2]) / 2
    }

    mutating func add(_ stops: Double) {
        samples.append(stops)
        if samples.count > FinderMatch.window { samples.removeFirst(samples.count - FinderMatch.window) }
    }
}

/// The finder match of every lens measured so far, by the lens's label.
struct FinderMatches: Equatable {
    private(set) var byLens: [String: FinderMatch]

    init(byLens: [String: FinderMatch] = [:]) {
        self.byLens = byLens.filter { $0.value.count > 0 }
    }

    /// Lenses with at least one frame, in label order.
    var lenses: [String] { byLens.keys.sorted() }

    /// Frames measured on every lens together.
    var count: Int { byLens.values.reduce(0) { $0 + $1.count } }

    /// Every lens's last frames as one pool: what a lens not measured
    /// yet starts from (the lenses of one phone sit within a stop).
    var pooled: FinderMatch { FinderMatch(samples: byLens.values.flatMap(\.samples)) }

    /// The stops for `lens`: its own median once it has a frame, the
    /// pool's before, the prior before any frame at all.
    func stops(lens: String?) -> Double {
        if let lens, let m = byLens[lens], m.count > 0 { return m.stops }
        return pooled.stops
    }

    /// Frames measured on `lens` itself.
    func count(lens: String?) -> Int { lens.flatMap { byLens[$0]?.count } ?? 0 }

    mutating func add(_ stops: Double, lens: String) {
        var m = byLens[lens] ?? FinderMatch(samples: [])
        m.add(stops)
        byLens[lens] = m
    }
}

/// What the finder measured as a frame was taken, carried with the shot
/// until its first development: the median of the linear mix before the
/// gain, the stream it came from, and the stops the finder applied then.
struct FinderSample: Equatable {
    var median: Double
    var format: String
    var applied: Double
}

/// One frame's measurement, as written to the file.
struct FinderMatchRecord: Codable {
    var at: String
    var frame: String
    var device: String
    var ios: String
    var lens: String
    var iso: Int?
    var exposureNs: Int64?
    var format: String
    var finderMedian: Double
    var negativeMedian: Double
    /// log2(negative / finder): what the Log axis is off by on this frame.
    var stops: Double
    /// What the finder applied when the frame was taken.
    var applied: Double
    /// What the finder applies after this frame.
    var match: Double
}

enum FinderMatchLog {
    static let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("finder-match.json")

    static var exists: Bool { FileManager.default.fileExists(atPath: url.path) }

    /// Append one record (the file is a JSON array, read whole: a few
    /// hundred lines at most). Call off the main actor.
    static func append(_ r: FinderMatchRecord) {
        do {
            var records = FormatDump.readOrSetAside([FinderMatchRecord].self, at: url) ?? []
            records.append(r)
            let enc = JSONEncoder()
            enc.outputFormatting = [.prettyPrinted, .sortedKeys]
            try enc.encode(records).write(to: url, options: .atomic)
        } catch {
            log.warning("finder-match.json: \(error.localizedDescription)")
        }
        log.info("finder match \(r.frame): finder \(r.finderMedian) negative \(r.negativeMedian) → \(r.stops) stops (applied \(r.applied), now \(r.match)) on \(r.format), \(r.lens), ISO \(r.iso ?? 0), \(r.exposureNs ?? 0) ns")
    }

    /// "17.5.1", off any actor (UIDevice is the main actor's).
    static var osVersion: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }

    /// The hardware name (`iPhone16,1`), for the record.
    static let deviceModel: String = {
        var u = utsname()
        uname(&u)
        let s = withUnsafePointer(to: &u.machine) { p in
            p.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) { String(cString: $0) }
        }
        return s.isEmpty ? "iPhone" : s
    }()
}
