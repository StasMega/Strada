package com.stasmega.strada

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.PointF
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

// --- КЛАСС СОСТОЯНИЯ МАРКЕРА ---
class BusMarkerState(
    val line: String,
    val type: String,
    initialLat: Double,
    initialLon: Double,
    initialBearing: Float
) {
    var startLat = initialLat
    var startLon = initialLon
    var endLat = initialLat
    var endLon = initialLon
    var startBearing = initialBearing
    var endBearing = initialBearing

    var currentLat by mutableDoubleStateOf(initialLat)
    var currentLon by mutableDoubleStateOf(initialLon)
    var currentBearing by mutableFloatStateOf(initialBearing)

    fun update(newLat: Double, newLon: Double, newBearing: Float) {
        startLat = currentLat
        startLon = currentLon
        startBearing = currentBearing
        endLat = newLat
        endLon = newLon
        endBearing = newBearing
    }

    fun animate(fraction: Float) {
        currentLat = startLat + (endLat - startLat) * fraction
        currentLon = startLon + (endLon - startLon) * fraction
        currentBearing = interpolateRotation(startBearing, endBearing, fraction)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: BusViewModel) {
    val busesData by viewModel.buses.collectAsState()
    val routeShapes by viewModel.routeShapes.collectAsState()
    val selectedRouteOnMap by viewModel.selectedRouteOnMap
    val stopBoardDepartures by viewModel.stopBoardDepartures.collectAsState()
    val allRoutes by viewModel.routes.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    // Состояния поиска
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val filteredResults = remember(searchQuery, allRoutes) {
        if (searchQuery.isBlank()) emptyList()
        else allRoutes.filter {
            it.number.contains(searchQuery, ignoreCase = true) ||
                    it.name.contains(searchQuery, ignoreCase = true)
        }.take(5)
    }

    var showStopSheet by remember { mutableStateOf(false) }
    var selectedStopName by remember { mutableStateOf("") }
    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedBus by remember { mutableStateOf<BusMarkerState?>(null) }

    val boldFont = FontFamily(Font(R.font.google_sans_bold, FontWeight.Bold))

    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme by remember(viewModel.themeMode.intValue, isSystemDark) {
        derivedStateOf {
            when (viewModel.themeMode.intValue) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> isSystemDark
            }
        }
    }
    val styleUri = if (isDarkTheme) "asset://versatiles_strada_dark.json" else "asset://versatiles_strada_light.json"

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var transportSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    var isStyleReady by remember { mutableStateOf(false) }

    val busStates = remember { mutableStateMapOf<String, BusMarkerState>() }
    val loadedIcons = remember { mutableSetOf<String>() }

    var lastUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val serverUpdateInterval = 10000L
    val primaryColor = MaterialTheme.colorScheme.primary

    var cameraIdleJob by remember { mutableStateOf<Job?>(null) }

    val setupLayers: (Style, MapLibreMap) -> Unit = { style, map ->
        loadedIcons.clear()
        isStyleReady = false

        style.addSource(GeoJsonSource("route-line-src"))
        style.addLayer(LineLayer("route-line-layer", "route-line-src").apply {
            setProperties(
                PropertyFactory.lineColor(primaryColor.toArgb()),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.8f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
        })

        style.addImage("stop-icon", createStopIcon(context, primaryColor.toArgb()))
        style.addSource(GeoJsonSource("stops-src"))
        style.addLayer(SymbolLayer("stops-layer", "stops-src").apply {
            setProperties(
                PropertyFactory.iconImage("stop-icon"),
                PropertyFactory.iconSize(0.65f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
            minZoom = 15f
        })

        val src = GeoJsonSource("transport-src")
        style.addSource(src)
        transportSource = src
        style.addLayer(SymbolLayer("transport-layer", "transport-src").apply {
            setProperties(
                PropertyFactory.iconImage(Expression.get("icon_id")),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconSize(0.5f),
                PropertyFactory.iconRotationAlignment("map"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        })

        enableLocationComponent(style, context, map)
        isStyleReady = true
    }

    LaunchedEffect(styleUri) {
        mapLibreMap?.setStyle(Style.Builder().fromUri(styleUri)) { setupLayers(it, mapLibreMap!!) }
    }

    LaunchedEffect(busesData) {
        if (!isStyleReady) return@LaunchedEffect
        val freshIds = busesData.map { it.id }.toSet()
        busStates.keys.removeAll { it !in freshIds }

        mapLibreMap?.getStyle { style ->
            busesData.forEach { bus ->
                val iconKey = "${bus.type.lowercase()}-${bus.lineNumber}"
                if (!loadedIcons.contains(iconKey)) {
                    val resId = if (bus.type == "Tram") R.drawable.tram_icon else R.drawable.bus_icon
                    createBitmapWithText(context, resId, bus.lineNumber)?.let {
                        style.addImage(iconKey, it)
                        loadedIcons.add(iconKey)
                    }
                }
                val state = busStates[bus.id]
                if (state == null) {
                    busStates[bus.id] = BusMarkerState(bus.lineNumber, bus.type, bus.latitude, bus.longitude, bus.bearing)
                } else {
                    state.update(bus.latitude, bus.longitude, bus.bearing)
                }
            }
        }
        if (busesData.isNotEmpty()) lastUpdateTime = System.currentTimeMillis()
    }

    LaunchedEffect(isStyleReady) {
        if (!isStyleReady) return@LaunchedEffect
        while (isActive) {
            val fraction = ((System.currentTimeMillis() - lastUpdateTime).toFloat() / serverUpdateInterval).coerceIn(0f, 1f)
            if (transportSource != null) {
                try { transportSource?.setGeoJson(createGeoJsonFromStatesOptimized(busStates.entries.toList(), fraction)) } catch (_: Exception) {}
            }
            delay(32)
        }
    }

    LaunchedEffect(selectedRouteOnMap, routeShapes, isStyleReady) {
        if (!isStyleReady) return@LaunchedEffect
        mapLibreMap?.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>("route-line-src") ?: return@getStyle
            val route = selectedRouteOnMap?.let { routeShapes[it] }
            if (route != null) {
                source.setGeoJson(createRouteLineGeoJson(route.points))
                mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(calculateBounds(route.points), 100))
            } else source.setGeoJson(emptyFeatureCollection())
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapLibre.getInstance(ctx)
                MapView(ctx).apply {
                    getMapAsync { map ->
                        mapLibreMap = map
                        map.addOnMapClickListener { point ->
                            val pixel = map.projection.toScreenLocation(point)
                            val pointF = PointF(pixel.x.toFloat(), pixel.y.toFloat())
                            val busFeatures = map.queryRenderedFeatures(pointF, "transport-layer")
                            if (busFeatures.isNotEmpty()) {
                                selectedBus = busStates[busFeatures[0].getStringProperty("id")]
                                showBottomSheet = true
                                return@addOnMapClickListener true
                            }
                            val stopFeatures = map.queryRenderedFeatures(pointF, "stops-layer")
                            if (stopFeatures.isNotEmpty()) {
                                val name = stopFeatures[0].getStringProperty("name")
                                val id = stopFeatures[0].getStringProperty("id")
                                selectedStopName = name
                                viewModel.loadStopBoard(id)
                                showStopSheet = true
                                return@addOnMapClickListener true
                            }
                            false
                        }
                        map.addOnCameraIdleListener {
                            cameraIdleJob?.cancel()
                            cameraIdleJob = coroutineScope.launch {
                                delay(300)
                                val zoom = map.cameraPosition.zoom
                                if (zoom >= 15f && isStyleReady) {
                                    val visibleStops = viewModel.getStopsInBounds(map.projection.visibleRegion.latLngBounds)
                                    withContext(Dispatchers.Main) {
                                        map.getStyle { style ->
                                            style.getSourceAs<GeoJsonSource>("stops-src")?.setGeoJson(createStopsGeoJson(visibleStops))
                                        }
                                    }
                                }
                            }
                        }
                        map.setStyle(Style.Builder().fromUri(styleUri)) { setupLayers(it, map) }
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(59.437, 24.753), 12.5))
                    }
                }
            }
        )

        // ВЕРХНИЙ БЛОК: ПОИСК И ЧИПЫ
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .align(Alignment.TopCenter)
        ) {
            DockedSearchBar(
                modifier = Modifier.fillMaxWidth(),
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { isSearchActive = false },
                active = isSearchActive,
                onActiveChange = { isSearchActive = it },
                placeholder = { Text("Strada", color = Color.Gray) },
                leadingIcon = {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.padding(6.dp))
                    }
                },
                trailingIcon = { Icon(Icons.Default.Mic, null, tint = Color.Gray) },
                colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(28.dp)
            ) {
                filteredResults.forEach { route ->
                    ListItem(
                        headlineContent = { Text(route.name) },
                        supportingContent = { Text("№${route.number}") },
                        leadingContent = { Icon(Icons.Default.DirectionsBus, null) },
                        modifier = Modifier.clickable {
                            searchQuery = route.number
                            isSearchActive = false
                            viewModel.selectRouteOnMap(route.number)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransportChip(
                    label = "Buses",
                    icon = Icons.Default.DirectionsBus,
                    selected = viewModel.showBus.value,
                    onClick = { viewModel.toggleBus(!viewModel.showBus.value) }
                )
                TransportChip(
                    label = "Trams",
                    icon = Icons.Default.Tram,
                    selected = viewModel.showTram.value,
                    onClick = { viewModel.toggleTram(!viewModel.showTram.value) }
                )
                TransportChip(
                    label = "Trains",
                    icon = Icons.Default.Train,
                    selected = true,
                    onClick = { }
                )
            }
        }

        // Кнопка My Location
        FloatingActionButton(
            onClick = {
                mapLibreMap?.locationComponent?.lastKnownLocation?.let {
                    mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0))
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 16.dp).bounceClick(),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
            shape = CircleShape
        ) { Icon(Icons.Filled.MyLocation, null) }

        // BottomSheets
        if (showBottomSheet && selectedBus != null) {
            ModalBottomSheet(onDismissRequest = { showBottomSheet = false }, sheetState = sheetState) {
                Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${if (selectedBus?.type == "Tram") "Трамвай" else "Автобус"} №${selectedBus?.line}", style = MaterialTheme.typography.headlineMedium, fontFamily = boldFont)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { viewModel.selectRouteOnMap(selectedBus?.line); showBottomSheet = false }, Modifier.fillMaxWidth()) {
                        Text("Показать маршрут")
                    }
                }
            }
        }

        if (showStopSheet) {
            ModalBottomSheet(onDismissRequest = { showStopSheet = false }, modifier = Modifier.fillMaxHeight(0.6f)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(selectedStopName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    if (stopBoardDepartures.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                    } else {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(stopBoardDepartures) { dep -> DepartureItem(dep, getTransportColor(dep.type)) }
                            item { Spacer(Modifier.height(40.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// --- ХЕЛПЕРЫ ---

@Composable
fun TransportChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = FilterChipDefaults.filterChipElevation(elevation = 4.dp)
    )
}

private val geoJsonBuilder = StringBuilder(16384)

fun createGeoJsonFromStatesOptimized(entries: List<Map.Entry<String, BusMarkerState>>, fraction: Float): String {
    geoJsonBuilder.setLength(0)
    geoJsonBuilder.append("{\"type\":\"FeatureCollection\",\"features\":[")
    entries.forEachIndexed { index, (id, state) ->
        if (index > 0) geoJsonBuilder.append(',')
        state.animate(fraction)
        geoJsonBuilder.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        geoJsonBuilder.append(state.currentLon).append(',').append(state.currentLat)
        geoJsonBuilder.append("]},\"properties\":{\"id\":\"").append(id)
        geoJsonBuilder.append("\",\"bearing\":").append(state.currentBearing.toInt())
        geoJsonBuilder.append(",\"icon_id\":\"").append(state.type.lowercase()).append('-').append(state.line)
        geoJsonBuilder.append("\"}}")
    }
    geoJsonBuilder.append("]}")
    return geoJsonBuilder.toString()
}

fun createStopsGeoJson(stops: List<GtfsStop>): String {
    val builder = StringBuilder(stops.size * 128)
    builder.append("{\"type\":\"FeatureCollection\",\"features\":[")
    stops.forEachIndexed { index, stop ->
        if (index > 0) builder.append(',')
        builder.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        builder.append(stop.lon).append(',').append(stop.lat)
        builder.append("]},\"properties\":{\"name\":\"").append(stop.name.replace("\"", "\\\""))
        builder.append("\",\"id\":\"").append(stop.id).append("\"}}")
    }
    builder.append("]}")
    return builder.toString()
}

fun createRouteLineGeoJson(points: List<RoutePoint>): String {
    val builder = StringBuilder(points.size * 64)
    builder.append("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
    points.forEachIndexed { index, point ->
        if (index > 0) builder.append(',')
        builder.append('[').append(point.lon).append(',').append(point.lat).append(']')
    }
    builder.append("]}}]}")
    return builder.toString()
}

fun calculateBounds(points: List<RoutePoint>): LatLngBounds {
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(LatLng(it.lat, it.lon)) }
    return builder.build()
}

fun createStopIcon(context: Context, color: Int): Bitmap {
    val size = (20 * context.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = color
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size * 0.6f / 2f, paint)
    return bitmap
}

fun createBitmapWithText(context: Context, resId: Int, text: String): Bitmap? {
    val drawable = androidx.core.content.ContextCompat.getDrawable(context, resId) ?: return null
    val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = canvas.width * 0.45f
        typeface = ResourcesCompat.getFont(context, R.font.google_sans_bold)
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(text, canvas.width / 2f, canvas.height / 2f - (paint.descent() + paint.ascent()) / 2f + (canvas.height * 0.08f), paint)
    return bitmap
}

fun interpolateRotation(start: Float, end: Float, fraction: Float): Float {
    var diff = end - start
    while (diff < -180) diff += 360
    while (diff > 180) diff -= 360
    return start + diff * fraction
}

private fun enableLocationComponent(style: Style, context: Context, map: MapLibreMap) {
    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        val lc = map.locationComponent
        lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, style).build())
        lc.isLocationComponentEnabled = true
        lc.cameraMode = CameraMode.TRACKING
        lc.renderMode = RenderMode.COMPASS
    }
}

private fun emptyFeatureCollection() = "{\"type\":\"FeatureCollection\",\"features\":[]}"