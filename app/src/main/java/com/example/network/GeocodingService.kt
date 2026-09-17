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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    data class LocationContext(
        val latitude: Double,
        val longitude: Double,
        val locality: String?,
        val city: String?,
        val state: String?,
        val country: String?
    )

    data class PhotonResult(
        val destination: Destination,
        val osmKey: String,
        val osmValue: String,
        val type: String,
        val city: String?,
        val state: String?,
        val country: String?
    ) {
        val isCityOrRegion: Boolean
            get() = (osmKey == "place" && (osmValue == "city" || osmValue == "town" || osmValue == "province" || osmValue == "country")) ||
                    type == "city" || type == "district" || osmKey == "boundary"
    }

    @Volatile
    private var cachedLocationContext: LocationContext? = null

    private val genericLocalKeywords = setOf(
        "hospital", "hospitals", "clinic", "clinics", "dispensary", "pharmacy", "pharmacies",
        "chemist", "medical", "doctor", "doctors", "dentist", "dentists", "blood bank",
        "restaurant", "restaurants", "cafe", "cafes", "coffee", "coffee shop", "bakery", "bakeries",
        "bar", "bars", "pub", "pubs", "dhaba", "food", "eatery", "bistro", "diner", "fast food",
        "pizza", "burger", "ice cream",
        "mall", "malls", "shopping mall", "supermarket", "supermarkets", "grocery", "groceries",
        "store", "stores", "mart", "market", "bazaar",
        "petrol pump", "petrol bunk", "petrol", "fuel station", "fuel", "gas station", "gas",
        "ev charging", "charging station", "cng station", "garage", "puncture", "mechanic",
        "atm", "atms", "bank", "banks",
        "hotel", "hotels", "lodge", "lodging", "resort", "resorts", "hostel", "hostels", "pg", "guest house",
        "gym", "gyms", "fitness", "yoga", "swimming pool", "stadium", "sports complex",
        "college", "colleges", "university", "universities", "school", "schools", "library", "libraries",
        "bus stand", "bus stop", "metro station", "metro", "subway", "railway station", "train station",
        "airport", "airports", "police station", "post office", "fire station", "park", "parks",
        "cinema", "movie", "theatre", "theater", "temple", "church", "mosque"
    )

    companion object {
        fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return r * c
        }
    }

    suspend fun warmupLocationContext(lat: Double, lon: Double) {
        resolveLocationContext(lat, lon)
    }

    private suspend fun resolveLocationContext(lat: Double, lon: Double): LocationContext? {
        val cached = cachedLocationContext
        if (cached != null) {
            val dist = calculateDistanceMeters(lat, lon, cached.latitude, cached.longitude)
            if (dist < 15_000.0) {
                return cached
            }
        }

        return try {
            val url = "https://photon.komoot.io/reverse?lat=$lat&lon=$lon&lang=en"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RouteWake-Android-App/2.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val root = JSONObject(body)
                    val features = root.optJSONArray("features")
                    if (features != null && features.length() > 0) {
                        val props = features.getJSONObject(0).optJSONObject("properties") ?: JSONObject()
                        val city = props.optString("city").ifEmpty { props.optString("county") }.ifEmpty { null }
                        val locality = props.optString("locality").ifEmpty { props.optString("district") }.ifEmpty { null }
                        val state = props.optString("state").ifEmpty { null }
                        val country = props.optString("country").ifEmpty { null }
                        val ctx = LocationContext(lat, lon, locality, city, state, country)
                        cachedLocationContext = ctx
                        ctx
                    } else null
                } else null
            }
        } catch (_: Exception) {
            cached
        }
    }

    fun isGenericLocalQuery(query: String): Boolean {
        val lower = query.lowercase().trim()
        if (lower in genericLocalKeywords) return true
        if (lower.endsWith(" near me") || lower.endsWith(" nearby")) return true

        val words = lower.split(Regex("\\s+"))
        if (words.size <= 2) {
            for (kw in genericLocalKeywords) {
                if (lower == kw || lower.startsWith("$kw ") || lower.endsWith(" $kw")) {
                    return true
                }
            }
        }
        return false
    }

    suspend fun searchDestinations(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<Destination> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val isGeneric = isGenericLocalQuery(trimmed)
        val hasGps = userLat != null && userLon != null &&
                !userLat.isNaN() && !userLon.isNaN() &&
                (userLat != 0.0 || userLon != 0.0)

        try {
            if (hasGps) {
                val locContext = resolveLocationContext(userLat!!, userLon!!)
                val currentCity = locContext?.city ?: locContext?.locality

                if (isGeneric) {
                    // GENERIC LOCAL SEARCH (e.g. "hospital", "restaurant", "cafe", "mall", "petrol pump", "atm")
                    // Current location has strong influence. Nearby relevant places appear first.
                    val candidateResults = mutableListOf<Destination>()

                    // 1. If locality/city is known, search with city context for prominent city POIs
                    if (!currentCity.isNullOrBlank()) {
                        val cityQuery = "$trimmed $currentCity"
                        val cityResults = queryPhoton(cityQuery, userLat, userLon, biasScale = 0.1, limit = 20)
                        candidateResults.addAll(cityResults.map { it.destination })
                    }

                    // 2. Query Photon with user coordinates and strong location bias (scale 0.1)
                    val biasedResults = queryPhoton(trimmed, userLat, userLon, biasScale = 0.1, limit = 20)
                    candidateResults.addAll(biasedResults.map { it.destination })

                    // Deduplicate
                    val distinctCandidates = candidateResults.distinctBy {
                        "${it.name.lowercase().trim()}_${Math.round(it.latitude * 1000)}_${Math.round(it.longitude * 1000)}"
                    }

                    if (distinctCandidates.isNotEmpty()) {
                        // Sort by proximity to user's current GPS position
                        val sorted = distinctCandidates.sortedBy { dest ->
                            calculateDistanceMeters(userLat, userLon, dest.latitude, dest.longitude)
                        }

                        // Prioritize locations within the urban/regional area (e.g. <= 60 km)
                        val localArea = sorted.filter { dest ->
                            calculateDistanceMeters(userLat, userLon, dest.latitude, dest.longitude) <= 60_000.0
                        }

                        if (localArea.isNotEmpty()) {
                            return@withContext localArea
                        }
                        return@withContext sorted
                    }
                } else {
                    // SPECIFIC LOCATION SEARCH (e.g. "Bengaluru", "Mysuru", "Chennai", "Tokyo", "London", "Paris", "MG Road")
                    // Respect the explicit location/name in the query.
                    // 1. Query with user GPS location bias (helps local road/neighborhood queries like "MG Road" in current city)
                    val biasedResults = queryPhoton(trimmed, userLat, userLon, biasScale = null, limit = 15)

                    // 2. Check global results to respect explicit world cities, capitals, or countries
                    val globalResults = queryPhoton(trimmed, null, null, null, limit = 5)
                    val prominentCityMatch = globalResults.firstOrNull {
                        it.isCityOrRegion && (it.destination.name.equals(trimmed, ignoreCase = true) ||
                                it.city?.equals(trimmed, ignoreCase = true) == true)
                    }

                    if (prominentCityMatch != null) {
                        // Explicit city search: place the matching city destination at the top!
                        val combined = mutableListOf<Destination>()
                        combined.add(prominentCityMatch.destination)
                        biasedResults.forEach { item ->
                            if (item.destination.id != prominentCityMatch.destination.id &&
                                !combined.any { it.name.equals(item.destination.name, ignoreCase = true) &&
                                        Math.abs(it.latitude - item.destination.latitude) < 0.05 }
                            ) {
                                combined.add(item.destination)
                            }
                        }
                        return@withContext combined
                    }

                    if (biasedResults.isNotEmpty()) {
                        return@withContext biasedResults.map { it.destination }
                    }
                }
            } else {
                // GPS UNAVAILABLE:
                // Search functions with standard geocoding without crashing
                val photonResults = queryPhoton(trimmed, null, null, null, limit = 15)
                if (photonResults.isNotEmpty()) {
                    return@withContext photonResults.map { it.destination }
                }
            }
        } catch (_: Exception) {
            // Fallback to Nominatim
        }

        try {
            // Nominatim fallback
            val nominatimResults = queryNominatim(
                trimmed,
                userLat = if (isGeneric) userLat else null,
                userLon = if (isGeneric) userLon else null
            )
            if (nominatimResults.isNotEmpty()) {
                if (hasGps && isGeneric) {
                    return@withContext nominatimResults.sortedBy { dest ->
                        calculateDistanceMeters(userLat!!, userLon!!, dest.latitude, dest.longitude)
                    }
                }
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

        return emptyList()
    }

    private val fallbackDestinations = listOf(
        // North America
        Destination(name = "San Francisco International Airport (SFO)", address = "San Francisco, CA, United States", latitude = 37.6213, longitude = -122.3790, category = "Airport"),
        Destination(name = "Los Angeles International Airport (LAX)", address = "1 World Way, Los Angeles, CA, United States", latitude = 33.9416, longitude = -118.4085, category = "Airport"),
        Destination(name = "John F. Kennedy International Airport (JFK)", address = "Queens, NY, United States", latitude = 40.6413, longitude = -73.7781, category = "Airport"),
        Destination(name = "Chicago O'Hare International Airport (ORD)", address = "10000 W Balmoral Ave, Chicago, IL, United States", latitude = 41.9742, longitude = -87.9073, category = "Airport"),
        Destination(name = "Grand Central Terminal", address = "89 E 42nd St, New York, NY, United States", latitude = 40.7527, longitude = -73.9772, category = "Train"),
        Destination(name = "Penn Station", address = "Pennsylvania Station, New York, NY, United States", latitude = 40.7505, longitude = -73.9934, category = "Train"),
        Destination(name = "Powell Street BART & Metro Station", address = "Market St & Powell St, San Francisco, CA", latitude = 37.7844, longitude = -122.4080, category = "Metro"),
        Destination(name = "Embarcadero BART & Metro Station", address = "Market St & Main St, San Francisco, CA", latitude = 37.7929, longitude = -122.3970, category = "Metro"),
        Destination(name = "San Francisco Caltrain Station", address = "700 4th St, San Francisco, CA", latitude = 37.7766, longitude = -122.3950, category = "Train"),
        // Europe & Global
        Destination(name = "London Heathrow Airport (LHR)", address = "Hounslow, Greater London, United Kingdom", latitude = 51.4700, longitude = -0.4543, category = "Airport"),
        Destination(name = "Paris Charles de Gaulle Airport (CDG)", address = "Roissy-en-France, Île-de-France, France", latitude = 49.0097, longitude = 2.5479, category = "Airport"),
        Destination(name = "Frankfurt Airport (FRA)", address = "Frankfurt am Main, Hesse, Germany", latitude = 50.0379, longitude = 8.5622, category = "Airport"),
        Destination(name = "Tokyo Haneda Airport (HND)", address = "Ota, Tokyo, Japan", latitude = 35.5494, longitude = 139.7798, category = "Airport"),
        Destination(name = "Indira Gandhi International Airport (DEL)", address = "New Delhi, Delhi, India", latitude = 28.5562, longitude = 77.1000, category = "Airport"),
        Destination(name = "Kempegowda International Airport (BLR)", address = "Devanahalli, Bengaluru, Karnataka, India", latitude = 13.1986, longitude = 77.7066, category = "Airport"),
        Destination(name = "Singapore Changi Airport (SIN)", address = "Changi, Singapore", latitude = 1.3644, longitude = 103.9915, category = "Airport"),
        Destination(name = "Dubai International Airport (DXB)", address = "Dubai, United Arab Emirates", latitude = 25.2532, longitude = 55.3657, category = "Airport")
    )

    private fun queryPhoton(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null,
        biasScale: Double? = null,
        limit: Int = 15
    ): List<PhotonResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = StringBuilder("https://photon.komoot.io/api/?q=$encoded&lang=en&limit=$limit")
        if (userLat != null && userLon != null) {
            url.append("&lat=$userLat&lon=$userLon")
            if (biasScale != null) {
                url.append("&location_bias_scale=$biasScale")
            }
        }
        val request = Request.Builder()
            .url(url.toString())
            .header("User-Agent", "RouteWake-Android-App/2.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val features = root.optJSONArray("features") ?: return emptyList()

            val results = mutableListOf<PhotonResult>()
            for (i in 0 until features.length()) {
                val item = features.getJSONObject(i)
                val geometry = item.optJSONObject("geometry") ?: continue
                val coords = geometry.optJSONArray("coordinates") ?: continue
                val lon = coords.optDouble(0)
                val lat = coords.optDouble(1)
                if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue

                val props = item.optJSONObject("properties") ?: JSONObject()
                val houseNum = props.optString("housenumber").trim()
                val street = props.optString("street").trim()
                val rawName = props.optString("name").trim()

                val name = when {
                    rawName.isNotEmpty() -> rawName
                    street.isNotEmpty() -> if (houseNum.isNotEmpty()) "$houseNum $street" else street
                    else -> props.optString("city", query)
                }

                val addressParts = mutableListOf<String>()
                val streetDisplay = if (houseNum.isNotEmpty() && street.isNotEmpty()) "$houseNum $street" else street
                if (streetDisplay.isNotEmpty() && !name.contains(streetDisplay, ignoreCase = true)) {
                    addressParts.add(streetDisplay)
                }
                val locality = props.optString("locality").trim()
                if (locality.isNotEmpty() && !name.contains(locality, ignoreCase = true) && !addressParts.contains(locality)) {
                    addressParts.add(locality)
                }
                val district = props.optString("district").trim()
                if (district.isNotEmpty() && district != locality && !name.contains(district, ignoreCase = true) && !addressParts.contains(district)) {
                    if (addressParts.size < 2) {
                        addressParts.add(district)
                    }
                }
                val city = props.optString("city").trim()
                if (city.isNotEmpty() && !name.contains(city, ignoreCase = true) && !addressParts.contains(city)) {
                    addressParts.add(city)
                }
                val state = props.optString("state").trim()
                if (state.isNotEmpty() && !name.contains(state, ignoreCase = true) && !addressParts.contains(state)) {
                    addressParts.add(state)
                }
                val country = props.optString("country").trim()
                if (country.isNotEmpty() && !name.contains(country, ignoreCase = true) && !addressParts.contains(country)) {
                    addressParts.add(country)
                }

                val address = if (addressParts.isNotEmpty()) {
                    addressParts.joinToString(", ")
                } else {
                    props.optString("country", "Known Location")
                }

                val osmKey = props.optString("osm_key", "").lowercase()
                val osmValue = props.optString("osm_value", "").lowercase()
                val type = props.optString("type", "").lowercase()
                val category = detectCategory(name, osmValue.ifEmpty { type })

                results.add(
                    PhotonResult(
                        destination = Destination(
                            name = name,
                            address = address,
                            latitude = lat,
                            longitude = lon,
                            category = category
                        ),
                        osmKey = osmKey,
                        osmValue = osmValue,
                        type = type,
                        city = city.ifEmpty { null },
                        state = state.ifEmpty { null },
                        country = country.ifEmpty { null }
                    )
                )
            }
            return results.distinctBy { "${it.destination.name}_${Math.round(it.destination.latitude * 1000)}_${Math.round(it.destination.longitude * 1000)}" }
        }
    }

    private fun queryNominatim(query: String, userLat: Double? = null, userLon: Double? = null): List<Destination> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = StringBuilder("https://nominatim.openstreetmap.org/search?format=json&q=$encoded&limit=15&addressdetails=1")
        if (userLat != null && userLon != null) {
            val minLat = userLat - 1.5
            val maxLat = userLat + 1.5
            val minLon = userLon - 1.5
            val maxLon = userLon + 1.5
            url.append("&viewbox=$minLon,$maxLat,$maxLon,$minLat")
        }
        val request = Request.Builder()
            .url(url.toString())
            .header("User-Agent", "RouteWake-Android-App/2.0 (contact: support@routewake.app)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val array = org.json.JSONArray(body)

            val results = mutableListOf<Destination>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val lat = item.optString("lat").toDoubleOrNull() ?: item.optDouble("lat")
                val lon = item.optString("lon").toDoubleOrNull() ?: item.optDouble("lon")
                if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue

                val displayName = item.optString("display_name", "")
                val addressObj = item.optJSONObject("address")

                val rawName = item.optString("name").ifEmpty {
                    addressObj?.optString("road")
                        ?: addressObj?.optString("suburb")
                        ?: displayName.split(",").firstOrNull()?.trim()
                        ?: query
                }

                val addressParts = mutableListOf<String>()
                addressObj?.optString("road")?.takeIf { it.isNotEmpty() && it != rawName }?.let { addressParts.add(it) }
                addressObj?.optString("suburb")?.takeIf { it.isNotEmpty() && it != rawName }?.let { addressParts.add(it) }
                addressObj?.optString("city")?.takeIf { it.isNotEmpty() && it != rawName }?.let { addressParts.add(it) }
                addressObj?.optString("state")?.takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }
                addressObj?.optString("country")?.takeIf { it.isNotEmpty() }?.let { addressParts.add(it) }

                val address = if (addressParts.isNotEmpty()) {
                    addressParts.joinToString(", ")
                } else {
                    displayName.split(",").drop(1).take(3).joinToString(",").trim().ifEmpty { displayName }
                }

                val type = item.optString("type", "").lowercase()

                results.add(
                    Destination(
                        name = rawName,
                        address = address,
                        latitude = lat,
                        longitude = lon,
                        category = detectCategory(rawName, type)
                    )
                )
            }
            return results.distinctBy { "${it.name}_${Math.round(it.latitude * 1000)}_${Math.round(it.longitude * 1000)}" }
        }
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): Destination = withContext(Dispatchers.IO) {
        // 1. Primary: Try Photon Reverse Geocoding
        try {
            val url = "https://photon.komoot.io/reverse?lat=$lat&lon=$lon"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RouteWake-Android-App/2.0")
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
