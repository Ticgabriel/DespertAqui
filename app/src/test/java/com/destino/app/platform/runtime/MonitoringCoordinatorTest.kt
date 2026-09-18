package com.destino.app.platform.runtime

import android.content.Context
import com.destino.app.data.local.ActiveSessionDetails
import com.destino.app.core.model.AlarmDefinition
import com.destino.app.core.model.AlarmRuntime
import com.destino.app.core.model.AlertController
import com.destino.app.core.model.AlertEvent
import com.destino.app.core.model.AlertEventType
import com.destino.app.core.model.AlertState
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.DeliveryResult
import com.destino.app.core.model.Destination
import com.destino.app.core.model.LocationConfig
import com.destino.app.core.model.LocationEvent
import com.destino.app.core.model.LocationSource
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringSession
import com.destino.app.core.model.QualityState
import com.destino.app.core.model.SessionState
import com.destino.app.data.local.AlarmRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringCoordinatorTest {

    private class FakeAlarmRepository : AlarmRepository {
        var sessions = mutableMapOf<String, MonitoringSession>()
        var runtimes = mutableMapOf<String, AlarmRuntime>()
        var alerts = mutableMapOf<String, AlertEvent>()
        var destinations = mutableMapOf<String, Destination>()
        var alarms = mutableMapOf<String, AlarmDefinition>()

        var unfinishedToReturn: ActiveSessionDetails? = null
        var lastPersistedRuntime: AlarmRuntime? = null
        var runtimeUpdateCount = 0
        var arrivalCallCount = 0
        var shouldFailArrivalTransaction = false

        override fun observeActiveSession(): Flow<ActiveSessionDetails?> = emptyFlow()
        override suspend fun getActiveSessionDetails(): ActiveSessionDetails? = null

        override suspend fun getUnfinishedSessionDetails(): ActiveSessionDetails? {
            if (unfinishedToReturn != null) return unfinishedToReturn
            val s = sessions.values.firstOrNull { it.state == SessionState.STARTING || it.state == SessionState.ACTIVE } ?: return null
            val dest = destinations[s.destinationId] ?: return null
            val alarm = alarms[s.alarmDefinitionId] ?: return null
            val runtime = runtimes[s.id] ?: AlarmRuntime("run", s.id)
            val pendingAlert = alerts.values.firstOrNull { it.sessionId == s.id && it.state == AlertState.PENDING }
            return ActiveSessionDetails(dest, alarm, s, runtime, pendingAlert)
        }

        override suspend fun getLatestInterruptedSessionDetails(): ActiveSessionDetails? = null
        override fun observeLatestDestination(): Flow<Destination?> = emptyFlow()
        override suspend fun getLatestDestination(): Destination? = null
        override suspend fun saveDestinationAndAlarm(destination: Destination, alarm: AlarmDefinition) {}

        override suspend fun startSession(destination: Destination, alarm: AlarmDefinition): ActiveSessionDetails {
            val sessionId = UUID.randomUUID().toString()
            val session = MonitoringSession(
                id = sessionId,
                destinationId = destination.id,
                alarmDefinitionId = alarm.id,
                state = SessionState.STARTING,
                startedAtEpochMs = 1000L
            )
            val runtime = AlarmRuntime(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                isArmed = true
            )
            sessions[sessionId] = session
            runtimes[sessionId] = runtime
            destinations[destination.id] = destination
            alarms[alarm.id] = alarm

            return ActiveSessionDetails(destination, alarm, session, runtime)
        }

        override suspend fun markSessionActive(sessionId: String) {
            sessions[sessionId]?.let {
                sessions[sessionId] = it.copy(state = SessionState.ACTIVE)
            }
        }

        override suspend fun finishSession(sessionId: String) {
            sessions[sessionId]?.let {
                sessions[sessionId] = it.copy(state = SessionState.FINISHED, finishedAtEpochMs = 2000L)
            }
        }

        override suspend fun markActiveSessionsInterrupted() {
            sessions.forEach { (id, s) ->
                if (s.state == SessionState.STARTING || s.state == SessionState.ACTIVE) {
                    sessions[id] = s.copy(state = SessionState.INTERRUPTED)
                }
            }
        }

        override suspend fun markSessionInterrupted(sessionId: String) {
            sessions[sessionId]?.let {
                sessions[sessionId] = it.copy(state = SessionState.INTERRUPTED)
            }
        }

        override suspend fun markSessionFailed(sessionId: String) {
            sessions[sessionId]?.let {
                sessions[sessionId] = it.copy(state = SessionState.FAILED)
            }
        }

        override suspend fun resumeSession(sessionId: String): ActiveSessionDetails? {
            val session = sessions[sessionId] ?: return null
            val dest = destinations[session.destinationId] ?: return null
            val alarm = alarms[session.alarmDefinitionId] ?: return null
            val runtime = runtimes[sessionId] ?: AlarmRuntime(UUID.randomUUID().toString(), sessionId)
            val resumed = session.copy(state = SessionState.STARTING)
            sessions[sessionId] = resumed
            return ActiveSessionDetails(dest, alarm, resumed, runtime)
        }

        override suspend fun consumeAndRecordArrival(
            sessionId: String,
            cycle: Int,
            destinationId: String,
            runtime: AlarmRuntime
        ): AlertEvent? {
            arrivalCallCount++
            if (shouldFailArrivalTransaction) {
                return null
            }
            val disarmed = runtime.copy(isArmed = false)
            runtimes[sessionId] = disarmed
            lastPersistedRuntime = disarmed

            alarms[destinationId]?.let {
                alarms[destinationId] = it // consumed
            }

            val eventId = UUID.randomUUID().toString()
            val event = AlertEvent(
                id = eventId,
                sessionId = sessionId,
                cycle = cycle,
                eventType = AlertEventType.PROXIMITY,
                state = AlertState.PENDING,
                createdAtEpochMs = 1500L
            )
            alerts[eventId] = event
            return event
        }

        override suspend fun recordPrecaution(sessionId: String, cycle: Int, message: String): AlertEvent? {
            val eventId = UUID.randomUUID().toString()
            val event = AlertEvent(
                id = eventId,
                sessionId = sessionId,
                cycle = cycle,
                eventType = AlertEventType.PRECAUTION,
                state = AlertState.PENDING,
                createdAtEpochMs = 1500L
            )
            alerts[eventId] = event
            return event
        }

        override suspend fun getMaxPrecautionCycle(sessionId: String): Int = 0
        override suspend fun getAlertEventById(eventId: String): AlertEvent? = alerts[eventId]

        override suspend fun updateAlertStatus(
            eventId: String,
            state: AlertState,
            deliveredAtEpochMs: Long?,
            failureReason: String?
        ) {
            alerts[eventId]?.let {
                alerts[eventId] = it.copy(state = state)
            }
        }

        override suspend fun acknowledgeAlert(eventId: String) {
            alerts[eventId]?.let {
                alerts[eventId] = it.copy(state = AlertState.ACKNOWLEDGED, acknowledgedAtEpochMs = 1800L)
            }
        }

        override suspend fun updateRuntime(runtime: AlarmRuntime) {
            if (lastPersistedRuntime == runtime) return
            lastPersistedRuntime = runtime
            runtimes[runtime.sessionId] = runtime
            runtimeUpdateCount++
        }

        override fun observeAllActiveSessions(): Flow<List<ActiveSessionDetails>> = emptyFlow()
        override suspend fun getAllActiveSessionDetails(): List<ActiveSessionDetails> = emptyList()
        override suspend fun getDestinationById(id: String): Destination? = destinations[id]
        override fun observeAllAlarms(): Flow<List<AlarmDefinition>> = emptyFlow()
        override suspend fun getAlarmById(id: String): AlarmDefinition? = alarms[id]
        override suspend fun getAllEnabledAlarms(): List<AlarmDefinition> = alarms.values.filter { it.isEnabled }
        override suspend fun updateAlarm(alarm: AlarmDefinition) { alarms[alarm.id] = alarm }
        override suspend fun setAlarmEnabled(id: String, isEnabled: Boolean) { alarms[id]?.let { alarms[id] = it.copy(isEnabled = isEnabled) } }
        override suspend fun deleteAlarm(id: String) { alarms.remove(id) }
        override suspend fun startOccurrenceSession(occurrence: com.destino.app.core.model.AlarmOccurrence, destination: Destination, alarm: AlarmDefinition): ActiveSessionDetails =
            startSession(destination, alarm).copy(occurrence = occurrence)
        override suspend fun getSessionByOccurrenceId(occurrenceId: String): ActiveSessionDetails? = null
        override suspend fun getRecoverableSessionDetails(): List<ActiveSessionDetails> =
            sessions.values
                .filter { it.state == SessionState.STARTING || it.state == SessionState.ACTIVE }
                .mapNotNull { session ->
                    val destination = destinations[session.destinationId] ?: return@mapNotNull null
                    val alarm = alarms[session.alarmDefinitionId] ?: return@mapNotNull null
                    val runtime = runtimes[session.id] ?: AlarmRuntime("run-${session.id}", session.id)
                    val pendingAlert = alerts.values.firstOrNull {
                        it.sessionId == session.id && it.state == AlertState.PENDING
                    }
                    ActiveSessionDetails(destination, alarm, session, runtime, pendingAlert)
                }
        override suspend fun getActiveSessionsForAlarm(alarmId: String): List<ActiveSessionDetails> = emptyList()
        override suspend fun getOccurrenceById(id: String): com.destino.app.core.model.AlarmOccurrence? = null
        override suspend fun recordDeliveryAttempt(eventId: String, channelsDelivered: String, failureReason: String?) {}
        override suspend fun updateOccurrenceArrivalCycle(occurrenceId: String, cycle: Int) {}
    }

    private class FakeAlertController : AlertController {
        override var onPlaybackError: ((eventId: String, playbackAttemptId: String, errorMsg: String) -> Unit)? = null
        var lastDeliveredEvent: AlertEvent? = null
        var lastAcknowledgedId: String? = null
        var lastVibrationEnabled: Boolean = true
        var shouldDeliveryFail = false

        override suspend fun deliver(
            event: AlertEvent,
            destinationName: String,
            isVibrationEnabled: Boolean
        ): DeliveryResult {
            lastDeliveredEvent = event
            lastVibrationEnabled = isVibrationEnabled
            return if (shouldDeliveryFail) {
                DeliveryResult.Failed("Falha de áudio simulada", recoverable = true, isNotificationPosted = true, isVibrating = isVibrationEnabled)
            } else {
                DeliveryResult.Success(isAudioPlaying = true, isNotificationPosted = true, isVibrating = isVibrationEnabled)
            }
        }

        override suspend fun acknowledge(eventId: String) {
            lastAcknowledgedId = eventId
        }

        override suspend fun test(): DeliveryResult = DeliveryResult.Success()
    }

    private class FakeLocationSource(
        val flow: MutableSharedFlow<LocationEvent> = MutableSharedFlow(replay = 10)
    ) : LocationSource {
        var errorToThrow: Throwable? = null
        override fun observe(config: LocationConfig): Flow<LocationEvent> = flow {
            val err = errorToThrow
            if (err != null) {
                throw err
            }
            emitAll(flow)
        }
    }

    private class FakeCapabilityProvider : CapabilityProvider() {
        var currentCapabilities = SystemCapabilities(
            hasFineLocationPermission = true,
            hasCoarseLocationPermission = true,
            hasNotificationPermission = true,
            areNotificationsBlocked = false,
            isLocationProviderEnabled = true,
            isPlayServicesAvailable = true
        )

        override fun getCapabilities(): SystemCapabilities = currentCapabilities
    }

    private class TestableMonitoringCoordinator(
        repository: AlarmRepository,
        locationSource: LocationSource,
        alertController: AlertController,
        capabilityProvider: CapabilityProvider,
        dispatcher: CoroutineDispatcher
    ) : MonitoringCoordinatorImpl(
        repository = repository,
        locationSource = locationSource,
        alertController = alertController,
        capabilityProvider = capabilityProvider,
        dispatcher = dispatcher
    ) {
        var startServiceCallCount = 0
        var stopServiceCallCount = 0
        var shouldStartServiceThrow = false

        override fun startForegroundMonitoringService() {
            if (shouldStartServiceThrow) {
                throw SecurityException("startForegroundService not allowed")
            }
            startServiceCallCount++
        }

        override fun stopForegroundMonitoringService() {
            stopServiceCallCount++
        }

        override fun startPeriodicTicker() {
            // No-op nos testes unitários para evitar loop infinito com delay no UnconfinedTestDispatcher
        }
    }

    private fun TestScope.createCoordinator(
        repo: FakeAlarmRepository,
        alertController: FakeAlertController = FakeAlertController(),
        capsProvider: FakeCapabilityProvider = FakeCapabilityProvider(),
        locationSource: FakeLocationSource = FakeLocationSource()
    ): TestableMonitoringCoordinator {
        return TestableMonitoringCoordinator(
            repository = repo,
            locationSource = locationSource,
            alertController = alertController,
            capabilityProvider = capsProvider,
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )
    }

    @Test
    fun testSessionLifecycleStartingToActiveAndFinish() = runTest {
        val repo = FakeAlarmRepository()
        val coordinator = createCoordinator(repo)

        val result = coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-1",
                destinationName = "Destino Teste",
                coordinates = Coordinates(-23.550520, -46.633308),
                radiusMeters = 500.0
            )
        )

        assertTrue(result is CommandResult.Success)
        val activeSession = coordinator.operationalState.value.activeSession
        assertNotNull(activeSession)
        assertEquals(SessionState.STARTING, activeSession?.state)
        assertEquals(SessionState.STARTING, repo.sessions[activeSession?.id]?.state)

        // Simula confirmação do serviço em primeiro plano
        coordinator.onServiceForegroundConfirmed().join()

        assertEquals(SessionState.ACTIVE, coordinator.operationalState.value.sessionState)
        assertEquals(SessionState.ACTIVE, repo.sessions[activeSession?.id]?.state)

        // Encerrar viagem
        val stopResult = coordinator.execute(
            MonitoringCommand.StopJourney(
                commandId = "cmd-stop",
                sessionId = activeSession!!.id
            )
        )
        assertTrue(stopResult is CommandResult.Success)
        assertEquals(SessionState.FINISHED, coordinator.operationalState.value.sessionState)
        assertEquals(SessionState.FINISHED, repo.sessions[activeSession.id]?.state)
        assertEquals(1, coordinator.stopServiceCallCount)
    }

    @Test
    fun testReconciliationOfUnfinishedSessionMarksInterrupted() = runTest {
        val repo = FakeAlarmRepository()
        val unfinishedSession = MonitoringSession(
            id = "sess-orphan",
            destinationId = "dest-1",
            alarmDefinitionId = "alarm-1",
            state = SessionState.STARTING,
            startedAtEpochMs = 1000L
        )
        repo.sessions[unfinishedSession.id] = unfinishedSession
        repo.destinations["dest-1"] = Destination("dest-1", "Destino Antigo", Coordinates(-23.550520, -46.633308))
        repo.alarms["alarm-1"] = AlarmDefinition("alarm-1", "dest-1", 500.0, true)
        repo.runtimes[unfinishedSession.id] = AlarmRuntime("run-1", unfinishedSession.id, isArmed = true, waitingExit = false)

        val coordinator = createCoordinator(repo)

        // Aguarda a reconciliação assíncrona do init
        withTimeout(2000) {
            coordinator.operationalState.first { it.sessionState == SessionState.INTERRUPTED }
        }

        assertEquals(SessionState.INTERRUPTED, coordinator.operationalState.value.sessionState)
        assertEquals(SessionState.INTERRUPTED, repo.sessions["sess-orphan"]?.state)
        assertEquals("dest-1", coordinator.operationalState.value.activeDestination?.id)
    }

    @Test
    fun testAcknowledgeArrivalByEventTypeClosesJourneyEvenIfAudioNotPlaying() = runTest {
        val repo = FakeAlarmRepository()
        val alertController = FakeAlertController()
        val coordinator = createCoordinator(repo, alertController)

        coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-1",
                destinationName = "Destino",
                coordinates = Coordinates(-23.550520, -46.633308),
                radiusMeters = 500.0
            )
        )
        coordinator.onServiceForegroundConfirmed().join()

        // Simula evento de proximidade/chegada
        val arrival = repo.consumeAndRecordArrival(
            sessionId = coordinator.operationalState.value.activeSession!!.id,
            cycle = 1,
            destinationId = "dest-1",
            runtime = AlarmRuntime("r-1", coordinator.operationalState.value.activeSession!!.id, isArmed = false, waitingExit = false)
        )
        assertNotNull(arrival)

        // Reconhece o evento de chegada mesmo que o áudio não esteja tocando
        val ackResult = coordinator.execute(
            MonitoringCommand.AcknowledgeAlert(
                commandId = "cmd-ack-arr",
                eventId = arrival!!.id
            )
        )

        assertTrue(ackResult is CommandResult.Success)
        assertEquals(SessionState.FINISHED, coordinator.operationalState.value.sessionState)
    }

    @Test
    fun testAcknowledgePrecautionDoesNotCloseJourney() = runTest {
        val repo = FakeAlarmRepository()
        val alertController = FakeAlertController()
        val coordinator = createCoordinator(repo, alertController)

        coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-1",
                destinationName = "Destino",
                coordinates = Coordinates(-23.550520, -46.633308),
                radiusMeters = 500.0
            )
        )
        coordinator.onServiceForegroundConfirmed().join()

        val activeSessionId = coordinator.operationalState.value.activeSession!!.id
        // Simula evento de precaução associado à sessão ativa
        val precaution = repo.recordPrecaution(activeSessionId, 1, "Sinal degradado")
        assertNotNull(precaution)

        val ackResult = coordinator.execute(
            MonitoringCommand.AcknowledgeAlert(
                commandId = "cmd-ack",
                eventId = precaution!!.id
            )
        )

        assertTrue(ackResult is CommandResult.Success)
        assertEquals(SessionState.ACTIVE, coordinator.operationalState.value.sessionState)
        assertEquals(AlertState.ACKNOWLEDGED, repo.alerts[precaution.id]?.state)
        assertEquals(precaution.id, alertController.lastAcknowledgedId)
    }

    @Test
    fun testStopJourneyRejectsMismatchedSessionId() = runTest {
        val repo = FakeAlarmRepository()
        val coordinator = createCoordinator(repo)

        coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-1",
                destinationName = "Destino",
                coordinates = Coordinates(-23.550520, -46.633308),
                radiusMeters = 500.0
            )
        )

        val badStop = coordinator.execute(
            MonitoringCommand.StopJourney(
                commandId = "cmd-bad",
                sessionId = "wrong-session-id"
            )
        )

        assertTrue(badStop is CommandResult.Rejected)
        assertEquals(SessionState.STARTING, coordinator.operationalState.value.sessionState)
    }

    @Test
    fun testSynchronousServiceStartFailureResetsStateToFailed() = runTest {
        val repo = FakeAlarmRepository()
        val coordinator = createCoordinator(repo)
        coordinator.shouldStartServiceThrow = true

        val startResult = coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-fail",
                destinationName = "Destino",
                coordinates = Coordinates(-23.550520, -46.633308),
                radiusMeters = 500.0
            )
        )

        assertTrue(startResult is CommandResult.Error)
        assertEquals(SessionState.FAILED, coordinator.operationalState.value.sessionState)
    }

    @Test
    fun testArrivalTransactionFailurePreservesArmedStateAndSubsequentSuccessDeliversAlertOnce() = runTest {
        val repo = FakeAlarmRepository()
        val alertController = FakeAlertController()
        val locationSource = FakeLocationSource()
        val coordinator = createCoordinator(repo, alertController, locationSource = locationSource)

        val destCoords = Coordinates(-23.550520, -46.633308)
        coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-arrive",
                destinationName = "Destino Chegada",
                coordinates = destCoords,
                radiusMeters = 500.0
            )
        )
        coordinator.onServiceForegroundConfirmed().join()

        // 1ª leitura útil: fora do raio (~554m) para armar WAITING_ARRIVAL
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.555500, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 1000L,
                sequenceNumber = 1L
            )
        )
        kotlinx.coroutines.delay(50)

        // Simula falha na transação de chegada no banco (Item 1 da avaliação)
        repo.shouldFailArrivalTransaction = true

        // 2ª leitura: dentro do raio (~442m, deslocamento plausível de ~112m em 4s = 28 m/s)
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.554500, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 5000L,
                sequenceNumber = 2L
            )
        )
        kotlinx.coroutines.delay(50)

        // Como a transação falhou, o alerta NÃO deve ter sido entregue e o estado continua em vigilância
        assertEquals(1, repo.arrivalCallCount)
        assertFalse(coordinator.operationalState.value.isAlertActive)
        assertEquals(null, alertController.lastDeliveredEvent)

        // Agora restaura o banco de dados
        repo.shouldFailArrivalTransaction = false

        // 3ª leitura: posição consecutiva no raio tenta novamente a transação com sucesso
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.554300, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 7000L,
                sequenceNumber = 3L
            )
        )

        withTimeout(2000) {
            coordinator.operationalState.first { it.isAlertActive }
        }

        // Desta vez a transação teve sucesso, o alerta foi entregue e o estado confirma chegada
        assertEquals(2, repo.arrivalCallCount)
        assertTrue(coordinator.operationalState.value.isAlertActive)
        assertNotNull(alertController.lastDeliveredEvent)
        assertEquals(AlertEventType.PROXIMITY, alertController.lastDeliveredEvent?.eventType)
    }

    @Test
    fun testTerminalLocationFailureCancelsTickerAndMarksSessionInterrupted() = runTest {
        val repo = FakeAlarmRepository()
        val locationSource = FakeLocationSource()
        locationSource.errorToThrow = RuntimeException("Sensor GPS desconectado")
        val coordinator = createCoordinator(repo, locationSource = locationSource)

        coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-gps-fail",
                destinationName = "Destino",
                coordinates = Coordinates(-23.550520, -46.633308),
                radiusMeters = 500.0
            )
        )
        coordinator.onServiceForegroundConfirmed().join()

        withTimeout(2000) {
            coordinator.operationalState.first { it.sessionState == SessionState.INTERRUPTED }
        }

        // A falha do fluxo deve ter colocado a sessão em INTERRUPTED e qualidade BLOCKED
        assertEquals(QualityState.BLOCKED, coordinator.operationalState.value.qualityState)
        assertEquals(SessionState.INTERRUPTED, coordinator.operationalState.value.sessionState)
        assertTrue(coordinator.operationalState.value.statusMessage.contains("Falha no sensor"))
    }

    @Test
    fun testAudioPlaybackErrorWithMatchingEventUpdatesStateAndDatabase() = runTest {
        val repo = FakeAlarmRepository()
        val alertController = FakeAlertController()
        val locationSource = FakeLocationSource()
        val coordinator = createCoordinator(repo, alertController, locationSource = locationSource)

        val destCoords = Coordinates(-23.550520, -46.633308)
        coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-audio",
                destinationName = "Destino",
                coordinates = destCoords,
                radiusMeters = 500.0
            )
        )
        coordinator.onServiceForegroundConfirmed().join()

        // Provoca chegada para ter um alerta ativo de fato
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.555500, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 1000L,
                sequenceNumber = 1L
            )
        )
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.554500, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 5000L,
                sequenceNumber = 2L
            )
        )
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.554300, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 7000L,
                sequenceNumber = 3L
            )
        )

        withTimeout(2000) {
            coordinator.operationalState.first { it.isAlertActive }
        }

        val activeEventId = coordinator.operationalState.value.activeAlertEventId!!

        // Erro atrasado com ID divergente NÃO afeta o alerta ativo
        alertController.onPlaybackError?.invoke("outro-evento-antigo", "att-old", "Erro antigo")
        kotlinx.coroutines.delay(50)
        assertEquals(AlertState.PLAYING, repo.alerts[activeEventId]?.state)
        assertEquals(AlertState.PLAYING, coordinator.operationalState.value.alertState)

        // Erro com o ID correto atualiza o alerta ativo para DELIVERY_FAILED
        alertController.onPlaybackError?.invoke(activeEventId, "att-1", "Codec falhou")
        withTimeout(2000) {
            coordinator.operationalState.first { it.alertState == AlertState.DELIVERY_FAILED }
        }

        assertEquals(AlertState.DELIVERY_FAILED, repo.alerts[activeEventId]?.state)
        assertEquals(AlertState.DELIVERY_FAILED, coordinator.operationalState.value.alertState)
        assertFalse(coordinator.operationalState.value.isAlarmPlaying)
    }

    @Test
    fun testVibrationPreferencePassedToAlertController() = runTest {
        val repo = FakeAlarmRepository()
        val alertController = FakeAlertController()
        val locationSource = FakeLocationSource()
        val coordinator = createCoordinator(repo, alertController, locationSource = locationSource)

        val destCoords = Coordinates(-23.550520, -46.633308)
        // Inicia com vibração DESABILITADA
        coordinator.execute(
            MonitoringCommand.StartJourney(
                commandId = "cmd-no-vib",
                destinationName = "Destino Sem Vibrar",
                coordinates = destCoords,
                radiusMeters = 500.0,
                isVibrationEnabled = false
            )
        )
        coordinator.onServiceForegroundConfirmed().join()

        // Partida fora do raio (~554m)
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.555500, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 1000L,
                sequenceNumber = 1L
            )
        )
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.554500, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 5000L,
                sequenceNumber = 2L
            )
        )
        locationSource.flow.emit(
            LocationEvent.NewLocation(
                coordinates = Coordinates(-23.554300, -46.633308),
                accuracyMeters = 10f,
                monotonicTimeMs = 7000L,
                sequenceNumber = 3L
            )
        )

        withTimeout(2000) {
            coordinator.operationalState.first { it.isAlertActive }
        }

        assertNotNull(alertController.lastDeliveredEvent)
        // Verifica que o AlertController recebeu a preferência real de vibração desabilitada
        assertFalse(alertController.lastVibrationEnabled)
    }
}
