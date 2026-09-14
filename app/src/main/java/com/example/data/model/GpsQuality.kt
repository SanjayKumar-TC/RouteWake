package com.example.data.model

enum class GpsQuality(val label: String) {
    EXCELLENT("GPS • Excellent"),
    GOOD("GPS • Good"),
    FAIR("GPS • Fair"),
    POOR("GPS • Poor"),
    STALE("GPS • Stale"),
    OFF("GPS • Off");

    companion object {
        fun fromAccuracy(accuracyMeters: Float, ageSeconds: Long, isAvailable: Boolean = true): GpsQuality {
            return when {
                !isAvailable -> OFF
                ageSeconds > 15 -> STALE
                accuracyMeters > 50 -> POOR
                accuracyMeters <= 12 -> EXCELLENT
                accuracyMeters <= 25 -> GOOD
                else -> FAIR
            }
        }
    }
}
