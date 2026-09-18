package com.destino.app.feature.setup.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.destino.app.core.model.Coordinates
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

@Composable
fun InteractiveMapComponent(
    selectedCoordinates: Coordinates?,
    radiusMeters: Double,
    destinationName: String,
    onMapClick: (Coordinates) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    // Ponto de partida padrão: São Paulo - Marco Zero se nada selecionado
    val defaultLatLng = LatLng(-23.550520, -46.633308)
    var lastKnownLatLng by remember { mutableStateOf<LatLng?>(null) }
    val targetLatLng = selectedCoordinates?.let { LatLng(it.latitude, it.longitude) }
        ?: lastKnownLatLng
        ?: defaultLatLng

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && selectedCoordinates == null) {
            try {
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            lastKnownLatLng = LatLng(location.latitude, location.longitude)
                        }
                    }
            } catch (_: SecurityException) {
                lastKnownLatLng = null
            }
        }
    }

    val destinationZoom = when {
        radiusMeters <= 150.0 -> 17f
        radiusMeters <= 300.0 -> 16f
        radiusMeters <= 700.0 -> 15f
        radiusMeters <= 1_500.0 -> 14f
        radiusMeters <= 4_000.0 -> 12.5f
        radiusMeters <= 10_000.0 -> 11f
        else -> 9.5f
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(targetLatLng, if (selectedCoordinates != null) destinationZoom else 13f)
    }

    // Centraliza a localização inicial ou enquadra o destino conforme o raio escolhido.
    LaunchedEffect(selectedCoordinates, lastKnownLatLng, radiusMeters) {
        if (selectedCoordinates != null) {
            val newTarget = LatLng(selectedCoordinates.latitude, selectedCoordinates.longitude)
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(newTarget, destinationZoom),
                durationMs = 800
            )
        } else if (lastKnownLatLng != null) {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(lastKnownLatLng!!, 15f),
                durationMs = 800
            )
        }
    }

    val markerState = remember(selectedCoordinates) {
        MarkerState(position = targetLatLng)
    }

    val uiSettings = remember(hasLocationPermission) {
        MapUiSettings(
            zoomControlsEnabled = true,
            compassEnabled = true,
            myLocationButtonEnabled = hasLocationPermission
        )
    }

    val mapProperties = remember(hasLocationPermission) {
        MapProperties(
            isMyLocationEnabled = hasLocationPermission
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = uiSettings,
                properties = mapProperties,
                onMapClick = { latLng ->
                    onMapClick(Coordinates(latLng.latitude, latLng.longitude))
                }
            ) {
                if (selectedCoordinates != null) {
                    // Marcador de destino
                    Marker(
                        state = markerState,
                        title = destinationName.ifBlank { "Destino" },
                        snippet = "Raio de aviso: ${radiusMeters.toInt()}m",
                        draggable = false
                    )

                    // Círculo dinâmico do raio do alarme
                    Circle(
                        center = targetLatLng,
                        radius = radiusMeters,
                        fillColor = Color(0x33153F2C),
                        strokeColor = Color(0xFF153F2C),
                        strokeWidth = 3f
                    )
                }
            }

            // Dica visual no topo do mapa
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (selectedCoordinates == null) {
                        "Toque no mapa para escolher o destino"
                    } else {
                        "Destino selecionado • Círculo indica o raio de ${radiusMeters.toInt()}m"
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp
                )
            }
        }
    }
}
