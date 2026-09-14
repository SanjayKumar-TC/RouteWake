package com.example.ui.map

import android.location.Location
import com.example.data.model.RoutePoint
import com.example.data.model.TrafficSegment
import kotlin.math.*

/**
 * High-performance geometric engine for live remaining-route navigation progress
 * and traffic segment clipping.
 *
 * Core Principles:
 * - Uses authoritative GPS coordinate and OSRM route geometry.
 * - Finds true perpendicular projection on route polyline segments.
 * - Anti-jitter protection: maintains furthest confirmed progress to prevent
 *   flickering or backward jumping during GPS jitter.
 * - Off-route protection: does not advance route progress when user is far away
 *   from the route (>150m).
 * - Near-destination preservation: guarantees the remaining route does not disappear
 *   prematurely before arrival.
 */
object RouteProgressHelper {

    /**
     * Calculates candidate progress along the route and perpendicular distance to route.
     * Returns Pair(candidateProgressMeters, distanceToRouteMeters).
     */
    fun calculateCandidateProgress(
        userLat: Double,
        userLng: Double,
        routePoints: List<RoutePoint>,
        currentConfirmedProgressMeters: Double = 0.0
    ): Pair<Double, Double> {
        if (routePoints.size < 2) return Pair(0.0, 0.0)

        val n = routePoints.size
        val segmentDistances = DoubleArray(n - 1)
        val cumulativeDistances = DoubleArray(n)
        cumulativeDistances[0] = 0.0

        for (i in 0 until n - 1) {
            val p1 = routePoints[i]
            val p2 = routePoints[i + 1]
            val dist = computeDistanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            segmentDistances[i] = dist
            cumulativeDistances[i + 1] = cumulativeDistances[i] + dist
        }

        var bestProgress = 0.0
        var minDistanceToRoute = Double.MAX_VALUE

        // First pass: look for segments near or ahead of current confirmed progress (within reasonable window)
        // This prevents snapping to parallel roads or returning loops far ahead.
        var foundInWindow = false
        val windowBackMeters = 40.0 // Allow small projection jitter backward

        for (i in 0 until n - 1) {
            val segStartDist = cumulativeDistances[i]
            val segEndDist = cumulativeDistances[i + 1]

            // If we have existing confirmed progress, prioritize segments ahead or nearby
            if (currentConfirmedProgressMeters > 10.0 && segEndDist < currentConfirmedProgressMeters - windowBackMeters) {
                continue
            }

            val p1 = routePoints[i]
            val p2 = routePoints[i + 1]
            val (projProgressOnSeg, perpDist) = projectPointOnSegment(
                userLat, userLng,
                p1.latitude, p1.longitude,
                p2.latitude, p2.longitude,
                segmentDistances[i]
            )

            val totalSegProgress = segStartDist + projProgressOnSeg

            if (perpDist < minDistanceToRoute) {
                minDistanceToRoute = perpDist
                bestProgress = totalSegProgress
                foundInWindow = true
            }
        }

        // If not found in forward window or distance is very high, search all segments
        if (!foundInWindow || minDistanceToRoute > 150.0) {
            for (i in 0 until n - 1) {
                val p1 = routePoints[i]
                val p2 = routePoints[i + 1]
                val segStartDist = cumulativeDistances[i]

                val (projProgressOnSeg, perpDist) = projectPointOnSegment(
                    userLat, userLng,
                    p1.latitude, p1.longitude,
                    p2.latitude, p2.longitude,
                    segmentDistances[i]
                )

                val totalSegProgress = segStartDist + projProgressOnSeg

                if (perpDist < minDistanceToRoute) {
                    minDistanceToRoute = perpDist
                    bestProgress = totalSegProgress
                }
            }
        }

        return Pair(bestProgress, minDistanceToRoute)
    }

    /**
     * Updates confirmed route progress with anti-jitter protection and off-route safety.
     */
    fun updateConfirmedProgress(
        currentConfirmedProgress: Double,
        candidateProgress: Double,
        distanceToRoute: Double,
        totalRouteDistance: Double,
        isRerouteOrReset: Boolean = false
    ): Double {
        if (isRerouteOrReset) {
            return candidateProgress.coerceIn(0.0, (totalRouteDistance - 15.0).coerceAtLeast(0.0))
        }

        // Off-route protection: if GPS position is significantly away from route (>150m),
        // do NOT advance progress based on an inaccurate fix.
        if (distanceToRoute > 150.0) {
            return currentConfirmedProgress
        }

        // Anti-jitter: If GPS jitter causes calculated route-progress position to move
        // backward by a small amount, do NOT make the remaining route suddenly grow backward.
        // Maintain furthest confirmed progress.
        if (candidateProgress < currentConfirmedProgress) {
            return currentConfirmedProgress
        }

        // Near-destination protection: Do not remove the entire route prematurely.
        // Keep at least 15m of remaining route until ArrivalEngine confirms arrival.
        val maxAllowedProgress = (totalRouteDistance - 15.0).coerceAtLeast(0.0)
        return minOf(candidateProgress, maxAllowedProgress)
    }

    /**
     * Computes the remaining route points from the progress distance to the destination.
     * The first point is the exact projected progress point on the current road segment.
     */
    fun computeRemainingRoutePoints(
        routePoints: List<RoutePoint>,
        progressMeters: Double
    ): List<RoutePoint> {
        if (routePoints.size < 2) return routePoints
        if (progressMeters <= 3.0) return routePoints

        val n = routePoints.size
        val segmentDistances = DoubleArray(n - 1)
        val cumulativeDistances = DoubleArray(n)
        cumulativeDistances[0] = 0.0

        for (i in 0 until n - 1) {
            val p1 = routePoints[i]
            val p2 = routePoints[i + 1]
            val dist = computeDistanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            segmentDistances[i] = dist
            cumulativeDistances[i + 1] = cumulativeDistances[i] + dist
        }

        val totalDist = cumulativeDistances[n - 1]
        val clampedProgress = progressMeters.coerceIn(0.0, (totalDist - 10.0).coerceAtLeast(0.0))

        // Find segment k containing clampedProgress
        var segIndex = 0
        for (i in 0 until n - 1) {
            if (clampedProgress <= cumulativeDistances[i + 1]) {
                segIndex = i
                break
            }
            segIndex = i
        }

        val segStartDist = cumulativeDistances[segIndex]
        val segLen = segmentDistances[segIndex]
        val t = if (segLen > 0.0) {
            ((clampedProgress - segStartDist) / segLen).coerceIn(0.0, 1.0)
        } else 0.0

        val p1 = routePoints[segIndex]
        val p2 = routePoints[segIndex + 1]

        val cutLat = p1.latitude + t * (p2.latitude - p1.latitude)
        val cutLng = p1.longitude + t * (p2.longitude - p1.longitude)
        val cutPoint = RoutePoint(cutLat, cutLng)

        val remaining = mutableListOf<RoutePoint>()
        remaining.add(cutPoint)

        for (i in segIndex + 1 until n) {
            remaining.add(routePoints[i])
        }

        // Ensure at least 2 points
        if (remaining.size < 2) {
            remaining.add(routePoints.last())
        }

        return remaining
    }

    /**
     * Clips the traffic segments so that only segments ahead of progressMeters are retained.
     * The segment containing progressMeters is clipped to start at the progress cut-point.
     * Past segments are removed.
     */
    fun computeRemainingTrafficSegments(
        originalSegments: List<TrafficSegment>,
        routePoints: List<RoutePoint>,
        progressMeters: Double
    ): List<TrafficSegment> {
        if (originalSegments.isEmpty() || routePoints.size < 2) return originalSegments
        if (progressMeters <= 3.0) return originalSegments

        val remainingRoute = computeRemainingRoutePoints(routePoints, progressMeters)
        if (remainingRoute.isEmpty()) return emptyList()

        val cutPoint = remainingRoute.first()

        // Calculate cumulative distance of each original segment
        val segmentBounds = mutableListOf<Pair<Double, Double>>()
        var accumDist = 0.0

        for (seg in originalSegments) {
            val pts = seg.points
            var segLen = 0.0
            if (pts.size >= 2) {
                for (i in 0 until pts.size - 1) {
                    segLen += computeDistanceMeters(
                        pts[i].latitude, pts[i].longitude,
                        pts[i + 1].latitude, pts[i + 1].longitude
                    )
                }
            }
            segmentBounds.add(Pair(accumDist, accumDist + segLen))
            accumDist += segLen
        }

        val result = mutableListOf<TrafficSegment>()

        for (i in originalSegments.indices) {
            val seg = originalSegments[i]
            val (startDist, endDist) = segmentBounds[i]

            if (progressMeters >= endDist - 1.0) {
                // Segment is completely travelled -> skip/hide
                continue
            } else if (progressMeters <= startDist + 1.0) {
                // Segment is completely ahead -> keep entire segment
                result.add(seg)
            } else {
                // Progress is inside this segment -> clip segment to start at cutPoint
                val remainingSegPoints = mutableListOf<RoutePoint>()
                remainingSegPoints.add(cutPoint)

                // Add vertices of this segment that lie ahead of the cutPoint
                val segPts = seg.points
                if (segPts.size >= 2) {
                    var segAccum = startDist
                    for (j in 0 until segPts.size - 1) {
                        val d = computeDistanceMeters(
                            segPts[j].latitude, segPts[j].longitude,
                            segPts[j + 1].latitude, segPts[j + 1].longitude
                        )
                        if (segAccum + d > progressMeters) {
                            remainingSegPoints.add(segPts[j + 1])
                        }
                        segAccum += d
                    }
                }

                if (remainingSegPoints.size >= 2) {
                    result.add(seg.copy(points = remainingSegPoints))
                } else if (segPts.isNotEmpty()) {
                    result.add(seg.copy(points = listOf(cutPoint, segPts.last())))
                }
            }
        }

        return result
    }

    /**
     * Computes revealed route points from the start (GPS location) up to revealedMeters.
     * Used for smooth route drawing animation from user location toward destination.
     */
    fun computeRevealedRoutePoints(
        routePoints: List<RoutePoint>,
        revealedMeters: Double
    ): List<RoutePoint> {
        if (routePoints.size < 2) return routePoints
        if (revealedMeters <= 0.0) return emptyList()

        val n = routePoints.size
        val segmentDistances = DoubleArray(n - 1)
        val cumulativeDistances = DoubleArray(n)
        cumulativeDistances[0] = 0.0

        for (i in 0 until n - 1) {
            val p1 = routePoints[i]
            val p2 = routePoints[i + 1]
            val dist = computeDistanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            segmentDistances[i] = dist
            cumulativeDistances[i + 1] = cumulativeDistances[i] + dist
        }

        val totalDist = cumulativeDistances[n - 1]
        if (revealedMeters >= totalDist) return routePoints

        val clampedRevealed = revealedMeters.coerceIn(0.0, totalDist)

        // Find segment k containing clampedRevealed
        var segIndex = 0
        for (i in 0 until n - 1) {
            if (clampedRevealed <= cumulativeDistances[i + 1]) {
                segIndex = i
                break
            }
            segIndex = i
        }

        val segStartDist = cumulativeDistances[segIndex]
        val segLen = segmentDistances[segIndex]
        val t = if (segLen > 0.0) {
            ((clampedRevealed - segStartDist) / segLen).coerceIn(0.0, 1.0)
        } else 0.0

        val p1 = routePoints[segIndex]
        val p2 = routePoints[segIndex + 1]

        val cutLat = p1.latitude + t * (p2.latitude - p1.latitude)
        val cutLng = p1.longitude + t * (p2.longitude - p1.longitude)
        val cutPoint = RoutePoint(cutLat, cutLng)

        val revealed = mutableListOf<RoutePoint>()
        for (i in 0..segIndex) {
            revealed.add(routePoints[i])
        }

        // Add interpolated point if not identical to last point
        val lastPoint = revealed.last()
        val distToLast = computeDistanceMeters(lastPoint.latitude, lastPoint.longitude, cutPoint.latitude, cutPoint.longitude)
        if (distToLast > 0.5) {
            revealed.add(cutPoint)
        }

        // Ensure at least 2 points if revealedMeters > 0
        if (revealed.size < 2) {
            revealed.add(cutPoint)
        }

        return revealed
    }

    /**
     * Computes revealed traffic segments from the start (GPS location) up to revealedMeters.
     * Used for progressive traffic-colored route animation.
     */
    fun computeRevealedTrafficSegments(
        originalSegments: List<TrafficSegment>,
        routePoints: List<RoutePoint>,
        revealedMeters: Double
    ): List<TrafficSegment> {
        if (originalSegments.isEmpty() || routePoints.size < 2) return originalSegments
        if (revealedMeters <= 0.0) return emptyList()

        val revealedRoute = computeRevealedRoutePoints(routePoints, revealedMeters)
        if (revealedRoute.size < 2) return emptyList()

        val cutPoint = revealedRoute.last()

        // Calculate cumulative distance of each original segment
        val segmentBounds = mutableListOf<Pair<Double, Double>>()
        var accumDist = 0.0

        for (seg in originalSegments) {
            val pts = seg.points
            var segLen = 0.0
            if (pts.size >= 2) {
                for (i in 0 until pts.size - 1) {
                    segLen += computeDistanceMeters(
                        pts[i].latitude, pts[i].longitude,
                        pts[i + 1].latitude, pts[i + 1].longitude
                    )
                }
            }
            segmentBounds.add(Pair(accumDist, accumDist + segLen))
            accumDist += segLen
        }

        val totalDist = accumDist
        if (revealedMeters >= totalDist) return originalSegments

        val result = mutableListOf<TrafficSegment>()

        for (i in originalSegments.indices) {
            val seg = originalSegments[i]
            val (startDist, endDist) = segmentBounds[i]

            if (revealedMeters <= startDist) {
                // Segment is completely ahead of the revealed front -> not drawn yet
                continue
            } else if (revealedMeters >= endDist) {
                // Segment is completely behind the revealed front -> fully drawn
                result.add(seg)
            } else {
                // Revealed front is inside this segment -> clip segment to end at cutPoint
                val segPts = seg.points
                val partialSegPoints = mutableListOf<RoutePoint>()
                var segAccum = startDist

                if (segPts.isNotEmpty()) {
                    partialSegPoints.add(segPts.first())
                    for (j in 0 until segPts.size - 1) {
                        val d = computeDistanceMeters(
                            segPts[j].latitude, segPts[j].longitude,
                            segPts[j + 1].latitude, segPts[j + 1].longitude
                        )
                        if (segAccum + d < revealedMeters) {
                            partialSegPoints.add(segPts[j + 1])
                        }
                        segAccum += d
                    }
                }

                val lastPt = partialSegPoints.lastOrNull()
                if (lastPt != null) {
                    val distToLast = computeDistanceMeters(lastPt.latitude, lastPt.longitude, cutPoint.latitude, cutPoint.longitude)
                    if (distToLast > 0.5) {
                        partialSegPoints.add(cutPoint)
                    }
                } else {
                    partialSegPoints.add(cutPoint)
                }

                if (partialSegPoints.size >= 2) {
                    result.add(seg.copy(points = partialSegPoints))
                } else if (segPts.isNotEmpty()) {
                    result.add(seg.copy(points = listOf(segPts.first(), cutPoint)))
                }
            }
        }

        return result
    }

    /**
     * Projects point (userLat, userLng) onto line segment (lat1, lng1)-(lat2, lng2).
     * Returns Pair(distanceAlongSegmentMeters, perpendicularDistanceMeters).
     */
    private fun projectPointOnSegment(
        userLat: Double,
        userLng: Double,
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
        segmentLengthMeters: Double
    ): Pair<Double, Double> {
        val midLatRad = Math.toRadians((lat1 + lat2) / 2.0)
        val cosMidLat = cos(midLatRad)

        // Equirectangular local flat-earth coordinates in meters
        val dx = (lng2 - lng1) * cosMidLat * 111320.0
        val dy = (lat2 - lat1) * 110540.0
        val segLenSq = dx * dx + dy * dy

        val ux = (userLng - lng1) * cosMidLat * 111320.0
        val uy = (userLat - lat1) * 110540.0

        if (segLenSq < 1e-4) {
            val dist = sqrt(ux * ux + uy * uy)
            return Pair(0.0, dist)
        }

        val t = ((ux * dx + uy * dy) / segLenSq).coerceIn(0.0, 1.0)
        val projX = t * dx
        val projY = t * dy

        val pdx = ux - projX
        val pdy = uy - projY
        val perpDist = sqrt(pdx * pdx + pdy * pdy)
        val progressOnSeg = t * segmentLengthMeters

        return Pair(progressOnSeg, perpDist)
    }

    /**
     * Computes distance between two coordinates in meters using Android Location.distanceBetween.
     */
    fun computeDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toDouble()
    }

    /**
     * Computes total distance of a route in meters.
     */
    fun computeTotalRouteDistance(routePoints: List<RoutePoint>): Double {
        if (routePoints.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until routePoints.size - 1) {
            total += computeDistanceMeters(
                routePoints[i].latitude, routePoints[i].longitude,
                routePoints[i + 1].latitude, routePoints[i + 1].longitude
            )
        }
        return total
    }
}
