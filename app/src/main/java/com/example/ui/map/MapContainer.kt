package com.example.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.data.local.MapCameraState
import com.example.data.model.Destination
import com.example.data.model.RoutePoint
import com.example.data.model.TrafficData
import com.example.data.model.TransportMode
import com.example.data.model.TripState

/**
 * Persistent MapContainer hosting the high-performance native OsmNativeMapView.
 * Placed at the bottom of the Z-order hierarchy as the permanent background visual layer.
 *
 * 100% Free and Open - Requires ZERO API keys, secrets, or external credentials.
 */
@Composable
fun MapContainer(
    currentLat: Double?,
    currentLng: Double?,
    destination: Destination?,
    alarmRadiusMeters: Int,
    showAlarmRadius: Boolean,
    routePoints: List<RoutePoint>,
    isSatellite: Boolean,
    modifier: Modifier = Modifier,
    initialCameraState: MapCameraState? = null,
    isFollowMode: Boolean = true,
    onManualNavigation: ((lat: Double, lng: Double, zoom: Double) -> Unit)? = null,
    onCameraChanged: ((lat: Double, lng: Double, zoom: Double) -> Unit)? = null,
    onSaveCameraImmediate: ((lat: Double, lng: Double, zoom: Double) -> Unit)? = null,
    recenterTrigger: Int = 0,
    zoomInTrigger: Int = 0,
    zoomOutTrigger: Int = 0,
    fitRouteTrigger: Int = 0,
    bearing: Float? = null,
    speedKmh: Double = 0.0,
    transportMode: TransportMode = TransportMode.CAR,
    tripState: TripState = TripState.IDLE,
    routeDistanceMeters: Double = 0.0,
    routeDurationSeconds: Long = 0L,
    gpsQualityLabel: String = "",
    trafficEnabled: Boolean = true,
    trafficData: TrafficData? = null,
    onMapClick: (Double, Double) -> Unit = { _, _ -> },
    onStartTrip: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize()) {
        OsmNativeMapView(
            currentLat = currentLat,
            currentLng = currentLng,
            destination = destination,
            alarmRadiusMeters = alarmRadiusMeters,
            showAlarmRadius = showAlarmRadius,
            routePoints = routePoints,
            isSatellite = isSatellite,
            initialCameraState = initialCameraState,
            isFollowMode = isFollowMode,
            onManualNavigation = onManualNavigation,
            onCameraChanged = onCameraChanged,
            onSaveCameraImmediate = onSaveCameraImmediate,
            recenterTrigger = recenterTrigger,
            zoomInTrigger = zoomInTrigger,
            zoomOutTrigger = zoomOutTrigger,
            fitRouteTrigger = fitRouteTrigger,
            bearing = bearing,
            speedKmh = speedKmh,
            transportMode = transportMode,
            tripState = tripState,
            routeDistanceMeters = routeDistanceMeters,
            routeDurationSeconds = routeDurationSeconds,
            gpsQualityLabel = gpsQualityLabel,
            trafficEnabled = trafficEnabled,
            trafficData = trafficData,
            onMapClick = onMapClick,
            onStartTrip = onStartTrip,
            modifier = Modifier.fillMaxSize()
        )
    }
}

