package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.data.model.GpsQuality
import com.example.network.RoutingService
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.max

/**
 * Authoritative GPS Location Engine for RouteWake.
 *
 * Single owner of device location updates:
 * - Uses Google Play Services FusedLocationProviderClient with Priority.PRIORITY_HIGH_ACCURACY.
 * - Robust dual-provider LocationManager fallback for AOSP / environments without Play Services.
 * - Real elapsed-realtime fix freshness validation (monotonic clock).
 * - Adaptive update intervals based on destination distance.
 * - Minimal displacement threshold (0m) to guarantee real-time updates while stationary or moving.
 * - Light jitter filtering without artificial lag.
 * - Clean stale detection and diagnostic state tracking.
 */
class GpsEngine private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private var simulationJob: Job? = null
    private var warmupJob: Job? = null
    private var staleCheckJob: Job? = null

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _gpsQuality = MutableStateFlow(GpsQuality.GOOD)
    val gpsQuality: StateFlow<GpsQuality> = _gpsQuality.asStateFlow()

    private val _warmupSecondsRemaining = MutableStateFlow(0)
    val warmupSecondsRemaining: StateFlow<Int> = _warmupSecondsRemaining.asStateFlow()

    private var isSubscribed = false
    private var isUsingFused = false
    private var currentIntervalMs: Long = 2000L
    private var lastAcceptedFixRealtimeNanos: Long = 0L

    var onLocationUpdated: ((Location) -> Unit)? = null
    var onWarmupFinished: (() -> Unit)? = null

    // Fused Location Callback
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {
                processRawLocation(location, "fused")
            }
        }

        override fun onLocationAvailability(avail: LocationAvailability) {
            if (!avail.isLocationAvailable) {
                Log.d(TAG, "Fused location unavailable, checking providers")
                checkProviderStatus()
            }
        }
    }

    // Standard Android LocationManager Listener (Fallback)
    private val standardLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            processRawLocation(location, location.provider ?: "location_manager")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            checkProviderStatus()
        }
        override fun onProviderDisabled(provider: String) {
            checkProviderStatus()
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isSubscribed) return
        isSubscribed = true
        Log.d(TAG, "startTracking: starting location subscriptions (interval=${currentIntervalMs}ms)")

        startStaleMonitor()
        checkProviderStatus()

        var fusedRegistered = false
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
                .setMinUpdateIntervalMillis(minOf(1000L, currentIntervalMs / 2))
                .setMinUpdateDistanceMeters(0f)
                .setWaitForAccurateLocation(false)
                .build()

            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                .addOnSuccessListener {
                    fusedRegistered = true
                    isUsingFused = true
                    Log.d(TAG, "FusedLocationProviderClient connected successfully")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "FusedLocationProviderClient failed to register: ${e.message}, falling back to LocationManager")
                    registerLocationManagerFallback()
                }

            // Immediately query last location for rapid UI populating
            fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    val ageSec = getLocationAgeSeconds(lastLoc)
                    if (ageSec <= 15.0) {
                        Log.d(TAG, "Fused lastLocation is fresh (${ageSec}s old), accepting immediately")
                        processRawLocation(lastLoc, "fused_cached_fresh")
                    } else {
                        Log.d(TAG, "Fused lastLocation is stale (${ageSec}s old), waiting for live fix")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "FusedLocationProviderClient exception: ${e.message}, using LocationManager fallback")
            registerLocationManagerFallback()
        }

        // Always also check LocationManager last known location if we don't have a fix yet
        if (_currentLocation.value == null) {
            try {
                val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val best = when {
                    gpsLoc == null -> netLoc
                    netLoc == null -> gpsLoc
                    getLocationAgeSeconds(gpsLoc) < getLocationAgeSeconds(netLoc) -> gpsLoc
                    else -> netLoc
                }
                if (best != null && getLocationAgeSeconds(best) <= 15.0) {
                    processRawLocation(best, "lm_cached_fresh")
                }
            } catch (_: SecurityException) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationManagerFallback() {
        try {
            isUsingFused = false
            val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (hasGps) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    currentIntervalMs,
                    0f,
                    standardLocationListener,
                    Looper.getMainLooper()
                )
            }
            if (hasNetwork) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    currentIntervalMs,
                    0f,
                    standardLocationListener,
                    Looper.getMainLooper()
                )
            }
            Log.d(TAG, "LocationManager fallback registered: hasGps=$hasGps, hasNetwork=$hasNetwork")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering LocationManager: ${e.message}")
            _gpsQuality.value = GpsQuality.OFF
        }
    }

    fun stopTracking() {
        Log.d(TAG, "stopTracking: removing subscriptions")
        isSubscribed = false
        staleCheckJob?.cancel()
        warmupJob?.cancel()
        simulationJob?.cancel()

        try {
            fusedClient.removeLocationUpdates(fusedCallback)
        } catch (_: Throwable) {}

        try {
            locationManager.removeUpdates(standardLocationListener)
        } catch (_: Throwable) {}
    }

    fun startWarmup(onComplete: () -> Unit) {
        warmupJob?.cancel()
        _warmupSecondsRemaining.value = 10

        warmupJob = scope.launch {
            for (sec in 10 downTo 1) {
                _warmupSecondsRemaining.value = sec
                delay(1000L)
            }
            _warmupSecondsRemaining.value = 0
            withContext(Dispatchers.Main) {
                onWarmupFinished?.invoke()
                onComplete()
            }
        }
    }

    /**
     * Adaptive GPS interval updates for active trip tracking.
     * FAR FROM DESTINATION (>10km): ~10-15s (Eco: ~15s)
     * 3-10 km: ~5-8s (Eco: ~10s)
     * 1-3 km: ~2.5-4s (Eco: ~5s)
     * 500m - 1km: ~1.5s (High precision overrides Eco)
     * < 500m: ~1s (Arrival critical)
     */
    @SuppressLint("MissingPermission")
    fun updateAdaptiveInterval(distanceRemainingMeters: Double, isEcoMode: Boolean) {
        val targetIntervalMs = when {
            distanceRemainingMeters > 10_000 -> if (isEcoMode) 15_000L else 10_000L
            distanceRemainingMeters > 3_000 -> if (isEcoMode) 10_000L else 6_000L
            distanceRemainingMeters > 1_000 -> if (isEcoMode) 5_000L else 3_000L
            distanceRemainingMeters > 500 -> 1_500L
            else -> 1_000L // Very near destination: ~1 second, reliability > battery
        }

        if (targetIntervalMs != currentIntervalMs && isSubscribed) {
            Log.d(TAG, "Adaptive interval updated: ${currentIntervalMs}ms -> ${targetIntervalMs}ms (dist=${distanceRemainingMeters.toInt()}m, eco=$isEcoMode)")
            currentIntervalMs = targetIntervalMs
            reapplyLocationRequests()
        }
    }

    @SuppressLint("MissingPermission")
    private fun reapplyLocationRequests() {
        if (!isSubscribed) return
        try {
            if (isUsingFused) {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
                    .setMinUpdateIntervalMillis(minOf(1000L, currentIntervalMs / 2))
                    .setMinUpdateDistanceMeters(0f)
                    .setWaitForAccurateLocation(false)
                    .build()
                fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
            } else {
                locationManager.removeUpdates(standardLocationListener)
                registerLocationManagerFallback()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error reapplying location requests: ${e.message}")
        }
    }

    /**
     * Primary Location Ingestion Pipeline.
     * Evaluates fix freshness, checks impossible jumps, updates quality status, and notifies observers.
     */
    private fun processRawLocation(location: Location, source: String) {
        // 1. Basic coordinate sanity
        if (location.latitude < -90.0 || location.latitude > 90.0 ||
            location.longitude < -180.0 || location.longitude > 180.0 ||
            (location.latitude == 0.0 && location.longitude == 0.0)) {
            Log.w(TAG, "GPS_FIX rejected: invalid coordinates lat=${location.latitude} lng=${location.longitude}")
            return
        }

        // 2. Freshness check using elapsed realtime monotonic clock
        val ageSec = getLocationAgeSeconds(location)
        if (ageSec > 20.0) {
            Log.d(TAG, "GPS_FIX rejected: stale fix age=${String.format(Locale.US, "%.1f", ageSec)}s source=$source")
            return
        }

        // 3. Prevent duplicate processing of exact same timestamp fix
        val fixNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos
        } else {
            location.time * 1_000_000L
        }
        if (fixNanos == lastAcceptedFixRealtimeNanos && _currentLocation.value != null) {
            return
        }

        // 4. Glitch / Impossible Jump Rejection
        val previous = _currentLocation.value
        if (previous != null) {
            val dtSec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                max(0.05, (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0)
            } else {
                max(0.05, (location.time - previous.time) / 1000.0)
            }

            val dist = RoutingService.computeHaversineDistanceMeters(
                previous.latitude, previous.longitude,
                location.latitude, location.longitude
            )
            val speedMps = dist / dtSec

            // Reject impossible jumps (> 100 m/s or 360 km/h) if accuracy is poor
            if (speedMps > 100.0 && location.hasAccuracy() && location.accuracy > 30f) {
                Log.d(TAG, "GPS_FIX rejected: impossible jump dist=${dist.toInt()}m dt=${String.format(Locale.US, "%.1f", dtSec)}s speed=${speedMps.toInt()}m/s source=$source")
                return
            }
        }

        // 5. Fix Accepted
        lastAcceptedFixRealtimeNanos = fixNanos
        val accuracy = if (location.hasAccuracy()) location.accuracy else 25f
        _gpsQuality.value = GpsQuality.fromAccuracy(accuracy, ageSec.toLong(), isAvailable = true)
        _currentLocation.value = location

        Log.d(TAG, "GPS_FIX accepted: accuracy=${String.format(Locale.US, "%.1f", accuracy)}m age=${String.format(Locale.US, "%.1f", ageSec)}s speed=${String.format(Locale.US, "%.1f", location.speed)}m/s bearing=${String.format(Locale.US, "%.1f", location.bearing)} provider=${location.provider ?: source}")

        onLocationUpdated?.invoke(location)
    }

    private fun getLocationAgeSeconds(location: Location): Double {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            max(0.0, ageNanos / 1_000_000_000.0)
        } else {
            val ageMillis = System.currentTimeMillis() - location.time
            max(0.0, ageMillis / 1000.0)
        }
    }

    private fun checkProviderStatus() {
        val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!hasGps && !hasNetwork) {
            _gpsQuality.value = GpsQuality.OFF
        }
    }

    private fun isAnyLocationProviderAvailable(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun startStaleMonitor() {
        staleCheckJob?.cancel()
        staleCheckJob = scope.launch {
            while (isActive) {
                delay(4000L)
                if (!isSubscribed) continue

                if (!isAnyLocationProviderAvailable()) {
                    _gpsQuality.value = GpsQuality.OFF
                    continue
                }

                if (lastAcceptedFixRealtimeNanos > 0L) {
                    val nowNanos = SystemClock.elapsedRealtimeNanos()
                    val ageNanos = nowNanos - lastAcceptedFixRealtimeNanos
                    if (ageNanos > 15_000_000_000L) { // 15 seconds stale threshold
                        if (_gpsQuality.value != GpsQuality.STALE) {
                            _gpsQuality.value = GpsQuality.STALE
                            Log.d(TAG, "GPS_STATE changed to STALE (no fix for ${(ageNanos / 1e9).toInt()}s)")
                        }
                    }
                }
            }
        }
    }

    // Development GPS Simulation
    fun startSimulation(
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double,
        speedKmh: Double = 50.0,
        timeMultiplier: Int = 20,
        onStep: (Location) -> Unit
    ) {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            val totalDistance = RoutingService.computeHaversineDistanceMeters(
                startLat, startLng, destLat, destLng
            )
            val simulatedSpeedMps = (speedKmh * 1000.0 / 3600.0) * timeMultiplier
            val totalDurationSec = max(totalDistance / max(simulatedSpeedMps, 1.0), 10.0)
            val steps = (totalDurationSec * 2).toInt().coerceIn(20, 300)

            for (i in 0..steps) {
                if (!isActive) break
                val fraction = i.toDouble() / steps.toDouble()
                val curLat = startLat + (destLat - startLat) * fraction
                val curLng = startLng + (destLng - startLng) * fraction

                val simLocation = Location("Simulation").apply {
                    latitude = curLat
                    longitude = curLng
                    accuracy = 5f
                    time = System.currentTimeMillis()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    speed = (speedKmh / 3.6).toFloat()
                }

                lastAcceptedFixRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                _currentLocation.value = simLocation
                _gpsQuality.value = GpsQuality.EXCELLENT

                withContext(Dispatchers.Main) {
                    onStep(simLocation)
                    onLocationUpdated?.invoke(simLocation)
                }

                delay(500L)
            }
        }
    }

    fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
    }

    companion object {
        private const val TAG = "RouteWakeGps"

        @Volatile
        private var INSTANCE: GpsEngine? = null

        fun getInstance(context: Context): GpsEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GpsEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
