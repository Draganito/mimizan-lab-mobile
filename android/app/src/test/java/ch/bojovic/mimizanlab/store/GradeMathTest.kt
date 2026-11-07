package ch.bojovic.mimizanlab.store

import ch.bojovic.mimizanlab.engine.DevelopSettings
import ch.bojovic.mimizanlab.engine.Filter
import ch.bojovic.mimizanlab.engine.PeakLimits
import ch.bojovic.mimizanlab.engine.PlateFilters
import ch.bojovic.mimizanlab.engine.Process
import ch.bojovic.mimizanlab.engine.PeakMeasured
import org.junit.Assert.assertEquals
import org.junit.Test

/** The grade arithmetic with the core's limit passed in by hand: ±3 stops of paper. */
class GradeMathTest {
    private val limit = 3.0
    private val eps = 1e-9

    @Test
    fun snapStopsRoundsToTwelfthsAndClamps() {
        assertEquals(1.0 / 12.0, GradeMath.snapStops(0.09, limit), eps)
        assertEquals(0.0, GradeMath.snapStops(0.04, limit), eps)
        assertEquals(3.0, GradeMath.snapStops(7.0, limit), eps)
        assertEquals(-3.0, GradeMath.snapStops(-3.4, limit), eps)
        assertEquals(0.0, GradeMath.snapStops(Double.NaN, limit), eps)
        assertEquals(0.0, GradeMath.snapStops(Double.POSITIVE_INFINITY, limit), eps)
        assertEquals(2.0, GradeMath.snapStops(2.0, 2.0), eps)
        assertEquals(-2.0, GradeMath.snapStops(-2.7, 2.0), eps)
    }

    @Test
    fun shiftGradeMovesBothAndKeepsTheGap() {
        val p = GradeMath.shiftGrade(0.5, -0.25, 1.0, limit)
        assertEquals(1.5, p.soft, eps)
        assertEquals(0.75, p.hard, eps)
    }

    @Test
    fun shiftGradeStopsAtTheLimitSoTheGapStays() {
        // Up: the higher slider has 1 stop of room.
        val up = GradeMath.shiftGrade(1.0, 2.0, 2.5, limit)
        assertEquals(2.0, up.soft, eps)
        assertEquals(3.0, up.hard, eps)
        // Down: the lower slider has 2 stops of room.
        val down = GradeMath.shiftGrade(-1.0, 1.0, -5.0, limit)
        assertEquals(-3.0, down.soft, eps)
        assertEquals(-1.0, down.hard, eps)
        // A pair spanning the whole range cannot move.
        val full = GradeMath.shiftGrade(-3.0, 3.0, 1.0, limit)
        assertEquals(-3.0, full.soft, eps)
        assertEquals(3.0, full.hard, eps)
    }

    @Test
    fun shiftGradeSnapsTheDeltaNotThePair() {
        // 0.3 stops is 3.6 twelfths: the nearest twelfth is 4.
        val p = GradeMath.shiftGrade(0.0, 0.0, 0.3, limit)
        assertEquals(4.0 / 12.0, p.soft, eps)
        assertEquals(4.0 / 12.0, p.hard, eps)
        assertEquals(0.0, GradeMath.shiftGrade(0.0, 0.0, Double.NaN, limit).soft, eps)
    }

    // The one grade slider: the same pairs the two sliders set, nothing else.

    @Test
    fun withContrastKeepsTheMeanAndSetsTheGap() {
        val g = GradeMath.withContrast(-1.0, 0.5, 2.5, limit)
        assertEquals(2.5, g.hard - g.soft, eps)
        assertEquals(-0.25, (g.soft + g.hard) / 2, eps)
        assertEquals(-1.5, g.soft, eps)
        assertEquals(1.0, g.hard, eps)
    }

    @Test
    fun withContrastIsTheIdentityOnEveryPairTheSlidersCanSet() {
        // Every twelfth pair inside the limit comes back unchanged when its
        // own contrast is asked for: an untouched slider never moves a print.
        for (s in -36..36) for (h in -36..36) {
            val soft = s / 12.0
            val hard = h / 12.0
            val g = GradeMath.withContrast(soft, hard, hard - soft, limit)
            assertEquals("soft $soft hard $hard", soft, g.soft, eps)
            assertEquals("soft $soft hard $hard", hard, g.hard, eps)
        }
    }

    @Test
    fun withContrastLandsOnTheTwelfthGridInsideTheLimit() {
        for (s in -36..36) for (h in -36..36) for (c in -72..72) {
            val g = GradeMath.withContrast(s / 12.0, h / 12.0, c / 12.0, limit)
            assertEquals(g.soft, kotlin.math.round(g.soft * 12.0) / 12.0, eps)
            assertEquals(g.hard, kotlin.math.round(g.hard * 12.0) / 12.0, eps)
            assert(g.soft >= -limit - eps && g.soft <= limit + eps) { "soft ${g.soft}" }
            assert(g.hard >= -limit - eps && g.hard <= limit + eps) { "hard ${g.hard}" }
            // The mean moves by at most a twenty-fourth, and only to make the grid.
            val mean = (s + h) / 24.0
            assert(kotlin.math.abs((g.soft + g.hard) / 2 - mean) <= 1.0 / 24.0 + eps) { "mean" }
        }
    }

    @Test
    fun withContrastClampsSoNeitherExposureLeavesTheLimit() {
        // Mean +2: at most 1 stop of room each way, so the gap is at most 2.
        val g = GradeMath.withContrast(2.0, 2.0, 5.0, limit)
        assertEquals(1.0, g.soft, eps)
        assertEquals(3.0, g.hard, eps)
        val n = GradeMath.withContrast(2.0, 2.0, -5.0, limit)
        assertEquals(3.0, n.soft, eps)
        assertEquals(1.0, n.hard, eps)
        // A sixth-stop step on an odd-twelfth pair: the mean gives a twenty-fourth, lighter, once.
        val odd = GradeMath.withContrast(0.0, 1.0 / 12.0, 2.0 / 12.0, limit)
        assertEquals(2.0 / 12.0, odd.hard - odd.soft, eps)
        assertEquals(-1.0 / 12.0, odd.soft, eps)
        assertEquals(1.0 / 12.0, odd.hard, eps)
        assertEquals(0.0, GradeMath.withContrast(0.0, 0.0, Double.NaN, limit).hard, eps)
    }

    private val peakLimits = PeakLimits(radiusDefault = 0.7, radiusMin = 0.3, radiusMax = 3.0, amountMax = 2.0)
    private val standingPeak = Peak.standing(PeakMode.OFF, peakLimits)

    @Test
    fun recipeRoundTripsThroughItsFileAndRefusesOtherText() {
        val p = Print(Grade(0.5, -1.25), Peak(PeakMode.CUSTOM, 1.1, 0.6))
        val settings = DevelopSettings(filter = Filter.RED25, process = Process.PLATE, plateFilters = PlateFilters.WRATTEN)
        val back = Recipe.fromJson(Recipe.toJson(p, settings).toString(), limit, peakLimits, standingPeak)
        assertEquals(p, back?.first)
        assertEquals(settings, back?.second)
        // Out of range on every field: each is clamped, none refused.
        val wide = Recipe.fromJson("""{"soft": 9, "hard": -9, "peak": {"mode": "CUSTOM", "radius": 9, "amount": 9}}""", limit, peakLimits, standingPeak)
        assertEquals(Print(Grade(3.0, -3.0), Peak(PeakMode.CUSTOM, 3.0, 2.0)), wide?.first)
        assertEquals(null, Recipe.fromJson("not a print", limit, peakLimits, standingPeak))
        assertEquals(null, Recipe.fromJson("""{"soft": 1}""", limit, peakLimits, standingPeak))
    }

    @Test
    fun recipeFromAnOlderFileTakesTheStandingPeakAndMono() {
        // 0.15.0 wrote the grade alone; before 0.18 no process or filter.
        val p = Recipe.fromJson("""{"soft": 0.25, "hard": 0}""", limit, peakLimits, standingPeak)
        assertEquals(Print(Grade(0.25, 0.0), standingPeak), p?.first)
        assertEquals(DevelopSettings(), p?.second)
        // A peak object with an unknown mode is Auto at the defaults; unknown names read as the defaults too.
        val odd = Recipe.fromJson(
            """{"soft": 0, "hard": 0, "peak": {"mode": "SHARP"}, "process": "DUOTONE", "filter": "X1", "plateFilters": "?"}""",
            limit, peakLimits, standingPeak,
        )
        assertEquals(Peak(PeakMode.AUTO, 0.7, 0.0), odd?.first?.peak)
        assertEquals(DevelopSettings(), odd?.second)
    }

    @Test
    fun peakAppliedByMode() {
        val measured = PeakMeasured(radius = 1.2, amount = 0.8, found = true)
        assertEquals(0.9 to 0.0, Peak(PeakMode.OFF, 0.9, 0.5).applied(measured))
        assertEquals(1.2 to 0.8, Peak(PeakMode.AUTO, 0.9, 0.5).applied(measured))
        assertEquals(0.9 to 0.0, Peak(PeakMode.AUTO, 0.9, 0.5).applied(null))
        assertEquals(0.9 to 0.5, Peak(PeakMode.CUSTOM, 0.9, 0.5).applied(measured))
    }

    @Test
    fun shotModeMigratesFromTheStayOnFinderSwitch() {
        assertEquals(ShotMode.JPEG, GradeMath.shotModeOf(null, null))
        assertEquals(ShotMode.JPEG, GradeMath.shotModeOf(null, true))
        assertEquals(ShotMode.DEVELOPER, GradeMath.shotModeOf(null, false))
        assertEquals(ShotMode.RAW, GradeMath.shotModeOf("RAW", false))
        assertEquals(ShotMode.JPEG, GradeMath.shotModeOf("not a mode", false))
    }

    @Test
    fun peakModeMigratesFromTheCheckbox() {
        assertEquals(PeakMode.AUTO, GradeMath.peakModeOf(null, null))
        assertEquals(PeakMode.AUTO, GradeMath.peakModeOf(null, true))
        assertEquals(PeakMode.OFF, GradeMath.peakModeOf(null, false))
        assertEquals(PeakMode.OFF, GradeMath.peakModeOf("OFF", true))
        // A standing Custom from before 0.15.1 is Auto: the hand pair belongs to a frame now.
        assertEquals(PeakMode.AUTO, GradeMath.peakModeOf("CUSTOM", false))
        assertEquals(PeakMode.AUTO, GradeMath.peakModeOf("legacy", false))
    }
}
