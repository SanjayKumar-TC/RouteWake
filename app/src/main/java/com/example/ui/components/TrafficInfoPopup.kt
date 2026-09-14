package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CongestionLevel
import com.example.data.model.TrafficSegment
import com.example.data.model.TransportMode
import com.example.ui.theme.BorderAccent
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TrafficClear
import com.example.ui.theme.TrafficHeavy
import com.example.ui.theme.TrafficModerate
import com.example.ui.theme.TrafficSevere
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Custom Compose UI component 'TrafficInfoPopup' displaying 'TrafficSegment' data
 * in a compact, floating card format anchored above the tapped map coordinate.
 *
 * Design Specifications:
 * - Color Palette: #0F172A background, #334155 border, with RouteWake telemetry accents.
 * - Dynamic Content Adjustment: Height and width adapt cleanly to the exact telemetry fields
 *   present (road name, speeds, delays, distances, incident alerts) without empty gaps.
 * - Downward pointer/arrow precisely anchored above the tapped map coordinate.
 */
@Composable
fun TrafficInfoPopup(
    segment: TrafficSegment,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    transportMode: TransportMode = TransportMode.CAR,
    destinationName: String? = null,
    originName: String? = null,
    anchorPx: Offset? = null,
    containerSize: IntSize? = null
) {
    val density = LocalDensity.current
    var popupSize by remember { mutableStateOf(IntSize.Zero) }

    val congestionColor = when (segment.congestionLevel) {
        CongestionLevel.CLEAR -> TrafficClear
        CongestionLevel.MODERATE -> TrafficModerate
        CongestionLevel.HEAVY -> TrafficHeavy
        CongestionLevel.SEVERE -> TrafficSevere
    }
    val animatedCongestionColor by animateColorAsState(
        targetValue = congestionColor,
        animationSpec = tween(220),
        label = "popup_congestion_color"
    )

    // Pointer dimensions
    val pointerWidthDp = 14.dp
    val pointerHeightDp = 7.dp
    val pointerWidthPx = with(density) { pointerWidthDp.toPx() }
    val pointerHeightPx = with(density) { pointerHeightDp.toPx() }
    val marginPx = with(density) { 12.dp.toPx() }
    val gapAboveAnchorPx = with(density) { 6.dp.toPx() }

    // If anchorPx is provided, calculate position relative to container
    val (cardOffset, pointerOffsetXPx) = remember(anchorPx, popupSize, containerSize) {
        if (anchorPx == null || popupSize.width == 0 || popupSize.height == 0) {
            Pair(IntOffset.Zero, 0f)
        } else {
            val totalPopupHeight = popupSize.height
            val popupWidth = popupSize.width

            val containerW = containerSize?.width?.toFloat() ?: (anchorPx.x + popupWidth + marginPx)
            val containerH = containerSize?.height?.toFloat() ?: (anchorPx.y + totalPopupHeight + marginPx)
            val minX = marginPx
            val maxX = maxOf(minX, containerW - popupWidth - marginPx)

            val idealLeft = anchorPx.x - (popupWidth / 2f)
            val clampedLeft = idealLeft.coerceIn(minX, maxX)

            val idealTop = anchorPx.y - totalPopupHeight - gapAboveAnchorPx
            val clampedTop = if (idealTop < marginPx) {
                // If top goes offscreen near top edge, place below anchor
                (anchorPx.y + gapAboveAnchorPx).coerceIn(marginPx, maxOf(marginPx, containerH - totalPopupHeight - marginPx))
            } else {
                idealTop.coerceIn(marginPx, maxOf(marginPx, containerH - totalPopupHeight - marginPx))
            }

            // Pointer tip should point straight at anchorPx.x
            val pointerCenterX = anchorPx.x - clampedLeft
            val minPointerX = with(density) { 18.dp.toPx() }
            val maxPointerX = maxOf(minPointerX, popupWidth - minPointerX - pointerWidthPx)
            val clampedPointerX = (pointerCenterX - (pointerWidthPx / 2f)).coerceIn(minPointerX, maxPointerX)

            Pair(
                IntOffset(clampedLeft.roundToInt(), clampedTop.roundToInt()),
                clampedPointerX
            )
        }
    }

    val popupModifier = if (anchorPx != null) {
        modifier
            .offset { cardOffset }
            .onSizeChanged { popupSize = it }
    } else {
        modifier.onSizeChanged { popupSize = it }
    }

    val transportEmoji = when (transportMode) {
        TransportMode.WALK -> "🚶"
        TransportMode.CYCLE -> "🚲"
        TransportMode.BUS -> "🚌"
        TransportMode.TRAIN -> "🚆"
        TransportMode.CAR -> "🚗"
    }

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = popupModifier
            .wrapContentSize()
            .testTag("traffic_info_popup")
    ) {
        // 1. Floating Card Container (uses #0F172A background, #334155 border)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xF80F172A),
            border = BorderStroke(1.dp, BorderAccent),
            shadowElevation = 10.dp,
            modifier = Modifier
                .widthIn(min = 200.dp, max = 285.dp)
                .wrapContentHeight()
                .shadow(12.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* Absorbs clicks so touch events inside card do NOT propagate to map */ }
                )
                .testTag("traffic_info_popup_card")
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .wrapContentHeight(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Header: Transport Emoji + Title ("Route Traffic") + Close Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = transportEmoji,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )

                    Text(
                        text = "Route Traffic",
                        color = TextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("traffic_info_title")
                    )

                    // Close Button "×"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, radius = 12.dp),
                                onClick = onDismiss
                            )
                            .testTag("traffic_info_close_button")
                    ) {
                        Text(
                            text = "✕",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                val isTrafficAvailable = segment.speedKmh != null ||
                        segment.delayMinutes != null ||
                        !segment.roadName.isNullOrBlank() ||
                        !segment.incident.isNullOrBlank()

                if (isTrafficAvailable) {
                    // 1. Status Indicator / Congestion Badge (e.g. 🟢 Clear, 🟡 Moderate, 🔴 Heavy, 🛑 Severe)
                    AnimatedContent(
                        targetState = segment.congestionLevel,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180)))
                                .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.94f, animationSpec = tween(140)))
                        },
                        label = "traffic_popup_badge_transition"
                    ) { level ->
                        val (statusDot, statusText) = when (level) {
                            CongestionLevel.CLEAR -> Pair("🟢", "Clear")
                            CongestionLevel.MODERATE -> Pair("🟡", "Moderate")
                            CongestionLevel.HEAVY -> Pair("🔴", "Heavy")
                            CongestionLevel.SEVERE -> Pair("🛑", "Severe")
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 1.dp)
                        ) {
                            Text(
                                text = "$statusDot $statusText",
                                color = animatedCongestionColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.testTag("traffic_info_congestion_badge")
                            )
                        }
                    }

                    // 2. Speed (e.g. 42 km/h)
                    if (segment.speedKmh != null && segment.speedKmh > 0) {
                        AnimatedContent(
                            targetState = segment.speedKmh.toInt(),
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(180)))
                                    .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.96f, animationSpec = tween(140)))
                            },
                            label = "traffic_popup_speed_transition"
                        ) { speed ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "$speed km/h",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("traffic_info_speed")
                                )

                                if (segment.normalSpeedKmh != null && segment.normalSpeedKmh > 0 &&
                                    segment.normalSpeedKmh.toInt() != speed) {
                                    Text(
                                        text = "(norm ${segment.normalSpeedKmh.toInt()} km/h)",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    // 3. Estimated delay: e.g. "Estimated delay: +2 min"
                    if (segment.delayMinutes != null) {
                        AnimatedContent(
                            targetState = segment.delayMinutes,
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(180)))
                                    .togetherWith(fadeOut(animationSpec = tween(140)))
                            },
                            label = "traffic_popup_delay_transition"
                        ) { delayMinVal ->
                            val delayMin = delayMinVal ?: 0
                            val delayText = if (delayMin > 0) "+$delayMin min" else "No delay"
                            Text(
                                text = "Estimated delay: $delayText",
                                color = if (delayMin > 0) animatedCongestionColor else Color(0xFF94A3B8),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.testTag("traffic_info_delay")
                            )
                        }
                    }

                    // 4. Route / Destination context: e.g. "Bengaluru → Destination" or road name
                    val routeContext = when {
                        !segment.roadName.isNullOrBlank() && !destinationName.isNullOrBlank() ->
                            "${segment.roadName} → $destinationName"
                        !destinationName.isNullOrBlank() ->
                            "${originName ?: "Bengaluru"} → $destinationName"
                        !segment.roadName.isNullOrBlank() ->
                            segment.roadName
                        else -> null
                    }

                    if (routeContext != null) {
                        Text(
                            text = routeContext,
                            color = ElectricBlue,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("traffic_info_route_context")
                        )
                    }

                    // 5. Incident alert
                    if (!segment.incident.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("traffic_info_incident_box")
                        ) {
                            Text(
                                text = "⚠ ${segment.incident}",
                                color = congestionColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("traffic_info_incident")
                            )

                            if (!segment.incidentDescription.isNullOrBlank()) {
                                Text(
                                    text = segment.incidentDescription,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 10.dp, top = 1.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Traffic data unavailable fallback
                    Text(
                        text = "Traffic data unavailable",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier
                            .padding(vertical = 3.dp)
                            .testTag("traffic_info_unavailable")
                    )

                    if (!destinationName.isNullOrBlank()) {
                        Text(
                            text = "${originName ?: "Bengaluru"} → $destinationName",
                            color = ElectricBlue,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 2. Downward Pointer / Beak anchored directly above the coordinate
        if (anchorPx != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(pointerOffsetXPx.roundToInt(), 0) }
                    .size(pointerWidthDp, pointerHeightDp)
                    .testTag("traffic_info_pointer")
            ) {
                Canvas(modifier = Modifier.size(pointerWidthDp, pointerHeightDp)) {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width / 2f, size.height)
                        close()
                    }
                    drawPath(path, color = Color(0xF80F172A))
                    // Pointer left border
                    drawLine(
                        color = BorderAccent,
                        start = Offset(0f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                    // Pointer right border
                    drawLine(
                        color = BorderAccent,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }
    }
}
