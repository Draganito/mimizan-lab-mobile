// What the viewfinder does to the camera's preview. The print is not
// involved: monochrome is the preview desaturated, colour is the preview.
import Foundation

struct FinderTone: Equatable, Sendable {
    var mono: Bool
    /// Display value at or above which zebra marks; above 1 means off.
    var zebra: Float
}
