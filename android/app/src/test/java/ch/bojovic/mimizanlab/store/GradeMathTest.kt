package ch.bojovic.mimizanlab.store

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

    @Test
    fun gradeRoundTripsThroughItsFileAndRefusesOtherText() {
        val g = Grade(0.5, -1.25)
        val back = Grade.fromJson(g.toJson().toString(), limit)
        assertEquals(g, back)
        assertEquals(Grade(3.0, -3.0), Grade.fromJson("""{"soft": 9, "hard": -9}""", limit))
        assertEquals(null, Grade.fromJson("not a grade", limit))
        assertEquals(null, Grade.fromJson("""{"soft": 1}""", limit))
    }

    @Test
    fun peakAppliedByMode() {
        val measured = PeakMeasured(radius = 1.2, amount = 0.8, found = true)
        assertEquals(0.9 to 0.0, GradeMath.peakApplied(PeakMode.OFF, 0.9, 0.5, measured, 0.7))
        assertEquals(1.2 to 0.8, GradeMath.peakApplied(PeakMode.AUTO, 0.9, 0.5, measured, 0.7))
        assertEquals(0.7 to 0.0, GradeMath.peakApplied(PeakMode.AUTO, 0.9, 0.5, null, 0.7))
        assertEquals(0.9 to 0.5, GradeMath.peakApplied(PeakMode.CUSTOM, 0.9, 0.5, measured, 0.7))
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
        assertEquals(PeakMode.CUSTOM, GradeMath.peakModeOf("CUSTOM", false))
        assertEquals(PeakMode.AUTO, GradeMath.peakModeOf("legacy", false))
    }
}
