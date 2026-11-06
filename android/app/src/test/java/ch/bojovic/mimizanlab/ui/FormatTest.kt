package ch.bojovic.mimizanlab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun exposureLineMarksManual() {
        assertEquals("100  ·  1/120", Format.exposureLine(100, 8_333_333L, manual = false))
        assertEquals("M  400  ·  1/30", Format.exposureLine(400, 33_333_333L, manual = true))
        assertEquals("—  ·  —", Format.exposureLine(null, null, manual = false))
    }

    @Test
    fun paperShiftIsTheMeanAndAWordForTheGradeAndSilentAtTheBasePrint() {
        assertEquals(null, Format.paperShift(0.0, 0.0))
        // A grade alone (soft and hard apart, mean zero) is a word, not a number.
        assertEquals("softer", Format.paperShift(0.5, -0.5))
        assertEquals("harder", Format.paperShift(-0.5, 0.5))
        assertEquals("+0.50", Format.paperShift(0.5, 0.5))
        assertEquals("-1.00  softer", Format.paperShift(-0.75, -1.25))
        assertEquals("-1.00", Format.paperShift(-1.0, -1.0))
        assertEquals("+0.08", Format.paperShift(1.0 / 12.0, 1.0 / 12.0))
        assertEquals("+1.00  harder", Format.paperShift(0.5, 1.5))
    }
}
