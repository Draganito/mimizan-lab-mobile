package ch.bojovic.mimizanlab.ui

import android.hardware.camera2.CaptureResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import ch.bojovic.mimizanlab.engine.Filter
import ch.bojovic.mimizanlab.engine.SeparationMode
import ch.bojovic.mimizanlab.engine.ShotStatus
import ch.bojovic.mimizanlab.engine.coreVersion
import ch.bojovic.mimizanlab.engine.paperGrade
import ch.bojovic.mimizanlab.engine.version
import ch.bojovic.mimizanlab.engine.workerThreads
import ch.bojovic.mimizanlab.store.Prefs
import java.util.Locale
import kotlin.math.roundToInt
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
    val soft by vm.darkroom.prefs.soft.collectAsStateWithLifecycle()
    val hard by vm.darkroom.prefs.hard.collectAsStateWithLifecycle()
    val sharpen by vm.darkroom.prefs.sharpen.collectAsStateWithLifecycle()
    val deconvolution by vm.darkroom.prefs.deconvolution.collectAsStateWithLifecycle()
    val deconvPasses by vm.darkroom.prefs.deconvPasses.collectAsStateWithLifecycle()
    val compensation by vm.darkroom.prefs.compensationThirds.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top bar: lens, filter, settings.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.nextLens() }, enabled = vm.lenses.size > 1) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Lens")
                Spacer(Modifier.width(6.dp))
                Text(lens?.let { "${it.label} ${it.equivalentMm} mm" } ?: "—")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.toggleGrid() }) {
                Icon(Icons.Default.Grid3x3, contentDescription = "Grid", tint = if (vf.grid) Color.White else Color.Gray)
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Tune, contentDescription = "Settings")
            }
        }

        FilterRow(selected = develop.filter, onSelect = vm::setFilter)

        // Viewfinder with focus tap and overlays. It takes the height the
        // readout and the shutter row leave over: on a short screen it is
        // narrower than the display rather than pushing the controls into
        // each other.
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
                    val px = with(d) { size.toPx() }
                    val locked = stream.afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        stream.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    Box(
                        Modifier
                            .offset { IntOffset((fx * viewSize.width - px / 2).toInt(), (fy * viewSize.height - px / 2).toInt()) }
                            .size(size)
                            .border(1.5.dp, if (locked) Color(0xFFBBFF66) else Color.White, RoundedCornerShape(4.dp)),
                    )
                }

                if (vf.showHistogram) {
                    Histogram(
                        bins = histogram,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(width = 120.dp, height = 56.dp)
                            .clickable { vm.toggleHistogram() },
                    )
                }

                if (stream.capturing) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
                }

                stream.error?.let {
                    Text(it, color = Color.Red, modifier = Modifier.align(Alignment.Center))
                }
            }
        }

        // Exposure readout and controls, at their own height.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Format.iso(stream.iso)}   ${Format.shutter(stream.exposureNs)}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Text(if (exposure.manual) "M" else "A", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = exposure.manual,
                        onCheckedChange = { if (it) vm.enterManual() else vm.setAuto() },
                        enabled = lens?.hasManualSensor == true,
                    )
                }
            }
            val l = lens
            if (l != null) {
                if (exposure.manual) {
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
                } else if (l.evRange.upper > l.evRange.lower) {
                    val step = l.evStep.toDouble()
                    LabeledSlider(
                        label = "EV ${Format.ev(exposure.evSteps, step)}",
                        value = (exposure.evSteps - l.evRange.lower).toFloat() / (l.evRange.upper - l.evRange.lower),
                        onChange = { vm.setEv((l.evRange.lower + it * (l.evRange.upper - l.evRange.lower)).roundToInt()) },
                        steps = l.evRange.upper - l.evRange.lower - 1,
                    )
                }
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
                Spacer(Modifier.height(8.dp))
                Text("Separation", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in SeparationMode.entries) {
                        FilterChip(
                            selected = develop.separation == m,
                            onClick = { vm.setSeparation(m) },
                            label = { Text(when (m) { SeparationMode.DUBOIS -> "Dubois"; SeparationMode.ADAPTIVE -> "Adaptive"; SeparationMode.OFF -> "Mask off" }) },
                        )
                    }
                }
                SettingRow("Quick mode (2x binning, quarter time)", develop.binning == 2) { vm.setQuick(it) }
                SettingRow("Save JPEG automatically", develop.autoJpeg) { vm.setAutoJpeg(it) }
                Spacer(Modifier.height(8.dp))
                Text("Paper", style = MaterialTheme.typography.titleMedium)
                PaperSliders(soft, hard, onSoft = vm::setSoft, onHard = vm::setHard)
                Text(
                    "Zero is the base print. These exposures drive the live viewfinder, the automatic JPEG, the roll thumbnail, and the starting values in review. Each picture can still be changed there.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                LabeledSlider(
                    label = if (sharpen > 0.0) String.format(Locale.US, "USM %.2f", sharpen) else "USM off",
                    value = sharpen.toFloat(),
                    onChange = { vm.setSharpen(it.toDouble()) },
                    range = 0f..Prefs.SHARPEN_LIMIT.toFloat(),
                )
                SettingRow("Deconvolution", deconvolution) { vm.setDeconvolution(it) }
                if (deconvolution) {
                    LabeledSlider(
                        label = "Passes $deconvPasses",
                        value = deconvPasses.toFloat(),
                        onChange = { vm.setDeconvPasses(it.roundToInt()) },
                        range = 1f..Prefs.DECONV_PASSES_MAX.toFloat(),
                        steps = Prefs.DECONV_PASSES_MAX - 2,
                    )
                }
                Text(
                    if (deconvolution) {
                        "Runs first, on every pixel. If USM is above zero, it sharpens that result afterwards. USM off leaves only the deconvolution."
                    } else {
                        "USM sharpens the automatic JPEG and the full-size JPEG / TIFF."
                    },
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("JPEG quality", Modifier.weight(1f))
                    Text("100 (fixed)", color = Color.Gray)
                }
                Spacer(Modifier.height(16.dp))
                Text("Exposure", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val compLimit = Prefs.COMP_LIMIT_THIRDS
                LabeledSlider(
                    label = "comp ${Format.ev(compensation, 1.0 / 3)}",
                    value = (compensation + compLimit).toFloat() / (2 * compLimit),
                    onChange = { vm.setCompensationThirds((it * 2 * compLimit).roundToInt() - compLimit) },
                    steps = 2 * compLimit - 1,
                )
                Text(
                    "Standing compensation on A, in thirds of a stop, added to the phone's auto-exposure " +
                        "within the range the camera allows. The EV slider on the viewfinder comes on top of it.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                Spacer(Modifier.height(16.dp))
                Text("Viewfinder", style = MaterialTheme.typography.titleMedium)
                SettingRow("Zebra (highlights)", vf.zebra) { vm.toggleZebra() }
                SettingRow("Histogram", vf.showHistogram) { vm.toggleHistogram() }
                SettingRow("Grid", vf.grid) { vm.toggleGrid() }
                Spacer(Modifier.height(16.dp))
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Raw frames in the app: ${shots.size} · ${Format.bytes(cacheBytes)}")
                        Text("Pictures in the gallery are not touched.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    TextButton(onClick = { confirmDeleteAll = true }, enabled = shots.isNotEmpty()) { Text("Delete all") }
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
fun FilterRow(selected: Filter, onSelect: (Filter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (f in Filter.entries) {
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(Format.filterShort(f)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF3A3A3A)),
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PaperSliders(soft: Double, hard: Double, onSoft: (Double) -> Unit, onHard: (Double) -> Unit) {
    val grade = remember(soft, hard) { runCatching { paperGrade(soft, hard) }.getOrNull() }
    if (grade != null) {
        Text(
            String.format(Locale.US, "≈ grade %s · %.1f stops of scene", grade.label, grade.sceneStops),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val range = -Prefs.STOP_LIMIT.toFloat()..Prefs.STOP_LIMIT.toFloat()
    LabeledSlider(
        label = String.format(Locale.US, "soft %+.2f", soft),
        value = soft.toFloat(),
        onChange = { onSoft(it.toDouble()) },
        range = range,
    )
    Text("filter 00", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    LabeledSlider(
        label = String.format(Locale.US, "hard %+.2f", hard),
        value = hard.toFloat(),
        onChange = { onHard(it.toDouble()) },
        range = range,
    )
    Text("filter 5", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps.coerceAtLeast(0), modifier = Modifier.weight(1f))
    }
}

@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(3.dp, if (enabled) Color.White else Color.Gray, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.DarkGray)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
fun Histogram(bins: FloatArray, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(2.dp)) {
        if (bins.isEmpty()) return@Canvas
        val w = size.width / bins.size
        for (i in bins.indices) {
            val h = bins[i].coerceIn(0f, 1f) * size.height
            drawLine(
                color = color.copy(alpha = 0.85f),
                start = Offset(i * w + w / 2, size.height),
                end = Offset(i * w + w / 2, size.height - h),
                strokeWidth = w,
            )
        }
    }
}
