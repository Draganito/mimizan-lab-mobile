package ch.bojovic.mimizanlab.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import ch.bojovic.mimizanlab.MainActivity
import ch.bojovic.mimizanlab.MimizanApp
import ch.bojovic.mimizanlab.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that drains the [Darkroom] queue so a development
 * (seconds of CPU, hundreds of MB) survives the app going to the
 * background between shots.
 */
class DevelopService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(notification(getString(R.string.developing), null))
        if (worker?.isActive != true) {
            worker = scope.launch { drain() }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        val darkroom = Darkroom.get(this)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mimizan:develop").apply { acquire(10 * 60_000L) }
        try {
            var done = 0
            while (darkroom.runNext { name ->
                    notify(getString(R.string.developing), name)
                }
            ) {
                done++
            }
            notify(getString(R.string.developed), null)
        } finally {
            wakeLock?.takeIf { it.isHeld }?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notify(title: String, text: String?) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun notification(title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, MimizanApp.CHANNEL_DEVELOP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, DevelopService::class.java)
            context.startForegroundService(intent)
        }
    }

    private fun goForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, n, type)
    }
}
