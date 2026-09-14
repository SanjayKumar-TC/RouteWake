package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.RouteWakeApp
import com.example.data.local.ActiveTripRecord
import com.example.data.local.UserPreferences
import com.example.data.model.Destination
import com.example.data.model.TransportMode
import com.example.data.model.TripState
import com.example.engine.AlarmAudioEngine
import com.example.engine.ArrivalEngine
import com.example.engine.GpsEngine
import com.example.engine.VibrationEngine
import com.example.network.RoutingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs

/**
 * Foreground Service for persistent, real-time background GPS tracking and arrival monitoring.
 *
 * Designed to stay active even when:
 * 1. The user locks the screen or device idles (via PowerManager.PARTIAL_WAKE_LOCK).
 * 2. The app is placed in the background or minimized.
 * 3. The user swipes the app away from Recents / task switcher (via onTaskRemoved override).
 * 4. The system kills and restarts the service under memory pressure (via START_STICKY + state persistence).
 */
class TrackingForegroundService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var userPreferences: UserPreferences
    private lateinit var gpsEngine: GpsEngine
    private lateinit var audioEngine: AlarmAudioEngine
    private lateinit var vibrationEngine: VibrationEngine
    lateinit var arrivalEngine: ArrivalEngine
        private set

    private var wakeLock: PowerManager.WakeLock? = null

    private val _distanceRemaining = MutableStateFlow(0.0)
    val distanceRemaining: StateFlow<Double> = _distanceRemaining.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): TrackingForegroundService = this@TrackingForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Initializing TrackingForegroundService")
        userPreferences = UserPreferences(this)
        audioEngine = AlarmAudioEngine()
        vibrationEngine = VibrationEngine(this)
        arrivalEngine = ArrivalEngine(this, audioEngine, vibrationEngine)
        gpsEngine = GpsEngine.getInstance(this)

        gpsEngine.onLocationUpdated = { location ->
            handleLocationUpdate(location)
        }

        gpsEngine.onWarmupFinished = {
            arrivalEngine.onWarmupFinished()
        }

        arrivalEngine.onArrivalTriggered = { destination, distance ->
            showArrivalNotification(destination, distance)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_TRACKING
        Log.d(TAG, "onStartCommand: action=$action, intent=${if (intent == null) "NULL (system restarted)" else "provided"}")

        if (intent == null) {
            // Service was restarted by the system (START_STICKY). Restore saved active trip if any.
            val activeRecord = userPreferences.getActiveTrip()
            if (activeRecord != null) {
                Log.d(TAG, "Restoring active background trip from persistent storage for: ${activeRecord.destinationName}")
                val restoredDest = Destination(
                    name = activeRecord.destinationName,
                    address = activeRecord.destinationAddress,
                    latitude = activeRecord.destinationLat,
                    longitude = activeRecord.destinationLng
                )
                startTrackingInternal(
                    destination = restoredDest,
                    radiusMeters = activeRecord.radiusMeters,
                    transport = activeRecord.transportMode,
                    startTimeMs = activeRecord.startTimeMs
                )
                return START_STICKY
            } else {
                Log.d(TAG, "No active trip found on sticky restart, stopping service.")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (action) {
            ACTION_START_TRACKING -> {
                val destName = intent.getStringExtra(EXTRA_DEST_NAME) ?: "Destination"
                val destAddress = intent.getStringExtra(EXTRA_DEST_ADDRESS) ?: ""
                val destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
                val destLng = intent.getDoubleExtra(EXTRA_DEST_LNG, 0.0)
                val radiusMeters = intent.getIntExtra(EXTRA_RADIUS_METERS, 500)
                val transportName = intent.getStringExtra(EXTRA_TRANSPORT_MODE) ?: TransportMode.CAR.name
                val transport = TransportMode.fromString(transportName)

                val dest = Destination(
                    name = destName,
                    address = destAddress,
                    latitude = destLat,
                    longitude = destLng
                )

                startTrackingInternal(dest, radiusMeters, transport)
            }
            ACTION_STOP_TRACKING -> {
                Log.d(TAG, "Stop tracking requested via action")
                stopTrackingInternal()
            }
            ACTION_DISMISS_ALARM -> {
                Log.d(TAG, "Dismiss alarm requested via action")
                arrivalEngine.dismiss(_distanceRemaining.value)
                stopTrackingInternal()
            }
            ACTION_SNOOZE_ALARM -> {
                Log.d(TAG, "Snooze alarm requested via action")
                val settings = userPreferences.getSettings()
                arrivalEngine.snooze(60, settings.alarmTone, settings.vibrationEnabled)
            }
        }

        return START_STICKY
    }

    /**
     * Called when the user has removed a task that comes from the service's application.
     * Overriding this ensures the foreground service continues running in the background
     * without being terminated when the app is swiped away from Recents.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: Application swiped away from Recents. Ensuring background GPS tracking stays active.")

        // Verify that the trip is currently active
        val hasActiveTrip = userPreferences.hasActiveTrip() || arrivalEngine.tripState.value != TripState.IDLE
        if (hasActiveTrip) {
            acquireWakeLock()

            // Update notification to reassure user that background GPS tracking remains active
            val dest = arrivalEngine.getActiveDestination()
            if (dest != null) {
                val curLoc = _currentLocation.value
                val dist = _distanceRemaining.value
                val locText = if (curLoc != null) formatCoordinates(curLoc) else "Active GPS"
                val statusText = "${formatDistance(dist)} remaining • $locText (Background)"

                val updatedNotification = buildTrackingNotification(dest.name, statusText, curLoc)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, updatedNotification)
            }
        }
    }

    private fun startTrackingInternal(
        destination: Destination,
        radiusMeters: Int,
        transport: TransportMode,
        startTimeMs: Long = System.currentTimeMillis()
    ) {
        Log.d(TAG, "startTrackingInternal: dest=${destination.name}, radius=${radiusMeters}m, mode=${transport.name}")

        // Persist active trip record so service can recover seamlessly if process is recreated
        userPreferences.saveActiveTrip(
            ActiveTripRecord(
                destinationName = destination.name,
                destinationAddress = destination.address,
                destinationLat = destination.latitude,
                destinationLng = destination.longitude,
                radiusMeters = radiusMeters,
                transportMode = transport,
                startTimeMs = startTimeMs
            )
        )

        // Acquire partial wake lock to keep CPU awake when screen is locked or app closed
        acquireWakeLock()

        // Start foreground notification immediately to satisfy platform requirements
        val initialNotification = buildTrackingNotification(
            destName = destination.name,
            statusText = "Initializing GPS coordinates...",
            currentLoc = null
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Start GPS tracking and initial warmup
        gpsEngine.startTracking()
        gpsEngine.startWarmup {
            arrivalEngine.onWarmupFinished()
        }

        val curLoc = gpsEngine.currentLocation.value
        val initialDist = if (curLoc != null) {
            RoutingService.computeHaversineDistanceMeters(
                curLoc.latitude, curLoc.longitude,
                destination.latitude, destination.longitude
            )
        } else 5000.0

        arrivalEngine.startTrip(destination, radiusMeters, transport, initialDist)
    }

    private fun handleLocationUpdate(location: Location) {
        _currentLocation.value = location
        val dest = arrivalEngine.getActiveDestination() ?: return

        val distance = RoutingService.computeHaversineDistanceMeters(
            location.latitude, location.longitude,
            dest.latitude, dest.longitude
        )
        _distanceRemaining.value = distance

        val settings = userPreferences.getSettings()

        // Adaptive GPS interval update based on distance
        gpsEngine.updateAdaptiveInterval(distance, settings.ecoMode)

        // Evaluate arrival condition
        arrivalEngine.evaluateDistance(distance, settings.alarmTone, settings.vibrationEnabled)

        // Update persistent notification with live GPS coordinates and remaining distance
        if (arrivalEngine.tripState.value == TripState.TRACKING || arrivalEngine.tripState.value == TripState.WARMUP) {
            val distFormatted = formatDistance(distance)
            val coordFormatted = formatCoordinates(location)
            val statusText = "$distFormatted remaining • $coordFormatted"

            val updatedNotification = buildTrackingNotification(dest.name, statusText, location)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, updatedNotification)
        }
    }

    private fun buildTrackingNotification(destName: String, statusText: String, currentLoc: Location?): Notification {
        // Tap intent: Returns user to MainActivity live map view
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop Tracking action: Allows user to cancel tracking from notification shade without opening app
        val stopIntent = Intent(this, TrackingForegroundService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }
        val stopPending = PendingIntent.getService(
            this, 10, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedText = if (currentLoc != null && currentLoc.hasSpeed() && currentLoc.speed > 0.5f) {
            String.format(Locale.US, " • %.0f km/h", currentLoc.speed * 3.6)
        } else ""

        return NotificationCompat.Builder(this, RouteWakeApp.CHANNEL_TRACKING)
            .setContentTitle("RouteWake • Tracking to $destName")
            .setContentText(statusText)
            .setSubText("GPS Tracking Active$speedText")
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

    private fun showArrivalNotification(destination: Destination, distance: Double) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOW_ARRIVAL", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, TrackingForegroundService::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val dismissPending = PendingIntent.getService(
            this, 2, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, TrackingForegroundService::class.java).apply {
            action = ACTION_SNOOZE_ALARM
        }
        val snoozePending = PendingIntent.getService(
            this, 3, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val arrivalNotification = NotificationCompat.Builder(this, RouteWakeApp.CHANNEL_ARRIVAL)
            .setContentTitle("YOU'RE HERE • ${destination.name}")
            .setContentText("Entered destination alarm radius (${formatDistance(distance)})")
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

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, arrivalNotification)
    }

    private fun stopTrackingInternal() {
        Log.d(TAG, "stopTrackingInternal: Cleaning up tracking service state")
        userPreferences.clearActiveTrip()
        gpsEngine.stopTracking()
        gpsEngine.stopSimulation()
        audioEngine.stopAlarm()
        vibrationEngine.stopVibration()

        releaseWakeLock()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun startDevSimulation(
        destLat: Double,
        destLng: Double,
        speedKmh: Double,
        multiplier: Int
    ) {
        val cur = _currentLocation.value
        val startLat = cur?.latitude ?: (destLat - 0.04)
        val startLng = cur?.longitude ?: (destLng - 0.04)

        gpsEngine.startSimulation(startLat, startLng, destLat, destLng, speedKmh, multiplier) { simLoc ->
            handleLocationUpdate(simLoc)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKELOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                Log.d(TAG, "Partial WakeLock acquired to maintain background GPS execution.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Partial WakeLock released.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}")
        }
    }

    private fun formatDistance(meters: Double): String {
        return if (meters >= 1000) {
            String.format(Locale.US, "%.1f km", meters / 1000.0)
        } else {
            "${meters.toInt()} m"
        }
    }

    private fun formatCoordinates(location: Location): String {
        val latDir = if (location.latitude >= 0) "N" else "S"
        val lngDir = if (location.longitude >= 0) "E" else "W"
        return String.format(
            Locale.US,
            "%.3f°%s, %.3f°%s",
            abs(location.latitude), latDir,
            abs(location.longitude), lngDir
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: TrackingForegroundService destroyed")
        stopTrackingInternal()
        scope.cancel()
    }

    companion object {
        private const val TAG = "TrackingForegroundSvc"
        private const val WAKELOCK_TAG = "RouteWake::BackgroundTrackingWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L // 12 hours max

        const val NOTIFICATION_ID = 1001
        const val ACTION_START_TRACKING = "com.routewake.ACTION_START"
        const val ACTION_STOP_TRACKING = "com.routewake.ACTION_STOP"
        const val ACTION_DISMISS_ALARM = "com.routewake.ACTION_DISMISS"
        const val ACTION_SNOOZE_ALARM = "com.routewake.ACTION_SNOOZE"

        const val EXTRA_DEST_NAME = "extra_dest_name"
        const val EXTRA_DEST_ADDRESS = "extra_dest_address"
        const val EXTRA_DEST_LAT = "extra_dest_lat"
        const val EXTRA_DEST_LNG = "extra_dest_lng"
        const val EXTRA_RADIUS_METERS = "extra_radius_meters"
        const val EXTRA_TRANSPORT_MODE = "extra_transport_mode"
    }
}
