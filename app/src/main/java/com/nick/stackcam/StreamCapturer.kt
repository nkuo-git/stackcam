package com.nick.stackcam

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fast burst capture straight from the camera's full-resolution YUV stream (ImageAnalysis).
 * Frames arrive at the sensor's frame rate (~30 fps), so a whole burst takes a fraction of a
 * second instead of one still capture after another. Per-frame capture results (exposure time,
 * ISO, ...) are matched by sensor timestamp, which lets HDR brackets use exactly-exposed frames.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class StreamCapturer {

    data class Meta(
        val timeNs: Long?, val iso: Int?, val evIndex: Int?, val aeState: Int?,
        val focal: Float?, val aperture: Float?, val boost: Int? = null,
    )

    class RawFrame(val nv21: ByteArray, val width: Int, val height: Int, val rotation: Int, val timestamp: Long)

    private val metas = ConcurrentHashMap<Long, Meta>()
    @Volatile var latestMeta: Meta? = null
        private set
    @Volatile private var sink: Channel<RawFrame>? = null
    /** Rejects frames whose (already known) capture result is unwanted, before the costly copy. */
    private var blackSkipped = 0
    @Volatile private var preFilter: ((Meta) -> Boolean)? = null

    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            val m = Meta(
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(CaptureResult.SENSOR_SENSITIVITY),
                result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
                result.get(CaptureResult.CONTROL_AE_STATE),
                result.get(CaptureResult.LENS_FOCAL_LENGTH),
                result.get(CaptureResult.LENS_APERTURE),
                result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
            )
            metas[ts] = m
            latestMeta = m
            if (metas.size > 90) {
                val cutoff = ts - 3_000_000_000L
                metas.keys.removeIf { it < cutoff }
            }
        }
    }

    val analyzer = ImageAnalysis.Analyzer { image -> onFrame(image) }

    private fun onFrame(image: ImageProxy) {
        val ch = sink
        try {
            if (ch == null) return
            val f = preFilter
            val known = metas[image.imageInfo.timestamp]
            if (f != null && known != null && !f(known)) return
            val p = image.planes
            // Only the crop rect is valid image content; keep offsets/sizes even for 4:2:0 chroma.
            val crop = image.cropRect
            val left = (crop.left.coerceIn(0, image.width - 2)) and 1.inv()
            val top = (crop.top.coerceIn(0, image.height - 2)) and 1.inv()
            val cw = (minOf(crop.width(), image.width - left)) and 1.inv()
            val chh = (minOf(crop.height(), image.height - top)) and 1.inv()
            val fw = if (cw >= 2) cw else image.width and 1.inv()
            val fh = if (chh >= 2) chh else image.height and 1.inv()
            val nv21 = CaptureCore.toNv21(
                fw, fh,
                p[0].buffer, p[0].rowStride, p[0].pixelStride,
                p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                cropLeft = if (cw >= 2) left else 0, cropTop = if (chh >= 2) top else 0,
            )
            // A pitch-black frame while the stream is otherwise live means the camera wasn't
            // delivering real content yet (e.g. right after a reconfigure): skip it.
            if (ImageChecks.meanLumaNv21(nv21, fw, fh, 997) < 0.5 && blackSkipped < 15) { blackSkipped++; return }
            blackSkipped = 0
            ch.trySend(RawFrame(nv21, fw, fh, image.imageInfo.rotationDegrees, image.imageInfo.timestamp))
        } catch (oom: OutOfMemoryError) {
            ch?.close(IllegalStateException("記憶體不足"))
        } catch (e: Exception) {
            ch?.close(e)
        } finally {
            image.close()
        }
    }

    private suspend fun metaFor(ts: Long): Meta? {
        repeat(20) {
            metas[ts]?.let { return it }
            delay(10)
        }
        return metas[ts]
    }

    /**
     * Collects up to [count] frames (taking every [stride]-th accepted frame) from the stream.
     * Returns early with what it has after [timeoutMs].
     */
    suspend fun burst(
        count: Int, stride: Int = 1, timeoutMs: Long = 4000,
        accept: ((Meta?) -> Boolean)? = null,
        onFrame: (Int) -> Unit = {},
    ): List<RawFrame> {
        val ch = Channel<RawFrame>(Channel.UNLIMITED)
        val out = ArrayList<RawFrame>(count)
        var seen = 0
        preFilter = accept?.let { a -> { m: Meta -> a(m) } }
        sink = ch
        try {
            withTimeoutOrNull(timeoutMs) {
                for (f in ch) {
                    if (accept != null && !accept(metaFor(f.timestamp))) continue
                    if (seen++ % stride != 0) continue
                    out += f
                    onFrame(out.size)
                    if (out.size >= count) break
                }
            }
        } finally {
            sink = null
            preFilter = null
            ch.close()
            while (ch.tryReceive().isSuccess) { /* drop leftovers */ }
        }
        return out
    }

    // ---- manual exposure for HDR brackets ----

    class SensorRanges(val minT: Long, val maxT: Long, val minIso: Int, val maxIso: Int)

    fun manualRanges(camera: Camera): SensorRanges? = try {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val manual = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val t = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (manual && t != null && iso != null) SensorRanges(t.lower, t.upper, iso.lower, iso.upper) else null
    } catch (_: Exception) { null }

    suspend fun setManualExposure(camera: Camera, timeNs: Long, iso: Int, boost: Int?) {
        val b = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, timeNs)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(timeNs, 33_333_333L))
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        // With AE off the ISP's extra digital gain is manual too: keep what auto exposure used.
        if (boost != null) b.setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost)
        apply(camera, b.build())
    }

    suspend fun clearManual(camera: Camera) {
        try {
            withContext(Dispatchers.IO) {
                Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions().get(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) { }
    }

    private suspend fun apply(camera: Camera, opts: CaptureRequestOptions) {
        try {
            withContext(Dispatchers.IO) {
                Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(opts).get(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) { }
    }

    /** EXIF tags describing the capture settings of [m]. */
    fun exifOf(m: Meta?): Map<String, String> {
        if (m == null) return emptyMap()
        val out = HashMap<String, String>()
        m.timeNs?.let { out[ExifInterface.TAG_EXPOSURE_TIME] = "${(it / 1000).coerceAtLeast(1)}/1000000" }
        m.iso?.let { out[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY] = it.toString() }
        m.aperture?.let { out[ExifInterface.TAG_F_NUMBER] = "${(it * 100).toInt()}/100" }
        m.focal?.let { out[ExifInterface.TAG_FOCAL_LENGTH] = "${(it * 1000).toInt()}/1000" }
        return out
    }
}
