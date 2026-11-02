package com.nick.stackcam

import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A strided view of one image plane stored in a ByteArray (unsigned), ShortArray (unsigned) or
 * FloatArray. Used for NV21 (Y, V, U) and Bayer RAW (4 CFA sub-planes) without copying.
 */
class PlaneView(
    val w: Int, val h: Int,
    private val b: ByteArray? = null, private val s: ShortArray? = null, private val f: FloatArray? = null,
    val offset: Int = 0, val rowStride: Int = w, val pixStride: Int = 1,
) {
    fun idx(x: Int, y: Int) = offset + y * rowStride + x * pixStride
    fun get(x: Int, y: Int): Float {
        val i = offset + y * rowStride + x * pixStride
        return when {
            b != null -> (b[i].toInt() and 0xFF).toFloat()
            s != null -> (s[i].toInt() and 0xFFFF).toFloat()
            else -> f!![i]
        }
    }

    companion object {
        fun ofFloat(px: FloatArray, w: Int, h: Int) = PlaneView(w, h, f = px)
        /** Y, V, U planes of an NV21 buffer. */
        fun nv21(buf: ByteArray, w: Int, h: Int): Array<PlaneView> = arrayOf(
            PlaneView(w, h, b = buf),
            PlaneView(w / 2, h / 2, b = buf, offset = w * h, rowStride = w, pixStride = 2),
            PlaneView(w / 2, h / 2, b = buf, offset = w * h + 1, rowStride = w, pixStride = 2),
        )
        /** The 4 CFA sub-planes (0,0) (1,0) (0,1) (1,1) of a Bayer image of [w] x [h]. */
        fun bayer(raw: ShortArray, w: Int, h: Int): Array<PlaneView> = Array(4) { k ->
            PlaneView(w / 2, h / 2, s = raw, offset = (k shr 1) * w + (k and 1), rowStride = 2 * w, pixStride = 2)
        }
    }
}

/**
 * A frame to merge: its planes plus [scale] of each plane relative to the merge grid
 * (1 = grid resolution, 0.5 = half, e.g. NV21 chroma). The guide (used for alignment and
 * weights) is the mean of all scale-1 planes.
 */
class MergeFrame(val planes: Array<PlaneView>, val half: BooleanArray) {
    val gw = planes[0].w
    val gh = planes[0].h
    private val guideIdx = planes.indices.filter { !half[it] }.toIntArray()
    private val inv = 1f / guideIdx.size
    fun guide(x: Int, y: Int): Float {
        if (guideIdx.size == 1) return planes[guideIdx[0]].get(x, y)
        var s = 0f
        for (k in guideIdx) s += planes[k].get(x, y)
        return s * inv
    }

    /** Guide downsampled 2x (box) as a [Gray]. */
    fun guideHalf(): Gray {
        val nw = gw / 2; val nh = gh / 2
        val out = FloatArray(nw * nh)
        Par.rows(nh) { y ->
            for (x in 0 until nw) {
                val xx = 2 * x; val yy = 2 * y
                out[y * nw + x] = (guide(xx, yy) + guide(xx + 1, yy) + guide(xx, yy + 1) + guide(xx + 1, yy + 1)) * 0.25f
            }
        }
        return Gray(nw, nh, out)
    }

    companion object {
        fun nv21(buf: ByteArray, w: Int, h: Int) = MergeFrame(PlaneView.nv21(buf, w, h), booleanArrayOf(false, true, true))
        fun bayer(raw: ShortArray, w: Int, h: Int) = MergeFrame(PlaneView.bayer(raw, w, h), BooleanArray(4))
    }
}

object Par {
    /** Runs [body] for 0 until n in parallel (rows / tile rows). */
    fun rows(n: Int, body: (Int) -> Unit) {
        if (n <= 0) return
        IntStream.range(0, n).parallel().forEach { body(it) }
    }
}

/**
 * Signal-dependent noise model: sigma (standard deviation) of a single frame as a function of
 * signal level, sampled at [levels] and linearly interpolated.
 */
class NoiseModel(val levels: FloatArray, val sigmas: FloatArray) {
    fun sigma(v: Float): Float {
        if (levels.size == 1 || v <= levels[0]) return sigmas[0]
        for (i in 1 until levels.size) {
            if (v <= levels[i]) {
                val t = (v - levels[i - 1]) / (levels[i] - levels[i - 1])
                return sigmas[i - 1] + t * (sigmas[i] - sigmas[i - 1])
            }
        }
        return sigmas.last()
    }
    fun variance(v: Float): Float { val s = sigma(v); return s * s }
    fun scaled(k: Float) = NoiseModel(levels, FloatArray(sigmas.size) { sigmas[it] * k })
    fun maxSigma() = sigmas.maxOrNull() ?: 1f

    companion object {
        fun constant(sigma: Float) = NoiseModel(floatArrayOf(0f), floatArrayOf(sigma))
    }
}

object NoiseEstimator {
    /**
     * Estimates per-level noise of a plane with Immerkaer's Laplacian-difference operator on
     * 8x8 blocks; per brightness bin a low percentile of the block estimates is taken so that
     * texture doesn't count as noise. [maxV] is the value range (255 for 8-bit).
     */
    fun estimate(get: (Int, Int) -> Float, w: Int, h: Int, maxV: Float, bins: Int = 8, blockStep: Int = 2, floor: Float = 0.3f): NoiseModel {
        val bs = 8
        val bx = (w - 2) / bs; val by = (h - 2) / bs
        val lists = Array(bins) { ArrayList<Float>() }
        val c = sqrt(PI / 2).toFloat() / (6f * bs * bs)
        var yb = 0
        while (yb < by) {
            var xb = (yb / blockStep) % blockStep
            while (xb < bx) {
                var acc = 0f; var mean = 0f
                val x0 = 1 + xb * bs; val y0 = 1 + yb * bs
                for (y in y0 until y0 + bs) for (x in x0 until x0 + bs) {
                    val v = get(x, y)
                    mean += v
                    val l = get(x - 1, y - 1) - 2 * get(x, y - 1) + get(x + 1, y - 1) -
                        2 * get(x - 1, y) + 4 * v - 2 * get(x + 1, y) +
                        get(x - 1, y + 1) - 2 * get(x, y + 1) + get(x + 1, y + 1)
                    acc += abs(l)
                }
                mean /= bs * bs
                // Skip clipped blocks: they have no noise and would bias the estimate low.
                if (mean > maxV * 0.02f && mean < maxV * 0.97f) {
                    val bin = ((mean / maxV) * bins).toInt().coerceIn(0, bins - 1)
                    lists[bin].add(acc * c)
                }
                xb += blockStep
            }
            yb += blockStep
        }
        val lv = ArrayList<Float>(); val sg = ArrayList<Float>()
        for (b in 0 until bins) {
            val l = lists[b]
            if (l.size < 12) continue
            l.sort()
            lv += (b + 0.5f) / bins * maxV
            sg += max(floor, l[(l.size * 0.25f).toInt()])
        }
        if (lv.isEmpty()) {
            val all = lists.flatMap { it }.sorted()
            return NoiseModel.constant(if (all.isEmpty()) max(floor, maxV / 128f) else max(floor, all[all.size / 4]))
        }
        return NoiseModel(lv.toFloatArray(), sg.toFloatArray())
    }
}

/**
 * Hierarchical tile alignment (coarse-to-fine). Produces one integer displacement per
 * alignment tile (in grid pixels) such that frame(x+dx, y+dy) matches ref(x, y).
 */
class TileAlignment(val tile: Int, val cols: Int, val rows: Int, val dx: IntArray, val dy: IntArray) {
    /** Displacement at grid position (x, y), bilinearly interpolated between tile centers. */
    fun at(x: Float, y: Float): Pair<Int, Int> {
        val fx = (x / tile - 0.5f).coerceIn(0f, (cols - 1).toFloat())
        val fy = (y / tile - 0.5f).coerceIn(0f, (rows - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(cols - 1, x0 + 1); val y1 = min(rows - 1, y0 + 1)
        val tx = fx - x0; val ty = fy - y0
        fun lerp(a: IntArray): Float {
            val top = a[y0 * cols + x0] * (1 - tx) + a[y0 * cols + x1] * tx
            val bot = a[y1 * cols + x0] * (1 - tx) + a[y1 * cols + x1] * tx
            return top * (1 - ty) + bot * ty
        }
        return lerp(dx).roundToInt() to lerp(dy).roundToInt()
    }

    companion object {
        fun uniform(tile: Int, gw: Int, gh: Int, dx: Int, dy: Int): TileAlignment {
            val c = max(1, gw / tile); val r = max(1, gh / tile)
            return TileAlignment(tile, c, r, IntArray(c * r) { dx }, IntArray(c * r) { dy })
        }
    }
}

object TileAligner {
    private fun sadTile(ref: Gray, frm: Gray, x0: Int, y0: Int, t: Int, dx: Int, dy: Int, step: Int): Float {
        var s = 0f; var n = 0
        var y = y0
        while (y < y0 + t) {
            val fy = y + dy
            if (y in 0 until ref.h && fy in 0 until frm.h) {
                var x = x0
                while (x < x0 + t) {
                    val fx = x + dx
                    if (x in 0 until ref.w && fx in 0 until frm.w) {
                        s += abs(ref.px[y * ref.w + x] - frm.px[fy * frm.w + fx]); n++
                    }
                    x += step
                }
            }
            y += step
        }
        return if (n < 4) Float.MAX_VALUE else s / n
    }

    private fun sadGuide(ref: MergeFrame, frm: MergeFrame, x0: Int, y0: Int, t: Int, dx: Int, dy: Int, step: Int): Float {
        var s = 0f; var n = 0
        var y = y0
        while (y < y0 + t) {
            val fy = y + dy
            if (y in 0 until ref.gh && fy in 0 until frm.gh) {
                var x = x0
                while (x < x0 + t) {
                    val fx = x + dx
                    if (x in 0 until ref.gw && fx in 0 until frm.gw) { s += abs(ref.guide(x, y) - frm.guide(fx, fy)); n++ }
                    x += step
                }
            }
            y += step
        }
        return if (n < 4) Float.MAX_VALUE else s / n
    }

    private fun variance(g: Gray, x0: Int, y0: Int, t: Int): Float {
        var s = 0f; var s2 = 0f; var n = 0
        for (y in y0 until min(g.h, y0 + t)) for (x in x0 until min(g.w, x0 + t)) {
            val v = g.px[y * g.w + x]; s += v; s2 += v * v; n++
        }
        if (n == 0) return 0f
        val m = s / n
        return s2 / n - m * m
    }

    private fun median3x3(a: IntArray, cols: Int, rows: Int): IntArray {
        val out = IntArray(a.size)
        val buf = IntArray(9)
        for (r in 0 until rows) for (c in 0 until cols) {
            var n = 0
            for (oy in -1..1) for (ox in -1..1) {
                val rr = (r + oy).coerceIn(0, rows - 1); val cc = (c + ox).coerceIn(0, cols - 1)
                buf[n++] = a[rr * cols + cc]
            }
            buf.sort(0, n)
            out[r * cols + c] = buf[n / 2]
        }
        return out
    }

    /**
     * [refPyr]/[frmPyr] are guide pyramids starting at HALF grid resolution (level 0 = 1/2).
     * [tile] is the alignment tile size in grid pixels (multiple of 8). [noiseSigma] is the
     * typical guide noise; flat tiles (no texture above noise) keep the global shift.
     */
    fun align(
        ref: MergeFrame, frm: MergeFrame, refPyr: List<Gray>, frmPyr: List<Gray>,
        tile: Int, noiseSigma: Float, localRadius: Int = 2,
    ): TileAlignment {
        val (gx, gy) = StackCore.estimateShift(refPyr, frmPyr)
        val gdx = (gx * 2f).roundToInt(); val gdy = (gy * 2f).roundToInt()
        val cols = max(1, ref.gw / tile); val rows = max(1, ref.gh / tile)
        val dx = IntArray(cols * rows); val dy = IntArray(cols * rows)
        val half = refPyr[0]; val halfF = frmPyr[0]
        val quarter = refPyr.getOrNull(1); val quarterF = frmPyr.getOrNull(1)
        // Texture threshold at quarter resolution: noise there is ~sigma/4 (4x4 box of the grid).
        val flatVar = 4f * (noiseSigma / 4f) * (noiseSigma / 4f) + 1f
        Par.rows(rows) { r ->
            for (c in 0 until cols) {
                val i = r * cols + c
                var bx = gdx; var by = gdy
                val textured = quarter == null || variance(quarter, c * tile / 4, r * tile / 4, tile / 4) > flatVar
                if (textured) {
                    // quarter level, search around the global shift
                    if (quarter != null && quarterF != null) {
                        val px = Math.floorDiv(gdx, 4); val py = Math.floorDiv(gdy, 4)
                        var best = Float.MAX_VALUE; var cx = px; var cy = py
                        for (oy in -localRadius..localRadius) for (ox in -localRadius..localRadius) {
                            val s = sadTile(quarter, quarterF, c * tile / 4, r * tile / 4, tile / 4, px + ox, py + oy, 1) +
                                0.02f * noiseSigma * (abs(ox) + abs(oy))
                            if (s < best) { best = s; cx = px + ox; cy = py + oy }
                        }
                        bx = cx * 4; by = cy * 4
                    }
                    // half level, +-2 around the (upscaled) coarse estimate
                    run {
                        val px = Math.floorDiv(bx, 2); val py = Math.floorDiv(by, 2)
                        val rr = if (quarter != null) 2 else localRadius + 1
                        var best = Float.MAX_VALUE; var cx = px; var cy = py
                        for (oy in -rr..rr) for (ox in -rr..rr) {
                            val s = sadTile(half, halfF, c * tile / 2, r * tile / 2, tile / 2, px + ox, py + oy, 1)
                            if (s < best) { best = s; cx = px + ox; cy = py + oy }
                        }
                        bx = cx * 2; by = cy * 2
                    }
                    // grid level, +-1
                    run {
                        var best = Float.MAX_VALUE; var cx = bx; var cy = by
                        val step = if (tile >= 48) 2 else 1
                        for (oy in -1..1) for (ox in -1..1) {
                            val s = sadGuide(ref, frm, c * tile, r * tile, tile, bx + ox, by + oy, step)
                            if (s < best) { best = s; cx = bx + ox; cy = by + oy }
                        }
                        bx = cx; by = cy
                    }
                }
                dx[i] = bx; dy[i] = by
            }
        }
        return TileAlignment(tile, cols, rows, median3x3(dx, cols, rows), median3x3(dy, cols, rows))
    }
}

/**
 * Noise-adaptive tile merge (HDR+ style, spatial domain): overlapping tiles of [tile] grid pixels
 * (stride tile/2, raised-cosine windows that sum to 1). In each tile every frame gets a
 * Wiener-like weight from how much its aligned tile differs from the reference beyond the
 * expected noise, times a per-pixel ghost mask (3x3-filtered difference vs. noise).
 * [strength] > 1 merges more aggressively (more denoise, more ghost risk).
 */
class TileMerger(
    private val ref: MergeFrame,
    private val noise: NoiseModel,
    private val tile: Int = 32,
    private val strength: Float = 1f,
) {
    val gw = ref.gw; val gh = ref.gh
    val out: Array<FloatArray> = Array(ref.planes.size) { FloatArray(ref.planes[it].w * ref.planes[it].h) }
    /** Average total weight per pixel (1 = only the reference contributed), for diagnostics. */
    var meanWeight = 1f; private set

    fun merge(frames: List<MergeFrame>, aligns: List<TileAlignment>) {
        val t = tile; val st = t / 2
        val win = FloatArray(t) { (0.5 - 0.5 * cos(2 * PI * (it + 0.5) / t)).toFloat() }
        val th = t / 2
        val winH = FloatArray(th) { (0.5 - 0.5 * cos(2 * PI * (it + 0.5) / th)).toFloat() }
        val tilesX = (gw - 1) / st + 2; val tilesY = (gh - 1) / st + 2
        val wsum = DoubleArray(tilesY)
        for (phase in 0..1) {
            Par.rows((tilesY - phase + 1) / 2) { k ->
                val ty = 2 * k + phase
                val diff = FloatArray(t * t); val mask = FloatArray(t * t)
                val wts = FloatArray(frames.size)
                val sx = IntArray(frames.size); val sy = IntArray(frames.size)
                val acc = Array(ref.planes.size) { FloatArray(t * t) }
                val nrm = Array(ref.planes.size) { FloatArray(t * t) }
                val masks = Array(frames.size) { FloatArray(t * t) }
                var wrow = 0.0
                for (tx in 0 until tilesX) {
                    val ox = tx * st - st; val oy = ty * st - st
                    // tile mean level of the reference (for the noise model)
                    var mean = 0f; var cnt = 0
                    var yy = max(0, oy)
                    while (yy < min(gh, oy + t)) { var xx = max(0, ox); while (xx < min(gw, ox + t)) { mean += ref.guide(xx, yy); cnt++; xx += 2 }; yy += 2 }
                    if (cnt == 0) continue
                    mean /= cnt
                    val varN = noise.variance(mean)
                    val e = 2f * varN                       // expected E[(ref-frm)^2] for a perfect match
                    for ((fi, f) in frames.withIndex()) {
                        val (ddx, ddy) = aligns[fi].at(ox + t / 2f, oy + t / 2f)
                        sx[fi] = ddx; sy[fi] = ddy
                        var d2 = 0f; var n = 0
                        for (j in 0 until t) {
                            val y = oy + j
                            for (i in 0 until t) {
                                val x = ox + i
                                val fx = x + ddx; val fy = y + ddy
                                val p = j * t + i
                                if (y < 0 || y >= gh || x < 0 || x >= gw || fx < 0 || fy < 0 || fx >= f.gw || fy >= f.gh) {
                                    diff[p] = Float.NaN; continue
                                }
                                val d = ref.guide(x, y) - f.guide(fx, fy)
                                diff[p] = d; d2 += d * d; n++
                            }
                        }
                        if (n == 0) { wts[fi] = 0f; continue }
                        d2 /= n
                        val excess = max(0f, d2 - e)
                        wts[fi] = 1f / (1f + excess / (0.5f * strength * e + 1e-6f))
                        // per-pixel ghost mask from a 3x3 box of the difference
                        val sd = sqrt(2f * varN / 9f) + 0.5f
                        val t1 = 2.5f * sd * strength; val t2 = 5f * sd * strength
                        val m = masks[fi]
                        for (j in 0 until t) for (i in 0 until t) {
                            val p = j * t + i
                            if (diff[p].isNaN()) { m[p] = 0f; continue }
                            var s = 0f; var c = 0
                            for (b in max(0, j - 1)..min(t - 1, j + 1)) for (a in max(0, i - 1)..min(t - 1, i + 1)) {
                                val v = diff[b * t + a]; if (!v.isNaN()) { s += v; c++ }
                            }
                            val ad = abs(s / c)
                            m[p] = when { ad <= t1 -> 1f; ad >= t2 -> 0f; else -> (t2 - ad) / (t2 - t1) }
                        }
                        mask.fill(0f)
                    }
                    for (pi in ref.planes.indices) {
                        val halfP = ref.half[pi]
                        val tp = if (halfP) th else t
                        val a = acc[pi]; val nr = nrm[pi]
                        val rp = ref.planes[pi]
                        val pox = if (halfP) ox / 2 else ox; val poy = if (halfP) oy / 2 else oy
                        for (j in 0 until tp) {
                            val y = poy + j
                            if (y < 0 || y >= rp.h) continue
                            for (i in 0 until tp) {
                                val x = pox + i
                                if (x < 0 || x >= rp.w) continue
                                val p = j * tp + i
                                a[p] = rp.get(x, y); nr[p] = 1f
                            }
                        }
                        for ((fi, f) in frames.withIndex()) {
                            val wf = wts[fi]
                            if (wf < 0.02f) continue
                            val fp = f.planes[pi]
                            val m = masks[fi]
                            val ddx = if (halfP) Math.floorDiv(sx[fi] + 1, 2) else sx[fi]
                            val ddy = if (halfP) Math.floorDiv(sy[fi] + 1, 2) else sy[fi]
                            for (j in 0 until tp) {
                                val y = poy + j; val fy = y + ddy
                                if (y < 0 || y >= rp.h || fy < 0 || fy >= fp.h) continue
                                for (i in 0 until tp) {
                                    val x = pox + i; val fx = x + ddx
                                    if (x < 0 || x >= rp.w || fx < 0 || fx >= fp.w) continue
                                    val mw = if (halfP) m[(2 * j) * t + 2 * i] else m[j * t + i]
                                    val ww = wf * mw
                                    if (ww <= 0f) continue
                                    val p = j * tp + i
                                    a[p] += ww * fp.get(fx, fy); nr[p] += ww
                                }
                            }
                        }
                        val o = out[pi]
                        val wv = if (halfP) winH else win
                        for (j in 0 until tp) {
                            val y = poy + j
                            if (y < 0 || y >= rp.h) continue
                            val wy = wv[j]
                            for (i in 0 until tp) {
                                val x = pox + i
                                if (x < 0 || x >= rp.w) continue
                                val p = j * tp + i
                                o[y * rp.w + x] += wy * wv[i] * a[p] / nr[p]
                                if (pi == 0) wrow += (wy * wv[i] * nr[p]).toDouble()
                            }
                        }
                    }
                }
                wsum[ty] = wrow
            }
        }
        val total = wsum.sum()
        meanWeight = (total / (ref.planes[0].w.toDouble() * ref.planes[0].h)).toFloat()
    }
}

/** Whole burst: reference selection, alignment and merge. */
object BurstMerge {
    class Result(val planes: Array<FloatArray>, val refIndex: Int, val noise: NoiseModel, val meanWeight: Float, val gw: Int, val gh: Int)

    fun sharpness(f: MergeFrame): Double {
        // Laplacian variance on a 1/4-scale guide sample
        val w = f.gw / 4; val h = f.gh / 4
        val px = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = f.guide(4 * x + 1, 4 * y + 1)
        return StackCore.sharpness(Gray(w, h, px))
    }

    fun run(
        frames: List<MergeFrame>, maxV: Float, noise: NoiseModel? = null,
        mergeTile: Int = 32, alignTile: Int = 64, strength: Float = 1f,
        refIndex: Int? = null, onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Result {
        require(frames.isNotEmpty())
        val n = frames.size
        val ri = refIndex ?: run {
            val s = frames.mapIndexed { i, f -> onProgress(2 + 8 * i / n, "評估清晰度 ${i + 1}/$n"); sharpness(f) }
            s.indices.maxByOrNull { s[it] } ?: 0
        }
        val ref = frames[ri]
        val nm = noise ?: NoiseEstimator.estimate({ x, y -> ref.guide(x, y) }, ref.gw, ref.gh, maxV)
        val refPyr = StackCore.pyramid(ref.guideHalf(), 4)
        val others = ArrayList<MergeFrame>(); val aligns = ArrayList<TileAlignment>()
        var k = 0
        for ((i, f) in frames.withIndex()) {
            if (i == ri) continue
            k++
            onProgress(10 + 40 * k / n, "對齊影格 $k/${n - 1}")
            if (f.gw != ref.gw || f.gh != ref.gh) continue
            val pyr = StackCore.pyramid(f.guideHalf(), 4)
            aligns += TileAligner.align(ref, f, refPyr, pyr, alignTile, nm.sigma(maxV * 0.25f))
            others += f
        }
        onProgress(55, "降噪合成 ${others.size + 1} 張")
        val m = TileMerger(ref, nm, mergeTile, strength)
        m.merge(others, aligns)
        return Result(m.out, ri, nm, m.meanWeight, ref.gw, ref.gh)
    }
}
