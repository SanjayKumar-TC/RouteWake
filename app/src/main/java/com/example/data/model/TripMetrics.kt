package com.example.data.model

data class TripMetrics(
    val currentLat: Double? = null,
    val currentLng: Double? = null,
    val distanceRemainingMeters: Double = 0.0,
    val initialDistanceMeters: Double = 0.0,
    val etaSeconds: Long = 0,
    val progressPercent: Float = 0f,
    val currentSpeedKmh: Double = 0.0,
    val isModeledEta: Boolean = true,
    val warmupSecondsRemaining: Int = 3,
    val gpsQuality: GpsQuality = GpsQuality.GOOD
)

data class RoutePoint(
    val latitude: Double,
    val longitude: Double
)

data class RouteInfo(
    val distanceMeters: Double,
    val durationSeconds: Long,
    val points: List<RoutePoint>,
    val isModeled: Boolean,
    val trafficData: TrafficData? = null
)
