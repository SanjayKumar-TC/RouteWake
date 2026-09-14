package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM trip_history ORDER BY endTimeMs DESC LIMIT 20")
    fun getLatestTrips(): Flow<List<TripHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripHistoryEntity): Long

    @Delete
    suspend fun deleteTrip(trip: TripHistoryEntity)

    @Query("DELETE FROM trip_history")
    suspend fun clearHistory()
}
