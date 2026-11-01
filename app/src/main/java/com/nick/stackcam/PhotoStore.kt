package com.nick.stackcam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saving, listing and loading the photos StackCam wrote to Pictures/StackCam. */
object PhotoStore {

    data class Photo(val uri: Uri, val name: String, val dateTaken: Long)

    private val COPY_TAGS = listOf(
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
    )

    /** Reads capture settings (exposure, ISO, ...) out of a camera JPEG. */
    fun exifFromJpeg(jpeg: ByteArray): Map<String, String> = try {
        val src = ExifInterface(ByteArrayInputStream(jpeg))
        COPY_TAGS.mapNotNull { t -> src.getAttribute(t)?.let { t to it } }.toMap()
    } catch (_: Exception) { emptyMap() }

    private fun writeExif(exif: ExifInterface, tags: Map<String, String>, width: Int, height: Int) {
        val now = Date()
        val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
        val tz = SimpleDateFormat("XXX", Locale.US).format(now)
        for ((k, v) in tags) exif.setAttribute(k, v)
        exif.setAttribute(ExifInterface.TAG_DATETIME, dt)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dt)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, tz)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, tz)
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "StackCam ${BuildConfig.VERSION_NAME}")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        exif.saveAttributes()
    }

    fun save(context: Context, bitmap: Bitmap, tag: String, exifTags: Map<String, String>): Uri {
        val name = "StackCam_${tag}_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StackCam")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    writeExif(ExifInterface(pfd.fileDescriptor), exifTags, bitmap.width, bitmap.height)
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
            try { writeExif(ExifInterface(file.absolutePath), exifTags, bitmap.width, bitmap.height) } catch (_: Exception) { }
            // Insert into MediaStore right away so the in-app gallery can list it.
            val values = ContentValues().apply {
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            val uri = try { context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) } catch (_: Exception) { null }
            if (uri == null) MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            uri ?: Uri.fromFile(file)
        }
    }

    /** StackCam photos, newest first. On Android 10+ apps can always read the media they created. */
    fun list(context: Context): List<Photo> {
        val out = ArrayList<Photo>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
        )
        val (sel, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf(Environment.DIRECTORY_PICTURES + "/StackCam/%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/" + Environment.DIRECTORY_PICTURES + "/StackCam/%")
        }
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        try {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, sel, args, order)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out += Photo(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        c.getString(nameCol) ?: "", c.getLong(dateCol),
                    )
                }
            }
        } catch (_: Exception) { }
        return out
    }

    fun latest(context: Context): Photo? = list(context).firstOrNull()

    fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } else load(context, uri, sizePx)
    } catch (_: Exception) { null }

    /** Decodes [uri] so its longer side is at most about [maxSide] pixels, upright. */
    fun load(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (deg == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (r !== bmp) bmp.recycle()
        return r
    }
}
