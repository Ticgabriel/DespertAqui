package com.destino.app.feature.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.AlarmDefinition
import com.destino.app.core.model.Destination
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.OccurrenceStatus
import com.destino.app.core.model.ScheduleException
import com.destino.app.core.model.ScheduleExceptionType
import com.destino.app.core.model.ScheduleRule
import com.destino.app.core.model.ScheduleRuleType
import com.destino.app.core.model.ScheduleWindow
import com.destino.app.core.schedule.ScheduleEvaluator
import com.destino.app.core.schedule.ScheduleEvaluatorImpl
import com.destino.app.data.local.AlarmRepository
import com.destino.app.data.local.OccurrenceRepository
import com.destino.app.data.local.ScheduleRepository
import com.destino.app.platform.schedule.SystemScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

data class AlarmItemUi(
    val alarm: AlarmDefinition,
    val destination: Destination?,
    val rule: ScheduleRule?,
    val windows: List<ScheduleWindow>,
    val scheduleSummary: String,
    val statusBadge: String,
    val nextActivationDescription: String?
)

data class AlarmListUiState(
    val alarms: List<AlarmItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val userMessage: String? = null,
    val startedSessionId: String? = null
)

@HiltViewModel
class AlarmListViewModel @Inject constructor(
    private val alarmRepository: AlarmRepository,
    private val scheduleRepository: ScheduleRepository,
    private val occurrenceRepository: OccurrenceRepository,
    private val systemScheduler: SystemScheduler,
    private val coordinator: MonitoringCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlarmListUiState())
    val uiState: StateFlow<AlarmListUiState> = _uiState.asStateFlow()

    private val evaluator: ScheduleEvaluator = ScheduleEvaluatorImpl()

    init {
        viewModelScope.launch {
            combine(
                alarmRepository.observeAllAlarms(),
                occurrenceRepository.observeActiveOccurrences()
            ) { alarmList, activeOccurrences ->
                val occurrencesByAlarm = activeOccurrences.groupBy { it.alarmDefinitionId }
                alarmList.map { alarm ->
                    val dest = alarmRepository.getDestinationById(alarm.destinationId)
                    val rule = scheduleRepository.getRuleForAlarm(alarm.id)
                    val windows = if (rule != null) scheduleRepository.getWindowsForRule(rule.id) else emptyList()
                    val exceptions = if (rule != null) scheduleRepository.getExceptions(rule.id) else emptyList()
                    val occurrences = occurrencesByAlarm[alarm.id] ?: emptyList()

                    val eval = if (rule != null && alarm.isEnabled) {
                        evaluator.evaluate(rule, windows, exceptions, Instant.now(), ZoneId.systemDefault())
                    } else null

                    val awaitingPrereq = occurrences.firstOrNull { it.status == OccurrenceStatus.AWAITING_PREREQUISITES }
                    val statusBadge = when {
                        !alarm.isEnabled -> "Desativado"
                        occurrences.any { it.status == OccurrenceStatus.MONITORING || it.status == OccurrenceStatus.WAITING_EXIT } -> "Monitorando"
                        awaitingPrereq != null -> "Requisitos pendentes"
                        eval?.isEligibleNow == true -> "Aguardando início"
                        eval?.nextBoundaryEpochMs != null -> "Agendado"
                        else -> "Inativo"
                    }

                    val summary = buildScheduleSummary(rule, windows)
                    val nextDesc = if (awaitingPrereq != null) {
                        "Atenção: ${awaitingPrereq.completionReason ?: "Faltam permissões para iniciar"}"
                    } else {
                        eval?.nextBoundaryEpochMs?.let { ms ->
                            val zdt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                            val prefix = if (eval.isEligibleNow) "Término da janela: " else "Próxima ativação: "
                            prefix + zdt.format(DateTimeFormatter.ofPattern("EEE, dd/MM 'às' HH:mm"))
                        }
                    }

                    AlarmItemUi(
                        alarm = alarm,
                        destination = dest,
                        rule = rule,
                        windows = windows,
                        scheduleSummary = summary,
                        statusBadge = statusBadge,
                        nextActivationDescription = nextDesc
                    )
                }
            }.collectLatest { items ->
                _uiState.update { it.copy(alarms = items, isLoading = false) }
            }
        }
    }

    private fun buildScheduleSummary(rule: ScheduleRule?, windows: List<ScheduleWindow>): String {
        if (rule == null || windows.isEmpty()) return "Manual"

        val daysStr = when {
            rule.type == ScheduleRuleType.ONE_OFF -> rule.specificDate?.format(DateTimeFormatter.ofPattern("dd/MM")) ?: "Data única"
            rule.daysOfWeek.size == 7 -> "Todos os dias"
            rule.daysOfWeek == setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "Seg a sex"
            rule.daysOfWeek == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Fim de semana"
            else -> rule.daysOfWeek.sortedBy { it.value }.joinToString(", ") {
                when (it) {
                    DayOfWeek.MONDAY -> "Seg"
                    DayOfWeek.TUESDAY -> "Ter"
                    DayOfWeek.WEDNESDAY -> "Qua"
                    DayOfWeek.THURSDAY -> "Qui"
                    DayOfWeek.FRIDAY -> "Sex"
                    DayOfWeek.SATURDAY -> "Sáb"
                    DayOfWeek.SUNDAY -> "Dom"
                }
            }
        }

        val windowStr = if (windows.any { it.isAllDay }) {
            "Dia inteiro"
        } else {
            windows.joinToString(" e ") {
                "${it.startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}–${it.endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            }
        }

        return "$daysStr · $windowStr"
    }

    fun toggleAlarm(alarmId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            if (!isEnabled) stopActiveOccurrences(alarmId)
            alarmRepository.setAlarmEnabled(alarmId, isEnabled)
            systemScheduler.reconcileSchedules()
            _uiState.update { it.copy(userMessage = if (isEnabled) "Alarme ativado" else "Alarme desativado") }
        }
    }

    fun deleteAlarm(alarmId: String) {
        viewModelScope.launch {
            stopActiveOccurrences(alarmId)
            alarmRepository.deleteAlarm(alarmId)
            systemScheduler.reconcileSchedules()
            _uiState.update { it.copy(userMessage = "Alarme excluído") }
        }
    }

    fun duplicateAlarm(item: AlarmItemUi) {
        viewModelScope.launch {
            val dest = item.destination
            if (dest == null) {
                _uiState.update { it.copy(userMessage = "Não foi possível duplicar: destino não encontrado") }
                return@launch
            }
            val newDestId = UUID.randomUUID().toString()
            val duplicatedDest = dest.copy(id = newDestId)

            val newAlarmId = UUID.randomUUID().toString()
            val duplicatedAlarm = item.alarm.copy(
                id = newAlarmId,
                destinationId = newDestId,
                name = "Cópia de ${item.alarm.name}",
                isEnabled = false // Começa desativado para ajustes conforme Seção 4
            )
            alarmRepository.saveDestinationAndAlarm(duplicatedDest, duplicatedAlarm)

            val rule = item.rule
            if (rule != null) {
                val newRuleId = UUID.randomUUID().toString()
                val duplicatedRule = rule.copy(id = newRuleId, alarmDefinitionId = newAlarmId)
                val duplicatedWindows = item.windows.map { it.copy(id = UUID.randomUUID().toString(), ruleId = newRuleId) }
                scheduleRepository.saveSchedule(duplicatedRule, duplicatedWindows)
            }
            systemScheduler.reconcileSchedules()
            _uiState.update { it.copy(userMessage = "Alarme duplicado com sucesso") }
        }
    }

    fun skipNextOccurrence(item: AlarmItemUi) {
        val rule = item.rule ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existingExceptions = scheduleRepository.getExceptions(rule.id)
            val nextOccurrence = occurrenceRepository.getByAlarmId(item.alarm.id)
                .filter { it.scheduledEndEpochMs > now && it.status in setOf(OccurrenceStatus.SCHEDULED, OccurrenceStatus.AWAITING_PREREQUISITES) }
                .minByOrNull { it.scheduledStartEpochMs }

            val candidate = nextOccurrence ?: evaluator.materializeOccurrences(
                alarmId = item.alarm.id,
                rule = rule,
                windows = item.windows,
                exceptions = existingExceptions,
                radiusMeters = item.alarm.radiusMeters,
                isVibrationEnabled = item.alarm.isVibrationEnabled,
                soundUri = item.alarm.soundSelection.uriString,
                fromDate = LocalDate.now(),
                daysAhead = 14,
                zoneId = ZoneId.systemDefault()
            ).filter { it.scheduledEndEpochMs > now && it.status != OccurrenceStatus.SKIPPED }
             .minByOrNull { it.scheduledStartEpochMs }
             ?: return@launch

            val window = item.windows.firstOrNull { it.id == candidate.windowId }
                ?: item.windows.firstOrNull()
                ?: return@launch

            scheduleRepository.addException(
                ScheduleException(
                    id = UUID.randomUUID().toString(),
                    ruleId = rule.id,
                    occurrenceDate = candidate.occurrenceDate,
                    windowId = window.id,
                    type = ScheduleExceptionType.SKIPPED
                )
            )
            systemScheduler.reconcileSchedules()
            _uiState.update { it.copy(userMessage = "Próxima ocorrência pulada") }
        }
    }

    fun startImmediately(item: AlarmItemUi) {
        val dest = item.destination ?: return
        viewModelScope.launch {
            val result = coordinator.execute(
                MonitoringCommand.StartJourney(
                    commandId = UUID.randomUUID().toString(),
                    destinationName = item.alarm.name.ifBlank { dest.name },
                    coordinates = dest.coordinates,
                    radiusMeters = item.alarm.radiusMeters,
                    isVibrationEnabled = item.alarm.isVibrationEnabled,
                    destinationId = dest.id,
                    alarmId = item.alarm.id,
                    alarmName = item.alarm.name,
                    soundSelection = if (item.alarm.usesDefaultSound) null else item.alarm.soundSelection,
                    vibrationPattern = if (item.alarm.usesDefaultVibrationPattern) null else item.alarm.vibrationPattern,
                    audioOutputPolicy = if (item.alarm.usesDefaultAudioOutputPolicy) null else item.alarm.audioOutputPolicy,
                    monitoringProfile = if (item.alarm.usesDefaultMonitoringProfile) null else item.alarm.monitoringProfile,
                    allowNewEntrySameWindow = item.alarm.allowNewEntrySameWindow
                )
            )
            val startedSessionId = when (result) {
                is CommandResult.Success -> {
                    coordinator.operationalState.value.activeOccurrences.values
                        .firstOrNull { it.alarm.id == item.alarm.id }?.session?.id
                        ?: alarmRepository.getActiveSessionsForAlarm(item.alarm.id).firstOrNull()?.session?.id
                        ?: coordinator.operationalState.value.activeSession?.id
                }
                is CommandResult.AlreadyActive -> result.sessionId
                else -> null
            }
            _uiState.update {
                it.copy(
                    startedSessionId = startedSessionId,
                    userMessage = when (result) {
                        is CommandResult.Success -> result.message
                        is CommandResult.AlreadyActive -> "Este destino já está sendo monitorado"
                        is CommandResult.Rejected -> result.reason
                        is CommandResult.Error -> result.message
                    }
                )
            }
        }
    }

    fun clearStartedSessionId() {
        _uiState.update { it.copy(startedSessionId = null) }
    }

    private suspend fun stopActiveOccurrences(alarmId: String) {
        val activeSessions = alarmRepository.getActiveSessionsForAlarm(alarmId)
        activeSessions.forEach { sessionDetails ->
            coordinator.execute(
                MonitoringCommand.StopJourney(
                    commandId = UUID.randomUUID().toString(),
                    sessionId = sessionDetails.session.id
                )
            )
        }

        occurrenceRepository.getByAlarmId(alarmId)
            .filter { it.status == OccurrenceStatus.MONITORING || it.status == OccurrenceStatus.WAITING_EXIT }
            .forEach { occurrence ->
                coordinator.execute(
                    MonitoringCommand.StopOccurrence(
                        commandId = UUID.randomUUID().toString(),
                        occurrenceId = occurrence.id,
                        finalStatus = OccurrenceStatus.CANCELLED,
                        reason = "Alarme desativado pelo usuário"
                    )
                )
                occurrenceRepository.updateStatus(occurrence.id, OccurrenceStatus.CANCELLED, "Alarme desativado pelo usuário")
            }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
