package com.nick.stackcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app process in the foreground while [ProcessingQueue] still has photos to merge,
 * so leaving the app mid-processing doesn't lose the shot. Stops itself when the queue is empty.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collecting = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        try { startForeground(NOTIF_ID, notification(ProcessingQueue.state.value.pending)) } catch (_: Exception) { }
        if (!collecting) {
            collecting = true
            scope.launch {
                ProcessingQueue.state.collect { s ->
                    if (s.pending <= 0) {
                        stopSelfNow()
                    } else {
                        try {
                            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(s.pending))
                        } catch (_: Exception) { }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelfResult(lastStartId) // ignored if a newer start arrived meanwhile
    }

    private fun notification(pending: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "照片處理", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("StackCam 正在合成照片")
            .setContentText("還有 $pending 張處理中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "processing"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
            } catch (_: Exception) { /* best effort: processing still runs while the app is open */ }
        }
    }
}
