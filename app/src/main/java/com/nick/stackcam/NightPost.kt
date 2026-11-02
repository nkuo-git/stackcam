package com.nick.stackcam

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A smooth low-resolution gain field, bilinearly interpolated to full resolution. */
class GainMap(val cols: Int, val rows: Int, val cell: Int, val g: FloatArray) {
    fun at(x: Int, y: Int): Float {
        val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (cols - 1).toFloat())
        val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (rows - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(cols - 1, x0 + 1); val y1 = min(rows - 1, y0 + 1)
        val tx = fx - x0; val ty = fy - y0
        val a = g[y0 * cols + x0] * (1 - tx) + g[y0 * cols + x1] * tx
        val b = g[y1 * cols + x0] * (1 - tx) + g[y1 * cols + x1] * tx
        return a * (1 - ty) + b * ty
    }
}

/**
 * Post-processing of a merged YUV image (Y full resolution, V and U half resolution, values
 * 0..255, NV21 plane order): noise-aware denoise, optional night tone mapping, sharpening and
 * conversion to ARGB.
 */
object NightPost {

    class Style(
        val toneMap: Boolean,
        val lumaK: Float,           // luma denoise strength (x residual noise)
        val chromaK: Float,         // chroma denoise strength
        val sharpen: Float,
        val saturation: Float = 1f,
        val targetKey: Float = 0.26f,
        val maxGain: Float = 4f,
        val grayWorld: Float = 0f,  // 0..1 blend towards a gray-world white balance
    )

    val STYLE_A = Style(toneMap = false, lumaK = 1.0f, chromaK = 2.2f, sharpen = 0.35f)
    val STYLE_C = Style(toneMap = true, lumaK = 1.2f, chromaK = 2.8f, sharpen = 0.3f, saturation = 1.08f, grayWorld = 0.3f)

    fun process(planes: Array<FloatArray>, w: Int, h: Int, style: Style, onProgress: (Int, String) -> Unit = { _, _ -> }): IntArray {
        var y = planes[0]; var v = planes[1]; var u = planes[2]
        val cw = w / 2; val ch = h / 2
        onProgress(70, "估計殘餘雜訊")
        val ny = NoiseEstimator.estimate({ x, yy -> y[yy * w + x] }, w, h, 255f, floor = 0.2f)
        val nc = max(
            NoiseEstimator.estimate({ x, yy -> v[yy * cw + x] }, cw, ch, 255f, floor = 0.2f).sigma(128f),
            NoiseEstimator.estimate({ x, yy -> u[yy * cw + x] }, cw, ch, 255f, floor = 0.2f).sigma(128f),
        )
        val gm = if (style.toneMap) { onProgress(74, "夜景色調"); toneGainMap(y, w, h, style.targetKey, style.maxGain) } else null
        onProgress(78, "亮度降噪")
        y = denoiseLuma(y, w, h, ny, style.lumaK, gm)
        onProgress(83, "色彩降噪")
        val (v2, u2) = denoiseChroma(v, u, cw, ch, nc, style.chromaK, gm)
        v = v2; u = u2
        if (gm != null) applyGain(y, w, h, gm)
        onProgress(88, "銳化")
        y = sharpen(y, w, h, style.sharpen, ny, gm)
        val wb = if (style.grayWorld > 0f) grayWorldShift(y, v, u, w, h, style.grayWorld) else floatArrayOf(0f, 0f)
        onProgress(92, "輸出影像")
        return toArgb(y, v, u, w, h, gm, style.saturation, wb[0], wb[1])
    }

    /** Edge-preserving 3x3 filter: rational range weight 1/(1+(d/(k*sigma))^2). */
    fun denoiseLuma(src: FloatArray, w: Int, h: Int, noise: NoiseModel, k: Float, gm: GainMap?): FloatArray {
        val out = FloatArray(src.size)
        val sw = floatArrayOf(0.5f, 0.75f, 0.5f, 0.75f, 1f, 0.75f, 0.5f, 0.75f, 0.5f)
        Par.rows(h) { y ->
            for (x in 0 until w) {
                val c = src[y * w + x]
                var sr = k * noise.sigma(c) * 1.5f
                if (gm != null) sr *= sqrt(gm.at(x, y))
                val inv = 1f / (sr * sr + 1e-4f)
                var s = 0f; var n = 0f; var t = 0
                for (oy in -1..1) {
                    val yy = (y + oy).coerceIn(0, h - 1)
                    for (ox in -1..1) {
                        val v = src[yy * w + (x + ox).coerceIn(0, w - 1)]
                        val d = v - c
                        val wt = sw[t++] / (1f + d * d * inv)
                        s += wt * v; n += wt
                    }
                }
                out[y * w + x] = s / n
            }
        }
        return out
    }

    /** Joint 5x5 chroma filter (both channels decide the weight). */
    fun denoiseChroma(v: FloatArray, u: FloatArray, w: Int, h: Int, sigma: Float, k: Float, gm: GainMap?): Pair<FloatArray, FloatArray> {
        val ov = FloatArray(v.size); val ou = FloatArray(u.size)
        Par.rows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val cv = v[i]; val cu = u[i]
                var sr = k * sigma * 1.5f
                if (gm != null) sr *= gm.at(2 * x, 2 * y).pow(0.75f)
                val inv = 1f / (2f * sr * sr + 1e-4f)
                var sv = 0f; var su = 0f; var n = 0f
                for (oy in -2..2) {
                    val yy = (y + oy).coerceIn(0, h - 1)
                    for (ox in -2..2) {
                        val j = yy * w + (x + ox).coerceIn(0, w - 1)
                        val dv = v[j] - cv; val du = u[j] - cu
                        val sp = if (abs(ox) + abs(oy) > 2) 0.5f else 1f
                        val wt = sp / (1f + (dv * dv + du * du) * inv)
                        sv += wt * v[j]; su += wt * u[j]; n += wt
                    }
                }
                ov[i] = sv / n; ou[i] = su / n
            }
        }
        return ov to ou
    }

    /** Unsharp mask (3x3 Gaussian) with noise coring so grain isn't amplified. */
    fun sharpen(src: FloatArray, w: Int, h: Int, amount: Float, noise: NoiseModel, gm: GainMap?): FloatArray {
        if (amount <= 0f) return src
        val out = FloatArray(src.size)
        Par.rows(h) { y ->
            val y0 = max(0, y - 1) * w; val y1 = y * w; val y2 = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xl = max(0, x - 1); val xr = min(w - 1, x + 1)
                val blur = (src[y0 + xl] + 2 * src[y0 + x] + src[y0 + xr] +
                    2 * src[y1 + xl] + 4 * src[y1 + x] + 2 * src[y1 + xr] +
                    src[y2 + xl] + 2 * src[y2 + x] + src[y2 + xr]) / 16f
                val c = src[y1 + x]
                val d = c - blur
                var core = 0.5f * noise.sigma(c)
                if (gm != null) core *= gm.at(x, y)
                val dd = if (d > core) d - core else if (d < -core) d + core else 0f
                out[y1 + x] = (c + amount * 2f * dd).coerceIn(0f, 255f)
            }
        }
        return out
    }

    /**
     * Local tone mapping gain field: a 1/16-resolution, edge-preserving (self-guided filter)
     * base layer of the luma; dark regions get gain base^gamma/base where gamma brings the
     * log-average ("key") of the image to [targetKey]. Gain <= [maxGain], ~1 for bright regions.
     */
    fun toneGainMap(y: FloatArray, w: Int, h: Int, targetKey: Float, maxGain: Float, cell: Int = 16): GainMap {
        val cols = max(1, w / cell); val rows = max(1, h / cell)
        val base = FloatArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            var s = 0f; var n = 0
            var yy = r * cell
            while (yy < min(h, (r + 1) * cell)) {
                var xx = c * cell
                while (xx < min(w, (c + 1) * cell)) { s += y[yy * w + xx]; n++; xx += 2 }
                yy += 2
            }
            base[r * cols + c] = s / max(1, n) / 255f
        }
        val smooth = guidedSelf(base, cols, rows, 2, 0.01f)
        var lsum = 0.0
        for (b in smooth) lsum += ln(max(b, 0f) + 0.01)
        val key = exp(lsum / smooth.size).toFloat().coerceIn(0.005f, 0.99f)
        val gamma = (ln(targetKey) / ln(key)).coerceIn(0.45f, 1f)
        val g = FloatArray(smooth.size) {
            val b = smooth[it].coerceIn(0.002f, 1f)
            (b.pow(gamma) / b).coerceIn(1f, maxGain)
        }
        return GainMap(cols, rows, cell, g)
    }

    private fun boxMean(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val out = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var n = 0
            for (yy in max(0, y - r)..min(h - 1, y + r)) for (xx in max(0, x - r)..min(w - 1, x + r)) { s += a[yy * w + xx]; n++ }
            out[y * w + x] = s / n
        }
        return out
    }

    /** He et al. guided filter with the image as its own guide (edge-preserving smoothing). */
    fun guidedSelf(p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val mean = boxMean(p, w, h, r)
        val sq = boxMean(FloatArray(p.size) { p[it] * p[it] }, w, h, r)
        val a = FloatArray(p.size); val b = FloatArray(p.size)
        for (i in p.indices) {
            val vr = sq[i] - mean[i] * mean[i]
            a[i] = vr / (vr + eps); b[i] = mean[i] - a[i] * mean[i]
        }
        val ma = boxMean(a, w, h, r); val mb = boxMean(b, w, h, r)
        return FloatArray(p.size) { ma[it] * p[it] + mb[it] }
    }

    /** Applies the gain field to luma, with a soft highlight shoulder and a gentle S-curve. */
    fun applyGain(y: FloatArray, w: Int, h: Int, gm: GainMap) {
        Par.rows(h) { yy ->
            for (x in 0 until w) {
                val i = yy * w + x
                y[i] = toneValue(y[i] / 255f * gm.at(x, yy)) * 255f
            }
        }
    }

    /** Shoulder above 0.8 (never clips hard) + 12% smoothstep contrast. */
    fun toneValue(v0: Float): Float {
        val knee = 0.8f
        var v = if (v0 <= knee) max(0f, v0) else knee + (1 - knee) * (1 - exp(-(v0 - knee) / (1 - knee)))
        val c = 0.12f
        v = (1 - c) * v + c * (v * v * (3 - 2 * v))
        return v.coerceIn(0f, 1f)
    }

    /** Partial gray-world correction: shift of (V, U) towards neutral over mid-tones, limited. */
    fun grayWorldShift(y: FloatArray, v: FloatArray, u: FloatArray, w: Int, h: Int, blend: Float): FloatArray {
        val cw = w / 2; val ch = h / 2
        var sv = 0.0; var su = 0.0; var n = 0
        var yy = 0
        while (yy < ch) {
            var x = 0
            while (x < cw) {
                val l = y[(2 * yy) * w + 2 * x]
                if (l > 25f && l < 230f) { sv += v[yy * cw + x] - 128.0; su += u[yy * cw + x] - 128.0; n++ }
                x += 4
            }
            yy += 4
        }
        if (n < 100) return floatArrayOf(0f, 0f)
        val dv = (-(sv / n) * blend).toFloat().coerceIn(-6f, 6f)
        val du = (-(su / n) * blend).toFloat().coerceIn(-6f, 6f)
        return floatArrayOf(dv, du)
    }

    /** Full-range BT.601 YCbCr -> ARGB, bilinear chroma upsampling, optional chroma gain. */
    fun toArgb(y: FloatArray, v: FloatArray, u: FloatArray, w: Int, h: Int, gm: GainMap?, sat: Float, dv: Float, du: Float): IntArray {
        val out = IntArray(w * h)
        val cw = w / 2; val ch = h / 2
        Par.rows(h) { yy ->
            val fy = ((yy - 0.5f) / 2f).coerceIn(0f, (ch - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(ch - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x - 0.5f) / 2f).coerceIn(0f, (cw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(cw - 1, x0 + 1); val tx = fx - x0
                fun bl(a: FloatArray): Float {
                    val t = a[y0 * cw + x0] * (1 - tx) + a[y0 * cw + x1] * tx
                    val b = a[y1 * cw + x0] * (1 - tx) + a[y1 * cw + x1] * tx
                    return t * (1 - ty) + b * ty
                }
                var k = sat
                if (gm != null) k *= gm.at(x, yy).pow(0.75f)
                val cr = (bl(v) - 128f + dv) * k
                val cb = (bl(u) - 128f + du) * k
                val l = y[yy * w + x]
                val r = (l + 1.402f * cr).roundToInt().coerceIn(0, 255)
                val g = (l - 0.344136f * cb - 0.714136f * cr).roundToInt().coerceIn(0, 255)
                val b = (l + 1.772f * cb).roundToInt().coerceIn(0, 255)
                out[yy * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** ARGB (8-bit) -> Y / V / U float planes (NV21 order; chroma 2x2 averaged). */
    fun fromArgb(px: IntArray, w: Int, h: Int): Array<FloatArray> {
        val y = FloatArray(w * h); val cw = w / 2; val ch = h / 2
        val v = FloatArray(cw * ch); val u = FloatArray(cw * ch)
        for (i in 0 until w * h) {
            val c = px[i]
            y[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        for (yy in 0 until ch) for (x in 0 until cw) {
            var sv = 0f; var su = 0f
            for (j in 0..1) for (i in 0..1) {
                val c = px[(2 * yy + j) * w + 2 * x + i]
                val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
                sv += 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
                su += -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
            }
            v[yy * cw + x] = sv / 4; u[yy * cw + x] = su / 4
        }
        return arrayOf(y, v, u)
    }

    /** ARGB -> NV21 bytes (used for JPEG-path frames so every night variant shares one merge). */
    fun argbToNv21(px: IntArray, w: Int, h: Int): ByteArray {
        val p = fromArgb(px, w, h)
        val out = ByteArray(w * h * 3 / 2)
        for (i in 0 until w * h) out[i] = p[0][i].roundToInt().coerceIn(0, 255).toByte()
        val cw = w / 2
        for (i in 0 until cw * (h / 2)) {
            out[w * h + 2 * i] = p[1][i].roundToInt().coerceIn(0, 255).toByte()
            out[w * h + 2 * i + 1] = p[2][i].roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }
}
