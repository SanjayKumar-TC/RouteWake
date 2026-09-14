package com.example.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Build
import android.webkit.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.Destination
import com.example.data.model.RoutePoint

class MapBridge(
    private val onMapClicked: (Double, Double) -> Unit
) {
    @JavascriptInterface
    fun onMapClick(lat: Double, lng: Double) {
        onMapClicked(lat, lng)
    }
}

object LeafletAssetCache {
    var js: String? = null
    var css: String? = null

    fun load(context: Context) {
        if (js != null && css != null) return
        try {
            if (css == null) {
                css = context.assets.open("leaflet/leaflet.css").bufferedReader().use { it.readText() }
            }
            if (js == null) {
                js = context.assets.open("leaflet/leaflet.js").bufferedReader().use { it.readText() }
            }
        } catch (_: Throwable) {
            css = ""
            js = ""
        }
    }
}

/**
 * Interactive Leaflet map utilizing CartoDB Dark Matter tiles.
 * 100% Free & Open - Requires ZERO API keys, ZERO secrets, and ZERO configuration.
 *
 * Fully interactive with pan, pinch-to-zoom, dynamic GPS radar beacon,
 * amber destination marker, arrival geofence radius, and teal route polyline.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DarkMapView(
    currentLat: Double?,
    currentLng: Double?,
    destination: Destination?,
    alarmRadiusMeters: Int,
    showAlarmRadius: Boolean,
    routePoints: List<RoutePoint>,
    isSatellite: Boolean,
    modifier: Modifier = Modifier,
    recenterTrigger: Int = 0,
    zoomInTrigger: Int = 0,
    zoomOutTrigger: Int = 0,
    onMapClick: (Double, Double) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        LeafletAssetCache.load(context)
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isMapLoaded by remember { mutableStateOf(false) }

    // Recenter map on user location when requested
    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger > 0 && isMapLoaded && currentLat != null && currentLng != null) {
            webViewRef?.evaluateJavascript(
                "if (window.recenter) { window.recenter($currentLat, $currentLng); }",
                null
            )
        }
    }

    // Zoom in when requested
    LaunchedEffect(zoomInTrigger) {
        if (zoomInTrigger > 0 && isMapLoaded) {
            webViewRef?.evaluateJavascript("if (window.map) { window.map.zoomIn(); }", null)
        }
    }

    // Zoom out when requested
    LaunchedEffect(zoomOutTrigger) {
        if (zoomOutTrigger > 0 && isMapLoaded) {
            webViewRef?.evaluateJavascript("if (window.map) { window.map.zoomOut(); }", null)
        }
    }

    // Update current location marker
    LaunchedEffect(currentLat, currentLng, isMapLoaded) {
        if (isMapLoaded) {
            if (currentLat != null && currentLng != null) {
                webViewRef?.evaluateJavascript(
                    "if (window.setUserLocation) { window.setUserLocation($currentLat, $currentLng); }",
                    null
                )
            } else {
                webViewRef?.evaluateJavascript(
                    "if (window.clearUserLocation) { window.clearUserLocation(); }",
                    null
                )
            }
        }
    }

    // Update destination and alarm radius
    LaunchedEffect(destination, alarmRadiusMeters, showAlarmRadius, isMapLoaded) {
        if (isMapLoaded) {
            if (destination != null) {
                val safeName = destination.name.replace("'", "\\'").replace("\"", "\\\"")
                webViewRef?.evaluateJavascript(
                    "if (window.setDestination) { window.setDestination(${destination.latitude}, ${destination.longitude}, '$alarmRadiusMeters', $showAlarmRadius, '$safeName'); }",
                    null
                )
            } else {
                webViewRef?.evaluateJavascript(
                    "if (window.clearDestination) { window.clearDestination(); }",
                    null
                )
            }
        }
    }

    // Update route polyline
    LaunchedEffect(routePoints, isMapLoaded) {
        if (isMapLoaded) {
            if (routePoints.isNotEmpty()) {
                val jsonPoints = routePoints.joinToString(prefix = "[", postfix = "]") {
                    "[${it.latitude}, ${it.longitude}]"
                }
                webViewRef?.evaluateJavascript(
                    "if (window.setRoute) { window.setRoute($jsonPoints); }",
                    null
                )
            } else {
                webViewRef?.evaluateJavascript(
                    "if (window.clearRoute) { window.clearRoute(); }",
                    null
                )
            }
        }
    }

    // Update map style (Dark Matter vs Satellite/Street)
    LaunchedEffect(isSatellite, isMapLoaded) {
        if (isMapLoaded) {
            webViewRef?.evaluateJavascript(
                "if (window.setMapStyle) { window.setMapStyle($isSatellite); }",
                null
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                LeafletAssetCache.load(ctx)
                try {
                    val baseCodeCache = java.io.File(ctx.cacheDir, "WebView/Default/HTTP Cache/Code Cache")
                    java.io.File(baseCodeCache, "js").mkdirs()
                    java.io.File(baseCodeCache, "wasm").mkdirs()
                } catch (_: Throwable) {}

                WebView(ctx).apply {
                    webViewRef = this
                    setBackgroundColor(0xFF090D16.toInt())

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        allowContentAccess = true
                        allowFileAccess = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                    }

                    addJavascriptInterface(MapBridge(onMapClick), "AndroidBridge")

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            android.util.Log.d("RouteWakeMap", "${consoleMessage?.message()} (line ${consoleMessage?.lineNumber()})")
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            isMapLoaded = false
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?
                        ) {
                            handler?.proceed()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isMapLoaded = true

                            // Re-apply state on page finish
                            if (currentLat != null && currentLng != null) {
                                view?.evaluateJavascript(
                                    "if (window.setUserLocation) { window.setUserLocation($currentLat, $currentLng); }",
                                    null
                                )
                            }
                            if (destination != null) {
                                val safeName = destination.name.replace("'", "\\'").replace("\"", "\\\"")
                                view?.evaluateJavascript(
                                    "if (window.setDestination) { window.setDestination(${destination.latitude}, ${destination.longitude}, '$alarmRadiusMeters', $showAlarmRadius, '$safeName'); }",
                                    null
                                )
                            }
                            if (routePoints.isNotEmpty()) {
                                val jsonPoints = routePoints.joinToString(prefix = "[", postfix = "]") {
                                    "[${it.latitude}, ${it.longitude}]"
                                }
                                view?.evaluateJavascript(
                                    "if (window.setRoute) { window.setRoute($jsonPoints); }",
                                    null
                                )
                            }
                        }
                    }

                    val initialCenterLat = currentLat ?: destination?.latitude ?: 37.7749
                    val initialCenterLng = currentLng ?: destination?.longitude ?: -122.4194

                    loadDataWithBaseURL(
                        "https://basemaps.cartocdn.com",
                        generateLeafletHtml(
                            initLat = initialCenterLat,
                            initLng = initialCenterLng,
                            leafletCss = LeafletAssetCache.css ?: "",
                            leafletJs = LeafletAssetCache.js ?: ""
                        ),
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            update = {
                // View state is maintained reactively through LaunchedEffects
            },
            onRelease = { webView ->
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Throwable) {}
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun generateLeafletHtml(
    initLat: Double,
    initLng: Double,
    leafletCss: String,
    leafletJs: String
): String {
    val cssBlock = if (leafletCss.isNotBlank()) {
        "<style>$leafletCss</style>"
    } else {
        """
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css" />
        """.trimIndent()
    }

    val jsBlock = if (leafletJs.isNotBlank()) {
        "<script>$leafletJs</script>"
    } else {
        """
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        """.trimIndent()
    }

    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
    $cssBlock
    $jsBlock
    <style>
        * {
            box-sizing: border-box;
            -webkit-touch-callout: none;
            -webkit-user-select: none;
            user-select: none;
            margin: 0;
            padding: 0;
        }
        html, body {
            width: 100%;
            height: 100%;
            overflow: hidden;
            background-color: #090D16;
        }
        #map {
            width: 100%;
            height: 100%;
            position: absolute;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background-color: #090D16;
        }
        .leaflet-container {
            background-color: #090D16 !important;
        }

        /* Current Location Radar Halo Marker */
        .user-marker-container {
            position: relative;
            width: 36px;
            height: 36px;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .user-radar-ring {
            position: absolute;
            width: 36px;
            height: 36px;
            border-radius: 50%;
            background: rgba(56, 189, 248, 0.35);
            border: 1.5px solid rgba(56, 189, 248, 0.8);
            animation: radarPulse 2s ease-out infinite;
        }
        .user-core-dot {
            position: relative;
            width: 18px;
            height: 18px;
            border-radius: 50%;
            background: #38BDF8;
            border: 3px solid #0F172A;
            box-shadow: 0 0 10px rgba(56, 189, 248, 0.9);
        }
        @keyframes radarPulse {
            0% { transform: scale(0.5); opacity: 1; }
            100% { transform: scale(2.2); opacity: 0; }
        }

        /* Destination Amber Pin */
        .dest-marker-container {
            position: relative;
            width: 38px;
            height: 46px;
        }
        .dest-pin {
            width: 38px;
            height: 46px;
            filter: drop-shadow(0 4px 8px rgba(0, 0, 0, 0.6));
        }
    </style>
</head>
<body>
    <div id="map"></div>
    <script>
        var initialLat = $initLat;
        var initialLng = $initLng;

        var map = L.map('map', {
            zoomControl: false,
            attributionControl: false,
            preferCanvas: false
        }).setView([initialLat, initialLng], 14);

        // CartoDB Dark Matter basemap (Default)
        var darkTileLayer = L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/dark_all/{z}/{x}/{y}{r}.png', {
            maxZoom: 19,
            subdomains: 'abcd',
            attribution: '&copy; OpenStreetMap &copy; CARTO'
        }).addTo(map);

        // OpenStreetMap for alternate / satellite mode
        var satelliteTileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; OpenStreetMap contributors'
        });

        // Ensure Leaflet resizes properly and loads all tiles
        function invalidateMap() {
            if (map) {
                map.invalidateSize();
            }
        }
        window.addEventListener('resize', invalidateMap);
        setTimeout(invalidateMap, 50);
        setTimeout(invalidateMap, 200);
        setTimeout(invalidateMap, 600);
        setTimeout(invalidateMap, 1200);

        if (window.ResizeObserver) {
            var ro = new ResizeObserver(function() {
                invalidateMap();
            });
            ro.observe(document.getElementById('map'));
        }

        var userMarker = null;
        var destMarker = null;
        var destRadiusCircle = null;
        var routePolyline = null;
        var hasUserCentered = false;

        var userIcon = L.divIcon({
            className: 'custom-user-icon',
            html: '<div class="user-marker-container"><div class="user-radar-ring"></div><div class="user-core-dot"></div></div>',
            iconSize: [36, 36],
            iconAnchor: [18, 18]
        });

        var destSvg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 38 46" width="38" height="46"><path d="M19 2C9.6 2 2 9.6 2 19C2 31 19 44 19 44C19 44 36 31 36 19C36 9.6 28.4 2 19 2Z" fill="#F59E0B" stroke="#0F172A" stroke-width="2.5"/><circle cx="19" cy="19" r="6" fill="#0B111E"/><circle cx="19" cy="19" r="3" fill="#FDE68A"/></svg>';
        var destIcon = L.divIcon({
            className: 'custom-dest-icon',
            html: '<div class="dest-marker-container"><div class="dest-pin">' + destSvg + '</div></div>',
            iconSize: [38, 46],
            iconAnchor: [19, 44]
        });

        window.setUserLocation = function(lat, lng) {
            if (!userMarker) {
                userMarker = L.marker([lat, lng], { icon: userIcon, zIndexOffset: 1000 }).addTo(map);
                if (!hasUserCentered) {
                    map.setView([lat, lng], 15);
                    hasUserCentered = true;
                }
            } else {
                userMarker.setLatLng([lat, lng]);
            }
            invalidateMap();
        };

        window.clearUserLocation = function() {
            if (userMarker) {
                map.removeLayer(userMarker);
                userMarker = null;
            }
        };

        window.setDestination = function(lat, lng, radiusMeters, showRadius, name) {
            var radius = parseFloat(radiusMeters) || 500;
            if (!destMarker) {
                destMarker = L.marker([lat, lng], { icon: destIcon, zIndexOffset: 900 }).addTo(map);
            } else {
                destMarker.setLatLng([lat, lng]);
            }

            if (showRadius) {
                if (!destRadiusCircle) {
                    destRadiusCircle = L.circle([lat, lng], {
                        radius: radius,
                        color: '#F59E0B',
                        dashArray: '8, 8',
                        weight: 2.5,
                        opacity: 0.8,
                        fillColor: '#F59E0B',
                        fillOpacity: 0.15
                    }).addTo(map);
                } else {
                    destRadiusCircle.setLatLng([lat, lng]);
                    destRadiusCircle.setRadius(radius);
                }
            } else if (destRadiusCircle) {
                map.removeLayer(destRadiusCircle);
                destRadiusCircle = null;
            }
            invalidateMap();
        };

        window.clearDestination = function() {
            if (destMarker) { map.removeLayer(destMarker); destMarker = null; }
            if (destRadiusCircle) { map.removeLayer(destRadiusCircle); destRadiusCircle = null; }
            if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
        };

        window.setRoute = function(latLngArray) {
            if (routePolyline) { map.removeLayer(routePolyline); }
            if (latLngArray && latLngArray.length > 0) {
                routePolyline = L.polyline(latLngArray, {
                    color: '#14B8A6',
                    weight: 5,
                    opacity: 0.9,
                    lineJoin: 'round',
                    lineCap: 'round'
                }).addTo(map);

                map.fitBounds(routePolyline.getBounds(), { padding: [60, 60], maxZoom: 15 });
            }
            invalidateMap();
        };

        window.clearRoute = function() {
            if (routePolyline) { map.removeLayer(routePolyline); routePolyline = null; }
        };

        window.setMapStyle = function(isSatellite) {
            if (isSatellite) {
                if (map.hasLayer(darkTileLayer)) map.removeLayer(darkTileLayer);
                if (!map.hasLayer(satelliteTileLayer)) satelliteTileLayer.addTo(map);
            } else {
                if (map.hasLayer(satelliteTileLayer)) map.removeLayer(satelliteTileLayer);
                if (!map.hasLayer(darkTileLayer)) darkTileLayer.addTo(map);
            }
        };

        window.recenter = function(lat, lng) {
            map.flyTo([lat, lng], 15, { duration: 0.8 });
        };

        map.on('click', function(e) {
            if (window.AndroidBridge && window.AndroidBridge.onMapClick) {
                window.AndroidBridge.onMapClick(e.latlng.lat, e.latlng.lng);
            }
        });
    </script>
</body>
</html>
    """.trimIndent()
}
