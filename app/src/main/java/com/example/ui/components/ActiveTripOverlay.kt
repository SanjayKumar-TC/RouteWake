package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Destination
import com.example.data.model.GpsQuality
import com.example.data.model.TransportMode
import com.example.data.model.TripMetrics
import com.example.data.model.TripState
import com.example.ui.theme.*
import java.util.Locale

/**
 * Proximity alarm states for the active trip navigation overlay.
 */
enum class ProximityAlarmState {
    WARMUP,
    TRACKING_NORMAL,
    APPROACHING_ALARM,
    INSIDE_ALARM_ZONE
}

@Composable
fun ActiveTripOverlay(
    tripState: TripState,
    destination: Destination?,
    radiusMeters: Int,
    transportMode: TransportMode,
    metrics: TripMetrics,
    isEcoMode: Boolean,
    onCancelTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = tripState == TripState.WARMUP || tripState == TripState.TRACKING

    AnimatedVisibility(
        visible = isVisible && destination != null,
        enter = fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                slideInVertically(initialOffsetY = { -it / 2 }, animationSpec = tween(300, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)) +
               slideOutVertically(targetOffsetY = { -it / 2 }, animationSpec = tween(200, easing = FastOutLinearInEasing)),
        modifier = modifier
    ) {
        if (destination == null) return@AnimatedVisibility

        val isInsideAlarm = metrics.distanceRemainingMeters in 0.001..radiusMeters.toDouble()
        val isApproachingAlarm = !isInsideAlarm && metrics.distanceRemainingMeters <= radiusMeters * 1.5

        val alarmState = when {
            tripState == TripState.WARMUP -> ProximityAlarmState.WARMUP
            isInsideAlarm -> ProximityAlarmState.INSIDE_ALARM_ZONE
            isApproachingAlarm -> ProximityAlarmState.APPROACHING_ALARM
            else -> ProximityAlarmState.TRACKING_NORMAL
        }

        // Smoothly animated container colors when switching between alarm states
        val animatedContainerBg by animateColorAsState(
            targetValue = when (alarmState) {
                ProximityAlarmState.INSIDE_ALARM_ZONE -> Color(0xF23B0D18) // Glowing Crimson-Dark
                ProximityAlarmState.APPROACHING_ALARM -> Color(0xF21C1938) // Subtle Amber-Indigo hue
                ProximityAlarmState.WARMUP -> Color(0xF21A1A2E)
                ProximityAlarmState.TRACKING_NORMAL -> DarkSurfaceGlass
            },
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            label = "overlay_bg_color_animation"
        )

        val animatedBorderColor by animateColorAsState(
            targetValue = when (alarmState) {
                ProximityAlarmState.INSIDE_ALARM_ZONE -> AlarmCrimson
                ProximityAlarmState.APPROACHING_ALARM -> AmberAction
                ProximityAlarmState.WARMUP -> AmberDark.copy(alpha = 0.6f)
                ProximityAlarmState.TRACKING_NORMAL -> BorderAccent
            },
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            label = "overlay_border_color_animation"
        )

        val animatedDistanceColor by animateColorAsState(
            targetValue = when (alarmState) {
                ProximityAlarmState.INSIDE_ALARM_ZONE -> AlarmCrimson
                ProximityAlarmState.APPROACHING_ALARM -> AmberAction
                else -> ElectricBlue
            },
            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
            label = "overlay_distance_color_animation"
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = animatedContainerBg,
            shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, animatedBorderColor)
        ) {
            Column(
                modifier = Modifier.padding(14.dp)
            ) {
                // Header: Destination Name + Transport + Cancel + Animated Alarm Status
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
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(DarkSurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getTransportIcon(transportMode),
                                contentDescription = transportMode.label,
                                tint = AmberAction,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = destination.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1
                            )

                            // Smooth animated alarm state label
                            AnimatedContent(
                                targetState = alarmState,
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                                     slideInVertically(initialOffsetY = { -it / 2 }, animationSpec = tween(220)))
                                        .togetherWith(
                                            fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                                            slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(180))
                                        )
                                },
                                label = "alarm_subtitle_transition"
                            ) { state ->
                                val text = when (state) {
                                    ProximityAlarmState.INSIDE_ALARM_ZONE -> "🚨 Inside Wakeup Zone (${formatDistance(radiusMeters.toDouble())})"
                                    ProximityAlarmState.APPROACHING_ALARM -> "⚡ Approaching Alarm (${formatDistance(radiusMeters.toDouble())})"
                                    ProximityAlarmState.WARMUP -> "GPS Warming Up • Alarm at ${formatDistance(radiusMeters.toDouble())}"
                                    ProximityAlarmState.TRACKING_NORMAL -> "Alarm at ${formatDistance(radiusMeters.toDouble())}"
                                }
                                val color = when (state) {
                                    ProximityAlarmState.INSIDE_ALARM_ZONE -> AlarmCrimson
                                    ProximityAlarmState.APPROACHING_ALARM -> AmberAction
                                    else -> TextSecondary
                                }
                                Text(
                                    text = text,
                                    fontSize = 11.sp,
                                    fontWeight = if (state != ProximityAlarmState.TRACKING_NORMAL) FontWeight.SemiBold else FontWeight.Normal,
                                    color = color
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onCancelTrip,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("cancel_trip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Cancel Trip",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Primary Distance & ETA display with smooth animated number transitions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "DISTANCE REMAINING",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        AnimatedContent(
                            targetState = formatDistance(metrics.distanceRemainingMeters),
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                                 slideInVertically(initialOffsetY = { -it / 3 }, animationSpec = tween(200, easing = FastOutSlowInEasing)))
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing)) +
                                        slideOutVertically(targetOffsetY = { it / 3 }, animationSpec = tween(150, easing = FastOutLinearInEasing))
                                    )
                            },
                            label = "distance_remaining_animated_content"
                        ) { formattedDist ->
                            Text(
                                text = formattedDist,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black,
                                color = animatedDistanceColor
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (metrics.isModeledEta) "MODELED ETA" else "LIVE ETA",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        AnimatedContent(
                            targetState = formatEta(metrics.etaSeconds),
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                                 slideInVertically(initialOffsetY = { -it / 3 }, animationSpec = tween(200, easing = FastOutSlowInEasing)))
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing)) +
                                        slideOutVertically(targetOffsetY = { it / 3 }, animationSpec = tween(150, easing = FastOutLinearInEasing))
                                    )
                            },
                            label = "eta_animated_content"
                        ) { formattedEtaStr ->
                            Text(
                                text = formattedEtaStr,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Status Badges with smooth crossfade & slide transitions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = alarmState,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                             slideInHorizontally(initialOffsetX = { 20 }, animationSpec = tween(220)))
                                .togetherWith(
                                    fadeOut(animationSpec = tween(160, easing = FastOutLinearInEasing)) +
                                    slideOutHorizontally(targetOffsetX = { -20 }, animationSpec = tween(160))
                                )
                        },
                        label = "alarm_status_badge_transition"
                    ) { state ->
                        when (state) {
                            ProximityAlarmState.INSIDE_ALARM_ZONE -> {
                                StatusPill(
                                    label = "ALARM ACTIVE",
                                    bgColor = AlarmCrimson.copy(alpha = 0.25f),
                                    textColor = AlarmCrimson,
                                    leadingIcon = Icons.Default.NotificationsActive
                                )
                            }
                            ProximityAlarmState.APPROACHING_ALARM -> {
                                StatusPill(
                                    label = "NEAR ALARM ZONE",
                                    bgColor = AmberDark.copy(alpha = 0.25f),
                                    textColor = AmberAction,
                                    leadingIcon = Icons.Default.Alarm
                                )
                            }
                            ProximityAlarmState.WARMUP -> {
                                StatusPill(
                                    label = "GPS Warmup ${metrics.warmupSecondsRemaining}s",
                                    bgColor = AmberDark.copy(alpha = 0.3f),
                                    textColor = AmberAction
                                )
                            }
                            ProximityAlarmState.TRACKING_NORMAL -> {
                                val (gpsBg, gpsText) = when (metrics.gpsQuality) {
                                    GpsQuality.EXCELLENT -> GpsExcellent.copy(alpha = 0.2f) to GpsExcellent
                                    GpsQuality.GOOD -> GpsGood.copy(alpha = 0.2f) to GpsGood
                                    GpsQuality.FAIR -> GpsFair.copy(alpha = 0.2f) to GpsFair
                                    GpsQuality.POOR -> GpsPoor.copy(alpha = 0.2f) to GpsPoor
                                    GpsQuality.STALE -> AmberDark.copy(alpha = 0.25f) to AmberAction
                                    GpsQuality.OFF -> GpsPoor.copy(alpha = 0.2f) to GpsPoor
                                }
                                StatusPill(
                                    label = metrics.gpsQuality.label,
                                    bgColor = gpsBg,
                                    textColor = gpsText
                                )
                            }
                        }
                    }

                    if (isEcoMode) {
                        StatusPill(
                            label = "Eco Mode",
                            bgColor = TealRoute.copy(alpha = 0.2f),
                            textColor = TealRoute,
                            leadingIcon = Icons.Default.Eco
                        )
                    }

                    StatusPill(
                        label = "Background Tracking",
                        bgColor = DarkSurfaceVariant,
                        textColor = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    bgColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    leadingIcon: ImageVector? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

fun getTransportIcon(mode: TransportMode): ImageVector {
    return when (mode) {
        TransportMode.WALK -> Icons.Default.DirectionsWalk
        TransportMode.CYCLE -> Icons.Default.PedalBike
        TransportMode.BUS -> Icons.Default.DirectionsBus
        TransportMode.TRAIN -> Icons.Default.Train
        TransportMode.CAR -> Icons.Default.DirectionsCar
    }
}

fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format(Locale.US, "%.1f km", meters / 1000.0)
    } else {
        "${meters.toInt()} m"
    }
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "--"
    val mins = seconds / 60
    return if (mins < 60) {
        "$mins min"
    } else {
        val hours = mins / 60
        val remMins = mins % 60
        "${hours}h ${remMins}m"
    }
}
