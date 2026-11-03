// The viewfinder's state: which lens, which exposure, and which development
// the next frame gets. The finder itself is the camera's picture.
import AVFoundation
import Foundation
import MimizanCore
import Observation
import os

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "capture")

/// Viewfinder settings that are not camera state.
struct ViewfinderSettings: Equatable {
    var zebra = false
    var grid = false
    var showHistogram = false
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
    /// Set when a frame has been taken, so the screen can open the developer.
    private(set) var pendingReview: String?

    private var startTask: Task<Void, Never>?
    /// A start is under way and has not been cancelled.
    private var starting = false
    private var observing = false

    var state: CaptureState { session.state }
    var prefs: Prefs { darkroom.prefs }

    /// What the next frame is developed with.
    var develop: DevelopSettings { darkroom.withStandingProcess(local) }

    init() {
        lenses = LensCatalog.enumerate()
        lens = lenses.first
        viewfinder = ViewfinderSettings(zebra: prefs.zebra, grid: prefs.grid, showHistogram: prefs.histogram)
        if let renderer {
            session.onFrame = { [renderer] frame in renderer.submit(frame: frame) }
        }
        rebuildTone()
        observeTone()
    }

    // MARK: tone

    /// Re-run `rebuildTone` whenever an input it reads changes.
    private func observeTone() {
        withObservationTracking {
            _ = prefs.process
            _ = viewfinder.zebra
            _ = session.state.rotationQuarters
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.rebuildTone()
                self.observeTone()
            }
        }
    }

    /// Monochrome is the camera's preview with the colour taken out.
    /// Colour is the preview itself. The grade never reaches the finder.
    private func rebuildTone() {
        let t = FinderTone(mono: !develop.isPlate, zebra: viewfinder.zebra ? 0.985 : 2)
        if t != tone {
            tone = t
            renderer?.set(tone: t)
        }
        renderer?.set(rotationQuarters: session.state.rotationQuarters)
    }

    // MARK: lifecycle

    /// Open the camera. `onAppear` and the scene turning active both call
    /// this at launch; a start that is still under way is left to finish
    /// rather than configured a second time.
    func resume() {
        if starting, let t = startTask, !t.isCancelled { return }
        restart()
    }

    /// Open the camera again whatever is under way (a lens change).
    private func restart() {
        startTask?.cancel()
        starting = true
        startTask = Task {
            await start()
            // A cancelled start has been replaced; the replacement owns the flag.
            if !Task.isCancelled { starting = false }
        }
    }

    func stop() {
        startTask?.cancel()
        starting = false
        session.stop()
    }

    /// The screen's size changed (the iPad turned): the finder's rotation follows the interface.
    func refreshOrientation() { session.refreshOrientation() }

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
                // Replaced while the session was opening: the newer start owns the state.
                if Task.isCancelled { return }
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
        // Another lens, another range: back to the automatics.
        exposure = ExposureSettings()
        restart()
    }

    func nextLens() {
        guard lenses.count > 1 else { return }
        let i = lenses.firstIndex { $0.key == lens?.key } ?? 0
        selectLens(lenses[(i + 1) % lenses.count])
    }

    // MARK: exposure

    func setManual(iso: Double, seconds: Double) {
        // An invalid CMTime reads as NaN; it must not reach the device or a slider.
        guard iso.isFinite, seconds.isFinite, seconds > 0 else { return }
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
        let measured = state.exposureSeconds ?? 1.0 / 125
        let t = min(max(measured.isFinite && measured > 0 ? measured : 1.0 / 125, l.exposureRange.lowerBound), l.exposureRange.upperBound)
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
    /// The standing grade: the automatic JPEG, and where a new frame starts.
    func setSoft(_ stops: Double) { prefs.setSoft(stops) }
    func setHard(_ stops: Double) { prefs.setHard(stops) }
    func setGrade(soft: Double, hard: Double) {
        prefs.setSoft(soft)
        prefs.setHard(hard)
    }
    func toggleZebra() { viewfinder.zebra.toggle(); prefs.setZebra(viewfinder.zebra) }
    func toggleGrid() { viewfinder.grid.toggle(); prefs.setGrid(viewfinder.grid) }
    func toggleHistogram() { viewfinder.showHistogram.toggle(); prefs.setHistogram(viewfinder.showHistogram) }
    func setPeakMode(_ mode: PeakMode) { prefs.setPeakMode(mode) }
    func setPeak(radius: Double, amount: Double) { prefs.setPeak(radius: radius, amount: amount) }
    func setProcess(_ p: Process) { prefs.setProcess(p) }
    func setPlateFilters(_ f: PlateFilters) { prefs.setPlateFilters(f) }
    func setPlateSpace(_ s: PlateSpace) { prefs.setPlateSpace(s) }
    func setShotMode(_ mode: ShotMode) { prefs.setShotMode(mode) }

    // MARK: the shot

    func capture() {
        guard let l = lens, !state.capturing else { return }
        var settings = develop
        let mode = prefs.shotMode
        settings.autoJpeg = mode == .jpeg
        Task {
            do {
                let dng = try await session.capture()
                let name = darkroom.submit(dng: dng, lens: l.label, capturedAt: Date(), settings: settings, developNow: mode != .raw)
                if mode == .developer { pendingReview = name }
                // Once: a JPEG that came out wrong is not lost. The frame
                // is on the roll and prints again at another grade.
                if mode == .jpeg, !prefs.rollHintShown {
                    prefs.markRollHintShown()
                    message = "The frame stays on the roll. Open it there to print it again, at another grade."
                }
            } catch {
                log.error("capture: \(error.localizedDescription)")
                message = error.localizedDescription
            }
        }
    }

    /// The screen calls this once it has opened the developer.
    func clearPendingReview() { pendingReview = nil }
}
