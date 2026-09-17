package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.model.TransportMode
import java.util.concurrent.TimeUnit

object ArrivalWorkScheduler {

    const val TAG = "ArrivalWorkScheduler"
    const val UNIQUE_WORK_NAME = "routewake_arrival_detection_work"
    const val TAG_ARRIVAL_WORK = "routewake_arrival_worker"

    /**
     * Schedules the WorkManager-based arrival detection worker.
     *
     * @param context Application context
     * @param initialDelaySeconds Delay before the first execution (e.g., after trip starts)
     * @param replaceExisting Whether to replace any currently queued worker
     */
    fun scheduleArrivalDetection(
        context: Context,
        initialDelaySeconds: Long = 0L,
        replaceExisting: Boolean = true
    ) {
        try {
            val workManager = WorkManager.getInstance(context)

            val constraints = Constraints.Builder()
                // We do not require unmetered network or charging, so it runs reliably in the background
                .build()

            val workRequestBuilder = OneTimeWorkRequestBuilder<ArrivalDetectionWorker>()
                .setConstraints(constraints)
                .addTag(TAG_ARRIVAL_WORK)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)

            if (initialDelaySeconds > 0) {
                workRequestBuilder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            }

            val workRequest = workRequestBuilder.build()

            val policy = if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, workRequest)

            Log.d(TAG, "Scheduled ArrivalDetectionWorker with initial delay: ${initialDelaySeconds}s (policy=$policy)")
        } catch (e: Exception) {
            Log.w(TAG, "WorkManager not available or not initialized: ${e.message}")
        }
    }

    /**
     * Schedules the next check with the calculated adaptive delay.
     */
    fun scheduleNextCheck(context: Context, delaySeconds: Long) {
        val safeDelay = delaySeconds.coerceAtLeast(15L)
        scheduleArrivalDetection(context, initialDelaySeconds = safeDelay, replaceExisting = true)
        Log.d(TAG, "Scheduled next arrival check in ${safeDelay}s")
    }

    /**
     * Cancels any scheduled or active arrival detection work.
     */
    fun cancelArrivalDetection(context: Context) {
        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelAllWorkByTag(TAG_ARRIVAL_WORK)
            Log.d(TAG, "Cancelled ArrivalDetectionWorker")
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling arrival detection work: ${e.message}")
        }
    }

    /**
     * Calculates the battery-efficient adaptive check interval (in seconds)
     * based on distance remaining to destination, arrival alarm radius, and transport mode.
     *
     * Ensures the background worker wakes up with ample time before the arrival radius is reached,
     * while avoiding constant wake locks and polling when far away.
     */
    fun calculateAdaptiveIntervalSeconds(
        distanceMeters: Double,
        radiusMeters: Int,
        transportMode: TransportMode
    ): Long {
        val bufferMeters = (distanceMeters - radiusMeters).coerceAtLeast(0.0)

        // Estimated speed in meters per second
        val estimatedSpeedMps = when (transportMode) {
            TransportMode.WALK -> 1.4 // ~5 km/h
            TransportMode.CYCLE -> 4.5 // ~16 km/h
            TransportMode.BUS -> 12.0 // ~43 km/h
            TransportMode.TRAIN -> 25.0 // ~90 km/h
            TransportMode.CAR -> 22.0 // ~80 km/h
        }

        // Time to reach boundary with 50% safety margin
        val travelTimeToRadiusSec = (bufferMeters / estimatedSpeedMps).toLong()
        val safetyAdjustedTimeSec = (travelTimeToRadiusSec * 0.5).toLong()

        val distanceBasedIntervalSec = when {
            distanceMeters > 30_000.0 -> 900L // 15 min (>30km)
            distanceMeters > 15_000.0 -> 480L // 8 min (15-30km)
            distanceMeters > 7_000.0 -> 240L  // 4 min (7-15km)
            distanceMeters > 3_000.0 -> 120L  // 2 min (3-7km)
            distanceMeters > 1_500.0 -> 60L   // 1 min (1.5-3km)
            else -> 30L                       // 30s (<1.5km - close approach)
        }

        // Take the tighter of the distance-based interval and the travel time estimate, bounded between 20s and 900s
        val interval = minOf(distanceBasedIntervalSec, maxOf(20L, safetyAdjustedTimeSec))
        return interval.coerceIn(20L, 900L)
    }
}
