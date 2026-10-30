// The viewfinder screen: the picture fills it. One line names the process
// and the exposure, the shutter sits below, and the aids live on the
// picture itself. Paper, filter and separation belong to the open frame.
import MimizanCore
import SwiftUI

struct CaptureView: View {
    @Bindable var vm: CaptureViewModel
    let onOpenShot: (String) -> Void
    let onOpenRoll: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var exposureOpen = false

    var body: some View {
        let state = vm.state
        let lens = vm.lens
        let develop = vm.develop
        let shots = vm.darkroom.shots
        let exposure = vm.exposure
        VStack(spacing: 0) {
            // The finder fills the height the line and the shutter leave,
            // as wide as the picture's own aspect allows.
            GeometryReader { geo in
                let aspect = state.frameAspect
                let h = min(geo.size.height, geo.size.width / aspect)
                let w = h * aspect
                ZStack {
                    finder(size: CGSize(width: w, height: h))
                }
                .frame(width: w, height: h)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            HStack(spacing: 16) {
                Button(develop.isPlate ? "Color" : "Mono") {
                    vm.setProcess(develop.isPlate ? .mono : .plate)
                }
                .font(.subheadline.weight(.semibold))
                Spacer()
                Button { exposureOpen.toggle() } label: {
                    Text(exposureLine(state))
                        .font(.subheadline.monospaced())
                }
                .buttonStyle(.plain)
                if lens?.hasFlash == true {
                    Button(action: vm.cycleFlash) {
                        Image(systemName: state.flash == .off ? "bolt.slash" : state.flash == .auto ? "bolt.badge.automatic" : "bolt.fill")
                            .foregroundStyle(state.flash == .off ? .gray : .white)
                    }
                }
                Button(exposure.manual ? "M" : "A") {
                    if exposure.manual { vm.setAuto() } else { vm.enterManual() }
                    exposureOpen = true
                }
                .font(.subheadline.bold())
                .disabled(lens?.hasManualExposure != true)
            }
            .padding(.horizontal, 20)
            .padding(.top, 10)
            .padding(.bottom, 4)

            if exposureOpen {
                exposureSliders
            }

            HStack {
                let last = shots.first
                Button {
                    if let last { onOpenShot(last.name) }
                } label: {
                    ZStack {
                        RoundedRectangle(cornerRadius: 6).fill(Color(white: 0.125))
                        if let t = last?.thumbnail {
                            Image(decorative: t, scale: 1).resizable().aspectRatio(contentMode: .fill)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                        if let last {
                            switch last.status {
                            case .saving, .queued, .developing: ProgressView().tint(.white)
                            case .failed: Text("!").bold().foregroundStyle(.red)
                            default: EmptyView()
                            }
                        }
                    }
                    .frame(width: 56, height: 56)
                }
                .buttonStyle(.plain)
                .disabled(last == nil)
                Spacer()
                ShutterButton(enabled: !state.capturing && lens != nil && state.lens != nil, action: vm.capture)
                Spacer()
                Button(action: onOpenRoll) {
                    Text("\(shots.count)\nroll").multilineTextAlignment(.center).frame(width: 56)
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
        }
        .background(Color.black.ignoresSafeArea())
        .foregroundStyle(.white)
        .toast($vm.message)
        .sheet(isPresented: $showSettings) { SettingsSheet(vm: vm) }
        .onAppear { vm.resume() }
        .onDisappear { vm.stop() }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: vm.resume()
            case .background: vm.stop()
            default: break
            }
        }
    }

    @ViewBuilder
    private func finder(size: CGSize) -> some View {
        let state = vm.state
        if let renderer = vm.renderer {
            FinderView(renderer: renderer)
                .contentShape(Rectangle())
                .gesture(
                    SpatialTapGesture().onEnded { e in
                        vm.focusAt(CGPoint(x: e.location.x / size.width, y: e.location.y / size.height))
                    }
                )
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in vm.clearFocus() })
        } else {
            Text("No Metal device").foregroundStyle(.red)
        }
        if state.lens == nil, state.error == nil, !vm.permissionDenied {
            ProgressView().tint(.white)
        }
        if vm.viewfinder.grid {
            Path { p in
                for x in [size.width / 3, size.width * 2 / 3] {
                    p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: size.height))
                }
                for y in [size.height / 3, size.height * 2 / 3] {
                    p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y))
                }
            }
            .stroke(Color.white.opacity(0.45), lineWidth: 1)
            .allowsHitTesting(false)
        }
        if let f = state.focusPoint {
            RoundedRectangle(cornerRadius: 4)
                .stroke(state.adjustingFocus ? Color.white : Color(red: 0.73, green: 1, blue: 0.4), lineWidth: 1.5)
                .frame(width: 72, height: 72)
                .position(x: f.x * size.width, y: f.y * size.height)
                .allowsHitTesting(false)
        }
        if vm.viewfinder.showHistogram {
            VStack {
                Spacer()
                HStack {
                    HistogramView(bins: state.histogram)
                        .frame(width: 120, height: 56)
                        .padding(8)
                        .onTapGesture { vm.viewfinder.showHistogram = false }
                    Spacer()
                }
            }
        }
        VStack {
            HStack {
                Button { vm.nextLens() } label: {
                    Text(lensLabel)
                        .font(.caption)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                }
                .disabled(vm.lenses.count < 2)
                Spacer()
                Button { showSettings = true } label: {
                    Image(systemName: "gearshape")
                        .font(.caption)
                        .padding(8)
                }
            }
            Spacer()
            HStack {
                Spacer()
                VStack(spacing: 4) {
                    aid("grid", on: vm.viewfinder.grid) { vm.viewfinder.grid.toggle() }
                    aid("chart.bar.xaxis", on: vm.viewfinder.showHistogram) { vm.viewfinder.showHistogram.toggle() }
                    aid("circle.lefthalf.filled", on: vm.viewfinder.zebra) { vm.viewfinder.zebra.toggle() }
                }
                .padding(6)
            }
        }
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.55), radius: 2, y: 1)
        if state.capturing {
            Color.black.opacity(0.5).allowsHitTesting(false)
        }
        if vm.permissionDenied {
            VStack(spacing: 8) {
                Text("Camera access is off.").foregroundStyle(.red)
                Button("Open Settings") {
                    if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                }
            }
        } else if let e = state.error {
            Text(e).foregroundStyle(.red).multilineTextAlignment(.center).padding()
        }
    }

    private var lensLabel: String {
        vm.lens.map { "\($0.label)  \($0.equivalentMm) mm" } ?? ""
    }

    private func exposureLine(_ state: CaptureState) -> String {
        let iso = state.iso.map(String.init) ?? "—"
        return "\(iso)  ·  \(Format.shutter(seconds: state.exposureSeconds ?? 0))"
    }

    private func aid(_ symbol: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.caption)
                .frame(width: 32, height: 32)
                .foregroundStyle(on ? .white : .white.opacity(0.45))
        }
        .buttonStyle(.plain)
    }

    private var exposureSliders: some View {
        let lens = vm.lens
        let exposure = vm.exposure
        return VStack(spacing: 2) {
            if let l = lens {
                if exposure.manual {
                    let iso = exposure.iso ?? l.isoRange.lowerBound
                    let t = exposure.seconds ?? l.exposureRange.lowerBound
                    LabeledSlider(
                        label: "ISO \(Int(iso))",
                        value: Binding(
                            get: { Format.isoToSlider(iso, lo: l.isoRange.lowerBound, hi: l.isoRange.upperBound) },
                            set: { vm.setManual(iso: Format.sliderToIso($0, lo: l.isoRange.lowerBound, hi: l.isoRange.upperBound), seconds: t) }
                        )
                    )
                    LabeledSlider(
                        label: Format.shutter(seconds: t),
                        value: Binding(
                            get: { Format.secondsToSlider(t, lo: l.exposureRange.lowerBound, hi: l.exposureRange.upperBound) },
                            set: { vm.setManual(iso: iso, seconds: Format.sliderToSeconds($0, lo: l.exposureRange.lowerBound, hi: l.exposureRange.upperBound)) }
                        )
                    )
                } else if l.biasRange.upperBound > l.biasRange.lowerBound {
                    LabeledSlider(
                        label: "EV \(Format.ev(exposure.bias))",
                        value: Binding(get: { exposure.bias }, set: { vm.setBias($0) }),
                        range: l.biasRange,
                        step: 1.0 / 3
                    )
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}

struct SettingsSheet: View {
    @Bindable var vm: CaptureViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmDeleteAll = false

    var body: some View {
        let develop = vm.develop
        let shots = vm.darkroom.shots
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    SettingRow(label: "Save JPEG automatically", on: Binding(get: { develop.autoJpeg }, set: vm.setAutoJpeg))
                    SettingRow(label: "Quick development", on: Binding(get: { develop.binning == 2 }, set: vm.setQuick))
                    Text("Quick development bins the frame and finishes in about a quarter of the time.")
                        .font(.caption).foregroundStyle(.gray)
                    PaperSliders(soft: vm.prefs.soft, hard: vm.prefs.hard, onSoft: vm.setSoft, onHard: vm.setHard)
                        .padding(.top, 8)
                    SettingRow(label: "Peak", on: Binding(get: { vm.prefs.peak }, set: vm.setPeak))
                    Text("The grade and Peak are the preset: the viewfinder, the automatic JPEG, and where each picture starts.")
                        .font(.caption).foregroundStyle(.gray)

                    Text("Storage").font(.headline).padding(.top, 16)
                    HStack {
                        VStack(alignment: .leading) {
                            Text("Raw frames in the app: \(shots.count) · \(Format.bytes(vm.darkroom.cacheBytes))")
                            Text("Pictures in Files and Photos are not touched.").font(.caption).foregroundStyle(.gray)
                        }
                        Spacer()
                        Button("Delete all") { confirmDeleteAll = true }.disabled(shots.isEmpty)
                    }

                    Text("engine \(version()) · core \(coreVersion()) · \(workerThreads()) threads")
                        .font(.caption).foregroundStyle(.gray).padding(.top, 16)
                    Text("GPL-3.0 or any later version. Copyright (C) 2026 Dragan Bojovic. The license text is shipped in this app.")
                        .font(.caption).foregroundStyle(.gray)
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .alert("Delete all \(shots.count) frames?", isPresented: $confirmDeleteAll) {
                Button("Delete all", role: .destructive) { vm.darkroom.deleteAll() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Every raw frame leaves the app for good (\(Format.bytes(vm.darkroom.cacheBytes))). Pictures already in Files and Photos stay.")
            }
        }
    }
}
