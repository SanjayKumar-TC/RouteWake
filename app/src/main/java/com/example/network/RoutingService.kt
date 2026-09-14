package com.example.network

import com.example.data.model.CongestionLevel
import com.example.data.model.RouteInfo
import com.example.data.model.RoutePoint
import com.example.data.model.TrafficData
import com.example.data.model.TrafficSegment
import com.example.data.model.TransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.*

class RoutingService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun calculateRoute(
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double,
        transportMode: TransportMode
    ): RouteInfo = withContext(Dispatchers.IO) {
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/$startLng,$startLat;$destLng,$destLat?overview=full&geometries=geojson&annotations=true&steps=true"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RouteWake-Android-App/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val root = JSONObject(body)
                        val code = root.optString("code")
                        if (code == "Ok") {
                            val routes = root.optJSONArray("routes")
                            if (routes != null && routes.length() > 0) {
                                val firstRoute = routes.getJSONObject(0)
                                val distance = firstRoute.optDouble("distance", 0.0)
                                val osrmDuration = firstRoute.optDouble("duration", 0.0).toLong()

                                val geometry = firstRoute.optJSONObject("geometry")
                                val coordinates = geometry?.optJSONArray("coordinates")
                                val points = mutableListOf<RoutePoint>()

                                if (coordinates != null) {
                                    for (i in 0 until coordinates.length()) {
                                        val pt = coordinates.getJSONArray(i)
                                        val lon = pt.getDouble(0)
                                        val lat = pt.getDouble(1)
                                        points.add(RoutePoint(lat, lon))
                                    }
                                }

                                val calculatedDuration = if (transportMode == TransportMode.CAR) {
                                    if (osrmDuration > 0) osrmDuration else (distance / 13.88).toLong()
                                } else {
                                    val speedMps = (transportMode.fallbackSpeedKmh * 1000.0) / 3600.0
                                    (distance / max(speedMps, 1.0)).toLong()
                                }

                                if (points.isNotEmpty() && distance > 0) {
                                    val legObj = firstRoute.optJSONArray("legs")?.optJSONObject(0)
                                    // Parse OSRM speed annotations for native traffic segmentation
                                    val speedArray = legObj?.optJSONObject("annotation")?.optJSONArray("speed")

                                    // Parse step road names if available
                                    val stepsList = mutableListOf<StepInfo>()
                                    val stepsArray = legObj?.optJSONArray("steps")
                                    if (stepsArray != null) {
                                        for (s in 0 until stepsArray.length()) {
                                            val stepObj = stepsArray.optJSONObject(s)
                                            val sName = stepObj?.optString("name") ?: ""
                                            val locArr = stepObj?.optJSONObject("maneuver")?.optJSONArray("location")
                                            if (sName.isNotBlank() && locArr != null && locArr.length() >= 2) {
                                                val sLon = locArr.optDouble(0)
                                                val sLat = locArr.optDouble(1)
                                                stepsList.add(StepInfo(sName, sLat, sLon))
                                            }
                                        }
                                    }

                                    val trafficData = buildTrafficData(points, speedArray, stepsList, transportMode.fallbackSpeedKmh)

                                    return@withContext RouteInfo(
                                        distanceMeters = distance,
                                        durationSeconds = max(1L, calculatedDuration),
                                        points = points,
                                        isModeled = false,
                                        trafficData = trafficData
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to modeled route
        }

        // Offline / Modeled fallback route
        return@withContext generateModeledRoute(startLat, startLng, destLat, destLng, transportMode)
    }

    private fun generateModeledRoute(
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double,
        transportMode: TransportMode
    ): RouteInfo {
        val distance = computeHaversineDistanceMeters(startLat, startLng, destLat, destLng)
        val speedMps = (transportMode.fallbackSpeedKmh * 1000.0) / 3600.0
        val durationSeconds = (distance / max(speedMps, 1.0)).toLong()

        // Generate smooth intermediate waypoints
        val steps = 20
        val points = mutableListOf<RoutePoint>()
        for (i in 0..steps) {
            val fraction = i.toDouble() / steps.toDouble()
            val lat = startLat + (destLat - startLat) * fraction
            val lng = startLng + (destLng - startLng) * fraction
            points.add(RoutePoint(lat, lng))
        }

        val trafficData = generateModeledTrafficData(points, transportMode)

        return RouteInfo(
            distanceMeters = distance,
            durationSeconds = durationSeconds,
            points = points,
            isModeled = true,
            trafficData = trafficData
        )
    }

    private fun buildTrafficData(
        points: List<RoutePoint>,
        speedArray: JSONArray?,
        steps: List<StepInfo> = emptyList(),
        fallbackSpeedKmh: Double = 50.0
    ): TrafficData {
        if (points.size < 2) {
            return TrafficData(segments = emptyList(), isEstimated = true)
        }

        if (speedArray != null && speedArray.length() == points.size - 1) {
            val speedsKmh = mutableListOf<Double>()
            for (i in 0 until speedArray.length()) {
                val mps = speedArray.optDouble(i, 13.88)
                speedsKmh.add(mps * 3.6)
            }
            val segments = groupPointsBySpeeds(points, speedsKmh, steps, fallbackSpeedKmh)
            return TrafficData(
                segments = segments,
                incidents = emptyList(),
                isEstimated = true,
                summaryLabel = "Estimated Traffic (OSRM Road Speeds)"
            )
        }

        // Fallback if annotations are missing
        return generateModeledTrafficData(points, TransportMode.CAR, steps)
    }

    private fun generateModeledTrafficData(
        points: List<RoutePoint>,
        transportMode: TransportMode,
        steps: List<StepInfo> = emptyList()
    ): TrafficData {
        if (points.size < 2) {
            return TrafficData(segments = emptyList(), isEstimated = true)
        }

        // Modeled traffic profile: slight urban congestion near start and destination, clear in middle
        val n = points.size - 1
        val speedsKmh = mutableListOf<Double>()
        val baseSpeed = transportMode.fallbackSpeedKmh

        for (i in 0 until n) {
            val progress = i.toDouble() / n.toDouble()
            val speed = when {
                progress < 0.2 -> baseSpeed * 0.55 // Departure slowdown
                progress > 0.8 -> baseSpeed * 0.60 // Arrival congestion
                else -> baseSpeed * 1.15           // Open road cruising
            }
            speedsKmh.add(speed)
        }

        val segments = groupPointsBySpeeds(points, speedsKmh, steps, baseSpeed)
        return TrafficData(
            segments = segments,
            incidents = emptyList(),
            isEstimated = true,
            summaryLabel = "Estimated Traffic (Modeled)"
        )
    }

    companion object {
        data class StepInfo(val name: String, val lat: Double, val lon: Double)

        fun computeHaversineDistanceMeters(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double
        ): Double {
            val r = 6371000.0 // Earth radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2.0) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2.0)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }

        /**
         * Segments route points according to speeds, ensuring adjacent segments share
         * boundary coordinates to eliminate any visible gaps between colors.
         */
        fun groupPointsBySpeeds(
            points: List<RoutePoint>,
            speedsKmh: List<Double>,
            steps: List<StepInfo> = emptyList(),
            baseNormalSpeedKmh: Double = 50.0
        ): List<TrafficSegment> {
            if (points.size < 2 || speedsKmh.isEmpty()) return emptyList()

            fun createSegment(pts: List<RoutePoint>, level: CongestionLevel, avgSpeed: Double): TrafficSegment {
                var segDist = 0.0
                for (p in 0 until pts.size - 1) {
                    segDist += computeHaversineDistanceMeters(
                        pts[p].latitude, pts[p].longitude,
                        pts[p + 1].latitude, pts[p + 1].longitude
                    )
                }
                val normalSpeed = maxOf(baseNormalSpeedKmh, 45.0)
                val delayMin = if (avgSpeed < normalSpeed && segDist > 80.0) {
                    val normalSec = segDist / (normalSpeed / 3.6)
                    val actualSec = segDist / (maxOf(avgSpeed, 1.0) / 3.6)
                    val diff = kotlin.math.round((actualSec - normalSec) / 60.0).toInt()
                    if (diff > 0) diff else 0
                } else if (level == CongestionLevel.CLEAR) {
                    0
                } else null

                val midPt = pts[pts.size / 2]
                val roadName = steps.minByOrNull {
                    computeHaversineDistanceMeters(midPt.latitude, midPt.longitude, it.lat, it.lon)
                }?.name?.takeIf { it.isNotBlank() }

                val incident = when (level) {
                    CongestionLevel.SEVERE -> "Severe congestion ahead"
                    CongestionLevel.HEAVY -> "Traffic slowdown"
                    CongestionLevel.MODERATE -> if ((delayMin ?: 0) >= 3) "Congestion ahead" else null
                    CongestionLevel.CLEAR -> null
                }

                return TrafficSegment(
                    points = pts,
                    congestionLevel = level,
                    speedKmh = avgSpeed,
                    normalSpeedKmh = normalSpeed,
                    roadName = roadName,
                    delayMinutes = delayMin,
                    distanceMeters = if (segDist > 0.0) segDist else null,
                    incident = incident
                )
            }

            val segments = mutableListOf<TrafficSegment>()
            var currentLevel = CongestionLevel.fromSpeedKmh(speedsKmh[0])
            var currentPoints = mutableListOf(points[0], points[1])
            var totalSpeed = speedsKmh[0]
            var count = 1

            for (i in 1 until minOf(speedsKmh.size, points.size - 1)) {
                val nextLevel = CongestionLevel.fromSpeedKmh(speedsKmh[i])
                if (nextLevel == currentLevel) {
                    currentPoints.add(points[i + 1])
                    totalSpeed += speedsKmh[i]
                    count++
                } else {
                    segments.add(createSegment(currentPoints.toList(), currentLevel, totalSpeed / count))
                    currentLevel = nextLevel
                    // Crucial: start new segment with points[i] so boundary coordinate is shared
                    currentPoints = mutableListOf(points[i], points[i + 1])
                    totalSpeed = speedsKmh[i]
                    count = 1
                }
            }

            if (currentPoints.size >= 2) {
                segments.add(createSegment(currentPoints.toList(), currentLevel, totalSpeed / count))
            }

            return segments
        }
    }
}

