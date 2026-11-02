// Where finished pictures go. Every export lands in `Documents/Mimizan Lab`,
// which the Files app shows (the Android app's `Pictures/Mimizan Lab`);
// JPEGs are added to Photos as well when the user allows it. TIFFs,
// linear negatives and plates stay in Files only: Photos would show the
// linear files dark and would gain nothing.
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
    /// Returns the file in `Documents/Mimizan Lab`. The scratch file and a
    /// `.mask.tif` sidecar the core may have written beside it are removed
    /// when the write fails, so a later export does not adopt a stale mask.
    func place(fileName: String, write: (String) throws -> Void) throws -> URL {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        let tmpMask = Exporter.sidecar(of: tmp)
        try? FileManager.default.removeItem(at: tmp)
        try? FileManager.default.removeItem(at: tmpMask)
        do {
            try write(tmp.path)
            let dst = unique(dir.appendingPathComponent(fileName))
            try FileManager.default.moveItem(at: tmp, to: dst)
            return dst
        } catch {
            try? FileManager.default.removeItem(at: tmp)
            try? FileManager.default.removeItem(at: tmpMask)
            throw error
        }
    }

    /// The mask the core writes next to a negative or plate: `<stem>.mask.tif`
    /// beside `<stem>.tif`, so `MLM_x.negative (2).tif` pairs with
    /// `MLM_x.negative (2).mask.tif`, which is what the desktop looks for.
    static func sidecar(of main: URL) -> URL {
        main.deletingPathExtension().appendingPathExtension("mask").appendingPathExtension("tif")
    }

    /// The sidecar Rust wrote next to the scratch file `fileName` (the mask
    /// of a negative), moved in beside `placed`, the file `place` returned,
    /// under that file's name. nil when there is none.
    func placeSidecar(scratchOf fileName: String, beside placed: URL) -> URL? {
        let src = Exporter.sidecar(of: FileManager.default.temporaryDirectory.appendingPathComponent(fileName))
        guard FileManager.default.fileExists(atPath: src.path) else { return nil }
        let dst = Exporter.sidecar(of: placed)
        try? FileManager.default.removeItem(at: dst)
        return (try? FileManager.default.moveItem(at: src, to: dst)) != nil ? dst : nil
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
