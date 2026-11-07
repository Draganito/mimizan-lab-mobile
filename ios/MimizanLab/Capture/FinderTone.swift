// What the finder kernel and the histogram do to a camera frame.
//
// On a camera with Apple Log the finder is the paper, as on Android:
//
//   rgb   = decode(Apple Log frame)      — scene-linear BT.2020, the ISP's
//                                           white balance applied, no tone map
//   gray  = gain · v · rgb               — `v` is the still's mix weights
//                                           pulled back from the balanced
//                                           camera channels to BT.2020;
//                                           `gain` puts the Log axis on the
//                                           negative's (1 = the sensor clip)
//   value = lut[gray]                    — the paper's display value of that
//                                           linear grey
//
// Android inverts the ISP's exact matrix; AVFoundation exposes none, so
// the pull-back goes through the sensor's XYZ → camera matrix (from the
// last DNG of this lens) and the BT.2020 primaries, white kept at white.
// The still is developed from the DNG; the finder is the finder.
//
// Without Apple Log the finder is the camera's own SDR picture,
// desaturated in monochrome; nothing of the paper reaches it.
import CoreVideo
import Foundation
import simd

struct FinderTone: Equatable, Sendable {
    /// The camera's picture in grey (no paper), or the colour frame itself.
    var mono: Bool
    /// Display value at or above which zebra marks; > 1 means off.
    var zebra: Float
    /// The paper on an Apple Log frame; nil on the camera's picture.
    var paper: PaperTone?
}

/// The negative's mix and the paper, for an Apple Log frame.
struct PaperTone: Equatable, Sendable {
    /// Weights on linear BT.2020 for the mix.
    var v: SIMD3<Float>
    /// In the plate process the three taking filters pulled back the
    /// same way (rows R, G, B); nil for the monochrome negative.
    var plate: (SIMD3<Float>, SIMD3<Float>, SIMD3<Float>)?
    /// Log-linear → the negative's axis: `2^FinderMatch.stops`.
    var gain: Float
    /// The paper over the linear axis, `n + 1` display values.
    var lut: [Float]

    static func == (a: PaperTone, b: PaperTone) -> Bool {
        a.v == b.v && a.gain == b.gain && a.lut == b.lut
            && a.plate?.0 == b.plate?.0 && a.plate?.1 == b.plate?.1 && a.plate?.2 == b.plate?.2
    }

    /// Display value of the linear grey `g`, for the histogram's inner loop.
    func display(of g: Float) -> Float {
        let x = min(max(g, 0), 1) * Float(lut.count - 1)
        let i = min(Int(x), lut.count - 2)
        let f = x - Float(i)
        return lut[i] + (lut[i + 1] - lut[i]) * f
    }

    /// 1024 display values over linear grey, for the histogram.
    func grayTable() -> [Float] {
        (0..<1024).map { display(of: Float($0) / 1023) }
    }
}

/// Apple's log transfer function, as published (Apple Log Profile White
/// Paper). `linear` undoes it: an encoded value becomes scene-linear
/// reflectance, 0.18 the grey card at the ISP's exposure, about 12 at
/// code 1. The kernel in `Finder.metal` carries the same constants.
enum AppleLog {
    static let r0: Float = -0.05641088
    static let rt: Float = 0.01
    static let c: Float = 47.28711236
    static let beta: Float = 0.00964052
    static let gamma: Float = 0.08550479
    static let delta: Float = 0.69336945
    /// The encoded value at `rt`, where the parabola hands over to the log.
    static let pt: Float = c * (rt - r0) * (rt - r0)

    static func linear(_ y: Float) -> Float {
        if y >= pt { return exp2((y - delta) / gamma) - beta }
        if y > 0 { return (y / c).squareRoot() + r0 }
        return r0
    }
}

/// Video-range Y'CbCr → R'G'B' for a frame, by the matrix its buffer is
/// tagged with (BT.2020 for Apple Log; BT.709 if a device says so). The
/// kernel and the histogram expand the range first: Y' from 64/1023,
/// Cb and Cr about 512/1023, both over their 876 and 896 codes.
enum YccMatrix {
    static let bt2020 = simd_float3x3(rows: [
        SIMD3<Float>(1, 0, 1.4746),
        SIMD3<Float>(1, -0.16455, -0.57135),
        SIMD3<Float>(1, 1.8814, 0),
    ])
    static let bt709 = simd_float3x3(rows: [
        SIMD3<Float>(1, 0, 1.5748),
        SIMD3<Float>(1, -0.18732, -0.46812),
        SIMD3<Float>(1, 1.8556, 0),
    ])

    static func of(_ frame: CVPixelBuffer) -> simd_float3x3 {
        guard let m = CVBufferCopyAttachment(frame, kCVImageBufferYCbCrMatrixKey, nil) else { return bt2020 }
        return CFEqual(m, kCVImageBufferYCbCrMatrix_ITU_R_709_2) ? bt709 : bt2020
    }
}

/// Linear BT.2020 → balanced camera channels, white to white. Identity
/// when the lens has not shown a matrix yet.
struct Rec2020ToCamera: Equatable {
    /// Rows R, G, B of the balanced camera signal over linear BT.2020.
    var m: simd_float3x3

    static let identity = Rec2020ToCamera(m: matrix_identity_float3x3)

    /// From the sensor's XYZ → camera matrix (9 values row-major, D65).
    /// `cam = xyzToCam · XYZ_from_2020 · rgb`; each row is then scaled so
    /// a grey (1, 1, 1) gives (1, 1, 1) in the balanced channels, which
    /// is what the white balance before the separation does.
    init?(xyzToCam: [Double]?) {
        guard let c = xyzToCam, c.count == 9 else { return nil }
        // BT.2020 (D65) → XYZ, rows X, Y, Z.
        let rec2020ToXyz = simd_float3x3(rows: [
            SIMD3<Float>(0.6369580, 0.1446169, 0.1688810),
            SIMD3<Float>(0.2627002, 0.6779981, 0.0593017),
            SIMD3<Float>(0.0000000, 0.0280727, 1.0609851),
        ])
        let x2c = simd_float3x3(rows: [
            SIMD3<Float>(Float(c[0]), Float(c[1]), Float(c[2])),
            SIMD3<Float>(Float(c[3]), Float(c[4]), Float(c[5])),
            SIMD3<Float>(Float(c[6]), Float(c[7]), Float(c[8])),
        ])
        var a = x2c * rec2020ToXyz
        let white = a * SIMD3<Float>(1, 1, 1)
        guard white.x > 1e-6, white.y > 1e-6, white.z > 1e-6 else { return nil }
        // Scale the rows: a is stored by columns, so scale per output row.
        let s = SIMD3<Float>(1 / white.x, 1 / white.y, 1 / white.z)
        a = simd_float3x3(diagonal: s) * a
        m = a
    }

    init(m: simd_float3x3) { self.m = m }

    /// `v = Aᵀ · w`: weights on the balanced channels become weights on
    /// linear BT.2020.
    func pullBack(_ w: SIMD3<Float>) -> SIMD3<Float> {
        m.transpose * w
    }
}
