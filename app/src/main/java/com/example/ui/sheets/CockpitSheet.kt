package com.example.ui.sheets

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CongestionLevel
import com.example.data.model.Destination
import com.example.data.model.RouteInfo
import com.example.data.model.TrafficSegment
import com.example.data.model.TransportMode
import com.example.data.model.TripMetrics
import com.example.data.model.TripState
import com.example.ui.components.PermissionStatus
import com.example.ui.components.PermissionWarningBanner
import com.example.ui.components.formatDistance
import com.example.ui.components.formatEta
import com.example.ui.components.getTransportIcon
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CockpitSheet(
    tripState: TripState,
    selectedDestination: Destination?,
    activeRadiusMeters: Int,
    selectedTransport: TransportMode,
    routeInfo: RouteInfo?,
    metrics: TripMetrics,
    searchQuery: String,
    searchResults: List<Destination>,
    recentSearches: List<String>,
    isSearching: Boolean,
    isDevSimulationActive: Boolean = false,
    isCalculatingRoute: Boolean = false,
    permissionStatus: PermissionStatus? = null,
    onEnablePermissions: () -> Unit = {},
    onSearchQueryChanged: (String) -> Unit,
    onSearchSubmitted: (String) -> Unit,
    onDestinationSelected: (Destination) -> Unit,
    onClearDestination: () -> Unit,
    onRadiusChanged: (Int) -> Unit,
    onTransportChanged: (TransportMode) -> Unit,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onToggleDevSimulation: (Boolean, Double, Int) -> Unit = { _, _, _ -> }
) {
    val focusManager = LocalFocusManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("cockpit_scroll_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 6.dp)
    ) {
        // Search Section if no active trip or wanting to change
        if (tripState == TripState.IDLE && selectedDestination == null) {
            item {
                // Search Input Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    placeholder = {
                        Text(
                            text = "Search station, airport, place...",
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isSearching) ElectricBlue else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        } else if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = ElectricBlue,
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        onSearchSubmitted(searchQuery)
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 58.dp)
                        .testTag("destination_search_input"),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurfaceVariant,
                        unfocusedContainerColor = DarkSurfaceVariant,
                        focusedBorderColor = ElectricBlue,
                        unfocusedBorderColor = BorderAccent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }

            // Category shortcuts
            item {
                Text(
                    text = "QUICK TRANSIT CATEGORIES",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val categories = listOf("Metro", "Train Stations", "Bus Terminals", "Airports", "Work", "Home")
                    items(categories) { category ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.clickable {
                                onSearchQueryChanged(category)
                                onSearchSubmitted(category)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val icon = when {
                                    category.contains("Metro") || category.contains("Train") -> Icons.Default.Train
                                    category.contains("Bus") -> Icons.Default.DirectionsBus
                                    category.contains("Airport") -> Icons.Default.FlightTakeoff
                                    category.contains("Home") -> Icons.Default.Home
                                    else -> Icons.Default.Work
                                }
                                Icon(icon, contentDescription = null, tint = AmberAction, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(category, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            // Search Results or Recent Searches
            if (searchResults.isNotEmpty()) {
                item {
                    Text(
                        text = "SEARCH RESULTS (${searchResults.size})",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                }

                items(searchResults) { destination ->
                    DestinationResultItem(
                        destination = destination,
                        onClick = {
                            android.util.Log.d("RouteWakeTiming", "DESTINATION_TAP_RECEIVED: Search result clicked: ${destination.name} (${destination.latitude}, ${destination.longitude})")
                            focusManager.clearFocus()
                            onDestinationSelected(destination)
                        }
                    )
                }
            } else if (recentSearches.isNotEmpty() && searchQuery.isEmpty()) {
                item {
                    Text(
                        text = "RECENT SEARCHES",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                }

                items(recentSearches) { recent ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSearchQueryChanged(recent)
                                onSearchSubmitted(recent)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = recent,
                            fontSize = 14.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Selected Destination Configuration Card
        if (selectedDestination != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = DarkSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(AmberAction.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Place, contentDescription = null, tint = AmberAction, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(selectedDestination.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    if (selectedDestination.address.isNotEmpty()) {
                                        Text(selectedDestination.address, fontSize = 12.sp, color = TextSecondary, maxLines = 1)
                                    }
                                }
                            }

                            if (tripState == TripState.IDLE) {
                                TextButton(onClick = onClearDestination) {
                                    Text("Change", color = ElectricBlue, fontSize = 12.sp)
                                }
                            }
                        }

                        // Route stats if available or calculating with smooth animated transitions
                        AnimatedContent(
                            targetState = Pair(isCalculatingRoute, routeInfo != null),
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                                 scaleIn(initialScale = 0.96f, animationSpec = tween(220, easing = FastOutSlowInEasing)))
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(160, easing = FastOutLinearInEasing)) +
                                        scaleOut(targetScale = 0.96f, animationSpec = tween(160, easing = FastOutLinearInEasing))
                                    )
                            },
                            label = "route_calculation_state_transition"
                        ) { (calculating, hasRoute) ->
                            if (calculating) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    HorizontalDivider(color = DividerColor)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = ElectricBlue
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "Calculating live route & ETA...",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            } else if (hasRoute && routeInfo != null) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    HorizontalDivider(color = DividerColor)
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("ROUTE DISTANCE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                            AnimatedContent(
                                                targetState = formatDistance(routeInfo.distanceMeters),
                                                transitionSpec = {
                                                    (fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { -it / 3 }))
                                                        .togetherWith(fadeOut(animationSpec = tween(150)) + slideOutVertically(targetOffsetY = { it / 3 }))
                                                },
                                                label = "route_distance_text_transition"
                                            ) { dist ->
                                                Text(dist, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TealRoute)
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("ESTIMATED TIME", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                            AnimatedContent(
                                                targetState = formatEta(routeInfo.durationSeconds),
                                                transitionSpec = {
                                                    (fadeIn(animationSpec = tween(200)) + slideInVertically(initialOffsetY = { -it / 3 }))
                                                        .togetherWith(fadeOut(animationSpec = tween(150)) + slideOutVertically(targetOffsetY = { it / 3 }))
                                                },
                                                label = "route_eta_text_transition"
                                            ) { eta ->
                                                Text(eta, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("TRAFFIC", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                                            Text(if (routeInfo.isModeled) "ETA Modeled" else "Live OSRM", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Transport Mode Selector (if not active)
            if (tripState == TripState.IDLE) {
                item {
                    Text(
                        text = "SELECT TRANSPORT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TransportMode.entries.forEach { mode ->
                            val isSelected = selectedTransport == mode
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) ElectricBlue else DarkSurfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) ElectricBlue else BorderSubtle),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 2.dp)
                                    .clickable { onTransportChanged(mode) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = getTransportIcon(mode),
                                        contentDescription = mode.label,
                                        tint = if (isSelected) DarkBackground else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = mode.label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) DarkBackground else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Alarm Radius Selector with smooth transitions
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ARRIVAL ALARM RADIUS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        AnimatedContent(
                            targetState = activeRadiusMeters,
                            transitionSpec = {
                                val isIncreasing = targetState > initialState
                                (slideInVertically(
                                    initialOffsetY = { if (isIncreasing) it / 2 else -it / 2 },
                                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = tween(200)))
                                    .togetherWith(
                                        slideOutVertically(
                                            targetOffsetY = { if (isIncreasing) -it / 2 else it / 2 },
                                            animationSpec = tween(150)
                                        ) + fadeOut(animationSpec = tween(150))
                                    )
                            },
                            label = "alarm_radius_header_transition"
                        ) { radius ->
                            Text(
                                text = formatDistance(radius.toDouble()),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AmberAction
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(100, 300, 500, 1000, 2000).forEach { radius ->
                            val isSelected = activeRadiusMeters == radius
                            val animatedBg by animateColorAsState(
                                targetValue = if (isSelected) AmberAction else DarkSurfaceVariant,
                                animationSpec = tween(250, easing = FastOutSlowInEasing),
                                label = "radius_bg_$radius"
                            )
                            val animatedBorder by animateColorAsState(
                                targetValue = if (isSelected) AmberAction else BorderSubtle,
                                animationSpec = tween(250, easing = FastOutSlowInEasing),
                                label = "radius_border_$radius"
                            )
                            val animatedText by animateColorAsState(
                                targetValue = if (isSelected) DarkBackground else TextPrimary,
                                animationSpec = tween(250, easing = FastOutSlowInEasing),
                                label = "radius_text_$radius"
                            )
                            val animatedScale by animateFloatAsState(
                                targetValue = if (isSelected) 1.04f else 1.0f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                label = "radius_scale_$radius"
                            )

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = animatedBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, animatedBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onRadiusChanged(radius) }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (radius >= 1000) "${radius / 1000}km" else "${radius}m",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = animatedText
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Permission warning banner if critical permissions are missing
            if (tripState == TripState.IDLE && permissionStatus != null && !permissionStatus.hasAllCritical) {
                item {
                    PermissionWarningBanner(
                        status = permissionStatus,
                        onEnableClicked = onEnablePermissions,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            // Start / Stop Trip Button with smooth animated state transition
            item {
                Spacer(modifier = Modifier.height(4.dp))
                AnimatedContent(
                    targetState = tripState,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                         scaleIn(initialScale = 0.94f, animationSpec = tween(220, easing = FastOutSlowInEasing)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                                scaleOut(targetScale = 0.94f, animationSpec = tween(180, easing = FastOutLinearInEasing))
                            )
                    },
                    label = "start_stop_trip_button_transition"
                ) { state ->
                    if (state == TripState.IDLE) {
                        val isRouteReady = !isCalculatingRoute || routeInfo != null
                        Button(
                            onClick = onStartTrip,
                            enabled = isRouteReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("start_trip_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberAction,
                                contentColor = DarkBackground,
                                disabledContainerColor = AmberAction.copy(alpha = 0.45f),
                                disabledContentColor = DarkBackground.copy(alpha = 0.65f)
                            )
                        ) {
                            if (isCalculatingRoute && routeInfo == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = DarkBackground
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("PREPARING ROUTE...", fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp)
                            } else {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("START TRIP", fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 0.5.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = onStopTrip,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("stop_trip_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AlarmCrimson,
                                contentColor = TextPrimary
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("STOP TRACKING", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DestinationResultItem(
    destination: Destination,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(BorderAccent),
                contentAlignment = Alignment.Center
            ) {
                val icon = when (destination.category) {
                    "Metro", "Train" -> Icons.Default.Train
                    "Bus" -> Icons.Default.DirectionsBus
                    "Airport" -> Icons.Default.FlightTakeoff
                    "Home" -> Icons.Default.Home
                    "Work" -> Icons.Default.Work
                    else -> Icons.Default.Place
                }
                Icon(icon, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = destination.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (destination.address.isNotEmpty()) {
                    Text(
                        text = destination.address,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }

            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}
