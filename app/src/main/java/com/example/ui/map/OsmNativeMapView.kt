package com.example.ui.map

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.local.MapCameraState
import com.example.data.local.UserPreferences
import com.example.data.model.CongestionLevel
import com.example.data.model.Destination
import com.example.data.model.IncidentSeverity
import com.example.data.model.IncidentType
import com.example.data.model.RoutePoint
import com.example.data.model.TrafficData
import com.example.data.model.TrafficIncident
import com.example.data.model.TrafficSegment
import com.example.data.model.TransportMode
import com.example.data.model.TripState
import com.example.ui.components.DestinationInfoPopup
import com.example.ui.components.MapInformationCard
import com.example.ui.components.MapInformationCardType
import com.example.ui.components.TrafficInfoPopup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.*
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow

const val MIN_AUTOMATIC_NAVIGATION_ZOOM = 15.5

/**
 * Safely fits a bounding box within the MapView while strictly adhering to the
 * MIN_AUTOMATIC_NAVIGATION_ZOOM floor to prevent the camera from zooming out to
 * an unreadable country or world level.
 */
private fun safeFitBoundingBox(
    mapView: MapView,
    box: BoundingBox,
    border: Int = 110,
    animationSpeed: Long = 400L,
    fallbackCenter: GeoPoint? = null
) {
    val width = mapView.width - 2 * border
    val height = mapView.height - 2 * border
    val calculatedZoom = if (width > 0 && height > 0) {
        MapView.getTileSystem().getBoundingBoxZoom(box, width, height)
    } else {
        MIN_AUTOMATIC_NAVIGATION_ZOOM
    }
    val clampedZoom = maxOf(calculatedZoom, MIN_AUTOMATIC_NAVIGATION_ZOOM)
    val targetCenter = if (calculatedZoom < MIN_AUTOMATIC_NAVIGATION_ZOOM && fallbackCenter != null) {
        fallbackCenter
    } else {
        box.centerWithDateLine
    }
    mapView.controller?.animateTo(targetCenter, clampedZoom, animationSpeed)
}

/**
 * Premium Native Android Map component using osmdroid (OpenStreetMap).
 * High-performance, low-latency overlay architecture:
 * - Persistent overlay references updated in-place without recreation.
 * - Electric-blue RouteWake GPS beacon with subtle breathing halo and heading cone.
 * - Presentation smoothing with zero lag accumulation and fresh-fix priority near arrival.
 * - Native RouteWake destination pin with vector transport icon.
 * - Custom RouteWake destination popup callout with dark glass styling and pointer.
 * - Native traffic-colored route segments (Clear, Moderate, Heavy, Severe) with dark casing contrast.
 * - Real traffic incident markers and RouteWake-styled incident callout cards.
 * - Empty map tap reverse geocoding with active trip protection.
 * - Dual-cased teal route line against dark night-cockpit tiles.
 * - Free & Open: 100% native OpenStreetMap Mapnik with zero API keys.
 */
@Composable
fun OsmNativeMapView(
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
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var hasCenteredOnFirstFix by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var debouncedSaveJob by remember { mutableStateOf<Job?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                mapViewRef?.let { mv ->
                    val center = mv.mapCenter
                    val zoom = mv.zoomLevelDouble
                    if (center != null && zoom != null) {
                        val lat = center.latitude
                        val lng = center.longitude
                        if (UserPreferences.isValidCameraState(lat, lng, zoom)) {
                            onSaveCameraImmediate?.invoke(lat, lng, zoom)
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            debouncedSaveJob?.cancel()
            mapViewRef?.let { mv ->
                val center = mv.mapCenter
                val zoom = mv.zoomLevelDouble
                if (center != null && zoom != null) {
                    val lat = center.latitude
                    val lng = center.longitude
                    if (UserPreferences.isValidCameraState(lat, lng, zoom)) {
                        onSaveCameraImmediate?.invoke(lat, lng, zoom)
                    }
                }
            }
        }
    }

    // RouteWake night cockpit color filter for OpenStreetMap Mapnik tiles.
    val darkMapColorFilter = remember {
        val matrix = ColorMatrix(
            floatArrayOf(
                -0.26f, -0.44f, -0.12f, 0.00f, 218f,
                -0.18f, -0.54f, -0.10f, 0.00f, 218f,
                -0.20f, -0.46f, -0.16f, 0.00f, 218f,
                 0.00f,  0.00f,  0.00f, 1.00f,   0f
            )
        )
        ColorMatrixColorFilter(matrix)
    }

    // Handle user recenter request
    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger > 0) {
            mapViewRef?.setMapOrientation(0.0f, true)
            if (currentLat != null && currentLng != null) {
                val currZoom = mapViewRef?.zoomLevelDouble ?: 16.0
                val targetZoom = if (currZoom < MIN_AUTOMATIC_NAVIGATION_ZOOM) 16.0 else currZoom
                mapViewRef?.controller?.animateTo(GeoPoint(currentLat, currentLng), targetZoom, 400L)
                debouncedSaveJob?.cancel()
                debouncedSaveJob = coroutineScope.launch {
                    delay(450L)
                    onCameraChanged?.invoke(currentLat, currentLng, targetZoom)
                }
            }
        }
    }

    // Handle zoom controls
    LaunchedEffect(zoomInTrigger) {
        if (zoomInTrigger > 0) {
            mapViewRef?.controller?.zoomIn()
        }
    }

    LaunchedEffect(zoomOutTrigger) {
        if (zoomOutTrigger > 0) {
            mapViewRef?.controller?.zoomOut()
        }
    }

    // Handle Fit Route request
    LaunchedEffect(fitRouteTrigger) {
        if (fitRouteTrigger > 0 && mapViewRef != null) {
            val mv = mapViewRef ?: return@LaunchedEffect
            if (destination != null && currentLat != null && currentLng != null) {
                val minLat = minOf(currentLat, destination.latitude)
                val maxLat = maxOf(currentLat, destination.latitude)
                val minLng = minOf(currentLng, destination.longitude)
                val maxLng = maxOf(currentLng, destination.longitude)
                val latMargin = maxOf(0.008, (maxLat - minLat) * 0.25)
                val lngMargin = maxOf(0.008, (maxLng - minLng) * 0.25)

                val box = BoundingBox(
                    maxLat + latMargin,
                    maxLng + lngMargin,
                    minLat - latMargin,
                    minLng - lngMargin
                )
                safeFitBoundingBox(
                    mv,
                    box,
                    border = 120,
                    fallbackCenter = GeoPoint(destination.latitude, destination.longitude)
                )
            } else if (destination != null) {
                val currZoom = mv.zoomLevelDouble
                val targetZoom = if (currZoom < MIN_AUTOMATIC_NAVIGATION_ZOOM) 16.0 else currZoom
                mv.controller?.animateTo(GeoPoint(destination.latitude, destination.longitude), targetZoom, 400L)
            } else if (currentLat != null && currentLng != null) {
                val currZoom = mv.zoomLevelDouble
                val targetZoom = if (currZoom < MIN_AUTOMATIC_NAVIGATION_ZOOM) 16.0 else currZoom
                mv.controller?.animateTo(GeoPoint(currentLat, currentLng), targetZoom, 400L)
            }
        }
    }

    // Center on valid GPS fix when follow mode is active
    LaunchedEffect(currentLat, currentLng, isFollowMode, mapViewRef) {
        val mv = mapViewRef ?: return@LaunchedEffect
        if (currentLat == null || currentLng == null) {
            // Location is disabled or unavailable; reset the first-fix flag so that
            // when location is subsequently enabled, it will immediately direct to current location
            hasCenteredOnFirstFix = false
        } else if (isFollowMode) {
            if (!hasCenteredOnFirstFix) {
                hasCenteredOnFirstFix = true
                mv.controller?.setZoom(16.0)
                mv.controller?.setCenter(GeoPoint(currentLat, currentLng))
                onCameraChanged?.invoke(currentLat, currentLng, 16.0)
            } else {
                val center = mv.mapCenter
                if (center != null) {
                    val dist = FloatArray(1)
                    Location.distanceBetween(center.latitude, center.longitude, currentLat, currentLng, dist)
                    if (dist[0] >= 1.5f) {
                        val currZoom = mv.zoomLevelDouble
                        mv.controller?.animateTo(GeoPoint(currentLat, currentLng), currZoom, 300L)
                    }
                } else {
                    mv.controller?.animateTo(GeoPoint(currentLat, currentLng))
                }
            }
        }
    }

    // Frame destination only if not already visible in current viewport
    LaunchedEffect(destination?.id) {
        if (destination != null && mapViewRef != null) {
            val mv = mapViewRef ?: return@LaunchedEffect
            val destGeo = GeoPoint(destination.latitude, destination.longitude)
            val currentBox = mv.boundingBox
            val isAlreadyVisible = currentBox != null &&
                    currentBox.latSouth <= destGeo.latitude &&
                    currentBox.latNorth >= destGeo.latitude &&
                    currentBox.lonWest <= destGeo.longitude &&
                    currentBox.lonEast >= destGeo.longitude

            if (!isAlreadyVisible) {
                if (currentLat != null && currentLng != null) {
                    val minLat = minOf(currentLat, destination.latitude)
                    val maxLat = maxOf(currentLat, destination.latitude)
                    val minLng = minOf(currentLng, destination.longitude)
                    val maxLng = maxOf(currentLng, destination.longitude)
                    val latMargin = maxOf(0.008, (maxLat - minLat) * 0.25)
                    val lngMargin = maxOf(0.008, (maxLng - minLng) * 0.25)

                    val box = BoundingBox(
                        maxLat + latMargin,
                        maxLng + lngMargin,
                        minLat - latMargin,
                        minLng - lngMargin
                    )
                    safeFitBoundingBox(
                        mv,
                        box,
                        border = 110,
                        fallbackCenter = destGeo
                    )
                } else {
                    val currZoom = mv.zoomLevelDouble
                    val targetZoom = if (currZoom < MIN_AUTOMATIC_NAVIGATION_ZOOM) 16.0 else currZoom
                    mv.controller?.animateTo(destGeo, targetZoom, 500L)
                }
            }
        }
    }

    // Smoothly animate camera toward destination upon confirmed arrival
    LaunchedEffect(tripState) {
        if (tripState == TripState.ARRIVED && destination != null && mapViewRef != null) {
            val currZoom = mapViewRef?.zoomLevelDouble ?: 16.0
            val targetZoom = if (currZoom < MIN_AUTOMATIC_NAVIGATION_ZOOM) 16.0 else currZoom
            mapViewRef?.controller?.animateTo(
                GeoPoint(destination.latitude, destination.longitude),
                targetZoom,
                650L
            )
        }
    }

    // Camera and map state for custom RouteWake information cards
    var activeCardState by remember { mutableStateOf<MapInformationCardType>(MapInformationCardType.None) }
    var trafficAnchorGeoPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var destAnchorGeoPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Dismiss traffic card if trip starts or traffic layer is disabled
    LaunchedEffect(destination?.id, tripState, trafficEnabled) {
        if (tripState == TripState.TRACKING || !trafficEnabled) {
            if (activeCardState is MapInformationCardType.RouteTraffic) {
                activeCardState = MapInformationCardType.None
                val h = mapViewRef?.getTag() as? MapOverlayHolder
                h?.isCardOpen = false
            }
            trafficAnchorGeoPoint = null
        }
    }

    // Dismiss destination card if destination is cleared
    LaunchedEffect(destination?.id) {
        if (destination == null) {
            if (activeCardState is MapInformationCardType.DestinationInfo) {
                activeCardState = MapInformationCardType.None
                val h = mapViewRef?.getTag() as? MapOverlayHolder
                h?.isCardOpen = false
            }
            destAnchorGeoPoint = null
        }
    }

    // Calculate whether user is near destination for popup emphasis
    val isNearDestination = remember(currentLat, currentLng, destination, routeDistanceMeters, alarmRadiusMeters) {
        if (currentLat != null && currentLng != null && destination != null) {
            val dist = FloatArray(1)
            Location.distanceBetween(currentLat, currentLng, destination.latitude, destination.longitude, dist)
            val straightDist = dist[0].toDouble()
            val effectiveDist = if (routeDistanceMeters > 0) minOf(routeDistanceMeters, straightDist) else straightDist
            effectiveDist <= maxOf(alarmRadiusMeters.toDouble(), 500.0)
        } else false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    mapViewRef = this
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    isTilesScaledToDpi = true
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled = false
                    minZoomLevel = 2.0
                    maxZoomLevel = 20.0
                    setScrollableAreaLimitDouble(BoundingBox(85.05112878, 180.0, -85.05112878, -180.0))
                    setScrollableAreaLimitLatitude(85.05112878, -85.05112878, 0)
                    setScrollableAreaLimitLongitude(-180.0, 180.0, 0)
                    InfoWindow.closeAllInfoWindowsOn(this)

                    val scheduleDebouncedSave: () -> Unit = {
                        val center = mapCenter
                        val zoom = zoomLevelDouble
                        if (center != null && zoom != null) {
                            val lat = center.latitude
                            val lng = center.longitude
                            if (UserPreferences.isValidCameraState(lat, lng, zoom)) {
                                debouncedSaveJob?.cancel()
                                debouncedSaveJob = coroutineScope.launch {
                                    delay(500L)
                                    onManualNavigation?.invoke(lat, lng, zoom)
                                    onCameraChanged?.invoke(lat, lng, zoom)
                                }
                            }
                        }
                    }

                    var isUserInteracting = false
                    setOnTouchListener { _, motionEvent ->
                        when (motionEvent.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                isUserInteracting = true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isUserInteracting = false
                                scheduleDebouncedSave()
                            }
                        }
                        false
                    }

                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            if (activeCardState !is MapInformationCardType.None) {
                                trafficAnchorGeoPoint?.let { geo ->
                                    val pt = Point()
                                    projection?.toPixels(geo, pt)
                                    val newAnchor = Offset(pt.x.toFloat(), pt.y.toFloat())
                                    val curr = activeCardState
                                    if (curr is MapInformationCardType.RouteTraffic) {
                                        activeCardState = curr.copy(anchorPx = newAnchor)
                                    }
                                }
                                destAnchorGeoPoint?.let { geo ->
                                    val pt = Point()
                                    projection?.toPixels(geo, pt)
                                    val density = ctx.resources.displayMetrics.density
                                    val newAnchor = Offset(pt.x.toFloat(), pt.y.toFloat() - (48f * density))
                                    val curr = activeCardState
                                    if (curr is MapInformationCardType.DestinationInfo) {
                                        activeCardState = curr.copy(anchorPx = newAnchor)
                                    }
                                }
                            }
                            if (isUserInteracting) {
                                scheduleDebouncedSave()
                            }
                            return false
                        }

                        override fun onZoom(event: ZoomEvent?): Boolean {
                            if (activeCardState !is MapInformationCardType.None) {
                                trafficAnchorGeoPoint?.let { geo ->
                                    val pt = Point()
                                    projection?.toPixels(geo, pt)
                                    val newAnchor = Offset(pt.x.toFloat(), pt.y.toFloat())
                                    val curr = activeCardState
                                    if (curr is MapInformationCardType.RouteTraffic) {
                                        activeCardState = curr.copy(anchorPx = newAnchor)
                                    }
                                }
                                destAnchorGeoPoint?.let { geo ->
                                    val pt = Point()
                                    projection?.toPixels(geo, pt)
                                    val density = ctx.resources.displayMetrics.density
                                    val newAnchor = Offset(pt.x.toFloat(), pt.y.toFloat() - (48f * density))
                                    val curr = activeCardState
                                    if (curr is MapInformationCardType.DestinationInfo) {
                                        activeCardState = curr.copy(anchorPx = newAnchor)
                                    }
                                }
                            }
                            scheduleDebouncedSave()
                            return false
                        }
                    })

                    setTileSource(TileSourceFactory.MAPNIK)
                    if (isSatellite) {
                        overlayManager.tilesOverlay.setColorFilter(null)
                    } else {
                        overlayManager.tilesOverlay.setColorFilter(darkMapColorFilter)
                    }

                    if (isFollowMode && currentLat != null && currentLng != null) {
                        // Location is ON and Follow Mode is active: prioritize centering on current GPS location
                        controller.setZoom(16.0)
                        controller.setCenter(GeoPoint(currentLat, currentLng))
                        hasCenteredOnFirstFix = true
                        onCameraChanged?.invoke(currentLat, currentLng, 16.0)
                    } else if (initialCameraState != null) {
                        // Location is OFF or manual navigation saved state: respect and direct to saved camera state
                        val clampedLat = initialCameraState.latitude.coerceIn(-85.05112878, 85.05112878)
                        val clampedLng = initialCameraState.longitude.coerceIn(-180.0, 180.0)
                        val clampedZoom = initialCameraState.zoom.coerceIn(2.0, 20.0)
                        controller.setZoom(clampedZoom)
                        controller.setCenter(GeoPoint(clampedLat, clampedLng))
                        hasCenteredOnFirstFix = false
                    } else if (currentLat != null && currentLng != null) {
                        controller.setZoom(16.0)
                        controller.setCenter(GeoPoint(currentLat, currentLng))
                        hasCenteredOnFirstFix = true
                    } else {
                        // Default fallback
                        controller.setZoom(16.0)
                        controller.setCenter(GeoPoint(37.7749, -122.4194))
                        hasCenteredOnFirstFix = false
                    }

                    val holder = MapOverlayHolder(this, ctx, onMapClick)
                    holder.onTrafficSegmentSelected = { seg, geoPoint ->
                        trafficAnchorGeoPoint = geoPoint
                        destAnchorGeoPoint = null
                        val pt = Point()
                        projection?.toPixels(geoPoint, pt)
                        val anchor = Offset(pt.x.toFloat(), pt.y.toFloat())
                        activeCardState = MapInformationCardType.RouteTraffic(seg, anchor)
                        holder.isCardOpen = true
                    }
                    holder.onDismissTraffic = {
                        if (activeCardState is MapInformationCardType.RouteTraffic) {
                            activeCardState = MapInformationCardType.None
                        }
                        holder.isCardOpen = false
                    }
                    holder.onDestinationSelected = { dest, geoPoint ->
                        destAnchorGeoPoint = geoPoint
                        trafficAnchorGeoPoint = null
                        val pt = Point()
                        val density = ctx.resources.displayMetrics.density
                        projection?.toPixels(geoPoint, pt)
                        val anchor = Offset(pt.x.toFloat(), pt.y.toFloat() - (48f * density))
                        activeCardState = MapInformationCardType.DestinationInfo(dest, anchor)
                        holder.isCardOpen = true
                    }
                    holder.onDismissDestination = {
                        if (activeCardState is MapInformationCardType.DestinationInfo) {
                            activeCardState = MapInformationCardType.None
                        }
                        holder.isCardOpen = false
                    }
                    setTag(holder)

                    holder.update(
                        currentLat = currentLat,
                        currentLng = currentLng,
                        bearing = bearing,
                        speedKmh = speedKmh,
                        destination = destination,
                        alarmRadiusMeters = alarmRadiusMeters,
                        showAlarmRadius = showAlarmRadius,
                        routePoints = routePoints,
                        transportMode = transportMode,
                        tripState = tripState,
                        routeDistanceMeters = routeDistanceMeters,
                        routeDurationSeconds = routeDurationSeconds,
                        gpsQualityLabel = gpsQualityLabel,
                        trafficEnabled = trafficEnabled,
                        trafficData = trafficData
                    )
                }
            },
            update = { mapView ->
                if (mapView.tileProvider.tileSource != TileSourceFactory.MAPNIK) {
                    mapView.setTileSource(TileSourceFactory.MAPNIK)
                }
                if (isSatellite) {
                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                } else {
                    mapView.overlayManager.tilesOverlay.setColorFilter(darkMapColorFilter)
                }

                val holder = mapView.getTag() as? MapOverlayHolder
                holder?.onTrafficSegmentSelected = { seg, geoPoint ->
                    trafficAnchorGeoPoint = geoPoint
                    destAnchorGeoPoint = null
                    val pt = Point()
                    mapView.projection?.toPixels(geoPoint, pt)
                    val anchor = Offset(pt.x.toFloat(), pt.y.toFloat())
                    activeCardState = MapInformationCardType.RouteTraffic(seg, anchor)
                    holder.isCardOpen = true
                }
                holder?.onDismissTraffic = {
                    if (activeCardState is MapInformationCardType.RouteTraffic) {
                        activeCardState = MapInformationCardType.None
                    }
                    holder.isCardOpen = false
                }
                holder?.onDestinationSelected = { dest, geoPoint ->
                    destAnchorGeoPoint = geoPoint
                    trafficAnchorGeoPoint = null
                    val pt = Point()
                    val density = mapView.context.resources.displayMetrics.density
                    mapView.projection?.toPixels(geoPoint, pt)
                    val anchor = Offset(pt.x.toFloat(), pt.y.toFloat() - (48f * density))
                    activeCardState = MapInformationCardType.DestinationInfo(dest, anchor)
                    holder.isCardOpen = true
                }
                holder?.onDismissDestination = {
                    if (activeCardState is MapInformationCardType.DestinationInfo) {
                        activeCardState = MapInformationCardType.None
                    }
                    holder.isCardOpen = false
                }
                if (isFollowMode && currentLat != null && currentLng != null && !hasCenteredOnFirstFix) {
                    hasCenteredOnFirstFix = true
                    mapView.controller?.setZoom(16.0)
                    mapView.controller?.setCenter(GeoPoint(currentLat, currentLng))
                    onCameraChanged?.invoke(currentLat, currentLng, 16.0)
                }

                holder?.update(
                    currentLat = currentLat,
                    currentLng = currentLng,
                    bearing = bearing,
                    speedKmh = speedKmh,
                    destination = destination,
                    alarmRadiusMeters = alarmRadiusMeters,
                    showAlarmRadius = showAlarmRadius,
                    routePoints = routePoints,
                    transportMode = transportMode,
                    tripState = tripState,
                    routeDistanceMeters = routeDistanceMeters,
                    routeDurationSeconds = routeDurationSeconds,
                    gpsQualityLabel = gpsQualityLabel,
                    trafficEnabled = trafficEnabled,
                    trafficData = trafficData
                )
            },
            onRelease = { mapView ->
                activeCardState = MapInformationCardType.None
                trafficAnchorGeoPoint = null
                destAnchorGeoPoint = null
                val center = mapView.mapCenter
                val zoom = mapView.zoomLevelDouble
                if (center != null && zoom != null) {
                    val lat = center.latitude
                    val lng = center.longitude
                    if (UserPreferences.isValidCameraState(lat, lng, zoom)) {
                        onSaveCameraImmediate?.invoke(lat, lng, zoom)
                    }
                }
                val holder = mapView.getTag() as? MapOverlayHolder
                holder?.release()
                mapView.onDetach()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Custom Animated MapInformationCard (Route Traffic and Destination Info with standard Compose transitions)
        MapInformationCard(
            cardState = activeCardState,
            transportMode = transportMode,
            destinationName = destination?.name,
            alarmRadiusMeters = alarmRadiusMeters,
            tripState = tripState,
            routeDistanceMeters = routeDistanceMeters,
            routeDurationSeconds = routeDurationSeconds,
            gpsQualityLabel = gpsQualityLabel,
            isNearDestination = isNearDestination,
            containerSize = containerSize,
            onStartTrip = {
                activeCardState = MapInformationCardType.None
                val h = mapViewRef?.getTag() as? MapOverlayHolder
                h?.isCardOpen = false
                onStartTrip?.invoke()
            },
            onDismiss = {
                activeCardState = MapInformationCardType.None
                val h = mapViewRef?.getTag() as? MapOverlayHolder
                h?.isCardOpen = false
            }
        )
    }
}

/**
 * Custom osmdroid Marker with smooth scale and fade-in entrance animation on placement.
 * Uses the destination marker's pin-tip anchor point as the canvas scale pivot so the tip
 * remains locked to the precise GPS coordinate while the pin smoothly pops and scales in.
 */
private class AnimatedDestinationMarker(mapView: MapView) : Marker(mapView) {
    var scale: Float = 1.0f

    override fun draw(canvas: Canvas, projection: Projection) {
        if (!isEnabled || icon == null) return
        val pos = position ?: return
        if (scale >= 0.99f && scale <= 1.01f) {
            super.draw(canvas, projection)
        } else {
            val pt = Point()
            projection.toPixels(pos, pt)
            canvas.save()
            // Anchor point (pin tip) is at (pt.x, pt.y)
            canvas.scale(scale, scale, pt.x.toFloat(), pt.y.toFloat())
            super.draw(canvas, projection)
            canvas.restore()
        }
    }
}

/**
 * Encapsulates persistent overlays to prevent recreation and memory churn on every GPS update.
 */
private class MapOverlayHolder(
    private val mapView: MapView,
    private val context: Context,
    private val onMapClick: (Double, Double) -> Unit
) {
    // Traffic selection callbacks to Compose layer
    var onTrafficSegmentSelected: ((TrafficSegment, GeoPoint) -> Unit)? = null
    var onDismissTraffic: (() -> Unit)? = null

    // Destination selection callbacks to Compose layer
    var onDestinationSelected: ((Destination, GeoPoint) -> Unit)? = null
    var onDismissDestination: (() -> Unit)? = null

    // Track whether any information card is currently open
    var isCardOpen: Boolean = false

    // Remaining-route progress tracking
    private var confirmedProgressMeters: Double = 0.0
    private var lastRenderedProgressMeters: Double = -1.0
    private var lastTripStateForRoute: TripState? = TripState.IDLE

    // Progressive route drawing animation
    private var routeDrawingAnimator: ValueAnimator? = null
    private var currentDrawFraction: Float = 1.0f
    private var lastAnimatedRouteSignature: String? = null

    // Incident Popup Callout & InfoWindow
    val incidentCardView = IncidentPopupCardView(context)
    val incidentInfoWindow = RouteWakeIncidentInfoWindow(incidentCardView, mapView)

    // Pool of polylines for traffic segments
    val trafficSegmentPolylines = mutableListOf<Polyline>()

    // Pool of markers for traffic incidents
    val trafficIncidentMarkers = mutableListOf<Marker>()

    // 2. Map Touch Event Overlay (Only selects destination when trip is IDLE, protecting active trips)
    val tapOverlay = MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
            val density = context.resources.displayMetrics.density
            val proj = mapView.projection

            // 1. Destination Marker Hit-Test (takes precedence over route hit-testing)
            val dest = lastDestination
            if (dest != null && p != null && proj != null) {
                val destPt = Point()
                proj.toPixels(GeoPoint(dest.latitude, dest.longitude), destPt)
                val tapPt = Point()
                proj.toPixels(p, tapPt)
                val dx = tapPt.x - destPt.x
                val dy = tapPt.y - (destPt.y - 25f * density)
                val hitRadius = 38f * density
                if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                    onDismissTraffic?.invoke()
                    if (incidentInfoWindow.isOpen) incidentInfoWindow.close()
                    InfoWindow.closeAllInfoWindowsOn(mapView)
                    onDestinationSelected?.invoke(dest, GeoPoint(dest.latitude, dest.longitude))
                    return true
                }
            }

            // 2. Traffic Route Segment Hit-Test
            if (p != null) {
                val hit = findNearestTrafficSegmentOrRoute(
                    tapPoint = p,
                    trafficSegments = if (lastTrafficEnabled) lastTrafficData?.segments ?: emptyList() else emptyList(),
                    routePoints = lastRoutePoints ?: emptyList(),
                    mapView = mapView,
                    tolerancePx = 28f * density
                )
                if (hit != null) {
                    onDismissDestination?.invoke()
                    openTrafficPopup(hit.first, hit.second)
                    return true
                }
            }

            // 3. Otherwise, if an information card is currently open: close it and consume tap
            if (isCardOpen) {
                isCardOpen = false
                onDismissDestination?.invoke()
                onDismissTraffic?.invoke()
                if (incidentInfoWindow.isOpen) {
                    incidentInfoWindow.close()
                }
                InfoWindow.closeAllInfoWindowsOn(mapView)
                return true
            }

            // 4. Empty map tap: dismiss any lingering overlays and select new destination only if trip is IDLE
            if (incidentInfoWindow.isOpen) {
                incidentInfoWindow.close()
            }
            InfoWindow.closeAllInfoWindowsOn(mapView)

            if (lastTripState == TripState.IDLE && p != null) {
                android.util.Log.d("RouteWakeTiming", "DESTINATION_TAP_RECEIVED: Map surface tapped at (${p.latitude}, ${p.longitude})")
                onMapClick(p.latitude, p.longitude)
            }
            return true
        }

        override fun longPressHelper(p: GeoPoint?): Boolean = false
    })

    // 3. OpenStreetMap Attribution Overlay
    val copyrightOverlay = CopyrightOverlay(context).apply {
        setAlignRight(true)
        setTextSize(9)
        setTextColor(0x88FFFFFF.toInt())
    }

    // 4. Dark Casing Polyline (underneath route line for high contrast)
    val routeCasingPolyline = Polyline(mapView).apply {
        outlinePaint.color = 0xFF080C14.toInt()
        outlinePaint.strokeWidth = 20f
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        infoWindow = null
    }

    // 5. Primary RouteWake Teal Route Polyline
    val routePolyline = Polyline(mapView).apply {
        outlinePaint.color = 0xFF14B8A6.toInt()
        outlinePaint.strokeWidth = 12f
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        infoWindow = null
    }

    // 6. Destination Alarm Radius Ring (The ONLY radius circle on map)
    val radiusPolygon = Polygon(mapView).apply {
        fillPaint.color = 0x16F59E0B.toInt()
        outlinePaint.color = 0xCCF59E0B.toInt()
        outlinePaint.strokeWidth = 4.5f
        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 12f), 0f)
        infoWindow = null
        isEnabled = false
    }

    // 7. Native Live GPS Puck (Electric Blue Beacon with Breathing Halo and Heading Cone)
    val userBeaconOverlay = UserBeaconOverlay(mapView)

    // 8. Multi-touch Map Rotation Gesture Overlay
    val rotationGestureOverlay = RotationGestureOverlay(mapView).apply {
        isEnabled = true
    }

    // 9. Destination Pin Marker with Vector Transport Icon and subtle scale/fade entrance animation
    val destMarker = AnimatedDestinationMarker(mapView).apply {
        setAnchor(0.5f, 49f / 52f) // Pin tip anchor precisely on destination coordinates
        infoWindow = null
        isEnabled = false
    }

    private var markerPlacementAnimator: ValueAnimator? = null

    private fun playMarkerPlacementAnimation() {
        markerPlacementAnimator?.cancel()
        destMarker.scale = 0.35f
        destMarker.alpha = 0.0f

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = OvershootInterpolator(1.35f)
            addUpdateListener { animator ->
                val progress = animator.animatedFraction
                val animVal = animator.animatedValue as Float
                // Subtle scale: start at 0.35, expand with a gentle overshoot bounce to 1.0
                destMarker.scale = (0.35f + 0.65f * animVal).coerceIn(0.1f, 1.35f)
                // Fade-in: complete opacity fade within the first 60% of duration
                destMarker.alpha = (progress / 0.6f).coerceIn(0.0f, 1.0f)
                mapView.postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    destMarker.scale = 1.0f
                    destMarker.alpha = 1.0f
                    mapView.postInvalidate()
                }
            })
            start()
        }
        markerPlacementAnimator = anim
    }

    private var lastDestination: Destination? = null
    private var lastDestLat: Double? = null
    private var lastDestLng: Double? = null
    private var lastRadius: Int = -1
    private var lastShowRadius: Boolean = false
    private var lastRoutePoints: List<RoutePoint>? = null
    private var lastTransportMode: TransportMode? = null
    private var lastTripState: TripState = TripState.IDLE
    private var lastRouteDistanceMeters: Double = 0.0
    private var lastRouteDurationSeconds: Long = 0L
    private var lastGpsQualityLabel: String = ""
    private var cachedDestDrawable: Drawable? = null

    init {
        // Overlay Z-order:
        // osmdroid dispatches touch events in reverse order (last overlay gets first touch).
        // Adding destMarker and userBeaconOverlay after tapOverlay ensures marker clicks are consumed first!
        mapView.overlays.add(tapOverlay)
        mapView.overlays.add(copyrightOverlay)
        mapView.overlays.add(routeCasingPolyline)
        mapView.overlays.add(routePolyline)
        mapView.overlays.add(radiusPolygon)
        mapView.overlays.add(userBeaconOverlay)
        mapView.overlays.add(destMarker)
        mapView.overlays.add(rotationGestureOverlay)

        // Completely disable default white osmdroid InfoWindows across all overlays
        destMarker.infoWindow = null
        radiusPolygon.infoWindow = null
        routePolyline.infoWindow = null
        routeCasingPolyline.infoWindow = null

        // Pass-through or interactive click listener for radiusPolygon
        radiusPolygon.setOnClickListener { _, _, eventPos ->
            val density = context.resources.displayMetrics.density
            val proj = mapView.projection
            val dest = lastDestination
            if (dest != null && eventPos != null && proj != null) {
                val destPt = Point()
                proj.toPixels(GeoPoint(dest.latitude, dest.longitude), destPt)
                val tapPt = Point()
                proj.toPixels(eventPos, tapPt)
                val dx = tapPt.x - destPt.x
                val dy = tapPt.y - (destPt.y - 25f * density)
                val hitRadius = 38f * density
                if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                    onDismissTraffic?.invoke()
                    if (incidentInfoWindow.isOpen) incidentInfoWindow.close()
                    InfoWindow.closeAllInfoWindowsOn(mapView)
                    onDestinationSelected?.invoke(dest, GeoPoint(dest.latitude, dest.longitude))
                    return@setOnClickListener true
                }
            }
            if (eventPos != null) {
                val hit = findNearestTrafficSegmentOrRoute(
                    tapPoint = eventPos,
                    trafficSegments = if (lastTrafficEnabled) lastTrafficData?.segments ?: emptyList() else emptyList(),
                    routePoints = lastRoutePoints ?: emptyList(),
                    mapView = mapView,
                    tolerancePx = 28f * density
                )
                if (hit != null) {
                    onDismissDestination?.invoke()
                    openTrafficPopup(hit.first, hit.second)
                    return@setOnClickListener true
                }
            }
            if (isCardOpen) {
                isCardOpen = false
                onDismissDestination?.invoke()
                onDismissTraffic?.invoke()
                if (incidentInfoWindow.isOpen) incidentInfoWindow.close()
                InfoWindow.closeAllInfoWindowsOn(mapView)
                return@setOnClickListener true
            }
            false
        }

        // Polyline taps open Route Traffic card
        routePolyline.setOnClickListener { _, _, eventPos ->
            if (eventPos != null) {
                onDismissDestination?.invoke()
                val density = context.resources.displayMetrics.density
                val hit = findNearestTrafficSegmentOrRoute(
                    tapPoint = eventPos,
                    trafficSegments = if (lastTrafficEnabled) lastTrafficData?.segments ?: emptyList() else emptyList(),
                    routePoints = lastRoutePoints ?: emptyList(),
                    mapView = mapView,
                    tolerancePx = 32f * density
                )
                if (hit != null) {
                    openTrafficPopup(hit.first, hit.second)
                } else {
                    val fallback = TrafficSegment(
                        points = lastRoutePoints ?: emptyList(),
                        congestionLevel = CongestionLevel.CLEAR
                    )
                    openTrafficPopup(fallback, eventPos)
                }
            }
            true
        }

        routeCasingPolyline.setOnClickListener { _, _, eventPos ->
            if (eventPos != null) {
                onDismissDestination?.invoke()
                val density = context.resources.displayMetrics.density
                val hit = findNearestTrafficSegmentOrRoute(
                    tapPoint = eventPos,
                    trafficSegments = if (lastTrafficEnabled) lastTrafficData?.segments ?: emptyList() else emptyList(),
                    routePoints = lastRoutePoints ?: emptyList(),
                    mapView = mapView,
                    tolerancePx = 32f * density
                )
                if (hit != null) {
                    openTrafficPopup(hit.first, hit.second)
                } else {
                    val fallback = TrafficSegment(
                        points = lastRoutePoints ?: emptyList(),
                        congestionLevel = CongestionLevel.CLEAR
                    )
                    openTrafficPopup(fallback, eventPos)
                }
            }
            true
        }

        // Ensure no default InfoWindows are open on MapView
        InfoWindow.closeAllInfoWindowsOn(mapView)

        // Tapping destination marker triggers custom DestinationInfoPopup without opening default InfoWindow
        destMarker.setOnMarkerClickListener { _, _ ->
            onDismissTraffic?.invoke()
            if (incidentInfoWindow.isOpen) incidentInfoWindow.close()
            InfoWindow.closeAllInfoWindowsOn(mapView)
            val dest = lastDestination
            if (dest != null) {
                onDestinationSelected?.invoke(dest, GeoPoint(dest.latitude, dest.longitude))
            }
            true
        }
    }

    fun openTrafficPopup(segment: TrafficSegment, anchorPos: GeoPoint) {
        if (incidentInfoWindow.isOpen) {
            incidentInfoWindow.close()
        }
        InfoWindow.closeAllInfoWindowsOn(mapView)
        onDismissDestination?.invoke()
        onTrafficSegmentSelected?.invoke(segment, anchorPos)
    }

    fun openIncidentPopup(incident: TrafficIncident, marker: Marker) {
        onDismissTraffic?.invoke()
        onDismissDestination?.invoke()
        InfoWindow.closeAllInfoWindowsOn(mapView)
        incidentCardView.bind(incident)
        val density = context.resources.displayMetrics.density
        val offsetY = -(32f * density).toInt()
        incidentInfoWindow.open(marker, marker.position, 0, offsetY)
    }

    private var lastTrafficEnabled: Boolean = true
    private var lastTrafficData: TrafficData? = null

    private fun routeBelongsToDestination(points: List<RoutePoint>?, dest: Destination?): Boolean {
        if (points.isNullOrEmpty() || dest == null) return false
        val lastPt = points.last()
        val dist = FloatArray(1)
        Location.distanceBetween(lastPt.latitude, lastPt.longitude, dest.latitude, dest.longitude, dist)
        return dist[0] <= 1500f
    }

    fun clearRouteOverlays() {
        routeDrawingAnimator?.cancel()
        routeDrawingAnimator = null
        currentDrawFraction = 0.0f
        lastAnimatedRouteSignature = null
        lastRenderedProgressMeters = -1.0
        confirmedProgressMeters = 0.0

        routeCasingPolyline.isEnabled = false
        routeCasingPolyline.setPoints(emptyList())

        routePolyline.isEnabled = false
        routePolyline.setPoints(emptyList())

        trafficSegmentPolylines.forEach {
            it.isEnabled = false
            it.setPoints(emptyList())
        }
        trafficIncidentMarkers.forEach {
            it.isEnabled = false
        }
        onDismissTraffic?.invoke()
        if (incidentInfoWindow.isOpen) incidentInfoWindow.close()
    }

    fun update(
        currentLat: Double?,
        currentLng: Double?,
        bearing: Float?,
        speedKmh: Double,
        destination: Destination?,
        alarmRadiusMeters: Int,
        showAlarmRadius: Boolean,
        routePoints: List<RoutePoint>,
        transportMode: TransportMode,
        tripState: TripState,
        routeDistanceMeters: Double,
        routeDurationSeconds: Long,
        gpsQualityLabel: String,
        trafficEnabled: Boolean = true,
        trafficData: TrafficData? = null
    ) {
        var needsInvalidate = false
        val destPositionChanged = destination != null &&
                (destination.latitude != lastDestLat || destination.longitude != lastDestLng)
        val destinationRemoved = destination == null && lastDestLat != null
        val destPropsChanged = destination != null &&
                (transportMode != lastTransportMode || alarmRadiusMeters != lastRadius || tripState != lastTripState)
        val routeChanged = routePoints != lastRoutePoints

        // When a destination changes or is removed, IMMEDIATELY purge all route overlays and reset drawing state
        if (destPositionChanged || destinationRemoved) {
            clearRouteOverlays()
            lastRoutePoints = null
            lastTrafficData = null
            lastTripStateForRoute = null
            needsInvalidate = true
        }

        // Reset progress on route or destination changes or when trip is idle
        if (routeChanged || destPositionChanged || tripState == TripState.IDLE) {
            confirmedProgressMeters = 0.0
            lastRenderedProgressMeters = -1.0
        }

        // Advance route progress along current route during active trip
        if ((tripState == TripState.TRACKING || tripState == TripState.WARMUP) &&
            currentLat != null && currentLng != null && routePoints.size >= 2
        ) {
            val (candidateProg, distToRoute) = RouteProgressHelper.calculateCandidateProgress(
                userLat = currentLat,
                userLng = currentLng,
                routePoints = routePoints,
                currentConfirmedProgressMeters = confirmedProgressMeters
            )
            val totalRouteDist = RouteProgressHelper.computeTotalRouteDistance(routePoints)
            confirmedProgressMeters = RouteProgressHelper.updateConfirmedProgress(
                currentConfirmedProgress = confirmedProgressMeters,
                candidateProgress = candidateProg,
                distanceToRoute = distToRoute,
                totalRouteDistance = totalRouteDist
            )
        }

        lastDestination = destination
        lastTransportMode = transportMode
        lastTripState = tripState
        lastRouteDistanceMeters = routeDistanceMeters
        lastRouteDurationSeconds = routeDurationSeconds
        lastGpsQualityLabel = gpsQualityLabel

        // Determine if user is near destination
        val isNearDestination = if (currentLat != null && currentLng != null && destination != null) {
            val dist = FloatArray(1)
            Location.distanceBetween(currentLat, currentLng, destination.latitude, destination.longitude, dist)
            dist[0] <= maxOf(alarmRadiusMeters.toFloat(), 500f)
        } else false

        // 1. Update User Location Marker (Smooth presentation smoothing with zero lag)
        if (currentLat != null && currentLng != null) {
            userBeaconOverlay.updateLocation(
                lat = currentLat,
                lng = currentLng,
                bearing = bearing,
                speedKmh = speedKmh,
                nearDest = isNearDestination
            )
            userBeaconOverlay.isEnabled = true
        } else {
            if (userBeaconOverlay.isEnabled) {
                userBeaconOverlay.isEnabled = false
                userBeaconOverlay.clearLocation()
                needsInvalidate = true
            }
        }

        // 2. Update Destination Marker & Geofence
        if (destination != null) {
            if (destPropsChanged || cachedDestDrawable == null) {
                cachedDestDrawable = createDestinationPinDrawable(
                    context = context,
                    transportMode = transportMode,
                    tripState = tripState
                )
                destMarker.icon = cachedDestDrawable
                needsInvalidate = true
            }

            if (destPositionChanged) {
                lastDestLat = destination.latitude
                lastDestLng = destination.longitude
                destMarker.position = GeoPoint(destination.latitude, destination.longitude)
                destMarker.isEnabled = true
                needsInvalidate = true
                android.util.Log.d("RouteWakeTiming", "MARKER_UPDATED: Destination marker placed at (${destination.latitude}, ${destination.longitude})")
                playMarkerPlacementAnimation()
            }

            // Update Destination Geofence (The ONLY radius circle)
            if (showAlarmRadius) {
                if (alarmRadiusMeters != lastRadius || !lastShowRadius || destPositionChanged || tripState != lastTripState) {
                    lastRadius = alarmRadiusMeters
                    lastShowRadius = true

                    radiusPolygon.points = Polygon.pointsAsCircle(
                        GeoPoint(destination.latitude, destination.longitude),
                        alarmRadiusMeters.toDouble()
                    )

                    // Adjust styling based on tracking/arrival state
                    when (tripState) {
                        TripState.ARRIVED -> {
                            radiusPolygon.fillPaint.color = 0x4CEF4444.toInt() // 30% crimson
                            radiusPolygon.outlinePaint.color = 0xFFEF4444.toInt()
                            radiusPolygon.outlinePaint.strokeWidth = 7f
                            radiusPolygon.outlinePaint.pathEffect = null // Solid ring on arrival
                        }
                        TripState.TRACKING, TripState.WARMUP -> {
                            radiusPolygon.fillPaint.color = 0x2CF59E0B.toInt() // Armed 17% amber
                            radiusPolygon.outlinePaint.color = 0xFFF59E0B.toInt()
                            radiusPolygon.outlinePaint.strokeWidth = 6f
                            radiusPolygon.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 12f), 0f)
                        }
                        else -> {
                            radiusPolygon.fillPaint.color = 0x16F59E0B.toInt() // Subtle 10% amber
                            radiusPolygon.outlinePaint.color = 0xCCF59E0B.toInt()
                            radiusPolygon.outlinePaint.strokeWidth = 4.5f
                            radiusPolygon.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 12f), 0f)
                        }
                    }

                    radiusPolygon.isEnabled = true
                    needsInvalidate = true
                }
            } else {
                if (lastShowRadius) {
                    lastShowRadius = false
                    radiusPolygon.isEnabled = false
                    needsInvalidate = true
                }
            }
        } else {
            if (lastDestLat != null) {
                markerPlacementAnimator?.cancel()
                destMarker.scale = 1.0f
                destMarker.alpha = 1.0f
                lastDestLat = null
                lastDestLng = null
                destMarker.isEnabled = false
                radiusPolygon.isEnabled = false
                onDismissTraffic?.invoke()
                needsInvalidate = true
            }
        }

        // 3. Update Route & Traffic Visualization Overlays
        val isRouteForCurrentDestination = destination != null && routeBelongsToDestination(routePoints, destination)
        val validRoutePoints = if (isRouteForCurrentDestination) routePoints else emptyList()

        if (validRoutePoints.isEmpty() || destination == null || tripState == TripState.ARRIVED) {
            clearRouteOverlays()
            lastRoutePoints = emptyList()
            lastTrafficEnabled = trafficEnabled
            lastTrafficData = trafficData
            lastTripStateForRoute = tripState
            needsInvalidate = true
        } else {
            val routeChanged = (validRoutePoints != lastRoutePoints)
            val trafficStateChanged = (trafficEnabled != lastTrafficEnabled) ||
                    (trafficData != lastTrafficData) ||
                    routeChanged ||
                    (tripState != lastTripStateForRoute)

            val progressAdvanced = Math.abs(confirmedProgressMeters - lastRenderedProgressMeters) >= 3.0

            lastTrafficEnabled = trafficEnabled
            lastTrafficData = trafficData
            lastRoutePoints = validRoutePoints
            lastTripStateForRoute = tripState

            if (tripState == TripState.IDLE) {
                // Route drawing progressive animation when destination is selected in IDLE state
                val routeSig = "${destination.latitude},${destination.longitude}_${validRoutePoints.size}_${validRoutePoints.first().latitude},${validRoutePoints.first().longitude}_${validRoutePoints.last().latitude},${validRoutePoints.last().longitude}"
                val isNewRoute = routeSig != lastAnimatedRouteSignature

                if (isNewRoute) {
                    clearRouteOverlays()
                    lastAnimatedRouteSignature = routeSig

                    val totalDist = RouteProgressHelper.computeTotalRouteDistance(validRoutePoints)
                    android.util.Log.d("RouteWakeTiming", "ROUTE_RENDER_STARTED: points=${validRoutePoints.size}, dist=${totalDist}m")
                    if (totalDist < 5.0) {
                        currentDrawFraction = 1.0f
                        renderRoute(1.0f)
                        needsInvalidate = true
                    } else {
                        val animDuration = when {
                            totalDist < 3000.0 -> 700L
                            totalDist < 10000.0 -> 900L
                            else -> 1100L
                        }
                        currentDrawFraction = 0.0f
                        renderRoute(0.0f)
                        needsInvalidate = true

                        android.util.Log.d("RouteWakeTiming", "ROUTE_ANIMATION_STARTED: duration=${animDuration}ms")
                        routeDrawingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = animDuration
                            interpolator = DecelerateInterpolator(1.4f)
                            addUpdateListener { anim ->
                                val fraction = anim.animatedValue as Float
                                currentDrawFraction = fraction
                                renderRoute(fraction)
                                mapView.invalidate()
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    currentDrawFraction = 1.0f
                                    renderRoute(1.0f)
                                    mapView.invalidate()
                                    routeDrawingAnimator = null
                                    android.util.Log.d("RouteWakeTiming", "ROUTE_ANIMATION_COMPLETED: route fully revealed")
                                }
                            })
                            start()
                        }
                    }
                } else if (trafficStateChanged && routeDrawingAnimator?.isRunning != true) {
                    renderRoute(currentDrawFraction)
                    needsInvalidate = true
                }
            } else {
                // Active trip navigation (TRACKING, WARMUP)
                if (routeDrawingAnimator?.isRunning == true) {
                    routeDrawingAnimator?.cancel()
                    routeDrawingAnimator = null
                }
                currentDrawFraction = 1.0f

                if (trafficStateChanged || progressAdvanced) {
                    lastRenderedProgressMeters = confirmedProgressMeters
                    renderRoute(1.0f)
                    needsInvalidate = true
                }
            }
        }

        if (needsInvalidate) {
            mapView.invalidate()
        }
    }

    private fun renderRoute(drawFraction: Float) {
        val points = lastRoutePoints
        val dest = lastDestination
        if (points.isNullOrEmpty() || points.size < 2 || dest == null || lastTripState == TripState.ARRIVED) {
            clearRouteOverlays()
            return
        }

        val displayRoutePoints: List<RoutePoint>
        val displayTrafficSegments: List<TrafficSegment>

        if (lastTripState == TripState.IDLE) {
            if (drawFraction <= 0.001f) {
                routeCasingPolyline.isEnabled = false
                routeCasingPolyline.setPoints(emptyList())
                routePolyline.isEnabled = false
                routePolyline.setPoints(emptyList())
                trafficSegmentPolylines.forEach {
                    it.isEnabled = false
                    it.setPoints(emptyList())
                }
                trafficIncidentMarkers.forEach { it.isEnabled = false }
                return
            }
            val totalDist = RouteProgressHelper.computeTotalRouteDistance(points)
            if (drawFraction >= 0.999f) {
                displayRoutePoints = points
                displayTrafficSegments = lastTrafficData?.segments ?: emptyList()
            } else {
                val revealedMeters = (drawFraction * totalDist).toDouble()
                displayRoutePoints = RouteProgressHelper.computeRevealedRoutePoints(points, revealedMeters)
                displayTrafficSegments = if (lastTrafficData != null) {
                    RouteProgressHelper.computeRevealedTrafficSegments(lastTrafficData!!.segments, points, revealedMeters)
                } else emptyList()
            }
        } else {
            // Active navigation (TRACKING, WARMUP) - slice behind user
            val effectiveProgress = confirmedProgressMeters
            displayRoutePoints = RouteProgressHelper.computeRemainingRoutePoints(points, effectiveProgress)
            displayTrafficSegments = if (lastTrafficData != null) {
                RouteProgressHelper.computeRemainingTrafficSegments(lastTrafficData!!.segments, points, effectiveProgress)
            } else emptyList()
        }

        val displayGeoPoints = displayRoutePoints.map { GeoPoint(it.latitude, it.longitude) }
        val hasTraffic = lastTrafficEnabled && lastTrafficData != null && lastTrafficData!!.segments.isNotEmpty()

        if (!hasTraffic) {
            routeCasingPolyline.isEnabled = false
            routeCasingPolyline.setPoints(emptyList())
            trafficSegmentPolylines.forEach {
                it.isEnabled = false
                it.setPoints(emptyList())
            }
            trafficIncidentMarkers.forEach { it.isEnabled = false }
            onDismissTraffic?.invoke()
            if (incidentInfoWindow.isOpen) incidentInfoWindow.close()

            if (displayGeoPoints.size >= 2) {
                routePolyline.outlinePaint.color = 0xD914B8A6.toInt()
                routePolyline.outlinePaint.strokeWidth = 12f
                routePolyline.setPoints(displayGeoPoints)
                routePolyline.isEnabled = true
            } else {
                routePolyline.isEnabled = false
                routePolyline.setPoints(emptyList())
            }
        } else {
            routePolyline.isEnabled = false
            routePolyline.setPoints(emptyList())

            // 1. Casing underneath revealed route
            if (displayGeoPoints.size >= 2) {
                routeCasingPolyline.outlinePaint.color = 0xB30F172A.toInt()
                routeCasingPolyline.outlinePaint.strokeWidth = 20f
                routeCasingPolyline.setPoints(displayGeoPoints)
                routeCasingPolyline.isEnabled = true
            } else {
                routeCasingPolyline.isEnabled = false
                routeCasingPolyline.setPoints(emptyList())
            }

            // 2. Traffic colored segments for revealed portion
            while (trafficSegmentPolylines.size < displayTrafficSegments.size) {
                val poly = Polyline(mapView).apply {
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    infoWindow = null
                }
                val idx = mapView.overlays.indexOf(routePolyline)
                if (idx >= 0) {
                    mapView.overlays.add(idx + 1 + trafficSegmentPolylines.size, poly)
                } else {
                    mapView.overlays.add(poly)
                }
                trafficSegmentPolylines.add(poly)
            }

            for (i in trafficSegmentPolylines.indices) {
                val poly = trafficSegmentPolylines[i]
                if (i < displayTrafficSegments.size) {
                    val seg = displayTrafficSegments[i]
                    poly.outlinePaint.color = (0xFF000000 or seg.congestionLevel.colorHex).toInt()
                    poly.outlinePaint.strokeWidth = 14f
                    poly.setPoints(seg.points.map { GeoPoint(it.latitude, it.longitude) })
                    poly.setOnClickListener { _, _, eventPos ->
                        val anchor = eventPos ?: (seg.points.firstOrNull()?.let { GeoPoint(it.latitude, it.longitude) }) ?: (mapView.mapCenter as GeoPoint)
                        openTrafficPopup(seg, anchor)
                        true
                    }
                    poly.isEnabled = seg.points.size >= 2
                } else {
                    poly.isEnabled = false
                    poly.setPoints(emptyList())
                }
            }

            // 3. Traffic incidents
            val incidents = lastTrafficData?.incidents ?: emptyList()
            while (trafficIncidentMarkers.size < incidents.size) {
                val marker = Marker(mapView).apply {
                    infoWindow = null
                }
                mapView.overlays.add(marker)
                trafficIncidentMarkers.add(marker)
            }

            for (i in trafficIncidentMarkers.indices) {
                val marker = trafficIncidentMarkers[i]
                if (i < incidents.size) {
                    val incident = incidents[i]
                    marker.position = GeoPoint(incident.latitude, incident.longitude)
                    marker.icon = createIncidentMarkerDrawable(context, incident)
                    marker.setAnchor(0.5f, 0.5f)
                    marker.setOnMarkerClickListener { _, _ ->
                        openIncidentPopup(incident, marker)
                        true
                    }
                    marker.isEnabled = true
                } else {
                    marker.isEnabled = false
                }
            }
        }
    }

    fun release() {
        markerPlacementAnimator?.cancel()
        markerPlacementAnimator = null
        routeDrawingAnimator?.cancel()
        routeDrawingAnimator = null
        onDismissDestination?.invoke()
        onDismissTraffic?.invoke()
        if (incidentInfoWindow.isOpen) {
            incidentInfoWindow.close()
        }
        incidentInfoWindow.onDetach()
        userBeaconOverlay.release()
    }
}

/**
 * Custom osmdroid Overlay that renders the live GPS puck:
 * - Electric-blue core with crisp white border and specular highlight.
 * - Subtle breathing / pulsing radar halo.
 * - Directional heading cone when device has reliable bearing.
 * - Smooth visual interpolation between authoritative fixes without GPS lag.
 */
private class UserBeaconOverlay(
    private val mapView: MapView
) : Overlay() {

    private var targetLat: Double? = null
    private var targetLng: Double? = null
    private var displayLat: Double? = null
    private var displayLng: Double? = null

    private var bearing: Float? = null
    private var isReliableBearing: Boolean = false
    private var lastFixTimeMs: Long = 0L

    private var animator: ValueAnimator? = null

    private val point = Point()
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val freshFixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val darkRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF090D16.toInt()
    }
    private val whiteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF38BDF8.toInt()
    }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val headingConePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val headingPath = Path()
    private val pointerPath = Path()
    private val pointerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0284C7.toInt() // Deep sky blue
    }
    private val pointerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    fun updateLocation(
        lat: Double,
        lng: Double,
        bearing: Float?,
        speedKmh: Double,
        nearDest: Boolean
    ) {
        val now = SystemClock.uptimeMillis()
        lastFixTimeMs = now

        // Reliable bearing check: show directional indicator when bearing is provided (from GPS bearing or device compass)
        val reliable = (bearing != null && !bearing.isNaN())
        this.bearing = bearing
        this.isReliableBearing = reliable

        if (displayLat == null || displayLng == null) {
            displayLat = lat
            displayLng = lng
            targetLat = lat
            targetLng = lng
            mapView.postInvalidate()
            return
        }

        val dist = FloatArray(1)
        Location.distanceBetween(displayLat!!, displayLng!!, lat, lng, dist)
        val jumpDistMeters = dist[0]

        // If movement is negligible (< 0.25m), skip animation and do a single light redraw
        if (jumpDistMeters < 0.25f) {
            displayLat = lat
            displayLng = lng
            targetLat = lat
            targetLng = lng
            mapView.postInvalidate()
            return
        }

        // If jump is large (> 75m) or user is near destination, prioritize freshness over animation
        if (jumpDistMeters > 75f || nearDest) {
            animator?.cancel()
            displayLat = lat
            displayLng = lng
            targetLat = lat
            targetLng = lng
            mapView.postInvalidate()
        } else {
            // Presentation smoothing only: smooth slide over 220ms
            val startLat = displayLat!!
            val startLng = displayLng!!
            targetLat = lat
            targetLng = lng

            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220L
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val fraction = va.animatedFraction
                    displayLat = startLat + fraction * (lat - startLat)
                    displayLng = startLng + fraction * (lng - startLng)
                    mapView.postInvalidate()
                }
                start()
            }
        }
    }

    fun clearLocation() {
        animator?.cancel()
        animator = null
        displayLat = null
        displayLng = null
        targetLat = null
        targetLng = null
        bearing = null
        mapView.postInvalidate()
    }

    override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
        if (e == null || displayLat == null || displayLng == null) return false
        val projection = mapView?.projection ?: return false
        projection.toPixels(GeoPoint(displayLat!!, displayLng!!), point)
        val dx = e.x - point.x
        val dy = e.y - point.y
        val hitRadius = 28f * (mapView?.context?.resources?.displayMetrics?.density ?: 1f)
        if (dx * dx + dy * dy <= hitRadius * hitRadius) {
            return true // Consume tap on GPS puck so it doesn't create a destination
        }
        return false
    }

    override fun draw(pCanvas: Canvas?, pMapView: MapView?, pShadow: Boolean) {
        if (pShadow || pCanvas == null || pMapView == null) return
        val dLat = displayLat ?: return
        val dLng = displayLng ?: return

        pMapView.projection.toPixels(GeoPoint(dLat, dLng), point)
        val cx = point.x.toFloat()
        val cy = point.y.toFloat()

        // Check if on screen
        if (cx < -100f || cy < -100f || cx > pMapView.width + 100f || cy > pMapView.height + 100f) {
            return
        }

        val density = pMapView.context.resources.displayMetrics.density

        // 1. Directional Heading Indicator / Cone (Subtle & visually restrained)
        if (isReliableBearing && bearing != null) {
            pCanvas.save()
            pCanvas.rotate(bearing!!, cx, cy)

            val coneLength = 38f * density
            headingPath.reset()
            headingPath.moveTo(cx, cy)
            headingPath.lineTo(cx - 16f * density, cy - coneLength)
            headingPath.lineTo(cx, cy - coneLength - 4f * density)
            headingPath.lineTo(cx + 16f * density, cy - coneLength)
            headingPath.close()

            headingConePaint.shader = RadialGradient(
                cx, cy, coneLength + 4f * density,
                intArrayOf(0x6638BDF8.toInt(), 0x2238BDF8.toInt(), 0x0038BDF8.toInt()),
                floatArrayOf(0.1f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            pCanvas.drawPath(headingPath, headingConePaint)
            pCanvas.restore()
        }

        // 2. Halo (Subtle RouteWake radar glow without infinite invalidate loop)
        val haloRadius = 18f * density
        haloPaint.color = 0x2E38BDF8.toInt()
        haloBorderPaint.color = 0x5538BDF8.toInt()
        haloBorderPaint.strokeWidth = 1.2f * density

        pCanvas.drawCircle(cx, cy, haloRadius, haloPaint)
        pCanvas.drawCircle(cx, cy, haloRadius, haloBorderPaint)

        // 3. Fresh-Fix Live Indicator (Active telemetry ring within 1.2s of new fix)
        val now = SystemClock.uptimeMillis()
        if (now - lastFixTimeMs < 1200L) {
            freshFixPaint.color = 0x8838BDF8.toInt()
            freshFixPaint.strokeWidth = 2.2f * density
            pCanvas.drawCircle(cx, cy, 13.5f * density, freshFixPaint)
        }

        // 4. Dark rim
        pCanvas.drawCircle(cx, cy, 11f * density, darkRimPaint)

        // 5. White outer border
        pCanvas.drawCircle(cx, cy, 9.5f * density, whiteBorderPaint)

        // 6. Electric Blue RouteWake core
        pCanvas.drawCircle(cx, cy, 7.5f * density, corePaint)

        // 7. Specular highlight point in center
        pCanvas.drawCircle(cx, cy, 2.5f * density, specularPaint)

        // 8. Explicit Directional Pointer Arrow (prominently showing exact orientation)
        if (isReliableBearing && bearing != null) {
            pCanvas.save()
            pCanvas.rotate(bearing!!, cx, cy)

            pointerPath.reset()
            // Tip pointing upward (towards bearing)
            pointerPath.moveTo(cx, cy - 17f * density)
            // Right outer fin
            pointerPath.lineTo(cx + 7.5f * density, cy - 6f * density)
            // Inner notch
            pointerPath.lineTo(cx, cy - 9.5f * density)
            // Left outer fin
            pointerPath.lineTo(cx - 7.5f * density, cy - 6f * density)
            pointerPath.close()

            // Outer crisp white outline
            pointerBorderPaint.strokeWidth = 2.4f * density
            pCanvas.drawPath(pointerPath, pointerBorderPaint)

            // Inner vibrant sky-blue fill
            pointerFillPaint.color = 0xFF0284C7.toInt()
            pCanvas.drawPath(pointerPath, pointerFillPaint)

            pCanvas.restore()
        }
    }

    fun release() {
        animator?.cancel()
        animator = null
    }
}

/**
 * Creates a high-polish native Destination Pin Marker:
 * - Teardrop pin in RouteWake Amber (#F59E0B) or Alarm Crimson (#EF4444) upon arrival.
 * - Crisp dark outline for contrast against both dark cockpit and satellite imagery.
 * - Clean vector transport icon inside the pin head.
 */
private fun createDestinationPinDrawable(
    context: Context,
    transportMode: TransportMode,
    tripState: TripState
): Drawable {
    val density = context.resources.displayMetrics.density
    val isArrived = tripState == TripState.ARRIVED
    val isTracking = tripState == TripState.TRACKING || tripState == TripState.WARMUP

    val widthPx = (42 * density).toInt()
    val heightPx = (52 * density).toInt()
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val centerX = widthPx / 2f
    val pinTipY = heightPx - (3f * density)
    val pinHeadCenterY = 17f * density
    val pinHeadRadius = 14f * density

    // 1. Teardrop Map Pin Path
    val pinPath = Path().apply {
        moveTo(centerX, pinTipY)
        quadTo(
            centerX - (pinHeadRadius * 1.25f),
            pinHeadCenterY + (pinHeadRadius * 0.75f),
            centerX - pinHeadRadius,
            pinHeadCenterY
        )
        arcTo(
            RectF(
                centerX - pinHeadRadius,
                pinHeadCenterY - pinHeadRadius,
                centerX + pinHeadRadius,
                pinHeadCenterY + pinHeadRadius
            ),
            180f,
            180f
        )
        quadTo(
            centerX + (pinHeadRadius * 1.25f),
            pinHeadCenterY + (pinHeadRadius * 0.75f),
            centerX,
            pinTipY
        )
        close()
    }

    // Pin Drop Shadow beneath tip
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55000000
    }
    canvas.drawOval(
        RectF(
            centerX - (9f * density),
            pinTipY - (2f * density),
            centerX + (9f * density),
            pinTipY + (3f * density)
        ),
        shadowPaint
    )

    // Pin Body Fill
    val pinColor = when {
        isArrived -> 0xFFEF4444.toInt() // Crimson
        isTracking -> 0xFFF59E0B.toInt() // Armed Amber
        else -> 0xFFF59E0B.toInt() // Amber
    }
    val pinBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pinColor
    }
    canvas.drawPath(pinPath, pinBodyPaint)

    // Pin Dark Outline
    val pinOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = 0xFF090D16.toInt()
    }
    canvas.drawPath(pinPath, pinOutlinePaint)

    // Pin Head Inner Dark Circle
    val headInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0F172A.toInt()
    }
    canvas.drawCircle(centerX, pinHeadCenterY, 9.5f * density, headInnerPaint)

    // 2. Vector Transport Mode Icon inside pin head
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = if (isArrived) 0xFFF87171.toInt() else 0xFFFDE68A.toInt()
    }
    val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (isArrived) 0xFFF87171.toInt() else 0xFFFDE68A.toInt()
    }

    if (isArrived) {
        val checkPath = Path().apply {
            moveTo(centerX - (4.5f * density), pinHeadCenterY)
            lineTo(centerX - (1f * density), pinHeadCenterY + (3f * density))
            lineTo(centerX + (4.5f * density), pinHeadCenterY - (3f * density))
        }
        canvas.drawPath(checkPath, iconPaint)
    } else {
        when (transportMode) {
            TransportMode.WALK -> {
                canvas.drawCircle(centerX, pinHeadCenterY - (4f * density), 1.6f * density, iconFillPaint)
                val body = Path().apply {
                    moveTo(centerX, pinHeadCenterY - (1.8f * density))
                    lineTo(centerX, pinHeadCenterY + (1.2f * density))
                    lineTo(centerX - (2.5f * density), pinHeadCenterY + (4.5f * density))
                    moveTo(centerX, pinHeadCenterY + (1.2f * density))
                    lineTo(centerX + (2.5f * density), pinHeadCenterY + (4.5f * density))
                }
                canvas.drawPath(body, iconPaint)
            }
            TransportMode.CYCLE -> {
                canvas.drawCircle(centerX - (3.5f * density), pinHeadCenterY + (1.8f * density), 2.2f * density, iconPaint)
                canvas.drawCircle(centerX + (3.5f * density), pinHeadCenterY + (1.8f * density), 2.2f * density, iconPaint)
                val bike = Path().apply {
                    moveTo(centerX - (3.5f * density), pinHeadCenterY + (1.8f * density))
                    lineTo(centerX, pinHeadCenterY - (1.2f * density))
                    lineTo(centerX + (3.5f * density), pinHeadCenterY + (1.8f * density))
                    moveTo(centerX, pinHeadCenterY - (1.2f * density))
                    lineTo(centerX + (1.8f * density), pinHeadCenterY - (3.5f * density))
                }
                canvas.drawPath(bike, iconPaint)
            }
            TransportMode.BUS -> {
                val busRect = RectF(
                    centerX - (5f * density),
                    pinHeadCenterY - (4.5f * density),
                    centerX + (5f * density),
                    pinHeadCenterY + (4.5f * density)
                )
                canvas.drawRoundRect(busRect, 1.8f * density, 1.8f * density, iconPaint)
                canvas.drawLine(
                    centerX - (3.5f * density), pinHeadCenterY - (1.2f * density),
                    centerX + (3.5f * density), pinHeadCenterY - (1.2f * density),
                    iconPaint
                )
                canvas.drawCircle(centerX - (3f * density), pinHeadCenterY + (2.8f * density), 0.7f * density, iconFillPaint)
                canvas.drawCircle(centerX + (3f * density), pinHeadCenterY + (2.8f * density), 0.7f * density, iconFillPaint)
            }
            TransportMode.TRAIN -> {
                val trainRect = RectF(
                    centerX - (4.5f * density),
                    pinHeadCenterY - (5f * density),
                    centerX + (4.5f * density),
                    pinHeadCenterY + (3.8f * density)
                )
                canvas.drawRoundRect(trainRect, 2f * density, 2f * density, iconPaint)
                canvas.drawCircle(centerX, pinHeadCenterY - (3.2f * density), 1f * density, iconFillPaint)
                canvas.drawLine(
                    centerX - (3f * density), pinHeadCenterY - (0.5f * density),
                    centerX + (3f * density), pinHeadCenterY - (0.5f * density),
                    iconPaint
                )
            }
            TransportMode.CAR -> {
                val carPath = Path().apply {
                    moveTo(centerX - (5.5f * density), pinHeadCenterY + (1.8f * density))
                    lineTo(centerX - (4.5f * density), pinHeadCenterY - (0.5f * density))
                    lineTo(centerX - (2.5f * density), pinHeadCenterY - (3.2f * density))
                    lineTo(centerX + (2.2f * density), pinHeadCenterY - (3.2f * density))
                    lineTo(centerX + (4f * density), pinHeadCenterY - (0.5f * density))
                    lineTo(centerX + (5.5f * density), pinHeadCenterY + (1.8f * density))
                    close()
                }
                canvas.drawPath(carPath, iconPaint)
                canvas.drawCircle(centerX - (3f * density), pinHeadCenterY + (3.2f * density), 1.2f * density, iconFillPaint)
                canvas.drawCircle(centerX + (3.5f * density), pinHeadCenterY + (3.2f * density), 1.2f * density, iconFillPaint)
            }
        }
    }

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * Modern custom RouteWake traffic information popup callout:
 * - Appears ONLY when the user taps directly on a ROUTE PATH / TRAFFIC ROUTE SEGMENT.
 * - Dark glass card (#0F172A) with subtle border (#334155) and rounded corners (14dp).
 * - Downward pointer triangle anchored precisely to the tapped route point.
 * - Header: Traffic status dot (🟢/🟡/🔴) + "Traffic" title + Close '✕' button.
 * - Road Name (e.g. "Bannerghatta Road") in Electric Blue / White.
 * - Congestion Status Badge (e.g. "MODERATE TRAFFIC").
 * - Speed metrics: current speed (e.g. "🚗 28 km/h") & normal speed (e.g. "Normal 45 km/h").
 * - Metrics: delay (e.g. "⏱ +7 min delay") & affected distance (e.g. "📍 2.4 km affected").
 * - Incident/Warning badge (e.g. "⚠ Congestion ahead" or "⚠ Accident ahead").
 * - Strictly displays ONLY available fields. Never invents data.
 */
private class TrafficPopupCardView(context: Context) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density

    private val statusDotView: View
    private val titleTextView: TextView
    private val closeButton: TextView
    private val roadNameTextView: TextView
    private val statusBadgeView: TextView
    private val speedRow: LinearLayout
    private val currentSpeedTextView: TextView
    private val normalSpeedTextView: TextView
    private val metricsRow: LinearLayout
    private val delayBadgeView: TextView
    private val distanceBadgeView: TextView
    private val warningRow: LinearLayout
    private val warningIconView: TextView
    private val warningTextView: TextView
    private val warningDescTextView: TextView

    var onCloseClicked: (() -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF80F172A.toInt() // Rich deep dark glass navy #0F172A
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFF334155.toInt() // Slate border #334155
    }

    private val pointerPath = Path()

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        setOnClickListener { /* consumed */ }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (14 * density).toInt()
            val padTop = (12 * density).toInt()
            val padBottom = (20 * density).toInt() // extra space for bottom pointer
            setPadding(padH, padTop, padH, padBottom)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        // 1. Header Row (Status dot + "Traffic" Title + Close X)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusDotView = View(context).apply {
            val s = (9 * density).toInt()
            val lp = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (7 * density).toInt()
            }
            layoutParams = lp
        }

        titleTextView = TextView(context).apply {
            text = "Traffic"
            setTextColor(0xFFF8FAFC.toInt())
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }

        closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                onCloseClicked?.invoke()
            }
        }

        headerRow.addView(statusDotView)
        headerRow.addView(titleTextView)
        headerRow.addView(closeButton)
        container.addView(headerRow)

        // 2. Road Name (e.g. "Bannerghatta Road")
        roadNameTextView = TextView(context).apply {
            setTextColor(0xFF38BDF8.toInt()) // Electric blue
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
            layoutParams = lp
            visibility = View.GONE
        }
        container.addView(roadNameTextView)

        // 3. Status Badge (e.g. "MODERATE TRAFFIC")
        statusBadgeView = TextView(context).apply {
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val padH = (8 * density).toInt()
            val padV = (3 * density).toInt()
            setPadding(padH, padV, padH, padV)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (7 * density).toInt()
            }
            layoutParams = lp
        }
        container.addView(statusBadgeView)

        // 4. Speed Row ("🚗 28 km/h" & "Normal 45 km/h")
        speedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (7 * density).toInt()
            }
            layoutParams = lp
            visibility = View.GONE
        }

        currentSpeedTextView = TextView(context).apply {
            setTextColor(0xFFF8FAFC.toInt())
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        normalSpeedTextView = TextView(context).apply {
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11.5f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (10 * density).toInt()
            }
            layoutParams = lp
        }

        speedRow.addView(currentSpeedTextView)
        speedRow.addView(normalSpeedTextView)
        container.addView(speedRow)

        // 5. Metrics Row ("⏱ +7 min delay" & "📍 2.4 km affected")
        metricsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (7 * density).toInt()
            }
            layoutParams = lp
            visibility = View.GONE
        }

        delayBadgeView = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val padH = (6 * density).toInt()
            val padV = (2 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        distanceBadgeView = TextView(context).apply {
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 11f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
            layoutParams = lp
        }

        metricsRow.addView(delayBadgeView)
        metricsRow.addView(distanceBadgeView)
        container.addView(metricsRow)

        // 6. Incident / Warning Row ("⚠ Congestion ahead")
        warningRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (7 * density).toInt()
            }
            layoutParams = lp
            visibility = View.GONE
        }

        val warningHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        warningIconView = TextView(context).apply {
            text = "⚠ "
            textSize = 11.5f
        }

        warningTextView = TextView(context).apply {
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        warningHeader.addView(warningIconView)
        warningHeader.addView(warningTextView)
        warningRow.addView(warningHeader)

        warningDescTextView = TextView(context).apply {
            setTextColor(0xFF94A3B8.toInt())
            textSize = 10.5f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (2 * density).toInt()
                marginStart = (14 * density).toInt()
            }
            layoutParams = lp
            visibility = View.GONE
        }
        warningRow.addView(warningDescTextView)
        container.addView(warningRow)

        addView(container)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = (270 * density).toInt()
        val specMode = MeasureSpec.getMode(widthMeasureSpec)
        val specSize = MeasureSpec.getSize(widthMeasureSpec)
        val constrainedSize = if (specMode == MeasureSpec.UNSPECIFIED) maxW else minOf(specSize, maxW)
        val constrainedWidthSpec = MeasureSpec.makeMeasureSpec(constrainedSize, MeasureSpec.AT_MOST)
        super.onMeasure(constrainedWidthSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pointerH = 10f * density
        val pointerW = 16f * density
        val r = 14f * density
        val stroke = 1.5f * density
        val halfStroke = stroke / 2f

        val left = halfStroke
        val top = halfStroke
        val right = w - halfStroke
        val cardBottom = h - pointerH
        val cx = w / 2f
        val pw = pointerW / 2f
        val bottomTip = h - halfStroke

        pointerPath.reset()
        // Start top-left
        pointerPath.moveTo(left + r, top)
        // Top edge
        pointerPath.lineTo(right - r, top)
        pointerPath.quadTo(right, top, right, top + r)
        // Right edge
        pointerPath.lineTo(right, cardBottom - r)
        pointerPath.quadTo(right, cardBottom, right - r, cardBottom)
        // Bottom edge to pointer start
        pointerPath.lineTo(cx + pw, cardBottom)
        // Triangle pointer down to tip
        pointerPath.lineTo(cx, bottomTip)
        // Pointer back up to bottom edge
        pointerPath.lineTo(cx - pw, cardBottom)
        // Bottom edge left
        pointerPath.lineTo(left + r, cardBottom)
        pointerPath.quadTo(left, cardBottom, left, cardBottom - r)
        // Left edge
        pointerPath.lineTo(left, top + r)
        pointerPath.quadTo(left, top, left + r, top)
        pointerPath.close()

        canvas.drawPath(pointerPath, bgPaint)
        canvas.drawPath(pointerPath, borderPaint)

        super.onDraw(canvas)
    }

    fun bind(segment: TrafficSegment) {
        val level = segment.congestionLevel
        val colorInt = (0xFF000000L or level.colorHex).toInt()
        val colorBg = (0x26000000L or (level.colorHex and 0x00FFFFFFL)).toInt()
        val strokeColor = (0x66000000L or (level.colorHex and 0x00FFFFFFL)).toInt()

        // 1. Status Dot
        statusDotView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorInt)
        }

        // 2. Road Name
        if (!segment.roadName.isNullOrBlank()) {
            roadNameTextView.text = segment.roadName
            roadNameTextView.visibility = View.VISIBLE
        } else {
            roadNameTextView.visibility = View.GONE
        }

        // 3. Status Badge
        statusBadgeView.text = "${level.name} TRAFFIC"
        statusBadgeView.setTextColor(colorInt)
        statusBadgeView.background = GradientDrawable().apply {
            setColor(colorBg)
            cornerRadius = 6f * density
            setStroke((1 * density).toInt(), strokeColor)
        }

        // 4. Speed Row
        var hasSpeed = false
        if (segment.speedKmh != null && segment.speedKmh > 0) {
            currentSpeedTextView.text = "🚗 ${segment.speedKmh.toInt()} km/h"
            hasSpeed = true
            if (segment.normalSpeedKmh != null && segment.normalSpeedKmh > 0) {
                normalSpeedTextView.text = "Normal ${segment.normalSpeedKmh.toInt()} km/h"
                normalSpeedTextView.visibility = View.VISIBLE
            } else {
                normalSpeedTextView.visibility = View.GONE
            }
        }
        speedRow.visibility = if (hasSpeed) View.VISIBLE else View.GONE

        // 5. Metrics Row (Delay & Distance)
        var hasMetrics = false
        if (segment.delayMinutes != null) {
            val delayText = if (segment.delayMinutes > 0) "+${segment.delayMinutes} min delay" else "+0 min delay"
            delayBadgeView.text = "⏱ $delayText"
            val dColor = if (segment.delayMinutes > 0) colorInt else 0xFF10B981.toInt()
            val dBg = if (segment.delayMinutes > 0) colorBg else 0x2210B981.toInt()
            delayBadgeView.setTextColor(dColor)
            delayBadgeView.background = GradientDrawable().apply {
                setColor(dBg)
                cornerRadius = 4f * density
                setStroke((1 * density).toInt(), (0x66000000 or (dColor and 0x00FFFFFF)))
            }
            delayBadgeView.visibility = View.VISIBLE
            hasMetrics = true
        } else {
            delayBadgeView.visibility = View.GONE
        }

        if (segment.distanceMeters != null && segment.distanceMeters > 0) {
            distanceBadgeView.text = "📍 ${formatDistance(segment.distanceMeters)} affected"
            distanceBadgeView.visibility = View.VISIBLE
            hasMetrics = true
        } else {
            distanceBadgeView.visibility = View.GONE
        }
        metricsRow.visibility = if (hasMetrics) View.VISIBLE else View.GONE

        // 6. Warning / Incident Row
        if (!segment.incident.isNullOrBlank()) {
            warningTextView.text = segment.incident
            warningTextView.setTextColor(colorInt)
            warningIconView.setTextColor(colorInt)
            if (!segment.incidentDescription.isNullOrBlank()) {
                warningDescTextView.text = segment.incidentDescription
                warningDescTextView.visibility = View.VISIBLE
            } else {
                warningDescTextView.visibility = View.GONE
            }
            warningRow.visibility = View.VISIBLE
        } else {
            warningRow.visibility = View.GONE
        }

        invalidate()
    }
}

/**
 * Hit tests tapped screen position against route traffic segments with tolerance in pixels.
 * Returns closest matching segment and interpolated GeoPoint anchor on the route path.
 */
private fun findNearestTrafficSegment(
    tapPoint: GeoPoint,
    segments: List<TrafficSegment>,
    mapView: MapView,
    tolerancePx: Float
): Pair<TrafficSegment, GeoPoint>? {
    val proj = mapView.projection ?: return null
    val tapPx = Point()
    proj.toPixels(tapPoint, tapPx)

    var closestSegment: TrafficSegment? = null
    var minDistanceSq = (tolerancePx * tolerancePx).toDouble()
    var closestGeoPoint: GeoPoint? = null

    val p1Px = Point()
    val p2Px = Point()

    for (segment in segments) {
        val pts = segment.points
        if (pts.size < 2) continue

        for (i in 0 until pts.size - 1) {
            val pt1 = pts[i]
            val pt2 = pts[i + 1]

            proj.toPixels(GeoPoint(pt1.latitude, pt1.longitude), p1Px)
            proj.toPixels(GeoPoint(pt2.latitude, pt2.longitude), p2Px)

            val dx = (p2Px.x - p1Px.x).toDouble()
            val dy = (p2Px.y - p1Px.y).toDouble()
            val lenSq = dx * dx + dy * dy

            val t = if (lenSq == 0.0) 0.0 else {
                val num = (tapPx.x - p1Px.x) * dx + (tapPx.y - p1Px.y) * dy
                (num / lenSq).coerceIn(0.0, 1.0)
            }

            val projX = p1Px.x + t * dx
            val projY = p1Px.y + t * dy

            val distSq = (tapPx.x - projX) * (tapPx.x - projX) + (tapPx.y - projY) * (tapPx.y - projY)
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq
                closestSegment = segment
                val projGeo = proj.fromPixels(projX.toInt(), projY.toInt()) as? GeoPoint
                closestGeoPoint = projGeo ?: GeoPoint(
                    pt1.latitude + t * (pt2.latitude - pt1.latitude),
                    pt1.longitude + t * (pt2.longitude - pt1.longitude)
                )
            }
        }
    }

    return if (closestSegment != null && closestGeoPoint != null) {
        Pair(closestSegment, closestGeoPoint)
    } else null
}

/**
 * Hit tests tapped screen position against route traffic segments first, falling back
 * to the base route geometry points if traffic segments are empty or miss.
 */
private fun findNearestTrafficSegmentOrRoute(
    tapPoint: GeoPoint,
    trafficSegments: List<TrafficSegment>,
    routePoints: List<RoutePoint>,
    mapView: MapView,
    tolerancePx: Float
): Pair<TrafficSegment, GeoPoint>? {
    if (trafficSegments.isNotEmpty()) {
        val hit = findNearestTrafficSegment(tapPoint, trafficSegments, mapView, tolerancePx)
        if (hit != null) return hit
    }

    if (routePoints.size >= 2) {
        val proj = mapView.projection ?: return null
        val tapPx = Point()
        proj.toPixels(tapPoint, tapPx)

        var minDistanceSq = (tolerancePx * tolerancePx).toDouble()
        var closestGeoPoint: GeoPoint? = null

        val p1Px = Point()
        val p2Px = Point()

        for (i in 0 until routePoints.size - 1) {
            val pt1 = routePoints[i]
            val pt2 = routePoints[i + 1]

            proj.toPixels(GeoPoint(pt1.latitude, pt1.longitude), p1Px)
            proj.toPixels(GeoPoint(pt2.latitude, pt2.longitude), p2Px)

            val dx = (p2Px.x - p1Px.x).toDouble()
            val dy = (p2Px.y - p1Px.y).toDouble()
            val lenSq = dx * dx + dy * dy

            val t = if (lenSq == 0.0) 0.0 else {
                val num = (tapPx.x - p1Px.x) * dx + (tapPx.y - p1Px.y) * dy
                (num / lenSq).coerceIn(0.0, 1.0)
            }

            val projX = p1Px.x + t * dx
            val projY = p1Px.y + t * dy

            val distSq = (tapPx.x - projX) * (tapPx.x - projX) + (tapPx.y - projY) * (tapPx.y - projY)
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq
                val projGeo = proj.fromPixels(projX.toInt(), projY.toInt()) as? GeoPoint
                closestGeoPoint = projGeo ?: GeoPoint(
                    pt1.latitude + t * (pt2.latitude - pt1.latitude),
                    pt1.longitude + t * (pt2.longitude - pt1.longitude)
                )
            }
        }

        if (closestGeoPoint != null) {
            val fallbackSegment = TrafficSegment(
                points = routePoints,
                congestionLevel = CongestionLevel.CLEAR,
                speedKmh = null,
                normalSpeedKmh = null,
                roadName = null,
                delayMinutes = null,
                distanceMeters = null,
                incident = null,
                incidentDescription = null,
                lastUpdated = null
            )
            return Pair(fallbackSegment, closestGeoPoint)
        }
    }

    return null
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000.0) {
        String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
    } else {
        "${meters.toInt()} m"
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = (seconds + 30) / 60
    return if (mins >= 60) {
        val hrs = mins / 60
        val remMins = mins % 60
        if (remMins > 0) "${hrs}h ${remMins}m" else "${hrs}h"
    } else {
        "~${maxOf(1, mins)} min"
    }
}

/**
 * Modern custom RouteWake traffic incident popup callout:
 * - Dark slate/navy card (#0F172A), border (#334155), rounded corners (14dp).
 * - Downward pointer triangle pointing to the incident marker.
 * - Header: Warning/Incident icon + Title + Close '✕' button.
 * - Description text.
 * - Detail badges: Delay badge (e.g. "+12 min delay"), Road name (e.g. "Main Road").
 */
private class IncidentPopupCardView(context: Context) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density

    private val titleTextView: TextView
    private val closeButton: TextView
    private val descriptionTextView: TextView
    private val delayBadgeView: TextView
    private val roadTextView: TextView
    private val detailsRow: LinearLayout

    var onCloseClicked: (() -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xF80F172A.toInt() // Rich deep dark glass navy
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFF334155.toInt() // Slate border
    }

    private val pointerPath = Path()

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        setOnClickListener { /* consumed */ }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (14 * density).toInt()
            val padTop = (12 * density).toInt()
            val padBottom = (20 * density).toInt() // extra space for bottom pointer
            setPadding(padH, padTop, padH, padBottom)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        // Header Row (Icon + Title + Close X)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        titleTextView = TextView(context).apply {
            setTextColor(0xFFF8FAFC.toInt())
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }

        closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                onCloseClicked?.invoke()
            }
        }

        headerRow.addView(titleTextView)
        headerRow.addView(closeButton)
        container.addView(headerRow)

        // Description TextView
        descriptionTextView = TextView(context).apply {
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 11.5f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
            layoutParams = lp
        }
        container.addView(descriptionTextView)

        // Details Row (Delay badge + Road location)
        detailsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
            layoutParams = lp
        }

        delayBadgeView = TextView(context).apply {
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val padH = (6 * density).toInt()
            val padV = (2 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        roadTextView = TextView(context).apply {
            setTextColor(0xFF38BDF8.toInt()) // Electric blue
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
            layoutParams = lp
        }

        detailsRow.addView(delayBadgeView)
        detailsRow.addView(roadTextView)
        container.addView(detailsRow)

        addView(container)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = (260 * density).toInt()
        val specMode = MeasureSpec.getMode(widthMeasureSpec)
        val specSize = MeasureSpec.getSize(widthMeasureSpec)
        val constrainedSize = if (specMode == MeasureSpec.UNSPECIFIED) maxW else minOf(specSize, maxW)
        val constrainedWidthSpec = MeasureSpec.makeMeasureSpec(constrainedSize, MeasureSpec.AT_MOST)
        super.onMeasure(constrainedWidthSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pointerH = 10f * density
        val pointerW = 16f * density
        val r = 14f * density
        val stroke = 1.5f * density
        val halfStroke = stroke / 2f

        val left = halfStroke
        val top = halfStroke
        val right = w - halfStroke
        val cardBottom = h - pointerH
        val cx = w / 2f
        val pw = pointerW / 2f
        val bottomTip = h - halfStroke

        pointerPath.reset()
        pointerPath.moveTo(left + r, top)
        pointerPath.lineTo(right - r, top)
        pointerPath.quadTo(right, top, right, top + r)
        pointerPath.lineTo(right, cardBottom - r)
        pointerPath.quadTo(right, cardBottom, right - r, cardBottom)
        pointerPath.lineTo(cx + pw, cardBottom)
        pointerPath.lineTo(cx, bottomTip)
        pointerPath.lineTo(cx - pw, cardBottom)
        pointerPath.lineTo(left + r, cardBottom)
        pointerPath.quadTo(left, cardBottom, left, cardBottom - r)
        pointerPath.lineTo(left, top + r)
        pointerPath.quadTo(left, top, left + r, top)
        pointerPath.close()

        canvas.drawPath(pointerPath, bgPaint)
        canvas.drawPath(pointerPath, borderPaint)

        super.onDraw(canvas)
    }

    fun bind(incident: TrafficIncident) {
        val prefix = when (incident.type) {
            IncidentType.ACCIDENT -> "⚠ Accident"
            IncidentType.CONSTRUCTION -> "🚧 Construction"
            IncidentType.SLOWDOWN -> "⏳ Traffic Slowdown"
            IncidentType.CLOSURE -> "⛔ Road Closure"
            IncidentType.HAZARD -> "⚠ Hazard"
        }
        titleTextView.text = if (incident.title.isNotEmpty()) "$prefix: ${incident.title}" else prefix

        if (incident.description.isNotEmpty()) {
            descriptionTextView.text = incident.description
            descriptionTextView.visibility = View.VISIBLE
        } else {
            descriptionTextView.visibility = View.GONE
        }

        var hasDetails = false
        if (incident.delayMinutes != null && incident.delayMinutes > 0) {
            val isHigh = incident.severity == IncidentSeverity.HIGH
            val color = if (isHigh) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt()
            val bgColor = if (isHigh) 0x22EF4444.toInt() else 0x22F59E0B.toInt()
            delayBadgeView.text = "+${incident.delayMinutes} min delay"
            delayBadgeView.setTextColor(color)
            delayBadgeView.background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 4f * density
                setStroke((1 * density).toInt(), color)
            }
            delayBadgeView.visibility = View.VISIBLE
            hasDetails = true
        } else {
            delayBadgeView.visibility = View.GONE
        }

        if (!incident.roadName.isNullOrEmpty()) {
            roadTextView.text = "📍 ${incident.roadName}"
            roadTextView.visibility = View.VISIBLE
            hasDetails = true
        } else {
            roadTextView.visibility = View.GONE
        }

        detailsRow.visibility = if (hasDetails) View.VISIBLE else View.GONE
        invalidate()
    }
}

/**
 * RouteWake custom osmdroid InfoWindow wrapping the IncidentPopupCardView.
 */
private class RouteWakeIncidentInfoWindow(
    val popupCardView: IncidentPopupCardView,
    mapView: MapView
) : InfoWindow(popupCardView, mapView) {

    init {
        popupCardView.onCloseClicked = {
            close()
        }
    }

    override fun onOpen(item: Any?) {
        closeAllInfoWindowsOn(mMapView)
    }

    override fun onClose() {
    }
}

/**
 * Creates a compact native map marker drawable for traffic incidents:
 * - 28dp x 28dp circular badge with shadow and #0F172A base.
 * - Severity border accent: Red (#EF4444) for HIGH, Amber (#F59E0B) for MODERATE.
 * - Crisp vector symbol representing incident type.
 */
private fun createIncidentMarkerDrawable(context: Context, incident: TrafficIncident): Drawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (28 * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val r = 11.5f * density

    // Drop shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55000000
    }
    canvas.drawCircle(cx, cy + (1.5f * density), r, shadowPaint)

    // Base background (#0F172A)
    val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0F172A.toInt()
    }
    canvas.drawCircle(cx, cy, r, basePaint)

    // Severity border
    val borderColor = if (incident.severity == IncidentSeverity.HIGH) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt()
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = borderColor
    }
    canvas.drawCircle(cx, cy, r, borderPaint)

    // Inner icon
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = borderColor
    }

    when (incident.type) {
        IncidentType.CONSTRUCTION -> {
            canvas.drawLine(cx - (4f * density), cy + (3.5f * density), cx + (4f * density), cy - (3.5f * density), iconPaint)
            canvas.drawLine(cx - (1f * density), cy + (4.5f * density), cx + (4.5f * density), cy - (1f * density), iconPaint)
        }
        IncidentType.CLOSURE -> {
            canvas.drawLine(cx - (4.5f * density), cy, cx + (4.5f * density), cy, iconPaint)
        }
        else -> {
            canvas.drawLine(cx, cy - (4.5f * density), cx, cy + (1f * density), iconPaint)
            canvas.drawCircle(cx, cy + (3.5f * density), 1f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = borderColor
            })
        }
    }

    return BitmapDrawable(context.resources, bitmap)
}

