// The few settings that survive a restart. Same keys and defaults as the
// Android app's `Prefs.kt`.
import Foundation
import MimizanCore
import Observation

/// The darkroom's two processes: the monochrome negative, or the
/// three-colour plate (Prokudin-Gorsky's principle: three mixes of the
/// same separation, printed through the same paper into one RGB file).
enum Process: String, CaseIterable, Codable {
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
enum PeakMode: String, CaseIterable {
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
}

@MainActor
@Observable
final class Prefs {
    private let store = UserDefaults.standard

    /// Soft-pass time in stops from the base exposure. The developer
    /// opens here, and the automatic JPEG is printed here.
    private(set) var soft: Double
    /// Hard-pass time in stops from the base exposure.
    private(set) var hard: Double
    /// Peak's high-pass on the automatic JPEG and the full-size exports.
    /// Auto measures each negative. Custom keeps a hand pair. Auto on a fresh install.
    private(set) var peakMode: PeakMode
    /// Custom Peak radius, pixels of the negative.
    private(set) var peakRadius: Double
    /// Custom Peak amount.
    private(set) var peakAmount: Double
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
    /// Overexposure marks on the finder. Off for a fresh install.
    private(set) var zebra: Bool
    /// The rule of thirds on the finder. Off for a fresh install.
    private(set) var grid: Bool
    /// The finder's histogram. Off for a fresh install.
    private(set) var histogram: Bool
    /// On, a picture places its middle on the grey card. The grade stays.
    /// Off for a fresh install.
    private(set) var zoneAuto: Bool
    /// Extra paper time on a balanced picture, in stops. Plus prints darker.
    /// Zero for a fresh install.
    private(set) var zoneBias: Double

    init() {
        soft = Prefs.snapStops(store.double(forKey: Keys.soft))
        hard = Prefs.snapStops(store.double(forKey: Keys.hard))
        if let raw = store.string(forKey: Keys.peakMode), let mode = PeakMode(rawValue: raw) {
            peakMode = mode
        } else if store.object(forKey: Keys.peak) != nil {
            // A bool from before the three states: on was the measurement, off was none.
            peakMode = store.bool(forKey: Keys.peak) ? .auto : .off
        } else {
            peakMode = .auto
        }
        peakRadius = Prefs.clampPeakRadius(store.object(forKey: Keys.peakRadius) == nil ? Prefs.peakLimits.radiusDefault : store.double(forKey: Keys.peakRadius))
        peakAmount = Prefs.clampPeakAmount(store.object(forKey: Keys.peakAmount) == nil ? 0 : store.double(forKey: Keys.peakAmount))
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
        zebra = store.bool(forKey: Keys.zebra)
        grid = store.bool(forKey: Keys.grid)
        histogram = store.bool(forKey: Keys.histogram)
        zoneAuto = store.bool(forKey: Keys.zoneAuto)
        zoneBias = Prefs.snapZoneBias(store.object(forKey: Keys.zoneBias) == nil ? 0 : store.double(forKey: Keys.zoneBias))
    }

    func setSoft(_ stops: Double) {
        soft = Prefs.snapStops(stops)
        store.set(soft, forKey: Keys.soft)
    }

    func setHard(_ stops: Double) {
        hard = Prefs.snapStops(stops)
        store.set(hard, forKey: Keys.hard)
    }

    func setPeakMode(_ mode: PeakMode) {
        peakMode = mode
        store.set(mode.rawValue, forKey: Keys.peakMode)
    }

    func setPeak(radius: Double, amount: Double) {
        peakRadius = Prefs.clampPeakRadius(radius)
        peakAmount = Prefs.clampPeakAmount(amount)
        store.set(peakRadius, forKey: Keys.peakRadius)
        store.set(peakAmount, forKey: Keys.peakAmount)
    }

    /// Radius and amount a print uses. Off applies nothing. Auto uses the measurement.
    nonisolated static func peakApplied(mode: PeakMode, radius: Double, amount: Double, measured: PeakMeasured?) -> (radius: Double, amount: Double) {
        switch mode {
        case .off: return (radius, 0)
        case .auto: return (measured?.radius ?? peakLimits.radiusDefault, measured?.amount ?? 0)
        case .custom: return (radius, amount)
        }
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

    func setZoneAuto(_ on: Bool) {
        zoneAuto = on
        store.set(on, forKey: Keys.zoneAuto)
    }

    func setZoneBias(_ stops: Double) {
        zoneBias = Prefs.snapZoneBias(stops)
        store.set(zoneBias, forKey: Keys.zoneBias)
    }

    /// Add `delta` stops to both exposures, snapped to a twelfth, and stop
    /// at the limit so the gap between them stays.
    nonisolated static func shiftGrade(soft: Double, hard: Double, by delta: Double) -> (Double, Double) {
        let lo = -stopLimit - min(soft, hard)
        let hi = stopLimit - max(soft, hard)
        let d = min(max((delta * 12).rounded() / 12, lo), hi)
        return (soft + d, hard + d)
    }

    // MARK: limits, asked of the core once

    /// How far either paper exposure may move, in stops: the core's `paper::STOP_LIMIT`.
    nonisolated static let stopLimit: Double = paperStopLimit()
    /// How far either lamp may move, in stops: the core's `print::LAMP_LIMIT`.
    nonisolated static let lampLimit: Double = MimizanCore.lampLimit()
    /// The range of the peak fields: the core's `recipe::PEAK_*`.
    nonisolated static let peakLimits: PeakLimits = MimizanCore.peakLimits()

    /// How far the balance may add paper time, in stops. The grade stays inside `stopLimit`.
    nonisolated static let zoneBiasLimit: Double = 2

    /// Twelfths of a stop, inside ±`zoneBiasLimit`.
    nonisolated static func snapZoneBias(_ v: Double) -> Double {
        min(max((finiteOrZero(v) * 12).rounded() / 12, -zoneBiasLimit), zoneBiasLimit)
    }
    /// Twelfths of a stop, inside ±`stopLimit`; a non-finite value snaps to zero.
    nonisolated static func snapStops(_ v: Double) -> Double {
        min(max((finiteOrZero(v) * 12).rounded() / 12, -stopLimit), stopLimit)
    }

    /// Twelfths of a stop, inside ±`lampLimit`.
    nonisolated static func snapLamp(_ v: Double) -> Double {
        min(max((finiteOrZero(v) * 12).rounded() / 12, -lampLimit), lampLimit)
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
        static let peakRadius = "peak_radius"
        static let peakAmount = "peak_amount"
        static let process = "process"
        static let plateFilters = "plate_filters"
        static let plateSpace = "plate_space"
        static let stayOnFinder = "stay_on_finder"
        static let shotMode = "shot_mode"
        static let zebra = "finder_zebra"
        static let grid = "finder_grid"
        static let histogram = "finder_histogram"
        static let zoneAuto = "zone_auto"
        static let zoneBias = "zone_bias"
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

extension SeparationMode {
    static let all: [SeparationMode] = [.dubois, .adaptive, .off]

    var name: String {
        switch self {
        case .dubois: return "DUBOIS"
        case .adaptive: return "ADAPTIVE"
        case .off: return "OFF"
        }
    }

    static func fromName(_ s: String?) -> SeparationMode? {
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
