// The core's 8-bit pictures as CGImages. The grey preview is the paper's
// display value; the plate is the paper's gamma 2.2 in the plate's
// primaries, which the display takes as sRGB (the same small difference,
// now in colour).
import CoreGraphics
import Foundation
import MimizanCore

enum Images {
    static func cgImage(_ g: GrayImage) -> CGImage? {
        let w = Int(g.width), h = Int(g.height)
        guard w > 0, h > 0, g.data.count >= w * h,
              let provider = CGDataProvider(data: g.data as CFData)
        else { return nil }
        return CGImage(
            width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: w,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
        )
    }

    static func cgImage(_ g: RgbImage) -> CGImage? {
        let w = Int(g.width), h = Int(g.height)
        guard w > 0, h > 0, g.data.count >= 3 * w * h,
              let provider = CGDataProvider(data: g.data as CFData)
        else { return nil }
        return CGImage(
            width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 24, bytesPerRow: 3 * w,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
        )
    }

    /// Every `scale`-th pixel so the longer edge fits `box` px (the
    /// preview planes are 2048 px; the thumbnail is a 256 px box).
    static func thumbnail(_ g: GrayImage, box: Int = 256) -> CGImage? {
        let sw = Int(g.width), sh = Int(g.height)
        guard sw > 0, sh > 0 else { return nil }
        let scale = max(1, (max(sw, sh) + box - 1) / box)
        let w = sw / scale, h = sh / scale
        var px = [UInt8](repeating: 0, count: w * h)
        g.data.withUnsafeBytes { (src: UnsafeRawBufferPointer) in
            for y in 0..<h {
                let row = y * scale * sw
                for x in 0..<w { px[y * w + x] = src[row + x * scale] }
            }
        }
        return cgImage(GrayImage(width: UInt32(w), height: UInt32(h), data: Data(px)))
    }

    static func thumbnail(_ g: RgbImage, box: Int = 256) -> CGImage? {
        let sw = Int(g.width), sh = Int(g.height)
        guard sw > 0, sh > 0 else { return nil }
        let scale = max(1, (max(sw, sh) + box - 1) / box)
        let w = sw / scale, h = sh / scale
        var px = [UInt8](repeating: 0, count: 3 * w * h)
        g.data.withUnsafeBytes { (src: UnsafeRawBufferPointer) in
            for y in 0..<h {
                let row = y * scale * sw
                for x in 0..<w {
                    let i = 3 * (row + x * scale), o = 3 * (y * w + x)
                    px[o] = src[i]
                    px[o + 1] = src[i + 1]
                    px[o + 2] = src[i + 2]
                }
            }
        }
        return cgImage(RgbImage(width: UInt32(w), height: UInt32(h), data: Data(px)))
    }

    /// Bins divided by the largest, for the histogram widgets.
    static func normalised(_ h: [UInt64]) -> [Float] {
        let m = max(Double(h.max() ?? 1), 1)
        return h.map { Float(Double($0) / m) }
    }
}
