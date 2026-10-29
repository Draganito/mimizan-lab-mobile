// How the camera's sRGB preview becomes the negative's display value, for
// the Metal kernel and the histogram alike:
//
//   gray  = v · linearise(rgb)   — `v` is the mix weights pulled back from
//                                   the balanced camera channels to linear
//                                   sRGB, so `gray` approximates the
//                                   balanced mix the development computes
//   value = lut[gray]            — the paper's display value of that linear
//                                   grey (the paper already encodes the print)
//
// Android pins the ISP to sRGB and inverts the exact matrix of every frame.
// AVFoundation exposes no such matrix: the pull-back here goes through the
// sensor's own XYZ → camera matrix (from the last DNG of this lens) and
// the sRGB primaries, with white kept at white. The still is developed
// from the DNG; the finder is the finder.
import Foundation
import simd

struct FinderTone: Equatable, Sendable {
    /// Weights on linear sRGB for the luminance.
    var v: SIMD3<Float>
    /// The paper over the linear axis, `n + 1` display values.
    var lut: [Float]
    /// Display value at or above which zebra marks; > 1 means off.
    var zebra: Float
    /// In the plate process the three taking filters pulled back the
    /// same way (rows R, G, B); nil for the monochrome negative.
    var plate: (SIMD3<Float>, SIMD3<Float>, SIMD3<Float>)?
    /// The lamps as linear factors (`2^stops`), green the reference.
    var lampGain: SIMD3<Float>

    static func == (a: FinderTone, b: FinderTone) -> Bool {
        a.v == b.v && a.lut == b.lut && a.zebra == b.zebra && a.lampGain == b.lampGain
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

/// Linear sRGB → balanced camera channels, white to white. Identity when
/// the lens has not shown a matrix yet.
struct SrgbToCamera: Equatable {
    /// Rows R, G, B of the balanced camera signal over linear sRGB.
    var m: simd_float3x3

    static let identity = SrgbToCamera(m: matrix_identity_float3x3)

    /// From the sensor's XYZ → camera matrix (9 values row-major, D65).
    /// `cam = xyzToCam · XYZ_from_sRGB · rgb`; each row is then scaled so a
    /// grey (1, 1, 1) in sRGB gives (1, 1, 1) in the balanced channels,
    /// which is what the white balance before the separation does.
    init?(xyzToCam: [Double]?) {
        guard let c = xyzToCam, c.count == 9 else { return nil }
        // sRGB (D65) → XYZ, rows X, Y, Z.
        let srgbToXyz = simd_float3x3(rows: [
            SIMD3<Float>(0.4124564, 0.3575761, 0.1804375),
            SIMD3<Float>(0.2126729, 0.7151522, 0.0721750),
            SIMD3<Float>(0.0193339, 0.1191920, 0.9503041),
        ])
        let x2c = simd_float3x3(rows: [
            SIMD3<Float>(Float(c[0]), Float(c[1]), Float(c[2])),
            SIMD3<Float>(Float(c[3]), Float(c[4]), Float(c[5])),
            SIMD3<Float>(Float(c[6]), Float(c[7]), Float(c[8])),
        ])
        var a = x2c * srgbToXyz
        let white = a * SIMD3<Float>(1, 1, 1)
        guard white.x > 1e-6, white.y > 1e-6, white.z > 1e-6 else { return nil }
        // Scale the rows: a is stored by columns, so scale per output row.
        let s = SIMD3<Float>(1 / white.x, 1 / white.y, 1 / white.z)
        a = simd_float3x3(diagonal: s) * a
        m = a
    }

    init(m: simd_float3x3) { self.m = m }

    /// `v = Aᵀ · w`: weights on the balanced channels become weights on
    /// linear sRGB.
    func pullBack(_ w: SIMD3<Float>) -> SIMD3<Float> {
        m.transpose * w
    }
}
