// What the camera offers, written down for the devices nobody here has
// in hand: every format of the lens with its size, pixel subtype, frame
// rates, colour spaces and photo flags, the format the session settled
// on, the RAW and stream types it then had, and why the finder is the
// paper or the picture. `Documents/formats.json`, one entry per lens,
// rewritten each time the lens opens.
import AVFoundation
import Foundation
import os

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "formats")

struct FormatRecord: Codable {
    var width: Int32
    var height: Int32
    var subtype: String
    var fps: [String]
    var colorSpaces: [String]
    var binned: Bool
    var videoHDR: Bool
    var highPhotoQuality: Bool
    var highestPhotoQuality: Bool
    var maxPhoto: [String]
    var iso: String

    init(_ f: AVCaptureDevice.Format) {
        let d = f.formatDescription.dimensions
        width = d.width
        height = d.height
        subtype = FormatDump.fourcc(CMFormatDescriptionGetMediaSubType(f.formatDescription))
        fps = f.videoSupportedFrameRateRanges.map { String(format: "%.0f-%.0f", $0.minFrameRate, $0.maxFrameRate) }
        colorSpaces = f.supportedColorSpaces.map(FormatDump.name)
        binned = f.isVideoBinned
        videoHDR = f.isVideoHDRSupported
        highPhotoQuality = f.isHighPhotoQualitySupported
        highestPhotoQuality = f.isHighestPhotoQualitySupported
        maxPhoto = f.supportedMaxPhotoDimensions.map { "\($0.width)×\($0.height)" }
        iso = String(format: "%.0f-%.0f", f.minISO, f.maxISO)
    }
}

struct LensFormats: Codable {
    var at: String
    var device: String
    var ios: String
    var lens: String
    /// Why the finder is the paper or the picture, as Settings shows it.
    var outcome: String
    var activeFormat: String
    /// The photo output's RAW types and the video output's stream types in the format settled on.
    var rawTypes: [String]
    var videoTypes: [String]
    /// The RAW types the photo output offered while the Apple Log format
    /// was active (nil when none was tried). Empty on every Pro iPhone so
    /// far, and that says nothing about the Log format: a format set by
    /// hand puts the session on `.inputPriority`, where no RAW is offered.
    var logRawTypes: [String]?
    var formats: [FormatRecord]
}

enum FormatDump {
    static let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("formats.json")

    /// Keep this lens's entry (the file is a dictionary by lens label) and
    /// say so, for Settings: the file is what the testers send, so a
    /// failure must be readable on the screen, not only in the log. Call
    /// off the camera queue.
    static func write(_ entry: LensFormats) -> String {
        do {
            var all = readOrSetAside([String: LensFormats].self, at: url) ?? [:]
            all[entry.lens] = entry
            let enc = JSONEncoder()
            enc.outputFormatting = [.prettyPrinted, .sortedKeys]
            try enc.encode(all).write(to: url, options: .atomic)
            let bytes = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
            return "formats.json written \(Date().formatted(date: .omitted, time: .standard)), \(entry.formats.count) formats of \(entry.lens), \(Format.bytes(bytes))."
        } catch {
            log.warning("formats.json: \(error.localizedDescription)")
            return "formats.json could not be written: \(error.localizedDescription)"
        }
    }

    static var exists: Bool { FileManager.default.fileExists(atPath: url.path) }

    /// The file decoded, nil when there is none. A file that is there but
    /// does not decode (a schema change, a cut-off write) is moved beside
    /// itself as `<name>.bad-<time>.json` rather than overwritten: these
    /// are the files the testers send, and what they hold is not to be lost.
    static func readOrSetAside<T: Decodable>(_ type: T.Type, at url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        if let v = try? JSONDecoder().decode(type, from: data) { return v }
        let stamp = Int(Date().timeIntervalSince1970)
        let aside = url.deletingPathExtension().appendingPathExtension("bad-\(stamp).json")
        try? FileManager.default.moveItem(at: url, to: aside)
        log.warning("\(url.lastPathComponent) did not decode; set aside as \(aside.lastPathComponent)")
        return nil
    }

    static func fourcc(_ t: OSType) -> String {
        let bytes = [UInt8(t >> 24 & 0xFF), UInt8(t >> 16 & 0xFF), UInt8(t >> 8 & 0xFF), UInt8(t & 0xFF)]
        let s = String(decoding: bytes, as: UTF8.self)
        return s.allSatisfy({ $0.isASCII && !$0.isNewline && $0 != "\0" }) ? s : String(t)
    }

    static func name(_ c: AVCaptureColorSpace) -> String {
        switch c {
        case .sRGB: return "sRGB"
        case .P3_D65: return "P3_D65"
        case .HLG_BT2020: return "HLG_BT2020"
        case .appleLog: return "AppleLog"
        @unknown default: return "\(c.rawValue)"
        }
    }
}
