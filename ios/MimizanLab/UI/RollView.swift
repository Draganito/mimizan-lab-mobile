// The roll: every frame in the cache, newest first, with its state; swipe
// or the bin to delete. Import from Files and Photos stays off: a full-size
// NEF develops past the iPad's memory and the system kills the app.
// Mirrors `RollScreen.kt`.
import MimizanCore
import SwiftUI

struct RollView: View {
    /// Which shot is open (the split view's selection; the stack pushes on it).
    @Binding var selection: String?
    @State private var toDelete: String?

    private var darkroom: Darkroom { Darkroom.shared }

    var body: some View {
        let shots = darkroom.shots
        List {
            if shots.isEmpty {
                Text("No frames. Pictures you exported are in Files › On My iPhone › Mimizan Lab.")
                    .foregroundStyle(.gray)
                    .listRowBackground(Color.black)
            }
            ForEach(shots) { shot in
                Button { selection = shot.name } label: { RollRow(shot: shot) }
                    .buttonStyle(.plain)
                    .listRowBackground(selection == shot.name ? Color(white: 0.18) : Color.black)
                    .swipeActions {
                        Button(role: .destructive) { toDelete = shot.name } label: { Label("Delete", systemImage: "trash") }
                            .disabled(shot.status == .saving || shot.status == .developing)
                    }
                    .contextMenu {
                        // Not while the frame is being written or developed, like the swipe.
                        Button(role: .destructive) { toDelete = shot.name } label: { Label("Delete frame", systemImage: "trash") }
                            .disabled(shot.status == .saving || shot.status == .developing)
                    }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.black.ignoresSafeArea())
        .foregroundStyle(.white)
        .navigationTitle("Roll · \(shots.count)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbarBackground(Color.black, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                Text(Format.bytes(darkroom.cacheBytes)).font(.caption).foregroundStyle(.gray)
            }
        }
        .alert("Delete \(toDelete ?? "")?", isPresented: Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } })) {
            Button("Delete", role: .destructive) { if let n = toDelete { darkroom.delete(n) }; toDelete = nil }
            Button("Cancel", role: .cancel) { toDelete = nil }
        } message: {
            Text("The raw frame leaves the app for good; it can no longer be developed again. Pictures already exported stay.")
        }
    }
}

private struct RollRow: View {
    let shot: Shot

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Rectangle().fill(Color(white: 0.125))
                if let t = shot.thumbnail {
                    Image(decorative: t, scale: 1).resizable().aspectRatio(contentMode: .fill).clipped()
                }
                if shot.status.isBusy { ProgressView().tint(.white) }
            }
            .frame(width: 72, height: 72)
            .clipped()
            VStack(alignment: .leading, spacing: 2) {
                Text(shot.name).font(.subheadline.monospaced())
                let m = shot.meta
                Text("\(m.lens) · \(Format.iso(m.iso)) · \(Format.shutter(m.exposureNs)) · " +
                    (shot.settings.isPlate ? "Plate \(Format.plateFilters(shot.settings.plateFilters))" : Format.filterShort(shot.settings.filter)))
                    .font(.caption).foregroundStyle(.gray).lineLimit(1)
                Text(statusText)
                    .font(.caption)
                    .foregroundStyle(isFailed ? Color(red: 1, green: 0.5, blue: 0.5) : .gray)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    private var isFailed: Bool { if case .failed = shot.status { return true }; return false }

    private var statusText: String {
        switch shot.status {
        case .saving: return "saving frame"
        case .cached: return "in cache · tap to develop"
        case .queued: return "queued"
        case .developing: return "developing"
        case let .ready(info): return "developed · \(info.timing.totalMs) ms" + (shot.jpegURL != nil ? " · JPEG saved" : "")
        case let .failed(m): return m
        }
    }
}
