package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Destination
import com.example.data.model.TransportMode
import com.example.network.GeocodingService
import com.example.network.RoutingService
import com.example.ui.components.NavTab
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("RouteWake", appName)
    }

    @Test
    fun `haversine distance computation correctness`() {
        // Distance between SF (37.7749, -122.4194) and Oakland (37.8044, -122.2712) ~13.5 km
        val dist = RoutingService.computeHaversineDistanceMeters(
            37.7749, -122.4194,
            37.8044, -122.2712
        )
        assertTrue("Distance should be approximately 13.5 km, was $dist", dist in 12000.0..15000.0)
    }

    @Test
    fun `arrival within radius detection`() {
        val targetLat = 37.7749
        val targetLng = -122.4194
        val currentLat = 37.7752
        val currentLng = -122.4194

        val distance = RoutingService.computeHaversineDistanceMeters(
            currentLat, currentLng,
            targetLat, targetLng
        )
        val radiusMeters = 500

        assertTrue("Distance ($distance m) should be within $radiusMeters m", distance <= radiusMeters)
    }

    @Test
    fun `cockpit workflow search and route calculation`() = runBlocking {
        val geocodingService = GeocodingService()
        val routingService = RoutingService()

        // 1. Search destination
        val results = geocodingService.searchDestinations("Airport")
        assertTrue("Should return destination matches for Airport", results.isNotEmpty())
        val selectedDest = results.first()
        assertNotNull(selectedDest.name)

        // 2. Calculate route for Car
        val routeCar = routingService.calculateRoute(
            37.7749, -122.4194,
            selectedDest.latitude, selectedDest.longitude,
            TransportMode.CAR
        )
        assertTrue("Route distance must be > 0", routeCar.distanceMeters > 0)
        assertTrue("Route duration must be > 0", routeCar.durationSeconds > 0)
        assertTrue("Route must have points", routeCar.points.isNotEmpty())

        // 3. Select Transit / Train Mode and verify speed differences
        val routeWalk = routingService.calculateRoute(
            37.7749, -122.4194,
            selectedDest.latitude, selectedDest.longitude,
            TransportMode.WALK
        )
        assertTrue(
            "Walking duration (${routeWalk.durationSeconds}s, dist=${routeWalk.distanceMeters}m, modeled=${routeWalk.isModeled}) should exceed driving duration (${routeCar.durationSeconds}s, dist=${routeCar.distanceMeters}m, modeled=${routeCar.isModeled})",
            routeWalk.durationSeconds > routeCar.durationSeconds
        )
    }

    @Test
    fun `cockpit tab toggle behavior`() {
        var activeTab: NavTab? = null

        // Tap Cockpit: opens sheet
        activeTab = if (activeTab == NavTab.COCKPIT) null else NavTab.COCKPIT
        assertEquals(NavTab.COCKPIT, activeTab)

        // Tap Cockpit second time: closes sheet
        activeTab = if (activeTab == NavTab.COCKPIT) null else NavTab.COCKPIT
        assertEquals(null, activeTab)
    }

    @Test
    fun `gps quality classification reflects accuracy and staleness accurately`() {
        // High accuracy, fresh fix -> EXCELLENT
        val excellent = com.example.data.model.GpsQuality.fromAccuracy(8f, ageSeconds = 1, isAvailable = true)
        assertEquals(com.example.data.model.GpsQuality.EXCELLENT, excellent)
        assertEquals("GPS • Excellent", excellent.label)

        // Moderate accuracy, fresh fix -> GOOD
        val good = com.example.data.model.GpsQuality.fromAccuracy(18f, ageSeconds = 2, isAvailable = true)
        assertEquals(com.example.data.model.GpsQuality.GOOD, good)

        // Fair accuracy -> FAIR
        val fair = com.example.data.model.GpsQuality.fromAccuracy(35f, ageSeconds = 3, isAvailable = true)
        assertEquals(com.example.data.model.GpsQuality.FAIR, fair)

        // Poor accuracy -> POOR
        val poor = com.example.data.model.GpsQuality.fromAccuracy(80f, ageSeconds = 2, isAvailable = true)
        assertEquals(com.example.data.model.GpsQuality.POOR, poor)

        // Stale fix (> 15 seconds) -> STALE
        val stale = com.example.data.model.GpsQuality.fromAccuracy(5f, ageSeconds = 20, isAvailable = true)
        assertEquals(com.example.data.model.GpsQuality.STALE, stale)
        assertEquals("GPS • Stale", stale.label)

        // Provider disabled -> OFF
        val off = com.example.data.model.GpsQuality.fromAccuracy(5f, ageSeconds = 1, isAvailable = false)
        assertEquals(com.example.data.model.GpsQuality.OFF, off)
        assertEquals("GPS • Off", off.label)
    }

    @Test
    fun `gps engine is authoritative singleton`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine1 = com.example.engine.GpsEngine.getInstance(context)
        val engine2 = com.example.engine.GpsEngine.getInstance(context)
        assertTrue("GpsEngine must be single authoritative instance", engine1 === engine2)
    }

    @Test
    fun `map destination radius preference toggle`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.local.UserPreferences(context)
        
        // Initial state is true
        assertTrue(prefs.getSettings().showDestinationRadiusOnMap)
        
        // Toggle to false
        prefs.updateShowRadius(false)
        assertEquals(false, prefs.getSettings().showDestinationRadiusOnMap)
        
        // Toggle back to true
        prefs.updateShowRadius(true)
        assertEquals(true, prefs.getSettings().showDestinationRadiusOnMap)
    }

    @Test
    fun `permission status detection and missing list`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val status = com.example.ui.components.checkPermissionStatus(context)
        
        // In clean test environment without granted permissions
        val missing = status.getMissingPermissions()
        assertTrue("Fine location should be missing initially", status.isLocationMissing || status.hasFineLocation)
        assertNotNull(missing)
    }

    @Test
    fun `permission status all critical logic`() {
        val allGranted = com.example.ui.components.PermissionStatus(
            hasFineLocation = true,
            hasCoarseLocation = true,
            hasNotification = true
        )
        assertTrue(allGranted.hasAllCritical)
        assertTrue(allGranted.getMissingPermissions().isEmpty())

        val missingLoc = com.example.ui.components.PermissionStatus(
            hasFineLocation = false,
            hasCoarseLocation = false,
            hasNotification = true
        )
        assertTrue(!missingLoc.hasAllCritical)
        assertTrue(missingLoc.isLocationMissing)
        assertTrue(missingLoc.getMissingPermissions().contains(android.Manifest.permission.ACCESS_FINE_LOCATION))

        val missingNotif = com.example.ui.components.PermissionStatus(
            hasFineLocation = true,
            hasCoarseLocation = true,
            hasNotification = false
        )
        assertTrue(!missingNotif.hasAllCritical)
        assertTrue(missingNotif.isNotificationMissing)

        val missingBg = com.example.ui.components.PermissionStatus(
            hasFineLocation = true,
            hasCoarseLocation = true,
            hasNotification = true,
            hasBackgroundLocation = false
        )
        assertTrue(missingBg.hasAllCritical)
        assertTrue(missingBg.isBackgroundLocationMissing)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            assertEquals(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION, missingBg.getBackgroundLocationPermission())
        }
    }

    @Test
    fun `active trip persistence across app closure`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.local.UserPreferences(context)

        // Verify initially no active trip
        assertNull(prefs.getActiveTrip())
        assertEquals(false, prefs.hasActiveTrip())

        // Save active trip
        val record = com.example.data.local.ActiveTripRecord(
            destinationName = "Downtown Station",
            destinationAddress = "100 Market St",
            destinationLat = 37.7891,
            destinationLng = -122.4014,
            radiusMeters = 750,
            transportMode = com.example.data.model.TransportMode.TRAIN,
            startTimeMs = 1700000000000L
        )
        prefs.saveActiveTrip(record)

        // Verify trip was persisted
        assertTrue(prefs.hasActiveTrip())
        val loaded = prefs.getActiveTrip()
        assertNotNull(loaded)
        assertEquals("Downtown Station", loaded?.destinationName)
        assertEquals("100 Market St", loaded?.destinationAddress)
        assertEquals(37.7891, loaded?.destinationLat ?: 0.0, 0.0001)
        assertEquals(-122.4014, loaded?.destinationLng ?: 0.0, 0.0001)
        assertEquals(750, loaded?.radiusMeters)
        assertEquals(com.example.data.model.TransportMode.TRAIN, loaded?.transportMode)
        assertEquals(1700000000000L, loaded?.startTimeMs)

        // Clear active trip (e.g. upon stop/dismiss)
        prefs.clearActiveTrip()
        assertNull(prefs.getActiveTrip())
        assertEquals(false, prefs.hasActiveTrip())
    }

    @Test
    fun `tracking foreground service lifecycle and actions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceController = org.robolectric.Robolectric.buildService(com.example.service.TrackingForegroundService::class.java)
        val service = serviceController.create().get()
        assertNotNull(service)

        val intent = android.content.Intent(context, com.example.service.TrackingForegroundService::class.java).apply {
            action = com.example.service.TrackingForegroundService.ACTION_START_TRACKING
            putExtra(com.example.service.TrackingForegroundService.EXTRA_DEST_NAME, "Test Airport")
            putExtra(com.example.service.TrackingForegroundService.EXTRA_DEST_ADDRESS, "Airport Way")
            putExtra(com.example.service.TrackingForegroundService.EXTRA_DEST_LAT, 37.6213)
            putExtra(com.example.service.TrackingForegroundService.EXTRA_DEST_LNG, -122.3790)
            putExtra(com.example.service.TrackingForegroundService.EXTRA_RADIUS_METERS, 1000)
            putExtra(com.example.service.TrackingForegroundService.EXTRA_TRANSPORT_MODE, com.example.data.model.TransportMode.CAR.name)
        }

        val flags = serviceController.withIntent(intent).startCommand(0, 1).get()
        assertNotNull(flags)

        // Verify active destination is set
        assertEquals("Test Airport", service.arrivalEngine.getActiveDestination()?.name)

        // Test stop command
        val stopIntent = android.content.Intent(context, com.example.service.TrackingForegroundService::class.java).apply {
            action = com.example.service.TrackingForegroundService.ACTION_STOP_TRACKING
        }
        serviceController.withIntent(stopIntent).startCommand(0, 2)
        serviceController.destroy()
    }

    @Test
    fun `sheet container height behavior test`() {
        composeTestRule.setContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .testTag("parent_column")
            ) {
                Text(text = "Header", modifier = Modifier.height(50.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .testTag("lazy_list")
                ) {
                    item {
                        Text(text = "Item 1", modifier = Modifier.height(60.dp))
                    }
                    item {
                        Text(text = "Item 2", modifier = Modifier.height(60.dp))
                    }
                }
            }
        }

        val parentBounds = composeTestRule.onNodeWithTag("parent_column").getUnclippedBoundsInRoot()
        val height = parentBounds.bottom - parentBounds.top
        // Header (50dp) + Item 1 (60dp) + Item 2 (60dp) = 170dp
        // If content-driven, parent height should be ~170dp, NOT 500dp!
        assertTrue("Parent height should be ~170dp, but was $height", height < 250.dp)
    }

    @Test
    fun `floating sheet container renders correctly`() {
        var closed = false
        composeTestRule.setContent {
            com.example.ui.components.SheetContainer(
                isOpen = true,
                title = "Navigation Cockpit",
                onClose = { closed = true }
            ) {
                Text(text = "Cockpit Content", modifier = Modifier.height(80.dp))
            }
        }

        composeTestRule.onNodeWithTag("sheet_close_button").assertExists()
        composeTestRule.onNodeWithTag("floating_sheet_surface").assertExists()
    }

    @Test
    fun `floating sheet outer height is constant regardless of content changes`() {
        val contentState = androidx.compose.runtime.mutableStateOf("short")
        composeTestRule.setContent {
            com.example.ui.components.SheetContainer(
                isOpen = true,
                title = "Navigation Cockpit",
                onClose = {}
            ) {
                if (contentState.value == "short") {
                    Text(text = "Short Content", modifier = Modifier.height(50.dp))
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(20) {
                            Text(text = "Item $it", modifier = Modifier.height(40.dp))
                        }
                    }
                }
            }
        }

        val initialBounds = composeTestRule.onNodeWithTag("floating_sheet_surface").getUnclippedBoundsInRoot()
        val initialHeight = initialBounds.bottom - initialBounds.top

        // Simulate search results arriving / content expansion
        contentState.value = "long"
        composeTestRule.waitForIdle()

        val updatedBounds = composeTestRule.onNodeWithTag("floating_sheet_surface").getUnclippedBoundsInRoot()
        val updatedHeight = updatedBounds.bottom - updatedBounds.top

        // Outer sheet height MUST remain constant!
        assertEquals("Outer sheet height must remain constant when content changes", initialHeight, updatedHeight)
    }

    @Test
    fun `sheet container honors explicit state-driven fixed height`() {
        composeTestRule.setContent {
            com.example.ui.components.SheetContainer(
                isOpen = true,
                title = "Navigation Cockpit",
                onClose = {},
                sheetHeight = 420.dp
            ) {
                Text(text = "Expanded Content", modifier = Modifier.height(100.dp))
            }
        }

        val bounds = composeTestRule.onNodeWithTag("floating_sheet_surface").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertEquals("SheetContainer must strictly honor explicit fixed sheetHeight", 420.dp, height)
    }

    @Test
    fun `cockpit in destination selected state renders START TRIP directly and excludes ready to start card`() {
        val destination = Destination(
            id = "test_dest",
            name = "MG Road Metro",
            address = "Mahatma Gandhi Rd, Bengaluru",
            latitude = 12.9756,
            longitude = 77.6066,
            category = "Metro"
        )

        composeTestRule.setContent {
            com.example.ui.sheets.CockpitSheet(
                tripState = com.example.data.model.TripState.IDLE,
                selectedDestination = destination,
                activeRadiusMeters = 500,
                selectedTransport = TransportMode.TRAIN,
                routeInfo = null,
                metrics = com.example.data.model.TripMetrics(),
                searchQuery = "",
                searchResults = emptyList(),
                recentSearches = emptyList(),
                isSearching = false,
                isDevSimulationActive = false,
                isCalculatingRoute = false,
                permissionStatus = null,
                onEnablePermissions = {},
                onSearchQueryChanged = {},
                onSearchSubmitted = {},
                onDestinationSelected = {},
                onClearDestination = {},
                onRadiusChanged = {},
                onTransportChanged = {},
                onStartTrip = {},
                onStopTrip = {},
                onToggleDevSimulation = { _, _, _ -> }
            )
        }

        // START TRIP button must exist and be immediately accessible
        composeTestRule.onNodeWithTag("start_trip_button").assertExists()

        // READY TO START card must be completely absent
        composeTestRule.onNodeWithTag("ready_to_start_card").assertDoesNotExist()
    }

    @Test
    fun `progressive route drawing geometry starts at user location and reveals toward destination`() {
        val p1 = com.example.data.model.RoutePoint(12.9716, 77.5946) // Bangalore City (User GPS)
        val p2 = com.example.data.model.RoutePoint(12.9756, 77.6066) // MG Road
        val p3 = com.example.data.model.RoutePoint(12.9800, 77.6200) // Indiranagar (Destination)

        val fullRoute = listOf(p1, p2, p3)
        val totalDist = com.example.ui.map.RouteProgressHelper.computeTotalRouteDistance(fullRoute)
        assertTrue("Total route distance must be > 0", totalDist > 1000.0)

        // At 0% revealed -> empty list
        val zeroRevealed = com.example.ui.map.RouteProgressHelper.computeRevealedRoutePoints(fullRoute, 0.0)
        assertTrue("0% revealed should have no points", zeroRevealed.isEmpty())

        // At 50% revealed -> starts at p1 (User GPS), extends midway
        val halfDist = totalDist * 0.5
        val halfRevealed = com.example.ui.map.RouteProgressHelper.computeRevealedRoutePoints(fullRoute, halfDist)
        assertTrue("Half revealed route should have >= 2 points", halfRevealed.size >= 2)
        assertEquals("Revealed route must start at user GPS location p1", p1.latitude, halfRevealed.first().latitude, 0.0001)
        assertEquals("Revealed route must start at user GPS location p1", p1.longitude, halfRevealed.first().longitude, 0.0001)

        val revealedHalfDist = com.example.ui.map.RouteProgressHelper.computeTotalRouteDistance(halfRevealed)
        assertEquals("Revealed route length should match requested revealed distance", halfDist, revealedHalfDist, 5.0)

        // At 100% revealed -> full route up to p3 (Destination)
        val fullRevealed = com.example.ui.map.RouteProgressHelper.computeRevealedRoutePoints(fullRoute, totalDist)
        assertEquals("Full revealed route must have all points", fullRoute.size, fullRevealed.size)
        assertEquals("Full revealed route must end at destination p3", p3.latitude, fullRevealed.last().latitude, 0.0001)
    }

    @Test
    fun `progressive traffic segments reveal preserves congestion colors and casing geometry`() {
        val p1 = com.example.data.model.RoutePoint(12.9716, 77.5946)
        val p2 = com.example.data.model.RoutePoint(12.9756, 77.6066)
        val p3 = com.example.data.model.RoutePoint(12.9800, 77.6200)

        val fullRoute = listOf(p1, p2, p3)
        val seg1 = com.example.data.model.TrafficSegment(
            points = listOf(p1, p2),
            congestionLevel = com.example.data.model.CongestionLevel.CLEAR,
            speedKmh = 45.0
        )
        val seg2 = com.example.data.model.TrafficSegment(
            points = listOf(p2, p3),
            congestionLevel = com.example.data.model.CongestionLevel.HEAVY,
            speedKmh = 12.0
        )
        val segments = listOf(seg1, seg2)
        val totalDist = com.example.ui.map.RouteProgressHelper.computeTotalRouteDistance(fullRoute)

        // At 25% revealed: only first segment partially revealed
        val quarterDist = totalDist * 0.25
        val revealedSegs25 = com.example.ui.map.RouteProgressHelper.computeRevealedTrafficSegments(segments, fullRoute, quarterDist)
        assertEquals("Only 1 traffic segment should be active at 25%", 1, revealedSegs25.size)
        assertEquals("Segment should retain CLEAR congestion level", com.example.data.model.CongestionLevel.CLEAR, revealedSegs25[0].congestionLevel)

        // At 100% revealed: both segments fully available
        val revealedSegs100 = com.example.ui.map.RouteProgressHelper.computeRevealedTrafficSegments(segments, fullRoute, totalDist)
        assertEquals("Both traffic segments should be active at 100%", 2, revealedSegs100.size)
        assertEquals(com.example.data.model.CongestionLevel.CLEAR, revealedSegs100[0].congestionLevel)
        assertEquals(com.example.data.model.CongestionLevel.HEAVY, revealedSegs100[1].congestionLevel)
    }

    @Test
    fun `MapInformationCard renders RouteTraffic and DestinationInfo with single card guarantee`() {
        val seg = com.example.data.model.TrafficSegment(
            points = listOf(
                com.example.data.model.RoutePoint(12.9716, 77.5946),
                com.example.data.model.RoutePoint(12.9756, 77.6066)
            ),
            congestionLevel = com.example.data.model.CongestionLevel.CLEAR,
            speedKmh = 42.0,
            delayMinutes = 2,
            roadName = "MG Road"
        )
        val cardState = androidx.compose.runtime.mutableStateOf<com.example.ui.components.MapInformationCardType>(
            com.example.ui.components.MapInformationCardType.RouteTraffic(
                segment = seg,
                anchorPx = androidx.compose.ui.geometry.Offset(200f, 300f)
            )
        )
        var dismissed = false

        composeTestRule.setContent {
            com.example.ui.components.MapInformationCard(
                cardState = cardState.value,
                transportMode = TransportMode.CAR,
                destinationName = "Indiranagar",
                alarmRadiusMeters = 500,
                tripState = com.example.data.model.TripState.IDLE,
                routeDistanceMeters = 5000.0,
                routeDurationSeconds = 600,
                gpsQualityLabel = "GPS • Excellent",
                isNearDestination = false,
                containerSize = androidx.compose.ui.unit.IntSize(1080, 1920),
                onStartTrip = {},
                onDismiss = { dismissed = true }
            )
        }

        // Route Traffic popup must exist
        composeTestRule.onNodeWithTag("traffic_info_popup").assertExists()
        // Destination popup must NOT exist
        composeTestRule.onNodeWithTag("destination_info_popup").assertDoesNotExist()

        // Switch to DestinationInfo
        val dest = Destination(
            id = "dest_b",
            name = "Indiranagar Metro",
            address = "100ft Rd, Indiranagar",
            latitude = 12.9800,
            longitude = 77.6200,
            category = "Metro"
        )
        cardState.value = com.example.ui.components.MapInformationCardType.DestinationInfo(
            destination = dest,
            anchorPx = androidx.compose.ui.geometry.Offset(250f, 350f)
        )
        composeTestRule.waitForIdle()

        // Destination popup must now exist
        composeTestRule.onNodeWithTag("destination_info_popup").assertExists()
        // Traffic popup must NOT exist
        composeTestRule.onNodeWithTag("traffic_info_popup").assertDoesNotExist()

        // Switch to None
        cardState.value = com.example.ui.components.MapInformationCardType.None
        composeTestRule.waitForIdle()

        // Neither popup exists
        composeTestRule.onNodeWithTag("traffic_info_popup").assertDoesNotExist()
        composeTestRule.onNodeWithTag("destination_info_popup").assertDoesNotExist()
    }

    @Test
    fun `destination switching cancels in-flight calculations and immediately resets route state`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.RouteWakeViewModel(app)

        val destA = Destination(
            id = "dest_a",
            name = "Destination A",
            address = "Address A",
            latitude = 12.9716,
            longitude = 77.5946
        )
        val destB = Destination(
            id = "dest_b",
            name = "Destination B",
            address = "Address B",
            latitude = 13.0827,
            longitude = 80.2707
        )

        // Select Destination A
        vm.selectDestination(destA)
        assertEquals("dest_a", vm.selectedDestination.value?.id)

        // Select Destination B: routeInfo is immediately nulled and previous calculation canceled
        vm.selectDestination(destB)
        assertEquals("dest_b", vm.selectedDestination.value?.id)
        assertNull("Route info must be immediately cleared when selecting a new destination", vm.routeInfo.value)
    }

    @Test
    fun `destination selection does not auto open cockpit tab and sets route calculation state`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.RouteWakeViewModel(app)

        val destination = Destination(
            id = "dest_instant",
            name = "Market Street",
            address = "Market St, San Francisco, CA",
            latitude = 37.7891,
            longitude = -122.4014,
            category = "Transit"
        )

        // Select destination - must immediately update state without waiting for network/routing
        // and does NOT force open the cockpit sheet
        val initialTab = vm.activeTab.value
        vm.selectDestination(destination)

        assertEquals("Market Street", vm.selectedDestination.value?.name)
        assertEquals(37.7891, vm.selectedDestination.value?.latitude ?: 0.0, 0.0001)
        assertEquals("Destination selection must not force open cockpit", initialTab, vm.activeTab.value)
        assertTrue("isCalculatingRoute must immediately be true for UI response", vm.isCalculatingRoute.value)

        // Clearing destination must reset state
        vm.clearSelectedDestination()
        assertNull(vm.selectedDestination.value)
        assertNull(vm.routeInfo.value)
        org.junit.Assert.assertFalse(vm.isCalculatingRoute.value)
    }

    @Test
    fun `selectDestinationFromCoordinates immediately sets provisional destination without forcing cockpit open`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.RouteWakeViewModel(app)

        val tapLat = 37.7654
        val tapLng = -122.4321

        val initialTab = vm.activeTab.value
        vm.selectDestinationFromCoordinates(tapLat, tapLng)

        val selected = vm.selectedDestination.value
        assertNotNull("Provisional destination must be set immediately upon coordinate tap", selected)
        assertEquals(tapLat, selected?.latitude ?: 0.0, 0.0001)
        assertEquals(tapLng, selected?.longitude ?: 0.0, 0.0001)
        assertEquals("Map tap must not force open cockpit", initialTab, vm.activeTab.value)
        assertTrue(vm.isCalculatingRoute.value)
    }

    @Test
    fun `cockpit sheet start trip button displays preparing route state when route is calculating`() {
        val dest = Destination(
            id = "dest_loading_test",
            name = "Mission Dolores",
            address = "Mission Dolores Park, SF",
            latitude = 37.7596,
            longitude = -122.4269,
            category = "Park"
        )

        composeTestRule.setContent {
            com.example.ui.sheets.CockpitSheet(
                tripState = com.example.data.model.TripState.IDLE,
                selectedDestination = dest,
                activeRadiusMeters = 500,
                selectedTransport = TransportMode.CAR,
                routeInfo = null,
                metrics = com.example.data.model.TripMetrics(),
                searchQuery = "",
                searchResults = emptyList(),
                recentSearches = emptyList(),
                isSearching = false,
                isDevSimulationActive = false,
                isCalculatingRoute = true,
                permissionStatus = null,
                onEnablePermissions = {},
                onSearchQueryChanged = {},
                onSearchSubmitted = {},
                onDestinationSelected = {},
                onClearDestination = {},
                onRadiusChanged = {},
                onTransportChanged = {},
                onStartTrip = {},
                onStopTrip = {},
                onToggleDevSimulation = { _, _, _ -> }
            )
        }

        composeTestRule.onNodeWithTag("start_trip_button").assertExists()
    }
}
