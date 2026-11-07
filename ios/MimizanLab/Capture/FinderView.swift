// MTKView that shows the latest camera frame. The frame arrives on the
// camera queue; the draw runs on the display link. Both touch `latest`
// under a lock. A BGRA frame goes through the `finder` kernel, an Apple
// Log frame (10-bit biplanar) through `finderLog` with the paper's table.
import CoreVideo
import Metal
import MetalKit
import os
import simd
import SwiftUI

/// Mirrors `FinderUniforms` in Finder.metal.
private struct FinderUniforms {
    var ycc: simd_float3x3
    var v: SIMD3<Float>
    var vR: SIMD3<Float>
    var vG: SIMD3<Float>
    var vB: SIMD3<Float>
    var gain: Float
    var plate: Float
    var lutLast: Float
    var zebra: Float
    var rotation: Float
    var mono: Float
}

final class FinderRenderer: NSObject, MTKViewDelegate {
    let device: MTLDevice
    private let queue: MTLCommandQueue
    private let picture: MTLComputePipelineState
    private let log: MTLComputePipelineState
    private var textureCache: CVMetalTextureCache?
    /// The latest frame, the tone with the paper's table as a buffer, and the turns.
    private let lock = OSAllocatedUnfairLock<(CVPixelBuffer?, FinderTone?, MTLBuffer?, Int)>(initialState: (nil, nil, nil, 0))
    /// Draws since the texture cache was last flushed (main thread, from `draw`).
    private var drawsSinceFlush = 0

    /// nil without a Metal device or the kernels in the bundle.
    static func make() -> FinderRenderer? {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue(),
              let lib = device.makeDefaultLibrary(),
              let picture = lib.makeFunction(name: "finder"),
              let log = lib.makeFunction(name: "finderLog"),
              let picturePipeline = try? device.makeComputePipelineState(function: picture),
              let logPipeline = try? device.makeComputePipelineState(function: log)
        else { return nil }
        return FinderRenderer(device: device, queue: queue, picture: picturePipeline, log: logPipeline)
    }

    private init(device: MTLDevice, queue: MTLCommandQueue, picture: MTLComputePipelineState, log: MTLComputePipelineState) {
        self.device = device
        self.queue = queue
        self.picture = picture
        self.log = log
        super.init()
        CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &textureCache)
    }

    func submit(frame: CVPixelBuffer) {
        lock.withLockUnchecked { $0.0 = frame }
    }

    func set(tone: FinderTone?) {
        // The table is too big for `setBytes`; it lives in a buffer of its own,
        // made here (the tone changes a few times a second at most).
        let lut: MTLBuffer? = tone?.paper.flatMap { p in
            p.lut.withUnsafeBytes { raw in
                raw.baseAddress.flatMap { device.makeBuffer(bytes: $0, length: raw.count, options: .storageModeShared) }
            }
        }
        lock.withLockUnchecked { $0.1 = tone; $0.2 = lut }
    }

    /// Quarter-turns clockwise the shader applies to the camera frame.
    func set(rotationQuarters: Int) {
        lock.withLockUnchecked { $0.3 = rotationQuarters }
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        let (frame, tone, lut, turns) = lock.withLockUnchecked { $0 }
        guard let frame, let tone, let cache = textureCache,
              let drawable = view.currentDrawable,
              let cmd = queue.makeCommandBuffer(), let enc = cmd.makeComputeCommandEncoder()
        else { return }
        var u = FinderUniforms(
            ycc: matrix_identity_float3x3, v: SIMD3(0, 1, 0), vR: SIMD3(1, 0, 0), vG: SIMD3(0, 1, 0), vB: SIMD3(0, 0, 1),
            gain: 1, plate: 0, lutLast: 0, zebra: tone.zebra, rotation: Float(turns), mono: tone.mono ? 1 : 0
        )
        // The textures and the pixel buffer behind them must outlive the
        // GPU's read: the camera may hand the next frame in before this
        // pass has run, and the buffer would go back to the pool while it
        // is sampled.
        var held: [CVMetalTexture] = []
        let pipeline: MTLComputePipelineState
        if CaptureSession.isLog(frame), let paper = tone.paper, let lut {
            guard let luma = texture(frame, plane: 0, .r16Unorm, cache), let chroma = texture(frame, plane: 1, .rg16Unorm, cache) else { enc.endEncoding(); cmd.commit(); return }
            held = [luma.0, chroma.0]
            u.ycc = YccMatrix.of(frame)
            u.v = paper.v
            u.gain = paper.gain
            u.lutLast = Float(paper.lut.count - 1)
            if let p = paper.plate { u.plate = 1; u.vR = p.0; u.vG = p.1; u.vB = p.2 }
            pipeline = log
            enc.setComputePipelineState(pipeline)
            enc.setTexture(luma.1, index: 0)
            enc.setTexture(chroma.1, index: 1)
            enc.setTexture(drawable.texture, index: 2)
            enc.setBuffer(lut, offset: 0, index: 1)
        } else {
            guard let src = texture(frame, plane: 0, .bgra8Unorm, cache) else { enc.endEncoding(); cmd.commit(); return }
            held = [src.0]
            pipeline = picture
            enc.setComputePipelineState(pipeline)
            enc.setTexture(src.1, index: 0)
            enc.setTexture(drawable.texture, index: 1)
        }
        enc.setBytes(&u, length: MemoryLayout<FinderUniforms>.stride, index: 0)
        let tw = pipeline.threadExecutionWidth
        let th = max(1, pipeline.maxTotalThreadsPerThreadgroup / tw)
        let groups = MTLSize(
            width: (drawable.texture.width + tw - 1) / tw,
            height: (drawable.texture.height + th - 1) / th,
            depth: 1
        )
        enc.dispatchThreadgroups(groups, threadsPerThreadgroup: MTLSize(width: tw, height: th, depth: 1))
        enc.endEncoding()
        cmd.present(drawable)
        cmd.addCompletedHandler { _ in
            _ = held
            _ = frame
        }
        cmd.commit()
        // Housekeeping the cache asks for now and then; every four seconds at 30 fps.
        drawsSinceFlush += 1
        if drawsSinceFlush >= 120 {
            drawsSinceFlush = 0
            CVMetalTextureCacheFlush(cache, 0)
        }
    }

    /// One plane of the frame as a texture, with the cache's handle that keeps it.
    private func texture(_ frame: CVPixelBuffer, plane: Int, _ format: MTLPixelFormat, _ cache: CVMetalTextureCache) -> (CVMetalTexture, MTLTexture)? {
        let planar = CVPixelBufferIsPlanar(frame)
        let w = planar ? CVPixelBufferGetWidthOfPlane(frame, plane) : CVPixelBufferGetWidth(frame)
        let h = planar ? CVPixelBufferGetHeightOfPlane(frame, plane) : CVPixelBufferGetHeight(frame)
        var cvTex: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, cache, frame, nil, format, w, h, plane, &cvTex)
        guard status == kCVReturnSuccess, let cvTex, let t = CVMetalTextureGetTexture(cvTex) else { return nil }
        return (cvTex, t)
    }
}

/// The live picture. `renderer` is shared with the camera, which feeds it frames.
struct FinderView: UIViewRepresentable {
    let renderer: FinderRenderer

    func makeUIView(context: Context) -> MTKView {
        let v = MTKView()
        // The drawable has to come from the device the kernel was built on.
        v.device = renderer.device
        v.delegate = renderer
        v.framebufferOnly = false
        v.colorPixelFormat = .bgra8Unorm
        v.preferredFramesPerSecond = 30
        v.isOpaque = true
        v.backgroundColor = .black
        return v
    }

    func updateUIView(_ uiView: MTKView, context: Context) {}
}
