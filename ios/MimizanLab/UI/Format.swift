import Foundation
import MimizanCore

enum Format {
    static func shutter(_ ns: Int64?) -> String {
        guard let ns, ns > 0 else { return "—" }
        let s = Double(ns) / 1e9
        if s >= 0.3 { return String(format: "%.1f s", s) }
        return "1/\(Int((1.0 / s).rounded()))"
    }

    static func shutter(seconds: Double) -> String {
        shutter(seconds > 0 ? Int64(seconds * 1e9) : nil)
    }

    static func iso(_ iso: Int?) -> String { iso.map { "ISO \($0)" } ?? "—" }

    static func bytes(_ n: Int64) -> String {
        switch n {
        case (1 << 30)...: return String(format: "%.2f GB", Double(n) / (1024.0 * 1024 * 1024))
        case (1 << 20)...: return String(format: "%.0f MB", Double(n) / (1024.0 * 1024))
        case (1 << 10)...: return String(format: "%.0f kB", Double(n) / 1024.0)
        default: return "\(n) B"
        }
    }

    static func ev(_ v: Double) -> String {
        v == 0 ? "±0" : String(format: "%+.1f", v)
    }

    static func stops(_ v: Double) -> String { String(format: "%+.2f", v) }

    static func filterShort(_ f: Filter) -> String {
        switch f {
        case .none: return "Pan"
        case .yellow8: return "Y8"
        case .yellowGreen11: return "YG11"
        case .orange16: return "O16"
        case .red25: return "R25"
        case .green58: return "G58"
        case .blue47: return "B47"
        }
    }

    static func plateFilters(_ f: PlateFilters) -> String {
        switch f {
        case .camera: return "Camera"
        case .wratten: return "Wratten"
        case .channels: return "Channels"
        }
    }

    static func plateSpace(_ s: PlateSpace) -> String {
        switch s {
        case .srgb: return "sRGB"
        case .adobeRgb: return "Adobe RGB"
        }
    }

    static func separation(_ m: SeparationMode) -> String {
        switch m {
        case .dubois: return "Dubois"
        case .adaptive: return "Adaptive"
        case .off: return "Mask off"
        }
    }

    /// A weight triple as the Enlarger shows it, signed (the camera set goes negative).
    static func weights(_ w: Weights) -> String { String(format: "%+.2f %+.2f %+.2f", w.r, w.g, w.b) }

    /// Log-spaced shutter slider: 0..1 -> [lo, hi] seconds.
    static func sliderToSeconds(_ t: Double, lo: Double, hi: Double) -> Double {
        let a = log(lo), b = log(hi)
        return min(max(exp(a + (b - a) * t), lo), hi)
    }

    static func secondsToSlider(_ s: Double, lo: Double, hi: Double) -> Double {
        let a = log(lo), b = log(hi)
        return min(max((log(s) - a) / (b - a), 0), 1)
    }

    /// Third-stop ISO steps read better than raw numbers.
    static func sliderToIso(_ t: Double, lo: Double, hi: Double) -> Double {
        let a = log(lo), b = log(hi)
        let v = exp(a + (b - a) * t)
        let stops = (log(v / lo) / log(2.0) * 3).rounded() / 3
        return min(max((lo * pow(2.0, stops)).rounded(), lo), hi)
    }

    static func isoToSlider(_ iso: Double, lo: Double, hi: Double) -> Double {
        let a = log(lo), b = log(hi)
        return min(max((log(iso) - a) / (b - a), 0), 1)
    }

    static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd_HHmmss"
        return f
    }()

    static func prettyJson(_ s: String) -> String {
        guard let data = s.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: pretty, encoding: .utf8)
        else { return s }
        return text
    }
}
