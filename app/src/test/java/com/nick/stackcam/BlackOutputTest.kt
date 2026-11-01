package com.nick.stackcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/** Regression tests for v2.4 "photos are completely black". */
class BlackOutputTest {

    /**
     * Planes laid out like a real Pixel / Qualcomm YUV_420_888 ImageProxy: Y rows padded to
     * rowStride with a short last row; U and V are views into ONE interleaved VU buffer
     * (pixelStride 2), V starting one byte before U, each plane's last row short too.
     */
    private class DevicePlanes(val w: Int, val h: Int, val yRow: Int, val uvRow: Int, yVal: (Int, Int) -> Int, uVal: (Int, Int) -> Int, vVal: (Int, Int) -> Int) {
        val y: ByteBuffer
        val u: ByteBuffer
        val v: ByteBuffer
        init {
            val yb = ByteArray(yRow * (h - 1) + w) { 0x55 } // padding garbage
            for (r in 0 until h) for (c in 0 until w) yb[r * yRow + c] = yVal(c, r).toByte()
            val ch = h / 2; val cw = w / 2
            val vu = ByteArray(uvRow * (ch - 1) + cw * 2) { 0x33 }
            for (r in 0 until ch) for (c in 0 until cw) {
                vu[r * uvRow + 2 * c] = vVal(c, r).toByte()
                vu[r * uvRow + 2 * c + 1] = uVal(c, r).toByte()
            }
            y = ByteBuffer.allocateDirect(yb.size).put(yb).also { it.flip() }
            val direct = ByteBuffer.allocateDirect(vu.size).put(vu).also { it.flip() }
            v = direct.duplicate().also { it.limit(vu.size - 1) }.slice()
            u = direct.duplicate().also { it.position(1) }.slice()
        }
    }

    /** NV21 -> ARGB, BT.601 full range (what YuvImage/JPEG roughly do). */
    private fun nv21ToArgb(nv: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (r in 0 until h) for (c in 0 until w) {
            val yv = nv[r * w + c].toInt() and 0xFF
            val o = w * h + (r / 2) * w + (c / 2) * 2
            val vv = (nv[o].toInt() and 0xFF) - 128
            val uv = (nv[o + 1].toInt() and 0xFF) - 128
            val rr = (yv + 1.402 * vv).toInt().coerceIn(0, 255)
            val gg = (yv - 0.344 * uv - 0.714 * vv).toInt().coerceIn(0, 255)
            val bb = (yv + 1.772 * uv).toInt().coerceIn(0, 255)
            out[r * w + c] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return out
    }

    @Test fun deviceLayoutIsConvertedExactly() {
        val w = 64; val h = 48
        val p = DevicePlanes(w, h, yRow = 128, uvRow = 128,
            yVal = { c, r -> 20 + (c * 3 + r * 2) % 200 }, uVal = { c, r -> 100 + (c + r) % 50 }, vVal = { c, r -> 140 + (c * 2 + r) % 40 })
        val nv = CaptureCore.toNv21(w, h, p.y, 128, 1, p.u, p.v, 128, 2)
        for (r in 0 until h) for (c in 0 until w) assertEquals(20 + (c * 3 + r * 2) % 200, nv[r * w + c].toInt() and 0xFF)
        var o = w * h
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            assertEquals(140 + (c * 2 + r) % 40, nv[o++].toInt() and 0xFF)
            assertEquals(100 + (c + r) % 50, nv[o++].toInt() and 0xFF)
        }
    }

    @Test fun conversionKeepsBrightness() {
        val w = 160; val h = 120
        val rnd = Random(3)
        val yv = IntArray(w * h) { 60 + rnd.nextInt(120) }
        val p = DevicePlanes(w, h, yRow = 192, uvRow = 192, yVal = { c, r -> yv[r * w + c] }, uVal = { _, _ -> 128 }, vVal = { _, _ -> 128 })
        val nv = CaptureCore.toNv21(w, h, p.y, 192, 1, p.u, p.v, 192, 2)
        val inMean = yv.average()
        assertEquals(inMean, ImageChecks.meanLumaNv21(nv, w, h), 0.01)
        val argbMean = ImageChecks.meanLuma(nv21ToArgb(nv, w, h))
        assertEquals(inMean, argbMean, 1.5)
    }

    @Test fun conversionDoesNotDependOnBufferPosition() {
        // Someone else (e.g. a previous read) may have moved the buffer position/limit.
        val w = 32; val h = 16
        val p = DevicePlanes(w, h, 48, 48, { c, _ -> 100 + c }, { _, _ -> 120 }, { _, _ -> 130 })
        p.y.position(p.y.limit()); p.u.position(5); p.v.position(7)
        val nv = CaptureCore.toNv21(w, h, p.y, 48, 1, p.u, p.v, 48, 2)
        assertEquals(100 + 31, nv[31].toInt() and 0xFF)
        assertEquals(130, nv[w * h].toInt() and 0xFF)
        // ...and the caller's buffers are left untouched.
        assertEquals(p.y.limit(), p.y.position())
    }

    @Test fun cropRectIsHonoured() {
        val w = 40; val h = 30
        val p = DevicePlanes(w, h, 64, 64, { c, r -> if (c >= 4 && r >= 2) 200 else 0 }, { c, r -> if (c >= 2 && r >= 1) 90 else 0 }, { _, _ -> 160 })
        val cw = 32; val ch = 24
        val nv = CaptureCore.toNv21(cw, ch, p.y, 64, 1, p.u, p.v, 64, 2, cropLeft = 4, cropTop = 2)
        for (i in 0 until cw * ch) assertEquals(200, nv[i].toInt() and 0xFF)
        for (i in 0 until cw * ch / 4) {
            assertEquals(160, nv[cw * ch + 2 * i].toInt() and 0xFF)
            assertEquals(90, nv[cw * ch + 2 * i + 1].toInt() and 0xFF)
        }
    }

    @Test fun planarLayoutWithPixelStride1() {
        val w = 20; val h = 10
        val yb = ByteArray(24 * (h - 1) + w)
        for (r in 0 until h) for (c in 0 until w) yb[r * 24 + c] = 77
        val ub = ByteArray(12 * (h / 2 - 1) + w / 2) { 111 }
        val vb = ByteArray(12 * (h / 2 - 1) + w / 2) { 99 }
        val nv = CaptureCore.toNv21(w, h, ByteBuffer.wrap(yb), 24, 1, ByteBuffer.wrap(ub), ByteBuffer.wrap(vb), 12, 1)
        assertEquals(77.0, ImageChecks.meanLumaNv21(nv, w, h), 0.0)
        assertEquals(99, nv[w * h].toInt()); assertEquals(111, nv[w * h + 1].toInt())
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun mergeKeepsBrightnessForEveryBurstSize() {
        val w = 120; val h = 90
        for (frames in listOf(6, 10, 12, 15, 20)) {
            val rnd = Random(frames)
            val scene = IntArray(w * h) { 40 + (it * 7) % 180 }
            fun noisy() = IntArray(w * h) { gray((scene[it] + rnd.nextInt(-6, 7)).coerceIn(0, 255)) }
            val ref = noisy()
            val refMean = ImageChecks.meanLuma(ref)
            val m = Merger(w, h, ref.copyOf(), 10, 32)
            repeat(frames - 1) { val f = noisy(); m.addFrame(0, 0, h, w) { row, buf -> System.arraycopy(f, row * w, buf, 0, w) } }
            val out = m.finish()
            assertEquals("burst $frames", refMean, ImageChecks.meanLuma(out), 1.0)
        }
    }

    @Test fun mergeOfWhiteFramesDoesNotOverflow() {
        val w = 16; val h = 16
        val white = IntArray(w * h) { gray(255) }
        val m = Merger(w, h, white.copyOf(), 16, 48)
        repeat(30) { m.addFrame(0, 0, h, w) { row, buf -> System.arraycopy(white, row * w, buf, 0, w) } }
        assertEquals(255.0, ImageChecks.meanLuma(m.finish()), 0.01)
    }

    @Test fun mergeWithEverythingRejectedReturnsReference() {
        val w = 16; val h = 16
        val ref = IntArray(w * h) { gray(150) }
        val other = IntArray(w * h) { gray(0) }
        val m = Merger(w, h, ref.copyOf(), 10, 32)
        repeat(5) { m.addFrame(3, -2, h, w) { row, buf -> System.arraycopy(other, row * w, buf, 0, w) } }
        assertEquals(150.0, ImageChecks.meanLuma(m.finish()), 0.01)
    }

    @Test fun guardDetectsBlackResults() {
        assertTrue(ImageChecks.mergedLooksBroken(0.0, 120.0))
        assertTrue(ImageChecks.mergedLooksBroken(2.0, 30.0))
        assertTrue(ImageChecks.mergedLooksBroken(10.0, 120.0))
        assertFalse(ImageChecks.mergedLooksBroken(100.0, 120.0))
        assertFalse(ImageChecks.mergedLooksBroken(60.0, 120.0)) // HDR may legitimately be darker
        assertFalse(ImageChecks.mergedLooksBroken(1.0, 4.0))    // a genuinely black scene
        assertFalse(ImageChecks.mergedLooksBroken(0.0, 0.0))
    }

    @Test fun meanLumaOfColours() {
        assertEquals(0.0, ImageChecks.meanLuma(IntArray(10) { 0xFF000000.toInt() }), 0.0)
        assertEquals(255.0, ImageChecks.meanLuma(IntArray(10) { -1 }), 0.01)
        assertEquals(0.0, ImageChecks.meanLuma(IntArray(0)), 0.0)
    }

    @Test fun viewerSampleSize() {
        // 12 MP photo for a 2340 px tall screen (maxSide 3200): full resolution, never "0".
        assertEquals(1, ImageChecks.sampleSizeFor(4032, 3024, 3200))
        assertEquals(2, ImageChecks.sampleSizeFor(12096, 9072, 3200)) // SR 3x size
        assertEquals(4, ImageChecks.sampleSizeFor(12800, 9600, 3200))
        assertEquals(1, ImageChecks.sampleSizeFor(0, 0, 3200))
        assertEquals(1, ImageChecks.sampleSizeFor(100, 100, 0))
    }
}
