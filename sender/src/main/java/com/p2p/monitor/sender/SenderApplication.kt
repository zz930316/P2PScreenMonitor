package com.p2p.monitor.sender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.p2p.monitor.util.Logger

class SenderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "capture_service",
                "投屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于保持投屏服务在前台运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
