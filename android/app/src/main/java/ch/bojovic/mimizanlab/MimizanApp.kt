package ch.bojovic.mimizanlab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ch.bojovic.mimizanlab.engine.initLogging

class MimizanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initLogging()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DEVELOP,
                getString(R.string.notification_channel_develop),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notification_channel_develop_desc) },
        )
    }

    companion object {
        const val CHANNEL_DEVELOP = "develop"
    }
}
