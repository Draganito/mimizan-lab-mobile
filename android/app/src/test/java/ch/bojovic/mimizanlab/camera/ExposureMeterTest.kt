package ch.bojovic.mimizanlab.camera

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureMeterTest {
    private fun meter(mode: MeteringMode, equiv: Int = 24) =
        ExposureMeter(isoMin = 50, isoMax = 3200, nsMin = 100_000L, nsMax = 1_000_000_000L, equivalentMm = equiv, mode = mode)

    private fun reading(highlight: Double, grey: Double = highlight * 0.3, spot: Double? = null) =
        Reading(highlight = highlight, grey = grey, spotGrey = spot, clipped = 0.0)

    private fun h(iso: Int, ns: Long) = iso * ns / 1e9

    private fun assertStops(expected: Double, actual: Double) =
        assertTrue("expected $expected stops, got $actual", abs(expected - actual) < 0.02)

    @Test
    fun seedIsTheFirstPlan() {
        val m = meter(MeteringMode.HIGHLIGHTS)
        m.seed(100, 16_666_667L)
        // The same exposure at the lowest ISO whose time fits the caps.
        assertEquals(50, m.plan.previewIso)
        assertEquals(33_333_333L, m.plan.previewNs)
        assertStops(0.0, ExposureMeter.log2(h(m.plan.previewIso, m.plan.previewNs) / h(100, 16_666_667L)))
        // The still is not capped here, so it is the same pair (to the nanosecond the division leaves).
        assertEquals(m.plan.previewIso, m.plan.stillIso)
        assertTrue(abs(m.plan.previewNs - m.plan.stillNs) <= 1)
        assertFalse(m.plan.flashRequired)
    }

    @Test
    fun oneProbePlacesTheHighlight() {
        val m = meter(MeteringMode.HIGHLIGHTS)
        m.seed(100, 16_666_667L)
        // Two stops under the target: the exposure quadruples.
        assertTrue(m.onProbe(reading(ExposureMeter.HIGHLIGHT_TARGET / 4), 100, 16_666_667L))
        val want = h(100, 16_666_667L) * 4
        // Preview: 1/15 s is over the 1/30 s cap, so ISO carries the rest.
        assertEquals(ExposureMeter.PREVIEW_CAP_NS, m.plan.previewNs)
        assertStops(0.0, ExposureMeter.log2(h(m.plan.previewIso, m.plan.previewNs) / want))
        // Still: the 2/24 s handheld cap, ISO 80 makes up the rest.
        assertEquals(83_333_333L, m.plan.stillNs)
        assertEquals(80, m.plan.stillIso)
        assertStops(0.0, ExposureMeter.log2(h(m.plan.stillIso, m.plan.stillNs) / want))
    }

    @Test
    fun deadBandKeepsASmallErrorStill() {
        val m = meter(MeteringMode.HIGHLIGHTS)
        m.seed(100, 10_000_000L)
        val before = m.plan
        assertFalse(m.onProbe(reading(ExposureMeter.HIGHLIGHT_TARGET * 1.07), 100, 10_000_000L))
        assertEquals(before, m.plan)
    }

    @Test
    fun clippedHighlightStepsDownAStop() {
        val m = meter(MeteringMode.HIGHLIGHTS)
        m.seed(400, 10_000_000L)
        assertTrue(m.onProbe(reading(1.0, grey = 0.3), 400, 10_000_000L))
        assertStops(-1.0, ExposureMeter.log2(h(m.plan.stillIso, m.plan.stillNs) / h(400, 10_000_000L)))
    }

    @Test
    fun aLampInADarkRoomDoesNotBlackOutThePicture() {
        val m = meter(MeteringMode.HIGHLIGHTS)
        // The grey is already under the floor: a clipped highlight is left alone.
        assertStops(0.0, m.correction(reading(1.0, grey = ExposureMeter.GREY_FLOOR / 4)))
        // Just above the floor: the step down stops at the floor.
        val g = ExposureMeter.GREY_FLOOR * 1.5
        assertStops(ExposureMeter.log2(ExposureMeter.GREY_FLOOR / g), m.correction(reading(1.0, grey = g)))
    }

    @Test
    fun greyModePrefersTheSpot() {
        val m = meter(MeteringMode.GREY)
        // Frame grey already on the card, spot a stop under: the spot wins.
        assertStops(1.0, m.correction(reading(0.5, grey = 0.18, spot = 0.09)))
        assertStops(0.0, m.correction(reading(0.5, grey = 0.18)))
    }

    @Test
    fun biasMovesTheTarget() {
        val m = meter(MeteringMode.GREY)
        m.biasStops = 1.0
        assertStops(1.0, m.correction(reading(0.5, grey = 0.18)))
    }

    @Test
    fun aProbeStillInFlightIsSkippedThreeTimes() {
        val m = meter(MeteringMode.GREY)
        m.seed(100, 10_000_000L)
        // The probe shows twice the commanded exposure: not ours yet.
        val stale = reading(0.5, grey = 0.045)
        assertFalse(m.onProbe(stale, 200, 10_000_000L))
        assertFalse(m.onProbe(stale, 200, 10_000_000L))
        assertFalse(m.onProbe(stale, 200, 10_000_000L))
        // The fourth is taken as the HAL's own coercion and acted on.
        assertTrue(m.onProbe(stale, 200, 10_000_000L))
    }

    @Test
    fun mainsFlickerSnapsTheTime() {
        val m = meter(MeteringMode.GREY)
        m.flickerHz = 50
        // 19 ms at base ISO becomes 10 ms at nearly twice the ISO.
        val (iso, ns) = m.split(50 * 0.019, ExposureMeter.PREVIEW_CAP_NS)
        assertEquals(10_000_000L, ns)
        assertEquals(95, iso)
        // Under one period nothing snaps.
        assertEquals(5_000_000L, m.split(50 * 0.005, ExposureMeter.PREVIEW_CAP_NS).second)
    }

    @Test
    fun aLongLensShortensTheStill() {
        val m = meter(MeteringMode.GREY, equiv = 110)
        m.seed(3200, 125_000_000L)
        // 2/110 s is the cap; ISO is already at its top.
        assertTrue(m.plan.stillNs <= 2_000_000_000L / 110 + 1)
        assertEquals(3200, m.plan.stillIso)
    }

    @Test
    fun tooDarkAsksForTheFlash() {
        val m = meter(MeteringMode.GREY)
        m.seed(3200, 83_000_000L)
        // Probes are taken with the viewfinder's pair.
        assertTrue(m.onProbe(reading(0.02, grey = 0.01), m.plan.previewIso, m.plan.previewNs))
        assertTrue(m.plan.flashRequired)
        assertEquals(3200, m.plan.stillIso)
        assertEquals(ExposureMeter.PREVIEW_CAP_NS, m.plan.previewNs)
        // And a bright scene clears it.
        assertTrue(m.onProbe(reading(0.9, grey = 0.5), m.plan.previewIso, m.plan.previewNs))
        assertFalse(m.plan.flashRequired)
    }

    @Test
    fun previewNeverExceedsItsCap() {
        val m = meter(MeteringMode.GREY)
        m.seed(50, 1_000_000_000L)
        assertEquals(ExposureMeter.PREVIEW_CAP_NS, m.plan.previewNs)
        assertTrue(m.plan.stillNs <= ExposureMeter.STILL_CAP_NS)
    }
}
