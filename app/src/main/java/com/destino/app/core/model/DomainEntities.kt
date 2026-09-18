package com.destino.app.core.model

data class Coordinates(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Latitude inválida: $latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Longitude inválida: $longitude" }
    }
}

data class Destination(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val providerOrigin: String? = null,
    val placeId: String? = null,
    val expiresAtEpochMs: Long? = null
)

data class AlarmDefinition(
    val id: String,
    val destinationId: String,
    val radiusMeters: Double,
    val isVibrationEnabled: Boolean = true,
    val name: String = "Alarme",
    val isEnabled: Boolean = true,
    val soundSelection: SoundSelection = SoundSelection(),
    val usesDefaultSound: Boolean = false,
    val vibrationPattern: VibrationPattern = VibrationPattern.STRONG,
    val usesDefaultVibrationPattern: Boolean = false,
    val audioOutputPolicy: AudioOutputPolicy = AudioOutputPolicy.SYSTEM_DEFAULT,
    val usesDefaultAudioOutputPolicy: Boolean = false,
    val monitoringProfile: MonitoringProfile = MonitoringProfile.AUTOMATIC,
    val usesDefaultMonitoringProfile: Boolean = false,
    val allowNewEntrySameWindow: Boolean = false,
    val cooldownMinutes: Int = 5,
    val version: Int = 1
) {
    init {
        require(radiusMeters.isFinite() && radiusMeters >= 100.0) { "Raio deve ser no mínimo 100 metros: $radiusMeters" }
    }
}

data class MonitoringSession(
    val id: String,
    val destinationId: String,
    val alarmDefinitionId: String,
    val state: SessionState,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val occurrenceId: String? = null
)

data class AlarmRuntime(
    val id: String,
    val sessionId: String,
    val isArmed: Boolean = true,
    val confirmationsCount: Int = 0,
    val waitingExit: Boolean = false,
    val cooldownUntilMonotonicMs: Long = 0L
)

data class AlertEvent(
    val id: String,
    val sessionId: String,
    val cycle: Int,
    val eventType: AlertEventType,
    val state: AlertState,
    val createdAtEpochMs: Long,
    val deliveredAtEpochMs: Long? = null,
    val acknowledgedAtEpochMs: Long? = null,
    val failureReason: String? = null
)
