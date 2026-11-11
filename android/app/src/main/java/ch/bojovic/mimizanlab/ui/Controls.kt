package ch.bojovic.mimizanlab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.bojovic.mimizanlab.engine.Filter
import ch.bojovic.mimizanlab.engine.PlateFilters
import ch.bojovic.mimizanlab.engine.paperLut
import ch.bojovic.mimizanlab.store.GradeMath
import ch.bojovic.mimizanlab.store.PeakMode
import ch.bojovic.mimizanlab.store.Prefs
import ch.bojovic.mimizanlab.store.ShotMode

// The widgets the camera and the developer share: the chip rows, the
// grade slider and the zone strip, the shutter, the histogram.

/**
 * The taking filters as one row over the paper strip, the width shared
 * in equal fields, one type size whichever process stands: in Mono the
 * seven glasses (Pan and the six Wratten numbers), in Color the three
 * ways to the plate. The chosen one is written in its own colour — the
 * glass in front of the lens, read without reading — with a line under
 * it that slides over on a change; the others grey. A tap chooses, a
 * finger drawn along the row scrubs through them with the picture
 * following. A field that cannot be used as it stands (Camera without
 * the lens's matrix yet) is dimmed, and still answers: the fallback
 * shows what it is. Twin of the iOS `FilterStrip`.
 */
@Composable
fun FilterStrip(labels: List<String>, inks: List<Color>, selected: Int, dimmed: Set<Int> = emptySet(), onSelect: (Int) -> Unit) {
    val n = labels.size.coerceAtLeast(1)
    val latest = rememberUpdatedState(selected)
    val slide by animateFloatAsState(selected.toFloat(), label = "filter")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(n) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val i = (change.position.x / (size.width / n)).toInt().coerceIn(0, n - 1)
                    if (i != latest.value) onSelect(i)
                }
            },
    ) {
        val w = maxWidth / n
        Row(Modifier.fillMaxWidth().height(26.dp)) {
            for (i in 0 until n) {
                Box(
                    Modifier
                        .width(w)
                        .height(26.dp)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        labels[i],
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = if (i == selected) inks[i] else Color.Gray.copy(alpha = if (i in dimmed) 0.4f else 0.8f),
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = w * slide + w * 0.2f)
                .width(w * 0.6f)
                .height(2.dp)
                .background(inks[selected.coerceIn(0, inks.size - 1)]),
        )
    }
}

@Composable
fun MonoFilterStrip(selected: Filter, onSelect: (Filter) -> Unit) {
    val all = Filter.entries
    FilterStrip(
        labels = all.map(Format::filterShort), inks = all.map(::filterInk),
        selected = all.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(all[it]) },
    )
}

@Composable
fun PlateFilterStrip(selected: PlateFilters, hasMatrix: Boolean, onSelect: (PlateFilters) -> Unit) {
    val all = PlateFilters.entries
    FilterStrip(
        labels = all.map(Format::plateFilters), inks = all.map { Color.White },
        selected = all.indexOf(selected).coerceAtLeast(0),
        dimmed = if (hasMatrix) emptySet() else setOf(all.indexOf(PlateFilters.CAMERA)),
        onSelect = { onSelect(all[it]) },
    )
}

/** The glass's own colour, as it looks held up to the light. */
fun filterInk(f: Filter): Color = when (f) {
    Filter.NONE -> Color.White
    Filter.YELLOW8 -> Color(0xFFFFD933)
    Filter.YELLOW_GREEN11 -> Color(0xFFBFE640)
    Filter.ORANGE16 -> Color(0xFFFF9933)
    Filter.RED25 -> Color(0xFFFF5242)
    Filter.GREEN58 -> Color(0xFF4DD966)
    Filter.BLUE47 -> Color(0xFF6694FF)
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

/** Settings, hand exposure and a paper off the base print are amber, so a dark finder reads as a setting, not a fault. */
val SettingInk = Color(0xFFFFB74D)

/**
 * The paper as one strip. It is drawn from the core's own paper table
 * ([paperLut] for the standing soft and hard exposures) over the scene,
 * a zone to the stop, from five stops under the grey card to the clip
 * two and a half above it, where the negative ends
 * ([GradeMath.stripLinearAt]): the strip is the print of a grey wedge,
 * every slice a grey of its own, and what it shows is what the paper
 * does to the picture. The ticks are the zones; the card stands at the
 * mark, two thirds along. One finger drags the exposure: both exposures
 * move by the same stops ([Prefs.shiftGrade]), the ramp slides under the
 * mark, the width of the strip is three stops. Two fingers spread the
 * grade: the ramp widens (softer) or closes (harder) round the print's
 * mid-grey, which stays where it is, as when one speed-matched filter
 * is changed for another ([Prefs.graded], the core's step, two stops of
 * hard light over soft to the doubling). The grade stops at 00 and at
 * 5, and sooner where a lighter print leaves the hard light no room; a
 * pair from before that lies beyond an end stays there and comes back
 * on a step inwards. Both gestures
 * together are allowed. A double tap is the base print; a second double
 * tap on a strip already there brings the pair back. Behind it are the
 * same two numbers as ever, soft and hard, and the print is the same.
 * White at the base print, amber away from it. Twin of the iOS `PaperStrip`.
 */
@Composable
fun PaperStrip(soft: Double, hard: Double, onBoth: (Double, Double) -> Unit) {
    val off = kotlin.math.abs(soft) >= 1.0 / 24.0 || kotlin.math.abs(hard) >= 1.0 / 24.0
    val latest = rememberUpdatedState(soft to hard)
    var remembered by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val lut = remember(soft, hard) {
        runCatching { paperLut(soft, hard, 256u).toFloatArray() }.getOrElse { FloatArray(257) { it / 256f } }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(if (off) 2.dp else 1.dp, if (off) SettingInk else Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    val (s, h) = latest.value
                    if (kotlin.math.abs(s) >= 1.0 / 24.0 || kotlin.math.abs(h) >= 1.0 / 24.0) {
                        remembered = s to h
                        onBoth(0.0, 0.0)
                    } else {
                        remembered?.let { onBoth(it.first, it.second) }
                    }
                })
            }
            .pointerInput(Unit) {
                // Pan and zoom of one gesture, read together: the pan is the
                // exposure from the pair as the gesture began, the zoom the
                // grade from the grade as it began, each applied to the
                // current pair so both have their say at once.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val base = latest.value
                    var dx = 0f
                    var scale = 1f
                    var moving = false
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            dx += event.calculatePan().x
                            scale *= event.calculateZoom()
                            if (!moving) {
                                moving = kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                    kotlin.math.abs(kotlin.math.ln(scale.toDouble())) > 0.05
                            }
                            if (moving) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                val delta = -dx.toDouble() / size.width.coerceAtLeast(1).toDouble() * Prefs.STOP_LIMIT
                                val shifted = Prefs.shiftGrade(base.first, base.second, delta)
                                // Spreading the ramp is the softer paper. The
                                // shifted pair has the grade the gesture began
                                // with, so the notches count from there.
                                val notches = GradeMath.spreadNotches(scale.toDouble())
                                val g = if (notches == 0) shifted else Prefs.graded(shifted.soft, shifted.hard, notches)
                                onBoth(g.soft, g.hard)
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })
                }
            },
    ) {
        // The ramp: each slice the paper's value for the linear grey of
        // its stop.
        val n = 192
        val w = size.width / n
        for (i in 0 until n) {
            val v = lutValue(lut, GradeMath.stripLinearAt((i + 0.5) / n))
            drawRect(Color(v, v, v), topLeft = Offset(i * w, 0f), size = androidx.compose.ui.geometry.Size(w + 0.5f, size.height))
        }
        // The zones, whole stops from the grey card, as ticks from the top edge.
        for (stops in GradeMath.stripTicks()) {
            val x = size.width * GradeMath.stripFracOf(stops).toFloat()
            drawRect(Color.Gray.copy(alpha = 0.6f), topLeft = Offset(x - 0.5f, 0f), size = androidx.compose.ui.geometry.Size(1f, size.height * 0.3f))
        }
        // The mark: the grey card's place, never moving.
        val mx = size.width * GradeMath.stripFracOf(0.0).toFloat()
        val mark = Path().apply {
            moveTo(mx - 5.dp.toPx(), size.height)
            lineTo(mx + 5.dp.toPx(), size.height)
            lineTo(mx, size.height - 7.dp.toPx())
            close()
        }
        drawPath(mark, if (off) SettingInk else Color.White)
    }
}

/** The paper table read at [linear], between entries. */
private fun lutValue(lut: FloatArray, linear: Double): Float {
    if (lut.size < 2) return linear.toFloat()
    val x = linear * (lut.size - 1)
    val i = x.toInt().coerceAtMost(lut.size - 2)
    val f = (x - i).toFloat()
    return (lut[i] * (1 - f) + lut[i + 1] * f).coerceIn(0f, 1f)
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
