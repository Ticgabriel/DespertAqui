package com.destino.app.core.model

data class EngineState(
    val destination: Destination,
    val alarmDefinition: AlarmDefinition,
    val session: MonitoringSession,
    val runtime: AlarmRuntime,
    val qualityState: QualityState = QualityState.ACQUIRING,
    val lastReceivedReading: LocationEvent.NewLocation? = null,
    val lastUsefulReading: LocationEvent.NewLocation? = null,
    val lastUsefulReadingMonotonicMs: Long = 0L,
    val pendingConfirmationReading: LocationEvent.NewLocation? = null,
    val pendingConfirmationSinceMonotonicMs: Long = 0L,
    val lastDistanceMeters: Double? = null,
    val consecutiveOutsideUsefulReadings: Int = 0,
    val consecutiveUsefulReadingsSinceLoss: Int = 0,
    val precautionEmittedForEpisode: Boolean = false,
    val precautionEpisodeCount: Int = 0,
    val acquisitionStartedAtMonotonicMs: Long = 0L
)

sealed interface EngineEvent {
    data class LocationReceived(
        val reading: LocationEvent.NewLocation,
        val monotonicTimeMs: Long,
        val continuityBroken: Boolean = false
    ) : EngineEvent

    data class ClockTick(
        val monotonicTimeMs: Long
    ) : EngineEvent

    data class UserAcknowledged(
        val eventId: String,
        val monotonicTimeMs: Long
    ) : EngineEvent
}

sealed interface EngineEffect {
    data class TriggerArrivalAlert(
        val sessionId: String,
        val cycle: Int,
        val destinationId: String,
        val distanceMeters: Double
    ) : EngineEffect

    data class EmitPrecautionAlert(
        val sessionId: String,
        val cycle: Int,
        val message: String
    ) : EngineEffect

    data class UpdateQuality(
        val qualityState: QualityState
    ) : EngineEffect

    data class ConsumeAlarm(
        val destinationId: String,
        val sessionId: String
    ) : EngineEffect

    data class EnterWaitingExit(
        val message: String
    ) : EngineEffect
}

data class EngineDecision(
    val newState: EngineState,
    val effects: List<EngineEffect> = emptyList()
)
