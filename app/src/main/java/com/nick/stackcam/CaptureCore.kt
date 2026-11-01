package com.nick.stackcam

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure (Android-free) helpers for the fast burst capture, unit tested on the JVM. */
object CaptureCore {

    enum class Mode { NORMAL, NIGHT, HDR, SUPERRES }

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
     * Converts YUV_420_888 planes into an NV21 byte array (Y plane then interleaved V/U).
     * Handles any row stride / pixel stride, reading whole rows in bulk for speed.
     */
    fun toNv21(
        w: Int, h: Int,
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
        out: ByteArray = ByteArray(w * h * 3 / 2),
    ): ByteArray {
        require(out.size >= w * h * 3 / 2)
        y.rewind(); u.rewind(); v.rewind()
        var pos = 0
        if (yPixelStride == 1) {
            for (row in 0 until h) {
                y.position(row * yRowStride)
                y.get(out, pos, w)
                pos += w
            }
        } else {
            val rowBuf = ByteArray(yRowStride)
            for (row in 0 until h) {
                val start = row * yRowStride
                y.position(start)
                val n = min(yRowStride, y.limit() - start)
                y.get(rowBuf, 0, n)
                for (col in 0 until w) out[pos++] = rowBuf[col * yPixelStride]
            }
        }
        val cw = w / 2
        val ch = h / 2
        val uRow = ByteArray(uvRowStride)
        val vRow = ByteArray(uvRowStride)
        for (row in 0 until ch) {
            val start = row * uvRowStride
            val un = min(uvRowStride, u.limit() - start)
            val vn = min(uvRowStride, v.limit() - start)
            u.position(start); u.get(uRow, 0, un)
            v.position(start); v.get(vRow, 0, vn)
            var idx = 0
            for (col in 0 until cw) {
                out[pos++] = vRow[idx]
                out[pos++] = uRow[idx]
                idx += uvPixelStride
            }
        }
        y.rewind(); u.rewind(); v.rewind()
        return out
    }

    /** Approximate heap bytes a burst needs while it is captured (NV21) and queued (JPEG). */
    fun burstBytes(frames: Int, w: Int, h: Int): Long = frames.toLong() * w * h * 3 / 2
}
