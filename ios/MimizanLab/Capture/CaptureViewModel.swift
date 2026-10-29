// The viewfinder's state: which lens, which exposure, which development
// the next frame gets, and the tone the finder shows. Mirrors
// `CameraViewModel.kt`.
import AVFoundation
import Foundation
import MimizanCore
import Observation
import os
import UIKit

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "capture")

/// Viewfinder settings that are not camera state.
struct ViewfinderSettings: Equatable {
    var zebra = true
    var grid = false
    var showHistogram = true
}

@MainActor
@Observable
final class CaptureViewModel {
    let darkroom = Darkroom.shared
    let session = CaptureSession()
    let renderer = FinderRenderer.make()
    let lenses: [LensInfo]

    private(set) var lens: LensInfo?
    private(set) var exposure = ExposureSettings()
    /// This session's choices; the process and the plate's come from the roll's standing settings.
    private var local = DevelopSettings()
    var viewfinder = ViewfinderSettings()
    var message: String?
    private(set) var permissionDenied = false
    private(set) var tone: FinderTone?

    private var startTask: Task<Void, Never>?
    private var observing = false

    var state: CaptureState { session.state }
    var prefs: Prefs { darkroom.prefs }

    /// What the next frame is developed with.
    var develop: DevelopSettings { darkroom.withStandingProcess(local) }

    init() {
        lenses = LensCatalog.enumerate()
        lens = lenses.first
        if let renderer {
            session.onFrame = { [renderer] frame in renderer.submit(frame: frame) }
        }
        rebuildTone()
        observeTone()
    }

    // MARK: tone

    /// Re-run `rebuildTone` whenever an input it read changes.
    private func observeTone() {
        withObservationTracking {
            _ = session.state.wbGains
            _ = prefs.soft; _ = prefs.hard; _ = prefs.lampRed; _ = prefs.lampBlue
            _ = prefs.process; _ = prefs.plateFilters; _ = prefs.plateSpace
            _ = local; _ = viewfinder.zebra; _ = lens
            _ = darkroom.shots.first?.meta.xyzToCam
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.rebuildTone()
                self.observeTone()
            }
        }
    }

    /// What the finder kernel applies to the camera's preview: the still's
    /// mix weights for the current filter and this frame's balance, pulled
    /// back to linear sRGB, and the paper the automatic JPEG uses; in the
    /// plate process the three taking filters the same way, and the lamps.
    private func rebuildTone() {
        let dev = develop
        let gains = session.state.wbGains
        let matrix = lens.flatMap { lensMatrix(for: $0) }
        let back = SrgbToCamera(xyzToCam: matrix) ?? .identity
        let params = darkroom.developParams(make: "Apple", model: CaptureViewModel.deviceModel, pattern: .rggb, dev)
        let w: Weights
        do {
            w = try mixWeights(params: params, wbGains: gains)
        } catch {
            log.warning("mix weights: \(error.localizedDescription)")
            w = .native
        }
        var plate: (SIMD3<Float>, SIMD3<Float>, SIMD3<Float>)?
        if dev.isPlate {
            do {
                let pw = try plateWeights(params: params, wbGains: gains, xyzToCam: matrix, filters: dev.plateFilters, space: dev.plateSpace)
                plate = (back.pullBack(pw.red.simd), back.pullBack(pw.green.simd), back.pullBack(pw.blue.simd))
            } catch {
                log.warning("plate weights: \(error.localizedDescription)")
            }
        }
        let lut: [Float]
        do {
            lut = try paperLut(soft: prefs.soft, hard: prefs.hard, n: 4096)
        } catch {
            log.warning("paper lut: \(error.localizedDescription)")
            lut = (0...4096).map { Float($0) / 4096 }
        }
        let t = FinderTone(
            v: back.pullBack(w.simd),
            lut: lut,
            zebra: viewfinder.zebra ? 0.985 : 2,
            plate: plate,
            lampGain: SIMD3(Float(pow(2.0, prefs.lampRed)), 1, Float(pow(2.0, prefs.lampBlue)))
        )
        if t != tone {
            tone = t
            session.tone = t
            renderer?.set(tone: t)
        }
    }

    /// The sensor's XYZ → camera matrix as the last DNG of this lens
    /// carried it; remembered across runs so the first finder frame has it.
    private func lensMatrix(for lens: LensInfo) -> [Double]? {
        let key = "xyz_to_cam_" + lens.label
        if let m = darkroom.shots.first(where: { $0.meta.lens == lens.label && $0.meta.xyzToCam != nil })?.meta.xyzToCam {
            UserDefaults.standard.set(m, forKey: key)
            return m
        }
        return UserDefaults.standard.array(forKey: key) as? [Double]
    }

    var hasMatrix: Bool { lens.flatMap { lensMatrix(for: $0) } != nil }

    nonisolated static let deviceModel: String = {
        var u = utsname()
        uname(&u)
        let s = withUnsafePointer(to: &u.machine) { p in
            p.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) { String(cString: $0) }
        }
        return s.isEmpty ? "iPhone" : s
    }()

    // MARK: lifecycle

    func resume() {
        startTask?.cancel()
        startTask = Task { await start() }
    }

    func stop() {
        startTask?.cancel()
        session.stop()
    }

    private func start() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: break
        case .notDetermined:
            if !(await AVCaptureDevice.requestAccess(for: .video)) { permissionDenied = true; return }
        default:
            permissionDenied = true
            return
        }
        permissionDenied = false
        guard let l = lens else { message = "No RAW-capable camera"; return }
        // The lens may advertise RAW and refuse the session: try the rest
        // before giving up, and keep the one that opened.
        let order = [l] + lenses.filter { $0.key != l.key }
        var last: Error?
        for candidate in order {
            if Task.isCancelled { return }
            do {
                try await session.start(candidate)
                session.setExposure(exposure)
                if candidate.key != l.key {
                    lens = candidate
                    message = "\(l.label) has no RAW session, opened \(candidate.label)"
                }
                return
            } catch {
                log.error("start \(candidate.label): \(error.localizedDescription)")
                last = error
            }
        }
        state.error = last?.localizedDescription ?? "Camera failed"
    }

    func selectLens(_ l: LensInfo) {
        guard lens?.key != l.key else { return }
        lens = l
        exposure = ExposureSettings(bias: exposure.bias)
        resume()
    }

    func nextLens() {
        guard lenses.count > 1 else { return }
        let i = lenses.firstIndex { $0.key == lens?.key } ?? 0
        selectLens(lenses[(i + 1) % lenses.count])
    }

    // MARK: exposure

    func setBias(_ ev: Double) {
        exposure.bias = ev
        session.setExposure(exposure)
    }

    func setManual(iso: Double, seconds: Double) {
        exposure.iso = iso
        exposure.seconds = seconds
        session.setExposure(exposure)
    }

    func setAuto() {
        exposure.iso = nil
        exposure.seconds = nil
        session.setExposure(exposure)
    }

    /// Switch to manual seeded with the exposure the automatics chose.
    func enterManual() {
        guard let l = lens else { return }
        let iso = min(max(Double(state.iso ?? 100), l.isoRange.lowerBound), l.isoRange.upperBound)
        let t = min(max(state.exposureSeconds ?? 1.0 / 125, l.exposureRange.lowerBound), l.exposureRange.upperBound)
        setManual(iso: iso, seconds: t)
    }

    func focusAt(_ p: CGPoint) { session.focus(at: p) }
    func clearFocus() { session.clearFocus() }
    func cycleFlash() { session.setFlash(state.flash.next) }

    // MARK: development settings

    func setFilter(_ f: Filter) { local.filter = f }
    func setSeparation(_ m: SeparationMode) { local.separation = m }
    func setQuick(_ on: Bool) { local.binning = on ? 2 : 1 }
    func setAutoJpeg(_ on: Bool) { local.autoJpeg = on }
    func setProcess(_ p: Process) { prefs.setProcess(p) }
    func setPlateFilters(_ f: PlateFilters) { prefs.setPlateFilters(f) }
    func setPlateSpace(_ s: PlateSpace) { prefs.setPlateSpace(s) }

    // MARK: the shot

    func capture() {
        guard let l = lens, !state.capturing else { return }
        let settings = develop
        Task {
            do {
                let dng = try await session.capture()
                darkroom.submit(dng: dng, lens: l.label, capturedAt: Date(), settings: settings)
            } catch {
                log.error("capture: \(error.localizedDescription)")
                message = error.localizedDescription
            }
        }
    }
}

extension Weights {
    var simd: SIMD3<Float> { SIMD3(Float(r), Float(g), Float(b)) }
}
