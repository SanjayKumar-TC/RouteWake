package com.example.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Distinct lifecycle states for RouteWake device location prerequisites.
 *
 * Distinctly separates:
 * 1. LocationDisabled: Device Location service (Master toggle) is OFF.
 * 2. PermissionRequired: Runtime permission (ACCESS_FINE_LOCATION) missing.
 * 3. HighAccuracyDisabled: Location is ON & permission granted, but high-accuracy GPS is disabled.
 * 4. Satisfied: All prerequisites met; safe to start GPS tracking.
 */
sealed interface LocationPrerequisiteState {
    object Checking : LocationPrerequisiteState
    object Satisfied : LocationPrerequisiteState

    data class LocationDisabled(
        val resolvableException: ResolvableApiException? = null
    ) : LocationPrerequisiteState

    data class PermissionRequired(
        val isPermanentlyDenied: Boolean = false,
        val missingPermissions: List<String> = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    ) : LocationPrerequisiteState

    data class HighAccuracyDisabled(
        val resolvableException: ResolvableApiException? = null
    ) : LocationPrerequisiteState
}

object LocationPrerequisiteManager {

    /**
     * Checks whether the device's master location service is enabled.
     */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            LocationManagerCompat.isLocationEnabled(lm)
        }
    }

    /**
     * Checks whether ACCESS_FINE_LOCATION runtime permission is granted.
     */
    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks whether either fine or coarse location runtime permission is granted.
     */
    fun hasAnyLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Comprehensive prerequisite verification following RouteWake's strict flow:
     * STEP 1: Check device location service (Master Switch).
     * STEP 2: Check runtime location permissions.
     * STEP 3: Check high-accuracy location availability (SettingsClient).
     */
    fun checkPrerequisites(
        context: Context,
        onResult: (LocationPrerequisiteState) -> Unit
    ) {
        val isLocEnabled = isLocationEnabled(context)
        val hasFine = hasFineLocationPermission(context)
        val hasCoarse = hasAnyLocationPermission(context)

        // Build Google Play Services LocationSettingsRequest for PRIORITY_HIGH_ACCURACY
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true) // Crucial to prompt user to enable
            .setNeedBle(false)
            .build()

        // STEP 1 — CHECK DEVICE LOCATION SERVICES
        if (!isLocEnabled) {
            try {
                val client = LocationServices.getSettingsClient(context)
                client.checkLocationSettings(settingsRequest)
                    .addOnSuccessListener {
                        if (!isLocationEnabled(context)) {
                            onResult(LocationPrerequisiteState.LocationDisabled(null))
                        } else if (!hasFine && !hasCoarse) {
                            onResult(LocationPrerequisiteState.PermissionRequired())
                        } else {
                            onResult(LocationPrerequisiteState.Satisfied)
                        }
                    }
                    .addOnFailureListener { exception ->
                        if (exception is ResolvableApiException) {
                            onResult(LocationPrerequisiteState.LocationDisabled(exception))
                        } else {
                            onResult(LocationPrerequisiteState.LocationDisabled(null))
                        }
                    }
            } catch (_: Throwable) {
                onResult(LocationPrerequisiteState.LocationDisabled(null))
            }
            return
        }

        // STEP 2 — CHECK RUNTIME PERMISSION
        if (!hasFine && !hasCoarse) {
            onResult(LocationPrerequisiteState.PermissionRequired())
            return
        }

        // STEP 3 — CHECK HIGH ACCURACY AVAILABILITY
        try {
            val client = LocationServices.getSettingsClient(context)
            client.checkLocationSettings(settingsRequest)
                .addOnSuccessListener { response ->
                    val states = response.locationSettingsStates
                    val isLocationUsable = states?.isLocationUsable ?: true
                    val isGpsUsable = states?.isGpsUsable ?: true

                    if (!isLocationUsable) {
                        onResult(LocationPrerequisiteState.LocationDisabled(null))
                    } else if (!isGpsUsable) {
                        onResult(LocationPrerequisiteState.HighAccuracyDisabled(null))
                    } else {
                        onResult(LocationPrerequisiteState.Satisfied)
                    }
                }
                .addOnFailureListener { exception ->
                    if (exception is ResolvableApiException) {
                        onResult(LocationPrerequisiteState.HighAccuracyDisabled(exception))
                    } else {
                        onResult(LocationPrerequisiteState.HighAccuracyDisabled(null))
                    }
                }
        } catch (_: Throwable) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val hasGps = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            if (!hasGps) {
                onResult(LocationPrerequisiteState.HighAccuracyDisabled(null))
            } else {
                onResult(LocationPrerequisiteState.Satisfied)
            }
        }
    }

    /**
     * Direct navigation to Android Device Location Settings.
     */
    fun openLocationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            openAppSettings(context)
        }
    }

    /**
     * Direct navigation to RouteWake Application Details in Android Settings.
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {}
    }
}
