// MTKView that shows the latest camera frame. The frame arrives on the
// camera queue; the draw runs on the display link. Both touch `latest`
// under a lock.
import CoreVideo
import Metal
import MetalKit
import os
import SwiftUI

/// Mirrors `FinderUniforms` in Finder.metal.
private struct FinderUniforms {
    var mono: Float
    var zebra: Float
    var rotation: Float
    var pad: Float = 0
}

final class FinderRenderer: NSObject, MTKViewDelegate {
    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let pipeline: MTLComputePipelineState
    private var textureCache: CVMetalTextureCache?
    private let lock = OSAllocatedUnfairLock<(CVPixelBuffer?, FinderTone?, Int)>(initialState: (nil, nil, 0))

    /// nil without a Metal device or the `finder` kernel in the bundle.
    static func make() -> FinderRenderer? {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue(),
              let lib = device.makeDefaultLibrary(),
              let fn = lib.makeFunction(name: "finder"),
              let pipeline = try? device.makeComputePipelineState(function: fn)
        else { return nil }
        return FinderRenderer(device: device, queue: queue, pipeline: pipeline)
    }

    private init(device: MTLDevice, queue: MTLCommandQueue, pipeline: MTLComputePipelineState) {
        self.device = device
        self.queue = queue
        self.pipeline = pipeline
        super.init()
        CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &textureCache)
    }

    func submit(frame: CVPixelBuffer) {
        lock.withLockUnchecked { $0.0 = frame }
    }

    func set(tone: FinderTone?) {
        lock.withLockUnchecked { $0.1 = tone }
    }

    /// Quarter-turns clockwise the shader applies to the camera frame.
    func set(rotationQuarters: Int) {
        lock.withLockUnchecked { $0.2 = rotationQuarters }
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        let (frame, tone, turns) = lock.withLockUnchecked { $0 }
        guard let frame, let tone, let cache = textureCache,
              let drawable = view.currentDrawable
        else { return }
        let w = CVPixelBufferGetWidth(frame), h = CVPixelBufferGetHeight(frame)
        var cvTex: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, cache, frame, nil, .bgra8Unorm, w, h, 0, &cvTex
        )
        guard status == kCVReturnSuccess, let cvTex, let src = CVMetalTextureGetTexture(cvTex) else { return }

        var u = FinderUniforms(mono: tone.mono ? 1 : 0, zebra: tone.zebra, rotation: Float(turns))
        guard let cmd = queue.makeCommandBuffer(), let enc = cmd.makeComputeCommandEncoder() else { return }
        enc.setComputePipelineState(pipeline)
        enc.setTexture(src, index: 0)
        enc.setTexture(drawable.texture, index: 1)
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
        cmd.commit()
    }
}

/// The live picture. `renderer` is shared with the camera, which feeds it frames.
struct FinderView: UIViewRepresentable {
    let renderer: FinderRenderer

    func makeUIView(context: Context) -> MTKView {
        let v = MTKView()
        v.device = MTLCreateSystemDefaultDevice()
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
