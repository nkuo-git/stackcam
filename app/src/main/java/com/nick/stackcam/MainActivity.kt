package com.nick.stackcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var shutter: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var zoomGroup: RadioGroup
    private lateinit var modeHint: TextView
    private lateinit var zoomBadge: TextView

    private enum class Mode { NORMAL, NIGHT, HDR, SUPERRES }

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var busy = false

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

        shutter.setOnClickListener { onShutter() }
        modeGroup.setOnCheckedChangeListener { _, _ -> updateModeUi() }
        zoomGroup.setOnCheckedChangeListener { _, _ -> updateModeUi() }
        updateModeUi()

        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector43 = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector43).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val captureSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(captureSelector)
                .setJpegQuality(95)
                .setTargetRotation(previewView.display?.rotation ?: 0)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
                status.text = "拍照時請盡量拿穩"
            } catch (e: Exception) {
                status.text = "相機啟動失敗: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
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

    private fun setBusy(b: Boolean) {
        busy = b
        shutter.isEnabled = !b
        shutter.alpha = if (b) 0.4f else 1f
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = !b
        for (i in 0 until zoomGroup.childCount) zoomGroup.getChildAt(i).isEnabled = !b
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun currentMode(): Mode = when (modeGroup.checkedRadioButtonId) {
        R.id.modeNight -> Mode.NIGHT
        R.id.modeHdr -> Mode.HDR
        R.id.modeSr -> Mode.SUPERRES
        else -> Mode.NORMAL
    }

    private fun zoomFactor(): Int = if (zoomGroup.checkedRadioButtonId == R.id.zoom3) 3 else 2

    private fun updateModeUi() {
        val mode = currentMode()
        modeHint.text = when (mode) {
            Mode.NORMAL -> "連拍 8 張疊合降噪，適合一般場景"
            Mode.NIGHT -> "連拍 15 張，暗處降噪；請靠著東西拿穩"
            Mode.HDR -> "白天強對比（逆光、窗邊、藍天＋陰影）\n拍 -2 / 0 / +2 EV 各 2 張，亮部暗部都清楚"
            Mode.SUPERRES -> "拍遠景：選倍率後連拍，自然手震提供多張細節\n合成比直接放大更清楚"
        }
        val sr = mode == Mode.SUPERRES
        zoomGroup.visibility = if (sr) View.VISIBLE else View.GONE
        // Preview-only digital zoom: the camera keeps shooting the full sensor so we can crop
        // native pixels ourselves.
        val z = if (sr) zoomFactor().toFloat() else 1f
        previewView.scaleX = z
        previewView.scaleY = z
        zoomBadge.visibility = if (sr) View.VISIBLE else View.GONE
        zoomBadge.text = "超解析 ${zoomFactor()}x"
    }

    private suspend fun lockFocus() {
        val cam = camera ?: return
        try {
            val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
            val action = FocusMeteringAction.Builder(point).disableAutoCancel().build()
            withContext(Dispatchers.IO) {
                cam.cameraControl.startFocusAndMetering(action).get(3, TimeUnit.SECONDS)
            }
        } catch (_: Exception) { /* focus lock is best effort */ }
    }

    /** Sets exposure compensation to roughly [ev] stops; returns the EV actually applied. */
    private suspend fun setEv(ev: Float): Float {
        val cam = camera ?: return 0f
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return 0f
        val step = st.exposureCompensationStep.toFloat()
        if (step <= 0f) return 0f
        val range = st.exposureCompensationRange
        val idx = (ev / step).roundToInt().coerceIn(range.lower, range.upper)
        try {
            withContext(Dispatchers.IO) {
                cam.cameraControl.setExposureCompensationIndex(idx).get(3, TimeUnit.SECONDS)
            }
        } catch (_: Exception) { }
        delay(350) // let a few frames with the new exposure go through the pipeline
        return idx * step
    }

    private fun onShutter() {
        val capture = imageCapture ?: return
        if (busy) return
        val mode = currentMode()
        val zoom = zoomFactor()
        setBusy(true)
        progress.progress = 0

        lifecycleScope.launch {
            var usedFrames: Int
            try {
                lockFocus()
                val frames = ArrayList<ByteArray>()
                val groups = ArrayList<Int>()
                var rotation = 0
                var exifSource: ByteArray? = null

                if (mode == Mode.HDR) {
                    val plan = listOf(0, 0, -2, -2, 2, 2)
                    val supported = camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true
                    if (!supported) Toast.makeText(this@MainActivity, "此相機不支援曝光補償，HDR 效果會有限", Toast.LENGTH_LONG).show()
                    try {
                        var lastEv = Int.MIN_VALUE
                        for ((i, ev) in plan.withIndex()) {
                            if (ev != lastEv) {
                                status.text = "設定曝光 ${if (ev > 0) "+" else ""}$ev EV…"
                                setEv(ev.toFloat())
                                lastEv = ev
                            }
                            status.text = "拍攝中 ${i + 1}/${plan.size}（${if (ev > 0) "+" else ""}$ev EV），請拿穩…"
                            progress.progress = 5 * i / plan.size
                            val (bytes, rot) = takeOne(capture)
                            frames += bytes; groups += ev; rotation = rot
                            if (i == 0) exifSource = bytes
                        }
                    } finally {
                        setEv(0f)
                    }
                } else {
                    val count = when (mode) {
                        Mode.NIGHT -> 15
                        Mode.SUPERRES -> if (zoom >= 3) 15 else 12
                        else -> 8
                    }
                    for (i in 0 until count) {
                        status.text = "拍攝中 ${i + 1}/$count，請拿穩…"
                        progress.progress = 5 * i / count
                        val (bytes, rot) = takeOne(capture)
                        frames += bytes
                        rotation = rot
                    }
                    exifSource = frames[0]
                }
                try { camera?.cameraControl?.cancelFocusAndMetering() } catch (_: Exception) {}
                usedFrames = frames.size

                val report: (Int, String) -> Unit = { p, msg -> runOnUiThread { progress.progress = p; status.text = msg } }
                val bitmap = withContext(Dispatchers.Default) {
                    when (mode) {
                        Mode.HDR -> HdrProcessor(report).process(frames, groups, rotation)
                        Mode.SUPERRES -> {
                            exifSource = exifSource?.let { extractExif(it) }
                            SuperResProcessor(zoom, report).process(frames, rotation)
                        }
                        else -> StackProcessor(mode == Mode.NIGHT, report).process(frames, rotation)
                    }
                }
                val exifBytes = exifSource
                frames.clear()
                status.text = "儲存中…"
                val tag = when (mode) {
                    Mode.NORMAL -> "Stack"
                    Mode.NIGHT -> "Night"
                    Mode.HDR -> "HDR"
                    Mode.SUPERRES -> "SR${zoom}x"
                }
                withContext(Dispatchers.IO) { save(bitmap, tag, exifBytes) }
                bitmap.recycle()
                progress.progress = 100
                status.text = "完成！已存到 Pictures/StackCam"
                val what = when (mode) {
                    Mode.HDR -> "HDR，$usedFrames 張"
                    Mode.SUPERRES -> "超解析 ${zoom}x，$usedFrames 張"
                    else -> "$usedFrames 張疊合"
                }
                Toast.makeText(this@MainActivity, "已儲存（$what）", Toast.LENGTH_LONG).show()
            } catch (e: OutOfMemoryError) {
                status.text = "記憶體不足，請關閉其他 app 後再試，或改用一般模式"
                Toast.makeText(this@MainActivity, "記憶體不足", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = "失敗: ${e.message}"
                Toast.makeText(this@MainActivity, "失敗: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    /** Keep only the first 64 KB of a JPEG (contains the EXIF APP1 segment) so the frame can be freed. */
    private fun extractExif(jpeg: ByteArray): ByteArray = jpeg.copyOf(minOf(jpeg.size, 65536))

    /** Writes date/time, device and (if available) capture settings from the reference frame. */
    private fun writeExif(exif: ExifInterface, source: ByteArray?, width: Int, height: Int) {
        val now = Date()
        val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
        val tz = SimpleDateFormat("XXX", Locale.US).format(now)
        if (source != null) {
            try {
                val src = ExifInterface(ByteArrayInputStream(source))
                for (t in listOf(
                    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
                )) src.getAttribute(t)?.let { exif.setAttribute(t, it) }
            } catch (_: Exception) { }
        }
        exif.setAttribute(ExifInterface.TAG_DATETIME, dt)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dt)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, tz)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, tz)
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "StackCam 2.0")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        exif.saveAttributes()
    }

    private fun save(bitmap: Bitmap, tag: String, exifSource: ByteArray?): Uri? {
        val name = "StackCam_${tag}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StackCam")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    writeExif(ExifInterface(pfd.fileDescriptor), exifSource, bitmap.width, bitmap.height)
                }
            } catch (_: Exception) { /* EXIF is optional */ }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "StackCam")
            dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            try { writeExif(ExifInterface(file.absolutePath), exifSource, bitmap.width, bitmap.height) } catch (_: Exception) { }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            Uri.fromFile(file)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
