package com.nick.stackcam

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/** Network side of the updater: checks GitHub Releases and downloads the APK. */
class Updater(private val context: Context) {

    sealed class CheckResult {
        data class UpdateAvailable(val release: UpdateCore.ReleaseInfo) : CheckResult()
        object UpToDate : CheckResult()
        object NoRelease : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    class UpdateException(message: String) : IOException(message)

    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val conn = open(URL(API_URL), "application/vnd.github+json")
            try {
                val code = conn.responseCode
                when {
                    code == 404 -> CheckResult.NoRelease
                    code == 429 || (code == 403 && conn.getHeaderField("X-RateLimit-Remaining") == "0") ->
                        CheckResult.Failed(rateLimitMessage(conn.getHeaderField("X-RateLimit-Reset")))
                    code != 200 -> CheckResult.Failed("檢查更新失敗（伺服器回應 $code），請稍後再試")
                    else -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val rel = UpdateCore.parseRelease(body)
                        when {
                            rel.apkUrl == null -> CheckResult.NoRelease
                            UpdateCore.isNewer(rel.tag, currentVersion) -> CheckResult.UpdateAvailable(rel)
                            else -> CheckResult.UpToDate
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            CheckResult.Failed(networkMessage(e, "檢查更新"))
        }
    }

    /** Downloads the APK to cacheDir/updates/ and verifies it is a newer StackCam build. */
    suspend fun download(release: UpdateCore.ReleaseInfo, onProgress: (downloaded: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val url = release.apkUrl ?: throw UpdateException("這個版本沒有 APK 檔案")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // clean old downloads
            val out = File(dir, "StackCam-${release.version}.apk")
            val part = File(dir, out.name + ".part")
            try {
                val conn = open(URL(url))
                try {
                    val code = conn.responseCode
                    if (code != 200) throw UpdateException("下載失敗（伺服器回應 $code）")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    var done = 0L
                    var lastReport = 0L
                    conn.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 100) { lastReport = now; onProgress(done, total) }
                            }
                        }
                    }
                    onProgress(done, total)
                    if (total > 0 && done != total) throw UpdateException("下載不完整，請重試")
                } finally {
                    conn.disconnect()
                }
                verifyApk(part)
                if (!part.renameTo(out)) throw UpdateException("無法儲存下載的檔案")
                out
            } catch (e: Exception) {
                part.delete()
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is UpdateException) throw e
                throw UpdateException(networkMessage(e, "下載"))
            }
        }

    /** Make sure the file really is a StackCam APK with a higher versionCode than what is installed. */
    private fun verifyApk(file: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw UpdateException("下載的檔案不是有效的 APK，請重試")
        if (info.packageName != context.packageName) throw UpdateException("下載的 APK 不是 StackCam（${info.packageName}）")
        @Suppress("DEPRECATION")
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val cur = currentVersionCode()
        if (newCode <= cur) throw UpdateException("下載的版本（code $newCode）沒有比目前版本（code $cur）新，請確認 GitHub 上的 APK 版本號")
    }

    private fun currentVersionCode(): Long {
        val pi = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_META_DATA)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    /** Opens a GET connection, following redirects manually (GitHub -> objects.githubusercontent.com). */
    private fun open(start: URL, accept: String? = null): HttpURLConnection {
        var url = start
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "StackCam/${BuildConfig.VERSION_NAME} (Android)")
            if (accept != null) c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect()
                url = URL(url, loc)
                if (url.protocol != "https") throw UpdateException("更新網址不安全，已停止")
            } else {
                return c
            }
        }
        throw UpdateException("重新導向次數過多")
    }

    private fun rateLimitMessage(reset: String?): String {
        val mins = reset?.toLongOrNull()?.let { ((it * 1000 - System.currentTimeMillis()) / 60000).coerceAtLeast(1) }
        return if (mins != null) "GitHub 查詢次數已達上限，請約 $mins 分鐘後再試" else "GitHub 查詢次數已達上限，請稍後再試"
    }

    private fun networkMessage(e: Exception, what: String): String = when (e) {
        is UpdateException -> e.message ?: "${what}失敗"
        is UnknownHostException, is ConnectException -> "無法連線，請確認網路後再試"
        is SocketTimeoutException -> "連線逾時，請確認網路後再試"
        is SSLException -> "安全連線失敗，請確認網路或手機時間是否正確"
        is IOException -> "${what}失敗：網路中斷或連線錯誤（${e.javaClass.simpleName}）"
        else -> "${what}失敗：${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val REPO = "nkuo-git/stackcam"
        const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    }
}
