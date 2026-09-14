package com.example.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.example.data.model.TrafficSegment
import com.example.data.model.TransportMode
import com.example.ui.components.TrafficInfoPopup as ComponentTrafficInfoPopup

/**
 * Re-export of TrafficInfoPopup in package com.example.ui.map for seamless map layer access.
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
    ComponentTrafficInfoPopup(
        segment = segment,
        onDismiss = onDismiss,
        modifier = modifier,
        transportMode = transportMode,
        destinationName = destinationName,
        originName = originName,
        anchorPx = anchorPx,
        containerSize = containerSize
    )
}
