package com.nick.stackcam

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen viewer for the photos StackCam saved, newest first. */
class GalleryActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var counter: TextView
    private lateinit var info: TextView
    private lateinit var empty: TextView
    private lateinit var processing: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private var photos: List<PhotoStore.Photo> = emptyList()
    private val adapter = PhotoAdapter()
    private var barsVisible = true
    private var pendingDelete: Uri? = null
    private var lastSavedSerial = -1

    private val deleteRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val uri = pendingDelete
        pendingDelete = null
        if (res.resultCode == RESULT_OK) {
            // On Android 10 the grant lets us delete now; on 11+ the system already deleted it.
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && uri != null) {
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            }
            onDeleted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        pager = findViewById(R.id.pager)
        counter = findViewById(R.id.counter)
        info = findViewById(R.id.info)
        empty = findViewById(R.id.empty)
        processing = findViewById(R.id.processing)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateBars()
        })
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.share).setOnClickListener { share() }
        findViewById<View>(R.id.delete).setOnClickListener { confirmDelete() }

        val startUri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        reload(startUri)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProcessingQueue.state.collect { s ->
                    processing.visibility = if (s.pending > 0) View.VISIBLE else View.GONE
                    processing.text = "還有 ${s.pending} 張處理中${if (s.message.isNotEmpty()) "：${s.message}" else ""}"
                    if (lastSavedSerial >= 0 && s.savedSerial != lastSavedSerial) {
                        // A new photo finished while browsing: add it but stay on the current one.
                        reload(current()?.uri)
                    }
                    lastSavedSerial = s.savedSerial
                }
            }
        }
    }

    private fun current(): PhotoStore.Photo? = photos.getOrNull(pager.currentItem)

    private fun reload(keep: Uri?) {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { PhotoStore.list(this@GalleryActivity) }
            photos = list
            adapter.notifyDataSetChanged()
            val idx = keep?.let { k -> list.indexOfFirst { it.uri == k } } ?: -1
            if (idx >= 0) pager.setCurrentItem(idx, false)
            updateBars()
        }
    }

    private fun updateBars() {
        empty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.share).isEnabled = photos.isNotEmpty()
        findViewById<View>(R.id.delete).isEnabled = photos.isNotEmpty()
        val p = current()
        counter.text = if (photos.isEmpty()) "" else "${pager.currentItem + 1} / ${photos.size}"
        info.text = p?.let {
            val date = if (it.dateTaken > 0) SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date(it.dateTaken)) else ""
            "${modeLabel(it.name)}  $date"
        } ?: ""
    }

    private fun modeLabel(name: String): String = when {
        name.contains("_Night_") -> "夜景"
        name.contains("_HDR_") -> "HDR 強對比"
        name.contains("_SR2x_") -> "超解析 2x"
        name.contains("_SR3x_") -> "超解析 3x"
        name.contains("_Stack_") -> "一般疊圖"
        else -> name
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val v = if (barsVisible) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = v
    }

    private fun share() {
        val p = current() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, p.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, "分享照片")) }
        catch (e: Exception) { Toast.makeText(this, "無法分享: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun confirmDelete() {
        val p = current() ?: return
        AlertDialog.Builder(this)
            .setTitle("刪除這張照片？")
            .setMessage("照片會從手機相簿中刪除，無法復原。")
            .setNegativeButton("取消", null)
            .setPositiveButton("刪除") { _, _ -> delete(p.uri) }
            .show()
    }

    private fun delete(uri: Uri) {
        lifecycleScope.launch {
            val needsSystem = withContext(Dispatchers.IO) {
                try {
                    contentResolver.delete(uri, null, null)
                    null
                } catch (e: SecurityException) {
                    e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@GalleryActivity, "刪除失敗: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    return@withContext Unit
                }
            }
            when (needsSystem) {
                null -> onDeleted()
                is SecurityException -> askSystemToDelete(uri, needsSystem)
            }
        }
    }

    /** Scoped storage: a photo we don't own (e.g. after a reinstall) needs the user's OK via the system. */
    private fun askSystemToDelete(uri: Uri, e: SecurityException) {
        try {
            val sender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    e.userAction.actionIntent.intentSender
                else -> throw e
            }
            pendingDelete = uri
            deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
        } catch (ex: Exception) {
            Toast.makeText(this, "刪除失敗: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onDeleted() {
        Toast.makeText(this, "已刪除", Toast.LENGTH_SHORT).show()
        val idx = pager.currentItem
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { PhotoStore.list(this@GalleryActivity) }
            photos = list
            adapter.notifyDataSetChanged()
            if (list.isNotEmpty()) pager.setCurrentItem(idx.coerceAtMost(list.size - 1), false)
            updateBars()
        }
    }

    private inner class Holder(val view: ZoomImageView) : RecyclerView.ViewHolder(view) {
        var job: Job? = null
    }

    private inner class PhotoAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = ZoomImageView(parent.context)
            v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            v.onSingleTap = { toggleBars() }
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.job?.cancel()
            holder.view.setImageBitmap(null)
            val uri = photos[position].uri
            val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 3 / 2
            holder.job = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try { PhotoStore.load(this@GalleryActivity, uri, maxSide.coerceIn(1600, 3200)) } catch (_: Throwable) { null }
                }
                if (bmp != null) holder.view.setImageBitmap(bmp)
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            holder.view.setImageBitmap(null)
        }
    }

    companion object {
        private const val EXTRA_URI = "uri"
        fun open(context: Context, uri: Uri?) {
            context.startActivity(Intent(context, GalleryActivity::class.java).apply {
                uri?.let { putExtra(EXTRA_URI, it.toString()) }
            })
        }
    }
}
