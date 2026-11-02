package ch.bojovic.mimizanlab.store

import ch.bojovic.mimizanlab.engine.PeakMeasured
import org.junit.Assert.assertEquals
import org.junit.Test

/** The grade arithmetic with the core's limits passed in by hand: ±3 stops of paper, ±2 of compensation. */
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
        assertEquals(1.5, p.grade.soft, eps)
        assertEquals(0.75, p.grade.hard, eps)
        assertEquals(1.0, p.shift, eps)
    }

    @Test
    fun shiftGradeStopsAtTheLimitSoTheGapStays() {
        // Up: the higher slider has 1 stop of room.
        val up = GradeMath.shiftGrade(1.0, 2.0, 2.5, limit)
        assertEquals(2.0, up.grade.soft, eps)
        assertEquals(3.0, up.grade.hard, eps)
        assertEquals(1.0, up.shift, eps)
        // Down: the lower slider has 2 stops of room.
        val down = GradeMath.shiftGrade(-1.0, 1.0, -5.0, limit)
        assertEquals(-3.0, down.grade.soft, eps)
        assertEquals(-1.0, down.grade.hard, eps)
        assertEquals(-2.0, down.shift, eps)
        // A pair spanning the whole range cannot move.
        val full = GradeMath.shiftGrade(-3.0, 3.0, 1.0, limit)
        assertEquals(0.0, full.shift, eps)
    }

    @Test
    fun shiftGradeSnapsTheDeltaNotThePair() {
        // 0.3 stops is 3.6 twelfths: the nearest twelfth is 4.
        val p = GradeMath.shiftGrade(0.0, 0.0, 0.3, limit)
        assertEquals(4.0 / 12.0, p.shift, eps)
        assertEquals(4.0 / 12.0, p.grade.soft, eps)
        assertEquals(0.0, GradeMath.shiftGrade(0.0, 0.0, Double.NaN, limit).shift, eps)
    }

    @Test
    fun placeAddsShiftAndCompensationOnce() {
        val p = GradeMath.place(Grade(0.5, 0.25), 1.0, -0.5, limit)
        assertEquals(1.0, p.grade.soft, eps)
        assertEquals(0.75, p.grade.hard, eps)
        assertEquals(0.5, p.shift, eps)
        // The compensation is clamped together with the shift, not after it.
        val pinned = GradeMath.place(Grade(1.0, 1.0), 2.5, -1.0, limit)
        assertEquals(1.5, pinned.shift, eps)
    }

    @Test
    fun unplaceUndoesPlaceWhenNothingWasPinned() {
        val stored = Grade(0.5, -0.25)
        val placed = GradeMath.place(stored, 1.25, 0.5, limit)
        val back = GradeMath.unplace(placed.grade, placed.shift, limit)
        assertEquals(stored.soft, back.soft, eps)
        assertEquals(stored.hard, back.hard, eps)
    }

    @Test
    fun aSliderMovedOnAPlacedPrintMovesOnlyThatStoredExposure() {
        val stored = Grade(1.5, 1.5)
        val placed = GradeMath.place(stored, 2.5, 0.0, limit) // pinned: (3, 3), shift 1.5
        assertEquals(1.5, placed.shift, eps)
        // The user takes soft down to 2.5; hard stays where it is shown.
        val back = GradeMath.unplace(Grade(2.5, placed.grade.hard), placed.shift, limit)
        assertEquals(1.0, back.soft, eps)
        assertEquals(1.5, back.hard, eps)
        // Placed again, the print shows what the user set.
        val again = GradeMath.place(back, 2.5, 0.0, limit)
        assertEquals(2.5, again.grade.soft, eps)
        assertEquals(3.0, again.grade.hard, eps)
    }

    @Test
    fun unplaceClampsEachExposure() {
        val back = GradeMath.unplace(Grade(-3.0, 3.0), 1.5, limit)
        assertEquals(-3.0, back.soft, eps)
        assertEquals(1.5, back.hard, eps)
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
