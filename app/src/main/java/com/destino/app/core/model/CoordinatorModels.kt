package com.destino.app.core.model

data class ActiveOccurrenceState(
    val occurrenceId: String? = null,
    val session: MonitoringSession,
    val destination: Destination,
    val alarm: AlarmDefinition,
    val sessionState: SessionState = SessionState.ACTIVE,
    val qualityState: QualityState = QualityState.ACQUIRING,
    val currentDistanceMeters: Double? = null,
    val isAlertActive: Boolean = false,
    val activeAlertEventId: String? = null,
    val activeAlertEventType: AlertEventType? = null
)

data class OperationalState(
    val sessionState: SessionState = SessionState.FINISHED,
    val qualityState: QualityState = QualityState.ACQUIRING,
    val alertState: AlertState = AlertState.ACKNOWLEDGED,
    val activeDestination: Destination? = null,
    val activeAlarm: AlarmDefinition? = null,
    val activeSession: MonitoringSession? = null,
    val currentDistanceMeters: Double? = null,
    val lastLocationUpdateEpochMs: Long? = null,
    val statusMessage: String = "Pronto para iniciar",
    val isAlarmPlaying: Boolean = false,
    val isAlertActive: Boolean = false,
    val waitingExitMessage: String? = null,
    val activeAlertEventId: String? = null,
    val activeAlertEventType: AlertEventType? = null,
    val activeOccurrences: Map<String, ActiveOccurrenceState> = emptyMap(),
    val pendingAlertQueue: List<AlertEvent> = emptyList(),
    val activeTravelsCount: Int = 0,
    val pendingAlertsCount: Int = 0
)

sealed interface MonitoringCommand {
    val commandId: String

    data class StartJourney(
        override val commandId: String,
        val destinationName: String,
        val coordinates: Coordinates,
        val radiusMeters: Double,
        val isVibrationEnabled: Boolean = true,
        val destinationId: String? = null,
        val alarmId: String? = null,
        val alarmName: String? = null,
        val soundSelection: SoundSelection? = null,
        val vibrationPattern: VibrationPattern? = null,
        val audioOutputPolicy: AudioOutputPolicy? = null,
        val monitoringProfile: MonitoringProfile? = null,
        val allowNewEntrySameWindow: Boolean = false
    ) : MonitoringCommand

    data class StartOccurrence(
        override val commandId: String,
        val occurrence: AlarmOccurrence,
        val destination: Destination,
        val alarm: AlarmDefinition
    ) : MonitoringCommand

    data class StopJourney(
        override val commandId: String,
        val sessionId: String
    ) : MonitoringCommand

    data class StopOccurrence(
        override val commandId: String,
        val occurrenceId: String,
        val finalStatus: OccurrenceStatus = OccurrenceStatus.EXPIRED,
        val reason: String = "Janela de horário encerrada"
    ) : MonitoringCommand

    data class AcknowledgeAlert(
        override val commandId: String,
        val eventId: String
    ) : MonitoringCommand

    data class TestAlert(
        override val commandId: String
    ) : MonitoringCommand

    data class ResumeJourney(
        override val commandId: String,
        val sessionId: String
    ) : MonitoringCommand
}

sealed interface CommandResult {
    data class Success(val message: String = "Operação concluída com sucesso") : CommandResult
    data class Rejected(val reason: String) : CommandResult
    data class Error(val message: String, val cause: Throwable? = null) : CommandResult
    data class AlreadyActive(val sessionId: String) : CommandResult
}
