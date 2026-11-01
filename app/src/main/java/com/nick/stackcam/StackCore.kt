package com.nick.stackcam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Grayscale image stored as floats (0..255). */
class Gray(val w: Int, val h: Int, val px: FloatArray) {
    fun at(x: Int, y: Int) = px[y * w + x]

    /** 2x box downsample. */
    fun down(): Gray {
        val nw = w / 2
        val nh = h / 2
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val r0 = 2 * y * w
            val r1 = r0 + w
            for (x in 0 until nw) {
                val c = 2 * x
                out[y * nw + x] = (px[r0 + c] + px[r0 + c + 1] + px[r1 + c] + px[r1 + c + 1]) * 0.25f
            }
        }
        return Gray(nw, nh, out)
    }

    companion object {
        fun fromArgb(argb: IntArray, w: Int, h: Int): Gray {
            val out = FloatArray(w * h)
            for (i in out.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[i] = (r * 77 + g * 150 + b * 29) / 256f
            }
            return Gray(w, h, out)
        }
    }
}

object StackCore {

    /** Variance of the Laplacian: higher = sharper. */
    fun sharpness(g: Gray): Double {
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (y in 1 until g.h - 1) {
            val row = y * g.w
            for (x in 1 until g.w - 1) {
                val i = row + x
                val l = (g.px[i - 1] + g.px[i + 1] + g.px[i - g.w] + g.px[i + g.w] - 4f * g.px[i]).toDouble()
                sum += l
                sum2 += l * l
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    fun pyramid(g: Gray, levels: Int): List<Gray> {
        val out = ArrayList<Gray>()
        var cur = g
        out.add(cur)
        for (i in 1 until levels) {
            if (cur.w < 64 || cur.h < 64) break
            cur = cur.down()
            out.add(cur)
        }
        return out
    }

    /** Mean absolute difference between ref(x,y) and frm(x+dx, y+dy) over the overlap. */
    private fun sad(ref: Gray, frm: Gray, dx: Int, dy: Int, margin: Int, step: Int): Float {
        val x0 = max(margin, margin - dx)
        val x1 = min(ref.w - margin, frm.w - margin - dx)
        val y0 = max(margin, margin - dy)
        val y1 = min(ref.h - margin, frm.h - margin - dy)
        if (x1 - x0 < 8 || y1 - y0 < 8) return Float.MAX_VALUE
        var s = 0f
        var n = 0
        var y = y0
        while (y < y1) {
            val rr = y * ref.w
            val fr = (y + dy) * frm.w + dx
            var x = x0
            while (x < x1) {
                s += abs(ref.px[rr + x] - frm.px[fr + x])
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) Float.MAX_VALUE else s / n
    }

    /**
     * Coarse-to-fine translation search. Returns the (sub-pixel) shift in level-0 pixels
     * such that frame(x+dx, y+dy) matches ref(x, y).
     */
    fun estimateShift(refPyr: List<Gray>, frmPyr: List<Gray>, coarseRadius: Int = 6): Pair<Float, Float> {
        val levels = min(refPyr.size, frmPyr.size)
        var dx = 0
        var dy = 0
        var fx = 0f
        var fy = 0f
        for (lvl in levels - 1 downTo 0) {
            val ref = refPyr[lvl]
            val frm = frmPyr[lvl]
            if (lvl != levels - 1) { dx *= 2; dy *= 2 }
            val r = if (lvl == levels - 1) coarseRadius else 2
            val margin = max(4, ref.w / 32)
            val step = if (ref.w > 1500) 3 else if (ref.w > 700) 2 else 1
            var best = Float.MAX_VALUE
            var bx = dx
            var by = dy
            for (oy in -r..r) for (ox in -r..r) {
                val s = sad(ref, frm, dx + ox, dy + oy, margin, step)
                if (s < best) { best = s; bx = dx + ox; by = dy + oy }
            }
            dx = bx; dy = by
            if (lvl == 0) {
                // Parabolic sub-pixel refinement.
                val cx = sad(ref, frm, dx, dy, margin, step)
                val lx = sad(ref, frm, dx - 1, dy, margin, step)
                val rx = sad(ref, frm, dx + 1, dy, margin, step)
                val uy = sad(ref, frm, dx, dy - 1, margin, step)
                val dy2 = sad(ref, frm, dx, dy + 1, margin, step)
                val denX = lx - 2 * cx + rx
                val denY = uy - 2 * cx + dy2
                if (denX > 1e-6f && lx < Float.MAX_VALUE && rx < Float.MAX_VALUE) fx = ((lx - rx) / (2 * denX)).coerceIn(-0.5f, 0.5f)
                if (denY > 1e-6f && uy < Float.MAX_VALUE && dy2 < Float.MAX_VALUE) fy = ((uy - dy2) / (2 * denY)).coerceIn(-0.5f, 0.5f)
            }
        }
        return Pair(dx + fx, dy + fy)
    }
}

/**
 * Accumulates aligned frames into the reference with ghost rejection.
 * Memory: ref IntArray + 3 ShortArray + 1 ByteArray, full resolution.
 */
class Merger(val w: Int, val h: Int, private val ref: IntArray, private val t1: Int, private val t2: Int) {
    private val sr = ShortArray(w * h)
    private val sg = ShortArray(w * h)
    private val sb = ShortArray(w * h)
    private val wt = ByteArray(w * h)
    private val maxW = 4

    init {
        for (i in 0 until w * h) {
            val c = ref[i]
            sr[i] = (((c shr 16) and 0xFF) * maxW).toShort()
            sg[i] = (((c shr 8) and 0xFF) * maxW).toShort()
            sb[i] = ((c and 0xFF) * maxW).toShort()
            wt[i] = maxW.toByte()
        }
    }

    /** Returns the fraction of pixels accepted (with any weight). */
    fun addFrame(dx: Int, dy: Int, frameH: Int, frameW: Int, getRow: (Int, IntArray) -> Unit): Double {
        val buf = IntArray(frameW)
        var accepted = 0L
        var total = 0L
        val span = (t2 - t1).coerceAtLeast(1)
        for (y in 0 until h) {
            val sy = y + dy
            if (sy < 0 || sy >= frameH) continue
            getRow(sy, buf)
            val xs = max(0, -dx)
            val xe = min(w, frameW - dx)
            val row = y * w
            for (x in xs until xe) {
                val i = row + x
                val c = buf[x + dx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val rc = ref[i]
                val d = max(abs(r - ((rc shr 16) and 0xFF)), max(abs(g - ((rc shr 8) and 0xFF)), abs(b - (rc and 0xFF))))
                val wgt = when {
                    d <= t1 -> maxW
                    d >= t2 -> 0
                    else -> (maxW * (t2 - d) + span / 2) / span
                }
                total++
                if (wgt > 0) {
                    accepted++
                    sr[i] = (sr[i] + r * wgt).toShort()
                    sg[i] = (sg[i] + g * wgt).toShort()
                    sb[i] = (sb[i] + b * wgt).toShort()
                    wt[i] = (wt[i] + wgt).toByte()
                }
            }
        }
        return if (total == 0L) 0.0 else accepted.toDouble() / total
    }

    /** Writes the merged image into [ref] (reused as output) and returns it. */
    fun finish(): IntArray {
        for (i in 0 until w * h) {
            val n = wt[i].toInt() and 0xFF
            val half = n / 2
            val r = ((sr[i].toInt() and 0xFFFF) + half) / n
            val g = ((sg[i].toInt() and 0xFFFF) + half) / n
            val b = ((sb[i].toInt() and 0xFFFF) + half) / n
            ref[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return ref
    }
}

object Sharpen {
    /**
     * Unsharp mask with a separable Gaussian blur of the given [sigma] (pixels).
     * Uses one temporary IntArray of the image size.
     */
    fun unsharpGauss(px: IntArray, w: Int, h: Int, sigma: Float, amount: Float) {
        val r = kotlin.math.max(1, kotlin.math.ceil(2.5f * sigma).toInt())
        val k = FloatArray(2 * r + 1) { val d = (it - r).toFloat(); kotlin.math.exp(-d * d / (2f * sigma * sigma)) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (j in -r..r) {
                    val c = px[row + (x + j).coerceIn(0, w - 1)]
                    val kv = k[j + r]
                    sr += kv * ((c shr 16) and 0xFF); sg += kv * ((c shr 8) and 0xFF); sb += kv * (c and 0xFF)
                }
                tmp[row + x] = ((sr + 0.5f).toInt() shl 16) or ((sg + 0.5f).toInt() shl 8) or (sb + 0.5f).toInt()
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0f; var sg = 0f; var sb = 0f
                for (j in -r..r) {
                    val c = tmp[(y + j).coerceIn(0, h - 1) * w + x]
                    val kv = k[j + r]
                    sr += kv * ((c shr 16) and 0xFF); sg += kv * ((c shr 8) and 0xFF); sb += kv * (c and 0xFF)
                }
                val i = y * w + x
                val o = px[i]
                val vr = (o shr 16) and 0xFF; val vg = (o shr 8) and 0xFF; val vb = o and 0xFF
                val nr = (vr + amount * (vr - sr) + 0.5f).toInt().coerceIn(0, 255)
                val ng = (vg + amount * (vg - sg) + 0.5f).toInt().coerceIn(0, 255)
                val nb = (vb + amount * (vb - sb) + 0.5f).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /** In-place unsharp mask with a 3x3 Gaussian blur. amount ~0.3..0.8 */
    fun unsharp(px: IntArray, w: Int, h: Int, amount: Float) {
        if (w < 3 || h < 3) return
        val prev = IntArray(w)
        val cur = IntArray(w)
        System.arraycopy(px, 0, prev, 0, w)
        System.arraycopy(px, w, cur, 0, w)
        val a = (amount * 256).roundToInt()
        for (y in 1 until h - 1) {
            val next = y + 1
            val nr = next * w
            for (x in 1 until w - 1) {
                val o = cur[x]
                val r = chan(prev, cur, px, nr, x, o, 16, a)
                val g = chan(prev, cur, px, nr, x, o, 8, a)
                val b = chan(prev, cur, px, nr, x, o, 0, a)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            // shift rolling original rows
            System.arraycopy(cur, 0, prev, 0, w)
            System.arraycopy(px, next * w, cur, 0, w)
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun chan(prev: IntArray, cur: IntArray, px: IntArray, nr: Int, x: Int, o: Int, sh: Int, a: Int): Int {
        val n0 = ((prev[x - 1] shr sh) and 0xFF) + 2 * ((prev[x] shr sh) and 0xFF) + ((prev[x + 1] shr sh) and 0xFF)
        val n1 = 2 * ((cur[x - 1] shr sh) and 0xFF) + 4 * ((o shr sh) and 0xFF) + 2 * ((cur[x + 1] shr sh) and 0xFF)
        val n2 = ((px[nr + x - 1] shr sh) and 0xFF) + 2 * ((px[nr + x] shr sh) and 0xFF) + ((px[nr + x + 1] shr sh) and 0xFF)
        val blur = (n0 + n1 + n2 + 8) shr 4
        val v = (o shr sh) and 0xFF
        return (v + (((v - blur) * a) shr 8)).coerceIn(0, 255)
    }
}
