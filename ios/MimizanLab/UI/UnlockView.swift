// The purchase, in one place: a section in Settings, and a sheet when a
// locked export is tapped in the developer. What is free stays said
// beside it, so nobody buys what they already have.
import SwiftUI

struct UnlockSection: View {
    @Bindable var entitlements: Entitlements

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if entitlements.fullResolution {
                Text("Full resolution is unlocked: the JPEG at full size, the 16-bit TIFF and the linear negative.")
                    .font(.caption).foregroundStyle(.gray)
            } else {
                Text("The camera, the finder, the developer, the plate, the roll and the JPEG at half size are free. One purchase, once, unlocks the print at full resolution: the JPEG at full size, the 16-bit TIFF and the linear negative for the desktop darkroom. Shared with your family and with the Mac.")
                    .font(.caption).foregroundStyle(.gray)
                HStack(spacing: 16) {
                    Button {
                        Task { await entitlements.purchase() }
                    } label: {
                        Text(entitlements.price.map { "Unlock full resolution · \($0)" } ?? "Unlock full resolution")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 9)
                            .background(Color.white, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    Button("Restore") { Task { await entitlements.restore() } }
                        .font(.subheadline)
                }
                .disabled(entitlements.busy)
                .opacity(entitlements.busy ? 0.55 : 1)
            }
        }
    }
}

/// The sheet the developer shows for a locked export.
struct UnlockSheet: View {
    @Bindable var entitlements: Entitlements
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Full resolution").font(.title2.bold())
                    UnlockSection(entitlements: entitlements)
                }
                .padding(24)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .toast($entitlements.message)
            .onChange(of: entitlements.fullResolution) { _, owned in
                if owned { dismiss() }
            }
        }
        .presentationDetents([.medium])
    }
}
