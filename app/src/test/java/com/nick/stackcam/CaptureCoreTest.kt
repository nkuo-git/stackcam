package com.nick.stackcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CaptureCoreTest {

    /** Builds YUV_420_888-like planes from known Y/U/V values with the given strides. */
    private fun planes(w: Int, h: Int, yRow: Int, uvRow: Int, uvPix: Int): Array<ByteBuffer> {
        val y = ByteArray(yRow * (h - 1) + w)
        for (r in 0 until h) for (c in 0 until w) y[r * yRow + c] = (r * 7 + c).toByte()
        val cw = w / 2; val ch = h / 2
        val uvLen = uvRow * (ch - 1) + (cw - 1) * uvPix + 1
        val u = ByteArray(uvLen); val v = ByteArray(uvLen)
        for (r in 0 until ch) for (c in 0 until cw) {
            u[r * uvRow + c * uvPix] = (100 + r * 3 + c).toByte()
            v[r * uvRow + c * uvPix] = (200 + r * 5 + c).toByte()
        }
        return arrayOf(ByteBuffer.wrap(y), ByteBuffer.wrap(u), ByteBuffer.wrap(v))
    }

    private fun check(nv: ByteArray, w: Int, h: Int) {
        for (r in 0 until h) for (c in 0 until w) assertEquals((r * 7 + c).toByte(), nv[r * w + c])
        var p = w * h
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            assertEquals("V at $r,$c", (200 + r * 5 + c).toByte(), nv[p++])
            assertEquals("U at $r,$c", (100 + r * 3 + c).toByte(), nv[p++])
        }
    }

    @Test fun nv21FromSemiPlanarWithPadding() {
        val w = 8; val h = 6
        val (y, u, v) = planes(w, h, yRow = 12, uvRow = 12, uvPix = 2)
        check(CaptureCore.toNv21(w, h, y, 12, 1, u, v, 12, 2), w, h)
    }

    @Test fun nv21FromPlanar() {
        val w = 10; val h = 4
        val (y, u, v) = planes(w, h, yRow = 10, uvRow = 5, uvPix = 1)
        check(CaptureCore.toNv21(w, h, y, 10, 1, u, v, 5, 1), w, h)
    }

    @Test fun bracketKeepsTotalExposure() {
        val base = 10_000_000L // 1/100 s
        val (tDark, isoDark) = CaptureCore.bracketExposure(base, 100, -2, 10_000, 1_000_000_000, 50, 6400)
        assertEquals(base * 100 / 4.0, tDark.toDouble() * isoDark, base * 100 * 0.02)
        assertTrue(tDark < base)
        val (tBright, isoBright) = CaptureCore.bracketExposure(base, 100, 2, 10_000, 1_000_000_000, 50, 6400)
        assertEquals(base * 100 * 4.0, tBright.toDouble() * isoBright, base * 100 * 0.08)
        assertTrue(tBright <= 50_000_000L)
    }

    @Test fun brightBracketUsesIsoWhenShutterIsCapped() {
        val base = 40_000_000L
        val (t, iso) = CaptureCore.bracketExposure(base, 400, 2, 10_000, 1_000_000_000, 50, 6400)
        assertTrue(t <= 50_000_000L)
        assertTrue(iso > 400)
        assertEquals(base * 400 * 4.0, t.toDouble() * iso, base * 400 * 0.05)
    }

    @Test fun darkBracketRespectsMinimums() {
        val (t, iso) = CaptureCore.bracketExposure(20_000L, 50, -2, 15_000, 1_000_000_000, 50, 6400)
        assertTrue(t >= 15_000)
        assertTrue(iso >= 50)
    }

    @Test fun exposureMatching() {
        assertTrue(CaptureCore.exposureMatches(10_000_000, 100, 10_000_000, 100))
        assertTrue(CaptureCore.exposureMatches(5_000_000, 200, 10_000_000, 100))
        assertFalse(CaptureCore.exposureMatches(10_000_000, 400, 10_000_000, 100))
        assertFalse(CaptureCore.exposureMatches(null, 100, 10_000_000, 100))
    }

    @Test fun burstPlans() {
        assertEquals(6, CaptureCore.burstPlan(CaptureCore.Mode.NORMAL, 2).frames)
        assertEquals(10, CaptureCore.burstPlan(CaptureCore.Mode.NIGHT, 2).frames)
        assertEquals(12, CaptureCore.burstPlan(CaptureCore.Mode.SUPERRES, 3).frames)
        assertEquals(CaptureCore.HDR_PLAN.size, CaptureCore.burstPlan(CaptureCore.Mode.HDR, 2).frames)
    }
}
