package com.omama.stationalarm.ui.screens

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.omama.stationalarm.network.GeoSearchResult
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * The osmdroid-backed map composable used by [MapSearchScreen]. Extracted from
 * MapSearchScreen.kt during the modularity refactor; behaviour is preserved
 * verbatim.
 */
@Composable
internal fun OsmMapView(
    initialCenter  : GeoPoint?,
    selectedResult : GeoSearchResult?,
    radiusKm       : Double,
    onSingleTap    : (Double, Double) -> Unit,
    onLongPress    : (Double, Double) -> Unit,
    onMapReady     : (MapView) -> Unit,
    modifier       : Modifier = Modifier
) {
    // Default center: India if GPS unavailable
    val startLat = initialCenter?.latitude  ?: 20.5937
    val startLon = initialCenter?.longitude ?: 78.9629

    // Stable callback holders — lets the factory's MapEventsOverlay
    // always call the latest lambda without needing to recreate the overlay.
    val singleTapCb = rememberUpdatedState(onSingleTap)
    val longPressCb = rememberUpdatedState(onLongPress)

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(
                    org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                )
                // Scale 256px tiles to device DPI — reduces tile count on HDPI screens
                isTilesScaledToDpi = true
                controller.setZoom(if (initialCenter != null) 17.5 else 5.0)
                controller.setCenter(GeoPoint(startLat, startLon))

                // Persistent tap listener — never destroyed
                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        p?.let { singleTapCb.value(it.latitude, it.longitude) }
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let { longPressCb.value(it.latitude, it.longitude) }
                        return true
                    }
                }
                overlays.add(0, MapEventsOverlay(receiver))
                onMapReady(this)
            }
        },
        update = { mapView ->
            // CRITICAL FIX: Close existing bubbles to prevent ghost double-bubbles
            org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(mapView)

            // Remove old pin/circle; MapEventsOverlay (index 0) is preserved
            mapView.overlays.removeAll { it is Marker || it is Polygon }

            val selectedLat = selectedResult?.lat
            val selectedLon = selectedResult?.lon

            if (selectedLat != null && selectedLon != null) {
                val center = GeoPoint(selectedLat, selectedLon)

                // Geofence circle points
                val circlePoints = (0..360 step 4).map { angle ->
                    val rad  = Math.toRadians(angle.toDouble())
                    val dLat = (radiusKm / 111.0) * Math.cos(rad)
                    val dLon = (radiusKm / (111.0 * Math.cos(Math.toRadians(selectedLat)))) * Math.sin(rad)
                    GeoPoint(selectedLat + dLat, selectedLon + dLon)
                }

                // Auto-zoom to fit the circle with padding
                val lats = circlePoints.map { it.latitude }
                val lons = circlePoints.map { it.longitude }
                val bbox = BoundingBox(
                    lats.max(), lons.max(), lats.min(), lons.min()
                )
                mapView.post {
                    mapView.zoomToBoundingBox(bbox, true, 100)
                    mapView.controller.setCenter(center)
                }

                // Pin marker — purely visual, no info window so it never
                // intercepts tap events that should reach MapEventsOverlay.
                val marker = Marker(mapView).apply {
                    position = center
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> false }
                }
                mapView.overlays.add(marker)

                // Geofence circle polygon (make non-clickable to prevent empty bubbles)
                val polygon = Polygon(mapView).apply {
                    points = circlePoints
                    fillPaint.color    = android.graphics.Color.argb(45, 79, 195, 247)
                    outlinePaint.color = android.graphics.Color.argb(210, 79, 195, 247)
                    outlinePaint.strokeWidth = 3.5f
                    // Prevent this polygon from opening an empty default InfoWindow
                    infoWindow = null
                    setOnClickListener { _, _, _ -> false }
                }
                mapView.overlays.add(polygon)

            } else if (initialCenter != null) {
                // No pin selected — show a "You are here" blue dot at GPS location
                val userMarker = Marker(mapView).apply {
                    position = initialCenter
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> false }
                    setInfoWindow(null)
                    icon = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setSize(48, 48)
                        setColor(android.graphics.Color.argb(220, 79, 195, 247))
                        setStroke(4, android.graphics.Color.WHITE)
                    }
                }
                mapView.overlays.add(userMarker)
                mapView.controller.setZoom(17.5)
                mapView.controller.setCenter(initialCenter)
            }
            mapView.invalidate()
        },
        modifier = modifier
    )
}
