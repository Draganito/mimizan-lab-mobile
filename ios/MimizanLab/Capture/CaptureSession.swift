// AVFoundation session: a video stream for the finder and the histogram,
// and a photo output that writes the still as a Bayer DNG. On a camera
// with Apple Log the stream is the log frame in 10-bit 4:2:0 or 4:2:2, which the
// finder reads as the negative; elsewhere it is the ISP's BGRA picture.
// Nothing of either reaches the negative; the DNG is the measurement and
// goes to the core as the desktop would read it.
import AVFoundation
import CoreVideo
import Foundation
import Observation
import os
import simd
import UIKit

private let log = Logger(subsystem: "ch.bojovic.mimizanlab", category: "camera")

enum FlashMode: Equatable {
    case off, auto, on

    var next: FlashMode {
        switch self {
        case .off: return .auto
        case .auto: return .on
        case .on: return .off
        }
    }
}

/// ISO and time set by hand, or both `nil` for the automatics. There is
/// no exposure bias: the camera spreads it over time and ISO as it
/// likes, with jumps at its limits, and the paper is corrected in the
/// developer instead, where the stops are what they say.
struct ExposureSettings: Equatable {
    var iso: Double?
    var seconds: Double?

    var manual: Bool { iso != nil && seconds != nil }
}

/// What the viewfinder shows about the running stream.
@MainActor
@Observable
final class CaptureState {
    /// Created by the session off the main actor; every field has its start value.
    nonisolated init() {}

    var lens: LensInfo?
    var iso: Int?
    var exposureSeconds: Double?
    var adjustingFocus = false
    /// Where a tap pinned focus and metering, as a fraction of the upright picture.
    var focusPoint: CGPoint?
    var flash: FlashMode = .off
    var capturing = false
    var error: String?
    /// 64 bins of the viewfinder's luminance, normalised to the largest.
    var histogram: [Float] = Array(repeating: 0, count: 64)
    /// Width / height of the finder picture as shown (after rotation).
    var frameAspect: CGFloat = 3.0 / 4.0
    /// Quarter-turns clockwise the shader applies when the camera refused to
    /// rotate the frames itself. 0 once the connection honours the angle.
    var rotationQuarters: Int = 0
    /// The stream is Apple Log: the finder is the paper. False on cameras
    /// without it (iPhones before the 15 Pro, the non-Pro models, iPads).
    var paperFinder = false
    /// The stream's format, for Settings: "Apple Log 1920×1440" or "camera picture".
    var finderFormat = "camera picture"
    /// Why the finder is the paper or the picture: which step of the
    /// Apple Log path passed or failed, with the counts behind it. For
    /// Settings, so a tester's screenshot says what a log line would.
    var finderNote = ""
    /// Whether `formats.json` was written for this lens, and when; the error if not.
    var formatsFile = ""
    /// The ISP's white balance gains (R, G, B) on an Apple Log stream, for
    /// the mix weights; nil on the camera's picture.
    var wbGains: [Double]?
}

struct CaptureError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// One still: the DNG, and what the finder measured as the frame was
/// taken (the median of its linear mix on the Log axis; nil on the
/// camera's picture or before the first analysed frame).
struct Still {
    var dng: Data
    var finderMedian: Double?
}

final class CaptureSession: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let state = CaptureState()
    /// Every finder frame, on the camera queue: the renderer keeps the latest.
    var onFrame: ((CVPixelBuffer) -> Void)?
    /// What the histogram applies to an Apple Log frame; set by the
    /// screen's owner, read on the camera queue.
    var tone: FinderTone? {
        get { toneLock.withLockUnchecked { $0 } }
        set { toneLock.withLockUnchecked { $0 = newValue } }
    }

    private let toneLock = OSAllocatedUnfairLock<FinderTone?>(initialState: nil)
    /// The median of the last analysed Log frame's linear mix, before the gain.
    private let medianLock = OSAllocatedUnfairLock<Double?>(initialState: nil)

    private let queue = DispatchQueue(label: "ch.bojovic.mimizanlab.camera", qos: .userInteractive)
    private let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private var input: AVCaptureDeviceInput?
    private var device: AVCaptureDevice?
    private var lens: LensInfo?
    private var rawFormat: OSType = 0
    /// The stream is Apple Log (camera queue's copy of `state.paperFinder`).
    private var paperStream = false
    /// Clockwise quarter-turns that bring the sensor buffer upright on the
    /// screen: the interface orientation, which is fixed to portrait on the
    /// phone and follows the iPad. The shader applies them; the video
    /// connection is kept at 0 so the frames are never rotated twice.
    private var shownQuarters = 1
    /// Clockwise quarter-turns the still is tagged with: the device
    /// orientation, which also moves on the portrait-locked phone. Only the
    /// DNG's orientation tag reads it.
    private var stillQuarters = 1
    private var observers: [NSKeyValueObservation] = []
    private var orientationObserver: (any NSObjectProtocol)?
    private var sessionObservers: [any NSObjectProtocol] = []
    private var exposure = ExposureSettings()
    private var flash: FlashMode = .off
    private var processor: PhotoProcessor?

    // Histogram: on the camera queue, at most every 66 ms.
    private var lastAnalysis = Date.distantPast
    private var grayTable: [Float] = []
    private var grayTableFor: PaperTone?

    override init() {
        super.init()
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: queue)
        observeSession()
    }

    deinit {
        for o in sessionObservers { NotificationCenter.default.removeObserver(o) }
        if let o = orientationObserver { NotificationCenter.default.removeObserver(o) }
    }

    // MARK: lifecycle

    /// Open `lens` with a Bayer RAW photo output. Throws when the lens
    /// offers no Bayer RAW (the caller tries the next one).
    func start(_ lens: LensInfo) async throws {
        let o = await MainActor.run { CaptureSession.currentOrientation() }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    self.shownQuarters = o.shown
                    self.stillQuarters = o.still
                    try self.configure(lens)
                    if !self.session.isRunning { self.session.startRunning() }
                    cont.resume()
                } catch {
                    self.teardown()
                    cont.resume(throwing: error)
                }
            }
        }
    }

    func stop() {
        queue.async {
            if self.session.isRunning { self.session.stopRunning() }
            self.teardown()
        }
    }

    /// Interruptions (a call, the iPad's Split View, system pressure) and
    /// runtime errors. The session keeps its configuration across an
    /// interruption and resumes on its own once it ends; a runtime error
    /// (media services reset) needs the lens opened again.
    private func observeSession() {
        let nc = NotificationCenter.default
        sessionObservers = [
            nc.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: nil) { [weak self] n in
                let raw = n.userInfo?[AVCaptureSessionInterruptionReasonKey] as? Int
                let reason = raw.flatMap { AVCaptureSession.InterruptionReason(rawValue: $0) }
                self?.queue.async { self?.interrupted(reason) }
            },
            nc.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: nil) { [weak self] _ in
                self?.queue.async { self?.interruptionEnded() }
            },
            nc.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil) { [weak self] n in
                let error = n.userInfo?[AVCaptureSessionErrorKey] as? NSError
                self?.queue.async { self?.runtimeError(error) }
            },
        ]
    }

    private func interrupted(_ reason: AVCaptureSession.InterruptionReason?) {
        guard lens != nil else { return }
        let why: String
        switch reason {
        case .videoDeviceNotAvailableInBackground: why = "the app is in the background"
        case .videoDeviceInUseByAnotherClient: why = "another app is using the camera"
        case .videoDeviceNotAvailableWithMultipleForegroundApps: why = "the camera is not available beside another app (open Mimizan Lab in full screen)"
        case .videoDeviceNotAvailableDueToSystemPressure: why = "the system is under pressure"
        case .audioDeviceInUseByAnotherClient: why = "the audio device is in use"
        default: why = "interrupted"
        }
        log.warning("session interrupted: \(why)")
        Task { @MainActor in self.state.error = "Camera paused: \(why)." }
    }

    private func interruptionEnded() {
        guard lens != nil else { return }
        log.info("session interruption ended")
        if !session.isRunning { session.startRunning() }
        Task { @MainActor in self.state.error = nil }
    }

    private func runtimeError(_ error: NSError?) {
        guard let lens else { return }
        log.error("session runtime error: \(error?.localizedDescription ?? "unknown")")
        // Open the same lens again; `configure` tears the old graph down first.
        do {
            try configure(lens)
            if !session.isRunning { session.startRunning() }
        } catch {
            teardown()
            let message = error.localizedDescription
            Task { @MainActor in self.state.error = message }
        }
    }

    private func configure(_ lens: LensInfo) throws {
        teardown()
        let device = lens.device
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        // The colour space is chosen below, not by the session.
        session.automaticallyConfiguresCaptureDeviceForWideColor = false
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw CaptureError(message: "\(lens.label): cannot add the camera") }
        session.addInput(input)
        guard session.canAddOutput(photoOutput) else { throw CaptureError(message: "\(lens.label): no photo output") }
        session.addOutput(photoOutput)
        guard session.canAddOutput(videoOutput) else { throw CaptureError(message: "\(lens.label): no video output") }
        session.addOutput(videoOutput)
        // Bayer RAW, not ProRAW: the mosaic as the sensor measured it.
        if photoOutput.isAppleProRAWSupported { photoOutput.isAppleProRAWEnabled = false }
        let bayer = { self.photoOutput.availableRawPhotoPixelFormatTypes.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }) }

        // The stream: Apple Log where the camera has it and the still keeps
        // its Bayer RAW in that format, else the ISP's picture. Nothing of
        // this is known without the device in hand, so each step is checked
        // and the picture is the fallback at every one; the step that
        // failed is written down for Settings and `formats.json`.
        let logCapable = device.formats.filter { $0.supportedColorSpaces.contains(.appleLog) }
        let logPhoto = logCapable.filter { $0.isHighPhotoQualitySupported }.count
        let counts = "\(logCapable.count) Apple Log format\(logCapable.count == 1 ? "" : "s") on this camera, \(logPhoto) of them photo formats, \(device.formats.count) formats in all"
        var logFormat = CaptureSession.logFormat(of: device)
        var note = logFormat == nil ? "No Apple Log format in 10-bit 4:2:0 or 4:2:2 at 30 fps: \(counts)." : ""
        var logRawTypes: [String]?
        if let f = logFormat {
            session.sessionPreset = .inputPriority
            do {
                try device.lockForConfiguration()
                device.activeFormat = f
                device.activeColorSpace = .appleLog
                device.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
                device.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
                device.unlockForConfiguration()
                // With a format set by hand the still's size is ours to ask for.
                if let dims = f.supportedMaxPhotoDimensions.max(by: { Int($0.width) * Int($0.height) < Int($1.width) * Int($1.height) }) {
                    photoOutput.maxPhotoDimensions = dims
                }
            } catch {
                log.warning("Apple Log format: \(error.localizedDescription)")
                note = "The Apple Log format was refused: \(error.localizedDescription). \(counts)."
                logFormat = nil
            }
            if logFormat != nil {
                let rawThere = photoOutput.availableRawPhotoPixelFormatTypes.map(FormatDump.fourcc)
                logRawTypes = rawThere
                if !videoOutput.availableVideoPixelFormatTypes.contains(CMFormatDescriptionGetMediaSubType(f.formatDescription)) {
                    log.warning("Apple Log format offers no 10-bit stream of its own subtype")
                    note = "No 10-bit stream in the Apple Log format's own subtype (\(FormatDump.fourcc(CMFormatDescriptionGetMediaSubType(f.formatDescription)))). \(counts)."
                    logFormat = nil
                } else if bayer() == nil {
                    log.warning("Apple Log format offers no Bayer RAW still")
                    note = "No Bayer RAW still in the Apple Log format (RAW types there: \(rawThere.isEmpty ? "none" : rawThere.joined(separator: ", "))). \(counts)."
                    logFormat = nil
                } else {
                    note = "Apple Log with a Bayer RAW still. \(counts)."
                }
            }
        }
        if logFormat == nil {
            // The ISP's picture for the finder: no HDR fusion, the plain colour space.
            session.sessionPreset = .photo
            do {
                try device.lockForConfiguration()
                if device.activeFormat.isVideoHDRSupported { device.automaticallyAdjustsVideoHDREnabled = false; device.isVideoHDREnabled = false }
                device.activeColorSpace = .sRGB
                device.unlockForConfiguration()
            } catch {
                log.warning("finder configuration: \(error.localizedDescription)")
            }
        }
        // The log stream in the format's own subtype: 4:2:0 or 4:2:2, both
        // 10-bit biplanar video range; the GPU and the analysis read either.
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: logFormat.map { CMFormatDescriptionGetMediaSubType($0.formatDescription) } ?? kCVPixelFormatType_32BGRA,
        ]
        guard let fmt = bayer() else { throw CaptureError(message: "\(lens.label) offers no Bayer RAW") }
        rawFormat = fmt
        self.input = input
        self.device = device
        self.lens = lens
        let d = device.activeFormat.formatDescription.dimensions
        let formatLabel = logFormat != nil ? "Apple Log \(d.width)×\(d.height)" : "camera picture"
        log.info("\(lens.label): finder \(formatLabel), RAW \(FormatDump.fourcc(fmt)); \(note)")
        let paper = logFormat != nil
        paperStream = paper
        Task { @MainActor in
            self.state.paperFinder = paper
            self.state.finderFormat = formatLabel
            self.state.finderNote = note
            if !paper { self.state.wbGains = nil }
        }
        medianLock.withLock { $0 = nil }
        // The dump after the configuration has been committed (the output's
        // types follow the commit), on this queue, then written off it.
        let label = lens.label
        queue.async { [weak self] in
            guard let self, let device = self.device, self.lens?.key == lens.key else { return }
            let a = device.activeFormat.formatDescription.dimensions
            let dump = LensFormats(
                at: ISO8601DateFormatter().string(from: Date()), device: FinderMatchLog.deviceModel, ios: FinderMatchLog.osVersion,
                lens: label, outcome: "\(formatLabel): \(note)",
                activeFormat: "\(a.width)×\(a.height) \(FormatDump.fourcc(CMFormatDescriptionGetMediaSubType(device.activeFormat.formatDescription)))",
                rawTypes: self.photoOutput.availableRawPhotoPixelFormatTypes.map(FormatDump.fourcc),
                videoTypes: self.videoOutput.availableVideoPixelFormatTypes.map(FormatDump.fourcc),
                logRawTypes: logRawTypes, formats: device.formats.map(FormatRecord.init)
            )
            DispatchQueue.global(qos: .utility).async {
                let status = FormatDump.write(dump)
                Task { @MainActor in self.state.formatsFile = status }
            }
        }

        // The app subscribes to device orientation notifications in
        // `MimizanLabApp.init`; the interface may turn a moment after the
        // device does, so the screen also calls `refreshOrientation` when
        // its size changes.
        if orientationObserver == nil {
            orientationObserver = NotificationCenter.default.addObserver(
                forName: UIDevice.orientationDidChangeNotification, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor in self?.refreshOrientation() }
            }
        }
        applyOrientation()
        observers = [
            device.observe(\.iso, options: [.new]) { [weak self] d, _ in
                let iso = Int(d.iso.rounded())
                Task { @MainActor in self?.state.iso = iso }
            },
            device.observe(\.exposureDuration, options: [.new]) { [weak self] d, _ in
                // An invalid CMTime reads as NaN: keep the last good value then.
                let s = d.exposureDuration.seconds
                guard s.isFinite, s > 0 else { return }
                Task { @MainActor in self?.state.exposureSeconds = s }
            },
            device.observe(\.isAdjustingFocus, options: [.new]) { [weak self] d, _ in
                let a = d.isAdjustingFocus
                Task { @MainActor in self?.state.adjustingFocus = a }
            },
        ]
        if paper {
            // The Log frame is balanced by the ISP; the still's mix weights
            // for the same balance come from the core with these gains.
            observers.append(device.observe(\.deviceWhiteBalanceGains, options: [.initial, .new]) { [weak self] d, _ in
                let g = d.deviceWhiteBalanceGains
                guard g.redGain.isFinite, g.greenGain.isFinite, g.blueGain.isFinite, g.greenGain > 0 else { return }
                let gains = [Double(g.redGain), Double(g.greenGain), Double(g.blueGain)]
                Task { @MainActor in
                    guard let s = self?.state else { return }
                    if let old = s.wbGains, zip(old, gains).allSatisfy({ abs($0 - $1) < 1e-3 }) { return }
                    s.wbGains = gains
                }
            })
        }
        applyExposure()
        let l = lens, f = flash
        Task { @MainActor in
            self.state.lens = l
            self.state.error = nil
            self.state.focusPoint = nil
            self.state.flash = f
        }
    }

    /// The two 10-bit biplanar video-range subtypes Apple Log comes in:
    /// 4:2:0 (`x420`) and 4:2:2 (`x422`, what the iPhone 16 Pro offers on
    /// every one of its log formats).
    static let logSubtypes: Set<OSType> = [kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange, kCVPixelFormatType_422YpCbCr10BiPlanarVideoRange]

    /// True for a frame in one of `logSubtypes`.
    static func isLog(_ pb: CVPixelBuffer) -> Bool { logSubtypes.contains(CVPixelBufferGetPixelFormatType(pb)) }

    /// A format the finder reads as the negative: Apple Log in 10-bit
    /// 4:2:0 or 4:2:2 at 30 fps, in the still's 4:3 framing when the
    /// camera offers one (else the widest the log comes in), the largest
    /// up to about 2 MP: the finder's own size, and what the GPU pass and
    /// the histogram walk every frame. nil without Apple Log.
    static func logFormat(of device: AVCaptureDevice) -> AVCaptureDevice.Format? {
        let logs = device.formats.filter { f in
            f.supportedColorSpaces.contains(.appleLog)
                && logSubtypes.contains(CMFormatDescriptionGetMediaSubType(f.formatDescription))
                && f.videoSupportedFrameRateRanges.contains { $0.minFrameRate <= 30 && $0.maxFrameRate >= 30 }
        }
        let area = { (f: AVCaptureDevice.Format) -> Int in
            let d = f.formatDescription.dimensions
            return Int(d.width) * Int(d.height)
        }
        let fourThree = logs.filter { f in
            let d = f.formatDescription.dimensions
            return abs(Double(d.width) / Double(d.height) - 4.0 / 3.0) < 0.02
        }
        let pool = fourThree.isEmpty ? logs : fourThree
        return pool.filter { area($0) <= 2100 * 1600 }.max { area($0) < area($1) } ?? pool.min { area($0) < area($1) }
    }

    private func teardown() {
        observers = []
        session.beginConfiguration()
        for i in session.inputs { session.removeInput(i) }
        for o in session.outputs { session.removeOutput(o) }
        session.commitConfiguration()
        input = nil
        device = nil
        lens = nil
    }

    /// Clockwise quarter-turns that stand the back camera's sensor buffer
    /// upright for a device held this way. The buffer arrives in the
    /// sensor's own orientation (the video connection is pinned at 0
    /// below). `.landscapeLeft` needs none: that is the orientation the
    /// sensor already scans in. Flat or unknown is nil: the interface
    /// decides then.
    static func quarters(for o: UIDeviceOrientation) -> Int? {
        switch o {
        case .landscapeLeft: return 0
        case .portrait: return 1
        case .landscapeRight: return 2
        case .portraitUpsideDown: return 3
        default: return nil
        }
    }

    /// The same for the interface: what the screen shows has to be
    /// rotated against the screen, and the screen is the interface. The
    /// interface landscapes are mirrored against the device's.
    static func quarters(for o: UIInterfaceOrientation) -> Int {
        switch o {
        case .landscapeRight: return 0
        case .portraitUpsideDown: return 3
        case .landscapeLeft: return 2
        default: return 1
        }
    }

    /// `shown`: the shader's turns, from the interface. `still`: the turns
    /// the DNG is tagged with, from the device (so a landscape frame taken
    /// on the portrait-locked phone is a landscape frame), falling back to
    /// the interface while the device lies flat.
    @MainActor
    static func currentOrientation() -> (shown: Int, still: Int) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let shown = scene.map { CaptureSession.quarters(for: $0.interfaceOrientation) } ?? 1
        let still = CaptureSession.quarters(for: UIDevice.current.orientation) ?? shown
        return (shown, still)
    }

    /// Read both orientations again and apply them. Called on a device
    /// orientation change and when the screen's size changes (the iPad's
    /// interface turning).
    @MainActor
    func refreshOrientation() {
        let o = CaptureSession.currentOrientation()
        queue.async {
            self.stillQuarters = o.still
            guard self.shownQuarters != o.shown else { return }
            self.shownQuarters = o.shown
            self.applyOrientation()
        }
    }

    private func applyOrientation() {
        // Keep the frames unrotated. Turning them here and in the shader
        // would rotate twice, and this connection does not reliably honour
        // the angle on every device.
        if let c = videoOutput.connection(with: .video), c.isVideoRotationAngleSupported(0) {
            c.videoRotationAngle = 0
        }
        let d = device?.activeFormat.formatDescription.dimensions
        let w = CGFloat(d?.width ?? 4), h = CGFloat(d?.height ?? 3)
        let turned = shownQuarters % 2 != 0
        let aspect = turned ? h / w : w / h
        let q = shownQuarters
        Task { @MainActor in
            self.state.frameAspect = aspect
            self.state.rotationQuarters = q
        }
    }

    // MARK: controls

    func setExposure(_ e: ExposureSettings) {
        queue.async {
            self.exposure = e
            self.applyExposure()
        }
    }

    private func applyExposure() {
        guard let d = device else { return }
        do {
            try d.lockForConfiguration()
            defer { d.unlockForConfiguration() }
            if exposure.manual, let iso = exposure.iso, let s = exposure.seconds, d.isExposureModeSupported(.custom) {
                let f = d.activeFormat
                let isoC = Float(min(max(iso, Double(f.minISO)), Double(f.maxISO)))
                let secC = min(max(s, f.minExposureDuration.seconds), f.maxExposureDuration.seconds)
                d.setExposureModeCustom(duration: CMTime(seconds: secC, preferredTimescale: 1_000_000_000), iso: isoC, completionHandler: nil)
            } else if d.isExposureModeSupported(.continuousAutoExposure) {
                d.exposureMode = .continuousAutoExposure
            }
        } catch {
            log.warning("exposure: \(error.localizedDescription)")
        }
    }

    func setFlash(_ m: FlashMode) {
        queue.async { self.flash = m }
        Task { @MainActor in self.state.flash = m }
    }

    /// Continuous until a tap pins focus and metering there (locked once converged).
    func focus(at p: CGPoint) {
        queue.async {
            guard let d = self.device else { return }
            let dp = CaptureSession.devicePoint(p, shaderQuarters: self.shownQuarters)
            do {
                try d.lockForConfiguration()
                defer { d.unlockForConfiguration() }
                if d.isFocusPointOfInterestSupported { d.focusPointOfInterest = dp }
                if d.isFocusModeSupported(.autoFocus) { d.focusMode = .autoFocus }
                if d.isExposurePointOfInterestSupported { d.exposurePointOfInterest = dp }
                if !self.exposure.manual, d.isExposureModeSupported(.autoExpose) { d.exposureMode = .autoExpose }
            } catch {
                log.warning("focus: \(error.localizedDescription)")
            }
            Task { @MainActor in self.state.focusPoint = p }
        }
    }

    func clearFocus() {
        queue.async {
            guard let d = self.device else { return }
            do {
                try d.lockForConfiguration()
                defer { d.unlockForConfiguration() }
                let c = CGPoint(x: 0.5, y: 0.5)
                if d.isFocusPointOfInterestSupported { d.focusPointOfInterest = c }
                if d.isFocusModeSupported(.continuousAutoFocus) { d.focusMode = .continuousAutoFocus }
                if d.isExposurePointOfInterestSupported { d.exposurePointOfInterest = c }
            } catch {
                log.warning("focus: \(error.localizedDescription)")
            }
            self.applyExposure()
            Task { @MainActor in self.state.focusPoint = nil }
        }
    }

    /// A point of the upright finder picture (0..1) in the sensor buffer,
    /// undoing the clockwise quarter-turns the shader applied. The same
    /// transform as `Finder.metal`, so a tap focuses where it landed.
    static func devicePoint(_ p: CGPoint, shaderQuarters q: Int) -> CGPoint {
        switch (q % 4 + 4) % 4 {
        case 1: return CGPoint(x: p.y, y: 1 - p.x)
        case 2: return CGPoint(x: 1 - p.x, y: 1 - p.y)
        case 3: return CGPoint(x: 1 - p.y, y: p.x)
        default: return p
        }
    }

    // MARK: the still

    /// One Bayer RAW still as a DNG, with the finder's measurement of the
    /// moment. The flash fires only here.
    func capture() async throws -> Still {
        await MainActor.run { self.state.capturing = true }
        defer { Task { @MainActor in self.state.capturing = false } }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Still, Error>) in
            queue.async {
                guard self.device != nil, self.rawFormat != 0 else {
                    cont.resume(throwing: CaptureError(message: "camera not running"))
                    return
                }
                let finderMedian = self.medianLock.withLock { $0 }
                let settings = AVCapturePhotoSettings(rawPixelFormatType: self.rawFormat)
                // The photo preset sizes the still itself; the Log format is told.
                if self.paperStream { settings.maxPhotoDimensions = self.photoOutput.maxPhotoDimensions }
                // With the exposure set by hand the automatics cannot decide,
                // so Auto does not fire; On fires once, as on Android. Whether
                // AVFoundation fires the flash under a custom exposure is to
                // be verified on a device.
                let wanted: AVCaptureDevice.FlashMode
                switch self.flash {
                case .off: wanted = .off
                case .auto: wanted = self.exposure.manual ? .off : .auto
                case .on: wanted = .on
                }
                settings.flashMode = self.photoOutput.supportedFlashModes.contains(wanted) ? wanted : .off
                // The DNG's orientation tag follows the device, not the screen.
                if let c = self.photoOutput.connection(with: .video) {
                    let a = CGFloat(self.stillQuarters * 90)
                    if c.isVideoRotationAngleSupported(a) { c.videoRotationAngle = a }
                }
                let processor = PhotoProcessor { [weak self] result in
                    self?.queue.async { self?.processor = nil }
                    cont.resume(with: result.map { Still(dng: $0, finderMedian: finderMedian) })
                }
                self.processor = processor
                self.photoOutput.capturePhoto(with: settings, delegate: processor)
            }
        }
    }

    // MARK: finder frames

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        onFrame?(pb)
        let now = Date()
        if now.timeIntervalSince(lastAnalysis) >= 0.066 {
            lastAnalysis = now
            analyse(pb)
        }
    }

    /// 64-bin histogram of the finder picture: the negative's display
    /// values on an Apple Log frame, the ISP's luma on the picture.
    private func analyse(_ pb: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        let bins: [Int]
        if CaptureSession.isLog(pb) {
            guard let paper = tone?.paper else { return }
            if grayTableFor != paper { grayTable = paper.grayTable(); grayTableFor = paper }
            let (b, median) = CaptureSession.analyseLog(pb, paper: paper, grayTable: grayTable)
            bins = b
            medianLock.withLock { $0 = median }
        } else {
            bins = CaptureSession.analysePicture(pb)
        }
        guard !bins.isEmpty else { return }
        let m = Float(max(bins.max() ?? 1, 1))
        let out = bins.map { Float($0) / m }
        Task { @MainActor in self.state.histogram = out }
    }

    /// The ISP's luma. The weights are the Rec. 709 luma the monochrome
    /// finder uses (`Finder.metal`), scaled to 256: 0.2126, 0.7152, 0.0722
    /// become 54, 183, 19.
    private static func analysePicture(_ pb: CVPixelBuffer) -> [Int] {
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return [] }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        let stride = CVPixelBufferGetBytesPerRow(pb)
        let p = base.assumingMemoryBound(to: UInt8.self)
        var bins = [Int](repeating: 0, count: 64)
        let step = max(1, max(w, h) / 240)
        var y = 0
        while y < h {
            let row = y * stride
            var x = 0
            while x < w {
                let i = row + x * 4
                let luma = (Int(p[i + 2]) * 54 + Int(p[i + 1]) * 183 + Int(p[i]) * 19) >> 8
                bins[min(luma >> 2, 63)] += 1
                x += step
            }
            y += step
        }
        return bins
    }

    /// The negative's display values on an Apple Log frame, the kernel's
    /// arithmetic on a grid of about 240 sites across, and the median of
    /// the linear mix before the gain (nil when no site is left between
    /// near-black and the clip, the same cut the core's `mixMedian`
    /// makes). The median is taken from a histogram in 1/32 stop.
    private static func analyseLog(_ pb: CVPixelBuffer, paper: PaperTone, grayTable: [Float]) -> ([Int], Double?) {
        guard let lumaBase = CVPixelBufferGetBaseAddressOfPlane(pb, 0), let chromaBase = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else { return ([], nil) }
        let w = CVPixelBufferGetWidthOfPlane(pb, 0), h = CVPixelBufferGetHeightOfPlane(pb, 0)
        let cw = CVPixelBufferGetWidthOfPlane(pb, 1), ch = CVPixelBufferGetHeightOfPlane(pb, 1)
        let lumaStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0) / 2
        let chromaStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1) / 2
        let luma = lumaBase.assumingMemoryBound(to: UInt16.self)
        let chroma = chromaBase.assumingMemoryBound(to: UInt16.self)
        let ycc = YccMatrix.of(pb)
        var bins = [Int](repeating: 0, count: 64)
        // log2 of the mix from 2^-16 to 2^6 in 1/32 stop.
        let stopsLow: Float = -16, stopsPerBin: Float = 1.0 / 32
        var stops = [Int](repeating: 0, count: 22 * 32)
        var counted = 0
        let step = max(1, max(w, h) / 240)
        let scale = Float(grayTable.count - 1)
        // The codes sit in the high 10 of 16 bits: code = sample >> 6.
        let yScale: Float = 1 / (876 * 64), cScale: Float = 1 / (896 * 64)
        let yBlack: Float = 64 * 64, cMid: Float = 512 * 64
        // The chroma plane is half the width (4:2:2) or half of both (4:2:0).
        var y = 0
        while y < h {
            let row = y * lumaStride
            let crow = min(y * ch / h, ch - 1) * chromaStride
            var x = 0
            while x < w {
                let yy = (Float(luma[row + x]) - yBlack) * yScale
                let ci = crow + min(x * cw / w, cw - 1) * 2
                let cb = (Float(chroma[ci]) - cMid) * cScale
                let cr = (Float(chroma[ci + 1]) - cMid) * cScale
                let enc = ycc * SIMD3<Float>(yy, cb, cr)
                let lin = SIMD3<Float>(AppleLog.linear(enc.x), AppleLog.linear(enc.y), AppleLog.linear(enc.z))
                let mix = simd_dot(lin, paper.v)
                let g = min(max(mix * paper.gain, 0), 1)
                let d = grayTable[min(Int(g * scale + 0.5), grayTable.count - 1)]
                bins[min(max(Int(d * 63.999), 0), 63)] += 1
                if g > 0.002, g < 0.98 {
                    let s = (log2(mix) - stopsLow) / stopsPerBin
                    stops[min(max(Int(s), 0), stops.count - 1)] += 1
                    counted += 1
                }
                x += step
            }
            y += step
        }
        guard counted > 0 else { return (bins, nil) }
        var seen = 0, bin = 0
        while bin < stops.count {
            seen += stops[bin]
            if seen * 2 >= counted { break }
            bin += 1
        }
        let median = exp2(Double(stopsLow) + (Double(bin) + 0.5) * Double(stopsPerBin))
        return (bins, median)
    }
}

/// Keeps one capture's delegate alive until the DNG is in hand.
private final class PhotoProcessor: NSObject, AVCapturePhotoCaptureDelegate {
    private var done: ((Result<Data, Error>) -> Void)?
    private var data: Data?

    init(done: @escaping (Result<Data, Error>) -> Void) {
        self.done = done
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error {
            finish(.failure(error))
            return
        }
        guard photo.isRawPhoto else { return }
        if let d = photo.fileDataRepresentation() {
            data = d
        } else {
            finish(.failure(CaptureError(message: "the camera gave no DNG")))
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        if let error {
            finish(.failure(error))
        } else if let data {
            finish(.success(data))
        } else {
            finish(.failure(CaptureError(message: "the camera gave no RAW frame")))
        }
    }

    private func finish(_ r: Result<Data, Error>) {
        done?(r)
        done = nil
    }
}
