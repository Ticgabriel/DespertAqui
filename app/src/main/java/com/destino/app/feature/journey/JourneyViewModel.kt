package com.destino.app.feature.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.core.model.OperationalState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class JourneyViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val coordinator: MonitoringCoordinator
) : ViewModel() {

    private val targetSessionId: String? = savedStateHandle.get<String>("sessionId")

    private fun resolveSelectedState(state: OperationalState): OperationalState {
        val selected = targetSessionId?.let { state.activeOccurrences[it] }
        return if (selected == null) {
            if (!targetSessionId.isNullOrBlank()) {
                state.copy(
                    activeSession = null,
                    activeDestination = null,
                    activeAlarm = null,
                    currentDistanceMeters = null,
                    lastLocationUpdateEpochMs = null,
                    waitingExitMessage = null,
                    isAlarmPlaying = false,
                    sessionState = com.destino.app.core.model.SessionState.FINISHED,
                    statusMessage = "Viagem encerrada",
                    isAlertActive = false,
                    activeAlertEventId = null,
                    activeAlertEventType = null
                )
            } else {
                state
            }
        } else {
            state.copy(
                activeSession = selected.session,
                activeDestination = selected.destination,
                activeAlarm = selected.alarm,
                sessionState = selected.sessionState,
                qualityState = selected.qualityState,
                currentDistanceMeters = selected.currentDistanceMeters,
                waitingExitMessage = if (state.activeSession?.id == selected.session.id) state.waitingExitMessage else null,
                lastLocationUpdateEpochMs = if (state.activeSession?.id == selected.session.id) state.lastLocationUpdateEpochMs else null,
                statusMessage = if (state.activeSession?.id == selected.session.id) state.statusMessage else when (selected.qualityState) {
                    com.destino.app.core.model.QualityState.GOOD -> "Localização disponível"
                    com.destino.app.core.model.QualityState.ACQUIRING -> "Buscando localização"
                    com.destino.app.core.model.QualityState.DEGRADED -> "Sinal de localização instável"
                    com.destino.app.core.model.QualityState.BLOCKED -> "Confira as permissões de localização"
                },
                isAlertActive = selected.isAlertActive,
                activeAlertEventId = selected.activeAlertEventId,
                activeAlertEventType = selected.activeAlertEventType
            )
        }
    }

    val operationalState: StateFlow<OperationalState> = coordinator.operationalState
        .map { resolveSelectedState(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, resolveSelectedState(coordinator.operationalState.value))

    private val _commandFeedback = MutableStateFlow<String?>(null)
    val commandFeedback: StateFlow<String?> = _commandFeedback.asStateFlow()

    private fun handleResult(result: CommandResult) {
        when (result) {
            is CommandResult.Success -> _commandFeedback.value = result.message
            is CommandResult.Rejected -> _commandFeedback.value = "Recusado: ${result.reason}"
            is CommandResult.Error -> _commandFeedback.value = "Erro: ${result.message}"
            is CommandResult.AlreadyActive -> _commandFeedback.value = "Viagem já em andamento"
        }
    }

    fun stopJourney() {
        val sessionId = targetSessionId ?: operationalState.value.activeSession?.id ?: return
        viewModelScope.launch {
            val result = coordinator.execute(
                MonitoringCommand.StopJourney(
                    commandId = UUID.randomUUID().toString(),
                    sessionId = sessionId
                )
            )
            handleResult(result)
        }
    }

    fun acknowledgeAlarm() {
        val eventId = operationalState.value.activeAlertEventId
            ?: (targetSessionId?.let { coordinator.operationalState.value.activeOccurrences[it]?.activeAlertEventId })
            ?: return
        viewModelScope.launch {
            val result = coordinator.execute(
                MonitoringCommand.AcknowledgeAlert(
                    commandId = UUID.randomUUID().toString(),
                    eventId = eventId
                )
            )
            handleResult(result)
        }
    }

    fun resumeJourney() {
        val sessionId = targetSessionId ?: operationalState.value.activeSession?.id ?: return
        viewModelScope.launch {
            val result = coordinator.execute(
                MonitoringCommand.ResumeJourney(
                    commandId = UUID.randomUUID().toString(),
                    sessionId = sessionId
                )
            )
            handleResult(result)
        }
    }

    fun testAlert() {
        viewModelScope.launch {
            val result = coordinator.execute(
                MonitoringCommand.TestAlert(
                    commandId = UUID.randomUUID().toString()
                )
            )
            handleResult(result)
        }
    }
}
