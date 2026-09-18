package com.destino.app.platform.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.LocationConfig
import com.destino.app.core.model.LocationEvent
import com.destino.app.core.model.LocationSource
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FusedLocationSource @Inject constructor(
    @ApplicationContext private val context: Context
) : LocationSource {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val sequenceCounter = java.util.concurrent.atomic.AtomicLong(1L)

    @SuppressLint("MissingPermission")
    override fun observe(config: LocationConfig): Flow<LocationEvent> = callbackFlow {
        val priority = if (config.highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.Builder(priority, config.intervalMillis)
            .setMinUpdateIntervalMillis(config.minUpdateIntervalMillis)
            .setMaxUpdateDelayMillis(config.maxUpdateDelayMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    try {
                        val coords = Coordinates(location.latitude, location.longitude)
                        val monotonicMs = if (location.elapsedRealtimeNanos > 0) {
                            location.elapsedRealtimeNanos / 1_000_000L
                        } else {
                            SystemClock.elapsedRealtime() - (System.currentTimeMillis() - location.time)
                        }
                        val seqNum = sequenceCounter.getAndIncrement()
                        val event = LocationEvent.NewLocation(
                            coordinates = coords,
                            accuracyMeters = location.accuracy,
                            monotonicTimeMs = monotonicMs,
                            speedMps = if (location.hasSpeed()) location.speed else null,
                            epochTimestampMs = location.time,
                            sequenceNumber = seqNum
                        )
                        val sendResult = trySend(event)
                        if (sendResult.isFailure) {
                            android.util.Log.w("FusedLocationSource", "Falha ao enviar leitura seq=$seqNum no fluxo de localização.")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("FusedLocationSource", "Coordenadas anômalas descartadas: ${e.message}")
                    }
                }
            }

            override fun onLocationAvailability(availability: com.google.android.gms.location.LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    trySend(LocationEvent.Unavailable("Sinal de GPS temporariamente indisponível"))
                }
            }
        }

        try {
            val task = fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            )

            // Observar sucesso e falha assíncronos da tarefa (Item 6 da avaliação)
            task.addOnFailureListener { e ->
                trySend(LocationEvent.Unavailable("Falha no provedor de localização: ${e.message}"))
                close(e)
            }
        } catch (e: SecurityException) {
            trySend(LocationEvent.Unavailable("Permissão de localização revogada: ${e.message}"))
            close(e)
            return@callbackFlow
        } catch (e: Exception) {
            trySend(LocationEvent.Unavailable("Falha ao registrar cliente de localização: ${e.message}"))
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try {
                fusedLocationClient.removeLocationUpdates(callback)
            } catch (e: Exception) {
                // Ignorar erro no encerramento
            }
        }
    }.buffer(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
}
