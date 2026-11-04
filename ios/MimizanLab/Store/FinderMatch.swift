// The paper finder's exposure against the negative's. The Apple Log frame
// is scene-linear with the grey card at 0.18 of the ISP's exposure; the
// negative is linear with 1 at the sensor's clip. The stops between the
// two are not published, so the app measures them: on every frame taken
// with the paper finder, the median of the finder's linear mix at the
// shutter against the median of the developed negative's mix (the core's
// `mixMedian`, the same cut of near-black and clip). Each measurement
// goes to `Documents/finder-match.json` and to the log; the median of
// the last eight is what the finder applies from the next tone on.
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

    /// Append one record (the file is a JSON array, read whole: a few
    /// hundred lines at most). Call off the main actor.
    static func append(_ r: FinderMatchRecord) {
        do {
            var records = (try? JSONDecoder().decode([FinderMatchRecord].self, from: Data(contentsOf: url))) ?? []
            records.append(r)
            let enc = JSONEncoder()
            enc.outputFormatting = [.prettyPrinted, .sortedKeys]
            try enc.encode(records).write(to: url, options: .atomic)
        } catch {
            log.warning("finder-match.json: \(error.localizedDescription)")
        }
        log.info("finder match \(r.frame): finder \(r.finderMedian) negative \(r.negativeMedian) → \(r.stops) stops (applied \(r.applied), now \(r.match)) on \(r.format), \(r.lens), ISO \(r.iso ?? 0), \(r.exposureNs ?? 0) ns")
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
