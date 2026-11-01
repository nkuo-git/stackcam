package com.nick.stackcam

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Single-channel float image. */
class Plane(val w: Int, val h: Int, val d: FloatArray = FloatArray(w * h))

/** Burt-Adelson style Gaussian / Laplacian pyramids with a 5-tap binomial kernel. */
object Pyr {
    private fun cl(i: Int, n: Int) = if (i < 0) 0 else if (i >= n) n - 1 else i

    fun down(p: Plane): Plane {
        val nw = (p.w + 1) / 2
        val nh = (p.h + 1) / 2
        val tmp = FloatArray(nw * p.h)
        for (y in 0 until p.h) {
            val r = y * p.w
            for (x in 0 until nw) {
                val c = 2 * x
                tmp[y * nw + x] = (p.d[r + cl(c - 2, p.w)] + 4f * p.d[r + cl(c - 1, p.w)] + 6f * p.d[r + cl(c, p.w)] +
                    4f * p.d[r + cl(c + 1, p.w)] + p.d[r + cl(c + 2, p.w)]) * (1f / 16f)
            }
        }
        val out = Plane(nw, nh)
        for (y in 0 until nh) {
            val c = 2 * y
            val r0 = cl(c - 2, p.h) * nw; val r1 = cl(c - 1, p.h) * nw; val r2 = cl(c, p.h) * nw
            val r3 = cl(c + 1, p.h) * nw; val r4 = cl(c + 2, p.h) * nw
            val o = y * nw
            for (x in 0 until nw) {
                out.d[o + x] = (tmp[r0 + x] + 4f * tmp[r1 + x] + 6f * tmp[r2 + x] + 4f * tmp[r3 + x] + tmp[r4 + x]) * (1f / 16f)
            }
        }
        return out
    }

    /** Upsample (zero-insert + [1 4 6 4 1]/8 per axis) to exactly tw x th. */
    fun expand(p: Plane, tw: Int, th: Int): Plane {
        val tmp = FloatArray(tw * p.h)
        for (y in 0 until p.h) {
            val r = y * p.w
            for (x in 0 until tw) {
                val i = x shr 1
                tmp[y * tw + x] = if (x and 1 == 0)
                    (p.d[r + cl(i - 1, p.w)] + 6f * p.d[r + cl(i, p.w)] + p.d[r + cl(i + 1, p.w)]) * 0.125f
                else
                    (p.d[r + cl(i, p.w)] + p.d[r + cl(i + 1, p.w)]) * 0.5f
            }
        }
        val out = Plane(tw, th)
        for (y in 0 until th) {
            val i = y shr 1
            val o = y * tw
            if (y and 1 == 0) {
                val a = cl(i - 1, p.h) * tw; val b = cl(i, p.h) * tw; val c = cl(i + 1, p.h) * tw
                for (x in 0 until tw) out.d[o + x] = (tmp[a + x] + 6f * tmp[b + x] + tmp[c + x]) * 0.125f
            } else {
                val a = cl(i, p.h) * tw; val b = cl(i + 1, p.h) * tw
                for (x in 0 until tw) out.d[o + x] = (tmp[a + x] + tmp[b + x]) * 0.5f
            }
        }
        return out
    }

    fun levelsFor(w: Int, h: Int, maxLevels: Int = 9): Int {
        var n = 1
        var mw = w; var mh = h
        while (n < maxLevels && min(mw, mh) > 24) { mw = (mw + 1) / 2; mh = (mh + 1) / 2; n++ }
        return n
    }

    fun gaussian(p: Plane, levels: Int): List<Plane> {
        val out = ArrayList<Plane>(levels)
        out.add(p)
        for (i in 1 until levels) out.add(down(out.last()))
        return out
    }

    fun laplacian(p: Plane, levels: Int): List<Plane> {
        val g = gaussian(p, levels)
        val out = ArrayList<Plane>(levels)
        for (i in 0 until levels - 1) {
            val e = expand(g[i + 1], g[i].w, g[i].h)
            val l = Plane(g[i].w, g[i].h)
            for (j in l.d.indices) l.d[j] = g[i].d[j] - e.d[j]
            out.add(l)
        }
        out.add(g.last())
        return out
    }

    fun collapse(lap: List<Plane>): Plane {
        var cur = lap.last()
        for (i in lap.size - 2 downTo 0) {
            val l = lap[i]
            val e = expand(cur, l.w, l.h)
            for (j in e.d.indices) e.d[j] += l.d[j]
            cur = e
        }
        return cur
    }
}

/**
 * Exposure fusion (Mertens, Kautz & Van Reeth 2007) of a bracketed set of aligned images.
 *
 * To keep memory low on 12MP images the Laplacian-pyramid fusion runs on a 1/[factor]
 * scale copy; the finest detail (full-res minus upsampled low-res) of each exposure is then
 * added back with per-pixel weights. Moving objects are detected against the reference
 * exposure (after histogram-matching brightness) and taken from the reference only.
 */
object HdrFusion {

    class Result(val ghostFraction: Double)

    private const val SIGMA_WE = 0.2f
    private val wellLut = FloatArray(256) { v ->
        val t = v / 255f - 0.5f
        exp(-(t * t) / (2f * SIGMA_WE * SIGMA_WE))
    }

    /** Box-downsample to three planes (0..1). */
    fun boxDown(px: IntArray, w: Int, h: Int, f: Int): Array<Plane> {
        val lw = max(1, w / f)
        val lh = max(1, h / f)
        val r = Plane(lw, lh); val g = Plane(lw, lh); val b = Plane(lw, lh)
        val inv = 1f / (255f * f * f)
        for (ly in 0 until lh) for (lx in 0 until lw) {
            var sr = 0; var sg = 0; var sb = 0
            for (oy in 0 until f) {
                val row = (ly * f + oy) * w + lx * f
                for (ox in 0 until f) {
                    val c = px[row + ox]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
            }
            val i = ly * lw + lx
            r.d[i] = sr * inv; g.d[i] = sg * inv; b.d[i] = sb * inv
        }
        return arrayOf(r, g, b)
    }

    fun luma(c: Array<Plane>): Plane {
        val out = Plane(c[0].w, c[0].h)
        for (i in out.d.indices) out.d[i] = 0.299f * c[0].d[i] + 0.587f * c[1].d[i] + 0.114f * c[2].d[i]
        return out
    }

    /** Mertens quality measure: contrast * saturation * well-exposedness. */
    fun mertensWeight(c: Array<Plane>): Plane {
        val w = c[0].w; val h = c[0].h
        val y = luma(c)
        val out = Plane(w, h)
        val k = 1f / (2f * SIGMA_WE * SIGMA_WE)
        for (yy in 0 until h) for (xx in 0 until w) {
            val i = yy * w + xx
            val l = if (xx > 0) y.d[i - 1] else y.d[i]
            val r = if (xx < w - 1) y.d[i + 1] else y.d[i]
            val u = if (yy > 0) y.d[i - w] else y.d[i]
            val dn = if (yy < h - 1) y.d[i + w] else y.d[i]
            val contrast = abs(l + r + u + dn - 4f * y.d[i])
            val rv = c[0].d[i]; val gv = c[1].d[i]; val bv = c[2].d[i]
            val m = (rv + gv + bv) / 3f
            val sat = sqrt(((rv - m) * (rv - m) + (gv - m) * (gv - m) + (bv - m) * (bv - m)) / 3f)
            val we = exp(-((rv - 0.5f) * (rv - 0.5f) + (gv - 0.5f) * (gv - 0.5f) + (bv - 0.5f) * (bv - 0.5f)) * k)
            out.d[i] = (contrast + 0.02f) * (sat + 0.03f) * we + 1e-9f
        }
        return out
    }

    /** Histogram-matching LUT (256 bins) mapping luminance of [src] to [dst]. */
    fun matchLut(src: Plane, dst: Plane): FloatArray {
        val hs = DoubleArray(256); val hd = DoubleArray(256)
        for (v in src.d) hs[(v * 255f).roundToInt().coerceIn(0, 255)]++
        for (v in dst.d) hd[(v * 255f).roundToInt().coerceIn(0, 255)]++
        for (i in 1 until 256) { hs[i] += hs[i - 1]; hd[i] += hd[i - 1] }
        val ns = hs[255]; val nd = hd[255]
        val lut = FloatArray(256)
        var j = 0
        for (i in 0 until 256) {
            val q = (hs[i] - 0.5 * (if (i > 0) hs[i] - hs[i - 1] else hs[i])) / ns
            while (j < 255 && hd[j] / nd < q) j++
            lut[i] = j / 255f
        }
        return lut
    }

    private fun boxBlur(p: Plane, r: Int): Plane {
        val w = p.w; val h = p.h
        val tmp = FloatArray(w * h)
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += p.d[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc / (2 * r + 1)
                acc += p.d[y * w + (x + r + 1).coerceIn(0, w - 1)] - p.d[y * w + (x - r).coerceIn(0, w - 1)]
            }
        }
        val out = Plane(w, h)
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out.d[y * w + x] = acc / (2 * r + 1)
                acc += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    private fun maxFilter3(p: Plane): Plane {
        val out = Plane(p.w, p.h)
        for (y in 0 until p.h) for (x in 0 until p.w) {
            var m = 0f
            for (oy in -1..1) for (ox in -1..1) {
                val v = p.d[(y + oy).coerceIn(0, p.h - 1) * p.w + (x + ox).coerceIn(0, p.w - 1)]
                if (v > m) m = v
            }
            out.d[y * p.w + x] = m
        }
        return out
    }

    /**
     * Ghost map for exposure [k] vs reference: 1 = moving content, use the reference instead.
     */
    fun ghostMap(k: Array<Plane>, ref: Array<Plane>): Plane {
        val yk = luma(k); val yr = luma(ref)
        val lut = matchLut(yk, yr)
        val g = Plane(yk.w, yk.h)
        for (i in g.d.indices) {
            val a = yk.d[i]; val b = yr.d[i]
            if (a < 0.03f || a > 0.97f || b < 0.03f || b > 0.97f) continue
            val pred = lut[(a * 255f).roundToInt().coerceIn(0, 255)]
            val d = abs(pred - b)
            g.d[i] = ((d - 0.07f) / 0.10f).coerceIn(0f, 1f)
        }
        val r = max(1, min(g.w, g.h) / 200)
        return boxBlur(maxFilter3(maxFilter3(g)), r).also { gb ->
            for (i in gb.d.indices) gb.d[i] = (gb.d[i] * 1.5f).coerceAtMost(1f)
        }
    }

    /** Mild S-curve + saturation. v in 0..1 */
    private fun tone(v: Float, a: Float): Float {
        val t = v.coerceIn(0f, 1f)
        return t + a * t * (1f - t) * (2f * t - 1f)
    }

    /**
     * Fuse [imgs] (ARGB, all w x h, aligned) into [out] (may alias one of the inputs).
     * [refIdx] is the reference exposure for ghost rejection; [factor] is the downscale
     * used for the pyramid stage (1 = full resolution).
     */
    fun fuse(
        imgs: List<IntArray>, w: Int, h: Int, refIdx: Int, out: IntArray,
        factor: Int = 4, ghost: Boolean = true, contrast: Float = 0.3f, saturation: Float = 1.08f,
        onProgress: (Int) -> Unit = {},
    ): Result {
        val n = imgs.size
        require(n >= 1)
        val f = max(1, factor)
        val low = imgs.map { boxDown(it, w, h, f) }
        onProgress(10)
        val lw = low[0][0].w; val lh = low[0][0].h
        val weights = low.map { mertensWeight(it) }.toMutableList()
        onProgress(20)

        var ghostPx = 0.0
        if (ghost && n > 1) {
            for (k in 0 until n) {
                if (k == refIdx) continue
                val g = ghostMap(low[k], low[refIdx])
                val wk = weights[k]; val wr = weights[refIdx]
                for (i in g.d.indices) {
                    val gi = g.d[i]
                    if (gi > 0f) {
                        val moved = wk.d[i] * gi
                        wk.d[i] -= moved
                        // the reference takes over; give it at least comparable weight
                        wr.d[i] += moved + gi * 1e-3f
                        if (gi > 0.5f) ghostPx++
                    }
                }
            }
            ghostPx /= (lw.toDouble() * lh * (n - 1))
        }
        // normalise
        for (i in 0 until lw * lh) {
            var s = 0f
            for (k in 0 until n) s += weights[k].d[i]
            val inv = 1f / s
            for (k in 0 until n) weights[k].d[i] *= inv
        }
        onProgress(30)

        val levels = Pyr.levelsFor(lw, lh)
        val wPyr = weights.map { Pyr.gaussian(it, levels) }
        val fused = Array(3) { ch ->
            var acc: List<Plane>? = null
            for (k in 0 until n) {
                val lap = Pyr.laplacian(low[k][ch], levels)
                if (acc == null) acc = List(levels) { Plane(lap[it].w, lap[it].h) }
                for (lv in 0 until levels) {
                    val a = acc[lv].d; val l = lap[lv].d; val wv = wPyr[k][lv].d
                    for (i in a.indices) a[i] += wv[i] * l[i]
                }
            }
            onProgress(40 + 15 * ch)
            Pyr.collapse(acc!!)
        }

        // Full-resolution reconstruction: low-res fused base + per-pixel weighted fine detail.
        val x0 = IntArray(w); val x1 = IntArray(w); val fx = FloatArray(w)
        for (x in 0 until w) {
            val lx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, (lw - 1).toFloat())
            val xi = floor(lx).toInt()
            x0[x] = xi; x1[x] = min(xi + 1, lw - 1); fx[x] = lx - xi
        }
        val sat = saturation
        val dw = FloatArray(n)
        val wLow = weights.map { it.d }
        for (y in 0 until h) {
            val ly = ((y + 0.5f) / f - 0.5f).coerceIn(0f, (lh - 1).toFloat())
            val yi = floor(ly).toInt()
            val y1 = min(yi + 1, lh - 1)
            val fy = ly - yi
            val r0 = yi * lw; val r1 = y1 * lw
            for (x in 0 until w) {
                val a = r0 + x0[x]; val b = r0 + x1[x]; val c = r1 + x0[x]; val d = r1 + x1[x]
                val ax = fx[x]
                val wa = (1 - ax) * (1 - fy); val wb = ax * (1 - fy); val wc = (1 - ax) * fy; val wd = ax * fy
                val i = y * w + x
                var fr = fused[0].d[a] * wa + fused[0].d[b] * wb + fused[0].d[c] * wc + fused[0].d[d] * wd
                var fg = fused[1].d[a] * wa + fused[1].d[b] * wb + fused[1].d[c] * wc + fused[1].d[d] * wd
                var fb = fused[2].d[a] * wa + fused[2].d[b] * wb + fused[2].d[c] * wc + fused[2].d[d] * wd
                if (f > 1) {
                    var ds = 0f
                    for (k in 0 until n) {
                        val wl = wLow[k]
                        val px = imgs[k][i]
                        val e = wellLut[(px shr 16) and 0xFF] * wellLut[(px shr 8) and 0xFF] * wellLut[px and 0xFF]
                        val v = (wl[a] * wa + wl[b] * wb + wl[c] * wc + wl[d] * wd) * (0.05f + e)
                        dw[k] = v; ds += v
                    }
                    val inv = if (ds > 0f) 1f / ds else 0f
                    for (k in 0 until n) {
                        val px = imgs[k][i]
                        val lk = low[k]
                        val q = dw[k] * inv
                        if (q < 1e-4f) continue
                        val ur = lk[0].d[a] * wa + lk[0].d[b] * wb + lk[0].d[c] * wc + lk[0].d[d] * wd
                        val ug = lk[1].d[a] * wa + lk[1].d[b] * wb + lk[1].d[c] * wc + lk[1].d[d] * wd
                        val ub = lk[2].d[a] * wa + lk[2].d[b] * wb + lk[2].d[c] * wc + lk[2].d[d] * wd
                        fr += q * (((px shr 16) and 0xFF) / 255f - ur)
                        fg += q * (((px shr 8) and 0xFF) / 255f - ug)
                        fb += q * ((px and 0xFF) / 255f - ub)
                    }
                }
                fr = tone(fr, contrast); fg = tone(fg, contrast); fb = tone(fb, contrast)
                val yl = 0.299f * fr + 0.587f * fg + 0.114f * fb
                fr = yl + (fr - yl) * sat; fg = yl + (fg - yl) * sat; fb = yl + (fb - yl) * sat
                val ri = (fr * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (fg * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (fb * 255f + 0.5f).toInt().coerceIn(0, 255)
                out[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
            if (y and 255 == 0) onProgress(85 + 15 * y / h)
        }
        return Result(ghostPx)
    }

    /** Histogram-equalised copy of a gray image: makes different exposures comparable for alignment. */
    fun equalize(g: Gray): Gray {
        val hist = IntArray(256)
        for (v in g.px) hist[v.toInt().coerceIn(0, 255)]++
        val cdf = FloatArray(256)
        var acc = 0
        for (i in 0 until 256) { acc += hist[i]; cdf[i] = acc * 255f / g.px.size }
        val out = FloatArray(g.px.size)
        for (i in out.indices) {
            val v = g.px[i].coerceIn(0f, 255f)
            val lo = v.toInt().coerceAtMost(254)
            val t = v - lo
            out[i] = cdf[lo] * (1 - t) + cdf[lo + 1] * t
        }
        return Gray(g.w, g.h, out)
    }

    /** Shift [src] into reference coordinates: dst(x,y) = src(x+dx, y+dy), edges clamped. */
    fun shifted(src: IntArray, w: Int, h: Int, dx: Int, dy: Int): IntArray {
        if (dx == 0 && dy == 0) return src
        val dst = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y + dy).coerceIn(0, h - 1)
            for (x in 0 until w) dst[y * w + x] = src[sy * w + (x + dx).coerceIn(0, w - 1)]
        }
        return dst
    }
}
