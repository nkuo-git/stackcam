package com.nick.stackcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SuperResTest {
    private val S = 2
    private val LW = 160
    private val LH = 120
    private val HW = LW * S
    private val HH = LH * S

    // Quasi-periodic scene with frequencies above the low-res Nyquist limit (in HR pixel units).
    private val comps = run {
        val r = Random(11)
        List(7) { doubleArrayOf(r.nextDouble(0.04, 0.42) * (if (r.nextBoolean()) 1 else -1), r.nextDouble(0.04, 0.42), r.nextDouble(0.0, 2 * PI), r.nextDouble(12.0, 26.0)) }
    }
    // Non-periodic structure (random Gaussian blobs) so registration is unambiguous.
    private val blobs = run {
        val r = Random(12)
        List(260) { doubleArrayOf(r.nextDouble(0.0, 600.0), r.nextDouble(0.0, 500.0), r.nextDouble(1.5, 8.0), r.nextDouble(-45.0, 45.0)) }
    }
    private fun scene(x: Double, y: Double): Double {
        var v = 128.0
        for (c in comps) v += 0.5 * c[3] * sin(2 * PI * (c[0] * x + c[1] * y) + c[2])
        for (b in blobs) {
            val dx = x - b[0]; val dy = y - b[1]
            val d2 = dx * dx + dy * dy
            if (d2 < 16 * b[2] * b[2]) v += b[3] * kotlin.math.exp(-d2 / (2 * b[2] * b[2]))
        }
        return v
    }
    /** Average of the scene over a square of side [size] starting at (x, y) (HR units). */
    private fun area(x: Double, y: Double, size: Double): Double {
        val n = 4
        var s = 0.0
        for (j in 0 until n) for (i in 0 until n) s += scene(x + (i + 0.5) * size / n, y + (j + 0.5) * size / n)
        return s / (n * n)
    }
    private fun lowFrame(sx: Double, sy: Double, noise: Double, rnd: Random): IntArray = IntArray(LW * LH) { i ->
        val x = i % LW; val y = i / LW
        val v = (area(x * S + sx + 40, y * S + sy + 40, S.toDouble()) + rnd.nextDouble(-noise, noise)).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    private val truth: IntArray by lazy {
        IntArray(HW * HH) { i -> area((i % HW).toDouble() + 40, (i / HW).toDouble() + 40, 1.0).toInt().coerceIn(0, 255) }
    }
    private fun rmse(px: IntArray): Double {
        var s = 0.0; var n = 0
        for (y in 12 until HH - 12) for (x in 12 until HW - 12) {
            val d = (px[y * HW + x] and 0xFF) - truth[y * HW + x]; s += d * d; n++
        }
        return sqrt(s / n)
    }

    private fun burst(n: Int, rnd: Random): Pair<List<IntArray>, List<Pair<Double, Double>>> {
        val offs = List(n) { if (it == 0) 0.0 to 0.0 else rnd.nextDouble(-3.0, 3.0) to rnd.nextDouble(-3.0, 3.0) }
        return offs.map { (sx, sy) -> lowFrame(sx, sy, 3.0, rnd) } to offs
    }

    @Test
    fun lucasKanadeGivesSubPixelShift() {
        val rnd = Random(21)
        val (frames, offs) = burst(6, rnd)
        val rp = SuperRes.refPyramid(Gray.fromArgb(frames[0], LW, LH), 3)
        for (k in 1 until frames.size) {
            val fg = Gray.fromArgb(frames[k], LW, LH)
            val (dx, dy) = SuperRes.register(rp, fg)
            // frame(x) = scene(x*S + sx) -> frame(x - sx/S) = ref(x): dx = -sx/S
            val ex = -offs[k].first / S; val ey = -offs[k].second / S
            println("shift est=($dx,$dy) true=($ex,$ey)")
            assertEquals(ex.toFloat(), dx, 0.08f)
            assertEquals(ey.toFloat(), dy, 0.08f)
        }
    }

    @Test
    fun superResBeatsBicubicOfSingleFrame() {
        val rnd = Random(22)
        val (frames, _) = burst(12, rnd)
        val rp = SuperRes.refPyramid(Gray.fromArgb(frames[0], LW, LH), 3)
        val shifts = frames.mapIndexed { k, f -> if (k == 0) 0f to 0f else SuperRes.register(rp, Gray.fromArgb(f, LW, LH)) }
        val bic = SuperRes.bicubicUp(frames[0], LW, LH, S)
        val sr = SuperRes.reconstruct(frames, LW, LH, shifts, 0, S)
        val srRaw = rmse(sr)
        Sharpen.unsharpGauss(sr, HW, HH, 0.5f * S, 1.0f)
        val bicSharp = bic.copyOf(); Sharpen.unsharpGauss(bicSharp, HW, HH, 0.5f * S, 1.0f)
        val srSharp = rmse(sr)
        val bicErr = rmse(bic)
        println("RMSE vs ground truth: bicubic(single)=$bicErr bicubic+sharpen=${rmse(bicSharp)} superres=$srRaw superres+sharpen=$srSharp")
        assertTrue(srSharp < 0.8 * bicErr && srSharp < 0.8 * rmse(bicSharp))
        assertTrue(srRaw < bicErr)
    }

    @Test
    fun superResHandles3xAndMovingObject() {
        val s = 3
        val lw = 100; val lh = 80
        val rnd = Random(23)
        val offs = List(12) { if (it == 0) 0.0 to 0.0 else rnd.nextDouble(-4.0, 4.0) to rnd.nextDouble(-4.0, 4.0) }
        val frames = offs.mapIndexed { k, (sx, sy) ->
            IntArray(lw * lh) { i ->
                val x = i % lw; val y = i / lw
                var v = area(x * s + sx + 40, y * s + sy + 40, s.toDouble()) + rnd.nextDouble(-3.0, 3.0)
                if (k == 5 && x in 40..60 && y in 30..50) v = 250.0 // object present in one frame only
                val c = v.toInt().coerceIn(0, 255)
                (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            }
        }
        val rp = SuperRes.refPyramid(Gray.fromArgb(frames[0], lw, lh), 3)
        val shifts = frames.mapIndexed { k, f -> if (k == 0) 0f to 0f else SuperRes.register(rp, Gray.fromArgb(f, lw, lh)) }
        val sr = SuperRes.reconstruct(frames, lw, lh, shifts, 0, s)
        val W = lw * s
        var maxV = 0
        for (y in 33 * s until 48 * s) for (x in 43 * s until 58 * s) maxV = maxOf(maxV, sr[y * W + x] and 0xFF)
        println("3x output ${W}x${lh * s}, max value in ghost area=$maxV")
        assertTrue("moving object should be rejected", maxV < 235)
    }
}
