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

@MainActor
@Observable
final class Prefs {
    private let store = UserDefaults.standard

    /// Soft-pass time in stops from the base exposure, for the viewfinder,
    /// the automatic JPEG and the start of review.
    private(set) var soft: Double
    /// Hard-pass time in stops from the base exposure.
    private(set) var hard: Double
    /// Peak's high-pass on the automatic JPEG and the full-size exports:
    /// the radius and the amount are measured on each negative; this is
    /// only the checkbox. On for a fresh install.
    private(set) var peak: Bool
    /// The darkroom's process; the next shot opens in it.
    private(set) var process: Process
    /// The plate's taking filters.
    private(set) var plateFilters: PlateFilters
    /// The plate's projection primaries, the colour space of the file.
    /// sRGB on the phone: Photos shows it as it is.
    private(set) var plateSpace: PlateSpace

    init() {
        soft = Prefs.snapStops(store.double(forKey: Keys.soft))
        hard = Prefs.snapStops(store.double(forKey: Keys.hard))
        peak = store.object(forKey: Keys.peak) == nil ? true : store.bool(forKey: Keys.peak)
        process = Process(rawValue: store.string(forKey: Keys.process) ?? "") ?? .mono
        plateFilters = PlateFilters.fromName(store.string(forKey: Keys.plateFilters)) ?? .camera
        plateSpace = PlateSpace.fromName(store.string(forKey: Keys.plateSpace)) ?? .srgb
    }

    func setSoft(_ stops: Double) {
        soft = Prefs.snapStops(stops)
        store.set(soft, forKey: Keys.soft)
    }

    func setHard(_ stops: Double) {
        hard = Prefs.snapStops(stops)
        store.set(hard, forKey: Keys.hard)
    }

    func setPeak(_ on: Bool) {
        peak = on
        store.set(on, forKey: Keys.peak)
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

    // MARK: limits, asked of the core once

    /// How far either paper exposure may move, in stops: the core's `paper::STOP_LIMIT`.
    nonisolated static let stopLimit: Double = paperStopLimit()
    /// How far either lamp may move, in stops: the core's `print::LAMP_LIMIT`.
    nonisolated static let lampLimit: Double = MimizanCore.lampLimit()
    /// The range of the peak fields: the core's `recipe::PEAK_*`.
    nonisolated static let peakLimits: PeakLimits = MimizanCore.peakLimits()

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
        static let process = "process"
        static let plateFilters = "plate_filters"
        static let plateSpace = "plate_space"
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
