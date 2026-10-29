// Navigation. On a phone (compact width) a stack: viewfinder, then the
// roll or a review pushed on it. On an iPad (regular width) a split view:
// the roll on the side, the viewfinder or the selected review in the
// detail column.
import SwiftUI

private enum Route: Hashable {
    case roll
    case review(String)
}

struct RootView: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var capture = CaptureViewModel()

    var body: some View {
        if sizeClass == .regular {
            SplitRoot(capture: capture)
        } else {
            StackRoot(capture: capture)
        }
    }
}

private struct StackRoot: View {
    let capture: CaptureViewModel
    @State private var path: [Route] = []
    @State private var selection: String?

    var body: some View {
        NavigationStack(path: $path) {
            CaptureView(vm: capture, onOpenShot: { path.append(.review($0)) }, onOpenRoll: { path.append(.roll) })
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .roll:
                        RollView(selection: $selection)
                            .onChange(of: selection) { _, name in
                                if let name { path.append(.review(name)); selection = nil }
                            }
                    case let .review(name):
                        ReviewView(name: name)
                    }
                }
        }
        .preferredColorScheme(.dark)
    }
}

private struct SplitRoot: View {
    let capture: CaptureViewModel
    @State private var selection: String?
    @State private var columns = NavigationSplitViewVisibility.all

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            RollView(selection: $selection)
                .navigationSplitViewColumnWidth(min: 280, ideal: 340, max: 420)
        } detail: {
            if let name = selection {
                ReviewView(name: name)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button { selection = nil } label: { Label("Camera", systemImage: "camera") }
                        }
                    }
                    .id(name)
            } else {
                CaptureView(vm: capture, onOpenShot: { selection = $0 }, onOpenRoll: { columns = .all })
                    .toolbar(.hidden, for: .navigationBar)
            }
        }
        .navigationSplitViewStyle(.balanced)
        .preferredColorScheme(.dark)
    }
}
