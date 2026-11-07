// The viewfinder's state: which lens, which exposure, and which development
// the next frame gets, and the tone the finder shows. On an Apple Log
// camera the finder is the paper, as on Android; elsewhere it is the
// camera's picture.
import AVFoundation
import Foundation
import MimizanCore
import Observation
import os
import simd

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
    var viewfinder = ViewfinderSettings()
    var message: String?
    private(set) var permissionDenied = false
    private(set) var tone: FinderTone?
    /// Set when a frame has been taken, so the screen can open the developer.
    private(set) var pendingReview: String?

    private var startTask: Task<Void, Never>?
    /// A start is under way and has not been cancelled.
    private var starting = false

    var state: CaptureState { session.state }
    var prefs: Prefs { darkroom.prefs }

    /// What the next frame is developed with: the standing process and
    /// the plate's filters, the mono filter.
    var develop: DevelopSettings { darkroom.withStandingProcess(DevelopSettings()) }

    /// ISO and time the hand's sliders run over: the range of the format
    /// the still is taken in, once the session knows it.
    var isoRange: ClosedRange<Double>? { state.isoRange ?? lens?.isoRange }
    var exposureRange: ClosedRange<Double>? { state.exposureRange ?? lens?.exposureRange }

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
            _ = prefs.soft; _ = prefs.hard
            _ = prefs.process; _ = prefs.filter; _ = prefs.plateFilters; _ = prefs.plateSpace
            _ = prefs.finderMatch
            _ = viewfinder.zebra; _ = lens
            _ = session.state.paperFinder
            _ = session.state.wbGains
            _ = session.state.rotationQuarters
            // A new DNG may have brought this lens its matrix.
            _ = prefs.matrixVersion
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.rebuildTone()
                self.observeTone()
            }
        }
    }

    /// On the camera's picture: monochrome is the preview with the colour
    /// taken out, colour the preview itself; the grade never reaches it.
    /// On an Apple Log stream: the still's mix weights for the current
    /// filter and this frame's balance, pulled back to linear BT.2020,
    /// the standing paper, and the measured stops between the two axes;
    /// in the plate process the three taking filters the same way.
    private func rebuildTone() {
        let dev = develop
        let t = FinderTone(
            mono: !dev.isPlate,
            zebra: viewfinder.zebra ? 0.985 : 2,
            paper: session.state.paperFinder ? paperTone(dev) : nil
        )
        if t != tone {
            tone = t
            session.tone = t
            renderer?.set(tone: t)
        }
        renderer?.set(rotationQuarters: session.state.rotationQuarters)
    }

    private func paperTone(_ dev: DevelopSettings) -> PaperTone {
        let gains = session.state.wbGains
        let matrix = lens.flatMap { prefs.xyzToCam(lens: $0.label) }
        let back = Rec2020ToCamera(xyzToCam: matrix) ?? .identity
        let params = darkroom.developParams(make: "Apple", model: FinderMatchLog.deviceModel, pattern: .rggb, dev)
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
        return PaperTone(
            v: back.pullBack(w.simd), plate: plate,
            gain: Float(exp2(prefs.finderMatch.stops(lens: lens?.label))), lut: lut
        )
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
            // The camera daemon is not ready on the same turn as the grant
            // (a fresh install aborts in commitConfiguration otherwise).
            try? await Task.sleep(nanoseconds: 400_000_000)
        default:
            permissionDenied = true
            return
        }
        permissionDenied = false
        guard let l = lens else { message = "No RAW-capable camera"; return }
        // The lens may advertise RAW and refuse the session: try the rest
        // before giving up, and keep the one that opened.
        let order = [l] + lenses.filter { $0.id != l.id }
        var last: Error?
        for candidate in order {
            if Task.isCancelled { return }
            do {
                try await session.start(candidate)
                // Replaced while the session was opening: the newer start owns the state.
                if Task.isCancelled { return }
                session.setExposure(exposure)
                if candidate.id != l.id {
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
        guard lens?.id != l.id else { return }
        lens = l
        // Another lens, another range: back to the automatics, told to the
        // session before it opens the lens so the old pair never reaches it.
        exposure = ExposureSettings()
        session.setExposure(exposure)
        restart()
    }

    func nextLens() {
        guard lenses.count > 1 else { return }
        let i = lenses.firstIndex { $0.id == lens?.id } ?? 0
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
        guard let isoRange, let exposureRange else { return }
        let iso = min(max(Double(state.iso ?? 100), isoRange.lowerBound), isoRange.upperBound)
        let measured = state.exposureSeconds ?? 1.0 / 125
        let t = min(max(measured.isFinite && measured > 0 ? measured : 1.0 / 125, exposureRange.lowerBound), exposureRange.upperBound)
        setManual(iso: iso, seconds: t)
    }

    func focusAt(_ p: CGPoint) { session.focus(at: p) }
    func clearFocus() { session.clearFocus() }
    func cycleFlash() { session.setFlash(state.flash.next) }

    // MARK: development settings

    /// The standing grade: the automatic JPEG, and where a new frame starts.
    func setGrade(soft: Double, hard: Double) { prefs.setGrade(soft: soft, hard: hard) }
    func toggleZebra() { viewfinder.zebra.toggle(); prefs.setZebra(viewfinder.zebra) }
    func toggleGrid() { viewfinder.grid.toggle(); prefs.setGrid(viewfinder.grid) }
    func toggleHistogram() { viewfinder.showHistogram.toggle(); prefs.setHistogram(viewfinder.showHistogram) }
    func setPeakMode(_ mode: PeakMode) { prefs.setPeakMode(mode) }
    func setProcess(_ p: Process) { prefs.setProcess(p) }
    func setFilter(_ f: Filter) { prefs.setFilter(f) }
    func setPlateFilters(_ f: PlateFilters) { prefs.setPlateFilters(f) }
    func setPlateSpace(_ s: PlateSpace) { prefs.setPlateSpace(s) }
    /// Whether this lens has brought its colour matrix yet (a DNG of it developed).
    var hasMatrix: Bool { lens.flatMap { prefs.xyzToCam(lens: $0.label) } != nil }
    func setShotMode(_ mode: ShotMode) { prefs.setShotMode(mode) }

    /// The shutter may fire: a lens is open and no still is under way.
    var canShoot: Bool { !state.capturing && lens != nil && state.lens != nil }

    /// The shot modes this device offers. The automatic JPEG is the print
    /// the finder showed, so it is only offered where the finder is the
    /// paper; elsewhere the picture is made in the developer, on the
    /// screen, or the frame waits on the roll.
    var shotModes: [ShotMode] { state.paperFinder ? ShotMode.allCases : [.developer, .raw] }

    /// The standing choice, read against what this device offers: a JPEG
    /// setting kept from a Pro, or from before, opens the developer here.
    var shotMode: ShotMode { shotModes.contains(prefs.shotMode) ? prefs.shotMode : .developer }

    /// Where a new frame starts: the standing pair under a paper finder,
    /// where it was seen and where Settings asks for the Peak; the base
    /// print and Auto where the finder is the camera's picture, so nothing
    /// set out of sight shapes the shot.
    var startingPrint: Print {
        state.paperFinder ? prefs.standingPrint : Print(grade: .base, peak: .standing(.auto))
    }

    // MARK: the shot

    func capture() {
        guard let l = lens, !state.capturing else { return }
        var settings = develop
        let mode = shotMode
        let start = startingPrint
        settings.autoJpeg = mode == .jpeg
        Task {
            do {
                let still = try await session.capture()
                // What the finder applied as the frame was taken, for the match.
                let finder = still.finderMedian.flatMap { median in
                    tone?.paper.map { FinderSample(median: median, format: state.finderFormat, applied: log2(Double($0.gain))) }
                }
                let name = darkroom.submit(dng: still.dng, lens: l.label, capturedAt: Date(), settings: settings, print: start, developNow: mode != .raw, finder: finder)
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
