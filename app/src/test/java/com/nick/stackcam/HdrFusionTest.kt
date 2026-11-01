package com.nick.stackcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class HdrFusionTest {
    private val W = 480
    private val H = 360

    // Non-periodic texture in -0.5..0.5 (blurred random field) so alignment is unambiguous.
    private val texture: DoubleArray by lazy {
        val r = Random(99)
        var f = DoubleArray(W * H) { r.nextDouble(-1.0, 1.0) }
        repeat(2) {
            val g = DoubleArray(W * H)
            for (y in 1 until H - 1) for (x in 1 until W - 1) {
                var a = 0.0
                for (oy in -1..1) for (ox in -1..1) a += f[(y + oy) * W + x + ox]
                g[y * W + x] = a / 9
            }
            f = g
        }
        val m = f.maxOf { kotlin.math.abs(it) }
        DoubleArray(W * H) { i -> 0.5 * f[i] / m + 0.15 * sin(i % W * 0.21) * sin(i / W * 0.17) }
    }

    /** Scene radiance: dark third (shadows), mid third, very bright third (sky/window). */
    private fun radiance(x: Int, y: Int): Double {
        val tex = texture[y * W + x].coerceIn(-0.6, 0.6)
        val base = when {
            x < W / 3 -> 0.02
            x < 2 * W / 3 -> 0.2
            else -> 2.0
        }
        return base * (1 + tex)
    }

    /** Simulated camera: exposure in EV, gamma 2.2 response, 8-bit clip, noise. */
    private fun shoot(ev: Int, rnd: Random, dx: Int = 0, dy: Int = 0, obj: Boolean = false): IntArray {
        val gain = 2.0.pow(ev)
        return IntArray(W * H) { i ->
            val x = (i % W + dx).coerceIn(0, W - 1); val y = (i / W + dy).coerceIn(0, H - 1)
            var r = radiance(x, y)
            if (obj && x in 200..260 && y in 140..200) r = 0.01
            val v = (255 * (r * gain).pow(1 / 2.2).coerceAtMost(1.0) + rnd.nextDouble(-2.0, 2.0)).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /** Local detail: std of (pixel - 5x5 box mean) inside a region. */
    private fun detail(px: IntArray, x0: Int, x1: Int): Double {
        var s = 0.0; var s2 = 0.0; var n = 0
        for (y in 40 until H - 40) for (x in x0 + 10 until x1 - 10) {
            var m = 0.0
            for (oy in -2..2) for (ox in -2..2) m += px[(y + oy) * W + x + ox] and 0xFF
            val d = (px[y * W + x] and 0xFF) - m / 25
            s += d; s2 += d * d; n++
        }
        val mean = s / n
        return sqrt(s2 / n - mean * mean)
    }

    private fun fuse(frames: List<IntArray>, ghost: Boolean, factor: Int = 2): IntArray {
        val out = IntArray(W * H)
        HdrFusion.fuse(frames, W, H, 1, out, factor = factor, ghost = ghost, contrast = 0f, saturation = 1f)
        return out
    }

    @Test
    fun fusionKeepsShadowAndHighlightDetail() {
        val rnd = Random(3)
        val dark = shoot(-2, rnd); val mid = shoot(0, rnd); val bright = shoot(2, rnd)
        val out = fuse(listOf(dark, mid, bright), ghost = true)
        val shadowsMid = detail(mid, 0, W / 3); val shadowsOut = detail(out, 0, W / 3)
        val hiMid = detail(mid, 2 * W / 3, W); val hiDark = detail(dark, 2 * W / 3, W); val hiOut = detail(out, 2 * W / 3, W)
        val midMid = detail(mid, W / 3, 2 * W / 3); val midOut = detail(out, W / 3, 2 * W / 3)
        println("shadow detail: 0EV=$shadowsMid fused=$shadowsOut | highlight detail: 0EV=$hiMid -2EV=$hiDark fused=$hiOut | mid: 0EV=$midMid fused=$midOut")
        assertTrue("highlights are clipped at 0EV", hiMid < 1.0)
        assertTrue("fused recovers highlight detail", hiOut > 0.5 * hiDark && hiOut > 3 * hiMid)
        assertTrue("fused lifts shadow detail", shadowsOut > 1.3 * shadowsMid)
        assertTrue("midtones keep detail", midOut > 0.7 * midMid)
    }

    @Test
    fun fullResolutionPathMatchesPyramidOnlyPath() {
        val rnd = Random(4)
        val frames = listOf(shoot(-2, rnd), shoot(0, rnd), shoot(2, rnd))
        val a = fuse(frames, true, factor = 1)
        val b = fuse(frames, true, factor = 2)
        var s = 0.0
        for (i in a.indices) s += kotlin.math.abs((a[i] and 0xFF) - (b[i] and 0xFF))
        val mad = s / a.size
        println("factor1 vs factor2 mean abs diff = $mad")
        assertTrue(mad < 6.0)
    }

    @Test
    fun ghostRejectionRemovesMovingObject() {
        val rnd = Random(5)
        val clean = listOf(shoot(-2, rnd), shoot(0, rnd), shoot(2, rnd))
        val withObj = listOf(clean[0], clean[1], shoot(2, rnd, obj = true)) // object only in +2EV frame
        val ref = fuse(clean, true)
        fun diff(o: IntArray): Double {
            var s = 0.0; var n = 0
            for (y in 140..200) for (x in 200..260) { s += kotlin.math.abs((o[y * W + x] and 0xFF) - (ref[y * W + x] and 0xFF)); n++ }
            return s / n
        }
        val on = diff(fuse(withObj, true)); val off = diff(fuse(withObj, false))
        println("ghost area error: rejection on=$on off=$off")
        assertTrue(off > 5.0)
        assertTrue(on < 0.35 * off)
    }

    @Test
    fun equalisedAlignmentWorksAcrossExposures() {
        val rnd = Random(6)
        val mid = shoot(0, rnd)
        val dark = shoot(-2, rnd, dx = 6, dy = -4) // dark(x,y) = scene(x+6, y-4)
        val rp = StackCore.pyramid(HdrFusion.equalize(Gray.fromArgb(mid, W, H)), 4)
        val fp = StackCore.pyramid(HdrFusion.equalize(Gray.fromArgb(dark, W, H)), 4)
        val (sx, sy) = StackCore.estimateShift(rp, fp)
        println("cross-exposure shift = $sx, $sy")
        assertEquals(-6f, sx, 0.6f)
        assertEquals(4f, sy, 0.6f)
    }
}
