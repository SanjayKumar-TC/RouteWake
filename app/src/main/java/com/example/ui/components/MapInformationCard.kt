package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.example.data.model.Destination
import com.example.data.model.TrafficSegment
import com.example.data.model.TransportMode
import com.example.data.model.TripState

sealed interface MapInformationCardType {
    data object None : MapInformationCardType
    data class RouteTraffic(
        val segment: TrafficSegment,
        val anchorPx: Offset
    ) : MapInformationCardType
    data class DestinationInfo(
        val destination: Destination,
        val anchorPx: Offset
    ) : MapInformationCardType
}

/**
 * Animated coordinator component for RouteWake map information cards (Route Traffic and Destination Info).
 *
 * Uses standard Jetpack Compose transitions (AnimatedContent) to guarantee:
 * 1. Smooth entrance from nothing (fade + scale in).
 * 2. Smooth exit to nothing (fade + scale out).
 * 3. Seamless switching between card types (e.g., switching from Route to Destination)
 *    so that only ONE card is ever open at a time and cards never overlap visually.
 */
@Composable
fun MapInformationCard(
    cardState: MapInformationCardType,
    transportMode: TransportMode,
    destinationName: String?,
    alarmRadiusMeters: Int,
    tripState: TripState,
    routeDistanceMeters: Double,
    routeDurationSeconds: Long,
    gpsQualityLabel: String,
    isNearDestination: Boolean,
    containerSize: IntSize,
    onStartTrip: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedContent(
        targetState = cardState,
        transitionSpec = {
            if (initialState is MapInformationCardType.None && targetState !is MapInformationCardType.None) {
                // Entrance animation from nothing
                (fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                 scaleIn(initialScale = 0.92f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)))
                    .togetherWith(fadeOut(animationSpec = tween(durationMillis = 90)))
            } else if (initialState !is MapInformationCardType.None && targetState is MapInformationCardType.None) {
                // Exit animation to nothing
                fadeIn(animationSpec = tween(durationMillis = 90))
                    .togetherWith(fadeOut(animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)) +
                                  scaleOut(targetScale = 0.94f, animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)))
            } else if (initialState is MapInformationCardType.RouteTraffic && targetState is MapInformationCardType.RouteTraffic) {
                // Switching between different route segments: smooth directional slide, fade, and scale
                (fadeIn(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) +
                 slideInHorizontally(initialOffsetX = { 36 }, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) +
                 scaleIn(initialScale = 0.96f, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(durationMillis = 140, easing = FastOutLinearInEasing)) +
                        slideOutHorizontally(targetOffsetX = { -36 }, animationSpec = tween(durationMillis = 140, easing = FastOutLinearInEasing)) +
                        scaleOut(targetScale = 0.96f, animationSpec = tween(durationMillis = 140, easing = FastOutLinearInEasing))
                    )
            } else {
                // Switching between different card types (RouteTraffic <-> DestinationInfo)
                // Staggered cross-fade/scale avoids showing two cards simultaneously
                (fadeIn(animationSpec = tween(durationMillis = 180, delayMillis = 40, easing = FastOutSlowInEasing)) +
                 scaleIn(initialScale = 0.95f, animationSpec = tween(durationMillis = 180, delayMillis = 40, easing = FastOutSlowInEasing)))
                    .togetherWith(fadeOut(animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing)) +
                                  scaleOut(targetScale = 0.96f, animationSpec = tween(durationMillis = 120, easing = FastOutLinearInEasing)))
            }
        },
        label = "MapInformationCardTransition"
    ) { state ->
        when (state) {
            is MapInformationCardType.None -> {
                // No card displayed
            }
            is MapInformationCardType.RouteTraffic -> {
                TrafficInfoPopup(
                    segment = state.segment,
                    transportMode = transportMode,
                    destinationName = destinationName,
                    onDismiss = onDismiss,
                    anchorPx = state.anchorPx,
                    containerSize = containerSize
                )
            }
            is MapInformationCardType.DestinationInfo -> {
                DestinationInfoPopup(
                    destination = state.destination,
                    transportMode = transportMode,
                    alarmRadiusMeters = alarmRadiusMeters,
                    tripState = tripState,
                    routeDistanceMeters = routeDistanceMeters,
                    routeDurationSeconds = routeDurationSeconds,
                    gpsQualityLabel = gpsQualityLabel,
                    isNearDestination = isNearDestination,
                    onStartTrip = onStartTrip,
                    onDismiss = onDismiss,
                    anchorPx = state.anchorPx,
                    containerSize = containerSize
                )
            }
        }
    }
}
