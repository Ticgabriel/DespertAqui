package com.destino.app.core.model

data class LocationConfig(
    val intervalMillis: Long = 3000L,
    val minUpdateIntervalMillis: Long = 1000L,
    val maxUpdateDelayMillis: Long = 0L,
    val highAccuracy: Boolean = true
)

sealed interface LocationEvent {
    data class NewLocation(
        val coordinates: Coordinates,
        val accuracyMeters: Float,
        val monotonicTimeMs: Long,
        val speedMps: Float? = null,
        val epochTimestampMs: Long = System.currentTimeMillis(),
        val sequenceNumber: Long = 0L
    ) : LocationEvent

    data class Unavailable(val reason: String) : LocationEvent
}
