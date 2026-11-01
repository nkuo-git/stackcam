package com.nick.stackcam

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * UI flow for the one-tap updater. Must be constructed during the activity's onCreate
 * (it registers an ActivityResult launcher).
 */
class UpdateUi(private val activity: AppCompatActivity, private val button: TextView) {

    private val updater = Updater(activity)
    private val prefs = activity.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private var checking = false
    private var downloadJob: Job? = null
    private var latest: UpdateCore.ReleaseInfo? = null

    private val unknownSourcesLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onReturnedFromSettings(fromCallback = true)
        }

    fun init() {
        showIdle()
        button.setOnClickListener { manualCheck() }
        cleanupOldDownloads()
        // Silent check on launch: only shows a hint on the button, never a dialog.
        activity.lifecycleScope.launch {
            val r = updater.check()
            if (r is Updater.CheckResult.UpdateAvailable) {
                latest = r.release
                showBadge(r.release)
            }
        }
    }

    /** Call from Activity.onResume: continues an install if the app was restarted while in Settings. */
    fun onResume() {
        if (prefs.getBoolean(KEY_WAITING_PERMISSION, false)) onReturnedFromSettings(fromCallback = false)
    }

    private fun showIdle() {
        button.text = "v${updater.currentVersion} · 檢查更新"
        button.setTextColor(Color.WHITE)
    }

    private fun showBadge(rel: UpdateCore.ReleaseInfo) {
        button.text = "● 有新版 ${rel.tag}"
        button.setTextColor(Color.parseColor("#FFC107"))
    }

    private fun manualCheck() {
        if (checking || downloadJob?.isActive == true) return
        checking = true
        button.text = "檢查中…"
        activity.lifecycleScope.launch {
            val r = updater.check()
            checking = false
            when (r) {
                is Updater.CheckResult.UpdateAvailable -> { latest = r.release; showBadge(r.release); showUpdateDialog(r.release) }
                Updater.CheckResult.UpToDate -> { showIdle(); toast("已是最新版本（v${updater.currentVersion}）") }
                Updater.CheckResult.NoRelease -> { showIdle(); toast("目前沒有可用的更新") }
                is Updater.CheckResult.Failed -> {
                    latest?.let { showBadge(it) } ?: showIdle()
                    AlertDialog.Builder(activity).setTitle("無法檢查更新").setMessage(r.message)
                        .setPositiveButton("好", null).show()
                }
            }
        }
    }

    private fun showUpdateDialog(rel: UpdateCore.ReleaseInfo) {
        val notes = rel.changelog.ifBlank { "（沒有更新說明）" }
        val size = if (rel.apkSize > 0) "\n檔案大小：%.1f MB".format(rel.apkSize / 1048576.0) else ""
        AlertDialog.Builder(activity)
            .setTitle("有新版本 ${rel.tag}")
            .setMessage("目前版本：v${updater.currentVersion}\n新版本：${rel.tag}$size\n\n更新內容：\n$notes")
            .setPositiveButton("立即更新") { _, _ -> startDownload(rel) }
            .setNegativeButton("稍後", null)
            .show()
    }

    private fun startDownload(rel: UpdateCore.ReleaseInfo) {
        val dp = activity.resources.displayMetrics.density
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = true
        }
        val label = TextView(activity).apply { text = "準備下載…"; setPadding(0, (8 * dp).toInt(), 0, 0) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * dp).toInt(); setPadding(p, (16 * dp).toInt(), p, 0)
            addView(bar); addView(label)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("下載 ${rel.tag} 中")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> downloadJob?.cancel() }
            .show()
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        downloadJob = activity.lifecycleScope.launch {
            try {
                val file = updater.download(rel) { done, total ->
                    activity.runOnUiThread {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.progress = (done * 1000 / total).toInt()
                            label.text = "%.1f / %.1f MB（%d%%）".format(done / 1048576.0, total / 1048576.0, done * 100 / total)
                        } else {
                            label.text = "%.1f MB".format(done / 1048576.0)
                        }
                    }
                }
                dialog.dismiss()
                prefs.edit().putString(KEY_PENDING_APK, file.absolutePath).apply()
                installOrAskPermission(file)
            } catch (e: CancellationException) {
                dialog.dismiss()
                toast("已取消下載")
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(activity).setTitle("下載失敗")
                    .setMessage(e.message ?: "下載失敗，請稍後再試")
                    .setPositiveButton("重試") { _, _ -> startDownload(rel) }
                    .setNegativeButton("取消", null).show()
            } finally {
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun canInstall(): Boolean = activity.packageManager.canRequestPackageInstalls()

    private fun installOrAskPermission(file: File) {
        if (canInstall()) { launchInstaller(file); return }
        AlertDialog.Builder(activity)
            .setTitle("需要允許安裝更新")
            .setMessage(
                "新版已下載完成。\n\nAndroid 需要你允許 StackCam「安裝不明應用程式」才能更新自己。" +
                "\n\n按「前往設定」後，請打開「允許來自這個來源的應用程式」，再按返回鍵回到 StackCam，就會自動繼續安裝。" +
                "\n\n（這個權限只用來安裝 StackCam 自己的更新）"
            )
            .setPositiveButton("前往設定") { _, _ ->
                prefs.edit().putBoolean(KEY_WAITING_PERMISSION, true).apply()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                try {
                    unknownSourcesLauncher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    prefs.edit().putBoolean(KEY_WAITING_PERMISSION, false).apply()
                    toast("無法開啟設定，請到「設定 > 應用程式 > 特殊應用程式存取權 > 安裝不明應用程式」允許 StackCam")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onReturnedFromSettings(fromCallback: Boolean) {
        if (!prefs.getBoolean(KEY_WAITING_PERMISSION, false)) return
        val path = prefs.getString(KEY_PENDING_APK, null)
        val file = path?.let { File(it) }?.takeIf { it.exists() }
        if (canInstall()) {
            prefs.edit().putBoolean(KEY_WAITING_PERMISSION, false).apply()
            if (file != null) launchInstaller(file) else toast("已允許安裝，請再按一次「檢查更新」")
        } else if (fromCallback) {
            prefs.edit().putBoolean(KEY_WAITING_PERMISSION, false).apply()
            AlertDialog.Builder(activity).setTitle("尚未允許安裝")
                .setMessage("沒有開啟「允許來自這個來源的應用程式」，所以無法安裝更新。")
                .setPositiveButton("再試一次") { _, _ -> file?.let { installOrAskPermission(it) } }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("找不到系統安裝程式，無法安裝更新")
        }
    }

    /** Once the new version is running, the downloaded APK is no longer needed. */
    private fun cleanupOldDownloads() {
        if (prefs.getBoolean(KEY_WAITING_PERMISSION, false)) return
        val path = prefs.getString(KEY_PENDING_APK, null) ?: return
        val f = File(path)
        val v = Regex("""StackCam-(.+)\.apk""").find(f.name)?.groupValues?.get(1)
        if (v == null || UpdateCore.compareVersions(v, updater.currentVersion) <= 0) {
            File(activity.cacheDir, "updates").listFiles()?.forEach { it.delete() }
            prefs.edit().remove(KEY_PENDING_APK).apply()
        }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()


    companion object {
        private const val KEY_PENDING_APK = "pending_apk"
        private const val KEY_WAITING_PERMISSION = "waiting_permission"
    }
}
