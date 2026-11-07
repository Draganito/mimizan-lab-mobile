// The few settings that survive a restart. Same keys and defaults as the
// Android app's `Prefs.kt`.
import Foundation
import MimizanCore
import Observation

/// The darkroom's two processes: the monochrome negative, or the
/// three-colour plate (Prokudin-Gorsky's principle: three mixes of the
/// same separation, printed through the same paper into one RGB file).
enum Process: String {
    case mono = "MONO"
    case plate = "PLATE"
}

/// What a shot does. JPEG writes the picture and stays. Developer opens it. Raw keeps the frame.
enum ShotMode: String, CaseIterable {
    case jpeg = "JPEG"
    case developer = "DEVELOPER"
    case raw = "RAW"

    var label: String {
        switch self {
        case .jpeg: return "JPEG"
        case .developer: return "Developer"
        case .raw: return "Raw"
        }
    }
}

/// Peak on a print: off, the measurement of this negative, or a hand pair.
enum PeakMode: String, CaseIterable, Codable {
    case off = "OFF"
    case auto = "AUTO"
    case custom = "CUSTOM"

    var label: String {
        switch self {
        case .off: return "Off"
        case .auto: return "Auto"
        case .custom: return "Custom"
        }
    }

    /// The modes a standing Peak can be: a hand pair belongs to one negative, so Custom is the developer's alone.
    static let standing: [PeakMode] = [.off, .auto]
}

/// The paper's two exposures, in stops from the base print. Same file
/// shape as the Android `Grade`.
struct Grade: Equatable, Sendable {
    var soft: Double
    var hard: Double

    static let base = Grade(soft: 0, hard: 0)
}

/// Peak on a print. Off applies nothing. Auto is the measurement of the
/// negative it is applied to. Custom is `radius` and `amount` by hand;
/// the two are carried for the other modes too, so a return to Custom
/// finds the pair again. Same file shape as the Android `Peak`.
struct Peak: Equatable, Sendable {
    var mode: PeakMode
    var radius: Double
    var amount: Double

    /// The standing Peak of `mode`, with the pair a Custom print starts from when nothing was measured.
    static func standing(_ mode: PeakMode) -> Peak {
        Peak(mode: mode, radius: Prefs.peakLimits.radiusDefault, amount: 0)
    }

    /// Radius and amount the print uses. Auto without a measurement applies nothing.
    func applied(_ measured: PeakMeasured?) -> (radius: Double, amount: Double) {
        switch mode {
        case .off: return (radius, 0)
        case .auto: return (measured?.radius ?? radius, measured?.amount ?? 0)
        case .custom: return (radius, amount)
        }
    }
}

extension Peak: Codable {
    /// A missing or unknown field falls back to Auto, the default radius, no amount; the pair is clamped.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mode = (try? c.decode(PeakMode.self, forKey: .mode)) ?? .auto
        radius = Prefs.clampPeakRadius((try? c.decode(Double.self, forKey: .radius)) ?? Prefs.peakLimits.radiusDefault)
        amount = Prefs.clampPeakAmount((try? c.decode(Double.self, forKey: .amount)) ?? 0)
    }
}

/// What one frame is printed with: the grade and Peak, the recipe beside
/// the RAW. A new frame takes the standing pair of the moment; the
/// developer moves this one alone. Written to `<name>.grade` by
/// `RawCache` together with the frame's process and filter.
struct Print: Equatable, Sendable {
    var grade: Grade
    var peak: Peak
}

@MainActor
@Observable
final class Prefs {
    private let store = UserDefaults.standard

    /// The standing grade: what the automatic JPEG prints and where a new
    /// frame starts. A frame's own grade, moved in the developer, lives
    /// with the frame (`RawCache`), not here.
    var grade: Grade { Grade(soft: soft, hard: hard) }
    /// Soft-pass time in stops from the base exposure.
    private(set) var soft: Double
    /// Hard-pass time in stops from the base exposure.
    private(set) var hard: Double
    /// The standing Peak, Off or Auto: what a new frame starts with and
    /// the automatic JPEG applies. Auto measures each negative. A hand
    /// pair is set in the developer and lives with the frame. Auto on a
    /// fresh install.
    private(set) var peakMode: PeakMode
    /// What a frame taken now is printed with: the standing grade and the standing Peak.
    var standingPrint: Print { Print(grade: grade, peak: .standing(peakMode)) }
    /// The darkroom's process; the next shot opens in it.
    private(set) var process: Process
    /// The plate's taking filters.
    private(set) var plateFilters: PlateFilters
    /// The plate's projection primaries, the colour space of the file.
    /// sRGB on the phone: Photos shows it as it is.
    private(set) var plateSpace: PlateSpace
    /// What the shutter does. Developer on a fresh install: the finder is
    /// the camera's picture, so a shot opens to be graded.
    private(set) var shotMode: ShotMode
    /// Overexposure marks on the finder. On for a fresh install: the marks
    /// are the one sign of lost highlights before the shot.
    private(set) var zebra: Bool
    /// The rule of thirds on the finder. Off for a fresh install.
    private(set) var grid: Bool
    /// The finder's histogram. Off for a fresh install.
    private(set) var histogram: Bool
    /// The paper finder's axis against the negative's, as measured on the
    /// frames taken so far, lens by lens (Apple Log cameras only).
    private(set) var finderMatch: FinderMatches

    init() {
        soft = Prefs.snapStops(store.double(forKey: Keys.soft))
        hard = Prefs.snapStops(store.double(forKey: Keys.hard))
        if let raw = store.string(forKey: Keys.peakMode), let mode = PeakMode(rawValue: raw), PeakMode.standing.contains(mode) {
            peakMode = mode
        } else if store.string(forKey: Keys.peakMode) == nil, store.object(forKey: Keys.peak) != nil {
            // A bool from before the three states: on was the measurement, off was none.
            peakMode = store.bool(forKey: Keys.peak) ? .auto : .off
        } else {
            // A fresh install, or a standing Custom from before 0.15.1: the hand pair belongs to a frame now.
            peakMode = .auto
        }
        process = Process(rawValue: store.string(forKey: Keys.process) ?? "") ?? .mono
        plateFilters = PlateFilters.fromName(store.string(forKey: Keys.plateFilters)) ?? .camera
        plateSpace = PlateSpace.fromName(store.string(forKey: Keys.plateSpace)) ?? .srgb
        if let raw = store.string(forKey: Keys.shotMode), let mode = ShotMode(rawValue: raw) {
            shotMode = mode
        } else if store.object(forKey: Keys.stayOnFinder) != nil {
            // The old switch: on was JPEG, off was Developer.
            shotMode = store.bool(forKey: Keys.stayOnFinder) ? .jpeg : .developer
        } else {
            shotMode = .developer
        }
        zebra = store.object(forKey: Keys.zebra) == nil ? true : store.bool(forKey: Keys.zebra)
        grid = store.bool(forKey: Keys.grid)
        histogram = store.bool(forKey: Keys.histogram)
        var byLens: [String: FinderMatch] = [:]
        if let table = store.dictionary(forKey: Keys.finderMatchByLens) {
            for (lens, v) in table {
                if let samples = v as? [Double] { byLens[lens] = FinderMatch(samples: samples) }
            }
        } else if let legacy = store.array(forKey: Keys.finderMatch) as? [Double], !legacy.isEmpty {
            // One table for every lens before 0.17.19: those frames were
            // the main camera's for the most part; it keeps them.
            byLens[Prefs.legacyMatchLens] = FinderMatch(samples: legacy)
        }
        finderMatch = FinderMatches(byLens: byLens)
    }

    /// The lens the one table of before 0.17.19 is handed to.
    static let legacyMatchLens = "Main"

    /// One more frame measured on `lens`: the finder follows from the next tone on.
    func addFinderMatch(_ stops: Double, lens: String) {
        guard stops.isFinite else { return }
        finderMatch.add(stops, lens: lens)
        storeFinderMatch()
    }

    /// Back to the published curve's own white, as on a fresh install.
    func resetFinderMatch() {
        finderMatch = FinderMatches()
        storeFinderMatch()
        store.removeObject(forKey: Keys.finderMatch)
    }

    private func storeFinderMatch() {
        var table: [String: [Double]] = [:]
        for lens in finderMatch.lenses { table[lens] = finderMatch.byLens[lens]?.samples ?? [] }
        store.set(table, forKey: Keys.finderMatchByLens)
    }

    /// The sensor's XYZ → camera matrix as the last DNG of this lens
    /// carried it, remembered so the first finder frame has it.
    func xyzToCam(lens: String) -> [Double]? {
        store.array(forKey: Keys.xyzToCam + lens) as? [Double]
    }

    /// Counts the matrices that changed, so the finder's tone can follow
    /// a lens's first DNG without watching the roll.
    private(set) var matrixVersion = 0

    func setXyzToCam(_ m: [Double], lens: String) {
        guard xyzToCam(lens: lens) != m else { return }
        store.set(m, forKey: Keys.xyzToCam + lens)
        matrixVersion += 1
    }

    /// The standing pair, each snapped and inside the limit.
    func setGrade(soft s: Double, hard h: Double) {
        let g = Prefs.snapGrade(soft: s, hard: h)
        soft = g.soft
        hard = g.hard
        store.set(soft, forKey: Keys.soft)
        store.set(hard, forKey: Keys.hard)
    }

    /// The standing grade as one number; the exposure (the strip) stays.
    func setContrast(_ stops: Double) {
        let g = Prefs.withContrast(soft: soft, hard: hard, contrast: stops)
        setGrade(soft: g.soft, hard: g.hard)
    }

    /// The standing Peak, Off or Auto.
    func setPeakMode(_ mode: PeakMode) {
        // A hand pair belongs to one negative: Custom is never standing.
        guard PeakMode.standing.contains(mode) else { return }
        peakMode = mode
        store.set(mode.rawValue, forKey: Keys.peakMode)
    }

    /// A hand pair inside the core's ranges.
    nonisolated static func customPeak(radius: Double, amount: Double) -> Peak {
        Peak(mode: .custom, radius: clampPeakRadius(radius), amount: clampPeakAmount(amount))
    }

    func setProcess(_ p: Process) {
        process = p
        store.set(p.rawValue, forKey: Keys.process)
    }

    func setPlateFilters(_ f: PlateFilters) {
        plateFilters = f
        store.set(f.name, forKey: Keys.plateFilters)
    }

    func setPlateSpace(_ s: PlateSpace) {
        plateSpace = s
        store.set(s.name, forKey: Keys.plateSpace)
    }

    func setShotMode(_ mode: ShotMode) {
        shotMode = mode
        store.set(mode.rawValue, forKey: Keys.shotMode)
    }

    func setZebra(_ on: Bool) {
        zebra = on
        store.set(on, forKey: Keys.zebra)
    }

    func setGrid(_ on: Bool) {
        grid = on
        store.set(on, forKey: Keys.grid)
    }

    func setHistogram(_ on: Bool) {
        histogram = on
        store.set(on, forKey: Keys.histogram)
    }

    /// Whether the one-time note after the first JPEG shot (the frame
    /// stays on the roll and can be printed again) has been shown.
    var rollHintShown: Bool { store.bool(forKey: Keys.rollHint) }

    func markRollHintShown() { store.set(true, forKey: Keys.rollHint) }

    /// Add `delta` stops to both exposures, snapped to a twelfth, and stop
    /// at the limit so the gap between them stays.
    nonisolated static func shiftGrade(soft: Double, hard: Double, by delta: Double) -> (Double, Double) {
        let lo = -stopLimit - min(soft, hard)
        let hi = stopLimit - max(soft, hard)
        let d = min(max((finiteOrZero(delta) * 12).rounded() / 12, lo), hi)
        return (soft + d, hard + d)
    }

    /// A grade, each exposure snapped and inside ±`stopLimit`.
    nonisolated static func snapGrade(soft: Double, hard: Double) -> Grade {
        Grade(soft: snapStops(soft), hard: snapStops(hard))
    }

    /// The grade as one number: hard light against soft, in stops. Zero is the paper's grade 2.
    nonisolated static func contrast(soft: Double, hard: Double) -> Double { hard - soft }

    /// The pair with `contrast` stops between hard and soft and the same
    /// exposure (the mean of the two, what the zone strip moves). Done in
    /// twenty-fourths so both land on the twelfth grid: when the mean sits
    /// between two twelfths the pair goes a twenty-fourth lighter, once.
    /// `contrast` is clamped so neither exposure leaves ±`stopLimit`. The
    /// pair is the same two numbers the two sliders would have set:
    /// nothing downstream changes. Twin of the Android `GradeMath.withContrast`.
    nonisolated static func withContrast(soft: Double, hard: Double, contrast: Double) -> Grade {
        let m24 = Int(((snapStops(soft) + snapStops(hard)) * 12).rounded())
        let lim24 = Int((stopLimit * 24).rounded())
        let room = lim24 - abs(m24)
        let wanted = min(max(finiteOrZero(contrast), -2 * stopLimit), 2 * stopLimit)
        let c12 = min(max(Int((wanted * 12).rounded()), -room), room)
        var s24 = m24 - c12
        if s24 & 1 != 0 { s24 -= 1 }
        if s24 < -lim24 { s24 += 2 }
        return Grade(soft: Double(s24) / 24, hard: Double(s24 + 2 * c12) / 24)
    }

    // MARK: limits, asked of the core once

    /// How far either paper exposure may move, in stops: the core's `paper::STOP_LIMIT`.
    nonisolated static let stopLimit: Double = paperStopLimit()
    /// The range of the peak fields: the core's `recipe::PEAK_*`.
    nonisolated static let peakLimits: PeakLimits = MimizanCore.peakLimits()

    /// Twelfths of a stop, inside ±`stopLimit`; a non-finite value snaps to zero.
    nonisolated static func snapStops(_ v: Double) -> Double {
        min(max((finiteOrZero(v) * 12).rounded() / 12, -stopLimit), stopLimit)
    }

    /// A peak radius inside the core's range; a non-finite value is the default.
    nonisolated static func clampPeakRadius(_ v: Double) -> Double {
        let r = v.isFinite ? v : peakLimits.radiusDefault
        return min(max(r, peakLimits.radiusMin), peakLimits.radiusMax)
    }

    /// A peak amount inside the core's range; a non-finite value is zero.
    nonisolated static func clampPeakAmount(_ v: Double) -> Double {
        min(max(finiteOrZero(v), 0), peakLimits.amountMax)
    }

    nonisolated private static func finiteOrZero(_ v: Double) -> Double { v.isFinite ? v : 0 }

    private enum Keys {
        static let soft = "paper_soft"
        static let hard = "paper_hard"
        static let peak = "peak_on"
        static let peakMode = "peak_mode"
        static let process = "process"
        static let plateFilters = "plate_filters"
        static let plateSpace = "plate_space"
        static let stayOnFinder = "stay_on_finder"
        static let shotMode = "shot_mode"
        static let zebra = "finder_zebra"
        static let grid = "finder_grid"
        static let histogram = "finder_histogram"
        static let rollHint = "hint_roll_shown"
        static let finderMatch = "finder_match_stops"
        static let finderMatchByLens = "finder_match_stops_by_lens"
        static let xyzToCam = "xyz_to_cam_"
    }
}

// Stable names for the stored enums (the Android app's enum names).
extension PlateFilters {
    var name: String {
        switch self {
        case .camera: return "CAMERA"
        case .wratten: return "WRATTEN"
        case .channels: return "CHANNELS"
        }
    }

    static func fromName(_ s: String?) -> PlateFilters? {
        switch s {
        case "CAMERA": return .camera
        case "WRATTEN": return .wratten
        case "CHANNELS": return .channels
        default: return nil
        }
    }

    static let all: [PlateFilters] = [.camera, .wratten, .channels]
}

extension PlateSpace {
    var name: String {
        switch self {
        case .srgb: return "SRGB"
        case .adobeRgb: return "ADOBE_RGB"
        }
    }

    static func fromName(_ s: String?) -> PlateSpace? {
        switch s {
        case "SRGB": return .srgb
        case "ADOBE_RGB": return .adobeRgb
        default: return nil
        }
    }

    static let all: [PlateSpace] = [.srgb, .adobeRgb]
}

extension Filter {
    static let all: [Filter] = [.none, .yellow8, .yellowGreen11, .orange16, .red25, .green58, .blue47]

    var name: String {
        switch self {
        case .none: return "NONE"
        case .yellow8: return "YELLOW8"
        case .yellowGreen11: return "YELLOW_GREEN11"
        case .orange16: return "ORANGE16"
        case .red25: return "RED25"
        case .green58: return "GREEN58"
        case .blue47: return "BLUE47"
        }
    }

    static func fromName(_ s: String?) -> Filter? {
        all.first { $0.name == s }
    }
}

extension BayerPattern {
    var name: String {
        switch self {
        case .rggb: return "RGGB"
        case .grbg: return "GRBG"
        case .gbrg: return "GBRG"
        case .bggr: return "BGGR"
        }
    }

    static func fromName(_ s: String?) -> BayerPattern? {
        switch s {
        case "RGGB": return .rggb
        case "GRBG": return .grbg
        case "GBRG": return .gbrg
        case "BGGR": return .bggr
        default: return nil
        }
    }
}
