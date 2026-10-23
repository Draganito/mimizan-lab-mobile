package ch.bojovic.mimizanlab.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensesTest {
    @Test
    fun pixelSizeIsKept() {
        assertEquals(4080 to 3072, chooseRawSize(listOf(4080 to 3072)))
    }

    @Test
    fun binnedSizeWinsOverFullSensor() {
        assertEquals(4000 to 3000, chooseRawSize(listOf(8160 to 6144, 4000 to 3000)))
    }

    @Test
    fun largestUnderTheCapWins() {
        assertEquals(4000 to 3000, chooseRawSize(listOf(2000 to 1500, 4000 to 3000)))
    }

    @Test
    fun onlyOversizedFrameIsStillUsed() {
        assertEquals(8160 to 6144, chooseRawSize(listOf(8160 to 6144)))
    }

    @Test
    fun emptyListHasNoSize() {
        assertNull(chooseRawSize(emptyList()))
    }

    @Test
    fun singleFocalIsUnchanged() {
        assertEquals(6.9f, focalForLens(floatArrayOf(6.9f), 12.4f))
    }

    @Test
    fun logicalCameraPicksTheNormalLens() {
        // Ultrawide 2.2 mm and main 6.9 mm on a sensor whose 6.9 mm is ~24 mm equivalent.
        val diag = 6.9f * 43.27f / 24f
        assertEquals(6.9f, focalForLens(floatArrayOf(2.2f, 6.9f, 23f), diag))
    }
}
