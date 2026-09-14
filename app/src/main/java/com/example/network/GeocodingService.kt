package com.example.network

import com.example.data.model.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GeocodingService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun searchDestinations(query: String): List<Destination> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        try {
            // Attempt 1: Photon API (Fast OSM-based geocoder)
            val photonResults = queryPhoton(trimmed)
            if (photonResults.isNotEmpty()) {
                return@withContext photonResults
            }
        } catch (_: Exception) {
            // Fallback to Nominatim
        }

        try {
            // Attempt 2: Nominatim fallback
            val nominatimResults = queryNominatim(trimmed)
            if (nominatimResults.isNotEmpty()) {
                return@withContext nominatimResults
            }
        } catch (_: Exception) {
            // Fallback to offline curated database
        }

        return@withContext searchFallbackDestinations(trimmed)
    }

    private fun searchFallbackDestinations(query: String): List<Destination> {
        val lower = query.lowercase().trim()
        val matches = fallbackDestinations.filter {
            it.name.lowercase().contains(lower) ||
            it.address.lowercase().contains(lower) ||
            it.category.lowercase().contains(lower) ||
            (lower.contains("metro") && it.category == "Metro") ||
            (lower.contains("train") && it.category == "Train") ||
            (lower.contains("bus") && it.category == "Bus") ||
            (lower.contains("airport") && it.category == "Airport") ||
            (lower.contains("work") && it.category == "Work") ||
            (lower.contains("home") && it.category == "Home")
        }
        if (matches.isNotEmpty()) return matches

        return listOf(
            Destination(
                name = query,
                address = "San Francisco Bay Area",
                latitude = 37.7749 + ((query.hashCode() % 50) * 0.001),
                longitude = -122.4194 + (((query.hashCode() / 50) % 50) * 0.001),
                category = detectCategory(query, "")
            )
        )
    }

    private val fallbackDestinations = listOf(
        Destination(name = "San Francisco International Airport (SFO)", address = "San Francisco, CA 94128", latitude = 37.6213, longitude = -122.3790, category = "Airport"),
        Destination(name = "Oakland International Airport (OAK)", address = "1 Airport Dr, Oakland, CA 94621", latitude = 37.7126, longitude = -122.2197, category = "Airport"),
        Destination(name = "San Jose Mineta International Airport (SJC)", address = "1701 Airport Blvd, San Jose, CA 95110", latitude = 37.3639, longitude = -121.9289, category = "Airport"),
        Destination(name = "Los Angeles International Airport (LAX)", address = "1 World Way, Los Angeles, CA 90045", latitude = 33.9416, longitude = -118.4085, category = "Airport"),
        Destination(name = "John F. Kennedy International Airport (JFK)", address = "Queens, NY 11430", latitude = 40.6413, longitude = -73.7781, category = "Airport"),
        Destination(name = "Chicago O'Hare International Airport (ORD)", address = "10000 W Balmoral Ave, Chicago, IL 60666", latitude = 41.9742, longitude = -87.9073, category = "Airport"),
        Destination(name = "Powell Street BART & Metro Station", address = "Market St & Powell St, San Francisco, CA", latitude = 37.7844, longitude = -122.4080, category = "Metro"),
        Destination(name = "Embarcadero BART & Metro Station", address = "Market St & Main St, San Francisco, CA", latitude = 37.7929, longitude = -122.3970, category = "Metro"),
        Destination(name = "Montgomery St BART & Metro Station", address = "598 Market St, San Francisco, CA", latitude = 37.7894, longitude = -122.4014, category = "Metro"),
        Destination(name = "16th St Mission BART Station", address = "2000 Mission St, San Francisco, CA", latitude = 37.7650, longitude = -122.4197, category = "Metro"),
        Destination(name = "24th St Mission BART Station", address = "2800 Mission St, San Francisco, CA", latitude = 37.7522, longitude = -122.4187, category = "Metro"),
        Destination(name = "San Francisco Caltrain Station (4th & King)", address = "700 4th St, San Francisco, CA", latitude = 37.7766, longitude = -122.3950, category = "Train"),
        Destination(name = "Millbrae Transit Center", address = "California Dr, Millbrae, CA", latitude = 37.5997, longitude = -122.3867, category = "Train"),
        Destination(name = "Salesforce Transit Center & Bus Terminal", address = "425 Mission St, San Francisco, CA", latitude = 37.7896, longitude = -122.3969, category = "Bus"),
        Destination(name = "San Francisco Ferry Building", address = "1 Ferry Building, San Francisco, CA", latitude = 37.7955, longitude = -122.3937, category = "Custom"),
        Destination(name = "Fisherman's Wharf", address = "Jefferson St, San Francisco, CA", latitude = 37.8080, longitude = -122.4177, category = "Custom"),
        Destination(name = "Grand Central Terminal", address = "89 E 42nd St, New York, NY 10017", latitude = 40.7527, longitude = -73.9772, category = "Train"),
        Destination(name = "Penn Station", address = "Pennsylvania Station, New York, NY 10001", latitude = 40.7505, longitude = -73.9934, category = "Train"),
        Destination(name = "Downtown Office Center", address = "Market St & 3rd St, Financial District, SF", latitude = 37.7885, longitude = -122.4015, category = "Work"),
        Destination(name = "Sunset Residential District", address = "Sunset Blvd, San Francisco, CA", latitude = 37.7550, longitude = -122.4940, category = "Home")
    )

    private fun queryPhoton(query: String): List<Destination> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://photon.komoot.de/api/?q=$encoded&limit=12"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RouteWake-Android-App/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val features = root.optJSONArray("features") ?: return emptyList()

            val results = mutableListOf<Destination>()
            for (i in 0 until features.length()) {
                val item = features.getJSONObject(i)
                val geometry = item.optJSONObject("geometry") ?: continue
                val coords = geometry.optJSONArray("coordinates") ?: continue
                val lon = coords.optDouble(0)
                val lat = coords.optDouble(1)

                val props = item.optJSONObject("properties") ?: JSONObject()
                val name = props.optString("name").ifEmpty {
                    props.optString("street").ifEmpty { query }
                }

                val addressParts = listOfNotNull(
                    props.optString("street").takeIf { it.isNotEmpty() && it != name },
                    props.optString("city").takeIf { it.isNotEmpty() },
                    props.optString("state").takeIf { it.isNotEmpty() },
                    props.optString("country").takeIf { it.isNotEmpty() }
                )
                val address = if (addressParts.isNotEmpty()) {
                    addressParts.joinToString(", ")
                } else {
                    props.optString("country", "Known Location")
                }

                val type = props.optString("osm_value", "").lowercase()
                val category = detectCategory(name, type)

                results.add(
                    Destination(
                        name = name,
                        address = address,
                        latitude = lat,
                        longitude = lon,
                        category = category
                    )
                )
            }
            return results
        }
    }

    private fun queryNominatim(query: String): List<Destination> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?format=json&q=$encoded&limit=10&addressdetails=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RouteWake-Android-App/1.0 (contact: support@routewake.app)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val array = org.json.JSONArray(body)

            val results = mutableListOf<Destination>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val lat = item.optDouble("lat")
                val lon = item.optDouble("lon")
                val displayName = item.optString("display_name")
                val parts = displayName.split(",")
                val name = parts.firstOrNull()?.trim() ?: query
                val address = parts.drop(1).take(3).joinToString(",").trim().ifEmpty { displayName }
                val type = item.optString("type", "").lowercase()

                results.add(
                    Destination(
                        name = name,
                        address = address,
                        latitude = lat,
                        longitude = lon,
                        category = detectCategory(name, type)
                    )
                )
            }
            return results
        }
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): Destination = withContext(Dispatchers.IO) {
        // 1. Primary: Try Photon Reverse Geocoding
        try {
            val url = "https://photon.komoot.de/reverse?lat=$lat&lon=$lon"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RouteWake-Android-App/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val root = JSONObject(body)
                        val features = root.optJSONArray("features")
                        if (features != null && features.length() > 0) {
                            val first = features.getJSONObject(0)
                            val props = first.optJSONObject("properties") ?: JSONObject()
                            val name = props.optString("name").ifEmpty {
                                props.optString("street").ifEmpty { "Selected Location" }
                            }
                            val addressParts = listOfNotNull(
                                props.optString("street").takeIf { it.isNotEmpty() && it != name },
                                props.optString("city").takeIf { it.isNotEmpty() },
                                props.optString("state").takeIf { it.isNotEmpty() },
                                props.optString("country").takeIf { it.isNotEmpty() }
                            )
                            val address = if (addressParts.isNotEmpty()) {
                                addressParts.joinToString(", ")
                            } else {
                                props.optString("country", "Tapped Map Location")
                            }
                            val type = props.optString("osm_value", "").lowercase()
                            return@withContext Destination(
                                id = "pin_${System.currentTimeMillis()}",
                                name = name,
                                address = address,
                                latitude = lat,
                                longitude = lon,
                                category = detectCategory(name, type)
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to Nominatim
        }

        // 2. Fallback: Try Nominatim Reverse Geocoding
        try {
            val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=18&addressdetails=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RouteWake-Android-App/1.0 (contact: support@routewake.app)")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val json = JSONObject(body)
                        val displayName = json.optString("display_name", "")
                        val addressObj = json.optJSONObject("address")
                        val name = addressObj?.optString("road")
                            ?: addressObj?.optString("suburb")
                            ?: displayName.split(",").firstOrNull()?.trim()
                            ?: "Selected Location"
                        return@withContext Destination(
                            id = "pin_${System.currentTimeMillis()}",
                            name = name,
                            address = displayName.ifEmpty { "Tapped Map Location" },
                            latitude = lat,
                            longitude = lon,
                            category = "Custom"
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to safe default
        }

        // 3. Fallback: Safe human-readable default without raw coordinates
        Destination(
            id = "pin_${System.currentTimeMillis()}",
            name = "Selected Location",
            address = "Tapped Map Location",
            latitude = lat,
            longitude = lon,
            category = "Custom"
        )
    }

    private fun detectCategory(name: String, osmType: String): String {
        val lower = "$name $osmType".lowercase()
        return when {
            lower.contains("metro") || lower.contains("subway") || lower.contains("underground") -> "Metro"
            lower.contains("train") || lower.contains("station") || lower.contains("rail") -> "Train"
            lower.contains("bus") || lower.contains("terminal") || lower.contains("coach") -> "Bus"
            lower.contains("airport") || lower.contains("terminal") || lower.contains("aerodrome") -> "Airport"
            lower.contains("home") || lower.contains("residence") -> "Home"
            lower.contains("office") || lower.contains("work") -> "Work"
            else -> "Custom"
        }
    }
}
