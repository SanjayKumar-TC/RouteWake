package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*

/**
 * Custom RouteWake Dialog displayed when Android's Master Location service is disabled.
 * Provides clear explanation and direct action to resolve or open Android Settings.
 */
@Composable
fun LocationServiceDisabledDialog(
    onEnableLocation: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag("location_disabled_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = DarkSurfaceGlass,
            shadowElevation = 24.dp,
            border = BorderStroke(1.5.dp, AmberAction)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AmberAction.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOff,
                        contentDescription = "Location Disabled",
                        tint = AmberAction,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = "Device Location Disabled",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description
                Text(
                    text = "RouteWake is a travel alarm and navigation app. Device Location must be turned on to display your current position, track route progress, and wake you upon arrival.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Action Button
                Button(
                    onClick = onEnableLocation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("enable_location_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberAction,
                        contentColor = DarkBackground
                    )
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ENABLE LOCATION",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Secondary Settings Button
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("open_location_settings_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ElectricBlue
                    )
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open Location Settings",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Dismiss Button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dismiss_location_dialog_button")
                ) {
                    Text(
                        text = "Not Now",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Custom RouteWake Dialog displayed when High Accuracy / Google Location Accuracy is unavailable.
 */
@Composable
fun HighAccuracyDisabledDialog(
    onEnableHighAccuracy: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag("high_accuracy_disabled_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = DarkSurfaceGlass,
            shadowElevation = 24.dp,
            border = BorderStroke(1.5.dp, ElectricBlue)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(ElectricBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GpsNotFixed,
                        contentDescription = "High Accuracy Required",
                        tint = ElectricBlue,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = "High Accuracy Required",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description
                Text(
                    text = "RouteWake requires high-accuracy GPS to accurately measure remaining distance and trigger your wake-up alarm on time. Please enable high accuracy in location settings.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Action Button
                Button(
                    onClick = onEnableHighAccuracy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("improve_accuracy_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                        contentColor = DarkBackground
                    )
                ) {
                    Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "IMPROVE ACCURACY",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Secondary Settings Button
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("open_accuracy_settings_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ElectricBlue
                    )
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open Location Settings",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Dismiss Button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dismiss_accuracy_dialog_button")
                ) {
                    Text(
                        text = "Not Now",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Top floating notification banner displayed when Location Services are disabled or degraded.
 */
@Composable
fun LocationPrerequisiteBanner(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("location_prerequisite_banner"),
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceGlass,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, AmberAction.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = AmberAction,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onAction,
                modifier = Modifier
                    .height(32.dp)
                    .testTag("banner_action_button"),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberAction,
                    contentColor = DarkBackground
                )
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
