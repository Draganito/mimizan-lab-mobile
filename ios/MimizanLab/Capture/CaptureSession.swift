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
    /// 64 bins of the viewfinder picture, normalised to the largest.
    var histogram: [Float] = Array(repeating: 0, count: 64)
    /// Width / height of the finder picture as shown (after rotation).
    var frameAspect: CGFloat = 3.0 / 4.0
    /// Quarter-turns clockwise the shader applies when the camera refused to
    /// rotate the frames itself. 0 once the connection honours the angle.
    var rotationQuarters: Int = 0
    /// The camera's white-balance gains R, G, B of the moment.
    var wbGains: [Double]?
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
    /// The rotation coordinator only follows the device while the app
    /// subscribes to orientation notifications; started once, with the session.
    private static let orientation = {
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
    }()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private var input: AVCaptureDeviceInput?
    private var device: AVCaptureDevice?
    private var lens: LensInfo?
    private var rawFormat: OSType = 0
    /// Clockwise quarter-turns that bring the sensor buffer upright for the
    /// device's current orientation. The shader applies them; the video
    /// connection is kept at 0 so the frames are never rotated twice.
    private var shownQuarters = 1
    private var observers: [NSKeyValueObservation] = []
    private var orientationObserver: (any NSObjectProtocol)?
    private var exposure = ExposureSettings()
    private var flash: FlashMode = .off
    private var processor: PhotoProcessor?

    // Histogram: on the camera queue, at most every 66 ms.
    private let toneLock = OSAllocatedUnfairLock<FinderTone?>(initialState: nil)
    private var lastAnalysis = Date.distantPast
    private var grayTable: [Float] = []
    private var grayTableFor: FinderTone?
    private static let srgbLinear: [Float] = (0..<256).map { i in
        let c = Float(i) / 255
        return c <= 0.04045 ? c / 12.92 : powf((c + 0.055) / 1.055, 2.4)
    }

    /// Set by the screen's owner; the histogram follows it.
    var tone: FinderTone? {
        get { toneLock.withLockUnchecked { $0 } }
        set { toneLock.withLockUnchecked { $0 = newValue } }
    }

    override init() {
        super.init()
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: queue)
    }

    // MARK: lifecycle

    /// Open `lens` with a Bayer RAW photo output. Throws when the lens
    /// offers no Bayer RAW (the caller tries the next one).
    func start(_ lens: LensInfo) async throws {
        let q = await MainActor.run { CaptureSession.currentQuarters() }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    self.shownQuarters = q
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

        _ = CaptureSession.orientation
        if orientationObserver == nil {
            orientationObserver = NotificationCenter.default.addObserver(
                forName: UIDevice.orientationDidChangeNotification, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor in
                    let q = CaptureSession.currentQuarters()
                    self?.queue.async {
                        self?.shownQuarters = q
                        self?.applyOrientation()
                    }
                }
            }
        }
        applyOrientation()
        observers = [
            device.observe(\.iso, options: [.new]) { [weak self] d, _ in
                let iso = Int(d.iso.rounded())
                Task { @MainActor in self?.state.iso = iso }
            },
            device.observe(\.exposureDuration, options: [.new]) { [weak self] d, _ in
                let s = d.exposureDuration.seconds
                Task { @MainActor in self?.state.exposureSeconds = s }
            },
            device.observe(\.isAdjustingFocus, options: [.new]) { [weak self] d, _ in
                let a = d.isAdjustingFocus
                Task { @MainActor in self?.state.adjustingFocus = a }
            },
            device.observe(\.deviceWhiteBalanceGains, options: [.new]) { [weak self] d, _ in
                let g = d.deviceWhiteBalanceGains
                guard g.redGain.isFinite, g.greenGain.isFinite, g.blueGain.isFinite, g.greenGain > 0 else { return }
                let gains = [Double(g.redGain), Double(g.greenGain), Double(g.blueGain)]
                Task { @MainActor in
                    guard let s = self?.state else { return }
                    if let old = s.wbGains, zip(old, gains).allSatisfy({ abs($0 - $1) < 1e-3 }) { return }
                    s.wbGains = gains
                }
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
    /// upright. The buffer arrives in the sensor's own orientation (the
    /// video connection is pinned at 0 below), so this is the whole rotation.
    /// `.landscapeLeft` needs none: that is the orientation the sensor
    /// already scans in.
    static func quarters(for o: UIDeviceOrientation) -> Int? {
        switch o {
        case .landscapeLeft: return 0
        case .portrait, .faceUp: return 1
        case .landscapeRight: return 2
        case .portraitUpsideDown: return 3
        default: return nil
        }
    }

    /// The quarter-turns to show now. The device orientation wins once the
    /// device has reported one; before the first movement it reads
    /// `.unknown`, so the window's orientation fills in. The interface
    /// landscapes are mirrored against the device's.
    @MainActor
    static func currentQuarters() -> Int {
        if let q = quarters(for: UIDevice.current.orientation) { return q }
        let iface = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.interfaceOrientation
        switch iface {
        case .landscapeRight: return 0
        case .portraitUpsideDown: return 3
        case .landscapeLeft: return 2
        default: return 1
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
                let wanted: AVCaptureDevice.FlashMode
                switch self.flash {
                case .off: wanted = .off
                case .auto: wanted = .auto
                case .on: wanted = .on
                }
                settings.flashMode = (!self.exposure.manual && self.photoOutput.supportedFlashModes.contains(wanted)) ? wanted : .off
                if let c = self.photoOutput.connection(with: .video) {
                    let a = CGFloat(self.shownQuarters * 90)
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

    /// 64-bin histogram of the finder picture: the negative's display
    /// values when a tone is set, else the ISP's luma.
    private func analyse(_ pb: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        let stride = CVPixelBufferGetBytesPerRow(pb)
        let p = base.assumingMemoryBound(to: UInt8.self)
        var bins = [Int](repeating: 0, count: 64)
        let step = max(1, max(w, h) / 240)
        let t = tone
        if let t {
            if grayTableFor != t { grayTable = t.grayTable(); grayTableFor = t }
            let lin = CaptureSession.srgbLinear
            let v = t.v
            let table = grayTable
            let scale = Float(table.count - 1)
            var y = 0
            while y < h {
                let row = y * stride
                var x = 0
                while x < w {
                    let i = row + x * 4
                    let b = lin[Int(p[i])], g = lin[Int(p[i + 1])], r = lin[Int(p[i + 2])]
                    let l = min(max(v.x * r + v.y * g + v.z * b, 0), 1)
                    let d = table[Int(l * scale + 0.5)]
                    bins[min(max(Int(d * 63.999), 0), 63)] += 1
                    x += step
                }
                y += step
            }
        } else {
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
