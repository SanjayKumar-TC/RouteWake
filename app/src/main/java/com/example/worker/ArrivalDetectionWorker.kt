package com.example.worker

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.RouteWakeDatabase
import com.example.data.local.TripHistoryEntity
import com.example.data.local.UserPreferences
import com.example.engine.AlarmAudioEngine
import com.example.engine.VibrationEngine
import com.example.network.RoutingService
import com.example.service.ArrivalNotificationHelper
import com.example.service.TrackingForegroundService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Battery-efficient WorkManager worker for background arrival detection.
 *
 * Instead of keeping high-frequency GPS active continuously in the background,
 * this worker samples location on Doze-aware adaptive intervals (e.g. 5-15 min when far,
 * scaling down to 30-60s on approach), conserving significant battery during long commutes.
 */
class ArrivalDetectionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userPrefs = UserPreferences(applicationContext)
        val activeTrip = userPrefs.getActiveTrip()

        if (activeTrip == null) {
            Log.d(TAG, "No active trip found in preferences. Cancelling arrival detection work.")
            ArrivalWorkScheduler.cancelArrivalDetection(applicationContext)
            return@withContext Result.success()
        }

        val settings = userPrefs.getSettings()

        Log.d(
            TAG,
            "Performing arrival check for '${activeTrip.destinationName}' (radius: ${activeTrip.radiusMeters}m, mode: ${activeTrip.transportMode})"
        )

        val location = getSingleLocation(applicationContext)

        if (location == null) {
            Log.w(TAG, "Unable to obtain location fix for arrival check. Scheduling fast retry.")
            ArrivalWorkScheduler.scheduleNextCheck(applicationContext, 45L)
            return@withContext Result.success()
        }

        val distanceMeters = RoutingService.computeHaversineDistanceMeters(
            location.latitude,
            location.longitude,
            activeTrip.destinationLat,
            activeTrip.destinationLng
        )

        Log.d(
            TAG,
            "Current distance to '${activeTrip.destinationName}': ${distanceMeters.toInt()}m (alarm radius: ${activeTrip.radiusMeters}m)"
        )

        if (distanceMeters <= activeTrip.radiusMeters) {
            // ARRIVAL DETECTED!
            Log.i(TAG, "ARRIVAL REACHED via WorkManager background service!")
            handleArrival(activeTrip.destinationName, activeTrip.destinationAddress, activeTrip.destinationLat, activeTrip.destinationLng, activeTrip.startTimeMs, distanceMeters, settings.alarmTone.name, settings.vibrationEnabled)
            ArrivalWorkScheduler.cancelArrivalDetection(applicationContext)
            Result.success()
        } else {
            // Not yet arrived: compute next battery-efficient check interval
            val nextInterval = ArrivalWorkScheduler.calculateAdaptiveIntervalSeconds(
                distanceMeters,
                activeTrip.radiusMeters,
                activeTrip.transportMode
            )

            Log.d(TAG, "Scheduling next arrival check in ${nextInterval}s")
            ArrivalWorkScheduler.scheduleNextCheck(applicationContext, nextInterval)

            // Update persistent background notification with battery efficiency status
            updateStatusNotification(activeTrip.destinationName, distanceMeters, nextInterval, location)

            Result.success()
        }
    }

    private suspend fun handleArrival(
        destName: String,
        destAddress: String,
        destLat: Double,
        destLng: Double,
        startTimeMs: Long,
        distanceMeters: Double,
        alarmToneName: String,
        vibrationEnabled: Boolean
    ) {
        val context = applicationContext
        val userPrefs = UserPreferences(context)
        val settings = userPrefs.getSettings()

        // 1. Trigger Alarm Sound & Vibration
        try {
            val audioEngine = AlarmAudioEngine()
            audioEngine.startAlarm(settings.alarmTone)

            if (vibrationEnabled) {
                val vibrationEngine = VibrationEngine(context)
                vibrationEngine.startArrivalVibration()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start arrival alarm/vibration: ${e.message}")
        }

        // 2. Show Urgent Full-Screen Arrival Notification
        ArrivalNotificationHelper.showArrivalNotification(context, destName, distanceMeters)

        // 3. Inform Foreground Service if running
        try {
            val arrivalIntent = Intent(context, TrackingForegroundService::class.java).apply {
                action = TrackingForegroundService.ACTION_ARRIVAL_FROM_WORKER
                putExtra(TrackingForegroundService.EXTRA_DEST_NAME, destName)
                putExtra(TrackingForegroundService.EXTRA_DEST_ADDRESS, destAddress)
                putExtra(TrackingForegroundService.EXTRA_DEST_LAT, destLat)
                putExtra(TrackingForegroundService.EXTRA_DEST_LNG, destLng)
            }
            context.startService(arrivalIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not notify TrackingForegroundService: ${e.message}")
        }

        // 4. Save Trip Completion to Room Database
        try {
            val durationSec = ((System.currentTimeMillis() - startTimeMs) / 1000).coerceAtLeast(1)
            val historyEntity = TripHistoryEntity(
                destinationName = destName,
                destinationAddress = destAddress,
                destinationLat = destLat,
                destinationLng = destLng,
                startTimeMs = startTimeMs,
                endTimeMs = System.currentTimeMillis(),
                distanceMeters = distanceMeters,
                durationSeconds = durationSec,
                avgSpeedKmh = 0.0,
                transportMode = settings.defaultTransport.name,
                radiusMeters = settings.defaultRadiusMeters,
                completed = true
            )
            RouteWakeDatabase.getDatabase(context).historyDao().insertTrip(historyEntity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save completed trip to database: ${e.message}")
        }
    }

    private fun updateStatusNotification(
        destName: String,
        distanceMeters: Double,
        nextIntervalSeconds: Long,
        location: Location
    ) {
        try {
            val distFormatted = ArrivalNotificationHelper.formatDistance(distanceMeters)
            val nextTimeText = if (nextIntervalSeconds >= 60) {
                "${nextIntervalSeconds / 60}m"
            } else {
                "${nextIntervalSeconds}s"
            }

            val statusText = "$distFormatted remaining • Eco Background"
            val subText = "Battery Saver active • Check in $nextTimeText"

            val notification = ArrivalNotificationHelper.buildTrackingNotification(
                context = applicationContext,
                destName = destName,
                statusText = statusText,
                subText = subText,
                currentLoc = location
            )

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ArrivalNotificationHelper.NOTIFICATION_ID_TRACKING, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating status notification: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getSingleLocation(context: Context): Location? {
        return withTimeoutOrNull(12000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                    val cts = CancellationTokenSource()
                    cont.invokeOnCancellation { cts.cancel() }

                    fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                        .addOnSuccessListener { loc ->
                            if (loc != null) {
                                if (cont.isActive) cont.resume(loc)
                            } else {
                                // Fallback to last known location
                                fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                                    if (cont.isActive) cont.resume(lastLoc ?: getFallbackLocation(context))
                                }.addOnFailureListener {
                                    if (cont.isActive) cont.resume(getFallbackLocation(context))
                                }
                            }
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(getFallbackLocation(context))
                        }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(getFallbackLocation(context))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getFallbackLocation(context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val passLoc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            } else null

            listOfNotNull(gpsLoc, netLoc, passLoc).maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ArrivalDetectionWorker"
    }
}
