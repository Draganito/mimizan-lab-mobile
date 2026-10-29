// Mimizan Lab for iOS: a camera that writes the Bayer DNG and develops it
// with the mimizan core into a monochrome negative or a three-colour plate.
import SwiftUI

/// The phone stays upright; the iPad may turn. The capture session reads
/// the device orientation, so the app has to subscribe to it.
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .portrait
    }
}

@main
struct MimizanLabApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // The darkroom restores the roll and starts the core's logging.
        _ = Darkroom.shared
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
