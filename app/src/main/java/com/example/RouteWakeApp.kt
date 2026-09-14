package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.data.local.RouteWakeDatabase

class RouteWakeApp : Application() {

    lateinit var database: RouteWakeDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        initOsmDroidConfig()
        initWebViewCacheDirs()
        database = RouteWakeDatabase.getDatabase(this)
        createNotificationChannels()
    }

    private fun initOsmDroidConfig() {
        try {
            org.osmdroid.config.Configuration.getInstance().apply {
                userAgentValue = packageName
                osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
                osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
            }
        } catch (_: Throwable) {}
    }

    private fun initWebViewCacheDirs() {
        try {
            val baseCodeCache = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache")
            java.io.File(baseCodeCache, "js").mkdirs()
            java.io.File(baseCodeCache, "wasm").mkdirs()
        } catch (_: Throwable) {
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Tracking Channel (Persistent foreground notification)
            val trackingChannel = NotificationChannel(
                CHANNEL_TRACKING,
                "RouteWake Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active GPS travel tracking and remaining distance"
                setShowBadge(false)
            }

            // Arrival Channel (Urgent alarm notification with sound & vibration)
            val arrivalChannel = NotificationChannel(
                CHANNEL_ARRIVAL,
                "RouteWake Arrival",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority alert when arriving within destination alarm radius"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 800)
                setShowBadge(true)
            }

            // Updates Channel
            val updatesChannel = NotificationChannel(
                CHANNEL_UPDATES,
                "RouteWake Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Trip updates and status notices"
            }

            notificationManager.createNotificationChannels(
                listOf(trackingChannel, arrivalChannel, updatesChannel)
            )
        }
    }

    companion object {
        const val CHANNEL_TRACKING = "routewake_tracking"
        const val CHANNEL_ARRIVAL = "routewake_arrival"
        const val CHANNEL_UPDATES = "routewake_updates"

        lateinit var instance: RouteWakeApp
            private set
    }
}
