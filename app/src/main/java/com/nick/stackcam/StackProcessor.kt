package com.nick.stackcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlin.math.roundToInt

/**
 * Takes a burst of JPEG frames, picks the sharpest as reference, aligns the rest
 * (global translation, coarse-to-fine) and merges them with ghost rejection.
 */
class StackProcessor(
    private val night: Boolean,
    private val onProgress: (Int, String) -> Unit,
) {
    private fun decode(jpeg: ByteArray, sample: Int): Bitmap {
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            ?: throw IllegalStateException("Could not decode frame")
    }

    private fun grayOf(jpeg: ByteArray, sample: Int): Gray {
        val bmp = decode(jpeg, sample)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Gray.fromArgb(px, w, h)
    }

    /** Returns the final, upright bitmap. */
    fun process(frames: List<ByteArray>, rotationDegrees: Int): Bitmap {
        require(frames.isNotEmpty())
        val n = frames.size

        // 1) Pick the sharpest frame (Laplacian variance on a 1/4 scale image).
        val scores = frames.mapIndexed { i, f ->
            onProgress(5 + 15 * i / n, "評估清晰度 ${i + 1}/$n")
            StackCore.sharpness(grayOf(f, 4))
        }
        val refIdx = scores.indices.maxByOrNull { scores[it] } ?: 0

        // 2) Reference at full resolution + alignment pyramid at 1/2 scale.
        onProgress(22, "載入基準影格")
        val refBmp = decode(frames[refIdx], 1)
        val w = refBmp.width
        val h = refBmp.height
        val refPx = IntArray(w * h)
        refBmp.getPixels(refPx, 0, w, 0, 0, w, h)
        refBmp.recycle()
        val refPyr = StackCore.pyramid(grayOf(frames[refIdx], 2), 5)

        val (t1, t2) = if (night) 16 to 48 else 10 to 32
        val merger = Merger(w, h, refPx, t1, t2)

        // 3) Align + merge every other frame.
        var done = 0
        for (i in frames.indices) {
            if (i == refIdx) continue
            done++
            val base = 25 + 60 * done / n
            onProgress(base, "對齊影格 $done/${n - 1}")
            val frmPyr = StackCore.pyramid(grayOf(frames[i], 2), 5)
            val (sx, sy) = StackCore.estimateShift(refPyr, frmPyr)
            val dx = (sx * 2f).roundToInt()
            val dy = (sy * 2f).roundToInt()
            onProgress(base + 2, "合併影格 $done/${n - 1}")
            val bmp = decode(frames[i], 1)
            if (bmp.width == w && bmp.height == h) {
                merger.addFrame(dx, dy, bmp.height, bmp.width) { row, buf ->
                    bmp.getPixels(buf, 0, bmp.width, 0, row, bmp.width, 1)
                }
            }
            bmp.recycle()
        }

        onProgress(88, "銳化")
        val out = merger.finish()
        Sharpen.unsharp(out, w, h, if (night) 0.35f else 0.5f)

        onProgress(94, "輸出影像")
        val result = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        if (rotationDegrees % 360 == 0) return result
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(result, 0, 0, w, h, m, true)
        if (rotated !== result) result.recycle()
        return rotated
    }
}
