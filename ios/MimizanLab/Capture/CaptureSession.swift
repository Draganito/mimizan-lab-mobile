// AVFoundation session: a BGRA video stream for the finder and the
// histogram, and a photo output that writes the still as a Bayer DNG.
// Nothing of the ISP's picture reaches the negative; the DNG is the
// measurement and goes to the core as the desktop would read it.
import AVFoundation
import CoreVideo
import Foundation
import Observation
import os
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

/// Exposure the user asked for. `nil` = automatic.
struct ExposureSettings: Equatable {
    var iso: Double?
    var seconds: Double?
    /// The EV slider on the viewfinder, stops of exposure bias.
    var bias: Double = 0

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
}

struct CaptureError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

final class CaptureSession: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let state = CaptureState()
    /// Every finder frame, on the camera queue: the renderer keeps the latest.
    var onFrame: ((CVPixelBuffer) -> Void)?

    private let queue = DispatchQueue(label: "ch.bojovic.mimizanlab.camera", qos: .userInteractive)
    private let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private var input: AVCaptureDeviceInput?
    private var device: AVCaptureDevice?
    private var lens: LensInfo?
    private var rawFormat: OSType = 0
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

    override init() {
        super.init()
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
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
        session.sessionPreset = .photo
        // The finder wants plain sRGB frames, not P3; set below, kept here.
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
        guard let fmt = photoOutput.availableRawPhotoPixelFormatTypes.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }) else {
            throw CaptureError(message: "\(lens.label) offers no Bayer RAW")
        }
        rawFormat = fmt
        self.input = input
        self.device = device
        self.lens = lens

        // The ISP's picture for the finder: no HDR fusion, the plain colour space.
        do {
            try device.lockForConfiguration()
            if device.activeFormat.isVideoHDRSupported { device.automaticallyAdjustsVideoHDREnabled = false; device.isVideoHDREnabled = false }
            device.activeColorSpace = .sRGB
            device.unlockForConfiguration()
        } catch {
            log.warning("finder configuration: \(error.localizedDescription)")
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
        applyExposure()
        let l = lens, f = flash
        Task { @MainActor in
            self.state.lens = l
            self.state.error = nil
            self.state.focusPoint = nil
            self.state.flash = f
        }
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
            } else {
                if d.isExposureModeSupported(.continuousAutoExposure) { d.exposureMode = .continuousAutoExposure }
                let bias = Float(min(max(exposure.bias, Double(d.minExposureTargetBias)), Double(d.maxExposureTargetBias)))
                d.setExposureTargetBias(bias, completionHandler: nil)
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

    /// One Bayer RAW still as a DNG. The flash fires only here.
    func capture() async throws -> Data {
        await MainActor.run { self.state.capturing = true }
        defer { Task { @MainActor in self.state.capturing = false } }
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            queue.async {
                guard self.device != nil, self.rawFormat != 0 else {
                    cont.resume(throwing: CaptureError(message: "camera not running"))
                    return
                }
                let settings = AVCapturePhotoSettings(rawPixelFormatType: self.rawFormat)
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
                    cont.resume(with: result)
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

    /// 64-bin histogram of the viewfinder's luminance. The weights are the
    /// Rec. 709 luma the monochrome finder uses (`Finder.metal`), scaled to
    /// 256: 0.2126, 0.7152, 0.0722 become 54, 183, 19.
    private func analyse(_ pb: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return }
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
        let m = Float(max(bins.max() ?? 1, 1))
        let out = bins.map { Float($0) / m }
        Task { @MainActor in self.state.histogram = out }
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
