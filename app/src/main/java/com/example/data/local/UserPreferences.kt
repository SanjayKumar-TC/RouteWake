package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.AlarmTone
import com.example.data.model.TransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

data class AppSettings(
    val ecoMode: Boolean = true,
    val alarmTone: AlarmTone = AlarmTone.RADAR_BEEP,
    val vibrationEnabled: Boolean = true,
    val defaultRadiusMeters: Int = 500,
    val satelliteMap: Boolean = false,
    val wakeLockEnabled: Boolean = true,
    val showDestinationRadiusOnMap: Boolean = true,
    val defaultTransport: TransportMode = TransportMode.CAR,
    val isSimulationActive: Boolean = false,
    val simulationSpeedMode: String = "CAR_30X",
    val trafficEnabled: Boolean = true,
    val workManagerBackgroundEnabled: Boolean = true
)

class UserPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("routewake_preferences", Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    fun getSettings(): AppSettings = _settingsFlow.value

    private fun loadSettings(): AppSettings {
        return AppSettings(
            ecoMode = prefs.getBoolean(KEY_ECO_MODE, true),
            alarmTone = AlarmTone.fromString(prefs.getString(KEY_ALARM_TONE, AlarmTone.RADAR_BEEP.name) ?: AlarmTone.RADAR_BEEP.name),
            vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, true),
            defaultRadiusMeters = prefs.getInt(KEY_DEFAULT_RADIUS, 500),
            satelliteMap = prefs.getBoolean(KEY_SATELLITE, false),
            wakeLockEnabled = prefs.getBoolean(KEY_WAKELOCK, true),
            showDestinationRadiusOnMap = prefs.getBoolean(KEY_SHOW_RADIUS, true),
            defaultTransport = TransportMode.fromString(prefs.getString(KEY_DEFAULT_TRANSPORT, TransportMode.CAR.name) ?: TransportMode.CAR.name),
            isSimulationActive = prefs.getBoolean(KEY_SIM_ACTIVE, false),
            simulationSpeedMode = prefs.getString(KEY_SIM_SPEED, "CAR_30X") ?: "CAR_30X",
            trafficEnabled = prefs.getBoolean(KEY_TRAFFIC_ENABLED, true),
            workManagerBackgroundEnabled = prefs.getBoolean(KEY_WORKMANAGER_BACKGROUND, true)
        )
    }

    fun updateWorkManagerBackground(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WORKMANAGER_BACKGROUND, enabled).apply()
        _settingsFlow.value = _settingsFlow.value.copy(workManagerBackgroundEnabled = enabled)
    }

    fun updateTrafficEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRAFFIC_ENABLED, enabled).apply()
        _settingsFlow.value = _settingsFlow.value.copy(trafficEnabled = enabled)
    }

    fun updateEcoMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ECO_MODE, enabled).apply()
        _settingsFlow.value = _settingsFlow.value.copy(ecoMode = enabled)
    }

    fun updateAlarmTone(tone: AlarmTone) {
        prefs.edit().putString(KEY_ALARM_TONE, tone.name).apply()
        _settingsFlow.value = _settingsFlow.value.copy(alarmTone = tone)
    }

    fun updateVibration(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply()
        _settingsFlow.value = _settingsFlow.value.copy(vibrationEnabled = enabled)
    }

    fun updateDefaultRadius(radiusMeters: Int) {
        prefs.edit().putInt(KEY_DEFAULT_RADIUS, radiusMeters).apply()
        _settingsFlow.value = _settingsFlow.value.copy(defaultRadiusMeters = radiusMeters)
    }

    fun updateSatellite(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SATELLITE, enabled).apply()
        _settingsFlow.value = _settingsFlow.value.copy(satelliteMap = enabled)
    }

    fun updateShowRadius(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_RADIUS, enabled).apply()
        _settingsFlow.value = _settingsFlow.value.copy(showDestinationRadiusOnMap = enabled)
    }

    fun updateSimulation(active: Boolean, speedMode: String = "CAR_30X") {
        prefs.edit()
            .putBoolean(KEY_SIM_ACTIVE, active)
            .putString(KEY_SIM_SPEED, speedMode)
            .apply()
        _settingsFlow.value = _settingsFlow.value.copy(isSimulationActive = active, simulationSpeedMode = speedMode)
    }

    fun getRecentSearches(): List<String> {
        val raw = prefs.getString(KEY_RECENT_SEARCHES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = getRecentSearches().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val limited = current.take(5)
        val array = JSONArray()
        limited.forEach { array.put(it) }
        prefs.edit().putString(KEY_RECENT_SEARCHES, array.toString()).apply()
    }

    fun saveActiveTrip(record: ActiveTripRecord) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE_TRIP_RUNNING, true)
            .putString(KEY_ACTIVE_TRIP_NAME, record.destinationName)
            .putString(KEY_ACTIVE_TRIP_ADDRESS, record.destinationAddress)
            .putLong(KEY_ACTIVE_TRIP_LAT, java.lang.Double.doubleToRawLongBits(record.destinationLat))
            .putLong(KEY_ACTIVE_TRIP_LNG, java.lang.Double.doubleToRawLongBits(record.destinationLng))
            .putInt(KEY_ACTIVE_TRIP_RADIUS, record.radiusMeters)
            .putString(KEY_ACTIVE_TRIP_TRANSPORT, record.transportMode.name)
            .putLong(KEY_ACTIVE_TRIP_START_TIME, record.startTimeMs)
            .apply()
    }

    fun getActiveTrip(): ActiveTripRecord? {
        if (!prefs.getBoolean(KEY_ACTIVE_TRIP_RUNNING, false)) return null
        val name = prefs.getString(KEY_ACTIVE_TRIP_NAME, null) ?: return null
        val address = prefs.getString(KEY_ACTIVE_TRIP_ADDRESS, "") ?: ""
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_ACTIVE_TRIP_LAT, 0L))
        val lng = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_ACTIVE_TRIP_LNG, 0L))
        val radius = prefs.getInt(KEY_ACTIVE_TRIP_RADIUS, 500)
        val transport = TransportMode.fromString(prefs.getString(KEY_ACTIVE_TRIP_TRANSPORT, TransportMode.CAR.name) ?: TransportMode.CAR.name)
        val startTime = prefs.getLong(KEY_ACTIVE_TRIP_START_TIME, System.currentTimeMillis())
        return ActiveTripRecord(name, address, lat, lng, radius, transport, startTime)
    }

    fun clearActiveTrip() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE_TRIP_RUNNING, false)
            .remove(KEY_ACTIVE_TRIP_NAME)
            .remove(KEY_ACTIVE_TRIP_ADDRESS)
            .remove(KEY_ACTIVE_TRIP_LAT)
            .remove(KEY_ACTIVE_TRIP_LNG)
            .remove(KEY_ACTIVE_TRIP_RADIUS)
            .remove(KEY_ACTIVE_TRIP_TRANSPORT)
            .remove(KEY_ACTIVE_TRIP_START_TIME)
            .apply()
    }

    fun hasActiveTrip(): Boolean = prefs.getBoolean(KEY_ACTIVE_TRIP_RUNNING, false)

    fun saveMapCameraState(latitude: Double, longitude: Double, zoom: Double) {
        if (!isValidCameraState(latitude, longitude, zoom)) return
        prefs.edit()
            .putLong(KEY_LAST_MAP_LATITUDE, java.lang.Double.doubleToRawLongBits(latitude))
            .putLong(KEY_LAST_MAP_LONGITUDE, java.lang.Double.doubleToRawLongBits(longitude))
            .putLong(KEY_LAST_MAP_ZOOM, java.lang.Double.doubleToRawLongBits(zoom))
            .putBoolean(KEY_HAS_SAVED_MAP_CAMERA, true)
            .apply()
    }

    fun saveMapCameraStateSynchronous(latitude: Double, longitude: Double, zoom: Double) {
        if (!isValidCameraState(latitude, longitude, zoom)) return
        prefs.edit()
            .putLong(KEY_LAST_MAP_LATITUDE, java.lang.Double.doubleToRawLongBits(latitude))
            .putLong(KEY_LAST_MAP_LONGITUDE, java.lang.Double.doubleToRawLongBits(longitude))
            .putLong(KEY_LAST_MAP_ZOOM, java.lang.Double.doubleToRawLongBits(zoom))
            .putBoolean(KEY_HAS_SAVED_MAP_CAMERA, true)
            .commit()
    }

    fun getMapCameraState(): MapCameraState? {
        if (!prefs.getBoolean(KEY_HAS_SAVED_MAP_CAMERA, false)) return null
        if (!prefs.contains(KEY_LAST_MAP_LATITUDE) || !prefs.contains(KEY_LAST_MAP_LONGITUDE) || !prefs.contains(KEY_LAST_MAP_ZOOM)) {
            return null
        }
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_MAP_LATITUDE, 0L))
        val lng = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_MAP_LONGITUDE, 0L))
        val zoom = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_MAP_ZOOM, 0L))
        return if (isValidCameraState(lat, lng, zoom)) {
            MapCameraState(lat, lng, zoom)
        } else {
            null
        }
    }

    fun hasSavedMapCameraState(): Boolean = getMapCameraState() != null

    fun clearMapCameraState() {
        prefs.edit()
            .remove(KEY_LAST_MAP_LATITUDE)
            .remove(KEY_LAST_MAP_LONGITUDE)
            .remove(KEY_LAST_MAP_ZOOM)
            .putBoolean(KEY_HAS_SAVED_MAP_CAMERA, false)
            .apply()
    }

    companion object {
        private const val KEY_ECO_MODE = "pref_eco_mode"
        private const val KEY_ALARM_TONE = "pref_alarm_tone"
        private const val KEY_VIBRATION = "pref_vibration"
        private const val KEY_DEFAULT_RADIUS = "pref_default_radius"
        private const val KEY_SATELLITE = "pref_satellite"
        private const val KEY_WAKELOCK = "pref_wakelock"
        private const val KEY_SHOW_RADIUS = "pref_show_radius"
        private const val KEY_DEFAULT_TRANSPORT = "pref_default_transport"
        private const val KEY_SIM_ACTIVE = "pref_sim_active"
        private const val KEY_SIM_SPEED = "pref_sim_speed"
        private const val KEY_RECENT_SEARCHES = "pref_recent_searches"
        private const val KEY_TRAFFIC_ENABLED = "pref_traffic_enabled"
        private const val KEY_WORKMANAGER_BACKGROUND = "pref_workmanager_background"

        private const val KEY_ACTIVE_TRIP_RUNNING = "pref_active_trip_running"
        private const val KEY_ACTIVE_TRIP_NAME = "pref_active_trip_name"
        private const val KEY_ACTIVE_TRIP_ADDRESS = "pref_active_trip_address"
        private const val KEY_ACTIVE_TRIP_LAT = "pref_active_trip_lat"
        private const val KEY_ACTIVE_TRIP_LNG = "pref_active_trip_lng"
        private const val KEY_ACTIVE_TRIP_RADIUS = "pref_active_trip_radius"
        private const val KEY_ACTIVE_TRIP_TRANSPORT = "pref_active_trip_transport"
        private const val KEY_ACTIVE_TRIP_START_TIME = "pref_active_trip_start_time"

        private const val KEY_LAST_MAP_LATITUDE = "pref_last_map_latitude"
        private const val KEY_LAST_MAP_LONGITUDE = "pref_last_map_longitude"
        private const val KEY_LAST_MAP_ZOOM = "pref_last_map_zoom"
        private const val KEY_HAS_SAVED_MAP_CAMERA = "pref_has_saved_map_camera"

        fun isValidCameraState(lat: Double, lng: Double, zoom: Double): Boolean {
            if (lat.isNaN() || lat.isInfinite() || lng.isNaN() || lng.isInfinite() || zoom.isNaN() || zoom.isInfinite()) {
                return false
            }
            if (lat < -85.05112878 || lat > 85.05112878) return false
            if (lng < -180.0 || lng > 180.0) return false
            if (zoom < 2.0 || zoom > 22.0) return false
            if (lat == 0.0 && lng == 0.0) return false
            return true
        }
    }
}

data class MapCameraState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double
)

data class ActiveTripRecord(
    val destinationName: String,
    val destinationAddress: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val radiusMeters: Int,
    val transportMode: TransportMode,
    val startTimeMs: Long
)
