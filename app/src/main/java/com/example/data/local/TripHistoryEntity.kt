package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.Destination
import com.example.data.model.TransportMode

@Entity(tableName = "trip_history")
data class TripHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val destinationName: String,
    val destinationAddress: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val avgSpeedKmh: Double,
    val transportMode: String,
    val radiusMeters: Int,
    val completed: Boolean
) {
    fun toDestination(): Destination {
        return Destination(
            name = destinationName,
            address = destinationAddress,
            latitude = destinationLat,
            longitude = destinationLng
        )
    }
}
