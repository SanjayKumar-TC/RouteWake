package com.example.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.RouteWakeApp
import com.example.data.local.FavoriteEntity
import com.example.data.local.MapCameraState
import com.example.data.local.TripHistoryEntity
import com.example.data.local.UserPreferences
import com.example.data.model.*
import com.example.engine.AlarmAudioEngine
import com.example.engine.CompassSensorManager
import com.example.engine.GpsEngine
import com.example.engine.LocationPrerequisiteManager
import com.example.engine.LocationPrerequisiteState
import com.example.engine.VibrationEngine
import com.example.network.GeocodingService
import com.example.network.RoutingService
import com.example.service.ArrivalNotificationHelper
import com.example.service.TrackingForegroundService
import com.example.ui.components.NavTab
import com.example.worker.ArrivalWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteWakeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as RouteWakeApp).database
    private val userPreferences = UserPreferences(application)
    private val geocodingService = GeocodingService()
    private val routingService = RoutingService()
    private val testAudioEngine = AlarmAudioEngine()
    private val gpsEngine = GpsEngine.getInstance(application)
    private val compassSensorManager = CompassSensorManager(application)

    val settings = userPreferences.settingsFlow
    val favorites: StateFlow<List<FavoriteEntity>> = db.favoriteDao().getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tripHistory: StateFlow<List<TripHistoryEntity>> = db.historyDao().getLatestTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Sheet Navigation: Initial state is null (full map + bottom bar, NO sheet open)
    private val _activeTab = MutableStateFlow<NavTab?>(null)
    val activeTab: StateFlow<NavTab?> = _activeTab.asStateFlow()

    // Destination & Trip Configuration
    private val _selectedDestination = MutableStateFlow<Destination?>(null)
    val selectedDestination: StateFlow<Destination?> = _selectedDestination.asStateFlow()

    private val _activeRadiusMeters = MutableStateFlow(500)
    val activeRadiusMeters: StateFlow<Int> = _activeRadiusMeters.asStateFlow()

    private val _selectedTransport = MutableStateFlow(TransportMode.CAR)
    val selectedTransport: StateFlow<TransportMode> = _selectedTransport.asStateFlow()

    private val _routeInfo = MutableStateFlow<RouteInfo?>(null)
    val routeInfo: StateFlow<RouteInfo?> = _routeInfo.asStateFlow()

    private val _isCalculatingRoute = MutableStateFlow(false)
    val isCalculatingRoute: StateFlow<Boolean> = _isCalculatingRoute.asStateFlow()

    // Active Trip Execution
    private val _tripState = MutableStateFlow(TripState.IDLE)
    val tripState: StateFlow<TripState> = _tripState.asStateFlow()

    private val _metrics = MutableStateFlow(TripMetrics())
    val metrics: StateFlow<TripMetrics> = _metrics.asStateFlow()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    // Real-time device heading/compass orientation (0..360 degrees)
    val deviceHeading: StateFlow<Float?> = compassSensorManager.headingFlow

    // Location prerequisites state (Device Location ON, Permissions, High Accuracy)
    private val _prerequisiteState = MutableStateFlow<LocationPrerequisiteState>(LocationPrerequisiteState.Checking)
    val prerequisiteState: StateFlow<LocationPrerequisiteState> = _prerequisiteState.asStateFlow()

    private val _completedTripSummary = MutableStateFlow<TripHistoryEntity?>(null)
    val completedTripSummary: StateFlow<TripHistoryEntity?> = _completedTripSummary.asStateFlow()

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Destination>>(emptyList())
    val searchResults: StateFlow<List<Destination>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _recentSearches = MutableStateFlow(userPreferences.getRecentSearches())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    // Follow Mode & Map Camera State
    private val _isFollowMode = MutableStateFlow(true)
    val isFollowMode: StateFlow<Boolean> = _isFollowMode.asStateFlow()

    private val _recenterTrigger = MutableStateFlow(0)
    val recenterTrigger: StateFlow<Int> = _recenterTrigger.asStateFlow()

    /**
     * Checks if device location services and runtime location permissions are active.
     */
    fun isLocationServicesActive(): Boolean {
        val context = getApplication<Application>()
        val isEnabled = LocationPrerequisiteManager.isLocationEnabled(context)
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return isEnabled && (hasFine || hasCoarse)
    }

    /**
     * Initiates 'Follow Mode'.
     * Strictly centers the map on the user's current GPS location when activated,
     * while preserving manual navigation persistence in userPreferences when not in follow mode.
     */
    fun initiateFollowMode(forceRecenter: Boolean = true) {
        _isFollowMode.value = true
        if (forceRecenter) {
            _recenterTrigger.value++
        }
        if (isLocationServicesActive()) {
            val loc = _currentLocation.value
            if (loc != null) {
                // Strictly center on current GPS location
                _metrics.value = _metrics.value.copy(
                    currentLat = loc.latitude,
                    currentLng = loc.longitude
                )
            } else {
                // Location services active but awaiting fix; start tracking to strictly center on fix
                gpsEngine.startTracking()
                refreshCurrentLocation()
            }
        }
        // Manual navigation persistence in userPreferences is strictly preserved and not overwritten.
    }

    /**
     * Activates 'Follow Mode', strictly centering on current GPS location.
     */
    fun activateFollowMode() {
        initiateFollowMode(forceRecenter = true)
    }

    /**
     * Called when manual navigation (panning, scrolling, zooming) occurs on the map.
     * Disengages Follow Mode so user manual exploration is respected, and persists the
     * manual camera position to storage.
     */
    fun onManualMapNavigation(lat: Double, lng: Double, zoom: Double) {
        _isFollowMode.value = false
        userPreferences.saveMapCameraState(lat, lng, zoom)
    }

    fun setFollowMode(enabled: Boolean) {
        if (enabled) {
            initiateFollowMode(forceRecenter = true)
        } else {
            _isFollowMode.value = false
        }
    }

    fun getSavedMapCameraState(): MapCameraState? = userPreferences.getMapCameraState()

    fun saveMapCameraState(lat: Double, lng: Double, zoom: Double) {
        userPreferences.saveMapCameraState(lat, lng, zoom)
    }

    fun clearSavedMapCameraState() {
        userPreferences.clearMapCameraState()
    }

    /**
     * Resolves the camera state for map initialization:
     * - If Follow Mode is active and location services are active with a current GPS fix,
     *   strictly centers on the user's current GPS coordinate.
     * - When not in follow mode, preserves and restores the persisted manual navigation camera state.
     */
    fun getInitialCameraState(): MapCameraState? {
        val loc = _currentLocation.value
        return if (_isFollowMode.value && isLocationServicesActive() && loc != null) {
            MapCameraState(loc.latitude, loc.longitude, 16.0)
        } else {
            userPreferences.getMapCameraState()
        }
    }

    private var searchJob: Job? = null
    private var routeCalculationJob: Job? = null
    private var reverseGeocodeJob: Job? = null
    private var activeRouteRequestId: Long = 0L
    private var trackingService: TrackingForegroundService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TrackingForegroundService.LocalBinder
            trackingService = binder.getService()
            isBound = true

            // Observe service distance and trip state
            viewModelScope.launch {
                trackingService?.distanceRemaining?.collect { dist ->
                    updateDistanceRemaining(dist)
                }
            }

            viewModelScope.launch {
                trackingService?.currentLocation?.collect { loc ->
                    if (loc != null) {
                        _currentLocation.value = loc
                        geocodingService.warmupLocationContext(loc.latitude, loc.longitude)
                    }
                }
            }

            viewModelScope.launch {
                trackingService?.arrivalEngine?.tripState?.collect { state ->
                    _tripState.value = state
                }
            }

            trackingService?.arrivalEngine?.onTripCompleted = { history ->
                _completedTripSummary.value = history
            }

            // Restore active destination from service if UI was opened while service was tracking
            val activeDest = trackingService?.arrivalEngine?.getActiveDestination()
            if (activeDest != null) {
                _selectedDestination.value = activeDest
                _activeRadiusMeters.value = trackingService?.arrivalEngine?.getActiveRadius() ?: 500
                _selectedTransport.value = trackingService?.arrivalEngine?.getActiveTransport() ?: TransportMode.CAR
                _tripState.value = trackingService?.arrivalEngine?.tripState?.value ?: TripState.TRACKING
                recalculateRouteIfDestinationSelected()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            isBound = false
        }
    }

    init {
        // Apply default settings
        _activeRadiusMeters.value = userPreferences.getSettings().defaultRadiusMeters
        _selectedTransport.value = userPreferences.getSettings().defaultTransport

        // Check if there is an active trip running in the background foreground service
        val activeTrip = userPreferences.getActiveTrip()
        if (activeTrip != null) {
            val dest = Destination(
                name = activeTrip.destinationName,
                address = activeTrip.destinationAddress,
                latitude = activeTrip.destinationLat,
                longitude = activeTrip.destinationLng
            )
            _selectedDestination.value = dest
            _activeRadiusMeters.value = activeTrip.radiusMeters
            _selectedTransport.value = activeTrip.transportMode
            _tripState.value = TripState.TRACKING

            try {
                val context = getApplication<Application>()
                val intent = Intent(context, TrackingForegroundService::class.java)
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                android.util.Log.w("RouteWakeViewModel", "Failed to bind to running service: ${e.message}")
            }
        }

        // Observe authoritative GpsEngine location stream
        viewModelScope.launch {
            gpsEngine.currentLocation.collect { loc ->
                if (loc != null) {
                    val prev = _currentLocation.value
                    _currentLocation.value = loc
                    _metrics.value = _metrics.value.copy(
                        currentLat = loc.latitude,
                        currentLng = loc.longitude,
                        currentSpeedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else _metrics.value.currentSpeedKmh
                    )
                    if (prev == null) {
                        recalculateRouteIfDestinationSelected()
                        if (_isFollowMode.value && isLocationServicesActive()) {
                            _recenterTrigger.value++
                        }
                    }
                    geocodingService.warmupLocationContext(loc.latitude, loc.longitude)
                } else {
                    _currentLocation.value = null
                }
            }
        }

        // Observe GPS quality and warmup countdown from GpsEngine
        viewModelScope.launch {
            gpsEngine.gpsQuality.collect { quality ->
                _metrics.value = _metrics.value.copy(gpsQuality = quality)
            }
        }

        viewModelScope.launch {
            gpsEngine.warmupSecondsRemaining.collect { sec ->
                _metrics.value = _metrics.value.copy(warmupSecondsRemaining = sec)
            }
        }

        compassSensorManager.start()
        val appContext = getApplication<Application>()
        if (LocationPrerequisiteManager.hasAnyLocationPermission(appContext) && LocationPrerequisiteManager.isLocationEnabled(appContext)) {
            gpsEngine.startTracking()
            gpsEngine.fetchImmediateLocation()
        }
        checkPrerequisites()
    }

    fun startCompass() {
        compassSensorManager.start()
    }

    fun stopCompass() {
        compassSensorManager.stop()
    }

    /**
     * Checks all device location prerequisites in proper sequence:
     * 1. Device Location service (Master toggle)
     * 2. Runtime location permissions (FINE / COARSE)
     * 3. High accuracy location settings (Google Play Services SettingsClient)
     */
    fun checkPrerequisites() {
        val context = getApplication<Application>()
        LocationPrerequisiteManager.checkPrerequisites(context) { state ->
            _prerequisiteState.value = state
            when (state) {
                is LocationPrerequisiteState.Satisfied -> {
                    gpsEngine.startTracking()
                }
                is LocationPrerequisiteState.HighAccuracyDisabled -> {
                    gpsEngine.startTracking()
                    _metrics.value = _metrics.value.copy(
                        gpsQuality = GpsQuality.POOR
                    )
                }
                is LocationPrerequisiteState.LocationDisabled -> {
                    gpsEngine.stopTracking()
                    gpsEngine.clearLocation()
                    _currentLocation.value = null
                    _metrics.value = _metrics.value.copy(
                        gpsQuality = GpsQuality.OFF,
                        currentSpeedKmh = 0.0
                    )
                }
                is LocationPrerequisiteState.PermissionRequired -> {
                    gpsEngine.stopTracking()
                    gpsEngine.clearLocation()
                    _currentLocation.value = null
                    _metrics.value = _metrics.value.copy(
                        gpsQuality = GpsQuality.OFF,
                        currentSpeedKmh = 0.0
                    )
                }
                LocationPrerequisiteState.Checking -> {}
            }
        }
    }

    fun refreshCurrentLocation() {
        gpsEngine.fetchImmediateLocation()
        checkPrerequisites()
    }

    private fun recalculateRouteIfDestinationSelected() {
        val dest = _selectedDestination.value ?: return
        if (_tripState.value != TripState.IDLE) return
        val currentLoc = _currentLocation.value ?: return
        val requestId = ++activeRouteRequestId
        routeCalculationJob?.cancel()
        _isCalculatingRoute.value = true
        routeCalculationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = routingService.calculateRoute(
                    currentLoc.latitude, currentLoc.longitude,
                    dest.latitude, dest.longitude,
                    _selectedTransport.value
                )
                withContext(Dispatchers.Main) {
                    if (requestId == activeRouteRequestId &&
                        _selectedDestination.value?.latitude == dest.latitude &&
                        _selectedDestination.value?.longitude == dest.longitude
                    ) {
                        _routeInfo.value = info
                        _metrics.value = _metrics.value.copy(
                            distanceRemainingMeters = info.distanceMeters,
                            initialDistanceMeters = info.distanceMeters,
                            etaSeconds = info.durationSeconds,
                            isModeledEta = info.isModeled
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RouteWakeViewModel", "Route recalculation error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    if (requestId == activeRouteRequestId) {
                        _isCalculatingRoute.value = false
                    }
                }
            }
        }
    }

    // Critical Sheet Open/Close Behavior
    fun onTabClicked(tab: NavTab) {
        if (_activeTab.value == tab) {
            // Second tap closes sheet completely!
            _activeTab.value = null
        } else {
            // Opens or switches sheet
            _activeTab.value = tab
        }
    }

    fun openTab(tab: NavTab) {
        _activeTab.value = tab
    }

    fun closeSheet() {
        _activeTab.value = null
    }

    private var activeSearchRequestId: Long = 0L

    // Search with ~300ms debounce and strict generation ID protection
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        val requestId = ++activeSearchRequestId
        searchJob?.cancel()

        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(300L) // 300ms debounce
            if (requestId != activeSearchRequestId || _searchQuery.value.trim() != trimmed) {
                return@launch
            }
            _isSearching.value = true
            try {
                val curLoc = _currentLocation.value
                val results = geocodingService.searchDestinations(trimmed, curLoc?.latitude, curLoc?.longitude)
                if (requestId == activeSearchRequestId && _searchQuery.value.trim() == trimmed) {
                    _searchResults.value = results
                }
            } finally {
                if (requestId == activeSearchRequestId) {
                    _isSearching.value = false
                }
            }
        }
    }

    fun onSearchSubmitted(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        userPreferences.addRecentSearch(trimmed)
        _recentSearches.value = userPreferences.getRecentSearches()

        val requestId = ++activeSearchRequestId
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (requestId != activeSearchRequestId) return@launch
            _isSearching.value = true
            try {
                val curLoc = _currentLocation.value
                val results = geocodingService.searchDestinations(trimmed, curLoc?.latitude, curLoc?.longitude)
                if (requestId == activeSearchRequestId && _searchQuery.value.trim() == trimmed) {
                    _searchResults.value = results
                }
            } finally {
                if (requestId == activeSearchRequestId) {
                    _isSearching.value = false
                }
            }
        }
    }

    fun selectDestination(destination: Destination, needsReverseGeocode: Boolean = false) {
        android.util.Log.d("RouteWakeTiming", "DESTINATION_TAP_RECEIVED: ViewModel processing ${destination.name} (${destination.latitude}, ${destination.longitude})")

        // 1. Invalidate previous in-flight jobs and bump request ID
        routeCalculationJob?.cancel()
        reverseGeocodeJob?.cancel()
        val requestId = ++activeRouteRequestId

        // 2. Synchronously update UI state IMMEDIATELY - ZERO delay, instant response
        _routeInfo.value = null
        _selectedDestination.value = destination
        _searchResults.value = emptyList()
        _searchQuery.value = ""
        _isCalculatingRoute.value = true

        android.util.Log.d("RouteWakeTiming", "DESTINATION_STATE_UPDATED: ${destination.name} (${destination.latitude}, ${destination.longitude})")

        // 3. Asynchronously calculate route off the main thread
        val currentLoc = _currentLocation.value
        val startLat = currentLoc?.latitude ?: 37.7749
        val startLng = currentLoc?.longitude ?: -122.4194

        android.util.Log.d("RouteWakeTiming", "ROUTE_REQUEST_STARTED: reqId=$requestId from ($startLat, $startLng) to (${destination.latitude}, ${destination.longitude})")

        routeCalculationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = routingService.calculateRoute(
                    startLat, startLng,
                    destination.latitude, destination.longitude,
                    _selectedTransport.value
                )
                withContext(Dispatchers.Main) {
                    android.util.Log.d("RouteWakeTiming", "ROUTE_RESPONSE_RECEIVED: reqId=$requestId, distance=${info.distanceMeters}m, duration=${info.durationSeconds}s")
                    // Protect against stale asynchronous route responses: only apply if this request
                    // is still the latest and destination matches current selection
                    if (requestId == activeRouteRequestId &&
                        _selectedDestination.value?.latitude == destination.latitude &&
                        _selectedDestination.value?.longitude == destination.longitude
                    ) {
                        _routeInfo.value = info
                        _metrics.value = _metrics.value.copy(
                            distanceRemainingMeters = info.distanceMeters,
                            initialDistanceMeters = info.distanceMeters,
                            etaSeconds = info.durationSeconds,
                            isModeledEta = info.isModeled
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RouteWakeViewModel", "Route calculation error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    if (requestId == activeRouteRequestId) {
                        _isCalculatingRoute.value = false
                    }
                }
            }
        }

        // 4. Asynchronously reverse-geocode ONLY if additional address information is actually required
        if (needsReverseGeocode) {
            reverseGeocodeJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val enriched = geocodingService.reverseGeocode(destination.latitude, destination.longitude)
                    withContext(Dispatchers.Main) {
                        val current = _selectedDestination.value
                        if (current != null &&
                            current.latitude == destination.latitude &&
                            current.longitude == destination.longitude
                        ) {
                            _selectedDestination.value = current.copy(
                                name = enriched.name.ifEmpty { current.name },
                                address = enriched.address.ifEmpty { current.address },
                                category = enriched.category
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RouteWakeViewModel", "Async reverse geocoding error: ${e.message}")
                }
            }
        }
    }

    fun selectDestinationFromCoordinates(latitude: Double, longitude: Double) {
        if (_tripState.value != TripState.IDLE) return
        val provisional = Destination(
            id = "tap_${latitude}_${longitude}_${System.currentTimeMillis()}",
            name = "Selected Location",
            address = "Locating address...",
            latitude = latitude,
            longitude = longitude,
            category = "Custom"
        )
        selectDestination(provisional, needsReverseGeocode = true)
    }

    fun clearSelectedDestination() {
        if (_tripState.value == TripState.IDLE) {
            routeCalculationJob?.cancel()
            reverseGeocodeJob?.cancel()
            activeRouteRequestId++
            _selectedDestination.value = null
            _routeInfo.value = null
            _isCalculatingRoute.value = false
        }
    }

    fun setRadius(radiusMeters: Int) {
        _activeRadiusMeters.value = radiusMeters
    }

    fun setTransport(mode: TransportMode) {
        _selectedTransport.value = mode
        val dest = _selectedDestination.value ?: return
        routeCalculationJob?.cancel()
        _routeInfo.value = null
        _isCalculatingRoute.value = true

        val requestId = ++activeRouteRequestId
        val currentLoc = _currentLocation.value
        val startLat = currentLoc?.latitude ?: 37.7749
        val startLng = currentLoc?.longitude ?: -122.4194

        android.util.Log.d("RouteWakeTiming", "ROUTE_REQUEST_STARTED: reqId=$requestId mode=$mode to (${dest.latitude}, ${dest.longitude})")

        routeCalculationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = routingService.calculateRoute(
                    startLat, startLng,
                    dest.latitude, dest.longitude,
                    mode
                )
                withContext(Dispatchers.Main) {
                    android.util.Log.d("RouteWakeTiming", "ROUTE_RESPONSE_RECEIVED: reqId=$requestId mode=$mode distance=${info.distanceMeters}m")
                    if (requestId == activeRouteRequestId &&
                        _selectedDestination.value?.latitude == dest.latitude &&
                        _selectedDestination.value?.longitude == dest.longitude
                    ) {
                        _routeInfo.value = info
                        _metrics.value = _metrics.value.copy(
                            distanceRemainingMeters = info.distanceMeters,
                            initialDistanceMeters = info.distanceMeters,
                            etaSeconds = info.durationSeconds,
                            isModeledEta = info.isModeled
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("RouteWakeViewModel", "Route recalculation error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    if (requestId == activeRouteRequestId) {
                        _isCalculatingRoute.value = false
                    }
                }
            }
        }
    }

    fun startTrip(): Boolean {
        val dest = _selectedDestination.value ?: return false
        val context = getApplication<Application>()

        if (!LocationPrerequisiteManager.isLocationEnabled(context)) {
            checkPrerequisites()
            return false
        }

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            checkPrerequisites()
            return false
        }

        try {
            val intent = Intent(context, TrackingForegroundService::class.java).apply {
                action = TrackingForegroundService.ACTION_START_TRACKING
                putExtra(TrackingForegroundService.EXTRA_DEST_NAME, dest.name)
                putExtra(TrackingForegroundService.EXTRA_DEST_ADDRESS, dest.address)
                putExtra(TrackingForegroundService.EXTRA_DEST_LAT, dest.latitude)
                putExtra(TrackingForegroundService.EXTRA_DEST_LNG, dest.longitude)
                putExtra(TrackingForegroundService.EXTRA_RADIUS_METERS, _activeRadiusMeters.value)
                putExtra(TrackingForegroundService.EXTRA_TRANSPORT_MODE, _selectedTransport.value.name)
            }

            context.startForegroundService(intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

            ArrivalWorkScheduler.scheduleArrivalDetection(context, initialDelaySeconds = 60L)

            _tripState.value = TripState.WARMUP
            _activeTab.value = null // Close sheet so user sees map and cockpit overlay
            initiateFollowMode(forceRecenter = true)
            return true
        } catch (e: Exception) {
            android.util.Log.e("RouteWakeViewModel", "Failed to start tracking service: ${e.message}", e)
            return false
        }
    }

    fun stopTrip() {
        val context = getApplication<Application>()
        AlarmAudioEngine.stopAll()
        VibrationEngine.stopAll(context)
        testAudioEngine.stopAlarm()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(TrackingForegroundService.NOTIFICATION_ID)
        notificationManager?.cancel(ArrivalNotificationHelper.NOTIFICATION_ID_ARRIVAL)
        notificationManager?.cancelAll()
        ArrivalNotificationHelper.dismissArrivalNotification(context)

        ArrivalWorkScheduler.cancelArrivalDetection(context)

        val intent = Intent(context, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_STOP_TRACKING
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}

        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
        }

        _tripState.value = TripState.IDLE
        gpsEngine.startTracking()
    }

    fun dismissArrival() {
        val context = getApplication<Application>()
        // 1. Instantly silence all audio and vibration across the entire app
        AlarmAudioEngine.stopAll()
        VibrationEngine.stopAll(context)
        testAudioEngine.stopAlarm()

        // 2. Immediately cancel all notifications (tracking & arrival)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(TrackingForegroundService.NOTIFICATION_ID)
        nm?.cancel(ArrivalNotificationHelper.NOTIFICATION_ID_ARRIVAL)
        nm?.cancelAll()
        ArrivalNotificationHelper.dismissArrivalNotification(context)

        // 3. Cancel WorkManager arrival detection
        ArrivalWorkScheduler.cancelArrivalDetection(context)

        // 4. Directly notify bound service if connected
        trackingService?.dismissAlarm()

        // 5. Send intent to service to ensure it handles termination even if unbound
        val intent = Intent(context, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_DISMISS_ALARM
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}

        // 6. Transition tripState to COMPLETED so arrival dialog closes immediately
        val currentDest = _selectedDestination.value
        if (_completedTripSummary.value == null && currentDest != null) {
            val dist = _metrics.value.initialDistanceMeters
            _completedTripSummary.value = TripHistoryEntity(
                destinationName = currentDest.name,
                destinationAddress = currentDest.address,
                destinationLat = currentDest.latitude,
                destinationLng = currentDest.longitude,
                startTimeMs = System.currentTimeMillis() - 60000,
                endTimeMs = System.currentTimeMillis(),
                distanceMeters = dist,
                durationSeconds = 1L,
                avgSpeedKmh = 0.0,
                transportMode = _selectedTransport.value.name,
                radiusMeters = _activeRadiusMeters.value,
                completed = true
            )
        }
        _tripState.value = TripState.COMPLETED
    }

    fun snoozeArrival() {
        val context = getApplication<Application>()
        // 1. Immediately silence audio and vibration
        AlarmAudioEngine.stopAll()
        VibrationEngine.stopAll(context)
        testAudioEngine.stopAlarm()

        // 2. Cancel arrival notifications
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(TrackingForegroundService.NOTIFICATION_ID)
        nm?.cancel(ArrivalNotificationHelper.NOTIFICATION_ID_ARRIVAL)
        ArrivalNotificationHelper.dismissArrivalNotification(context)

        // 3. Directly snooze in bound service
        trackingService?.snoozeAlarm()

        // 4. Send intent to service
        val intent = Intent(context, TrackingForegroundService::class.java).apply {
            action = TrackingForegroundService.ACTION_SNOOZE_ALARM
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }

    fun clearCompletedSummary() {
        _completedTripSummary.value = null
        _tripState.value = TripState.IDLE
        _selectedDestination.value = null
        _routeInfo.value = null
    }

    private fun updateDistanceRemaining(distMeters: Double) {
        val initial = _metrics.value.initialDistanceMeters.coerceAtLeast(distMeters)
        val progress = if (initial > 0) {
            ((initial - distMeters) / initial).toFloat().coerceIn(0f, 1f)
        } else 0f

        val speedMps = (_selectedTransport.value.fallbackSpeedKmh * 1000.0) / 3600.0
        val etaSec = (distMeters / speedMps.coerceAtLeast(1.0)).toLong()

        _metrics.value = _metrics.value.copy(
            distanceRemainingMeters = distMeters,
            initialDistanceMeters = initial,
            progressPercent = progress,
            etaSeconds = etaSec
        )
    }

    fun saveFavorite(destination: Destination, radiusMeters: Int) {
        viewModelScope.launch {
            db.favoriteDao().insertFavorite(
                FavoriteEntity(
                    name = destination.name,
                    address = destination.address,
                    latitude = destination.latitude,
                    longitude = destination.longitude,
                    preferredRadiusMeters = radiusMeters,
                    preferredTransport = _selectedTransport.value.name,
                    category = destination.category
                )
            )
        }
    }

    fun deleteFavorite(favorite: FavoriteEntity) {
        viewModelScope.launch {
            db.favoriteDao().deleteFavorite(favorite)
        }
    }

    fun deleteHistoryTrip(trip: TripHistoryEntity) {
        viewModelScope.launch {
            db.historyDao().deleteTrip(trip)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            db.historyDao().clearHistory()
        }
    }

    fun updateEcoMode(enabled: Boolean) = userPreferences.updateEcoMode(enabled)
    fun updateWorkManagerBackground(enabled: Boolean) = userPreferences.updateWorkManagerBackground(enabled)
    fun updateAlarmTone(tone: AlarmTone) = userPreferences.updateAlarmTone(tone)
    fun updateVibration(enabled: Boolean) = userPreferences.updateVibration(enabled)
    fun updateDefaultRadius(radius: Int) = userPreferences.updateDefaultRadius(radius)
    fun updateSatellite(enabled: Boolean) = userPreferences.updateSatellite(enabled)
    fun updateTrafficEnabled(enabled: Boolean) = userPreferences.updateTrafficEnabled(enabled)
    fun toggleTraffic() {
        val current = settings.value.trafficEnabled
        userPreferences.updateTrafficEnabled(!current)
    }
    fun updateShowRadius(enabled: Boolean) = userPreferences.updateShowRadius(enabled)
    fun toggleShowRadius() {
        val current = settings.value.showDestinationRadiusOnMap
        userPreferences.updateShowRadius(!current)
    }

    fun testAlarmTone(tone: AlarmTone) {
        viewModelScope.launch {
            testAudioEngine.startAlarm(tone)
            delay(2500L)
            testAudioEngine.stopAlarm()
        }
    }

    fun toggleDevSimulation(active: Boolean, speedKmh: Double, multiplier: Int) {
        userPreferences.updateSimulation(active)
        val dest = _selectedDestination.value
        if (dest != null && active) {
            trackingService?.startDevSimulation(dest.latitude, dest.longitude, speedKmh, multiplier)
        }
    }

    override fun onCleared() {
        super.onCleared()
        testAudioEngine.stopAlarm()
        compassSensorManager.stop()
        if (_tripState.value == TripState.IDLE) {
            gpsEngine.stopTracking()
        }
        if (isBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (_: Exception) {}
        }
    }
}
