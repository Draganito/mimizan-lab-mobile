// Where finished pictures go. Every export lands in `Documents/Mimizan Lab`,
// which the Files app shows (the Android app's `Pictures/Mimizan Lab`);
// prints (JPEG, TIFF) are added to Photos as well when the user allows it.
// Linear negatives and plates stay in Files only: Photos would show them
// dark and would gain nothing.
import Foundation
import Photos

final class Exporter: Sendable {
    let dir: URL

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        dir = docs.appendingPathComponent("Mimizan Lab", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// Rust writes to a path: a scratch file, then the move into the folder.
    /// Returns the file in `Documents/Mimizan Lab`.
    func place(fileName: String, write: (String) throws -> Void) throws -> URL {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try? FileManager.default.removeItem(at: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        try write(tmp.path)
        let dst = unique(dir.appendingPathComponent(fileName))
        try FileManager.default.moveItem(at: tmp, to: dst)
        return dst
    }

    /// A sidecar Rust may have written next to the scratch file (the mask
    /// of a negative): moved in beside the main file when it exists.
    func placeSidecar(_ name: String) -> URL? {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: tmp.path) else { return nil }
        let dst = unique(dir.appendingPathComponent(name))
        return (try? FileManager.default.moveItem(at: tmp, to: dst)) != nil ? dst : nil
    }

    /// `name.ext`, `name (2).ext`, ... so a second export of the same
    /// picture does not overwrite the first.
    private func unique(_ url: URL) -> URL {
        var candidate = url
        var n = 2
        let stem = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = url.deletingLastPathComponent().appendingPathComponent("\(stem) (\(n)).\(ext)")
            n += 1
        }
        return candidate
    }

    /// Add a copy of a print to Photos. Asks for add-only access the first
    /// time; a refusal is not an error (the file is in Files anyway).
    /// Returns whether Photos took it.
    func addToPhotos(_ url: URL) async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return false }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let req = PHAssetCreationRequest.forAsset()
                let opts = PHAssetResourceCreationOptions()
                opts.shouldMoveFile = false
                req.addResource(with: .photo, fileURL: url, options: opts)
            }
            return true
        } catch {
            return false
        }
    }
}
