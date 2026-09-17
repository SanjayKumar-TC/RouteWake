package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.RouteWakeApp
import java.util.Locale
import kotlin.math.abs

object ArrivalNotificationHelper {

    const val NOTIFICATION_ID_TRACKING = 1001
    const val NOTIFICATION_ID_ARRIVAL = 1002

    fun buildTrackingNotification(
        context: Context,
        destName: String,
        statusText: String,
        subText: String = "GPS Tracking Active",
        currentLoc: Location? = null
    ): Notification {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_STOP_TRACKING
        }
        val stopPending = PendingIntent.getService(
            context, 10, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedText = if (currentLoc != null && currentLoc.hasSpeed() && currentLoc.speed > 0.5f) {
            String.format(Locale.US, " • %.0f km/h", currentLoc.speed * 3.6)
        } else ""

        return NotificationCompat.Builder(context, RouteWakeApp.CHANNEL_TRACKING)
            .setContentTitle("RouteWake • Tracking to $destName")
            .setContentText(statusText)
            .setSubText("$subText$speedText")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP TRACKING", stopPending)
            .build()
    }

    fun showArrivalNotification(
        context: Context,
        destName: String,
        distanceMeters: Double
    ) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_ARRIVAL", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 1, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_DISMISS_ALARM
        }
        val dismissPending = PendingIntent.getService(
            context, 2, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_SNOOZE_ALARM
        }
        val snoozePending = PendingIntent.getService(
            context, 3, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val distFormatted = formatDistance(distanceMeters)
        val arrivalNotification = NotificationCompat.Builder(context, RouteWakeApp.CHANNEL_ARRIVAL)
            .setContentTitle("YOU'RE HERE • $destName")
            .setContentText("Entered destination alarm radius ($distFormatted)")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISMISS", dismissPending)
            .addAction(android.R.drawable.ic_popup_sync, "SNOOZE 1M", snoozePending)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_ARRIVAL, arrivalNotification)
    }

    fun dismissArrivalNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID_ARRIVAL)
            notificationManager.cancel(NOTIFICATION_ID_TRACKING)
        } catch (_: Exception) {}
    }

    fun formatDistance(meters: Double): String {
        return if (meters >= 1000) {
            String.format(Locale.US, "%.1f km", meters / 1000.0)
        } else {
            "${meters.toInt()} m"
        }
    }

    fun formatCoordinates(location: Location): String {
        val latDir = if (location.latitude >= 0) "N" else "S"
        val lngDir = if (location.longitude >= 0) "E" else "W"
        return String.format(
            Locale.US,
            "%.3f°%s, %.3f°%s",
            abs(location.latitude), latDir,
            abs(location.longitude), lngDir
        )
    }
}
