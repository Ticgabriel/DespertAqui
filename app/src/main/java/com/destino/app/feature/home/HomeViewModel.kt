package com.destino.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.ActiveOccurrenceState
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.Favorite
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.core.model.OperationalState
import com.destino.app.core.model.ScheduleRuleType
import com.destino.app.core.schedule.ScheduleEvaluator
import com.destino.app.core.schedule.ScheduleEvaluatorImpl
import com.destino.app.data.local.AlarmRepository
import com.destino.app.data.local.FavoriteRepository
import com.destino.app.data.local.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

data class NextActivationItem(
    val alarmId: String,
    val alarmName: String,
    val destinationName: String,
    val nextActivationTimeText: String,
    val nextEpochMs: Long,
    val radiusMeters: Double = 500.0
)

data class HomeUiState(
    val operationalState: OperationalState = OperationalState(),
    val quickFavorites: List<Favorite> = emptyList(),
    val nextActivations: List<NextActivationItem> = emptyList(),
    val userMessage: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val coordinator: MonitoringCoordinator,
    private val favoriteRepository: FavoriteRepository,
    private val alarmRepository: AlarmRepository,
    private val scheduleRepository: ScheduleRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val evaluator: ScheduleEvaluator = ScheduleEvaluatorImpl()

    init {
        // Observa estado operacional do monitoramento
        viewModelScope.launch {
            coordinator.operationalState.collectLatest { opState ->
                _uiState.update { it.copy(operationalState = opState) }
            }
        }

        // Observa favoritos para acesso rápido
        viewModelScope.launch {
            favoriteRepository.observeAll().collectLatest { favList ->
                _uiState.update { it.copy(quickFavorites = favList.take(6)) }
            }
        }

        // Carrega próximas ativações da agenda
        viewModelScope.launch {
            alarmRepository.observeAllAlarms().collectLatest { alarms ->
                val upcoming = mutableListOf<NextActivationItem>()
                val now = Instant.now()
                val zone = ZoneId.systemDefault()

                for (alarm in alarms.filter { it.isEnabled }) {
                    val dest = alarmRepository.getDestinationById(alarm.destinationId)
                    val rule = scheduleRepository.getRuleForAlarm(alarm.id) ?: continue
                    val windows = scheduleRepository.getWindowsForRule(rule.id)
                    val exceptions = scheduleRepository.getExceptions(rule.id)

                    val eval = evaluator.evaluate(rule, windows, exceptions, now, zone)
                    eval.nextBoundaryEpochMs?.let { ms ->
                        val zdt = Instant.ofEpochMilli(ms).atZone(zone)
                        val formatted = zdt.format(DateTimeFormatter.ofPattern("EEE, dd/MM 'às' HH:mm"))
                        upcoming.add(
                            NextActivationItem(
                                alarmId = alarm.id,
                                alarmName = alarm.name,
                                destinationName = dest?.name ?: "Destino",
                                nextActivationTimeText = formatted,
                                nextEpochMs = ms,
                                radiusMeters = alarm.radiusMeters
                            )
                        )
                    }
                }

                val sortedUpcoming = upcoming.sortedBy { it.nextEpochMs }.take(5)
                _uiState.update { it.copy(nextActivations = sortedUpcoming) }
            }
        }
    }

    fun startTripForFavorite(favorite: Favorite) {
        viewModelScope.launch {
            val result = coordinator.execute(
                MonitoringCommand.StartJourney(
                    commandId = UUID.randomUUID().toString(),
                    destinationName = favorite.nickname.ifBlank { favorite.destinationName },
                    coordinates = favorite.coordinates,
                    radiusMeters = favorite.suggestedRadiusMeters
                )
            )
            _uiState.update { it.copy(userMessage = resultMessage(result, "Viagem para ${favorite.nickname} iniciada!")) }
        }
    }

    fun stopSession(sessionId: String) {
        viewModelScope.launch {
            val result = coordinator.execute(
                MonitoringCommand.StopJourney(
                    commandId = UUID.randomUUID().toString(),
                    sessionId = sessionId
                )
            )
            _uiState.update { it.copy(userMessage = resultMessage(result, "Acompanhamento encerrado.")) }
        }
    }

    fun acknowledgeActiveAlert(eventId: String) {
        viewModelScope.launch {
            coordinator.execute(
                MonitoringCommand.AcknowledgeAlert(
                    commandId = UUID.randomUUID().toString(),
                    eventId = eventId
                )
            )
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    private fun resultMessage(result: CommandResult, success: String): String = when (result) {
        is CommandResult.Success -> success
        is CommandResult.AlreadyActive -> "Este destino já está sendo monitorado"
        is CommandResult.Rejected -> result.reason
        is CommandResult.Error -> result.message
    }
}
