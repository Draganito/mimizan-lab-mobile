// Mimizan Lab for iOS: a camera that writes the Bayer DNG and develops it
// with the mimizan core into a monochrome negative or a three-colour plate.
import SwiftUI

@main
struct MimizanLabApp: App {
    init() {
        // The darkroom restores the roll and starts the core's logging.
        _ = Darkroom.shared
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
