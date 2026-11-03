package ch.bojovic.mimizanlab.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IspControlsTest {
    @Test
    fun encodeAndDecodeAreInverse() {
        var c = 0f
        while (c <= 1f) {
            assertEquals(c, IspControls.encode(IspControls.decode(c)), 2e-6f)
            c += 1f / 64f
        }
        assertEquals(0f, IspControls.encode(0f), 0f)
        assertEquals(1f, IspControls.encode(1f), 1e-6f)
        assertEquals(0f, IspControls.decode(0f), 0f)
        assertEquals(1f, IspControls.decode(1f), 1e-6f)
    }

    @Test
    fun decodeIsTheSrgbCurve() {
        // The linear toe, then the power section: the two standard reference points.
        assertEquals(0.04045f / 12.92f, IspControls.decode(0.04045f), 1e-7f)
        assertEquals(0.2140f, IspControls.decode(0.5f), 5e-4f)
    }

    @Test
    fun curvePointsRunFromBlackToWhiteAndIncrease() {
        val pts = IspControls.srgbCurvePoints(16)
        assertEquals(32, pts.size)
        assertEquals(0f, pts[0], 0f)
        assertEquals(0f, pts[1], 0f)
        assertEquals(1f, pts[30], 1e-6f)
        assertEquals(1f, pts[31], 1e-6f)
        for (i in 1 until 16) {
            assertTrue("input increases at $i", pts[2 * i] > pts[2 * (i - 1)])
            assertTrue("output increases at $i", pts[2 * i + 1] > pts[2 * (i - 1) + 1])
            assertEquals("each point is on the sRGB curve", IspControls.encode(pts[2 * i]), pts[2 * i + 1], 1e-6f)
        }
        // Squared spacing: half of the points sit in the lower quarter of the input.
        assertEquals(8, pts.indices.count { it % 2 == 0 && pts[it] < 0.25f })
    }

    @Test
    fun vendorCurveFindsTheQualcommPrefixes() {
        assertTrue(IspControls.vendorCurve(listOf("android.control.aeMode", "org.codeaurora.qcamera3.exposure_metering")))
        assertTrue(IspControls.vendorCurve(listOf("org.quic.camera.eis3enable")))
        assertFalse(IspControls.vendorCurve(listOf("android.control.aeMode", "com.google.pixel.camera.something")))
        assertFalse(IspControls.vendorCurve(emptyList()))
    }

    @Test
    fun samsungKeepsLensShadingOnThePreview() {
        assertFalse(IspControls.pinShadingOff("samsung"))
        assertFalse(IspControls.pinShadingOff("Samsung"))
        assertTrue(IspControls.pinShadingOff("Google"))
        assertTrue(IspControls.pinShadingOff("Xiaomi"))
    }
}
