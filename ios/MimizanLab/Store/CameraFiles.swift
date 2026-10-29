// Camera files shipped in the bundle's `cameras` folder (schema 1 of
// Mimizan Lab), matched on make + model prefix, only when the file's
// `bayer_phase` matches the frame: the core refuses a mismatch, so a wrong
// file is worse than none.
import Foundation
import MimizanCore

final class CameraFiles: Sendable {
    private struct Entry: Sendable {
        let make: String
        let model: String
        let phase: String?
        let text: String
    }

    private let files: [Entry]

    init() {
        var out: [Entry] = []
        let urls = Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: "cameras") ?? []
        for url in urls {
            guard let data = try? Data(contentsOf: url),
                  let text = String(data: data, encoding: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { continue }
            out.append(Entry(
                make: ((obj["make"] as? String) ?? "").trimmingCharacters(in: .whitespaces).lowercased(),
                model: ((obj["model"] as? String) ?? "").trimmingCharacters(in: .whitespaces).lowercased(),
                phase: (obj["bayer_phase"] as? String)?.uppercased(),
                text: text
            ))
        }
        files = out
    }

    /// The file for this make/model whose `bayer_phase` matches the frame.
    func forModel(make: String, model: String, pattern: BayerPattern) -> String? {
        let m = make.trimmingCharacters(in: .whitespaces).lowercased()
        let mo = model.trimmingCharacters(in: .whitespaces).lowercased()
        return files.first { e in
            e.make == m && mo.hasPrefix(e.model) && (e.phase ?? pattern.name) == pattern.name
        }?.text
    }
}
