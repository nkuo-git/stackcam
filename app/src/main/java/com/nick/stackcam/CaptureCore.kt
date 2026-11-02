package com.nick.stackcam

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure (Android-free) helpers for the fast burst capture, unit tested on the JVM. */
object CaptureCore {

    enum class Mode { NORMAL, NIGHT, HDR, SUPERRES }

    /** Night-mode variants the user can compare side by side. */
    enum class NightVariant(val code: String, val label: String, val title: String, val desc: String, val raw: Boolean, val tone: Boolean, val exifName: String) {
        A("A", "A\n降噪", "夜景A 加強降噪", "分區對齊＋依雜訊加權合成，再做保留邊緣的降噪", false, false, "Night A (tile-aligned noise-weighted merge)"),
        B("B", "B\nRAW", "夜景B RAW 合成", "用未經處理的 RAW 疊圖，自己做白平衡、去馬賽克和色彩", true, false, "Night B (RAW merge)"),
        C("C", "C\n提亮", "夜景C 提亮色調", "A 的合成＋暗部提亮、亮部保護、自然色彩", false, true, "Night C (merge + night tone mapping)"),
        D("D", "D\nRAW提亮", "夜景D RAW＋提亮", "B 的 RAW 合成＋C 的提亮色調", true, true, "Night D (RAW merge + night tone mapping)");

        /** What to use when RAW isn't available. */
        fun nonRaw(): NightVariant = when (this) { B -> A; D -> C; else -> this }

        companion object {
            fun of(code: String?): NightVariant = values().firstOrNull { it.code == code } ?: A
        }
    }

    /** RAW frames per night burst (RAW frames are 2 bytes/pixel, so fewer than the YUV burst). */
    const val RAW_FRAMES = 8

    /**
     * How many frames to keep for a mode and how many stream frames to skip between them.
     * SUPERRES keeps every 2nd frame so natural hand shake spreads the frames over more sub-pixel
     * positions; the others take consecutive frames to keep the hold time as short as possible.
     */
    data class BurstPlan(val frames: Int, val stride: Int)

    fun burstPlan(mode: Mode, zoom: Int): BurstPlan = when (mode) {
        Mode.NORMAL -> BurstPlan(6, 1)
        Mode.NIGHT -> BurstPlan(10, 1)
        Mode.SUPERRES -> BurstPlan(if (zoom >= 3) 12 else 10, 2)
        Mode.HDR -> BurstPlan(6, 1) // 2 x (0, -2, +2 EV)
    }

    /** HDR shooting order: the current exposure first (no waiting), then darker, then brighter. */
    val HDR_PLAN = listOf(0, 0, -2, -2, 2, 2)

    /**
     * Manual exposure for a bracket of [ev] stops relative to the metered [baseTimeNs]/[baseIso].
     * Darker brackets shorten the shutter; brighter brackets lengthen it up to [maxTimeNs]
     * (to limit motion blur) and make up the rest with ISO. Values are clamped to the sensor ranges.
     */
    fun bracketExposure(
        baseTimeNs: Long, baseIso: Int, ev: Int,
        minTimeNs: Long, maxSensorTimeNs: Long, minIso: Int, maxIso: Int,
        maxTimeNs: Long = 50_000_000L,
    ): Pair<Long, Int> {
        val factor = Math.pow(2.0, ev.toDouble())
        val total = baseTimeNs.toDouble() * baseIso * factor
        val tCap = min(maxSensorTimeNs, max(baseTimeNs, maxTimeNs))
        var t = (baseTimeNs * factor).roundToLong().coerceIn(minTimeNs, tCap)
        var iso = (total / t).roundToInt()
        if (iso > maxIso) {
            iso = maxIso
            t = (total / iso).roundToLong().coerceIn(minTimeNs, maxSensorTimeNs)
        }
        if (iso < minIso) {
            iso = minIso
            t = (total / iso).roundToLong().coerceIn(minTimeNs, maxSensorTimeNs)
        }
        return t to iso
    }

    /** True if a frame's reported exposure is (within tolerance) the requested one. */
    fun exposureMatches(timeNs: Long?, iso: Int?, wantTimeNs: Long, wantIso: Int, tol: Double = 0.12): Boolean {
        if (timeNs == null || iso == null) return false
        val a = timeNs.toDouble() * iso
        val b = wantTimeNs.toDouble() * wantIso
        return kotlin.math.abs(a - b) <= tol * b
    }

    /**
     * Converts YUV_420_888 planes into an NV21 byte array (Y plane then interleaved V/U) of [w] x [h],
     * starting at ([cropLeft], [cropTop]) of the planes (the ImageProxy crop rect; must be even).
     * Handles any row stride / pixel stride, including the real-device layouts where the last row
     * of a plane is shorter than its row stride and U/V are views into one interleaved buffer.
     */
    fun toNv21(
        w: Int, h: Int,
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
        out: ByteArray = ByteArray(w * h * 3 / 2),
        cropLeft: Int = 0, cropTop: Int = 0,
    ): ByteArray {
        require(w > 0 && h > 0) { "bad size ${w}x$h" }
        require(out.size >= w * h * 3 / 2)
        require(cropLeft % 2 == 0 && cropTop % 2 == 0) { "crop offset must be even" }
        val ys = y.duplicate().also { it.clear() }
        val us = u.duplicate().also { it.clear() }
        val vs = v.duplicate().also { it.clear() }
        var pos = 0
        val yNeed = (w - 1) * yPixelStride + 1
        val rowBuf = ByteArray(yNeed)
        for (row in 0 until h) {
            val start = (row + cropTop) * yRowStride + cropLeft * yPixelStride
            require(start + yNeed <= ys.limit()) { "Y plane too small: row $row" }
            ys.position(start)
            if (yPixelStride == 1) {
                ys.get(out, pos, w)
                pos += w
            } else {
                ys.get(rowBuf, 0, yNeed)
                for (col in 0 until w) out[pos++] = rowBuf[col * yPixelStride]
            }
        }
        val cw = w / 2
        val ch = h / 2
        val cNeed = (cw - 1) * uvPixelStride + 1
        val uRow = ByteArray(cNeed)
        val vRow = ByteArray(cNeed)
        for (row in 0 until ch) {
            val start = (row + cropTop / 2) * uvRowStride + (cropLeft / 2) * uvPixelStride
            require(start + cNeed <= us.limit() && start + cNeed <= vs.limit()) { "UV plane too small: row $row" }
            us.position(start); us.get(uRow, 0, cNeed)
            vs.position(start); vs.get(vRow, 0, cNeed)
            var idx = 0
            for (col in 0 until cw) {
                out[pos++] = vRow[idx]
                out[pos++] = uRow[idx]
                idx += uvPixelStride
            }
        }
        return out
    }

    /** Approximate heap bytes a burst needs while it is captured (NV21) and queued (JPEG). */
    fun burstBytes(frames: Int, w: Int, h: Int): Long = frames.toLong() * w * h * 3 / 2
}
