package com.example.engine

import android.content.Context
import com.example.data.local.RouteWakeDatabase
import com.example.data.local.TripHistoryEntity
import com.example.data.model.AlarmTone
import com.example.data.model.Destination
import com.example.data.model.TransportMode
import com.example.data.model.TripState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ArrivalEngine(
    private val context: Context,
    private val audioEngine: AlarmAudioEngine,
    private val vibrationEngine: VibrationEngine
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var snoozeJob: Job? = null

    private val _tripState = MutableStateFlow(TripState.IDLE)
    val tripState: StateFlow<TripState> = _tripState.asStateFlow()

    private var activeDestination: Destination? = null
    private var activeRadiusMeters: Int = 500
    private var activeTransportMode: TransportMode = TransportMode.CAR
    private var tripStartTimeMs: Long = 0
    private var initialDistanceMeters: Double = 0.0
    private var hasTriggeredArrival: Boolean = false

    var onArrivalTriggered: ((Destination, Double) -> Unit)? = null
    var onTripCompleted: ((TripHistoryEntity) -> Unit)? = null

    fun startTrip(
        destination: Destination,
        radiusMeters: Int,
        transport: TransportMode,
        initialDistance: Double
    ) {
        activeDestination = destination
        activeRadiusMeters = radiusMeters
        activeTransportMode = transport
        initialDistanceMeters = initialDistance
        tripStartTimeMs = System.currentTimeMillis()
        hasTriggeredArrival = false
        snoozeJob?.cancel()

        // Start with GPS warmup state
        _tripState.value = TripState.WARMUP
    }

    fun onWarmupFinished() {
        if (_tripState.value == TripState.WARMUP) {
            _tripState.value = TripState.TRACKING
        }
    }

    fun evaluateDistance(currentDistanceMeters: Double, tone: AlarmTone, vibrationEnabled: Boolean) {
        if (_tripState.value != TripState.TRACKING) return
        if (hasTriggeredArrival) return

        if (currentDistanceMeters <= activeRadiusMeters) {
            triggerArrival(currentDistanceMeters, tone, vibrationEnabled)
        }
    }

    private fun triggerArrival(distanceMeters: Double, tone: AlarmTone, vibrationEnabled: Boolean) {
        hasTriggeredArrival = true
        _tripState.value = TripState.ARRIVED

        // Trigger synthesized alarm sound & volume ramp
        audioEngine.startAlarm(tone)

        // Trigger requested vibration pattern
        if (vibrationEnabled) {
            vibrationEngine.startArrivalVibration()
        }

        activeDestination?.let { dest ->
            onArrivalTriggered?.invoke(dest, distanceMeters)
        }
    }

    fun snooze(seconds: Int = 60, tone: AlarmTone, vibrationEnabled: Boolean) {
        audioEngine.stopAlarm()
        vibrationEngine.stopVibration()

        snoozeJob?.cancel()
        snoozeJob = scope.launch {
            delay(seconds * 1000L)
            if (_tripState.value == TripState.ARRIVED) {
                // Retrigger alarm
                audioEngine.startAlarm(tone)
                if (vibrationEnabled) {
                    vibrationEngine.startArrivalVibration()
                }
            }
        }
    }

    fun dismiss(finalDistanceMeters: Double) {
        snoozeJob?.cancel()
        audioEngine.stopAlarm()
        vibrationEngine.stopVibration()

        val dest = activeDestination
        val durationSec = ((System.currentTimeMillis() - tripStartTimeMs) / 1000).coerceAtLeast(1)
        val distanceCovered = (initialDistanceMeters - finalDistanceMeters).coerceAtLeast(0.0)
        val avgSpeedKmh = if (durationSec > 0) (distanceCovered / durationSec) * 3.6 else 0.0

        if (dest != null) {
            val historyEntity = TripHistoryEntity(
                destinationName = dest.name,
                destinationAddress = dest.address,
                destinationLat = dest.latitude,
                destinationLng = dest.longitude,
                startTimeMs = tripStartTimeMs,
                endTimeMs = System.currentTimeMillis(),
                distanceMeters = initialDistanceMeters,
                durationSeconds = durationSec,
                avgSpeedKmh = avgSpeedKmh,
                transportMode = activeTransportMode.name,
                radiusMeters = activeRadiusMeters,
                completed = true
            )

            // Persist to Room database
            scope.launch(Dispatchers.IO) {
                try {
                    RouteWakeDatabase.getDatabase(context).historyDao().insertTrip(historyEntity)
                } catch (_: Exception) {}
            }

            onTripCompleted?.invoke(historyEntity)
        }

        _tripState.value = TripState.COMPLETED
    }

    fun cancelTrip() {
        snoozeJob?.cancel()
        audioEngine.stopAlarm()
        vibrationEngine.stopVibration()
        _tripState.value = TripState.IDLE
        activeDestination = null
    }

    fun getActiveDestination(): Destination? = activeDestination
    fun getActiveRadius(): Int = activeRadiusMeters
    fun getActiveTransport(): TransportMode = activeTransportMode
    fun isArrived(): Boolean = _tripState.value == TripState.ARRIVED
}
