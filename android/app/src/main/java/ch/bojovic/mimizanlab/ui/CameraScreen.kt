package ch.bojovic.mimizanlab.ui

import android.hardware.camera2.CaptureResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bojovic.mimizanlab.camera.FlashMode
import ch.bojovic.mimizanlab.engine.PlateSpace
import ch.bojovic.mimizanlab.engine.Process
import ch.bojovic.mimizanlab.engine.ShotStatus
import ch.bojovic.mimizanlab.engine.coreVersion
import ch.bojovic.mimizanlab.engine.version
import ch.bojovic.mimizanlab.engine.workerThreads
import ch.bojovic.mimizanlab.store.PeakMode
import kotlinx.coroutines.delay

@Composable
fun CameraScreen(vm: CameraViewModel, onOpenShot: (String) -> Unit, onOpenRoll: () -> Unit) {
    val stream by vm.stream.collectAsStateWithLifecycle()
    val lens by vm.lens.collectAsStateWithLifecycle()
    val exposure by vm.exposure.collectAsStateWithLifecycle()
    val develop by vm.develop.collectAsStateWithLifecycle()
    val vf by vm.viewfinder.collectAsStateWithLifecycle()
    val histogram by vm.histogram.collectAsStateWithLifecycle()
    val tone by vm.tone.collectAsStateWithLifecycle()
    val shots by vm.darkroom.shots.collectAsStateWithLifecycle()
    val cacheBytes by vm.darkroom.cacheBytes.collectAsStateWithLifecycle()
    val grade by vm.darkroom.prefs.grade.collectAsStateWithLifecycle()
    val peakMode by vm.darkroom.prefs.peakMode.collectAsStateWithLifecycle()
    val shotMode by vm.darkroom.prefs.shotMode.collectAsStateWithLifecycle()
    val plateSpace by vm.darkroom.prefs.plateSpace.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pendingReview by vm.pendingReview.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Hand the camera back when the app leaves the foreground.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> vm.resume()
                Lifecycle.Event.ON_PAUSE -> vm.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val aspect = lens?.let { it.rawSize.height.toFloat() / it.rawSize.width } ?: 0.75f

    LaunchedEffect(pendingReview) {
        val name = pendingReview ?: return@LaunchedEffect
        vm.clearPendingReview()
        onOpenShot(name)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // The line above the finder: process, exposure, flash, A/M.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.setProcess(if (develop.isPlate) Process.MONO else Process.PLATE) }) {
                Text(if (develop.isPlate) "Color" else "Mono", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                Format.exposureLine(stream.iso, stream.exposureNs, exposure.manual),
                fontFamily = FontFamily.Monospace,
                color = if (exposure.manual) SettingInk else Color.White,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (lens?.hasFlash == true) {
                IconButton(onClick = vm::cycleFlash) {
                    Icon(
                        when (stream.flash) {
                            FlashMode.Off -> Icons.Filled.FlashOff
                            FlashMode.Auto -> Icons.Filled.FlashAuto
                            FlashMode.On -> Icons.Filled.FlashOn
                        },
                        contentDescription = "Flash",
                        tint = if (stream.flash == FlashMode.Off) Color.Gray else Color.White,
                    )
                }
            }
            TextButton(
                onClick = { if (exposure.manual) vm.setAuto() else vm.enterManual() },
                enabled = lens?.hasManualSensor == true,
            ) { Text(if (exposure.manual) "M" else "A", fontWeight = FontWeight.Bold, color = if (exposure.manual) SettingInk else Color.White) }
        }

        // The picture takes the height the line, the grade and the shutter
        // row leave.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .aspectRatio(aspect)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { p -> if (viewSize.width > 0) vm.focusAt(p.x / viewSize.width, p.y / viewSize.height) },
                            onLongPress = { vm.clearFocus() },
                        )
                    },
            ) {
                MonoViewfinder(tone = tone, aspect = aspect, onSurface = vm::onSurface, modifier = Modifier.fillMaxSize())
                if (stream.lens == null && stream.error == null) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(28.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
                if (vf.grid) {
                    Canvas(Modifier.matchParentSize()) {
                        val ink = Color.White.copy(alpha = 0.45f)
                        for (x in listOf(size.width / 3f, size.width * 2f / 3f)) {
                            drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                        }
                        for (y in listOf(size.height / 3f, size.height * 2f / 3f)) {
                            drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                        }
                    }
                }

                stream.focusPoint?.let { (fx, fy) ->
                    val d = LocalDensity.current
                    val size = 72.dp
                    val labelWidth = 120.dp
                    val px = with(d) { size.toPx() }
                    val labelPx = with(d) { labelWidth.toPx() }
                    val locked = stream.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        stream.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    Column(
                        Modifier
                            .offset { IntOffset((fx * viewSize.width - labelPx / 2).toInt(), (fy * viewSize.height - px / 2).toInt()) }
                            .width(labelWidth),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(size)
                                .border(1.5.dp, if (locked) Color(0xFFBBFF66) else Color.White, RoundedCornerShape(4.dp)),
                        )
                        // The tap also meters here. Say so, and how to let go.
                        Text(
                            "focus · meter\nhold to clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                // The finder's own controls sit along its lower edge, the
                // picture above them clear: bottom left the lens, the
                // histogram over it; bottom right the gear, a gap, and the
                // three aids.
                Row(Modifier.matchParentSize().padding(4.dp), verticalAlignment = Alignment.Bottom) {
                    Column {
                        if (vf.showHistogram) {
                            Histogram(
                                bins = histogram,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(width = 120.dp, height = 56.dp)
                                    .clickable { vm.toggleHistogram() },
                            )
                        }
                        TextButton(onClick = { vm.nextLens() }, enabled = vm.lenses.size > 1) {
                            Text(lens?.let { "${it.label}  ${it.equivalentMm} mm" } ?: "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Aid(Icons.Default.Settings, "Settings", true) { showSettings = true }
                        Spacer(Modifier.height(8.dp))
                        Aid(Icons.Default.Grid3x3, "Grid", vf.grid, vm::toggleGrid)
                        Aid(Icons.Default.BarChart, "Histogram", vf.showHistogram, vm::toggleHistogram)
                        Aid(Icons.Default.Contrast, "Zebra", vf.zebra, vm::toggleZebra)
                    }
                }

                if (stream.capturing) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
                }

                stream.error?.let {
                    Text(it, color = Color.Red, modifier = Modifier.align(Alignment.Center))
                }
            }
        }

        // The standing paper under the finder, the pair the finder, the
        // automatic JPEG and the next frame start from: the taking filter
        // as a row, and the paper as one strip — a drag is the exposure,
        // a spread the grade. The finder is the
        // paper, so the gesture shows what the print will be. A double
        // tap on the strip is the base print.
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(FilterStripGap)) {
            if (develop.isPlate) {
                PlateFilterStrip(selected = develop.plateFilters, hasMatrix = lens?.xyzToCam != null, onSelect = vm::setPlateFilters)
            } else {
                MonoFilterStrip(selected = develop.filter, onSelect = vm::setFilter)
            }
            PaperStrip(grade.soft, grade.hard, onBoth = vm::setGrade)
        }

        // The hand's two sliders, only in M. In A the automatics expose and
        // the paper is corrected in the developer, where the stops are real.
        val l = lens
        if (exposure.manual && l != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                val iso = exposure.iso ?: l.isoRange.lower
                val t = exposure.exposureNs ?: l.exposureRangeNs.lower
                LabeledSlider(
                    label = "ISO $iso",
                    value = Format.isoToSlider(iso, l.isoRange.lower, l.isoRange.upper),
                    onChange = { vm.setManual(Format.sliderToIso(it, l.isoRange.lower, l.isoRange.upper), t) },
                )
                LabeledSlider(
                    label = Format.shutter(t),
                    value = Format.nsToSlider(t, l.exposureRangeNs.lower, minOf(l.exposureRangeNs.upper, 4_000_000_000L)),
                    onChange = { vm.setManual(iso, Format.sliderToNs(it, l.exposureRangeNs.lower, minOf(l.exposureRangeNs.upper, 4_000_000_000L))) },
                )
                Text(
                    if (l.hasFlash) "Manual: the finder shows this exposure. Auto flash does not fire; On fires once. A is the automatics."
                    else "Manual: the finder shows this exposure. A is the automatics.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        }

        // Shutter row.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val last = shots.firstOrNull()
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF202020))
                    .clickable(enabled = last != null) { last?.let { onOpenShot(it.name) } },
                contentAlignment = Alignment.Center,
            ) {
                val thumb = last?.thumbnail
                if (thumb != null) {
                    Image(thumb.asImageBitmap(), contentDescription = "Last shot", modifier = Modifier.fillMaxSize())
                }
                when (last?.status) {
                    ShotStatus.Saving, ShotStatus.Queued, ShotStatus.Developing ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                    is ShotStatus.Failed -> Text("!", color = Color.Red, fontWeight = FontWeight.Bold)
                    else -> Unit
                }
            }

            ShutterButton(enabled = !stream.capturing && lens != null, onClick = vm::capture)

            TextButton(onClick = onOpenRoll) { Text("${shots.size}\nroll", textAlign = TextAlign.Center) }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 32.dp)) {
                Text("Development", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The standing paper is under the finder. The slider is the grade: hard light against soft, in stops; zero is the paper's grade 2, left is softer, right harder. The strip is the exposure: left prints darker, right lighter, a double tap is the base print. The finder, the automatic JPEG and a new frame start there. In the developer the grade is the picture's own and stays with it.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                PeakRow(peakMode, PeakMode.STANDING, onMode = vm::setPeakMode)
                Text(
                    "The standing Peak: the automatic JPEG, and where a new frame starts. Auto measures each negative. A hand pair is set in the developer and stays with that picture. The viewfinder does not follow Peak.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                ShotRow(shotMode, vm::setShotMode)
                Text(
                    "JPEG writes the picture and stays here. Developer opens it. Raw keeps the frame for later, and the picture waits for Export.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                Text("Color space", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sp in PlateSpace.entries) {
                        FilterChip(
                            selected = sp == plateSpace,
                            onClick = { vm.setPlateSpace(sp) },
                            label = { Text(Format.plateSpace(sp)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF3A3A3A)),
                        )
                    }
                }
                if (vm.darkroom.entitlements.storeBound) {
                    Spacer(Modifier.height(16.dp))
                    Text("Full resolution", style = MaterialTheme.typography.titleMedium)
                    UnlockSection(vm.darkroom.entitlements)
                }
                Spacer(Modifier.height(16.dp))
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Raw frames in the app: ${shots.size} · ${Format.bytes(cacheBytes)}")
                        Text("Pictures in the gallery are not touched.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    val rollBusy = shots.any {
                        it.status == ShotStatus.Saving || it.status == ShotStatus.Queued || it.status == ShotStatus.Developing
                    }
                    TextButton(
                        onClick = { confirmDeleteAll = true },
                        enabled = shots.isNotEmpty() && !rollBusy,
                    ) { Text("Delete all") }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "engine ${version()} · core ${coreVersion()} · ${workerThreads()} threads",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Text(
                    "GPL-3.0 or any later version. Copyright (C) 2026 Dragan Bojovic. The license text is shipped in this app.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all ${shots.size} frames?") },
            text = { Text("Every raw frame leaves the app for good (${Format.bytes(cacheBytes)}). Pictures already in the gallery stay.") },
            confirmButton = { TextButton(onClick = { vm.darkroom.deleteAll(); confirmDeleteAll = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }

    message?.let { msg ->
        LaunchedEffect(msg) { delay(4000); vm.clearMessage() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                msg,
                color = Color.White,
                modifier = Modifier.padding(bottom = 120.dp).background(Color(0xCC402020), RoundedCornerShape(8.dp)).padding(12.dp),
            )
        }
    }
}

@Composable
private fun Aid(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, toggle: () -> Unit) {
    IconButton(onClick = toggle) {
        Icon(icon, contentDescription = label, tint = if (on) Color.White else Color.White.copy(alpha = 0.45f))
    }
}
