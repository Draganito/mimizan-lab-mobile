// The viewfinder screen. On an Apple Log camera the picture is the paper,
// with the zone strip under it as the one exposure correction (as on
// Android); elsewhere it is the camera's own preview, desaturated in
// monochrome, untouched in colour. One line names the process and the
// exposure, the shutter sits below. The print is made afterwards, in the
// developer.
import MimizanCore
import SwiftUI

struct CaptureView: View {
    @Bindable var vm: CaptureViewModel
    let onOpenShot: (String) -> Void
    let onOpenRoll: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false

    /// Manual is amber, so a dark finder in M reads as a setting, not a fault.
    private let manualInk = Color(red: 1, green: 0.72, blue: 0.3)

    var body: some View {
        let state = vm.state
        let lens = vm.lens
        let develop = vm.develop
        let shots = vm.darkroom.shots
        let exposure = vm.exposure
        VStack(spacing: 0) {
            // The line above the finder: process, exposure, flash, A/M.
            HStack(spacing: 16) {
                Button(develop.isPlate ? "Color" : "Mono") {
                    vm.setProcess(develop.isPlate ? .mono : .plate)
                }
                .font(.subheadline.weight(.semibold))
                Spacer()
                Text(Format.exposureLine(iso: state.iso, seconds: state.exposureSeconds, manual: exposure.manual))
                    .font(.subheadline.monospaced())
                    .foregroundStyle(exposure.manual ? manualInk : .white)
                // Where the paper stands when it is off the base print, so a
                // strip left at +1 yesterday is seen before the shot.
                if let shift = Format.paperShift(soft: vm.prefs.soft, hard: vm.prefs.hard) {
                    Text(shift).font(.subheadline.monospaced()).foregroundStyle(manualInk)
                }
                if lens?.hasFlash == true {
                    Button(action: vm.cycleFlash) {
                        Image(systemName: state.flash == .off ? "bolt.slash" : state.flash == .auto ? "bolt.badge.automatic" : "bolt.fill")
                            .foregroundStyle(state.flash == .off ? .gray : .white)
                    }
                }
                Button(exposure.manual ? "M" : "A") {
                    if exposure.manual { vm.setAuto() } else { vm.enterManual() }
                }
                .font(.subheadline.bold())
                .foregroundStyle(exposure.manual ? manualInk : .white)
                .disabled(lens?.hasManualExposure != true)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)

            // The finder fills the height the line, the grade and the
            // shutter leave, as wide as the picture's own aspect allows.
            GeometryReader { geo in
                let aspect = state.frameAspect
                let h = min(geo.size.height, geo.size.width / aspect)
                let w = h * aspect
                ZStack {
                    finder(size: CGSize(width: w, height: h))
                }
                .frame(width: w, height: h)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // The iPad's interface turns a moment after the device does;
                // the new size is the sign that it has.
                .onChange(of: geo.size) { _, _ in vm.refreshOrientation() }
            }

            // The standing paper under the finder, the pair that the
            // paper finder, the automatic JPEG and a new frame start from:
            // the grade, and the strip for the exposure, darker to the
            // left, lighter to the right; the grade stays.
            VStack(spacing: 2) {
                GradeSlider(soft: vm.prefs.soft, hard: vm.prefs.hard, onContrast: vm.setContrast)
                ZoneStrip(soft: vm.prefs.soft, hard: vm.prefs.hard, onBoth: vm.setGrade)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)

            // The hand's two sliders, only in M. In A the automatics expose
            // and the paper is corrected in the developer, where the stops
            // are real.
            if exposure.manual, let l = lens {
                manualSliders(l)
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
        .onChange(of: vm.pendingReview) { _, name in
            if let name {
                onOpenShot(name)
                vm.clearPendingReview()
            }
        }
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
            // The tap also meters here. Say so, and how to let go.
            VStack(spacing: 2) {
                RoundedRectangle(cornerRadius: 4)
                    .stroke(state.adjustingFocus ? Color.white : Color(red: 0.73, green: 1, blue: 0.4), lineWidth: 1.5)
                    .frame(width: 72, height: 72)
                Text("focus · meter\nhold to clear")
                    .font(.caption2)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white.opacity(0.8))
            }
            .position(x: f.x * size.width, y: f.y * size.height + 16)
            .allowsHitTesting(false)
        }
        if vm.viewfinder.showHistogram {
            VStack {
                Spacer()
                HStack {
                    HistogramView(bins: state.histogram)
                        .frame(width: 120, height: 56)
                        .padding(8)
                        .onTapGesture { vm.toggleHistogram() }
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
                        .font(pad ? .title2 : .caption)
                        .frame(width: pad ? 64 : 32, height: pad ? 64 : 32)
                        .contentShape(Rectangle())
                }
            }
            Spacer()
            HStack {
                Spacer()
                VStack(spacing: pad ? 8 : 4) {
                    aid("grid", on: vm.viewfinder.grid) { vm.toggleGrid() }
                    aid("chart.bar.xaxis", on: vm.viewfinder.showHistogram) { vm.toggleHistogram() }
                    aid("circle.lefthalf.filled", on: vm.viewfinder.zebra) { vm.toggleZebra() }
                }
                .padding(pad ? 12 : 6)
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

    /// iPad targets are twice the phone's. A 32 pt symbol is too small to hit on the mini.
    private var pad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    private func aid(_ symbol: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(pad ? .title2 : .caption)
                .frame(width: pad ? 64 : 32, height: pad ? 64 : 32)
                .contentShape(Rectangle())
                .foregroundStyle(on ? .white : .white.opacity(0.45))
        }
        .buttonStyle(.plain)
    }

    private func manualSliders(_ l: LensInfo) -> some View {
        let exposure = vm.exposure
        let iso = exposure.iso ?? l.isoRange.lowerBound
        let t = exposure.seconds ?? l.exposureRange.lowerBound
        return VStack(spacing: 2) {
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
            Text(l.hasFlash
                ? "Manual: the finder shows this exposure. Auto flash does not fire; On fires once. A is the automatics."
                : "Manual: the finder shows this exposure. A is the automatics.")
                .font(.caption).foregroundStyle(.gray)
                .frame(maxWidth: .infinity, alignment: .leading)
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
        let paperFinder = vm.state.paperFinder
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    SettingRow(label: "Quick development", on: Binding(get: { develop.binning == 2 }, set: vm.setQuick))
                    Text("Quick development bins the frame and finishes in about a quarter of the time.")
                        .font(.caption).foregroundStyle(.gray)
                    Text(paperFinder
                        ? "The standing paper is under the finder. The slider is the grade: hard light against soft, in stops; zero is the paper's grade 2, left is softer, right harder. The strip is the exposure: left prints darker, right lighter; double-tap it for the base print. The viewfinder, the automatic JPEG and a new frame start there. In the developer the grade is the picture's own and stays with it."
                        : "The standing paper is under the finder. The slider is the grade: hard light against soft, in stops; zero is the paper's grade 2, left is softer, right harder. The strip is the exposure: left prints darker, right lighter; double-tap it for the base print. The automatic JPEG and a new frame start there. In the developer the grade is the picture's own and stays with it.")
                        .font(.caption).foregroundStyle(.gray).padding(.top, 8)
                    PeakRow(mode: vm.prefs.peakMode, modes: PeakMode.standing, onMode: vm.setPeakMode)
                    Text(paperFinder
                        ? "The standing Peak: the automatic JPEG, and where a new frame starts. Auto measures each negative. A hand pair is set in the developer and stays with that picture. The viewfinder does not show Peak."
                        : "The standing Peak: the automatic JPEG, and where a new frame starts. Auto measures each negative. A hand pair is set in the developer and stays with that picture. The viewfinder does not follow the grade or Peak.")
                        .font(.caption).foregroundStyle(.gray)
                    ShotRow(mode: vm.prefs.shotMode, onMode: vm.setShotMode)
                    Text("JPEG writes the picture and stays here. Developer opens it. Raw keeps the frame for later, and the picture waits for Export.")
                        .font(.caption).foregroundStyle(.gray)
                    Text("Plate colour space").font(.subheadline).padding(.top, 8)
                    HStack(spacing: 6) {
                        ForEach(PlateSpace.all, id: \.self) { sp in
                            Chip(label: Format.plateSpace(sp), selected: vm.prefs.plateSpace == sp) { vm.setPlateSpace(sp) }
                        }
                    }

                    Text("Viewfinder").font(.headline).padding(.top, 16)
                    if paperFinder {
                        let match = vm.prefs.finderMatch
                        HStack {
                            VStack(alignment: .leading) {
                                Text("The paper, from the \(vm.state.finderFormat) stream.")
                                Text(match.count == 0
                                    ? "Exposure match: \(Format.stops(match.stops)) stops, the curve's own white; the first frames measure it."
                                    : "Exposure match: \(Format.stops(match.stops)) stops from \(match.count) frame\(match.count == 1 ? "" : "s"). Each frame is a line in finder-match.json, below.")
                                    .font(.caption).foregroundStyle(.gray)
                            }
                            Spacer()
                            Button("Reset") { vm.prefs.resetFinderMatch() }.disabled(match.count == 0)
                        }
                    } else {
                        Text("The camera's picture. The paper needs Apple Log with a Bayer RAW still in the same format: an iPhone 15 Pro or a later Pro, and then only where the camera allows the two together.")
                            .font(.caption).foregroundStyle(.gray)
                    }
                    // The step that decided it, in the words a tester can
                    // photograph, and the two files to send, shared from
                    // here so nobody has to find them in Files.
                    Text(vm.state.finderNote.isEmpty ? "Opening the camera…" : vm.state.finderNote)
                        .font(.caption).foregroundStyle(.gray)
                    Text(vm.state.formatsFile.isEmpty ? "formats.json not written yet." : vm.state.formatsFile)
                        .font(.caption).foregroundStyle(.gray)
                    HStack(spacing: 16) {
                        ShareLink(item: FormatDump.url) { Label("formats.json", systemImage: "square.and.arrow.up") }
                            .disabled(!FormatDump.exists)
                        ShareLink(item: FinderMatchLog.url) { Label("finder-match.json", systemImage: "square.and.arrow.up") }
                            .disabled(!FinderMatchLog.exists)
                    }
                    .font(.subheadline)

                    Text("Full resolution").font(.headline).padding(.top, 16)
                    UnlockSection(entitlements: vm.darkroom.entitlements)

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
                    Text("Copyright (C) 2026 Dragan Bojovic. All rights reserved.")
                        .font(.caption).foregroundStyle(.gray)
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .toast(Bindable(vm.darkroom.entitlements).message)
            .alert("Delete all \(shots.count) frames?", isPresented: $confirmDeleteAll) {
                Button("Delete all", role: .destructive) { vm.darkroom.deleteAll() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Every raw frame leaves the app for good (\(Format.bytes(vm.darkroom.cacheBytes))). Pictures already in Files and Photos stay.")
            }
        }
    }
}
