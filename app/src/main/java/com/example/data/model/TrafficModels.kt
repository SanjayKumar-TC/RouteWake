package com.example.data.model

/**
 * Traffic congestion levels normalized according to RouteWake design specifications:
 * - CLEAR: #10B981 (Emerald Green)
 * - MODERATE: #F59E0B (Amber/Yellow)
 * - HEAVY: #EF4444 (Bright Red)
 * - SEVERE: #DC2626 (Deep Crimson)
 */
enum class CongestionLevel(
    val label: String,
    val colorHex: Long,
    val speedThresholdKmh: Double
) {
    CLEAR("Clear", 0xFF10B981, 50.0),
    MODERATE("Moderate", 0xFFF59E0B, 28.0),
    HEAVY("Heavy", 0xFFEF4444, 14.0),
    SEVERE("Severe", 0xFFDC2626, 0.0);

    companion object {
        fun fromSpeedKmh(speedKmh: Double): CongestionLevel {
            return when {
                speedKmh >= CLEAR.speedThresholdKmh -> CLEAR
                speedKmh >= MODERATE.speedThresholdKmh -> MODERATE
                speedKmh >= HEAVY.speedThresholdKmh -> HEAVY
                else -> SEVERE
            }
        }
    }
}

/**
 * A segmented portion of the authoritative route geometry with an associated congestion level.
 * Adjacent segments share boundary coordinates to ensure zero visible gaps.
 */
data class TrafficSegment(
    val points: List<RoutePoint>,
    val congestionLevel: CongestionLevel,
    val speedKmh: Double? = null,
    val normalSpeedKmh: Double? = null,
    val roadName: String? = null,
    val delayMinutes: Int? = null,
    val distanceMeters: Double? = null,
    val incident: String? = null,
    val incidentDescription: String? = null,
    val lastUpdated: String? = null
)

/**
 * Incident classification and severity hierarchy.
 */
enum class IncidentSeverity {
    MODERATE,
    HIGH
}

enum class IncidentType(val label: String) {
    ACCIDENT("Accident"),
    CONSTRUCTION("Construction"),
    SLOWDOWN("Traffic Slowdown"),
    CLOSURE("Road Closure"),
    HAZARD("Road Hazard")
}

/**
 * Native traffic incident representation.
 * Custom popup displays only information actually supplied by the service.
 */
data class TrafficIncident(
    val id: String = java.util.UUID.randomUUID().toString(),
    val latitude: Double,
    val longitude: Double,
    val type: IncidentType,
    val title: String,
    val description: String,
    val delayMinutes: Int? = null,
    val roadName: String? = null,
    val severity: IncidentSeverity = IncidentSeverity.MODERATE,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Overall traffic container bound to the authoritative route geometry.
 * Discloses whether data is verified live or estimated road speeds.
 */
data class TrafficData(
    val segments: List<TrafficSegment>,
    val incidents: List<TrafficIncident> = emptyList(),
    val isEstimated: Boolean = true,
    val summaryLabel: String = "Estimated Traffic"
)
