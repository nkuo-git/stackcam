package com.nick.stackcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlin.math.roundToInt

/** Shared Android bitmap helpers. */
object BitmapUtil {
    fun decode(jpeg: ByteArray, sample: Int): Bitmap {
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            ?: throw IllegalStateException("無法解碼影格")
    }

    fun pixels(jpeg: ByteArray, sample: Int): Triple<IntArray, Int, Int> {
        val bmp = decode(jpeg, sample)
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Triple(px, w, h)
    }

    fun gray(jpeg: ByteArray, sample: Int): Gray {
        val (px, w, h) = pixels(jpeg, sample)
        return Gray.fromArgb(px, w, h)
    }

    /** Builds an upright bitmap from ARGB pixels. */
    fun toUpright(px: IntArray, w: Int, h: Int, rotationDegrees: Int): Bitmap {
        val result = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (rotationDegrees % 360 == 0) return result
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(result, 0, 0, w, h, m, true)
        if (rotated !== result) result.recycle()
        return rotated
    }
}

/**
 * HDR (exposure-bracketed) processing.
 * frames[i] was shot at exposure group groups[i] (e.g. -2, 0, +2 EV).
 * Same-exposure frames are first stacked (denoise, ghost rejection), then the exposures
 * are fused with [HdrFusion].
 */
class HdrProcessor(private val onProgress: (Int, String) -> Unit) {

    fun process(frames: List<ByteArray>, groups: List<Int>, rotationDegrees: Int): Bitmap {
        return try {
            run(frames, groups, rotationDegrees, 1)
        } catch (oom: OutOfMemoryError) {
            System.gc()
            onProgress(5, "記憶體不足，改用較低解析度重新處理…")
            run(frames, groups, rotationDegrees, 2)
        }
    }

    private fun run(frames: List<ByteArray>, groups: List<Int>, rotationDegrees: Int, base: Int): Bitmap {
        val n = frames.size
        // 1) Quick look at every frame: sharpness and brightness (1/8 scale).
        val sharp = DoubleArray(n)
        val bright = DoubleArray(n)
        for (i in 0 until n) {
            onProgress(3 + 10 * i / n, "分析影格 ${i + 1}/$n")
            val g = BitmapUtil.gray(frames[i], 8)
            sharp[i] = StackCore.sharpness(g)
            bright[i] = g.px.average()
        }
        // Order the exposure groups by measured brightness (robust even if EV didn't apply).
        val groupIds = groups.distinct().sortedBy { gid -> groups.indices.filter { groups[it] == gid }.map { bright[it] }.average() }
        val members = groupIds.map { gid -> groups.indices.filter { groups[it] == gid }.sortedByDescending { sharp[it] } }
        val refGroup = groupIds.size / 2
        val refFrame = members[refGroup][0]

        // 2) Align all frames to the reference (equalised gray handles exposure differences).
        val alignSample = 2 * base
        val refPyr = StackCore.pyramid(HdrFusion.equalize(BitmapUtil.gray(frames[refFrame], alignSample)), 5)
        val shift = Array(n) { 0 to 0 }
        var k = 0
        for (i in 0 until n) {
            if (i == refFrame) continue
            k++
            onProgress(13 + 25 * k / n, "對齊影格 $k/${n - 1}")
            val p = StackCore.pyramid(HdrFusion.equalize(BitmapUtil.gray(frames[i], alignSample)), 5)
            val (sx, sy) = StackCore.estimateShift(refPyr, p)
            shift[i] = (sx * 2f).roundToInt() to (sy * 2f).roundToInt()
        }

        // 3) Stack each exposure group in reference coordinates.
        var w = 0; var h = 0
        val groupImgs = ArrayList<IntArray>()
        for ((gi, mem) in members.withIndex()) {
            onProgress(40 + 30 * gi / members.size, "合併曝光組 ${gi + 1}/${members.size}")
            val baseIdx = mem[0]
            val (px0, bw, bh) = BitmapUtil.pixels(frames[baseIdx], base)
            if (w == 0) { w = bw; h = bh }
            check(bw == w && bh == h) { "影格大小不一致" }
            val (bdx, bdy) = shift[baseIdx]
            val refPx = HdrFusion.shifted(px0, w, h, bdx, bdy)
            if (mem.size > 1) {
                val merger = Merger(w, h, refPx, 12, 40)
                for (j in 1 until mem.size) {
                    val bmp = BitmapUtil.decode(frames[mem[j]], base)
                    if (bmp.width == w && bmp.height == h) {
                        val (dx, dy) = shift[mem[j]]
                        merger.addFrame(dx, dy, h, w) { row, buf -> bmp.getPixels(buf, 0, w, 0, row, w, 1) }
                    }
                    bmp.recycle()
                }
                groupImgs += merger.finish()
            } else {
                groupImgs += refPx
            }
        }

        // 4) Exposure fusion (pyramid stage at ~1000 px wide, detail at full resolution).
        val factor = when {
            w >= 3000 -> 4
            w >= 1500 -> 2
            else -> 1
        }
        val out = groupImgs[refGroup]
        HdrFusion.fuse(groupImgs, w, h, refGroup, out, factor = factor) { p ->
            onProgress(70 + p * 20 / 100, "HDR 融合中…")
        }
        val result = out
        groupImgs.clear()
        onProgress(92, "銳化")
        Sharpen.unsharp(result, w, h, 0.35f)
        onProgress(95, "輸出影像")
        return BitmapUtil.toUpright(result, w, h, rotationDegrees)
    }
}
