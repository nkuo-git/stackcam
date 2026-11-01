package com.nick.stackcam

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-frame super-resolution: frames with small (natural hand-shake) sub-pixel offsets
 * are registered with Lucas-Kanade and splatted onto a [scale]x finer grid with a Gaussian
 * kernel and robustness weights; empty grid cells are filled from the interpolated reference.
 */
object SuperRes {

    private fun bilinear(g: Gray, x: Float, y: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val i = yi * g.w + xi
        val a = g.px[i]; val b = g.px[i + 1]; val c = g.px[i + g.w]; val d = g.px[i + g.w + 1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /**
     * Gauss-Newton refinement of a translation so that frm(x+dx, y+dy) ~ ref(x, y).
     * Typical accuracy is a few hundredths of a pixel on textured images.
     */
    fun refineShift(ref: Gray, frm: Gray, dx0: Float, dy0: Float, iterations: Int = 12): Pair<Float, Float> {
        var dx = dx0; var dy = dy0
        val step = if (ref.w > 1800) 2 else 1
        val margin = 4
        for (it in 0 until iterations) {
            val x0 = max(margin, (margin - floor(dx)).toInt())
            val x1 = min(ref.w - margin, (frm.w - margin - ceil(dx)).toInt() - 1)
            val y0 = max(margin, (margin - floor(dy)).toInt())
            val y1 = min(ref.h - margin, (frm.h - margin - ceil(dy)).toInt() - 1)
            if (x1 - x0 < 16 || y1 - y0 < 16) break
            // mean brightness offset (exposure flicker)
            var sumE = 0.0; var cnt = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { sumE += bilinear(frm, x + dx, y + dy) - ref.px[y * ref.w + x]; cnt++; x += step }
                y += step
            }
            val bias = (sumE / cnt).toFloat()
            var hxx = 0.0; var hxy = 0.0; var hyy = 0.0; var bx = 0.0; var by = 0.0
            y = y0
            while (y < y1) {
                val row = y * ref.w
                var x = x0
                while (x < x1) {
                    val i = row + x
                    val gx = (ref.px[i + 1] - ref.px[i - 1]) * 0.5f
                    val gy = (ref.px[i + ref.w] - ref.px[i - ref.w]) * 0.5f
                    val e = bilinear(frm, x + dx, y + dy) - bias - ref.px[i]
                    hxx += gx * gx; hxy += gx * gy; hyy += gy * gy
                    bx += gx * e; by += gy * e
                    x += step
                }
                y += step
            }
            val det = hxx * hyy - hxy * hxy
            if (abs(det) < 1e-9) break
            val ux = (-(hyy * bx - hxy * by) / det).toFloat().coerceIn(-1f, 1f)
            val uy = (-(-hxy * bx + hxx * by) / det).toFloat().coerceIn(-1f, 1f)
            dx += ux; dy += uy
            if (abs(ux) < 0.002f && abs(uy) < 0.002f) break
        }
        return Pair(dx, dy)
    }

    /** Separable [1 2 1]/4 blur, applied [passes] times (suppresses aliasing before registration). */
    fun smooth(g: Gray, passes: Int = 2): Gray {
        var cur = g.px
        val w = g.w; val h = g.h
        repeat(passes) {
            val t = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                t[i] = (cur[y * w + max(0, x - 1)] + 2 * cur[i] + cur[y * w + min(w - 1, x + 1)]) * 0.25f
            }
            val o = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                o[y * w + x] = (t[max(0, y - 1) * w + x] + 2 * t[y * w + x] + t[min(h - 1, y + 1) * w + x]) * 0.25f
            }
            cur = o
        }
        return Gray(w, h, cur)
    }

    /**
     * Full registration (on lightly smoothed images): coarse-to-fine SAD search + Lucas-Kanade
     * refinement. [refSmoothPyr] comes from [refPyramid].
     */
    fun register(refSmoothPyr: List<Gray>, frm: Gray): Pair<Float, Float> {
        val fs = smooth(frm)
        val frmPyr = StackCore.pyramid(fs, refSmoothPyr.size)
        val (cx, cy) = StackCore.estimateShift(refSmoothPyr, frmPyr)
        return refineShift(refSmoothPyr[0], fs, cx, cy)
    }

    /** Pyramid of the smoothed reference, for [register]. */
    fun refPyramid(ref: Gray, levels: Int): List<Gray> = StackCore.pyramid(smooth(ref), levels)

    /**
     * Reconstruct a (w*scale) x (h*scale) image.
     * [shifts] follow the StackCore convention: frame(x+dx, y+dy) matches ref(x, y).
     * [sigma] is the splat kernel width in output pixels; [t1]/[t2] are colour-difference
     * thresholds (0..255) against the reference for ghost/misregistration rejection.
     */
    fun reconstruct(
        frames: List<IntArray>, w: Int, h: Int, shifts: List<Pair<Float, Float>>, refIdx: Int, scale: Int,
        sigma: Float = 0.33f * scale, t1: Float = 28f, t2: Float = 80f, threads: Int = Runtime.getRuntime().availableProcessors(),
        onProgress: (Int) -> Unit = {},
    ): IntArray {
        val s = scale
        val W = w * s; val H = h * s
        val out = IntArray(W * H)
        val ref = frames[refIdx]
        val rad = max(1, ceil(2f * sigma).toInt())
        val inv2s2 = 1f / (2f * sigma * sigma)
        val lambda = 0.08f
        // Per-frame fixed sub-pixel kernel tables.
        val n = frames.size
        val offX = IntArray(n); val offY = IntArray(n)
        val kx = Array(n) { FloatArray(2 * rad + 2) }; val ky = Array(n) { FloatArray(2 * rad + 2) }
        for (k in 0 until n) {
            val (dx, dy) = shifts[k]
            val cx = (0.5f - dx) * s - 0.5f
            val cy = (0.5f - dy) * s - 0.5f
            val bx = floor(cx).toInt(); val by = floor(cy).toInt()
            offX[k] = bx; offY[k] = by
            val frx = cx - bx; val fry = cy - by
            for (j in -rad..rad + 1) {
                val ddx = j - frx; val ddy = j - fry
                kx[k][j + rad] = if (abs(ddx) <= rad) exp(-ddx * ddx * inv2s2) else 0f
                ky[k][j + rad] = if (abs(ddy) <= rad) exp(-ddy * ddy * inv2s2) else 0f
            }
        }

        /** Bilinear sample of the reference (LR coords) into [o] as r,g,b floats. */
        fun refRgb(x: Float, y: Float, o: FloatArray) {
            val xc = x.coerceIn(0f, (w - 1).toFloat()); val yc = y.coerceIn(0f, (h - 1).toFloat())
            val xi = min(floor(xc).toInt(), w - 2).coerceAtLeast(0); val yi = min(floor(yc).toInt(), h - 2).coerceAtLeast(0)
            val fx = xc - xi; val fy = yc - yi
            val i = yi * w + xi
            val x1 = if (w > 1) 1 else 0; val y1 = if (h > 1) w else 0
            val pa = ref[i]; val pb = ref[i + x1]; val pc = ref[i + y1]; val pd = ref[i + y1 + x1]
            val wa = (1 - fx) * (1 - fy); val wb = fx * (1 - fy); val wc = (1 - fx) * fy; val wd = fx * fy
            var sh = 16
            for (ch in 0 until 3) {
                o[ch] = ((pa shr sh) and 0xFF) * wa + ((pb shr sh) and 0xFF) * wb + ((pc shr sh) and 0xFF) * wc + ((pd shr sh) and 0xFF) * wd
                sh -= 8
            }
        }

        val stripH = 32
        val strips = (H + stripH - 1) / stripH
        val pool = Executors.newFixedThreadPool(max(1, min(threads, 8)))
        try {
            val done = java.util.concurrent.atomic.AtomicInteger()
            val tasks = (0 until strips).map { si ->
                Callable {
                    val y0 = si * stripH
                    val y1 = min(H, y0 + stripH)
                    val sh = y1 - y0
                    val tmp = FloatArray(3)
                    val ar = FloatArray(sh * W); val ag = FloatArray(sh * W); val ab = FloatArray(sh * W); val aw = FloatArray(sh * W)
                    for (k in 0 until n) {
                        val fr = frames[k]
                        val (dx, dy) = shifts[k]
                        val isRef = k == refIdx
                        val fyLo = max(0, floor((y0 - rad - 1 - offY[k]).toFloat() / s).toInt())
                        val fyHi = min(h - 1, ceil((y1 + rad + 1 - offY[k]).toFloat() / s).toInt())
                        val kxk = kx[k]; val kyk = ky[k]
                        for (fy in fyLo..fyHi) {
                            val vy = fy * s + offY[k]
                            val jyLo = max(-rad, y0 - vy); val jyHi = min(rad + 1, y1 - 1 - vy)
                            if (jyLo > jyHi) continue
                            val ry = fy - dy
                            for (fx in 0 until w) {
                                val c = fr[fy * w + fx]
                                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                                var rw = 1f
                                if (!isRef) {
                                    val rx = fx - dx
                                    if (rx < -0.5f || rx > w - 0.5f || ry < -0.5f || ry > h - 0.5f) continue
                                    refRgb(rx, ry, tmp)
                                    val d = max(abs(r - tmp[0]), max(abs(g - tmp[1]), abs(b - tmp[2])))
                                    rw = when {
                                        d <= t1 -> 1f
                                        d >= t2 -> 0f
                                        else -> (t2 - d) / (t2 - t1)
                                    }
                                    if (rw <= 0f) continue
                                }
                                val ux = fx * s + offX[k]
                                val jxLo = max(-rad, -ux); val jxHi = min(rad + 1, W - 1 - ux)
                                for (jy in jyLo..jyHi) {
                                    val wy = kyk[jy + rad] * rw
                                    if (wy == 0f) continue
                                    val row = (vy + jy - y0) * W + ux
                                    for (jx in jxLo..jxHi) {
                                        val wt = wy * kxk[jx + rad]
                                        val i = row + jx
                                        ar[i] += wt * r; ag[i] += wt * g; ab[i] += wt * b; aw[i] += wt
                                    }
                                }
                            }
                        }
                    }
                    for (yy in 0 until sh) {
                        val oy = y0 + yy
                        val ly = (oy + 0.5f) / s - 0.5f
                        for (x in 0 until W) {
                            val i = yy * W + x
                            val lx = (x + 0.5f) / s - 0.5f
                            val den = aw[i] + lambda
                            refRgb(lx, ly, tmp)
                            val r = ((ar[i] + lambda * tmp[0]) / den + 0.5f).toInt().coerceIn(0, 255)
                            val g = ((ag[i] + lambda * tmp[1]) / den + 0.5f).toInt().coerceIn(0, 255)
                            val b = ((ab[i] + lambda * tmp[2]) / den + 0.5f).toInt().coerceIn(0, 255)
                            out[oy * W + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    val d = done.incrementAndGet()
                    if (d % 16 == 0) onProgress(100 * d / strips)
                    Unit
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return out
    }

    private fun cubic(t: Float): Float {
        val a = -0.5f
        val x = abs(t)
        return when {
            x <= 1f -> (a + 2) * x * x * x - (a + 3) * x * x + 1
            x < 2f -> a * x * x * x - 5 * a * x * x + 8 * a * x - 4 * a
            else -> 0f
        }
    }

    /** Bicubic (Keys, a=-0.5) upscale of a single frame; same pixel-centre convention as reconstruct. */
    fun bicubicUp(px: IntArray, w: Int, h: Int, s: Int): IntArray {
        val W = w * s; val H = h * s
        val out = IntArray(W * H)
        for (y in 0 until H) {
            val ly = (y + 0.5f) / s - 0.5f
            val yi = floor(ly).toInt(); val fy = ly - yi
            for (x in 0 until W) {
                val lx = (x + 0.5f) / s - 0.5f
                val xi = floor(lx).toInt(); val fx = lx - xi
                var r = 0f; var g = 0f; var b = 0f
                for (m in -1..2) {
                    val wy = cubic(m - fy)
                    val yy = (yi + m).coerceIn(0, h - 1)
                    for (k in -1..2) {
                        val wgt = wy * cubic(k - fx)
                        val c = px[yy * w + (xi + k).coerceIn(0, w - 1)]
                        r += wgt * ((c shr 16) and 0xFF); g += wgt * ((c shr 8) and 0xFF); b += wgt * (c and 0xFF)
                    }
                }
                out[y * W + x] = (0xFF shl 24) or ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or (b + 0.5f).toInt().coerceIn(0, 255)
            }
        }
        return out
    }
}
