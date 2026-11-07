package ch.bojovic.mimizanlab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.bojovic.mimizanlab.engine.Filter
import ch.bojovic.mimizanlab.engine.PlateFilters
import ch.bojovic.mimizanlab.store.GradeMath
import ch.bojovic.mimizanlab.store.PeakMode
import ch.bojovic.mimizanlab.store.Prefs
import ch.bojovic.mimizanlab.store.ShotMode

// The widgets the camera and the developer share: the chip rows, the
// grade slider and the zone strip, the shutter, the histogram.

@Composable
fun PlateFilterRow(selected: PlateFilters, hasMatrix: Boolean, onSelect: (PlateFilters) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (f in PlateFilters.entries) {
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(if (f == PlateFilters.CAMERA && !hasMatrix) "Camera (no matrix)" else Format.plateFilters(f)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF3A3A3A)),
            )
        }
    }
}

@Composable
fun FilterRow(selected: Filter, onSelect: (Filter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
fun ShotRow(mode: ShotMode, onMode: (ShotMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("A shot", Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in ShotMode.entries) {
                FilterChip(
                    selected = m == mode,
                    onClick = { onMode(m) },
                    label = { Text(m.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF3A3A3A)),
                )
            }
        }
    }
}

/** Peak's mode out of [modes]: Off and Auto for the standing Peak in the settings, Custom too in the developer. */
@Composable
fun PeakRow(mode: PeakMode, modes: List<PeakMode>, onMode: (PeakMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Peak", Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in modes) {
                FilterChip(
                    selected = m == mode,
                    onClick = { onMode(m) },
                    label = { Text(m.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF3A3A3A)),
                )
            }
        }
    }
}

/**
 * The grade as one slider: hard light against soft, in stops, zero at
 * the paper's grade 2; left is softer, right harder. The exposure (the
 * mean of the two) stays where the zone strip put it. Behind it are the
 * same two numbers as ever, soft and hard, and the print is the same.
 * Steps of a sixth of a stop keep both on the twelfth grid. No label and
 * no number: the picture is the reading, the slider is as wide as the
 * zone strip under it, and a double tap on the strip is the way back.
 */
@Composable
fun GradeSlider(soft: Double, hard: Double, onContrast: (Double) -> Unit) {
    val lim = (2 * Prefs.STOP_LIMIT).toFloat()
    val ink = if (kotlin.math.abs(GradeMath.contrast(soft, hard)) < 1.0 / 24.0) Color.White else SettingInk
    Slider(
        value = GradeMath.contrast(soft, hard).toFloat(),
        onValueChange = { onContrast((kotlin.math.round(it * 6.0) / 6.0)) },
        valueRange = -lim..lim,
        colors = SliderDefaults.colors(thumbColor = ink, activeTrackColor = ink),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Settings, hand exposure and a paper off the base print are amber, so a dark finder reads as a setting, not a fault. */
val SettingInk = Color(0xFFFFB74D)

/**
 * Drag left to print darker, right to print lighter. Both exposures move
 * by the same stops, so the grade stays. A double tap is the base print:
 * both exposures back to zero, the one way out of a grade set yesterday.
 * The slider and the strip say themselves when they are off it: white at
 * the base print, amber away from it, each for its own half, the grade
 * or the exposure; the double tap turns both white again.
 */
@Composable
fun ZoneStrip(soft: Double, hard: Double, onBoth: (Double, Double) -> Unit) {
    val off = kotlin.math.abs(soft + hard) / 2.0 >= 1.0 / 24.0
    val latest = rememberUpdatedState(soft to hard)
    var origin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var dx by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.horizontalGradient(listOf(Color.Black, Color.White)))
            .border(if (off) 2.dp else 1.dp, if (off) SettingInk else Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onBoth(0.0, 0.0) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        origin = latest.value
                        dx = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val base = origin ?: return@detectHorizontalDragGestures
                        dx += dragAmount
                        val delta = -dx.toDouble() / size.width.coerceAtLeast(1).toDouble() * Prefs.STOP_LIMIT
                        val g = Prefs.shiftGrade(base.first, base.second, delta)
                        onBoth(g.soft, g.hard)
                    },
                    onDragEnd = { origin = null },
                    onDragCancel = { origin = null },
                )
            },
    )
}

/** A continuous slider with its reading in front, for the hand exposure and Peak's pair. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
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

/**
 * The luminance's histogram as bars; for a plate, [rgb] (three runs of
 * equal length, R then G then B) drawn as outlines over it, the way the
 * desktop's print histogram shows the three channels.
 */
@Composable
fun Histogram(bins: FloatArray, modifier: Modifier = Modifier, rgb: FloatArray = FloatArray(0)) {
    Canvas(modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(2.dp)) {
        if (bins.isEmpty()) return@Canvas
        val w = size.width / bins.size
        val barAlpha = if (rgb.isEmpty()) 0.85f else 0.45f
        for (i in bins.indices) {
            val h = bins[i].coerceIn(0f, 1f) * size.height
            drawLine(
                color = Color.White.copy(alpha = barAlpha),
                start = Offset(i * w + w / 2, size.height),
                end = Offset(i * w + w / 2, size.height - h),
                strokeWidth = w,
            )
        }
        if (rgb.size >= 3 && rgb.size % 3 == 0) {
            val n = rgb.size / 3
            val step = size.width / n
            val colours = arrayOf(Color(0xFFFF6060), Color(0xFF60FF60), Color(0xFF7090FF))
            for (c in 0 until 3) {
                val path = Path()
                for (i in 0 until n) {
                    val x = i * step + step / 2
                    val y = size.height - rgb[c * n + i].coerceIn(0f, 1f) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colours[c], style = Stroke(width = 1.5f))
            }
        }
    }
}
