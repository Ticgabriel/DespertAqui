package com.destino.app.platform.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.datastore.core.DataStore
import com.destino.app.core.engine.MonitoringEngineImpl
import com.destino.app.core.engine.LocationRequestPlanner
import com.destino.app.core.engine.DestinationMonitoringContext
import com.destino.app.core.engine.MonitoringProfileConfig
import com.destino.app.core.model.ActiveOccurrenceState
import com.destino.app.core.model.AlarmDefinition
import com.destino.app.core.model.AlarmOccurrence
import com.destino.app.core.model.AlarmRuntime
import com.destino.app.core.model.AlertController
import com.destino.app.core.model.AlertEvent
import com.destino.app.core.model.AlertEventType
import com.destino.app.core.model.AlertState
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.DeliveryResult
import com.destino.app.core.model.Destination
import com.destino.app.core.model.EngineDecision
import com.destino.app.core.model.EngineEffect
import com.destino.app.core.model.EngineEvent
import com.destino.app.core.model.EngineState
import com.destino.app.core.model.LocationConfig
import com.destino.app.core.model.LocationEvent
import com.destino.app.core.model.LocationSource
import com.destino.app.core.model.HistoryEventType
import com.destino.app.core.model.HistoryRecord
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.core.model.MonitoringEngine
import com.destino.app.core.model.MonitoringSession
import com.destino.app.core.model.OperationalState
import com.destino.app.core.model.QualityState
import com.destino.app.core.model.SessionState
import com.destino.app.core.model.SoundSourceType
import com.destino.app.core.model.UserPreferences
import com.destino.app.data.local.AlarmRepository
import com.destino.app.data.local.HistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class MonitoringCoordinatorImpl protected constructor(
    private val repository: AlarmRepository,
    private val locationSource: LocationSource,
    private val alertController: AlertController,
    private val capabilityProvider: CapabilityProvider,
    private val historyRepository: HistoryRepository?,
    private val locationRequestPlanner: LocationRequestPlanner?,
    private val preferencesDataStore: DataStore<UserPreferences>?,
    private val context: Context?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : MonitoringCoordinator {

    @Inject
    constructor(
        repository: AlarmRepository,
        locationSource: LocationSource,
        alertController: AlertController,
        capabilityProvider: CapabilityProvider,
        historyRepository: HistoryRepository,
        locationRequestPlanner: LocationRequestPlanner,
        preferencesDataStore: DataStore<UserPreferences>,
        @ApplicationContext context: Context
    ) : this(repository, locationSource, alertController, capabilityProvider, historyRepository, locationRequestPlanner, preferencesDataStore, context, Dispatchers.Default)

    constructor(
        repository: AlarmRepository,
        locationSource: LocationSource,
        alertController: AlertController,
        capabilityProvider: CapabilityProvider
    ) : this(repository, locationSource, alertController, capabilityProvider, null, null, null, null, Dispatchers.Default)

    constructor(
        repository: AlarmRepository,
        locationSource: LocationSource,
        alertController: AlertController,
        capabilityProvider: CapabilityProvider,
        dispatcher: CoroutineDispatcher
    ) : this(repository, locationSource, alertController, capabilityProvider, null, null, null, null, dispatcher)

    private val engine: MonitoringEngine = MonitoringEngineImpl()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()

    private val _operationalState = MutableStateFlow(OperationalState())
    override val operationalState: StateFlow<OperationalState> = _operationalState.asStateFlow()

    // Coleção indexada de acompanhamentos ativos independentes
    private val activeEngines = mutableMapOf<String, EngineState>()
    private val occurrenceIdBySession = mutableMapOf<String, String?>()
    private val pendingAlertQueue = mutableListOf<AlertEvent>()
    private val activeAlertBySession = mutableMapOf<String, AlertEvent>()
    private var currentlyPlayingAlertId: String? = null

    private var locationJob: Job? = null
    private var tickerJob: Job? = null
    private var lastSequenceNumber: Long = 0L
    private var currentLocationConfig: LocationConfig? = null
    private var currentPreferences = UserPreferences()

    private val successfulCommandCache = mutableMapOf<String, CommandResult>()

    // Barreira de recuperação inicial
    private val recoveryJob: Job = scope.launch {
        checkAndRecoverSession()
    }

    init {
        preferencesDataStore?.let { dataStore ->
            scope.launch {
                dataStore.data.collectLatest { preferences ->
                    mutex.withLock {
                        currentPreferences = preferences
                        for ((sessionId, engineState) in activeEngines) {
                            val alarm = engineState.alarmDefinition
                            if (alarm.usesDefaultSound || alarm.usesDefaultVibrationPattern ||
                                alarm.usesDefaultAudioOutputPolicy || alarm.usesDefaultMonitoringProfile) {
                                val updatedAlarm = resolveDefaults(alarm)
                                activeEngines[sessionId] = engineState.copy(alarmDefinition = updatedAlarm)
                            }
                        }
                        if (activeEngines.isNotEmpty() && locationJob?.isActive == true) {
                            val planned = buildLocationConfig()
                            val hasPendingConfirmation = activeEngines.values.any { it.pendingConfirmationReading != null }
                            if (planned != currentLocationConfig && !hasPendingConfirmation) {
                                locationJob?.cancel()
                                locationJob = null
                                startLocationObservation()
                            }
                        }
                    }
                }
            }
        }
        alertController.setOnPlaybackFinishedListener { eventId ->
            scope.launch {
                mutex.withLock {
                    if (currentlyPlayingAlertId == eventId) {
                        currentlyPlayingAlertId = null
                        _operationalState.update { it.copy(isAlarmPlaying = false) }
                        processNextPendingAlert()
                    }
                }
            }
        }
        alertController.onPlaybackError = { eventId, attemptId, errorMsg ->
            scope.launch {
                mutex.withLock {
                    if (_operationalState.value.activeAlertEventId == eventId || currentlyPlayingAlertId == eventId) {
                        _operationalState.update {
                            it.copy(
                                isAlarmPlaying = false,
                                alertState = AlertState.DELIVERY_FAILED,
                                statusMessage = "Falha no áudio: $errorMsg. Alerta permanece ativo."
                            )
                        }
                        repository.updateAlertStatus(
                            eventId = eventId,
                            state = AlertState.DELIVERY_FAILED,
                            deliveredAtEpochMs = System.currentTimeMillis(),
                            failureReason = errorMsg
                        )
                        val failedEvent = repository.getAlertEventById(eventId)
                        activeEngines[failedEvent?.sessionId]?.let { engineState ->
                            recordHistory(engineState, HistoryEventType.DELIVERY_FAILED, errorMsg)
                        }
                        currentlyPlayingAlertId = null
                        processNextPendingAlert()
                    }
                }
            }
        }
    }

    private fun enqueueAlert(event: AlertEvent) {
        pendingAlertQueue.removeAll { it.id == event.id }
        pendingAlertQueue.add(event)
        pendingAlertQueue.sortWith(
            compareBy<AlertEvent> { if (it.eventType == AlertEventType.PROXIMITY) 0 else 1 }
                .thenBy { it.createdAtEpochMs }
        )
    }

    private suspend fun checkAndRecoverSession() = mutex.withLock {
        val unfinishedSessions = repository.getRecoverableSessionDetails()
        if (unfinishedSessions.isNotEmpty()) {
            unfinishedSessions.forEach { details ->
                val interruptedSession = details.session.copy(state = SessionState.INTERRUPTED)
                activeEngines[details.session.id] = EngineState(
                    destination = details.destination,
                    alarmDefinition = details.alarm,
                    session = interruptedSession,
                    runtime = details.runtime,
                    qualityState = QualityState.ACQUIRING,
                    precautionEpisodeCount = repository.getMaxPrecautionCycle(details.session.id)
                )
                occurrenceIdBySession[details.session.id] = details.occurrence?.id
                details.pendingAlert?.let { enqueueAlert(it) }
                details.pendingAlert?.let { activeAlertBySession[details.session.id] = it }
            }
            repository.markActiveSessionsInterrupted()
            val primary = unfinishedSessions.maxBy { it.session.startedAtEpochMs }
            syncOperationalStateFromEngines(
                primarySessionId = primary.session.id,
                sessionStateOverride = SessionState.INTERRUPTED,
                message = "Acompanhamento interrompido pelo sistema. Toque para retomar."
            )
            _operationalState.update {
                it.copy(
                    isAlarmPlaying = false,
                    isAlertActive = pendingAlertQueue.isNotEmpty(),
                    activeAlertEventId = pendingAlertQueue.firstOrNull()?.id,
                    activeAlertEventType = pendingAlertQueue.firstOrNull()?.eventType,
                    pendingAlertQueue = pendingAlertQueue.toList(),
                    pendingAlertsCount = pendingAlertQueue.size
                )
            }
        }
    }

    override suspend fun execute(command: MonitoringCommand): CommandResult {
        recoveryJob.join()

        return mutex.withLock {
            successfulCommandCache[command.commandId]?.let {
                return@withLock it
            }

            val result = when (command) {
                is MonitoringCommand.StartJourney -> startJourneyInternal(command)
                is MonitoringCommand.StartOccurrence -> startOccurrenceInternal(command)
                is MonitoringCommand.StopJourney -> stopJourneyInternal(command.sessionId)
                is MonitoringCommand.StopOccurrence -> stopOccurrenceInternal(command)
                is MonitoringCommand.AcknowledgeAlert -> acknowledgeAlertInternal(command.eventId)
                is MonitoringCommand.TestAlert -> testAlertInternal()
                is MonitoringCommand.ResumeJourney -> resumeJourneyInternal(command.sessionId)
            }

            if (result is CommandResult.Success || result is CommandResult.AlreadyActive) {
                successfulCommandCache[command.commandId] = result
            }

            result
        }
    }

    private suspend fun startJourneyInternal(cmd: MonitoringCommand.StartJourney): CommandResult {
        val caps = capabilityProvider.getCapabilities()
        if (!caps.canStartMonitoring) {
            return CommandResult.Rejected(caps.missingCapabilityReason ?: "Capacidades de sistema insuficientes")
        }

        try {
            val existingAlarm = cmd.alarmId?.let { repository.getAlarmById(it) }
            val destination = if (existingAlarm != null) {
                repository.getDestinationById(existingAlarm.destinationId) ?: Destination(
                    id = existingAlarm.destinationId,
                    name = cmd.destinationName.ifBlank { "Destino" },
                    coordinates = cmd.coordinates
                )
            } else {
                Destination(
                    id = cmd.destinationId ?: UUID.randomUUID().toString(),
                    name = cmd.destinationName.ifBlank { "Destino" },
                    coordinates = cmd.coordinates
                )
            }

            val alarm = if (existingAlarm != null) {
                resolveDefaults(existingAlarm)
            } else {
                AlarmDefinition(
                    id = cmd.alarmId ?: UUID.randomUUID().toString(),
                    destinationId = destination.id,
                    radiusMeters = cmd.radiusMeters,
                    isVibrationEnabled = cmd.isVibrationEnabled,
                    name = cmd.alarmName ?: cmd.destinationName.ifBlank { "Alarme" },
                    soundSelection = cmd.soundSelection ?: currentPreferences.defaultSoundSelection,
                    vibrationPattern = cmd.vibrationPattern ?: currentPreferences.defaultVibrationPattern,
                    audioOutputPolicy = cmd.audioOutputPolicy ?: currentPreferences.defaultAudioOutputPolicy,
                    monitoringProfile = cmd.monitoringProfile ?: currentPreferences.defaultMonitoringProfile,
                    allowNewEntrySameWindow = cmd.allowNewEntrySameWindow || currentPreferences.allowNewEntrySameWindow
                )
            }

            val sessionDetails = repository.startSession(destination, alarm)
            val initialPrecautionCount = repository.getMaxPrecautionCycle(sessionDetails.session.id)

            val engineState = EngineState(
                destination = destination,
                alarmDefinition = alarm,
                session = sessionDetails.session,
                runtime = sessionDetails.runtime,
                qualityState = QualityState.ACQUIRING,
                precautionEpisodeCount = initialPrecautionCount
            )

            activeEngines[sessionDetails.session.id] = engineState
            occurrenceIdBySession[sessionDetails.session.id] = sessionDetails.occurrence?.id
            syncOperationalStateFromEngines(primarySessionId = sessionDetails.session.id, message = "Iniciando acompanhamento...")

            try {
                startForegroundMonitoringService()
                recordHistory(engineState, HistoryEventType.START, "Viagem iniciada", sessionDetails.occurrence?.id)
            } catch (e: Exception) {
                activeEngines.remove(sessionDetails.session.id)
                repository.markSessionFailed(sessionDetails.session.id)
                _operationalState.update {
                    it.copy(
                        sessionState = SessionState.FAILED,
                        statusMessage = "Falha ao iniciar serviço em primeiro plano: ${e.message}",
                        activeSession = null
                    )
                }
                return CommandResult.Error("Falha ao iniciar serviço em primeiro plano: ${e.message}", e)
            }

            return CommandResult.Success("Viagem iniciada com sucesso")
        } catch (e: Exception) {
            return CommandResult.Error("Falha ao iniciar viagem: ${e.message}", e)
        }
    }

    private suspend fun startOccurrenceInternal(cmd: MonitoringCommand.StartOccurrence): CommandResult {
        val caps = capabilityProvider.getCapabilities()
        if (!caps.canStartMonitoring) {
            return CommandResult.Rejected(caps.missingCapabilityReason ?: "Capacidades de sistema insuficientes")
        }

        try {
            val existingEngineEntry = activeEngines.entries.find { occurrenceIdBySession[it.key] == cmd.occurrence.id }
            if (existingEngineEntry != null) {
                if (existingEngineEntry.value.session.state == SessionState.STARTING || existingEngineEntry.value.session.state == SessionState.ACTIVE) {
                    return CommandResult.AlreadyActive(existingEngineEntry.key)
                }
                // Limpar motor anterior interrompido da mesma ocorrência
                activeEngines.remove(existingEngineEntry.key)
                occurrenceIdBySession.remove(existingEngineEntry.key)
                activeAlertBySession.remove(existingEngineEntry.key)
                pendingAlertQueue.removeAll { it.sessionId == existingEngineEntry.key }
            }

            val existingSession = repository.getSessionByOccurrenceId(cmd.occurrence.id)
            if (existingSession?.session?.state == SessionState.STARTING || existingSession?.session?.state == SessionState.ACTIVE) {
                return CommandResult.AlreadyActive(existingSession.session.id)
            }
            val effectiveAlarm = resolveDefaults(cmd.alarm)
            val sessionDetails = repository.startOccurrenceSession(cmd.occurrence, cmd.destination, cmd.alarm)
            val initialPrecautionCount = repository.getMaxPrecautionCycle(sessionDetails.session.id)

            val engineState = EngineState(
                destination = cmd.destination,
                alarmDefinition = effectiveAlarm,
                session = sessionDetails.session,
                runtime = sessionDetails.runtime,
                qualityState = QualityState.ACQUIRING,
                precautionEpisodeCount = initialPrecautionCount
            )

            activeEngines[sessionDetails.session.id] = engineState
            occurrenceIdBySession[sessionDetails.session.id] = cmd.occurrence.id
            syncOperationalStateFromEngines(primarySessionId = sessionDetails.session.id, message = "Iniciando acompanhamento da ocorrência...")

            try {
                startForegroundMonitoringService()
                recordHistory(engineState, HistoryEventType.START, "Ocorrência agendada iniciada", cmd.occurrence.id)
            } catch (e: Exception) {
                activeEngines.remove(sessionDetails.session.id)
                repository.markSessionFailed(sessionDetails.session.id)
                return CommandResult.Error("Falha ao iniciar serviço em primeiro plano: ${e.message}", e)
            }

            return CommandResult.Success("Ocorrência iniciada com sucesso")
        } catch (e: Exception) {
            return CommandResult.Error("Falha ao iniciar ocorrência: ${e.message}", e)
        }
    }

    private fun resolveDefaults(alarm: AlarmDefinition): AlarmDefinition = alarm.copy(
        soundSelection = if (alarm.usesDefaultSound) currentPreferences.defaultSoundSelection else alarm.soundSelection,
        vibrationPattern = if (alarm.usesDefaultVibrationPattern) currentPreferences.defaultVibrationPattern else alarm.vibrationPattern,
        audioOutputPolicy = if (alarm.usesDefaultAudioOutputPolicy) currentPreferences.defaultAudioOutputPolicy else alarm.audioOutputPolicy,
        monitoringProfile = if (alarm.usesDefaultMonitoringProfile) currentPreferences.defaultMonitoringProfile else alarm.monitoringProfile
    )

    fun onServiceForegroundConfirmed(): Job = scope.launch {
        mutex.withLock {
            for ((sessionId, engineState) in activeEngines) {
                if (engineState.session.state == SessionState.STARTING) {
                    repository.markSessionActive(sessionId)
                    activeEngines[sessionId] = engineState.copy(
                        session = engineState.session.copy(state = SessionState.ACTIVE)
                    )
                }
            }
            if (activeEngines.values.any { it.session.state == SessionState.ACTIVE }) {
                syncOperationalStateFromEngines(sessionStateOverride = SessionState.ACTIVE, message = "Buscando localização...")
                lastSequenceNumber = 0L
                startLocationObservation()
                startPeriodicTicker()
            }
            if (pendingAlertQueue.isNotEmpty() && currentlyPlayingAlertId == null) {
                processNextPendingAlert()
            }
        }
    }

    fun onServiceStartFailed(error: Throwable) {
        scope.launch {
            mutex.withLock {
                for ((sessionId, _) in activeEngines) {
                    repository.markSessionFailed(sessionId)
                }
                activeEngines.clear()
                occurrenceIdBySession.clear()
                activeAlertBySession.clear()
                occurrenceIdBySession.clear()
                cleanUpObservationJobs()
                _operationalState.update {
                    it.copy(
                        sessionState = SessionState.FAILED,
                        statusMessage = "Falha ao iniciar serviço de monitoramento: ${error.message}"
                    )
                }
            }
        }
    }

    fun onServiceDestroyed() {
        scope.launch {
            mutex.withLock {
                cleanUpObservationJobs()
                alertController.acknowledge("")

                for ((sessionId, _) in activeEngines) {
                    repository.markSessionInterrupted(sessionId)
                }
                activeEngines.values.forEach { state ->
                    recordHistory(state, HistoryEventType.INTERRUPTED, "Monitoramento interrompido pelo sistema")
                }
                activeEngines.clear()

                _operationalState.update {
                    it.copy(
                        sessionState = SessionState.INTERRUPTED,
                        statusMessage = "Viagem interrompida pelo sistema",
                        isAlarmPlaying = false,
                        activeOccurrences = emptyMap(),
                        activeTravelsCount = 0
                    )
                }
            }
        }
    }

    private suspend fun resumeJourneyInternal(sessionId: String): CommandResult {
        val caps = capabilityProvider.getCapabilities()
        if (!caps.canStartMonitoring) {
            return CommandResult.Rejected(caps.missingCapabilityReason ?: "Capacidades de sistema insuficientes")
        }

        val resumed = repository.resumeSession(sessionId)
            ?: return CommandResult.Rejected("Sessão não encontrada para retomada")

        val initialPrecautionCount = repository.getMaxPrecautionCycle(resumed.session.id)

        val engineState = EngineState(
            destination = resumed.destination,
            alarmDefinition = resumed.alarm,
            session = resumed.session,
            runtime = resumed.runtime,
            qualityState = QualityState.ACQUIRING,
            precautionEpisodeCount = initialPrecautionCount
        )

        activeEngines[resumed.session.id] = engineState
        occurrenceIdBySession[resumed.session.id] = resumed.occurrence?.id
        syncOperationalStateFromEngines(primarySessionId = resumed.session.id, message = "Retomando acompanhamento...")

        try {
            startForegroundMonitoringService()
        } catch (e: Exception) {
            activeEngines.remove(resumed.session.id)
            repository.markSessionFailed(resumed.session.id)
            return CommandResult.Error("Falha ao retomar serviço: ${e.message}", e)
        }

        return CommandResult.Success("Viagem retomada")
    }

    private suspend fun stopJourneyInternal(
        sessionId: String,
        historyEventType: HistoryEventType = HistoryEventType.INTERRUPTED,
        historyDetails: String = "Monitoramento encerrado pelo usuário"
    ): CommandResult {
        val engineState = activeEngines.remove(sessionId)
        val occurrenceId = occurrenceIdBySession.remove(sessionId)
        if (engineState == null && _operationalState.value.activeSession?.id != sessionId) {
            return CommandResult.Rejected("Identificador de sessão incompatível com as viagens ativas.")
        }

        repository.finishSession(sessionId)
        engineState?.let {
            recordHistory(it, historyEventType, historyDetails, occurrenceId)
        }
        pendingAlertQueue.removeAll { it.sessionId == sessionId }
        activeAlertBySession.remove(sessionId)

        if (currentlyPlayingAlertId != null) {
            val currentEvent = repository.getAlertEventById(currentlyPlayingAlertId!!)
            if (currentEvent?.sessionId == sessionId) {
                alertController.acknowledge(currentlyPlayingAlertId!!)
                currentlyPlayingAlertId = null
                processNextPendingAlert()
            }
        }

        if (activeEngines.isEmpty()) {
            cleanUpObservationJobs()
            alertController.acknowledge("")
            _operationalState.update {
                it.copy(
                    sessionState = SessionState.FINISHED,
                    statusMessage = "Viagem encerrada",
                    isAlarmPlaying = false,
                    isAlertActive = false,
                    currentDistanceMeters = null,
                    activeAlertEventId = null,
                    activeAlertEventType = null,
                    activeSession = null,
                    activeDestination = null,
                    activeAlarm = null,
                    activeOccurrences = emptyMap(),
                    activeTravelsCount = 0
                )
            }
            stopForegroundMonitoringService()
        } else {
            syncOperationalStateFromEngines(message = "Acompanhamento atualizado")
        }

        return CommandResult.Success("Viagem encerrada com sucesso")
    }

    private suspend fun stopOccurrenceInternal(command: MonitoringCommand.StopOccurrence): CommandResult {
        val sessionDetails = repository.getSessionByOccurrenceId(command.occurrenceId)
        return if (sessionDetails != null) {
            val result = stopJourneyInternal(
                sessionDetails.session.id,
                historyEventType = if (command.finalStatus == com.destino.app.core.model.OccurrenceStatus.EXPIRED) HistoryEventType.EXPIRED else HistoryEventType.INTERRUPTED,
                historyDetails = command.reason
            )
            result
        } else {
            CommandResult.Rejected("Ocorrência não encontrada em acompanhamento")
        }
    }

    private suspend fun acknowledgeAlertInternal(eventId: String): CommandResult {
        val event = repository.getAlertEventById(eventId)
            ?: return CommandResult.Rejected("Evento de alerta não encontrado no repositório.")

        alertController.acknowledge(eventId)
        repository.acknowledgeAlert(eventId)
        activeAlertBySession.remove(event.sessionId)
        pendingAlertQueue.removeAll { it.id == eventId }

        if (currentlyPlayingAlertId == eventId) {
            currentlyPlayingAlertId = null
            processNextPendingAlert()
        }

        val isCurrentActive = (_operationalState.value.activeAlertEventId == eventId)
        if (isCurrentActive) {
            _operationalState.update {
                it.copy(
                    isAlarmPlaying = false,
                    isAlertActive = pendingAlertQueue.isNotEmpty(),
                    alertState = AlertState.ACKNOWLEDGED,
                    activeAlertEventId = pendingAlertQueue.firstOrNull()?.id,
                    activeAlertEventType = pendingAlertQueue.firstOrNull()?.eventType,
                    pendingAlertQueue = pendingAlertQueue.toList(),
                    pendingAlertsCount = pendingAlertQueue.size
                )
            }
        } else {
            _operationalState.update {
                it.copy(
                    pendingAlertQueue = pendingAlertQueue.toList(),
                    pendingAlertsCount = pendingAlertQueue.size
                )
            }
        }

        val engineState = activeEngines[event.sessionId]
        if (event.eventType == AlertEventType.PROXIMITY) {
            val allowRearm = engineState?.alarmDefinition?.allowNewEntrySameWindow == true
            if (allowRearm && engineState != null) {
                val decision = engine.reduce(engineState, EngineEvent.UserAcknowledged(event.id, SystemClock.elapsedRealtime()))
                activeEngines[event.sessionId] = decision.newState
                repository.updateRuntime(decision.newState.runtime)
                val occId = occurrenceIdBySession[event.sessionId]
                if (occId != null) {
                    repository.updateOccurrenceArrivalCycle(occId, decision.newState.runtime.confirmationsCount)
                }
                recordHistory(
                    decision.newState,
                    HistoryEventType.ARRIVAL,
                    "Chegada reconhecida (Ciclo ${decision.newState.runtime.confirmationsCount}). Aguardando saída para rearme.",
                    occId
                )
                _operationalState.update {
                    it.copy(
                        statusMessage = "Alarme desligado. Aguardando saída do local para novo aviso.",
                        isAlarmPlaying = false,
                        isAlertActive = false,
                        alertState = AlertState.ACKNOWLEDGED,
                        activeAlertEventId = null,
                        activeAlertEventType = null
                    )
                }
                syncOperationalStateFromEngines()
            } else {
                _operationalState.update {
                    it.copy(statusMessage = "Alarme desligado. Você chegou ao destino!")
                }
                stopJourneyInternal(
                    event.sessionId,
                    historyEventType = HistoryEventType.ARRIVAL,
                    historyDetails = "Viagem concluída na chegada"
                )
            }
        } else {
            if (isCurrentActive) {
                _operationalState.update {
                    it.copy(statusMessage = "Aviso reconhecido. Monitoramento continua.")
                }
            }
        }

        return CommandResult.Success("Alerta reconhecido com sucesso")
    }

    private suspend fun testAlertInternal(): CommandResult {
        return when (val result = alertController.test()) {
            is DeliveryResult.Success -> CommandResult.Success("Teste de alerta iniciado (3 segundos)")
            is DeliveryResult.Failed -> CommandResult.Rejected(result.reason)
        }
    }

    private fun startLocationObservation() {
        if (locationJob?.isActive == true) return

        val locationConfig = buildLocationConfig()
        currentLocationConfig = locationConfig

        locationJob = scope.launch {
            locationSource.observe(locationConfig)
                .catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    mutex.withLock {
                        tickerJob?.cancel()
                        tickerJob = null

                        _operationalState.update {
                            it.copy(
                                qualityState = QualityState.BLOCKED,
                                sessionState = SessionState.INTERRUPTED,
                                statusMessage = "Falha no sensor de localização: ${e.message}. Monitoramento interrompido."
                            )
                        }

                        for ((sessionId, _) in activeEngines) {
                            repository.markSessionInterrupted(sessionId)
                        }
                    }
                }
                .collect { event ->
                    mutex.withLock {
                        try {
                            when (event) {
                                is LocationEvent.NewLocation -> handleLocationReading(event)
                                is LocationEvent.Unavailable -> {
                                    _operationalState.update {
                                        it.copy(
                                            qualityState = QualityState.BLOCKED,
                                            statusMessage = "Sinal bloqueado: ${event.reason}"
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            _operationalState.update {
                                it.copy(statusMessage = "Erro no processamento da posição: ${e.message}")
                            }
                        }
                    }
                }
        }
    }

    private fun buildLocationConfig(): LocationConfig {
        val contexts = activeEngines.values.map { state ->
            DestinationMonitoringContext(
                destinationId = state.destination.id,
                distanceMeters = state.lastDistanceMeters,
                radiusMeters = state.alarmDefinition.radiusMeters,
                uncertaintyMeters = state.lastUsefulReading?.accuracyMeters,
                speedMps = state.lastUsefulReading?.speedMps,
                isWaitingExit = state.runtime.waitingExit,
                isConfirming = state.pendingConfirmationReading != null || state.runtime.confirmationsCount > 0,
                isDegraded = state.qualityState == QualityState.DEGRADED,
                profile = state.alarmDefinition.monitoringProfile
            )
        }
        val customConfig = MonitoringProfileConfig(
            distantIntervalMillis = currentPreferences.customDistantIntervalSeconds.coerceIn(5, 60) * 1_000L,
            approachingIntervalMillis = currentPreferences.customApproachingIntervalSeconds.coerceIn(1, 30) * 1_000L,
            confirmingIntervalMillis = 1_000L,
            minUpdateIntervalMillis = 1_000L
        )
        return locationRequestPlanner?.planLocationRequest(contexts, customConfig)
            ?: LocationConfig(intervalMillis = 3_000L, minUpdateIntervalMillis = 1_000L)
    }

    private suspend fun handleLocationReading(reading: LocationEvent.NewLocation) {
        val monotonicNow = maxOf(SystemClock.elapsedRealtime(), reading.monotonicTimeMs)
        val continuityBroken = (lastSequenceNumber > 0L && reading.sequenceNumber > lastSequenceNumber + 1)
        lastSequenceNumber = reading.sequenceNumber

        val activeList = activeEngines.toList()
        for ((sessionId, currentState) in activeList) {
            val decision = engine.reduce(currentState, EngineEvent.LocationReceived(reading, monotonicNow, continuityBroken))
            applyEngineDecision(decision, reading, sessionId)
        }
        val plannedConfig = buildLocationConfig()
        val hasPendingConfirmation = activeEngines.values.any { it.pendingConfirmationReading != null }
        if (plannedConfig != currentLocationConfig && !hasPendingConfirmation) {
            locationJob?.cancel()
            locationJob = null
            startLocationObservation()
        }
    }

    protected open fun startPeriodicTicker() {
        if (tickerJob?.isActive == true) return

        tickerJob = scope.launch {
            while (isActive) {
                delay(2000L)
                mutex.withLock {
                    if (_operationalState.value.qualityState == QualityState.BLOCKED ||
                        _operationalState.value.sessionState == SessionState.INTERRUPTED ||
                        _operationalState.value.sessionState == SessionState.FINISHED
                    ) {
                        return@withLock
                    }

                    val monotonicNow = SystemClock.elapsedRealtime()
                    val activeList = activeEngines.toList()
                    for ((sessionId, currentState) in activeList) {
                        val decision = engine.reduce(currentState, EngineEvent.ClockTick(monotonicNow))
                        applyEngineDecision(decision, null, sessionId)
                    }

                    val plannedConfig = buildLocationConfig()
                    val hasPendingConfirmation = activeEngines.values.any { it.pendingConfirmationReading != null }
                    if (plannedConfig != currentLocationConfig && locationJob?.isActive == true && !hasPendingConfirmation) {
                        locationJob?.cancel()
                        locationJob = null
                        startLocationObservation()
                    }
                }
            }
        }
    }

    private suspend fun applyEngineDecision(decision: EngineDecision, reading: LocationEvent.NewLocation?, sessionId: String) {
        val state = decision.newState
        val destName = state.destination.name
        val hasArrivalEffect = decision.effects.any { it is EngineEffect.TriggerArrivalAlert }

        if (hasArrivalEffect) {
            val occurrenceId = occurrenceIdBySession[sessionId]
            if (occurrenceId != null) {
                val occ = repository.getOccurrenceById(occurrenceId)
                if (occ != null && occ.scheduledEndEpochMs > 0 && System.currentTimeMillis() > occ.scheduledEndEpochMs) {
                    recordHistory(state, HistoryEventType.EXPIRED, "Janela de horário expirou antes da chegada", occurrenceId)
                    stopJourneyInternal(sessionId, HistoryEventType.EXPIRED)
                    return
                }
            }

            val arrivalEffect = decision.effects.first { it is EngineEffect.TriggerArrivalAlert } as EngineEffect.TriggerArrivalAlert
            val alertEvent = try {
                repository.consumeAndRecordArrival(
                    sessionId = arrivalEffect.sessionId,
                    cycle = arrivalEffect.cycle,
                    destinationId = arrivalEffect.destinationId,
                    runtime = state.runtime
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }

            if (alertEvent == null) {
                _operationalState.update {
                    it.copy(statusMessage = "Erro ao registrar chegada no banco de dados. Nova tentativa na próxima leitura.")
                }
                return
            }

            activeEngines[sessionId] = state
            activeAlertBySession[sessionId] = alertEvent
            recordHistory(state, HistoryEventType.ARRIVAL, "Destino alcançado", occurrenceIdBySession[sessionId])

            // Chegada tem prioridade e interrompe um aviso de precaução.
            currentlyPlayingAlertId?.let { currentId ->
                val currentEvent = repository.getAlertEventById(currentId)
                if (currentEvent?.eventType == AlertEventType.PRECAUTION) {
                    alertController.acknowledge(currentId)
                    repository.acknowledgeAlert(currentId)
                    currentlyPlayingAlertId = null
                }
            }

            val deliveredImmediately: Boolean
            if (currentlyPlayingAlertId == null) {
                deliveredImmediately = true
                currentlyPlayingAlertId = alertEvent.id
                deliverAlertEvent(alertEvent, destName, state.alarmDefinition)
            } else {
                deliveredImmediately = false
                enqueueAlert(alertEvent)
                _operationalState.update {
                    it.copy(
                        pendingAlertQueue = pendingAlertQueue.toList(),
                        pendingAlertsCount = pendingAlertQueue.size
                    )
                }
            }

            _operationalState.update { current ->
                current.copy(
                    qualityState = state.qualityState,
                    currentDistanceMeters = state.lastDistanceMeters,
                    lastLocationUpdateEpochMs = state.lastUsefulReading?.epochTimestampMs ?: current.lastLocationUpdateEpochMs,
                    activeAlertEventId = if (deliveredImmediately) alertEvent.id else current.activeAlertEventId,
                    activeAlertEventType = if (deliveredImmediately) AlertEventType.PROXIMITY else current.activeAlertEventType,
                    isAlertActive = true,
                    statusMessage = "Seu destino está perto!"
                )
            }
            return
        }

        activeEngines[sessionId] = state
        repository.updateRuntime(state.runtime)

        syncOperationalStateFromEngines(reading = reading)

        // Processar efeitos
        for (effect in decision.effects) {
            when (effect) {
                is EngineEffect.TriggerArrivalAlert -> {}
                is EngineEffect.EmitPrecautionAlert -> {
                    if (!currentPreferences.isWeakSignalWarningEnabled) continue
                    val precautionEvent = repository.recordPrecaution(
                        sessionId = effect.sessionId,
                        cycle = effect.cycle,
                        message = effect.message
                    )
                    if (precautionEvent != null) {
                        activeAlertBySession[sessionId] = precautionEvent
                        recordHistory(state, HistoryEventType.PRECAUTION, effect.message, occurrenceIdBySession[sessionId])
                        val deliveredImmediately: Boolean
                        if (currentlyPlayingAlertId == null) {
                            deliveredImmediately = true
                            currentlyPlayingAlertId = precautionEvent.id
                            deliverAlertEvent(precautionEvent, destName, state.alarmDefinition)
                        } else {
                            deliveredImmediately = false
                            enqueueAlert(precautionEvent)
                            _operationalState.update {
                                it.copy(
                                    pendingAlertQueue = pendingAlertQueue.toList(),
                                    pendingAlertsCount = pendingAlertQueue.size
                                )
                            }
                        }

                        _operationalState.update {
                            it.copy(
                                activeAlertEventId = if (deliveredImmediately) precautionEvent.id else it.activeAlertEventId,
                                activeAlertEventType = if (deliveredImmediately) AlertEventType.PRECAUTION else it.activeAlertEventType,
                                isAlertActive = true,
                                statusMessage = effect.message
                            )
                        }
                    }
                }
                is EngineEffect.EnterWaitingExit -> {
                    _operationalState.update {
                        it.copy(waitingExitMessage = effect.message)
                    }
                }
                is EngineEffect.UpdateQuality -> {}
                is EngineEffect.ConsumeAlarm -> {}
            }
        }
    }

    private suspend fun deliverAlertEvent(alertEvent: AlertEvent, destinationName: String, alarm: AlarmDefinition) {
        val delivery = alertController.deliverWithOptions(
            event = alertEvent,
            destinationName = destinationName,
            isVibrationEnabled = alarm.isVibrationEnabled,
            soundSelection = alarm.soundSelection,
            vibrationPattern = alarm.vibrationPattern,
            audioOutputPolicy = alarm.audioOutputPolicy,
            maxDurationMinutes = currentPreferences.alarmAudioDurationMinutes,
            isGradualVolume = currentPreferences.isGradualVolumeEnabled
        )
        val isSuccess = delivery is DeliveryResult.Success
        val isAudioPlaying = (delivery as? DeliveryResult.Success)?.isAudioPlaying == true

        _operationalState.update {
            it.copy(
                isAlarmPlaying = isAudioPlaying,
                alertState = if (isSuccess) AlertState.PLAYING else AlertState.DELIVERY_FAILED
            )
        }

        repository.updateAlertStatus(
            eventId = alertEvent.id,
            state = if (isSuccess) AlertState.PLAYING else AlertState.DELIVERY_FAILED,
            deliveredAtEpochMs = System.currentTimeMillis(),
            failureReason = (delivery as? DeliveryResult.Failed)?.reason
        )

        val channels = buildString {
            if (alarm.soundSelection.type != SoundSourceType.SILENT) append("AUDIO,")
            if (alarm.isVibrationEnabled) append("VIBRATION,")
            append("NOTIFICATION")
        }
        val failureReason = (delivery as? DeliveryResult.Failed)?.reason
        repository.recordDeliveryAttempt(alertEvent.id, channels, failureReason)

        if (!isSuccess) {
            activeEngines[alertEvent.sessionId]?.let { state ->
                recordHistory(
                    state,
                    HistoryEventType.DELIVERY_FAILED,
                    (delivery as DeliveryResult.Failed).reason
                )
            }
        }
        if (!isSuccess && currentlyPlayingAlertId == alertEvent.id) {
            currentlyPlayingAlertId = null
            processNextPendingAlert()
        }
    }

    private suspend fun processNextPendingAlert() {
        if (pendingAlertQueue.isEmpty() || currentlyPlayingAlertId != null) return

        val nextEvent = pendingAlertQueue.removeAt(0)
        val currentDbEvent = repository.getAlertEventById(nextEvent.id)
        if (currentDbEvent == null || currentDbEvent.state == AlertState.ACKNOWLEDGED) {
            _operationalState.update {
                it.copy(
                    pendingAlertQueue = pendingAlertQueue.toList(),
                    pendingAlertsCount = pendingAlertQueue.size
                )
            }
            processNextPendingAlert()
            return
        }

        val engineState = activeEngines[nextEvent.sessionId]
        val destName = engineState?.destination?.name ?: "Destino"
        val alarm = engineState?.alarmDefinition ?: AlarmDefinition(UUID.randomUUID().toString(), "dest", 500.0)

        currentlyPlayingAlertId = nextEvent.id
        _operationalState.update {
            it.copy(
                activeAlertEventId = nextEvent.id,
                activeAlertEventType = nextEvent.eventType,
                isAlertActive = true,
                pendingAlertQueue = pendingAlertQueue.toList(),
                pendingAlertsCount = pendingAlertQueue.size
            )
        }
        deliverAlertEvent(nextEvent, destName, alarm)
    }

    private suspend fun recordHistory(
        state: EngineState,
        eventType: HistoryEventType,
        details: String,
        occurrenceId: String? = occurrenceIdBySession[state.session.id]
    ) {
        val fullDetails = "${details}|geo:${state.destination.coordinates.latitude},${state.destination.coordinates.longitude},${state.alarmDefinition.radiusMeters}"
        historyRepository?.recordEvent(
            HistoryRecord(
                id = UUID.randomUUID().toString(),
                occurrenceId = occurrenceId,
                alarmName = state.alarmDefinition.name,
                destinationName = state.destination.name,
                eventType = eventType,
                details = fullDetails
            )
        )
    }

    private fun syncOperationalStateFromEngines(
        primarySessionId: String? = null,
        sessionStateOverride: SessionState? = null,
        message: String? = null,
        reading: LocationEvent.NewLocation? = null
    ) {
        if (activeEngines.isEmpty()) return

        // O motor primário para exibição no estado geral é ou o passado explicitamente, ou o mais próximo do destino
        val primaryEngine = (primarySessionId?.let { activeEngines[it] }
            ?: activeEngines.values.minByOrNull { it.lastDistanceMeters ?: Double.MAX_VALUE }
            ?: activeEngines.values.first())

        val occurrencesMap = activeEngines.mapValues { (sessionId, eng) ->
            val activeAlert = activeAlertBySession[sessionId]
            ActiveOccurrenceState(
                occurrenceId = occurrenceIdBySession[sessionId],
                session = eng.session,
                destination = eng.destination,
                alarm = eng.alarmDefinition,
                sessionState = sessionStateOverride ?: eng.session.state,
                qualityState = eng.qualityState,
                currentDistanceMeters = eng.lastDistanceMeters,
                isAlertActive = activeAlert != null,
                activeAlertEventId = activeAlert?.id,
                activeAlertEventType = activeAlert?.eventType
            )
        }

        _operationalState.update { current ->
            current.copy(
                sessionState = sessionStateOverride ?: primaryEngine.session.state,
                qualityState = primaryEngine.qualityState,
                activeDestination = primaryEngine.destination,
                activeAlarm = primaryEngine.alarmDefinition,
                activeSession = primaryEngine.session.copy(state = sessionStateOverride ?: primaryEngine.session.state),
                currentDistanceMeters = primaryEngine.lastDistanceMeters,
                lastLocationUpdateEpochMs = primaryEngine.lastUsefulReading?.epochTimestampMs ?: current.lastLocationUpdateEpochMs,
                activeOccurrences = occurrencesMap,
                activeTravelsCount = activeEngines.size,
                statusMessage = message ?: when {
                    primaryEngine.runtime.waitingExit -> "Aguardando saída da área inicial..."
                    primaryEngine.qualityState == QualityState.GOOD -> if (activeEngines.size > 1) "Acompanhando ${activeEngines.size} destinos" else "Monitorando"
                    primaryEngine.qualityState == QualityState.ACQUIRING -> "Buscando localização..."
                    primaryEngine.qualityState == QualityState.DEGRADED -> "Sinal fraco (distância desatualizada)"
                    primaryEngine.qualityState == QualityState.BLOCKED -> "Sinal de localização bloqueado"
                    else -> current.statusMessage
                },
                waitingExitMessage = if (primaryEngine.runtime.waitingExit) "Você já está na área do aviso." else null
            )
        }
    }

    protected open fun cleanUpObservationJobs() {
        locationJob?.cancel()
        locationJob = null
        tickerJob?.cancel()
        tickerJob = null
    }

    protected open fun startForegroundMonitoringService() {
        val ctx = context ?: return
        val intent = Intent(ctx, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    protected open fun stopForegroundMonitoringService() {
        val ctx = context ?: return
        val intent = Intent(ctx, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_STOP
        }
        ctx.startService(intent)
    }
}
