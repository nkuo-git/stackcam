package com.nick.stackcam

import kotlin.math.max

/** Pure (Android-free) image sanity checks, unit tested on the JVM. */
object ImageChecks {

    /** Mean luma (0..255) of ARGB pixels, sampling every [step]-th pixel. */
    fun meanLuma(argb: IntArray, step: Int = 1): Double {
        if (argb.isEmpty()) return 0.0
        var s = 0L
        var n = 0L
        var i = 0
        val st = max(1, step)
        while (i < argb.size) {
            val c = argb[i]
            s += ((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29
            n++
            i += st
        }
        return s.toDouble() / 256.0 / n
    }

    /** Mean of the Y plane of an NV21 buffer of [w] x [h]. */
    fun meanLumaNv21(nv21: ByteArray, w: Int, h: Int, step: Int = 1): Double {
        val n = w * h
        if (n <= 0) return 0.0
        var s = 0L
        var k = 0L
        var i = 0
        val st = max(1, step)
        while (i < n) { s += nv21[i].toInt() and 0xFF; k++; i += st }
        return s.toDouble() / k
    }

    /**
     * True when a merged result is (nearly) black although the reference frame it came from is not:
     * then something in the pipeline broke and the reference frame should be saved instead.
     */
    fun mergedLooksBroken(mergedMean: Double, refMean: Double): Boolean =
        refMean >= 10.0 && mergedMean < max(3.0, refMean * 0.15)

    /** Largest power-of-two inSampleSize that keeps the longer side at or above [maxSide]. */
    fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
        val longest = max(width, height)
        if (longest <= 0 || maxSide <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
    }
}
