package com.nick.stackcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var shutter: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var zoomGroup: RadioGroup
    private lateinit var modeHint: TextView
    private lateinit var zoomBadge: TextView
    private lateinit var holdHint: TextView
    private lateinit var thumb: ImageView
    private lateinit var queueBox: View
    private lateinit var queueText: TextView
    private lateinit var updateUi: UpdateUi

    private val stream = StreamCapturer()
    private var imageCapture: ImageCapture? = null   // only in the fallback (legacy) capture path
    private var camera: Camera? = null
    private var streamFrameBytes = 0L               // bytes of one NV21 stream frame
    private var useLegacy = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var capturing = false
    private var hintJob: Job? = null
    private var latestPhoto: Uri? = null
    private var seenSaved = -1
    private var seenError = -1

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res[Manifest.permission.CAMERA] == true) startCamera()
            else {
                status.text = "需要相機權限才能拍照"
                Toast.makeText(this, "請允許相機權限", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        // TextureView-based preview so the view can be scaled for the super-res zoom preview.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        shutter = findViewById(R.id.shutter)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        modeGroup = findViewById(R.id.modeGroup)
        zoomGroup = findViewById(R.id.zoomGroup)
        modeHint = findViewById(R.id.modeHint)
        zoomBadge = findViewById(R.id.zoomBadge)
        holdHint = findViewById(R.id.holdHint)
        thumb = findViewById(R.id.thumb)
        queueBox = findViewById(R.id.queueBox)
        queueText = findViewById(R.id.queueText)
        updateUi = UpdateUi(this, findViewById(R.id.updateButton))
        updateUi.init()

        shutter.setOnClickListener { onShutter() }
        thumb.setOnClickListener { GalleryActivity.open(this, latestPhoto) }
        modeGroup.setOnCheckedChangeListener { _, _ -> updateModeUi(); refreshShutter() }
        zoomGroup.setOnCheckedChangeListener { _, _ -> updateModeUi(); refreshShutter() }
        updateModeUi()

        observeQueue()

        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------------------------------------------------------------- camera setup

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            try {
                bind(provider)
            } catch (e: Exception) {
                if (!useLegacy) {
                    useLegacy = true
                    try { bind(provider) } catch (e2: Exception) { status.text = "相機啟動失敗: ${e2.message}" }
                } else status.text = "相機啟動失敗: ${e.message}"
            }
            refreshShutter()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind(provider: ProcessCameraProvider) {
        val selector43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val fullRes = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()
        val rotation = previewView.display?.rotation ?: 0
        val preview = Preview.Builder().setResolutionSelector(selector43).build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        imageCapture = null
        camera = null

        if (!useLegacy) {
            // Fast path: full-resolution YUV stream, bursts at the sensor's frame rate.
            val builder = ImageAnalysis.Builder()
                .setResolutionSelector(fullRes)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(rotation)
            Camera2Interop.Extender(builder).setSessionCaptureCallback(stream.captureCallback)
            val analysis = builder.build()
            analysis.setAnalyzer(cameraExecutor, stream.analyzer)
            val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            val res = analysis.resolutionInfo?.resolution
            val px = (res?.width ?: 0).toLong() * (res?.height ?: 0)
            val maxJpeg = maxJpegPixels(cam)
            if (res != null && px >= 3_000_000 && (maxJpeg == 0L || px >= maxJpeg * 6 / 10)) {
                camera = cam
                streamFrameBytes = px * 3 / 2
                status.text = "按下快門後拿穩約 1 秒"
                return
            }
            // The device can't stream (near) full resolution next to the preview: use still captures.
            provider.unbindAll()
            useLegacy = true
        }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(fullRes)
            .setJpegQuality(95)
            .setTargetRotation(rotation)
            .build()
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        imageCapture = capture
        streamFrameBytes = 0
        status.text = "拍照時請盡量拿穩"
    }

    private fun maxJpegPixels(cam: Camera): Long = try {
        Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
    } catch (_: Exception) { 0L }

    // ---------------------------------------------------------------- UI state

    private fun currentMode(): CaptureCore.Mode = when (modeGroup.checkedRadioButtonId) {
        R.id.modeNight -> CaptureCore.Mode.NIGHT
        R.id.modeHdr -> CaptureCore.Mode.HDR
        R.id.modeSr -> CaptureCore.Mode.SUPERRES
        else -> CaptureCore.Mode.NORMAL
    }

    private fun zoomFactor(): Int = if (zoomGroup.checkedRadioButtonId == R.id.zoom3) 3 else 2

    private fun updateModeUi() {
        val mode = currentMode()
        val n = CaptureCore.burstPlan(mode, zoomFactor()).frames
        modeHint.text = when (mode) {
            CaptureCore.Mode.NORMAL -> "快速連拍 $n 張疊合降噪，適合一般場景"
            CaptureCore.Mode.NIGHT -> "連拍 $n 張，暗處降噪；請靠著東西拿穩"
            CaptureCore.Mode.HDR -> "白天強對比（逆光、窗邊、藍天＋陰影）\n拍 0 / -2 / +2 EV 各 2 張，亮部暗部都清楚"
            CaptureCore.Mode.SUPERRES -> "拍遠景：選倍率後連拍 $n 張，自然手震提供多張細節\n合成比直接放大更清楚"
        }
        val sr = mode == CaptureCore.Mode.SUPERRES
        zoomGroup.visibility = if (sr) View.VISIBLE else View.GONE
        // Preview-only digital zoom: the camera keeps shooting the full sensor so we can crop
        // native pixels ourselves.
        val z = if (sr) zoomFactor().toFloat() else 1f
        previewView.scaleX = z
        previewView.scaleY = z
        zoomBadge.visibility = if (sr) View.VISIBLE else View.GONE
        zoomBadge.text = "超解析 ${zoomFactor()}x"
    }

    private fun burstBytes(): Long {
        val plan = CaptureCore.burstPlan(currentMode(), zoomFactor())
        val perFrame = if (streamFrameBytes > 0) streamFrameBytes else 6L * 1024 * 1024
        return perFrame * plan.frames
    }

    /** Shutter is usable when the camera is ready, no burst is running and memory allows another. */
    private fun refreshShutter() {
        val ready = camera != null && !capturing
        val fits = ProcessingQueue.canAccept(burstBytes())
        val enabled = ready && fits
        shutter.isEnabled = enabled
        shutter.alpha = if (enabled) 1f else 0.4f
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = !capturing
        for (i in 0 until zoomGroup.childCount) zoomGroup.getChildAt(i).isEnabled = !capturing
        if (ready && !fits && ProcessingQueue.state.value.pending > 0) status.text = "上一張還在處理，請稍候再拍…"
        val keepOn = capturing || ProcessingQueue.state.value.pending > 0
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun observeQueue() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ProcessingQueue.state.collect { s ->
                        queueBox.visibility = if (s.pending > 0) View.VISIBLE else View.INVISIBLE
                        queueText.text = "處理中 ${s.pending}"
                        if (!capturing) {
                            if (s.pending > 0) {
                                progress.visibility = View.VISIBLE
                                progress.progress = s.progress
                                status.text = if (s.message.isNotEmpty()) "背景合成中：${s.message}" else "背景合成中…"
                            } else {
                                progress.visibility = View.INVISIBLE
                            }
                        }
                        if (seenSaved >= 0 && s.savedSerial != seenSaved) {
                            loadThumb()
                            if (!capturing && s.pending == 0) status.text = "完成！${s.lastResult}，點左下角縮圖查看"
                            Toast.makeText(this@MainActivity, s.lastResult, Toast.LENGTH_SHORT).show()
                        }
                        seenSaved = s.savedSerial
                        if (seenError >= 0 && s.errorSerial != seenError && s.lastError != null) {
                            status.text = s.lastError
                            Toast.makeText(this@MainActivity, s.lastError, Toast.LENGTH_LONG).show()
                        }
                        seenError = s.errorSerial
                        refreshShutter()
                    }
                }
                // Memory frees up gradually while a job runs: re-check the shutter now and then.
                launch {
                    while (isActive) {
                        delay(700)
                        if (ProcessingQueue.state.value.pending > 0) refreshShutter()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        loadThumb()
    }

    private fun loadThumb() {
        lifecycleScope.launch {
            val size = (64 * resources.displayMetrics.density).roundToInt()
            val (uri, bmp) = withContext(Dispatchers.IO) {
                val p = PhotoStore.latest(this@MainActivity)
                p?.uri to p?.let { PhotoStore.thumbnail(this@MainActivity, it.uri, size) }
            }
            latestPhoto = uri
            if (bmp != null) thumb.setImageBitmap(bmp) else thumb.setImageDrawable(null)
        }
    }

    private fun showHold(text: String) {
        hintJob?.cancel()
        holdHint.text = text
        holdHint.visibility = View.VISIBLE
    }

    private fun showMoveNow() {
        holdHint.text = "✓ 可以放下手機了"
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(1500)
            holdHint.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- capture

    private fun onShutter() {
        if (capturing || camera == null) return
        if (!ProcessingQueue.canAccept(burstBytes())) {
            Toast.makeText(this, "上一張還在處理，請稍候再拍", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = currentMode()
        val zoom = zoomFactor()
        capturing = true
        refreshShutter()
        progress.visibility = View.VISIBLE
        progress.progress = 0
        showHold("📷 請拿穩，不要動")
        status.text = "拍攝中，請拿穩…"

        lifecycleScope.launch {
            var ok = false
            try {
                val t0 = System.currentTimeMillis()
                val job = if (useLegacy) captureLegacy(mode, zoom) else captureStream(mode, zoom)
                val held = System.currentTimeMillis() - t0
                showMoveNow()
                ok = true
                val n = job.frames.size
                ProcessingQueue.submit(this@MainActivity, job)
                status.text = "拍好了（$n 張，${"%.1f".format(held / 1000f)} 秒），背景合成中…"
            } catch (e: OutOfMemoryError) {
                status.text = "記憶體不足，請等上一張處理完再拍"
                Toast.makeText(this@MainActivity, "記憶體不足", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = "拍攝失敗: ${e.message}"
                Toast.makeText(this@MainActivity, "拍攝失敗: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (!ok) holdHint.visibility = View.GONE
                capturing = false
                refreshShutter()
            }
        }
    }

    private fun onFrameCaptured(i: Int, n: Int) = runOnUiThread {
        status.text = "拍攝中 $i/$n，請拿穩…"
        progress.progress = 100 * i / n
    }

    /** Fast path: grabs the burst from the running full-resolution stream. */
    private suspend fun captureStream(mode: CaptureCore.Mode, zoom: Int): CaptureJob {
        val cam = camera ?: throw IllegalStateException("相機尚未就緒")
        val frames = ArrayList<StreamCapturer.RawFrame>()
        val groups = ArrayList<Int>()
        val exif = stream.exifOf(stream.latestMeta)

        if (mode == CaptureCore.Mode.HDR) {
            val total = CaptureCore.HDR_PLAN.size
            val base = stream.latestMeta
            val ranges = stream.manualRanges(cam)
            var usedEvComp = false
            try {
                for (ev in CaptureCore.HDR_PLAN.distinct()) {
                    val need = CaptureCore.HDR_PLAN.count { it == ev }
                    var accept: ((StreamCapturer.Meta?) -> Boolean)? = null
                    if (ev != 0) {
                        val baseT = base?.timeNs
                        val baseIso = base?.iso
                        if (ranges != null && baseT != null && baseIso != null) {
                            val (t, iso) = CaptureCore.bracketExposure(
                                baseT, baseIso, ev, ranges.minT, ranges.maxT, ranges.minIso, ranges.maxIso,
                            )
                            stream.setManualExposure(cam, t, iso, base.boost)
                            accept = { m -> CaptureCore.exposureMatches(m?.timeNs, m?.iso, t, iso) }
                        } else {
                            val idx = evIndex(cam, ev)
                            usedEvComp = true
                            withContext(Dispatchers.IO) {
                                try { cam.cameraControl.setExposureCompensationIndex(idx).get(2, TimeUnit.SECONDS) } catch (_: Exception) { }
                            }
                            accept = { m ->
                                m?.evIndex == idx && (m.aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                    m.aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                    m.aeState == CaptureResult.CONTROL_AE_STATE_LOCKED)
                            }
                        }
                    }
                    val already = frames.size
                    val got = ArrayList(stream.burst(need, timeoutMs = 1500, accept = accept) { onFrameCaptured(already + it, total) })
                    if (got.size < need) {
                        // Exposure never reported as settled: take what the camera gives now.
                        val have = already + got.size
                        got += stream.burst(need - got.size, timeoutMs = 1500) { onFrameCaptured(have + it, total) }
                    }
                    frames += got
                    repeat(got.size) { groups += ev }
                }
            } finally {
                stream.clearManual(cam)
                if (usedEvComp) withContext(Dispatchers.IO) {
                    try { cam.cameraControl.setExposureCompensationIndex(0).get(2, TimeUnit.SECONDS) } catch (_: Exception) { }
                }
            }
        } else {
            val plan = CaptureCore.burstPlan(mode, zoom)
            frames += stream.burst(plan.frames, plan.stride, timeoutMs = 5000) { onFrameCaptured(it, plan.frames) }
        }

        if (frames.isEmpty()) {
            // The stream delivered nothing: switch to still captures for the next shot.
            useLegacy = true
            startCamera()
            throw IllegalStateException("相機沒有輸出影格，已切換到相容模式，請再拍一次")
        }
        val f0 = frames[0]
        return CaptureJob(
            mode, zoom, frames.map { it.nv21 }.toMutableList(), groups, f0.rotation, exif,
            nv21Width = f0.width, nv21Height = f0.height,
        )
    }

    private fun evIndex(cam: Camera, ev: Int): Int {
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return 0
        val step = st.exposureCompensationStep.toFloat()
        if (step <= 0f) return 0
        return (ev / step).roundToInt().coerceIn(st.exposureCompensationRange.lower, st.exposureCompensationRange.upper)
    }

    /** Fallback path for devices that can't stream full resolution: one still capture at a time. */
    private suspend fun captureLegacy(mode: CaptureCore.Mode, zoom: Int): CaptureJob {
        val capture = imageCapture ?: throw IllegalStateException("相機尚未就緒")
        val cam = camera ?: throw IllegalStateException("相機尚未就緒")
        val frames = ArrayList<ByteArray>()
        val groups = ArrayList<Int>()
        var rotation = 0
        if (mode == CaptureCore.Mode.HDR) {
            val plan = CaptureCore.HDR_PLAN
            try {
                var lastEv = 0
                for ((i, ev) in plan.withIndex()) {
                    if (ev != lastEv) {
                        val idx = evIndex(cam, ev)
                        withContext(Dispatchers.IO) {
                            try { cam.cameraControl.setExposureCompensationIndex(idx).get(2, TimeUnit.SECONDS) } catch (_: Exception) { }
                        }
                        delay(200) // let frames with the new exposure reach the pipeline
                        lastEv = ev
                    }
                    onFrameCaptured(i + 1, plan.size)
                    val (bytes, rot) = takeOne(capture)
                    frames += bytes; groups += ev; rotation = rot
                }
            } finally {
                withContext(Dispatchers.IO) {
                    try { cam.cameraControl.setExposureCompensationIndex(0).get(2, TimeUnit.SECONDS) } catch (_: Exception) { }
                }
            }
        } else {
            val n = CaptureCore.burstPlan(mode, zoom).frames
            for (i in 0 until n) {
                onFrameCaptured(i + 1, n)
                val (bytes, rot) = takeOne(capture)
                frames += bytes; rotation = rot
            }
        }
        val exif = PhotoStore.exifFromJpeg(frames[0])
        return CaptureJob(mode, zoom, frames, groups, rotation, exif)
    }

    private suspend fun takeOne(capture: ImageCapture): Pair<ByteArray, Int> =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes[0].buffer
                        buf.rewind()
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val rot = image.imageInfo.rotationDegrees
                        image.close()
                        cont.resume(bytes to rot)
                    } catch (e: Exception) {
                        image.close()
                        cont.resumeWithException(e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    override fun onResume() {
        super.onResume()
        updateUi.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
