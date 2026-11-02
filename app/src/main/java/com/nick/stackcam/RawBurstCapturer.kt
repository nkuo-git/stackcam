package com.nick.stackcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fast RAW_SENSOR burst with plain Camera2 (CameraX 1.3 has no RAW output). The caller must
 * release CameraX first and re-bind it afterwards. Exposure, focus and colour come from the
 * last frame of the CameraX stream, so no 3A convergence is needed: frames start right after
 * the session is configured.
 */
class RawBurstCapturer(private val context: Context) {

    class Burst(
        val frames: MutableList<ShortArray>, val info: RawInfo,
        val exposureNs: Long, val iso: Int, val extraGain: Float,
    ) {
        val bytes: Long get() = frames.size.toLong() * info.w * info.h * 2
    }

    class RawUnavailable(msg: String) : Exception(msg)

    companion object {
        const val WARMUP = 2

        fun rawSize(context: Context, cameraId: String): Size? = try {
            val ch = (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(cameraId)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            if (!raw || cfa == null || cfa > 3) null
            else ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        } catch (_: Exception) { null }
    }

    private fun fm(ch: CameraCharacteristics): FloatArray? {
        val t = ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) ?: ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) ?: return null
        return FloatArray(9) { t.getElement(it % 3, it / 3).toFloat() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(manager: CameraManager, id: String, handler: Handler): CameraDevice {
        var last: Exception? = null
        repeat(6) { attempt ->
            try {
                return suspendCancellableCoroutine { cont ->
                    manager.openCamera(id, object : CameraDevice.StateCallback() {
                        override fun onOpened(d: CameraDevice) { if (cont.isActive) cont.resume(d) else d.close() }
                        override fun onDisconnected(d: CameraDevice) { d.close(); if (cont.isActive) cont.resumeWithException(RawUnavailable("相機中斷")) }
                        override fun onError(d: CameraDevice, error: Int) { d.close(); if (cont.isActive) cont.resumeWithException(RawUnavailable("相機錯誤 $error")) }
                    }, handler)
                }
            } catch (e: Exception) {
                last = e
                delay(120L * (attempt + 1)) // CameraX may still be closing the device
            }
        }
        throw RawUnavailable("無法開啟相機（${last?.message}）")
    }

    @Suppress("DEPRECATION")
    private suspend fun session(dev: CameraDevice, reader: ImageReader, handler: Handler): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            dev.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) { cont.resumeWithException(RawUnavailable("RAW 設定失敗")) }
            }, handler)
        }

    suspend fun capture(cameraId: String, count: Int, base: StreamCapturer.Meta?, onFrame: (Int) -> Unit): Burst {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = manager.getCameraCharacteristics(cameraId)
        val size = rawSize(context, cameraId) ?: throw RawUnavailable("這支手機的相機不支援 RAW")
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val w = size.width; val h = size.height

        // exposure: same total exposure as the live (YUV) stream, ISP boost moved into ISO when possible
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val tRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val baseT = base?.timeNs ?: 33_000_000L
        val baseIso = base?.iso ?: 800
        val boost = (base?.boost ?: 100).coerceAtLeast(100)
        val wantIso = baseIso.toLong() * boost / 100
        val iso = if (isoRange != null) wantIso.toInt().coerceIn(isoRange.lower, isoRange.upper) else baseIso
        val expT = if (tRange != null) baseT.coerceIn(tRange.lower, tRange.upper) else baseT
        val extraGain = (wantIso.toFloat() / iso).coerceAtLeast(1f) * (baseT.toFloat() / expT).coerceAtLeast(1f)
        val minFd = try { ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size) ?: 0L } catch (_: Exception) { 0L }

        val camThread = HandlerThread("raw-cam").also { it.start() }
        val imgThread = HandlerThread("raw-img").also { it.start() }
        val camH = Handler(camThread.looper); val imgH = Handler(imgThread.looper)
        val reader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, 5)
        val total = count + WARMUP
        val frames = ArrayList<Pair<Long, ShortArray>>()
        val results = ConcurrentHashMap<Long, TotalCaptureResult>()
        var received = 0
        var failed = 0
        val done = CountDownLatch(1)
        var dev: CameraDevice? = null
        var ses: CameraCaptureSession? = null
        try {
            reader.setOnImageAvailableListener({ r ->
                val img = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    received++
                    if (received > WARMUP && frames.size < count) {
                        val p = img.planes[0]
                        val data = try { RawCore.unpack16(p.buffer, w, h, p.rowStride) } catch (_: OutOfMemoryError) { null }
                        if (data != null) {
                            synchronized(frames) { frames += img.timestamp to data }
                            onFrame(frames.size)
                        }
                    }
                } finally {
                    img.close()
                }
                if (frames.size >= count || received + failed >= total) done.countDown()
            }, imgH)

            dev = open(manager, cameraId, camH)
            ses = session(dev, reader, camH)
            val b = dev.createCaptureRequest(if (manual) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(reader.surface)
            if (manual) {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expT)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(expT, minFd, 33_333_333L))
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                base?.focusDistance?.let { b.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
            }
            val shadingModes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES) ?: intArrayOf()
            if (shadingModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON))
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
            if (ois.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON))
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            val req = b.build()
            ses.captureBurst(List(total) { req }, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { results[it] = res }
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    failed++
                    if (received + failed >= total) done.countDown()
                }
            }, camH)
            val timeout = 2500L + total * (maxOf(expT, 33_333_333L) / 1_000_000L)
            withTimeoutOrNull(timeout) {
                while (done.count > 0) delay(15)
            }
            delay(60) // let the last capture results arrive
        } finally {
            try { ses?.close() } catch (_: Exception) { }
            try { dev?.close() } catch (_: Exception) { }
            reader.close()
            camThread.quitSafely(); imgThread.quitSafely()
        }
        val got = synchronized(frames) { ArrayList(frames) }
        if (got.size < 3) throw RawUnavailable("RAW 影格不足（${got.size} 張）")

        val res = results[got[0].first] ?: results.values.firstOrNull()
        val info = buildInfo(ch, res, base, w, h)
        return Burst(got.map { it.second }.toMutableList(), info, expT, iso, extraGain)
    }

    private fun buildInfo(ch: CameraCharacteristics, res: CaptureResult?, base: StreamCapturer.Meta?, w: Int, h: Int): RawInfo {
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val dynBlack = if (Build.VERSION.SDK_INT >= 29) res?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) else null
        val black = if (dynBlack != null && dynBlack.size == 4) dynBlack.copyOf() else {
            val p = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            if (p != null) FloatArray(4) { p.getOffsetForIndex(it and 1, it shr 1).toFloat() } else FloatArray(4) { 64f }
        }
        val dynWhite = if (Build.VERSION.SDK_INT >= 29) res?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) else null
        val white = (dynWhite ?: ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()

        // colour: what the ISP used for the live view of this scene; else neutral + forward matrix
        var wb = base?.ccGains
        var ccm = base?.ccm?.let { RawCore.normalizeRows(it) }
        if (wb == null || ccm == null) {
            val n = base?.neutral ?: res?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { r -> FloatArray(3) { r[it].toFloat() } }
            if (wb == null && n != null && n.all { it > 0f }) wb = RawCore.gainsFromNeutral(n[0], n[1], n[2])
            if (ccm == null) ccm = fm(ch)?.let { RawCore.ccmFromForwardMatrix(it) }
        }
        if (wb == null) wb = floatArrayOf(2f, 1f, 1f, 1.6f)
        if (ccm == null) ccm = floatArrayOf(1.6f, -0.45f, -0.15f, -0.25f, 1.5f, -0.25f, -0.05f, -0.55f, 1.6f)
        // normalize gains so green = 1
        val wb0: FloatArray = wb
        val gG = (wb0[1] + wb0[2]) / 2f
        wb = FloatArray(4) { wb0[it] / gG }

        val lsm = res?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val shading = lsm?.let { m ->
            val cols = m.columnCount; val rows = m.rowCount
            if (cols < 2 || rows < 2) null
            else ShadingMap(cols, rows, Array(4) { c -> FloatArray(cols * rows) { i -> m.getGainFactor(c, i % cols, i / cols) } })
        }
        val np = res?.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { arr ->
            DoubleArray(arr.size * 2) { i -> if (i % 2 == 0) arr[i / 2].first else arr[i / 2].second }
        }
        return RawInfo(w, h, cfa, black, white, wb, ccm, shading, np)
    }
}
