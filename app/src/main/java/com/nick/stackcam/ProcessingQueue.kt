package com.nick.stackcam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * One captured burst waiting to be merged. [frames] are JPEGs, or NV21 buffers of
 * [nv21Width] x [nv21Height] when those are > 0 (fast stream capture).
 */
class CaptureJob(
    val mode: CaptureCore.Mode,
    val zoom: Int,
    val frames: MutableList<ByteArray>,
    val groups: List<Int>,
    val rotation: Int,
    val exif: Map<String, String>,
    val nv21Width: Int = 0,
    val nv21Height: Int = 0,
) {
    val isNv21 get() = nv21Width > 0
    val rawBytesAtSubmit: Long = if (nv21Width > 0) frames.sumOf { it.size.toLong() } else 0L
}

/**
 * App-wide processing queue. Lives outside the activity, so merging continues while the user
 * browses the gallery (and [ProcessingService] keeps the process in the foreground meanwhile).
 * Jobs are merged strictly one at a time to keep memory safe.
 */
object ProcessingQueue {

    data class State(
        val pending: Int = 0,            // jobs submitted but not finished (incl. the one running)
        val progress: Int = 0,           // progress of the running job, 0..100
        val message: String = "",
        val lastSaved: Uri? = null,
        val savedSerial: Int = 0,        // increments every time a photo is saved
        val lastResult: String = "",
        val lastError: String? = null,
        val errorSerial: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val encodeLock = Mutex()
    private val processLock = Mutex()
    private val rawInFlight = AtomicLong(0)

    private const val MB = 1024L * 1024L
    /** Extra heap a running merge may still allocate on top of what it holds now. */
    private const val PROCESS_HEADROOM = 160 * MB
    private const val MAX_PENDING = 3

    /** Whether a new burst needing [newBytes] of raw frames fits in memory right now. */
    fun canAccept(newBytes: Long): Boolean {
        val s = _state.value
        if (s.pending >= MAX_PENDING) return false
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val free = rt.maxMemory() - used
        val headroom = if (s.pending > 0) PROCESS_HEADROOM else 48 * MB
        return free - newBytes > headroom
    }

    fun submit(context: Context, job: CaptureJob) {
        val app = context.applicationContext
        rawInFlight.addAndGet(job.rawBytesAtSubmit)
        _state.update { it.copy(pending = it.pending + 1) }
        ProcessingService.start(app)
        scope.launch {
            try {
                if (job.isNv21) encodeLock.withLock { encode(job) }
                processLock.withLock { process(app, job) }
            } catch (e: OutOfMemoryError) {
                fail("記憶體不足，這張沒有存成功。請等處理完再拍，或改用一般模式")
            } catch (e: Throwable) {
                fail("處理失敗: ${e.message}")
            } finally {
                job.frames.clear()
                _state.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0), progress = 0, message = "") }
            }
        }
    }

    private fun fail(msg: String) {
        _state.update { it.copy(lastError = msg, errorSerial = it.errorSerial + 1) }
    }

    /** NV21 -> JPEG, frame by frame, freeing each raw buffer as soon as it is encoded. */
    private fun encode(job: CaptureJob) {
        val w = job.nv21Width; val h = job.nv21Height
        try {
            for (i in job.frames.indices) {
                _state.update { it.copy(message = "準備影格 ${i + 1}/${job.frames.size}") }
                val raw = job.frames[i]
                val bos = ByteArrayOutputStream(w * h / 3)
                YuvImage(raw, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 95, bos)
                job.frames[i] = bos.toByteArray()
            }
        } finally {
            // Whatever happens, this job no longer counts as raw memory waiting.
            rawInFlight.addAndGet(-job.rawBytesAtSubmit)
        }
    }

    private fun process(app: Context, job: CaptureJob) {
        val n = job.frames.size
        val report: (Int, String) -> Unit = { p, msg -> _state.update { it.copy(progress = p, message = msg) } }
        // Keep the first frame (metered exposure, also in HDR) as a safety net; SR empties the list.
        val refJpeg = job.frames.firstOrNull()
        var bitmap = when (job.mode) {
            CaptureCore.Mode.HDR -> HdrProcessor(report).process(job.frames, job.groups, job.rotation)
            CaptureCore.Mode.SUPERRES -> SuperResProcessor(job.zoom, report).process(job.frames, job.rotation)
            else -> StackProcessor(job.mode == CaptureCore.Mode.NIGHT, report).process(job.frames, job.rotation)
        }
        job.frames.clear()
        var fellBack = false
        if (refJpeg != null && refJpeg.isNotEmpty()) {
            val mergedMean = BitmapUtil.meanLuma(bitmap)
            if (mergedMean < 12.0) {
                // Only decode the reference when the result is suspiciously dark.
                val refMean = try { BitmapUtil.meanLuma(BitmapUtil.decode(refJpeg, 8), recycle = true) } catch (_: Throwable) { 0.0 }
                if (ImageChecks.mergedLooksBroken(mergedMean, refMean)) {
                    report(96, "合成結果異常，改存原始影格…")
                    val (px, w, h) = BitmapUtil.pixels(refJpeg, 1)
                    bitmap.recycle()
                    bitmap = BitmapUtil.toUpright(px, w, h, job.rotation)
                    fellBack = true
                }
            }
        }
        report(97, "儲存中…")
        val tag = when (job.mode) {
            CaptureCore.Mode.NORMAL -> "Stack"
            CaptureCore.Mode.NIGHT -> "Night"
            CaptureCore.Mode.HDR -> "HDR"
            CaptureCore.Mode.SUPERRES -> "SR${job.zoom}x"
        }
        val uri = try { PhotoStore.save(app, bitmap, tag, job.exif) } finally { bitmap.recycle() }
        val what = when (job.mode) {
            CaptureCore.Mode.HDR -> "HDR，$n 張"
            CaptureCore.Mode.SUPERRES -> "超解析 ${job.zoom}x，$n 張"
            CaptureCore.Mode.NIGHT -> "夜景，$n 張疊合"
            else -> "$n 張疊合"
        }
        val result = if (fellBack) "已儲存（合成異常，改存單張原始影格）" else "已儲存（$what）"
        _state.update { it.copy(lastSaved = uri, savedSerial = it.savedSerial + 1, lastResult = result) }
    }
}
