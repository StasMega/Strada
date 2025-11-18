package com.stasmega.strada

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import java.io.IOException
import android.view.View
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private val client = OkHttpClient()
    private val UPDATE_INTERVAL_MS = 10000L
    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private val MAX_MATCHING_DISTANCE_METERS = 500.0
    private val busMarkersByRoute = mutableMapOf<String, MutableList<MarkerState>>()
    private lateinit var onestTypeface: Typeface
    private var myLocationMarker: Marker? = null
    private lateinit var locationProvider: GpsMyLocationProvider

    data class MarkerState(
        val marker: Marker,
        var animator: ValueAnimator? = null,
        var lastBearing: Float
    )
    data class BusInfo(
        val type: String,
        val lineNumber: String,
        val location: GeoPoint,
        val bearing: Float
    )

    inner class VehicleMarker(
        mapView: MapView,
        private val text: String,
        drawableRes: Int
    ) : Marker(mapView) {

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpToPx(this@MainActivity, 8f)
            typeface = onestTypeface
            textAlign = Paint.Align.CENTER
        }

        private val iconDrawable: Drawable = ContextCompat.getDrawable(this@MainActivity, drawableRes)!!
        private val iconSize = dpToPx(this@MainActivity, 34f).toInt()

        init {
            setAnchor(ANCHOR_CENTER, ANCHOR_CENTER)
        }

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            super.draw(canvas, mapView, shadow)
            if (shadow) return

            val positionPixels = this.mPositionPixels
            val x = positionPixels.x
            val y = positionPixels.y

            canvas.save()

            canvas.rotate(this.rotation, x.toFloat(), y.toFloat())

            iconDrawable.setBounds(x - iconSize / 4, y - iconSize / 3, x + iconSize / 4, y + iconSize / 3)
            iconDrawable.draw(canvas)

            val textY = y - ((textPaint.descent() + textPaint.ascent()) / 1f)
            canvas.drawText(text, x.toFloat(), textY, textPaint)

            canvas.restore()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        onestTypeface = ResourcesCompat.getFont(this, R.font.onest_regular)!!
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)

        val fab = findViewById<FloatingActionButton>(R.id.fab_my_location)
        fab.setOnClickListener {
            myLocationMarker?.position?.let { map.controller.animateTo(it) }
        }

        val menuButton = findViewById<ImageButton>(R.id.btn_menu)
        menuButton.setOnClickListener {
            Toast.makeText(this, "Not yet done:)", Toast.LENGTH_SHORT).show()
        }

        setupMap()
        requestLocationPermissions()
        startBusTracking()
    }

    private fun createNewMarker(busInfo: BusInfo): MarkerState {
        val isTram = busInfo.type == "Tram"

        val iconRes = if (isTram) R.drawable.tram_icon else R.drawable.bus_icon

        val newMarker = VehicleMarker(map, busInfo.lineNumber, iconRes).apply {
            position = busInfo.location
            rotation = busInfo.bearing
            title = "${busInfo.type} №${busInfo.lineNumber}"
            subDescription = busInfo.lineNumber

            icon = BitmapDrawable(resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }

        map.overlays.add(newMarker)
        return MarkerState(newMarker, null, busInfo.bearing)
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val mapController = map.controller
        mapController.setZoom(13.5)
        val startPoint = GeoPoint(59.4370, 24.7536)
        mapController.setCenter(startPoint)
        map.maxZoomLevel = 19.0
        map.minZoomLevel = 11.0

        myLocationMarker = Marker(map).apply {
            icon = BitmapDrawable(resources, createDirectionalLocationIcon())
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setFlat(true)
        }
        map.overlays.add(myLocationMarker)

        locationProvider = GpsMyLocationProvider(this)
        var isFirstFix = true

        val locationConsumer = IMyLocationConsumer { location, _ ->
            if (location == null) return@IMyLocationConsumer
            val geoPoint = GeoPoint(location)
            myLocationMarker?.position = geoPoint

            if (location.hasBearing()) {
                myLocationMarker?.rotation = location.bearing - map.mapOrientation
            }

            if (isFirstFix) {
                mapController.animateTo(geoPoint)
                isFirstFix = false
            }
            map.invalidate()
        }
        locationProvider.startLocationProvider(locationConsumer)

        val rotationGestureOverlay = RotationGestureOverlay(map)
        rotationGestureOverlay.isEnabled = true
        map.overlays.add(rotationGestureOverlay)
    }

    private suspend fun fetchAndDisplayBuses() {
        try {
            val busDataString = fetchBusData()
            val busInfoList = parseBusData(busDataString)
            updateMapMarkers(busInfoList)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private suspend fun fetchBusData(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://transport.tallinn.ee/gps.txt").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            response.body?.string() ?: ""
        }
    }

    private fun parseBusData(data: String): List<BusInfo> {
        return data.lines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 8) return@mapNotNull null
            try {
                val transportType = when (parts[0]) { "3" -> "Tram" else -> "Bus" }
                val lineNumber = parts[1]
                val lon = parts[2].toDouble() / 1_000_000.0
                val lat = parts[3].toDouble() / 1_000_000.0
                val bearing = parts[5].toFloat()
                BusInfo(transportType, lineNumber, GeoPoint(lat, lon), bearing)
            } catch (e: NumberFormatException) { null }
        }
    }

    private suspend fun updateMapMarkers(busInfoList: List<BusInfo>) = withContext(Dispatchers.Main) {
        val newBusesByRoute = busInfoList.groupBy { it.lineNumber }
        val markersToRemove = mutableListOf<MarkerState>()

        for ((route, oldMarkers) in busMarkersByRoute) {
            val newBusesForRoute = newBusesByRoute[route]
            val availableOldMarkers = oldMarkers.toMutableList()
            if (newBusesForRoute == null) {
                markersToRemove.addAll(oldMarkers)
                continue
            }
            for (newBus in newBusesForRoute) {
                val closestMarkerState = availableOldMarkers.minByOrNull { it.marker.position.distanceToAsDouble(newBus.location) }
                if (closestMarkerState != null && closestMarkerState.marker.position.distanceToAsDouble(newBus.location) < MAX_MATCHING_DISTANCE_METERS) {
                    availableOldMarkers.remove(closestMarkerState)
                    animateMarker(closestMarkerState, newBus.location, newBus.bearing)
                } else {
                    val newMarkerState = createNewMarker(newBus)
                    oldMarkers.add(newMarkerState)
                }
            }
            markersToRemove.addAll(availableOldMarkers)
        }

        val existingRoutes = busMarkersByRoute.keys
        for ((route, newBuses) in newBusesByRoute) {
            if (route !in existingRoutes) {
                val newMarkerStates = newBuses.map { createNewMarker(it) }
                busMarkersByRoute[route] = newMarkerStates.toMutableList()
            }
        }

        for (markerStateToRemove in markersToRemove) {
            busMarkersByRoute[markerStateToRemove.marker.subDescription]?.remove(markerStateToRemove)
            map.overlays.remove(markerStateToRemove.marker)
        }
        map.invalidate()
    }

    private fun animateMarker(markerState: MarkerState, newPosition: GeoPoint, newBearing: Float) {
        markerState.animator?.cancel()
        val startPosition = markerState.marker.position
        val valueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UPDATE_INTERVAL_MS
            interpolator = LinearInterpolator()
            val startBearing = markerState.lastBearing
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val lat = startPosition.latitude + (newPosition.latitude - startPosition.latitude) * fraction
                val lon = startPosition.longitude + (newPosition.longitude - startPosition.longitude) * fraction
                markerState.marker.position = GeoPoint(lat, lon)

                val interpolatedBearing = interpolateBearing(startBearing, newBearing, fraction)
                markerState.marker.rotation = interpolatedBearing
            }
        }
        valueAnimator.start()
        markerState.animator = valueAnimator
        markerState.lastBearing = newBearing
    }

    private fun interpolateBearing(start: Float, end: Float, fraction: Float): Float {
        var delta = end - start
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return start + delta * fraction
    }

    private fun createDirectionalLocationIcon(): Bitmap {
        val size = dpToPx(this, 32f).toInt()
        val center = size / 2f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#884285F4")
            maskFilter = BlurMaskFilter(center / 2, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(center, center, center / 2, glowPaint)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4285F4")
        }
        canvas.drawCircle(center, center, center / 2.2f, circlePaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(this@MainActivity, 1.5f)
        }
        canvas.drawCircle(center, center, center / 2.2f, borderPaint)

        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return dp * (context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)
    }

    private fun requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Location access is not granted", Toast.LENGTH_LONG).show()
        }
    }

    private fun startBusTracking() {
        lifecycleScope.launch {
            while (isActive) {
                fetchAndDisplayBuses()
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        InfoWindow.closeAllInfoWindowsOn(map)
        map.onPause()
    }
}
