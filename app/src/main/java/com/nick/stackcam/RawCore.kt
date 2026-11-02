package com.nick.stackcam

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Lens shading gain map (R, Gr, Gb, B) covering the whole image, bilinearly interpolated. */
class ShadingMap(val cols: Int, val rows: Int, val gains: Array<FloatArray>) {
    fun gain(ch: Int, x: Float, y: Float, w: Int, h: Int): Float {
        val fx = (x / max(1, w - 1) * (cols - 1)).coerceIn(0f, (cols - 1).toFloat())
        val fy = (y / max(1, h - 1) * (rows - 1)).coerceIn(0f, (rows - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(cols - 1, x0 + 1); val y1 = min(rows - 1, y0 + 1)
        val tx = fx - x0; val ty = fy - y0
        val g = gains[ch]
        val a = g[y0 * cols + x0] * (1 - tx) + g[y0 * cols + x1] * tx
        val b = g[y1 * cols + x0] * (1 - tx) + g[y1 * cols + x1] * tx
        return a * (1 - ty) + b * ty
    }
}

/**
 * Everything needed to develop a Bayer RAW_SENSOR frame.
 * [cfa]: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR).
 * [black]: per CFA position, index = row * 2 + col. [wb]: gains R, G-even (G on R rows),
 * G-odd (G on B rows), B. [ccm]: 3x3 row-major, white-balanced camera RGB -> linear sRGB.
 * [noiseProfile]: SENSOR_NOISE_PROFILE as (S, O) pairs per CFA position (normalized units), or null.
 */
class RawInfo(
    val w: Int, val h: Int, val cfa: Int,
    val black: FloatArray, val white: Float,
    val wb: FloatArray, val ccm: FloatArray,
    val shading: ShadingMap? = null,
    val noiseProfile: DoubleArray? = null,
)

object RawCore {
    const val R = 0; const val GR = 1; const val GB = 2; const val B = 3

    private val CHAN = arrayOf(
        intArrayOf(R, GR, GB, B),   // RGGB
        intArrayOf(GR, R, B, GB),   // GRBG
        intArrayOf(GB, B, R, GR),   // GBRG
        intArrayOf(B, GB, GR, R),   // BGGR
    )

    /** Channel (R, GR, GB, B) of CFA position k = row*2+col. */
    fun channel(cfa: Int, k: Int): Int = CHAN[cfa.coerceIn(0, 3)][k]
    fun color(ch: Int): Int = when (ch) { R -> 0; B -> 2; else -> 1 }

    /** XYZ (D50) -> linear sRGB (Bradford-adapted), for FORWARD_MATRIX based color. */
    val XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f,
    )

    fun mul3(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun inv3(m: FloatArray): FloatArray {
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(kotlin.math.abs(det) > 1e-12) { "singular matrix" }
        return floatArrayOf(
            ((e * i - f * h) / det).toFloat(), ((c * h - b * i) / det).toFloat(), ((b * f - c * e) / det).toFloat(),
            ((f * g - d * i) / det).toFloat(), ((a * i - c * g) / det).toFloat(), ((c * d - a * f) / det).toFloat(),
            ((d * h - e * g) / det).toFloat(), ((b * g - a * h) / det).toFloat(), ((a * e - b * d) / det).toFloat(),
        )
    }

    /** Scales each row to sum to 1 so neutral (white-balanced) gray stays neutral. */
    fun normalizeRows(m: FloatArray): FloatArray {
        val o = m.copyOf()
        for (r in 0..2) {
            val s = o[r * 3] + o[r * 3 + 1] + o[r * 3 + 2]
            if (kotlin.math.abs(s) > 1e-3f) for (c in 0..2) o[r * 3 + c] /= s
        }
        return o
    }

    /** Color matrix from a DNG-style forward matrix (WB camera -> XYZ D50). */
    fun ccmFromForwardMatrix(fm: FloatArray) = normalizeRows(mul3(XYZ_D50_TO_SRGB, fm))

    /** White-balance gains (R, Gr, Gb, B) from AS_SHOT_NEUTRAL (camera RGB of white). */
    fun gainsFromNeutral(r: Float, g: Float, b: Float): FloatArray =
        floatArrayOf(g / r, 1f, 1f, g / b)

    /** Copies a RAW_SENSOR plane (16-bit little-endian, any row stride) into [out]. */
    fun unpack16(buf: ByteBuffer, w: Int, h: Int, rowStride: Int, out: ShortArray = ShortArray(w * h)): ShortArray {
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) {
            b.position(y * rowStride)
            b.asShortBuffer().get(out, y * w, w)
        }
        return out
    }

    /**
     * Noise model of the merge guide (mean of the 4 CFA planes, raw units) from the sensor's
     * noise profile: var(v) = S*v + O per channel in normalized units.
     */
    fun guideNoise(info: RawInfo): NoiseModel? {
        val p = info.noiseProfile ?: return null
        if (p.size < 2) return null
        val n = p.size / 2
        val blk = info.black.average().toFloat()
        val range = info.white - blk
        val lv = FloatArray(9); val sg = FloatArray(9)
        for (i in 0..8) {
            val v = i / 8f
            var vs = 0.0
            for (c in 0 until 4) { val k = c % n; vs += max(0.0, p[2 * k] * v + p[2 * k + 1]) }
            val varGuide = vs / 16.0 * range * range   // mean of 4 independent channels
            lv[i] = blk + v * range
            sg[i] = kotlin.math.sqrt(varGuide).toFloat().coerceAtLeast(0.3f)
        }
        return NoiseModel(lv, sg)
    }

    /**
     * In place: black level, lens shading and white balance on merged CFA planes
     * (planes[k] = CFA position k, quarter resolution qw x qh). Result 0..~1 (1 = white level, G).
     */
    fun normalize(planes: Array<FloatArray>, qw: Int, qh: Int, info: RawInfo) {
        val minWb = info.wb.minOrNull() ?: 1f
        for (k in 0..3) {
            val ch = channel(info.cfa, k)
            val blk = info.black[k]
            val inv = 1f / max(1f, info.white - blk)
            val g = info.wb[ch]
            val p = planes[k]
            val px = k and 1; val py = k shr 1
            Par.rows(qh) { y ->
                for (x in 0 until qw) {
                    val sh = if (info.shading != null) info.shading.gain(ch, (2 * x + px).toFloat(), (2 * y + py).toFloat(), info.w, info.h) else 1f
                    val i = y * qw + x
                    // Clip at the saturation point of the lowest-gain channel so blown highlights stay white.
                    p[i] = min((p[i] - blk) * inv * g * sh, minWb * sh).coerceAtLeast(0f)
                }
            }
        }
    }

    /** Replaces isolated hot pixels (much brighter than all 8 same-color neighbours). */
    fun hotPixels(planes: Array<FloatArray>, qw: Int, qh: Int, thresh: Float = 0.02f): Int {
        var fixed = 0
        for (p in planes) {
            val src = p.copyOf()
            for (y in 1 until qh - 1) for (x in 1 until qw - 1) {
                val i = y * qw + x
                var mx = 0f; var s = 0f
                for (oy in -1..1) for (ox in -1..1) {
                    if (ox == 0 && oy == 0) continue
                    val v = src[i + oy * qw + ox]; if (v > mx) mx = v; s += v
                }
                if (src[i] > 2f * mx + thresh) { p[i] = s / 8f; fixed++ }
            }
        }
        return fixed
    }

    /** sRGB OETF (linear 0..1 -> encoded 0..1). */
    fun srgb(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }
    fun srgbInv(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Default RAW tone: soft highlight shoulder in linear light, then sRGB encoding. */
    fun baseTone(v: Float): Float {
        val knee = 0.7f
        val x = if (v <= knee) max(0f, v) else knee + (1 - knee) * (1 - kotlin.math.exp(-(v - knee) / (1 - knee)))
        return srgb(x)
    }

    /**
     * Mean linear luminance of the developed image (sampled), before exposure gain.
     */
    fun meanLuminance(planes: Array<FloatArray>, qw: Int, qh: Int, info: RawInfo): Float {
        val k = IntArray(4) { channel(info.cfa, it) }
        var sum = 0.0; var n = 0
        val m = info.ccm
        var y = 0
        while (y < qh) {
            var x = 0
            while (x < qw) {
                var r = 0f; var g = 0f; var b = 0f
                for (p in 0..3) {
                    val v = planes[p][y * qw + x]
                    when (k[p]) { R -> r = v; B -> b = v; else -> g += 0.5f * v }
                }
                val lr = m[0] * r + m[1] * g + m[2] * b
                val lg = m[3] * r + m[4] * g + m[5] * b
                val lb = m[6] * r + m[7] * g + m[8] * b
                sum += (0.2126f * lr + 0.7152f * lg + 0.0722f * lb).coerceIn(0f, 1f); n++
                x += 4
            }
            y += 4
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    /**
     * Malvar-He-Cutler (gradient-corrected bilinear) demosaic of normalized CFA planes, color
     * correction to linear sRGB, exposure [gain], [tone] (linear -> display 0..1), and output as
     * Y / V / U planes (0..255, NV21 order, chroma 2x2 averaged), ready for [NightPost].
     */
    fun develop(planes: Array<FloatArray>, qw: Int, qh: Int, info: RawInfo, gain: Float, tone: (Float) -> Float = ::baseTone): Array<FloatArray> {
        val w = qw * 2; val h = qh * 2
        val yP = FloatArray(w * h); val vP = FloatArray(qw * qh); val uP = FloatArray(qw * qh)
        val chanAt = IntArray(4) { channel(info.cfa, it) }
        val m = info.ccm
        // tone LUT (input linear 0..8 after gain)
        val lutN = 4096; val lutMax = 8f
        val lut = FloatArray(lutN + 1) { tone(it * lutMax / lutN) * 255f }
        fun t(v: Float): Float {
            val f = (v.coerceIn(0f, lutMax) * (lutN / lutMax))
            val i = f.toInt().coerceAtMost(lutN - 1); val fr = f - i
            return lut[i] * (1 - fr) + lut[i + 1] * fr
        }
        Par.rows(qh) { qy ->
            val rgb = FloatArray(3)
            var su = FloatArray(qw); var sv = FloatArray(qw)
            for (dy in 0..1) {
                val y = 2 * qy + dy
                for (x in 0 until w) {
                    demosaicPixel(planes, qw, w, h, x, y, chanAt, rgb)
                    val r0 = rgb[0] * gain; val g0 = rgb[1] * gain; val b0 = rgb[2] * gain
                    val lr = t(m[0] * r0 + m[1] * g0 + m[2] * b0)
                    val lg = t(m[3] * r0 + m[4] * g0 + m[5] * b0)
                    val lb = t(m[6] * r0 + m[7] * g0 + m[8] * b0)
                    yP[y * w + x] = 0.299f * lr + 0.587f * lg + 0.114f * lb
                    val cx = x shr 1
                    sv[cx] += 0.5f * lr - 0.418688f * lg - 0.081312f * lb
                    su[cx] += -0.168736f * lr - 0.331264f * lg + 0.5f * lb
                }
            }
            for (cx in 0 until qw) { vP[qy * qw + cx] = sv[cx] / 4f + 128f; uP[qy * qw + cx] = su[cx] / 4f + 128f }
        }
        return arrayOf(yP, vP, uP)
    }

    /** Camera RGB (white balanced) at full-res pixel (x, y). */
    fun demosaicPixel(planes: Array<FloatArray>, qw: Int, w: Int, h: Int, x: Int, y: Int, chanAt: IntArray, out: FloatArray) {
        fun c(xx: Int, yy: Int): Float {
            val mx = if (xx < 0) -xx else if (xx >= w) 2 * w - 2 - xx else xx
            val my = if (yy < 0) -yy else if (yy >= h) 2 * h - 2 - yy else yy
            return planes[((my and 1) shl 1) or (mx and 1)][(my shr 1) * qw + (mx shr 1)]
        }
        val ch = chanAt[((y and 1) shl 1) or (x and 1)]
        val c0 = c(x, y)
        val cross1 = c(x - 1, y) + c(x + 1, y) + c(x, y - 1) + c(x, y + 1)
        val cross2 = c(x - 2, y) + c(x + 2, y) + c(x, y - 2) + c(x, y + 2)
        val diag = c(x - 1, y - 1) + c(x + 1, y - 1) + c(x - 1, y + 1) + c(x + 1, y + 1)
        when (ch) {
            R, B -> {
                val g = (4 * c0 - cross2 + 2 * cross1) / 8f
                val o = (6 * c0 - 1.5f * cross2 + 2 * diag) / 8f
                if (ch == R) { out[0] = c0; out[1] = g; out[2] = o } else { out[0] = o; out[1] = g; out[2] = c0 }
            }
            else -> {
                val h2 = c(x - 2, y) + c(x + 2, y); val v2 = c(x, y - 2) + c(x, y + 2)
                val row = (5 * c0 + 4 * (c(x - 1, y) + c(x + 1, y)) - h2 - diag + 0.5f * v2) / 8f
                val col = (5 * c0 + 4 * (c(x, y - 1) + c(x, y + 1)) - v2 - diag + 0.5f * h2) / 8f
                // Which colour sits left/right of this green?
                val hcol = color(chanAt[((y and 1) shl 1) or ((x + 1) and 1)])
                if (hcol == 0) { out[0] = row; out[2] = col } else { out[0] = col; out[2] = row }
                out[1] = c0
            }
        }
    }
}
