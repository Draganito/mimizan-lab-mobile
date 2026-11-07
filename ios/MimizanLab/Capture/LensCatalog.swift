// The back lenses as single physical devices: the virtual (fusion) cameras
// never give Bayer RAW, so each sensor is opened on its own, the way the
// Android app opens one physical camera behind the logical one. Whether a
// lens really delivers RAW is only known once a session is configured on
// it (`CaptureSession.start` refuses one that does not).
import AVFoundation
import Foundation

struct LensInfo: Identifiable, Equatable {
    let device: AVCaptureDevice
    let label: String
    /// 35 mm equivalent focal length, from the horizontal field of view
    /// (a 36 mm wide frame), rounded.
    let equivalentMm: Int

    var id: String { device.uniqueID }

    var hasFlash: Bool { device.hasFlash }
    var hasManualExposure: Bool { device.isExposureModeSupported(.custom) }
    /// The active format's ranges, before the session has said which
    /// format the still is taken in (`CaptureState.isoRange`).
    var isoRange: ClosedRange<Double> { LensInfo.ranges(of: device.activeFormat).iso }
    var exposureRange: ClosedRange<Double> { LensInfo.ranges(of: device.activeFormat).exposure }

    /// ISO and time a format takes by hand: the time capped at 4 s, and
    /// each range at least two values wide so a slider has somewhere to go.
    static func ranges(of f: AVCaptureDevice.Format) -> (iso: ClosedRange<Double>, exposure: ClosedRange<Double>) {
        let iso = Double(f.minISO)...Double(max(f.maxISO, f.minISO + 1))
        let lo = max(f.minExposureDuration.seconds, 1.0 / 100_000)
        let hi = max(min(f.maxExposureDuration.seconds, 4.0), lo * 2)
        return (iso, lo...hi)
    }

    static func == (a: LensInfo, b: LensInfo) -> Bool { a.device.uniqueID == b.device.uniqueID }
}

enum LensCatalog {
    /// Main first, then by equivalent focal length.
    static func enumerate() -> [LensInfo] {
        let session = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInUltraWideCamera, .builtInTelephotoCamera],
            mediaType: .video,
            position: .back
        )
        var out: [LensInfo] = []
        for d in session.devices {
            let label: String
            switch d.deviceType {
            case .builtInWideAngleCamera: label = "Main"
            case .builtInUltraWideCamera: label = "Ultra wide"
            case .builtInTelephotoCamera: label = "Tele"
            default: label = d.localizedName
            }
            let fov = Double(d.activeFormat.videoFieldOfView)
            let mm = fov > 1 ? Int((36.0 / (2 * tan(fov * .pi / 360))).rounded()) : 0
            out.append(LensInfo(device: d, label: label, equivalentMm: mm))
        }
        return out.sorted { a, b in
            if (a.label == "Main") != (b.label == "Main") { return a.label == "Main" }
            return a.equivalentMm < b.equivalentMm
        }
    }
}
