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
}
