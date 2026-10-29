import CoreGraphics
import Foundation
import MimizanCore

/// How a frame is developed; chosen in the viewfinder, changeable in review.
struct DevelopSettings: Equatable {
    var separation: SeparationMode = .dubois
    /// 2 = CFA-preserving 2x binning before ingest (quick mode).
    var binning: Int = 1
    var filter: Filter = .none
    var whiteBalance: WhiteBalanceMode = .asShot
    /// Save a finished JPEG (the paper at the settings exposures, full size) as soon as the frame is developed.
    var autoJpeg: Bool = true
    var process: Process = .mono
    /// The plate's taking filters (only read in the plate process).
    var plateFilters: PlateFilters = .camera
    /// The plate's primaries, the colour space of the file.
    var plateSpace: PlateSpace = .srgb

    var isPlate: Bool { process == .plate }
}

enum ShotStatus: Equatable {
    case saving
    /// Frame in the cache (earlier run), negative not in memory.
    case cached
    case queued
    case developing
    case ready(DevelopInfo)
    case failed(String)

    var isBusy: Bool {
        switch self {
        case .saving, .queued, .developing: return true
        default: return false
        }
    }
}

struct Shot: Identifiable, Equatable {
    var name: String
    var meta: ShotMeta
    var settings: DevelopSettings
    var status: ShotStatus
    /// The automatic JPEG, once written.
    var jpegURL: URL?
    var thumbnail: CGImage?

    var id: String { name }

    static func == (a: Shot, b: Shot) -> Bool {
        a.name == b.name && a.meta == b.meta && a.settings == b.settings && a.status == b.status
            && a.jpegURL == b.jpegURL && a.thumbnail === b.thumbnail
    }
}

extension Weights {
    static let native = Weights(r: 0.25, g: 0.5, b: 0.25)
}
