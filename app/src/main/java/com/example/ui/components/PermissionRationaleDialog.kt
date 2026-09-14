package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.example.ui.theme.*

/**
 * Encapsulates the runtime permission status for RouteWake.
 */
data class PermissionStatus(
    val hasFineLocation: Boolean,
    val hasCoarseLocation: Boolean,
    val hasNotification: Boolean,
    val hasBackgroundLocation: Boolean = true
) {
    val hasAllCritical: Boolean
        get() = hasFineLocation && hasNotification

    val isLocationMissing: Boolean
        get() = !hasFineLocation

    val isNotificationMissing: Boolean
        get() = !hasNotification

    val isBackgroundLocationMissing: Boolean
        get() = !hasBackgroundLocation

    fun getMissingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (!hasFineLocation) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return missing
    }

    fun getBackgroundLocationPermission(): String? {
        return if (!hasBackgroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            null
        }
    }
}

/**
 * Helper to inspect current runtime permissions.
 */
fun checkPermissionStatus(context: Context): PermissionStatus {
    val fineLocation = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val coarseLocation = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    return PermissionStatus(
        hasFineLocation = fineLocation,
        hasCoarseLocation = coarseLocation,
        hasNotification = notification,
        hasBackgroundLocation = backgroundLocation
    )
}

/**
 * Create a PermissionStatus from Accompanist MultiplePermissionsState.
 */
@OptIn(ExperimentalPermissionsApi::class)
fun checkAccompanistPermissionStatus(
    locationAndNotificationPermissionsState: MultiplePermissionsState,
    backgroundLocationPermissionState: PermissionState? = null
): PermissionStatus {
    var fine = false
    var coarse = false
    var notification = true

    for (perm in locationAndNotificationPermissionsState.permissions) {
        when (perm.permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> {
                if (perm.status.isGranted) fine = true
            }
            Manifest.permission.ACCESS_COARSE_LOCATION -> {
                if (perm.status.isGranted) coarse = true
            }
            Manifest.permission.POST_NOTIFICATIONS -> {
                notification = perm.status.isGranted
            }
        }
    }

    val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && backgroundLocationPermissionState != null) {
        backgroundLocationPermissionState.status.isGranted
    } else {
        true
    }

    return PermissionStatus(
        hasFineLocation = fine,
        hasCoarseLocation = coarse,
        hasNotification = notification,
        hasBackgroundLocation = backgroundLocation
    )
}

/**
 * Helper to navigate user to system application settings.
 */
fun openAppSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

/**
 * Reason why the permission rationale dialog is being displayed.
 */
enum class PermissionDialogReason {
    INITIAL,
    START_TRIP,
    RECENTER,
    BANNER
}

/**
 * Material 3 Permission Rationale Dialog for ACCESS_FINE_LOCATION and POST_NOTIFICATIONS.
 */
@Composable
fun PermissionRationaleDialog(
    status: PermissionStatus,
    reason: PermissionDialogReason,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val isStartTrip = reason == PermissionDialogReason.START_TRIP

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag("permission_rationale_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = DarkSurfaceGlass,
            shadowElevation = 24.dp,
            border = BorderStroke(1.5.dp, if (isStartTrip) AmberAction else BorderAccent)
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
                        .background(if (isStartTrip) AmberAction.copy(alpha = 0.15f) else ElectricBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isStartTrip) Icons.Default.WarningAmber else Icons.Default.Security,
                        contentDescription = "Permission Alert",
                        tint = if (isStartTrip) AmberAction else ElectricBlue,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Dialog Title
                Text(
                    text = when (reason) {
                        PermissionDialogReason.START_TRIP -> "Permissions Needed to Start Trip"
                        PermissionDialogReason.RECENTER -> "Location Access Needed"
                        else -> "Permissions Required"
                    },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Explanatory Subtitle
                Text(
                    text = "RouteWake requires these permissions for background arrival detection and waking you up on time:",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Permission item 1: ACCESS_FINE_LOCATION
                PermissionDetailCard(
                    icon = Icons.Default.MyLocation,
                    title = "Precise Location (ACCESS_FINE_LOCATION)",
                    description = "Required for high-accuracy GPS geofencing, speed measurement, and calculating exact distance to your destination.",
                    isGranted = status.hasFineLocation
                )

                // Permission item 2: POST_NOTIFICATIONS (Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(modifier = Modifier.height(10.dp))
                    PermissionDetailCard(
                        icon = Icons.Default.NotificationsActive,
                        title = "Arrival Notifications (POST_NOTIFICATIONS)",
                        description = "Required to run the persistent tracking service and display urgent wake-up alarms even when the screen is locked.",
                        isGranted = status.hasNotification
                    )
                }

                // Permission item 3: ACCESS_BACKGROUND_LOCATION (Android 10+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Spacer(modifier = Modifier.height(10.dp))
                    PermissionDetailCard(
                        icon = Icons.Default.Explore,
                        title = "Background Location (All the time)",
                        description = "Allows GPS tracking to continue reliably when the app is in the background or screen is off.",
                        isGranted = status.hasBackgroundLocation
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Action Buttons
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("permission_grant_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberAction,
                        contentColor = DarkBackground
                    )
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "GRANT PERMISSIONS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("permission_settings_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ElectricBlue
                    )
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open App Settings",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("permission_dismiss_button")
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

@Composable
private fun PermissionDetailCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (isGranted) Color(0xFF10B981).copy(alpha = 0.4f) else AmberAction.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isGranted) Color(0x2210B981) else AmberAction.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isGranted) Color(0xFF10B981) else AmberAction,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isGranted) Color(0x2510B981) else AmberAction.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = if (isGranted) "GRANTED" else "REQUIRED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isGranted) Color(0xFF10B981) else AmberAction,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

/**
 * Persistent warning banner shown when critical permissions are missing.
 */
@Composable
fun PermissionWarningBanner(
    status: PermissionStatus,
    onEnableClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status.hasAllCritical) return

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xF21E293B),
        border = BorderStroke(1.dp, AmberAction.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("permission_warning_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = AmberAction,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Arrival Alarms Disabled",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (status.isLocationMissing && status.isNotificationMissing) {
                            "Location & notification access needed"
                        } else if (status.isLocationMissing) {
                            "Precise GPS location needed"
                        } else {
                            "Notification permission needed"
                        },
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onEnableClicked,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberAction,
                    contentColor = DarkBackground
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("permission_banner_action_button")
            ) {
                Text(
                    text = "ENABLE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
