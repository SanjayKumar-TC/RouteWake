package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.example.data.model.TripState
import com.example.engine.LocationPrerequisiteManager
import com.example.engine.LocationPrerequisiteState
import com.example.ui.components.*
import com.example.ui.map.MapContainer
import com.example.ui.sheets.*
import com.example.ui.theme.*

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RouteWakeScreen(
    viewModel: RouteWakeViewModel
) {
    val context = LocalContext.current

    // State flows
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val tripState by viewModel.tripState.collectAsStateWithLifecycle()
    val selectedDestination by viewModel.selectedDestination.collectAsStateWithLifecycle()
    val activeRadiusMeters by viewModel.activeRadiusMeters.collectAsStateWithLifecycle()
    val selectedTransport by viewModel.selectedTransport.collectAsStateWithLifecycle()
    val routeInfo by viewModel.routeInfo.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val deviceHeading by viewModel.deviceHeading.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val isCalculatingRoute by viewModel.isCalculatingRoute.collectAsStateWithLifecycle()

    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val tripHistory by viewModel.tripHistory.collectAsStateWithLifecycle()
    val completedSummary by viewModel.completedTripSummary.collectAsStateWithLifecycle()
    val prerequisiteState by viewModel.prerequisiteState.collectAsStateWithLifecycle()

    // Permissions Management via Google Accompanist Permissions library
    var permissionStatus by remember { mutableStateOf(checkPermissionStatus(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionDialogReason by remember { mutableStateOf(PermissionDialogReason.INITIAL) }

    // Dialog & System Resolution control
    var hasTriggeredSystemResolution by remember { mutableStateOf(false) }
    var showLocationDisabledDialog by remember { mutableStateOf(false) }
    var showHighAccuracyDialog by remember { mutableStateOf(false) }

    // System Settings Resolution Launcher (for ResolvableApiException)
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { _ ->
        viewModel.checkPrerequisites()
    }

    val criticalPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val locationAndNotifPermissionsState = rememberMultiplePermissionsState(
        permissions = criticalPermissions
    ) { _ ->
        permissionStatus = checkPermissionStatus(context)
        if (permissionStatus.hasFineLocation || permissionStatus.hasCoarseLocation) {
            viewModel.refreshCurrentLocation()
            viewModel.initiateFollowMode(forceRecenter = true)
        }
        if (permissionStatus.hasAllCritical) {
            showPermissionDialog = false
            if (permissionDialogReason == PermissionDialogReason.START_TRIP) {
                viewModel.startTrip()
            }
        } else {
            showPermissionDialog = true
        }
    }

    val backgroundLocationPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        rememberPermissionState(
            permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) { _ ->
            permissionStatus = checkPermissionStatus(context)
        }
    } else {
        null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionStatus = checkPermissionStatus(context)
                if (permissionStatus.hasFineLocation || permissionStatus.hasCoarseLocation) {
                    viewModel.refreshCurrentLocation()
                }
                viewModel.checkPrerequisites()
                viewModel.startCompass()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.stopCompass()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.stopCompass()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Automatically detect and launch native system resolution if needed, without endless loops
    LaunchedEffect(prerequisiteState) {
        when (val state = prerequisiteState) {
            is LocationPrerequisiteState.Satisfied -> {
                hasTriggeredSystemResolution = false
                showLocationDisabledDialog = false
                showHighAccuracyDialog = false
                showPermissionDialog = false
            }
            is LocationPrerequisiteState.LocationDisabled -> {
                if (state.resolvableException != null && !hasTriggeredSystemResolution) {
                    hasTriggeredSystemResolution = true
                    try {
                        val request = IntentSenderRequest.Builder(state.resolvableException.resolution.intentSender).build()
                        locationSettingsLauncher.launch(request)
                    } catch (_: Exception) {
                        showLocationDisabledDialog = true
                    }
                } else {
                    showLocationDisabledDialog = true
                }
            }
            is LocationPrerequisiteState.HighAccuracyDisabled -> {
                if (state.resolvableException != null && !hasTriggeredSystemResolution) {
                    hasTriggeredSystemResolution = true
                    try {
                        val request = IntentSenderRequest.Builder(state.resolvableException.resolution.intentSender).build()
                        locationSettingsLauncher.launch(request)
                    } catch (_: Exception) {
                        showHighAccuracyDialog = true
                    }
                } else {
                    showHighAccuracyDialog = true
                }
            }
            is LocationPrerequisiteState.PermissionRequired -> {
                if (!permissionStatus.hasFineLocation) {
                    showPermissionDialog = true
                }
            }
            LocationPrerequisiteState.Checking -> {}
        }
    }

    fun requestPermissions(reason: PermissionDialogReason) {
        permissionDialogReason = reason
        val currentStatus = checkPermissionStatus(context)
        permissionStatus = currentStatus

        if (!currentStatus.hasAllCritical) {
            // Use Accompanist to request missing critical permissions (foreground location + notifications)
            locationAndNotifPermissionsState.launchMultiplePermissionRequest()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !currentStatus.hasBackgroundLocation && backgroundLocationPermissionState != null) {
            // Android 10+: Foreground location is granted; now request background location
            backgroundLocationPermissionState.launchPermissionRequest()
        } else {
            if (reason == PermissionDialogReason.START_TRIP) {
                viewModel.startTrip()
            }
        }
    }

    LaunchedEffect(Unit) {
        val currentStatus = checkPermissionStatus(context)
        permissionStatus = currentStatus
        if (currentStatus.hasFineLocation || currentStatus.hasCoarseLocation) {
            viewModel.refreshCurrentLocation()
            viewModel.initiateFollowMode(forceRecenter = true)
        }
        if (!currentStatus.hasAllCritical) {
            locationAndNotifPermissionsState.launchMultiplePermissionRequest()
        } else {
            viewModel.checkPrerequisites()
        }
    }

    val vmRecenterTrigger by viewModel.recenterTrigger.collectAsStateWithLifecycle()
    var localRecenterTrigger by remember { mutableStateOf(0) }
    val effectiveRecenterTrigger = vmRecenterTrigger + localRecenterTrigger
    var zoomInTrigger by remember { mutableStateOf(0) }
    var zoomOutTrigger by remember { mutableStateOf(0) }
    var fitRouteTrigger by remember { mutableStateOf(0) }
    val isFollowMode by viewModel.isFollowMode.collectAsStateWithLifecycle()
    val initialCameraState = remember { viewModel.getInitialCameraState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // 1. Permanent Fullscreen Map Container at bottom of Z-order hierarchy
        MapContainer(
            currentLat = currentLocation?.latitude,
            currentLng = currentLocation?.longitude,
            destination = selectedDestination,
            alarmRadiusMeters = activeRadiusMeters,
            showAlarmRadius = true,
            routePoints = routeInfo?.points ?: emptyList(),
            isSatellite = settings.satelliteMap,
            initialCameraState = initialCameraState,
            isFollowMode = isFollowMode,
            onManualNavigation = { lat, lng, zoom ->
                viewModel.onManualMapNavigation(lat, lng, zoom)
            },
            onCameraChanged = { lat, lng, zoom ->
                if (!isFollowMode) {
                    viewModel.saveMapCameraState(lat, lng, zoom)
                }
            },
            onSaveCameraImmediate = { lat, lng, zoom ->
                if (!isFollowMode) {
                    viewModel.saveMapCameraState(lat, lng, zoom)
                }
            },
            recenterTrigger = effectiveRecenterTrigger,
            zoomInTrigger = zoomInTrigger,
            zoomOutTrigger = zoomOutTrigger,
            fitRouteTrigger = fitRouteTrigger,
            bearing = when {
                currentLocation?.hasBearing() == true && metrics.currentSpeedKmh >= 1.5 -> currentLocation?.bearing
                deviceHeading != null -> deviceHeading
                currentLocation?.hasBearing() == true -> currentLocation?.bearing
                else -> null
            },
            speedKmh = metrics.currentSpeedKmh,
            transportMode = selectedTransport,
            tripState = tripState,
            routeDistanceMeters = if (tripState != TripState.IDLE) metrics.distanceRemainingMeters else (routeInfo?.distanceMeters ?: 0.0),
            routeDurationSeconds = if (tripState != TripState.IDLE) metrics.etaSeconds else (routeInfo?.durationSeconds ?: 0L),
            gpsQualityLabel = metrics.gpsQuality.label,
            trafficEnabled = true,
            trafficData = routeInfo?.trafficData,
            onMapClick = { lat, lng ->
                viewModel.selectDestinationFromCoordinates(lat, lng)
            },
            onStartTrip = {
                viewModel.startTrip()
            },
            modifier = Modifier.fillMaxSize()
        )

        val isSheetOpen = activeTab != null
        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp

        // Fixed predetermined responsive sheet heights:
        val normalHeight = remember(screenHeight) {
            (screenHeight * 0.44f).coerceIn(340.dp, 370.dp)
        }
        val expandedHeight = remember(screenHeight) {
            (screenHeight * 0.65f).coerceIn(480.dp, 530.dp)
        }

        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        @OptIn(ExperimentalLayoutApi::class)
        val isKeyboardVisible = WindowInsets.isImeVisible || imeBottom > 0

        // Determine intentional target height:
        val targetSheetHeight: Dp = when (activeTab) {
            NavTab.COCKPIT -> {
                when {
                    tripState != TripState.IDLE -> expandedHeight
                    selectedDestination != null -> expandedHeight
                    isKeyboardVisible -> expandedHeight
                    else -> normalHeight
                }
            }
            NavTab.FAVORITES, NavTab.HISTORY, NavTab.SETTINGS -> expandedHeight
            null -> normalHeight
        }

        val animatedSheetHeight by animateDpAsState(
            targetValue = targetSheetHeight,
            animationSpec = tween(
                durationMillis = 300,
                easing = FastOutSlowInEasing
            ),
            label = "sheet_height_animation"
        )

        // Default position when sheet is closed: vertically centered on the right
        val defaultCenterBottomOffset = remember(screenHeight) {
            (screenHeight / 2) - 74.dp
        }

        // When sheet opens (cockpit, favorites, etc.): sits directly above the opened option card
        val targetButtonsBottomOffset = if (isSheetOpen) {
            (86.dp + targetSheetHeight + 12.dp).coerceAtMost(screenHeight - 160.dp)
        } else {
            defaultCenterBottomOffset
        }

        val animatedButtonsBottomOffset by animateDpAsState(
            targetValue = targetButtonsBottomOffset,
            animationSpec = tween(
                durationMillis = 300,
                easing = FastOutSlowInEasing
            ),
            label = "map_controls_bottom_offset"
        )

        // 2. Map Floating Action Controls (Right-Center -> sits just above opened option like cockpit: Recenter, Zoom In, Zoom Out)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = animatedButtonsBottomOffset, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    when (val state = prerequisiteState) {
                        is LocationPrerequisiteState.LocationDisabled -> {
                            if (state.resolvableException != null) {
                                try {
                                    val request = IntentSenderRequest.Builder(state.resolvableException.resolution.intentSender).build()
                                    locationSettingsLauncher.launch(request)
                                } catch (_: Exception) {
                                    showLocationDisabledDialog = true
                                }
                            } else {
                                showLocationDisabledDialog = true
                            }
                        }
                        is LocationPrerequisiteState.PermissionRequired -> {
                            requestPermissions(PermissionDialogReason.RECENTER)
                        }
                        is LocationPrerequisiteState.HighAccuracyDisabled -> {
                            if (state.resolvableException != null) {
                                try {
                                    val request = IntentSenderRequest.Builder(state.resolvableException.resolution.intentSender).build()
                                    locationSettingsLauncher.launch(request)
                                } catch (_: Exception) {
                                    showHighAccuracyDialog = true
                                }
                            } else {
                                showHighAccuracyDialog = true
                            }
                        }
                        else -> {
                            localRecenterTrigger++
                            viewModel.initiateFollowMode(forceRecenter = true)
                            viewModel.refreshCurrentLocation()
                        }
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceGlass)
                    .testTag("map_recenter_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recenter",
                    tint = if (prerequisiteState is LocationPrerequisiteState.Satisfied) ElectricBlue else AmberAction,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = {
                    zoomInTrigger++
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceGlass)
                    .testTag("map_zoom_in_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Zoom In",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = {
                    zoomOutTrigger++
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceGlass)
                    .testTag("map_zoom_out_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 2b. Location Prerequisite Warning Banner (shown when device location or high accuracy is disabled)
        if (prerequisiteState is LocationPrerequisiteState.LocationDisabled && activeTab == null) {
            val locState = prerequisiteState as LocationPrerequisiteState.LocationDisabled
            LocationPrerequisiteBanner(
                title = "Device Location is turned off",
                actionLabel = "ENABLE",
                onAction = {
                    if (locState.resolvableException != null) {
                        try {
                            val request = IntentSenderRequest.Builder(locState.resolvableException.resolution.intentSender).build()
                            locationSettingsLauncher.launch(request)
                        } catch (_: Exception) {
                            showLocationDisabledDialog = true
                        }
                    } else {
                        showLocationDisabledDialog = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (routeInfo != null && routeInfo!!.points.isNotEmpty()) 64.dp else 16.dp, start = 16.dp, end = 76.dp)
            )
        } else if (prerequisiteState is LocationPrerequisiteState.HighAccuracyDisabled && activeTab == null) {
            val highState = prerequisiteState as LocationPrerequisiteState.HighAccuracyDisabled
            LocationPrerequisiteBanner(
                title = "High accuracy GPS is unavailable",
                actionLabel = "SETTINGS",
                onAction = {
                    if (highState.resolvableException != null) {
                        try {
                            val request = IntentSenderRequest.Builder(highState.resolvableException.resolution.intentSender).build()
                            locationSettingsLauncher.launch(request)
                        } catch (_: Exception) {
                            showHighAccuracyDialog = true
                        }
                    } else {
                        showHighAccuracyDialog = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (routeInfo != null && routeInfo!!.points.isNotEmpty()) 64.dp else 16.dp, start = 16.dp, end = 76.dp)
            )
        } else if (!permissionStatus.hasAllCritical && tripState == TripState.IDLE && activeTab == null) {
            PermissionWarningBanner(
                status = permissionStatus,
                onEnableClicked = {
                    requestPermissions(PermissionDialogReason.BANNER)
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (routeInfo != null && routeInfo!!.points.isNotEmpty()) 64.dp else 16.dp, start = 16.dp, end = 76.dp)
            )
        }

        // 3. Floating Active Trip HUD Overlay (Top-Center)
        ActiveTripOverlay(
            tripState = tripState,
            destination = selectedDestination,
            radiusMeters = activeRadiusMeters,
            transportMode = selectedTransport,
            metrics = metrics,
            isEcoMode = settings.ecoMode,
            onCancelTrip = { viewModel.stopTrip() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
        )

        // 4. Slide-up Sheet Container (Single stable floating card above bottom navigation)
        val currentTabTitle = when (activeTab) {
            NavTab.COCKPIT -> "Navigation Cockpit"
            NavTab.FAVORITES -> "Favorites"
            NavTab.HISTORY -> "Trip History"
            NavTab.SETTINGS -> "Settings"
            null -> ""
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 86.dp)
        ) {
            SheetContainer(
                isOpen = isSheetOpen,
                title = currentTabTitle,
                onClose = { viewModel.closeSheet() },
                sheetHeight = animatedSheetHeight
            ) {
                AnimatedContent(
                    targetState = activeTab,
                    transitionSpec = {
                        val forward = (targetState?.ordinal ?: 0) > (initialState?.ordinal ?: 0)
                        (fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)) +
                         slideInHorizontally(
                             initialOffsetX = { width -> if (forward) (width * 0.08f).toInt() else (-width * 0.08f).toInt() },
                             animationSpec = tween(220, easing = FastOutSlowInEasing)
                         )) togetherWith
                        (fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                         slideOutHorizontally(
                             targetOffsetX = { width -> if (forward) (-width * 0.08f).toInt() else (width * 0.08f).toInt() },
                             animationSpec = tween(180, easing = FastOutLinearInEasing)
                         ))
                    },
                    label = "sheet_tab_content_transition",
                    modifier = Modifier.fillMaxSize()
                ) { targetTab ->
                    when (targetTab) {
                        NavTab.COCKPIT -> {
                            CockpitSheet(
                                tripState = tripState,
                                selectedDestination = selectedDestination,
                                activeRadiusMeters = activeRadiusMeters,
                                selectedTransport = selectedTransport,
                                routeInfo = routeInfo,
                                metrics = metrics,
                                searchQuery = searchQuery,
                                searchResults = searchResults,
                                recentSearches = recentSearches,
                                isSearching = isSearching,
                                isDevSimulationActive = settings.isSimulationActive,
                                isCalculatingRoute = isCalculatingRoute,
                                permissionStatus = permissionStatus,
                                onEnablePermissions = {
                                    requestPermissions(PermissionDialogReason.BANNER)
                                },
                                onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
                                onSearchSubmitted = { viewModel.onSearchSubmitted(it) },
                                onDestinationSelected = { dest ->
                                    viewModel.selectDestination(dest)
                                },
                                onClearDestination = { viewModel.clearSelectedDestination() },
                                onRadiusChanged = { viewModel.setRadius(it) },
                                onTransportChanged = { viewModel.setTransport(it) },
                                onStartTrip = {
                                    if (!permissionStatus.hasAllCritical) {
                                        permissionDialogReason = PermissionDialogReason.START_TRIP
                                        showPermissionDialog = true
                                    } else {
                                        viewModel.startTrip()
                                    }
                                },
                                onStopTrip = { viewModel.stopTrip() },
                                onToggleDevSimulation = { active, speed, mult ->
                                    viewModel.toggleDevSimulation(active, speed, mult)
                                }
                            )
                        }
                        NavTab.FAVORITES -> {
                            FavoritesSheet(
                                favorites = favorites,
                                onSelectFavorite = { dest, rad ->
                                    viewModel.selectDestination(dest)
                                    viewModel.setRadius(rad)
                                    viewModel.openTab(NavTab.COCKPIT)
                                },
                                onDeleteFavorite = { viewModel.deleteFavorite(it) },
                                onAddCurrentAsFavorite = {
                                    selectedDestination?.let { viewModel.saveFavorite(it, activeRadiusMeters) }
                                },
                                hasActiveDestination = selectedDestination != null
                            )
                        }
                        NavTab.HISTORY -> {
                            HistorySheet(
                                trips = tripHistory,
                                onReuseDestination = { dest, rad ->
                                    viewModel.selectDestination(dest)
                                    viewModel.setRadius(rad)
                                    viewModel.openTab(NavTab.COCKPIT)
                                },
                                onDeleteTrip = { viewModel.deleteHistoryTrip(it) },
                                onClearAll = { viewModel.clearAllHistory() }
                            )
                        }
                        NavTab.SETTINGS -> {
                            SettingsSheet(
                                settings = settings,
                                onUpdateEcoMode = { viewModel.updateEcoMode(it) },
                                onUpdateAlarmTone = { viewModel.updateAlarmTone(it) },
                                onUpdateVibration = { viewModel.updateVibration(it) },
                                onUpdateDefaultRadius = { viewModel.updateDefaultRadius(it) },
                                onUpdateSatellite = { viewModel.updateSatellite(it) },
                                onTestAlarmSound = { viewModel.testAlarmTone(it) }
                            )
                        }
                        null -> {
                            Spacer(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        // 5. Persistent Bottom Navigation Bar (4 tabs: Cockpit, Favorites, History, Settings)
        BottomNavBar(
            activeTab = activeTab,
            onTabSelected = { tab ->
                viewModel.onTabClicked(tab)
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 6. Arrival Alert Dialog ("YOU'RE HERE" + Alarm + Vibration + Dismiss + Snooze 1M)
        if (tripState == TripState.ARRIVED && selectedDestination != null) {
            ArrivalDialog(
                destination = selectedDestination!!,
                distanceMeters = metrics.distanceRemainingMeters,
                onDismiss = { viewModel.dismissArrival() },
                onSnooze = { viewModel.snoozeArrival() }
            )
        }

        // 7. Trip Completion Summary Dialog
        completedSummary?.let { summary ->
            TripCompletedDialog(
                tripSummary = summary,
                onDone = { viewModel.clearCompletedSummary() }
            )
        }

        // 8. Location Service Disabled Dialog (Custom fallback / manual dialog)
        if (showLocationDisabledDialog && prerequisiteState is LocationPrerequisiteState.LocationDisabled) {
            val state = prerequisiteState as LocationPrerequisiteState.LocationDisabled
            LocationServiceDisabledDialog(
                onEnableLocation = {
                    if (state.resolvableException != null) {
                        try {
                            val request = IntentSenderRequest.Builder(state.resolvableException.resolution.intentSender).build()
                            locationSettingsLauncher.launch(request)
                        } catch (_: Exception) {
                            LocationPrerequisiteManager.openLocationSettings(context)
                        }
                    } else {
                        LocationPrerequisiteManager.openLocationSettings(context)
                    }
                },
                onOpenSettings = {
                    LocationPrerequisiteManager.openLocationSettings(context)
                },
                onDismiss = {
                    showLocationDisabledDialog = false
                }
            )
        }

        // 9. High Accuracy Disabled Dialog
        if (showHighAccuracyDialog && prerequisiteState is LocationPrerequisiteState.HighAccuracyDisabled) {
            val state = prerequisiteState as LocationPrerequisiteState.HighAccuracyDisabled
            HighAccuracyDisabledDialog(
                onEnableHighAccuracy = {
                    if (state.resolvableException != null) {
                        try {
                            val request = IntentSenderRequest.Builder(state.resolvableException.resolution.intentSender).build()
                            locationSettingsLauncher.launch(request)
                        } catch (_: Exception) {
                            LocationPrerequisiteManager.openLocationSettings(context)
                        }
                    } else {
                        LocationPrerequisiteManager.openLocationSettings(context)
                    }
                },
                onOpenSettings = {
                    LocationPrerequisiteManager.openLocationSettings(context)
                },
                onDismiss = {
                    showHighAccuracyDialog = false
                }
            )
        }

        // 10. Permission Rationale & Request Dialog
        if (showPermissionDialog) {
            PermissionRationaleDialog(
                status = permissionStatus,
                reason = permissionDialogReason,
                onRequestPermissions = {
                    val currentStatus = checkPermissionStatus(context)
                    permissionStatus = currentStatus
                    if (!currentStatus.hasAllCritical) {
                        locationAndNotifPermissionsState.launchMultiplePermissionRequest()
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !currentStatus.hasBackgroundLocation && backgroundLocationPermissionState != null) {
                        backgroundLocationPermissionState.launchPermissionRequest()
                    } else {
                        showPermissionDialog = false
                    }
                },
                onOpenSettings = {
                    openAppSettings(context)
                },
                onDismiss = {
                    showPermissionDialog = false
                }
            )
        }
    }
}

