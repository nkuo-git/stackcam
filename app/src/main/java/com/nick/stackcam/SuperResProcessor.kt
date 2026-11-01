package com.nick.stackcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build

/**
 * Super-resolution zoom: crops the centre 1/scale of every frame at native resolution,
 * registers the crops with sub-pixel accuracy and reconstructs a scale-x larger image.
 */
class SuperResProcessor(private val scale: Int, private val onProgress: (Int, String) -> Unit) {

    private fun decodeCrop(jpeg: ByteArray, rect: Rect): IntArray {
        val dec: BitmapRegionDecoder = (if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        else legacyDecoder(jpeg)) ?: throw IllegalStateException("無法解碼影格")
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = dec.decodeRegion(rect, opts) ?: throw IllegalStateException("無法解碼影格")
            val px = IntArray(rect.width() * rect.height())
            bmp.getPixels(px, 0, rect.width(), 0, 0, rect.width(), rect.height())
            bmp.recycle()
            return px
        } finally {
            dec.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDecoder(jpeg: ByteArray): BitmapRegionDecoder? =
        BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)

    /** [frames] entries are released (set to empty) once decoded to save memory. */
    fun process(frames: MutableList<ByteArray>, rotationDegrees: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size, bounds)
        val fw = bounds.outWidth; val fh = bounds.outHeight
        val cw = fw / scale; val ch = fh / scale
        val rect = Rect((fw - cw) / 2, (fh - ch) / 2, (fw - cw) / 2 + cw, (fh - ch) / 2 + ch)

        val n = frames.size
        val crops = ArrayList<IntArray>(n)
        val sharp = ArrayList<Double>(n)
        for (i in 0 until n) {
            onProgress(3 + 20 * i / n, "裁切影格 ${i + 1}/$n")
            val px = decodeCrop(frames[i], rect)
            frames[i] = ByteArray(0)
            crops += px
            sharp += StackCore.sharpness(Gray.fromArgb(px, cw, ch))
        }
        // Drop motion-blurred frames; the sharpest one is the reference.
        val best = sharp.max()
        val keep = crops.indices.filter { sharp[it] >= 0.55 * best }.sortedByDescending { sharp[it] }
        val used = keep.map { crops[it] }.toMutableList()
        crops.clear()

        val refGray = Gray.fromArgb(used[0], cw, ch)
        val refPyr = SuperRes.refPyramid(refGray, 5)
        val shifts = ArrayList<Pair<Float, Float>>()
        shifts += 0f to 0f
        for (k in 1 until used.size) {
            onProgress(25 + 30 * k / used.size, "次像素對齊 $k/${used.size - 1}")
            shifts += SuperRes.register(refPyr, Gray.fromArgb(used[k], cw, ch))
        }

        onProgress(55, "超解析重建（${used.size} 張）…")
        var frs: List<IntArray> = used
        var shs: List<Pair<Float, Float>> = shifts
        val hr = try {
            SuperRes.reconstruct(frs, cw, ch, shs, 0, scale) { p -> onProgress(55 + p * 30 / 100, "超解析重建中…") }
        } catch (oom: OutOfMemoryError) {
            // Retry with half the frames.
            frs = used.filterIndexed { i, _ -> i % 2 == 0 }
            shs = shifts.filterIndexed { i, _ -> i % 2 == 0 }
            used.clear()
            System.gc()
            onProgress(55, "記憶體不足，改用 ${frs.size} 張重建…")
            SuperRes.reconstruct(frs, cw, ch, shs, 0, scale)
        }
        val W = cw * scale; val H = ch * scale
        onProgress(88, "銳化")
        Sharpen.unsharpGauss(hr, W, H, 0.5f * scale, 0.8f)
        onProgress(95, "輸出影像")
        return BitmapUtil.toUpright(hr, W, H, rotationDegrees)
    }
}
