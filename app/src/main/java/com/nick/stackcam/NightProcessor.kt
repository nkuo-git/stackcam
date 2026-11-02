package com.nick.stackcam

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/**
 * Night variants:
 *  A: NV21 frames, hierarchical tile alignment, noise-weighted tile merge, edge-preserving denoise.
 *  B: RAW_SENSOR frames merged in the Bayer domain, then our own RAW development.
 *  C: A + local night tone mapping (shadow lift, highlight shoulder, chroma-noise-aware).
 *  D: B + C's tone mapping.
 */
class NightProcessor(
    private val variant: CaptureCore.NightVariant,
    private val onProgress: (Int, String) -> Unit,
) {
    private val style = if (variant.tone) NightPost.STYLE_C else NightPost.STYLE_A

    fun process(job: CaptureJob): Bitmap {
        if (job.raw != null) {
            return try {
                processRaw(job)
            } catch (t: Throwable) {
                if (t !is Exception && t !is OutOfMemoryError) throw t
                job.raw = null
                val ref = job.refJpeg ?: throw t
                onProgress(90, "RAW 處理失敗，改存單張影格")
                job.note = "RAW 處理失敗（${t.message ?: t.javaClass.simpleName}），改存單張影格"
                val (px, w, h) = BitmapUtil.pixels(ref, 1)
                BitmapUtil.toUpright(px, w, h, job.rotation)
            }
        }
        return processYuv(job)
    }

    // ------------------------------------------------------------------ A / C

    private fun processYuv(job: CaptureJob): Bitmap {
        var w = job.nv21Width; var h = job.nv21Height
        if (!job.isNv21) {
            // Legacy still captures arrive as JPEG: convert to NV21 so all variants share one merge.
            job.refJpeg = job.frames.firstOrNull()
            for (i in job.frames.indices) {
                onProgress(1 + 4 * i / job.frames.size, "準備影格 ${i + 1}/${job.frames.size}")
                val (px, pw, ph) = BitmapUtil.pixels(job.frames[i], 1)
                val ew = pw and 1.inv(); val eh = ph and 1.inv()
                val cropped = if (ew == pw && eh == ph) px else IntArray(ew * eh).also { o ->
                    for (y in 0 until eh) System.arraycopy(px, y * pw, o, y * ew, ew)
                }
                if (i == 0) { w = ew; h = eh }
                job.frames[i] = if (ew == w && eh == h) NightPost.argbToNv21(cropped, ew, eh) else ByteArray(0)
            }
            job.frames.removeAll { it.isEmpty() }
        } else {
            job.refJpeg = nv21ToJpeg(job.frames[0], w, h)
        }
        val planes = mergeYuv(job, w, h)
        val px = NightPost.process(planes, w, h, style, onProgress)
        onProgress(95, "輸出影像")
        return BitmapUtil.toUpright(px, w, h, job.rotation)
    }

    private fun mergeYuv(job: CaptureJob, w: Int, h: Int): Array<FloatArray> {
        val frames = job.frames.map { MergeFrame.nv21(it, w, h) }
        val r = BurstMerge.run(frames, 255f, mergeTile = 32, alignTile = 64, onProgress = onProgress)
        job.frames.clear()
        return r.planes
    }

    // ------------------------------------------------------------------ B / D

    private fun processRaw(job: CaptureJob): Bitmap {
        val burst = job.raw!!
        val info = burst.info
        val qw = info.w / 2; val qh = info.h / 2
        val planes = mergeRaw(burst)
        job.raw = null
        onProgress(60, "RAW 顯影：黑電位、暗角、白平衡")
        RawCore.normalize(planes, qw, qh, info)
        RawCore.hotPixels(planes, qw, qh)
        val mean = RawCore.meanLuminance(planes, qw, qh, info)
        val target = if (variant.tone) 0.05f else 0.08f
        val g0 = burst.extraGain
        val gain = g0 * (target / (mean * g0 + 1e-5f)).coerceIn(0.8f, 2.5f)
        onProgress(64, "RAW 顯影：去馬賽克、色彩")
        val yuv = RawCore.develop(planes, qw, qh, info, gain)
        for (i in planes.indices) planes[i] = FloatArray(0)
        val px = NightPost.process(yuv, info.w, info.h, style, onProgress)
        onProgress(95, "輸出影像")
        return BitmapUtil.toUpright(px, info.w, info.h, job.rotation)
    }

    private fun mergeRaw(burst: RawBurstCapturer.Burst): Array<FloatArray> {
        val info = burst.info
        val frames = burst.frames.map { MergeFrame.bayer(it, info.w, info.h) }
        val r = BurstMerge.run(
            frames, info.white, noise = RawCore.guideNoise(info),
            mergeTile = 16, alignTile = 32, onProgress = onProgress,
        )
        burst.frames.clear()
        return r.planes
    }

    companion object {
        fun nv21ToJpeg(nv21: ByteArray, w: Int, h: Int): ByteArray {
            val bos = ByteArrayOutputStream(w * h / 3)
            YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 92, bos)
            return bos.toByteArray()
        }
    }
}
