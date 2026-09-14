package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.Destination
import com.example.data.model.TransportMode

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val preferredRadiusMeters: Int = 500,
    val preferredTransport: String = TransportMode.CAR.name,
    val category: String = "Other",
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toDestination(): Destination {
        return Destination(
            id = id.toString(),
            name = name,
            address = address,
            latitude = latitude,
            longitude = longitude,
            category = category
        )
    }
}
