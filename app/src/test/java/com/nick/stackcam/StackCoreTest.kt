package com.nick.stackcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class StackCoreTest {
    private val W = 800
    private val H = 600

    // Non-periodic textured scene: blurred random field (larger than frames).
    private val SW = 1000
    private val SH = 800
    private val field: IntArray by lazy {
        val r = Random(42)
        var f = DoubleArray(SW * SH) { r.nextDouble(0.0, 255.0) }
        repeat(3) {
            val g = DoubleArray(SW * SH)
            for (y in 1 until SH - 1) for (x in 1 until SW - 1) {
                var acc = 0.0
                for (oy in -1..1) for (ox in -1..1) acc += f[(y + oy) * SW + x + ox]
                g[y * SW + x] = acc / 9
            }
            f = g
        }
        // stretch contrast
        IntArray(SW * SH) { ((f[it] - 128) * 4 + 128 + 50 * sin(it % SW * 0.01)).toInt().coerceIn(0, 255) }
    }
    private fun scene(x: Int, y: Int): Int = field[(y + 100) * SW + (x + 100)]

    private fun frame(dx: Int, dy: Int, noise: Double, rnd: Random): IntArray {
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val v = (scene(x + dx, y + dy) + rnd.nextDouble(-noise, noise)).toInt().coerceIn(0, 255)
            px[y * W + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return px
    }

    @Test
    fun alignmentRecoversShift() {
        val rnd = Random(1)
        val ref = frame(0, 0, 5.0, rnd)
        val shifted = frame(37, -21, 5.0, rnd)  // frame(x,y) = scene(x+37, y-21)
        val rp = StackCore.pyramid(Gray.fromArgb(ref, W, H), 5)
        val fp = StackCore.pyramid(Gray.fromArgb(shifted, W, H), 5)
        val (sx, sy) = StackCore.estimateShift(rp, fp)
        // ref(x,y)=scene(x,y)=frame(x-37, y+21) -> dx=-37, dy=21
        assertEquals(-37f, sx, 0.6f)
        assertEquals(21f, sy, 0.6f)
    }

    @Test
    fun mergeReducesNoise() {
        val rnd = Random(2)
        val shifts = listOf(0 to 0, 5 to 3, -4 to 7, 9 to -6, -8 to -2, 2 to 10, 6 to 6, -3 to -9)
        val frames = shifts.map { (dx, dy) -> frame(dx, dy, 30.0, rnd) }
        val ref = frames[0].copyOf()
        val rp = StackCore.pyramid(Gray.fromArgb(ref, W, H), 5)
        val merger = Merger(W, H, ref, 16, 48)
        for (i in 1 until frames.size) {
            val fp = StackCore.pyramid(Gray.fromArgb(frames[i], W, H), 5)
            val (sx, sy) = StackCore.estimateShift(rp, fp)
            val f = frames[i]
            merger.addFrame(Math.round(sx), Math.round(sy), H, W) { row, buf -> System.arraycopy(f, row * W, buf, 0, W) }
        }
        val out = merger.finish()
        fun err(px: IntArray): Double {
            var s = 0.0; var n = 0
            for (y in 20 until H - 20) for (x in 20 until W - 20) { s += abs((px[y * W + x] and 0xFF) - scene(x, y)); n++ }
            return s / n
        }
        val before = err(frames[0])
        val after = err(out)
        println("mean abs error before=$before after=$after")
        assertTrue(after < before * 0.75)
        Sharpen.unsharp(out, W, H, 0.5f)
    }
}
