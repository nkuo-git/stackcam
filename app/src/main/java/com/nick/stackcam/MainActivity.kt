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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var shutter: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var modeGroup: RadioGroup

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
        shutter = findViewById(R.id.shutter)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        modeGroup = findViewById(R.id.modeGroup)

        shutter.setOnClickListener { onShutter() }

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
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onShutter() {
        val capture = imageCapture ?: return
        if (busy) return
        val night = findViewById<RadioButton>(R.id.modeNight).isChecked
        val count = if (night) 15 else 8
        setBusy(true)
        progress.progress = 0

        lifecycleScope.launch {
            try {
                // Lock focus / exposure / white balance at the centre so all frames match.
                val cam = camera
                if (cam != null) {
                    try {
                        val point = previewView.meteringPointFactory.createPoint(
                            previewView.width / 2f, previewView.height / 2f)
                        val action = FocusMeteringAction.Builder(point).disableAutoCancel().build()
                        withContext(Dispatchers.IO) {
                            cam.cameraControl.startFocusAndMetering(action).get(3, TimeUnit.SECONDS)
                        }
                    } catch (_: Exception) { /* focus lock is best effort */ }
                }

                val frames = ArrayList<ByteArray>(count)
                var rotation = 0
                for (i in 0 until count) {
                    status.text = "拍攝中 ${i + 1}/$count，請拿穩…"
                    progress.progress = 5 * i / count
                    val (bytes, rot) = takeOne(capture)
                    frames += bytes
                    rotation = rot
                }
                try { camera?.cameraControl?.cancelFocusAndMetering() } catch (_: Exception) {}

                val bitmap = withContext(Dispatchers.Default) {
                    StackProcessor(night) { p, msg ->
                        runOnUiThread { progress.progress = p; status.text = msg }
                    }.process(frames, rotation)
                }
                frames.clear()
                status.text = "儲存中…"
                val uri = withContext(Dispatchers.IO) { save(bitmap) }
                bitmap.recycle()
                progress.progress = 100
                status.text = "完成！已存到 Pictures/StackCam"
                Toast.makeText(this@MainActivity, "已儲存 (${count} 張疊合)", Toast.LENGTH_LONG).show()
            } catch (e: OutOfMemoryError) {
                status.text = "記憶體不足，請改用一般模式"
                Toast.makeText(this@MainActivity, "記憶體不足", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = "失敗: ${e.message}"
                Toast.makeText(this@MainActivity, "失敗: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun save(bitmap: Bitmap): Uri? {
        val name = "StackCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StackCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
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
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            Uri.fromFile(file)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
