package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
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
import com.example.data.model.Destination
import com.example.data.model.TransportMode
import com.example.data.model.TripState
import com.example.ui.theme.*
import kotlin.math.roundToInt

/**
 * Custom RouteWake native destination information card.
 *
 * Design Specifications:
 * - Color Palette: Dark surface #0F172A, Border #334155, Primary text #F8FAFC,
 *   Secondary text #94A3B8, Electric blue #38BDF8, Amber #F59E0B, Success #10B981.
 * - Rounded corners (14.dp) and subtle shadow.
 * - Downward pointer connecting the card to the destination pin marker.
 * - Compact size, responsive layout.
 * - Live in-place updates during active tracking without map recreation.
 */
@Composable
fun DestinationInfoPopup(
    destination: Destination,
    transportMode: TransportMode,
    alarmRadiusMeters: Int,
    tripState: TripState,
    routeDistanceMeters: Double,
    routeDurationSeconds: Long,
    gpsQualityLabel: String,
    isNearDestination: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onStartTrip: (() -> Unit)? = null,
    anchorPx: Offset? = null,
    containerSize: IntSize? = null
) {
    val density = LocalDensity.current
    var popupSize by remember { mutableStateOf(IntSize.Zero) }

    // Pointer dimensions
    val pointerWidthDp = 14.dp
    val pointerHeightDp = 7.dp
    val pointerWidthPx = with(density) { pointerWidthDp.toPx() }
    val pointerHeightPx = with(density) { pointerHeightDp.toPx() }
    val marginPx = with(density) { 12.dp.toPx() }
    val gapAboveAnchorPx = with(density) { 4.dp.toPx() }

    // Calculate position relative to container and anchor
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
            .testTag("destination_info_popup")
    ) {
        // 1. Floating Card Surface (#0F172A background, #334155 border)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xF80F172A),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .widthIn(min = 210.dp, max = 290.dp)
                .wrapContentHeight()
                .shadow(12.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* Absorbs clicks so touch events inside card do NOT propagate to map */ }
                )
                .testTag("destination_info_popup_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Header Row: Transport Icon + Title + Close Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = transportEmoji,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = destination.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("destination_popup_title")
                        )
                    }

                    // Compact Close "x" button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, radius = 12.dp)
                            ) { onDismiss() }
                            .testTag("destination_popup_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Address row (if present)
                if (destination.address.isNotBlank() && destination.address != destination.name) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = destination.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Body content based on trip state
                when (tripState) {
                    TripState.ARRIVED -> {
                        // 2.4 Arrived State
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(Color(0xFF10B981).copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "✓ ARRIVED",
                                color = Color(0xFF10B981),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.testTag("destination_popup_arrived_badge")
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Within ${formatDistance(alarmRadiusMeters.toDouble())}",
                                color = Color(0xFFF8FAFC),
                                fontSize = 11.sp
                            )
                        }
                    }

                    TripState.TRACKING, TripState.WARMUP -> {
                        // 2.2 Active Trip or 2.3 Near Arrival
                        if (isNearDestination) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .background(Color(0xFFF59E0B).copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "NEAR DESTINATION",
                                    color = Color(0xFFF59E0B),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.testTag("destination_popup_near_badge")
                                )
                            }
                        }

                        // Live remaining distance and ETA
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "${formatDistance(routeDistanceMeters)} remaining",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isNearDestination) Color(0xFFF59E0B) else Color(0xFF38BDF8),
                                modifier = Modifier.testTag("destination_popup_distance")
                            )
                            Text(
                                text = "·",
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "~${formatEta(routeDurationSeconds)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFF8FAFC),
                                modifier = Modifier.testTag("destination_popup_eta")
                            )
                        }

                        // Alarm radius & GPS status row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Alarm: ${formatDistance(alarmRadiusMeters.toDouble())}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }

                            if (gpsQualityLabel.isNotBlank()) {
                                Text(
                                    text = "📡 GPS: $gpsQualityLabel",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = when (gpsQualityLabel.uppercase()) {
                                        "EXCELLENT", "GOOD" -> Color(0xFF10B981)
                                        "FAIR" -> Color(0xFFF59E0B)
                                        else -> Color(0xFF38BDF8)
                                    },
                                    modifier = Modifier.testTag("destination_popup_gps")
                                )
                            }
                        }
                    }

                    TripState.IDLE, TripState.COMPLETED -> {
                        // 2.1 Before Trip State (Route details, Alarm radius, and START TRIP button)
                        if (routeDistanceMeters > 0) {
                            Text(
                                text = "${transportMode.label} • ${formatDistance(routeDistanceMeters)}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier
                                    .padding(bottom = 2.dp)
                                    .testTag("destination_popup_distance")
                            )
                            Text(
                                text = "ETA ${formatEta(routeDurationSeconds)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFF8FAFC),
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .testTag("destination_popup_eta")
                            )
                        }

                        // Alarm radius badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = if (onStartTrip != null) 10.dp else 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Alarm radius: ${formatDistance(alarmRadiusMeters.toDouble())}",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF59E0B),
                                modifier = Modifier.testTag("destination_popup_alarm_radius")
                            )
                        }

                        // START TRIP Button (calls existing start trip flow and dismisses card)
                        if (onStartTrip != null) {
                            Button(
                                onClick = {
                                    onStartTrip()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF38BDF8),
                                    contentColor = Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .testTag("destination_popup_start_trip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "START TRIP",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Downward Pointer (Connecting triangle pointing to the destination pin)
        if (anchorPx != null) {
            Canvas(
                modifier = Modifier
                    .offset { IntOffset(pointerOffsetXPx.roundToInt(), 0) }
                    .size(pointerWidthDp, pointerHeightDp)
            ) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path, color = Color(0xF80F172A))
                // Draw subtle border lines on pointer edges
                drawLine(
                    color = Color(0xFF334155),
                    start = Offset(0f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color(0xFF334155),
                    start = Offset(size.width, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    }
}
