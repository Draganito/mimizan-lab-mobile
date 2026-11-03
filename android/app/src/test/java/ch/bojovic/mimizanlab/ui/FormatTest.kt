package ch.bojovic.mimizanlab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun exposureLineMarksManualAndACompensation() {
        assertEquals("100  ·  1/120", Format.exposureLine(100, 8_333_333L, manual = false, evSteps = 0, evStep = 1.0 / 6))
        assertEquals("100  ·  1/120  ·  EV +1.0", Format.exposureLine(100, 8_333_333L, manual = false, evSteps = 6, evStep = 1.0 / 6))
        assertEquals("M  400  ·  1/30", Format.exposureLine(400, 33_333_333L, manual = true, evSteps = 6, evStep = 1.0 / 6))
        assertEquals("—  ·  —", Format.exposureLine(null, null, manual = false, evSteps = 0, evStep = 1.0 / 6))
    }
}
