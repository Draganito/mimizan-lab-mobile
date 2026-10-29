// The viewfinder screen: lens and settings on top, the process and the
// film, the live negative with focus tap, the exposure readout and
// controls, the shutter row. Mirrors `CameraScreen.kt`.
import MimizanCore
import SwiftUI

struct CaptureView: View {
    @Bindable var vm: CaptureViewModel
    let onOpenShot: (String) -> Void
    let onOpenRoll: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false

    var body: some View {
        let state = vm.state
        let lens = vm.lens
        let develop = vm.develop
        let shots = vm.darkroom.shots
        VStack(spacing: 0) {
            // Top bar: lens, grid, settings.
            HStack {
                Button {
                    vm.nextLens()
                } label: {
                    Label(lens.map { "\($0.label) \($0.equivalentMm) mm" } ?? "—", systemImage: "camera.rotate")
                }
                .disabled(vm.lenses.count < 2)
                Spacer()
                Button { vm.viewfinder.grid.toggle() } label: {
                    Image(systemName: "grid").foregroundStyle(vm.viewfinder.grid ? .white : .gray)
                }
                .padding(.horizontal, 8)
                Button { showSettings = true } label: { Image(systemName: "slider.horizontal.3") }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            ProcessRow(
                plate: develop.isPlate, filters: develop.plateFilters, space: develop.plateSpace, hasMatrix: vm.hasMatrix,
                onProcess: vm.setProcess, onFilters: vm.setPlateFilters, onSpace: vm.setPlateSpace
            )
            if !develop.isPlate {
                FilterRow(selected: develop.filter, onSelect: vm.setFilter)
            }

            // The finder fills the height the readout and the shutter row
            // leave, as wide as the picture's own aspect allows.
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

            exposureControls

            // Shutter row.
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
                HStack {
                    Spacer()
                    HistogramView(bins: state.histogram)
                        .frame(width: 120, height: 56)
                        .padding(8)
                        .onTapGesture { vm.viewfinder.showHistogram = false }
                }
                Spacer()
            }
        }
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

    private var exposureControls: some View {
        let state = vm.state
        let lens = vm.lens
        let exposure = vm.exposure
        return VStack(spacing: 2) {
            HStack {
                Text("\(Format.iso(state.iso))   \(Format.shutter(seconds: state.exposureSeconds ?? 0))")
                    .font(.headline.monospaced())
                Spacer()
                if lens?.hasFlash == true {
                    Button(action: vm.cycleFlash) {
                        Image(systemName: state.flash == .off ? "bolt.slash" : state.flash == .auto ? "bolt.badge.automatic" : "bolt.fill")
                            .foregroundStyle(state.flash == .off ? .gray : .white)
                    }
                    .padding(.trailing, 8)
                }
                Text(exposure.manual ? "M" : "A").bold()
                Toggle("", isOn: Binding(get: { exposure.manual }, set: { $0 ? vm.enterManual() : vm.setAuto() }))
                    .labelsHidden()
                    .disabled(lens?.hasManualExposure != true)
            }
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
        let prefs = vm.prefs
        let develop = vm.develop
        let shots = vm.darkroom.shots
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Development").font(.headline)
                    Text("Separation").font(.subheadline)
                    HStack(spacing: 8) {
                        ForEach(SeparationMode.all, id: \.self) { m in
                            Chip(label: Format.separation(m), selected: develop.separation == m) { vm.setSeparation(m) }
                        }
                    }
                    SettingRow(label: "Quick mode (2x binning, quarter time)", on: Binding(get: { develop.binning == 2 }, set: vm.setQuick))
                    SettingRow(label: "Save JPEG automatically", on: Binding(get: { develop.autoJpeg }, set: vm.setAutoJpeg))

                    Text("Paper").font(.headline).padding(.top, 8)
                    PaperSliders(soft: prefs.soft, hard: prefs.hard, onSoft: prefs.setSoft, onHard: prefs.setHard)
                    Text("Zero is the base print. These exposures drive the live viewfinder, the automatic JPEG, the roll thumbnail, and the starting values in review. Each picture can still be changed there.")
                        .font(.caption).foregroundStyle(.gray)

                    if develop.isPlate {
                        Text("Lamps").font(.headline).padding(.top, 8)
                        let lim = Prefs.lampLimit
                        LabeledSlider(label: String(format: "red %+.2f", prefs.lampRed), value: Binding(get: { prefs.lampRed }, set: prefs.setLampRed), range: -lim...lim, step: 1.0 / 12)
                        LabeledSlider(label: String(format: "blue %+.2f", prefs.lampBlue), value: Binding(get: { prefs.lampBlue }, set: prefs.setLampBlue), range: -lim...lim, step: 1.0 / 12)
                        Text("The projector's three lamps, green the reference: stops of exposure added to the red and the blue positive. The only colour control of the plate; the paper's two exposures stay the same for all three.")
                            .font(.caption).foregroundStyle(.gray)
                    }

                    SettingRow(label: "Peak", on: Binding(get: { prefs.peak }, set: prefs.setPeak))
                    Text("Sharpens the automatic JPEG and the full-size JPEG / TIFF. Radius and amount are measured on each negative: the smallest high-pass that brings its edges to the target, without a rim. Review shows the measured pair and lets you move it; the 1:1 view shows the result.")
                        .font(.caption).foregroundStyle(.gray)
                    HStack {
                        Text("JPEG quality")
                        Spacer()
                        Text("100 (fixed)").foregroundStyle(.gray)
                    }

                    Text("Viewfinder").font(.headline).padding(.top, 16)
                    SettingRow(label: "Zebra (highlights)", on: $vm.viewfinder.zebra)
                    SettingRow(label: "Histogram", on: $vm.viewfinder.showHistogram)
                    SettingRow(label: "Grid", on: $vm.viewfinder.grid)

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
