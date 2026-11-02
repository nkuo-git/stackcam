package com.nick.stackcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class NightCoreTest {
    private val W = 640
    private val H = 480
    private val SW = 800
    private val SH = 640

    /** Smooth, textured, non-periodic scene 0..1 (larger than the frames). */
    private val field: FloatArray by lazy {
        val r = java.util.Random(7)
        var f = FloatArray(SW * SH) { r.nextFloat() }
        repeat(3) {
            val g = FloatArray(SW * SH)
            for (y in 1 until SH - 1) for (x in 1 until SW - 1) {
                var a = 0f
                for (oy in -1..1) for (ox in -1..1) a += f[(y + oy) * SW + x + ox]
                g[y * SW + x] = a / 9
            }
            f = g
        }
        FloatArray(SW * SH) { (((f[it] - 0.5f) * 5f) + 0.5f + 0.15f * sin(it % SW * 0.02f)).coerceIn(0.03f, 0.97f) }
    }
    private fun scene(x: Int, y: Int) = field[(y + 80).coerceIn(0, SH - 1) * SW + (x + 80).coerceIn(0, SW - 1)]

    private fun psnr(a: FloatArray, b: FloatArray, w: Int, h: Int, border: Int, maxV: Float = 255f): Double {
        var se = 0.0; var n = 0
        for (y in border until h - border) for (x in border until w - border) {
            val d = (a[y * w + x] - b[y * w + x]).toDouble(); se += d * d; n++
        }
        return 10 * log10(maxV.toDouble() * maxV / (se / n))
    }

    // Moving object: a bright square that moves 9 px per frame.
    private fun objectAt(x: Int, y: Int, k: Int): Boolean { val ox = 300 + 9 * k; return x in ox until ox + 60 && y in 200 until 260 }

    /** Clean Y of frame k (scene seen with camera shift (dx,dy)); [warp] adds a local shift on the right half. */
    private fun cleanY(dx: Int, dy: Int, k: Int, warp: Int): FloatArray = FloatArray(W * H) { i ->
        val x = i % W; val y = i / W
        val lx = if (x > W / 2) warp else 0
        if (objectAt(x, y, k)) 235f else 20f + 140f * scene(x + dx + lx, y + dy)
    }

    private fun nv21(y: FloatArray, noise: Double, rnd: java.util.Random): ByteArray {
        val out = ByteArray(W * H * 3 / 2)
        for (i in 0 until W * H) out[i] = (y[i] + rnd.nextGaussian() * noise).roundToInt().coerceIn(0, 255).toByte()
        for (i in 0 until W * H / 4) {
            out[W * H + 2 * i] = (140 + rnd.nextGaussian() * noise).roundToInt().coerceIn(0, 255).toByte()
            out[W * H + 2 * i + 1] = (118 + rnd.nextGaussian() * noise).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    @Test fun noiseEstimatorFindsSigma() {
        val rnd = java.util.Random(1)
        val clean = cleanY(0, 0, -100, 0)
        val noisy = FloatArray(W * H) { clean[it] + rnd.nextGaussian().toFloat() * 6f }
        val m = NoiseEstimator.estimate({ x, y -> noisy[y * W + x] }, W, H, 255f)
        val s = m.sigma(90f)
        assertTrue("sigma estimate $s", s in 4.5f..7.5f)
    }

    @Test fun tileMergeBeatsSingleFrameAndPlainAverage() {
        val rnd = java.util.Random(3)
        val shifts = listOf(0 to 0, 3 to -2, -4 to 1, 5 to 4, -2 to -5, 1 to 3, -5 to -1, 4 to -4)
        val warps = listOf(0, 1, -1, 2, 0, -2, 1, -1)   // non-rigid (local) motion
        val noise = 10.0
        val cleanRef = cleanY(0, 0, 0, 0)
        val frames = shifts.mapIndexed { k, (dx, dy) -> nv21(cleanY(dx, dy, k, warps[k]), noise, rnd) }
        val single = FloatArray(W * H) { (frames[0][it].toInt() and 0xFF).toFloat() }

        // plain average after perfect GLOBAL alignment (what a global-shift stacker does at best)
        val avg = FloatArray(W * H); val cnt = FloatArray(W * H)
        for ((k, f) in frames.withIndex()) {
            val (dx, dy) = shifts[k]
            for (y in 0 until H) for (x in 0 until W) {
                // frame k at (x - dx) shows scene x
                val fx = x - dx; val fy = y - dy
                if (fx in 0 until W && fy in 0 until H) { avg[y * W + x] += (f[fy * W + fx].toInt() and 0xFF).toFloat(); cnt[y * W + x] += 1f }
            }
        }
        for (i in avg.indices) avg[i] /= cnt[i]

        val mfs = frames.map { MergeFrame.nv21(it, W, H) }
        val r = BurstMerge.run(mfs, 255f, refIndex = 0)
        val merged = r.planes[0]
        val pSingle = psnr(single, cleanRef, W, H, 16)
        val pAvg = psnr(avg, cleanRef, W, H, 16)
        val pMerged = psnr(merged, cleanRef, W, H, 16)
        println("PSNR single=%.2f plainAvg=%.2f tileMerge=%.2f meanWeight=%.2f".format(pSingle, pAvg, pMerged, r.meanWeight))
        assertTrue("merge must beat single frame by > 5 dB", pMerged > pSingle + 5)
        assertTrue("merge must beat plain average", pMerged > pAvg + 1)
        // chroma is merged too (noise drops)
        val v = r.planes[1]
        var se = 0.0
        for (i in v.indices) { val d = v[i] - 140f; se += d * d }
        assertTrue("chroma noise ${sqrt(se / v.size)}", sqrt(se / v.size) < noise * 0.6)
    }

    @Test fun lumaDenoiseImprovesNoisyFrame() {
        val rnd = java.util.Random(5)
        val clean = cleanY(0, 0, -100, 0)
        val noisy = FloatArray(W * H) { clean[it] + rnd.nextGaussian().toFloat() * 5f }
        val nm = NoiseEstimator.estimate({ x, y -> noisy[y * W + x] }, W, H, 255f)
        val den = NightPost.denoiseLuma(noisy, W, H, nm, 1f, null)
        val p0 = psnr(noisy, clean, W, H, 2); val p1 = psnr(den, clean, W, H, 2)
        println("denoise PSNR %.2f -> %.2f".format(p0, p1))
        assertTrue(p1 > p0 + 0.5)
    }

    @Test fun chromaDenoiseReducesBlotches() {
        val rnd = java.util.Random(6)
        val cw = 320; val ch = 240
        val v = FloatArray(cw * ch) { 150f + rnd.nextGaussian().toFloat() * 6f }
        val u = FloatArray(cw * ch) { 110f + rnd.nextGaussian().toFloat() * 6f }
        val (v2, u2) = NightPost.denoiseChroma(v, u, cw, ch, 6f, 2.2f, null)
        var e0 = 0.0; var e1 = 0.0
        for (i in v.indices) { e0 += (v[i] - 150.0).let { it * it }; e1 += (v2[i] - 150.0).let { it * it } }
        assertTrue("chroma rms ${sqrt(e1 / v.size)} vs ${sqrt(e0 / v.size)}", e1 < e0 * 0.25)
        assertEquals(110.0, u2.average(), 0.5)
    }

    @Test fun nightToneMappingLiftsShadowsWithoutClipping() {
        // dark scene: mean ~30, a few bright lamps
        val y = FloatArray(W * H) { i -> val x = i % W; val yy = i / W; 8f + 45f * scene(x, yy) }
        for (yy in 100 until 110) for (x in 100 until 110) y[yy * W + x] = 250f
        val before = y.average()
        val gm = NightPost.toneGainMap(y, W, H, NightPost.STYLE_C.targetKey, NightPost.STYLE_C.maxGain)
        val out = y.copyOf()
        NightPost.applyGain(out, W, H, gm)
        val after = out.average()
        val clipped = out.count { it >= 254.5f }.toDouble() / out.size
        println("tone mean %.1f -> %.1f, clipped %.4f".format(before, after, clipped))
        assertTrue("shadows lifted", after > before * 1.5)
        assertTrue("mean stays sane", after in 45.0..150.0)
        assertTrue("no clipping", clipped < 0.002)
        assertTrue("lamp stays bright", out[105 * W + 105] > 200f)
        // order preserved between a dark and a brighter pixel in the same area
        assertTrue(NightPost.toneValue(0.2f) < NightPost.toneValue(0.3f))
        // a well-exposed scene is left mostly alone
        val bright = FloatArray(W * H) { i -> 90f + 120f * scene(i % W, i / W) }
        val gb = NightPost.toneGainMap(bright, W, H, NightPost.STYLE_C.targetKey, NightPost.STYLE_C.maxGain)
        val ob = bright.copyOf(); NightPost.applyGain(ob, W, H, gb)
        assertEquals(1.0, ob.average() / bright.average(), 0.15)
    }

    @Test fun fullPostProcessingKeepsImageSane() {
        val rnd = java.util.Random(9)
        val c = cleanY(0, 0, -100, 0)
        val y = FloatArray(W * H)
        for (i in y.indices) y[i] = c[i] + rnd.nextGaussian().toFloat() * 2f
        val v = FloatArray(W * H / 4) { 128f }; val u = FloatArray(W * H / 4) { 128f }
        for (style in listOf(NightPost.STYLE_A, NightPost.STYLE_C)) {
            val px = NightPost.process(arrayOf(y.copyOf(), v.copyOf(), u.copyOf()), W, H, style)
            val mean = ImageChecks.meanLuma(px)
            assertTrue("mean $mean", mean > c.average() * 0.9 && mean < 200)
            assertTrue(!ImageChecks.mergedLooksBroken(mean, c.average()))
        }
    }

    // ------------------------------------------------------------------ RAW

    @Test fun unpack16HandlesRowPadding() {
        val w = 6; val h = 3; val stride = 16
        val bb = ByteBuffer.allocate(stride * h).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) for (x in 0 until w) bb.putShort(y * stride + 2 * x, (1000 + y * 10 + x).toShort())
        val out = RawCore.unpack16(bb, w, h, stride)
        for (y in 0 until h) for (x in 0 until w) assertEquals(1000 + y * 10 + x, out[y * w + x].toInt())
    }

    private val CCM = RawCore.normalizeRows(floatArrayOf(1.75f, -0.55f, -0.2f, -0.28f, 1.52f, -0.24f, 0.02f, -0.6f, 1.58f))
    private val WB = floatArrayOf(2.1f, 1f, 1f, 1.7f)
    private val COLORS = listOf(
        floatArrayOf(0.40f, 0.10f, 0.08f), floatArrayOf(0.08f, 0.30f, 0.10f), floatArrayOf(0.06f, 0.10f, 0.45f),
        floatArrayOf(0.18f, 0.18f, 0.18f), floatArrayOf(0.50f, 0.40f, 0.12f), floatArrayOf(0.04f, 0.04f, 0.05f),
    )

    /** Bayer frame (raw units) whose correct development is the linear sRGB [COLORS] patches. */
    private fun bayerPatches(w: Int, h: Int, cfa: Int, shading: ShadingMap?, noise: Double = 0.0, rnd: java.util.Random? = null, dx: Int = 0, dy: Int = 0): ShortArray {
        val inv = RawCore.inv3(CCM)
        val black = 64f; val white = 1023f
        val out = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x + dx; val sy = y + dy
            val patch = ((sy / (h / 2)).coerceIn(0, 1) * 3 + (sx / (w / 3)).coerceIn(0, 2))
            val c = COLORS[patch]
            val cam = FloatArray(3) { r -> inv[r * 3] * c[0] + inv[r * 3 + 1] * c[1] + inv[r * 3 + 2] * c[2] }
            val ch = RawCore.channel(cfa, ((y and 1) shl 1) or (x and 1))
            var v = cam[RawCore.color(ch)] / WB[ch]
            if (shading != null) v /= shading.gain(ch, x.toFloat(), y.toFloat(), w, h)
            var raw = black + v * (white - black)
            if (rnd != null) raw += (rnd.nextGaussian() * noise).toFloat()
            out[y * w + x] = raw.roundToInt().coerceIn(0, 1023).toShort()
        }
        return out
    }

    private fun quadPlanes(raw: ShortArray, w: Int, h: Int): Array<FloatArray> {
        val v = PlaneView.bayer(raw, w, h)
        return Array(4) { k -> FloatArray((w / 2) * (h / 2)) { i -> v[k].get(i % (w / 2), i / (w / 2)) } }
    }

    @Test fun demosaicReproducesFlatColorsExactly() {
        val w = 24; val h = 16
        for (cfa in 0..3) {
            val planes = Array(4) { k -> FloatArray(w * h / 4) { _ -> when (RawCore.color(RawCore.channel(cfa, k))) { 0 -> 0.3f; 1 -> 0.5f; else -> 0.2f } } }
            val out = FloatArray(3)
            val chanAt = IntArray(4) { RawCore.channel(cfa, it) }
            for (y in 0 until h) for (x in 0 until w) {
                RawCore.demosaicPixel(planes, w / 2, w, h, x, y, chanAt, out)
                assertEquals(0.3f, out[0], 1e-4f); assertEquals(0.5f, out[1], 1e-4f); assertEquals(0.2f, out[2], 1e-4f)
            }
        }
    }

    @Test fun rawPipelineReproducesKnownColors() {
        val w = 240; val h = 160
        // vignetting: gain 1 in the centre, 2.2 at the corners
        val cols = 9; val rows = 7
        val shading = ShadingMap(cols, rows, Array(4) { c ->
            FloatArray(cols * rows) { i ->
                val u = (i % cols) / (cols - 1f) - 0.5f; val v = (i / cols) / (rows - 1f) - 0.5f
                1f + 2.4f * (u * u + v * v) * (if (c == 0) 1.1f else 1f)
            }
        })
        for (cfa in listOf(0, 3)) {
            val raw = bayerPatches(w, h, cfa, shading)
            val info = RawInfo(w, h, cfa, FloatArray(4) { 64f }, 1023f, WB, CCM, shading)
            val planes = quadPlanes(raw, w, h)
            RawCore.normalize(planes, w / 2, h / 2, info)
            val yuv = RawCore.develop(planes, w / 2, h / 2, info, 1f) { RawCore.srgb(it) }
            val argb = NightPost.toArgb(yuv[0], yuv[1], yuv[2], w, h, null, 1f, 0f, 0f)
            for ((pi, c) in COLORS.withIndex()) {
                val px = (pi % 3) * (w / 3) + w / 6; val py = (pi / 3) * (h / 2) + h / 4
                val p = argb[py * w + px]
                val got = floatArrayOf(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f).map { RawCore.srgbInv(it) }
                for (k in 0..2) assertEquals("cfa $cfa patch $pi ch $k", c[k], got[k], 0.012f + 0.04f * c[k])
            }
        }
    }

    @Test fun forwardMatrixGivesNeutralWhite() {
        val fm = floatArrayOf(0.62f, 0.25f, 0.09f, 0.26f, 0.85f, -0.11f, 0.03f, -0.20f, 1.0f)
        val m = RawCore.ccmFromForwardMatrix(fm)
        for (r in 0..2) assertEquals(1f, m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2], 1e-4f)
        val inv = RawCore.inv3(m)
        val id = RawCore.mul3(m, inv)
        for (i in 0..8) assertEquals(if (i % 4 == 0) 1f else 0f, id[i], 1e-4f)
        val g = RawCore.gainsFromNeutral(0.5f, 1f, 0.625f)
        assertEquals(2f, g[0], 1e-5f); assertEquals(1.6f, g[3], 1e-5f)
    }

    @Test fun rawMergeBeatsSingleFrame() {
        val w = 320; val h = 240
        val rnd = java.util.Random(11)
        // textured RAW scene: scene() modulates the patch colours
        fun frame(dx: Int, dy: Int, noise: Double): ShortArray {
            val out = ShortArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val v = 64f + 700f * scene(x + dx, y + dy) * (if (((y and 1) + (x and 1)) == 1) 1f else 0.6f)
                out[y * w + x] = (v + (rnd.nextGaussian() * noise)).roundToInt().coerceIn(0, 1023).toShort()
            }
            return out
        }
        val shifts = listOf(0 to 0, 2 to -4, -4 to 2, 6 to 2, -2 to -6, 4 to 4, -6 to 0, 2 to 6)
        val clean = quadPlanes(frame(0, 0, 0.0), w, h)
        val frames = shifts.map { (dx, dy) -> frame(dx, dy, 30.0) }
        val prof = doubleArrayOf(0.0, (30.0 / 959) * (30.0 / 959), 0.0, (30.0 / 959) * (30.0 / 959), 0.0, (30.0 / 959) * (30.0 / 959), 0.0, (30.0 / 959) * (30.0 / 959))
        val info = RawInfo(w, h, 0, FloatArray(4) { 64f }, 1023f, WB, CCM, null, prof)
        val nm = RawCore.guideNoise(info)!!
        assertEquals(15f, nm.sigma(300f), 0.5f)    // mean of 4 channels: sigma / 2
        val r = BurstMerge.run(frames.map { MergeFrame.bayer(it, w, h) }, 1023f, noise = nm, mergeTile = 16, alignTile = 32, refIndex = 0)
        val single = quadPlanes(frames[0], w, h)
        var pS = 0.0; var pM = 0.0
        for (k in 0..3) { pS += psnr(single[k], clean[k], w / 2, h / 2, 8, 1023f); pM += psnr(r.planes[k], clean[k], w / 2, h / 2, 8, 1023f) }
        println("RAW PSNR single=%.2f merged=%.2f".format(pS / 4, pM / 4))
        assertTrue(pM / 4 > pS / 4 + 5)
    }

    @Test fun hotPixelsAreRemoved() {
        val qw = 20; val qh = 20
        val planes = Array(4) { FloatArray(qw * qh) { 0.1f } }
        planes[2][10 * qw + 10] = 0.9f
        val n = RawCore.hotPixels(planes, qw, qh)
        assertEquals(1, n)
        assertEquals(0.1f, planes[2][10 * qw + 10], 1e-5f)
    }

    @Test fun tileAlignmentFollowsLocalMotion() {
        val rnd = java.util.Random(13)
        val ref = nv21(cleanY(0, 0, -100, 0), 3.0, rnd)
        val frm = nv21(cleanY(3, -2, -100, 4), 3.0, rnd)   // right half moved 4 px more
        val a = MergeFrame.nv21(ref, W, H); val b = MergeFrame.nv21(frm, W, H)
        val al = TileAligner.align(a, b, StackCore.pyramid(a.guideHalf(), 4), StackCore.pyramid(b.guideHalf(), 4), 64, 3f)
        // frame(x + dx) == ref(x): left half dx = -3, right half dx = -7
        val (lx, ly) = al.at(100f, 240f); val (rx, ry) = al.at(560f, 240f)
        assertEquals(-3, lx); assertEquals(2, ly)
        assertEquals(-7, rx); assertEquals(2, ry)
    }

    /** Rough speed check at the Pixel 5 stream size (prints timings; asserts only a loose bound). */
    @Test fun processingSpeedAtFullSize() {
        val w = 4032; val h = 3024
        val rnd = java.util.Random(2)
        val base = ByteArray(w * h * 3 / 2) { i -> if (i < w * h) (60 + ((i % w) / 40 + (i / w) / 40) % 50).toByte() else 128.toByte() }
        val frames = (0 until 10).map { k -> val f = base.copyOf(); for (i in 0 until w * h step 7) f[i] = (f[i] + rnd.nextInt(9) - 4).toByte(); f }
        repeat(2) { run ->
            val t0 = System.nanoTime()
            val r = BurstMerge.run(frames.map { MergeFrame.nv21(it, w, h) }, 255f)
            val t1 = System.nanoTime()
            NightPost.process(r.planes, w, h, NightPost.STYLE_C)
            val t2 = System.nanoTime()
            println("speed run $run: merge10 %.1fs post %.1fs".format((t1 - t0) / 1e9, (t2 - t1) / 1e9))
            if (run == 1) assertTrue((t2 - t0) / 1e9 < 120)
        }
    }

    @Test fun nightVariantsAreDistinct() {
        val v = CaptureCore.NightVariant.values()
        assertEquals(4, v.map { it.code }.toSet().size)
        assertEquals(CaptureCore.NightVariant.A, CaptureCore.NightVariant.B.nonRaw())
        assertEquals(CaptureCore.NightVariant.C, CaptureCore.NightVariant.D.nonRaw())
        assertEquals(CaptureCore.NightVariant.A, CaptureCore.NightVariant.of("?"))
        assertTrue(v.all { it.exifName.all { c -> c.code < 128 } })   // EXIF UserComment stays ASCII
    }
}
