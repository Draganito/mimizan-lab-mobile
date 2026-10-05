package ch.bojovic.mimizanlab.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bojovic.mimizanlab.engine.SeparationMode
import ch.bojovic.mimizanlab.engine.ShotStatus
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(vm: ReviewViewModel, name: String, onBack: () -> Unit) {
    LaunchedEffect(name) { vm.open(name) }
    val s by vm.state.collectAsStateWithLifecycle()
    val darkroom = ch.bojovic.mimizanlab.engine.Darkroom.get(LocalContext.current)
    val shots by darkroom.shots.collectAsStateWithLifecycle()
    val shot = shots.firstOrNull { it.name == name }
    val sharpen by darkroom.prefs.sharpen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                val m = shot?.meta
                if (m != null) {
                    Text(
                        "${m.lens} · ${Format.iso(m.iso)} · ${Format.shutter(m.exposureNs)}" +
                            (m.fNumber?.let { String.format(Locale.US, " · f/%.1f", it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
            }
            IconButton(onClick = { showInfo = true }, enabled = s.info != null) { Icon(Icons.Default.Info, contentDescription = "Info") }
            IconButton(
                onClick = { vm.shareIntent()?.let { context.startActivity(Intent.createChooser(it, "Share")) } },
                enabled = s.lastExport != null || shot?.jpegUri != null,
            ) { Icon(Icons.Default.Share, contentDescription = "Share") }
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val pv = s.preview
            val info = s.info
            when {
                pv != null && info != null -> ZoomableNegative(
                    preview = pv,
                    fullWidth = info.uprightWidth.toInt(),
                    fullHeight = info.uprightHeight.toInt(),
                    detail = s.detail,
                    onVisibleRegion = { x, y, w, h -> vm.requestDetail(x, y, w, h) },
                    onZoomedOut = vm::clearDetail,
                    modifier = Modifier.fillMaxSize(),
                )
                s.needsDevelop -> DevelopPrompt(shot?.status, onDevelop = { vm.develop(vm.shotSettings() ?: ch.bojovic.mimizanlab.engine.DevelopSettings()) })
                else -> CircularProgressIndicator()
            }
            if (s.busy || s.exporting != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            if (s.histogram.isNotEmpty()) {
                Histogram(
                    bins = s.histogram,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(width = 128.dp, height = 48.dp),
                )
            }
        }

        if (s.info != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                FilterRow(selected = s.filter, onSelect = vm::setFilter)
                val w = s.weights
                LabeledSlider(
                    label = String.format(Locale.US, "R %.2f", w.r),
                    value = w.r.toFloat(),
                    onChange = { vm.setWeightsRB(it.toDouble(), w.b) },
                )
                LabeledSlider(
                    label = String.format(Locale.US, "B %.2f", w.b),
                    value = w.b.toFloat(),
                    onChange = { vm.setWeightsRB(w.r, it.toDouble()) },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val e = s.effective
                    Text(
                        String.format(Locale.US, "G %.2f", w.g) +
                            if (s.filter != ch.bojovic.mimizanlab.engine.Filter.NONE) String.format(Locale.US, "  →  %.2f/%.2f/%.2f", e.r, e.g, e.b) else "",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = vm::resetWeights) { Text("Reset") }
                    Spacer(Modifier.weight(1f))
                    Text("Reference look", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = s.reference, onCheckedChange = vm::setReference)
                }
                LabeledSlider(
                    label = String.format(Locale.US, "Con %+.2f", s.contrast),
                    value = s.contrast.toFloat(),
                    onChange = { vm.setContrast(it.toDouble()) },
                    range = -1f..1f,
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        FilledTonalButton(onClick = { exportMenu = true }, enabled = s.exporting == null) {
                            Text(s.exporting?.let { "Saving $it…" } ?: "Export")
                        }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            val usm = if (sharpen > 0.0) ", sharpened" else ""
                            DropdownMenuItem(text = { Text("JPEG, full size$usm") }, onClick = { exportMenu = false; vm.exportJpeg(true) })
                            DropdownMenuItem(text = { Text("JPEG 2048 px, screen sharpened") }, onClick = { exportMenu = false; vm.exportJpeg(false) })
                            DropdownMenuItem(text = { Text("TIFF 16-bit$usm") }, onClick = { exportMenu = false; vm.exportTiff() })
                            DropdownMenuItem(text = { Text("Negative (linear TIFF + mask)") }, onClick = { exportMenu = false; vm.exportNegative() })
                        }
                    }
                    val cur = vm.shotSettings()
                    if (cur != null && cur.binning == 2) {
                        TextButton(onClick = { vm.develop(cur.copy(binning = 1)) }) { Text("Develop full resolution") }
                    } else if (cur != null && cur.separation != SeparationMode.DUBOIS) {
                        TextButton(onClick = { vm.develop(cur.copy(separation = SeparationMode.DUBOIS)) }) { Text("Develop with Dubois") }
                    }
                }
            }
        }
    }

    if (showInfo && s.info != null) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            Text(
                prettyJson(s.info!!.json),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            )
        }
    }

    s.message?.let { msg ->
        LaunchedEffect(msg) { delay(3500); vm.clearMessage() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(msg, color = Color.White, modifier = Modifier.padding(bottom = 48.dp).background(Color(0xCC203020)).padding(12.dp))
        }
    }
}

@Composable
private fun DevelopPrompt(status: ShotStatus?, onDevelop: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (status) {
            ShotStatus.Saving -> { CircularProgressIndicator(); Text("Saving frame…") }
            ShotStatus.Queued -> { CircularProgressIndicator(); Text("Queued") }
            ShotStatus.Developing -> { CircularProgressIndicator(); Text("Developing…") }
            is ShotStatus.Failed -> {
                Text(status.message, color = Color(0xFFFF8080))
                FilledTonalButton(onClick = onDevelop) { Text("Develop") }
            }
            ShotStatus.Cached -> {
                Text("Frame in cache, not developed in this session.", color = Color.Gray)
                FilledTonalButton(onClick = onDevelop) { Text("Develop") }
            }
            else -> {
                Text("The negative is no longer in memory.", color = Color.Gray)
                FilledTonalButton(onClick = onDevelop) { Text("Develop again") }
            }
        }
    }
}

/**
 * Pinch/drag viewer. The preview (<= 2048 px) is the base layer; beyond its
 * resolution the visible window is requested at 1:1 and drawn on top.
 */
@Composable
fun ZoomableNegative(
    preview: Bitmap,
    fullWidth: Int,
    fullHeight: Int,
    detail: Detail?,
    onVisibleRegion: (x: Int, y: Int, w: Int, h: Int) -> Unit,
    onZoomedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val img = remember(preview) { preview.asImageBitmap() }

    // Fit the full picture into the view at scale 1.
    val fit = if (viewSize.width == 0) 1f else min(viewSize.width.toFloat() / fullWidth, viewSize.height.toFloat() / fullHeight)
    val fitW = fullWidth * fit
    val fitH = fullHeight * fit
    val base = Offset((viewSize.width - fitW) / 2f, (viewSize.height - fitH) / 2f)
    val maxScale = max(1f, 2f / fit) // up to 200 % of the sensor pixels

    // screen = offset + s * content; the picture occupies content [base, base + fit size].
    fun clamp(o: Offset, s: Float): Offset {
        val w = fitW * s
        val h = fitH * s
        val x = if (w <= viewSize.width) (viewSize.width - w) / 2f - base.x * s
        else o.x.coerceIn(viewSize.width - w - base.x * s, -base.x * s)
        val y = if (h <= viewSize.height) (viewSize.height - h) / 2f - base.y * s
        else o.y.coerceIn(viewSize.height - h - base.y * s, -base.y * s)
        return Offset(x, y)
    }

    // Screen px per full-res pixel; above 1 the preview is softer than the negative.
    val pxPerFull = fit * scale
    LaunchedEffect(scale, offset, viewSize, fullWidth, fullHeight) {
        if (viewSize.width == 0) return@LaunchedEffect
        val previewPxPerFull = preview.width.toFloat() / fullWidth
        if (pxPerFull <= previewPxPerFull * 1.05f) {
            onZoomedOut()
            return@LaunchedEffect
        }
        // Visible window in full-res coordinates.
        val originX = base.x * scale + offset.x // screen x of picture (0,0)
        val originY = base.y * scale + offset.y
        val x0 = ((0 - originX) / pxPerFull).toInt().coerceIn(0, fullWidth - 1)
        val y0 = ((0 - originY) / pxPerFull).toInt().coerceIn(0, fullHeight - 1)
        val x1 = ((viewSize.width - originX) / pxPerFull).roundToInt().coerceIn(x0 + 1, fullWidth)
        val y1 = ((viewSize.height - originY) / pxPerFull).roundToInt().coerceIn(y0 + 1, fullHeight)
        onVisibleRegion(x0, y0, x1 - x0, y1 - y0)
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewSize = it }
            .pointerInput(fullWidth, fullHeight) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, maxScale)
                    // Keep the point under the fingers fixed while zooming.
                    val k = newScale / scale
                    val o = Offset(
                        centroid.x - k * (centroid.x - offset.x) + pan.x,
                        centroid.y - k * (centroid.y - offset.y) + pan.y,
                    )
                    scale = newScale
                    offset = clamp(o, newScale)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1.01f) {
                        scale = 1f; offset = Offset.Zero
                    } else {
                        val s = min(maxScale, max(2f, 1f / fit)) // 1:1 on screen, or 2x
                        scale = s
                        offset = clamp(Offset(p.x - s * p.x, p.y - s * p.y), s)
                    }
                })
            },
    ) {
        translate(offset.x, offset.y) {
            scale(scale, pivot = Offset.Zero) {
                drawImage(
                    img,
                    dstOffset = androidx.compose.ui.unit.IntOffset(base.x.roundToInt(), base.y.roundToInt()),
                    dstSize = IntSize(fitW.roundToInt(), fitH.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
                if (detail != null) {
                    val d = detail
                    val dx = base.x + d.x * fit
                    val dy = base.y + d.y * fit
                    drawIntoCanvas { c ->
                        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        c.nativeCanvas.drawBitmap(
                            d.bitmap, null,
                            android.graphics.RectF(dx, dy, dx + d.bitmap.width * fit, dy + d.bitmap.height * fit),
                            paint,
                        )
                    }
                }
            }
        }
    }
}

private fun prettyJson(s: String): String = runCatching { org.json.JSONObject(s).toString(2) }.getOrDefault(s)
