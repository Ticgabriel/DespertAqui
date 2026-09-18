package com.destino.app.data.local

import androidx.room.withTransaction
import com.destino.app.core.model.AlarmDefinition
import com.destino.app.core.model.AlarmOccurrence
import com.destino.app.core.model.AlarmRuntime
import com.destino.app.core.model.AlertEvent
import com.destino.app.core.model.AlertEventType
import com.destino.app.core.model.AlertState
import com.destino.app.core.model.Destination
import com.destino.app.core.model.MonitoringSession
import com.destino.app.core.model.ScheduleRuleType
import com.destino.app.core.model.SessionState
import com.destino.app.data.local.entities.AlarmRuntimeEntity
import com.destino.app.data.local.entities.AlertDeliveryAttemptEntity
import com.destino.app.data.local.entities.AlertEventEntity
import com.destino.app.data.local.entities.MonitoringSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class ActiveSessionDetails(
    val destination: Destination,
    val alarm: AlarmDefinition,
    val session: MonitoringSession,
    val runtime: AlarmRuntime,
    val pendingAlert: AlertEvent? = null,
    val occurrence: AlarmOccurrence? = null
)

interface AlarmRepository {
    fun observeActiveSession(): Flow<ActiveSessionDetails?>
    fun observeAllActiveSessions(): Flow<List<ActiveSessionDetails>>
    suspend fun getActiveSessionDetails(): ActiveSessionDetails?
    suspend fun getAllActiveSessionDetails(): List<ActiveSessionDetails>
    suspend fun getRecoverableSessionDetails(): List<ActiveSessionDetails>
    suspend fun getActiveSessionsForAlarm(alarmId: String): List<ActiveSessionDetails>
    suspend fun getUnfinishedSessionDetails(): ActiveSessionDetails?
    suspend fun getLatestInterruptedSessionDetails(): ActiveSessionDetails?
    fun observeLatestDestination(): Flow<Destination?>
    suspend fun getLatestDestination(): Destination?
    suspend fun getDestinationById(id: String): Destination?
    fun observeAllAlarms(): Flow<List<AlarmDefinition>>
    suspend fun getAlarmById(id: String): AlarmDefinition?
    suspend fun getAllEnabledAlarms(): List<AlarmDefinition>
    suspend fun saveDestinationAndAlarm(destination: Destination, alarm: AlarmDefinition)
    suspend fun updateAlarm(alarm: AlarmDefinition)
    suspend fun setAlarmEnabled(id: String, isEnabled: Boolean)
    suspend fun deleteAlarm(id: String)
    suspend fun startSession(destination: Destination, alarm: AlarmDefinition): ActiveSessionDetails
    suspend fun startOccurrenceSession(occurrence: AlarmOccurrence, destination: Destination, alarm: AlarmDefinition): ActiveSessionDetails
    suspend fun getOccurrenceById(id: String): AlarmOccurrence?
    suspend fun getSessionByOccurrenceId(occurrenceId: String): ActiveSessionDetails?
    suspend fun markSessionActive(sessionId: String)
    suspend fun finishSession(sessionId: String)
    suspend fun markActiveSessionsInterrupted()
    suspend fun markSessionInterrupted(sessionId: String)
    suspend fun markSessionFailed(sessionId: String)
    suspend fun resumeSession(sessionId: String): ActiveSessionDetails?
    suspend fun consumeAndRecordArrival(
        sessionId: String,
        cycle: Int,
        destinationId: String,
        runtime: AlarmRuntime
    ): AlertEvent?
    suspend fun recordPrecaution(
        sessionId: String,
        cycle: Int,
        message: String
    ): AlertEvent?
    suspend fun getMaxPrecautionCycle(sessionId: String): Int
    suspend fun getAlertEventById(eventId: String): AlertEvent?
    suspend fun updateAlertStatus(
        eventId: String,
        state: AlertState,
        deliveredAtEpochMs: Long? = null,
        failureReason: String? = null
    )
    suspend fun recordDeliveryAttempt(
        eventId: String,
        channelsDelivered: String,
        failureReason: String? = null
    )
    suspend fun acknowledgeAlert(eventId: String)
    suspend fun updateRuntime(runtime: AlarmRuntime)
    suspend fun updateOccurrenceArrivalCycle(occurrenceId: String, cycle: Int)
}

class AlarmRepositoryImpl(
    private val database: DestinoDatabase
) : AlarmRepository {

    private val destinationDao = database.destinationDao()
    private val alarmDao = database.alarmDao()
    private val sessionDao = database.sessionDao()
    private val runtimeDao = database.alarmRuntimeDao()
    private val alertEventDao = database.alertEventDao()
    private val deliveryAttemptDao = database.alertDeliveryAttemptDao()
    private val scheduleDao = database.scheduleDao()
    private val occurrenceDao = database.occurrenceDao()

    override fun observeActiveSession(): Flow<ActiveSessionDetails?> {
        return sessionDao.observeActiveSession().map { sessionEntity ->
            sessionEntity?.let { buildSessionDetails(it) }
        }
    }

    override fun observeAllActiveSessions(): Flow<List<ActiveSessionDetails>> {
        return sessionDao.observeAllActiveSessions().map { sessionEntities ->
            sessionEntities.mapNotNull { buildSessionDetails(it) }
        }
    }

    override suspend fun getActiveSessionDetails(): ActiveSessionDetails? {
        val sessionEntity = sessionDao.getActiveSession() ?: return null
        return buildSessionDetails(sessionEntity)
    }

    override suspend fun getAllActiveSessionDetails(): List<ActiveSessionDetails> {
        val sessions = sessionDao.getAllActiveSessions()
        return sessions.mapNotNull { buildSessionDetails(it) }
    }

    override suspend fun getRecoverableSessionDetails(): List<ActiveSessionDetails> {
        return database.withTransaction {
            val sessions = sessionDao.getRecoverableSessions()
            val now = System.currentTimeMillis()
            sessions.mapNotNull { sessionEntity ->
                val occEntity = sessionEntity.occurrenceId?.let { occurrenceDao.getById(it) }
                if (occEntity != null) {
                    if (occEntity.scheduledEndEpochMs <= now ||
                        occEntity.status == com.destino.app.core.model.OccurrenceStatus.COMPLETED ||
                        occEntity.status == com.destino.app.core.model.OccurrenceStatus.CANCELLED ||
                        occEntity.status == com.destino.app.core.model.OccurrenceStatus.EXPIRED) {
                        sessionDao.updateSessionState(sessionEntity.id, SessionState.FINISHED, now)
                        return@mapNotNull null
                    }
                }
                buildSessionDetails(sessionEntity)
            }
        }
    }

    override suspend fun getActiveSessionsForAlarm(alarmId: String): List<ActiveSessionDetails> {
        val sessions = sessionDao.getAllActiveSessions().filter { it.alarmDefinitionId == alarmId }
        return sessions.mapNotNull { buildSessionDetails(it) }
    }

    override suspend fun getUnfinishedSessionDetails(): ActiveSessionDetails? {
        val sessionEntity = sessionDao.getUnfinishedSession() ?: return null
        return buildSessionDetails(sessionEntity)
    }

    override suspend fun getLatestInterruptedSessionDetails(): ActiveSessionDetails? {
        val sessionEntity = sessionDao.getLatestInterruptedSession() ?: return null
        return buildSessionDetails(sessionEntity)
    }

    private suspend fun buildSessionDetails(sessionEntity: MonitoringSessionEntity): ActiveSessionDetails? {
        val destination = destinationDao.getById(sessionEntity.destinationId)?.toDomain() ?: return null
        val alarm = alarmDao.getById(sessionEntity.alarmDefinitionId)?.toDomain() ?: return null
        val runtime = runtimeDao.getBySessionId(sessionEntity.id)?.toDomain() ?: AlarmRuntime(
            id = UUID.randomUUID().toString(),
            sessionId = sessionEntity.id
        )
        val pendingAlert = alertEventDao.getActiveAlertForSession(sessionEntity.id)?.toDomain()
        val occurrence = sessionEntity.occurrenceId?.let { occurrenceDao.getById(it)?.toDomain() }

        return ActiveSessionDetails(
            destination = destination,
            alarm = alarm,
            session = sessionEntity.toDomain(),
            runtime = runtime,
            pendingAlert = pendingAlert,
            occurrence = occurrence
        )
    }

    override fun observeLatestDestination(): Flow<Destination?> {
        return destinationDao.observeLatest().map { it?.toDomain() }
    }

    override suspend fun getLatestDestination(): Destination? {
        return destinationDao.getLatest()?.toDomain()
    }

    override suspend fun getDestinationById(id: String): Destination? {
        return destinationDao.getById(id)?.toDomain()
    }

    override fun observeAllAlarms(): Flow<List<AlarmDefinition>> {
        return alarmDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getAlarmById(id: String): AlarmDefinition? {
        return alarmDao.getById(id)?.toDomain()
    }

    override suspend fun getAllEnabledAlarms(): List<AlarmDefinition> {
        return alarmDao.getAllEnabled().map { it.toDomain() }
    }

    override suspend fun updateAlarm(alarm: AlarmDefinition) {
        alarmDao.update(alarm.toEntity())
    }

    override suspend fun setAlarmEnabled(id: String, isEnabled: Boolean) {
        alarmDao.setEnabled(id, isEnabled)
    }

    override suspend fun deleteAlarm(id: String) {
        database.withTransaction {
            val sessions = sessionDao.getAllActiveSessions().filter { it.alarmDefinitionId == id }
            val now = System.currentTimeMillis()
            sessions.forEach { s ->
                sessionDao.updateSessionState(s.id, SessionState.FINISHED, now)
                alertEventDao.cancelPendingAlertsForSession(s.id, now)
            }
            occurrenceDao.deleteAllForAlarm(id)
            scheduleDao.deleteRuleByAlarmId(id)
            alarmDao.deleteById(id)
        }
    }

    override suspend fun saveDestinationAndAlarm(destination: Destination, alarm: AlarmDefinition) {
        database.withTransaction {
            destinationDao.insert(destination.toEntity())
            alarmDao.insert(alarm.toEntity(isConsumed = false))
        }
    }

    override suspend fun startSession(destination: Destination, alarm: AlarmDefinition): ActiveSessionDetails {
        return database.withTransaction {
            if (destinationDao.getById(destination.id) == null) {
                destinationDao.insert(destination.toEntity())
            }
            if (alarmDao.getById(alarm.id) == null) {
                alarmDao.insert(alarm.toEntity(isConsumed = false))
            }

            val nowMs = System.currentTimeMillis()
            val occurrenceId = UUID.randomUUID().toString()
            val today = LocalDate.now(ZoneId.systemDefault())
            val occurrence = AlarmOccurrence(
                id = occurrenceId,
                alarmDefinitionId = alarm.id,
                windowId = AlarmOccurrence.MANUAL_WINDOW_ID,
                occurrenceDate = today,
                scheduledStartEpochMs = nowMs,
                scheduledEndEpochMs = nowMs + 24 * 3600 * 1000L,
                status = com.destino.app.core.model.OccurrenceStatus.MONITORING,
                appliedRadiusMeters = alarm.radiusMeters,
                appliedVibration = alarm.isVibrationEnabled,
                appliedSoundUri = alarm.soundSelection.uriString
            )
            occurrenceDao.insert(occurrence.toEntity())

            val sessionId = UUID.randomUUID().toString()
            val session = MonitoringSession(
                id = sessionId,
                destinationId = destination.id,
                alarmDefinitionId = alarm.id,
                state = SessionState.STARTING,
                startedAtEpochMs = nowMs,
                occurrenceId = occurrenceId
            )
            sessionDao.insert(session.toEntity())

            val runtime = AlarmRuntime(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                isArmed = true,
                confirmationsCount = 0,
                waitingExit = false
            )
            runtimeDao.insert(runtime.toEntity())

            ActiveSessionDetails(
                destination = destination,
                alarm = alarm,
                session = session,
                runtime = runtime,
                occurrence = occurrence
            )
        }
    }

    override suspend fun startOccurrenceSession(
        occurrence: AlarmOccurrence,
        destination: Destination,
        alarm: AlarmDefinition
    ): ActiveSessionDetails {
        return database.withTransaction {
            if (destinationDao.getById(destination.id) == null) {
                destinationDao.insert(destination.toEntity())
            }
            if (alarmDao.getById(alarm.id) == null) {
                alarmDao.insert(alarm.toEntity())
            }

            val sessionId = UUID.randomUUID().toString()
            val session = MonitoringSession(
                id = sessionId,
                destinationId = destination.id,
                alarmDefinitionId = alarm.id,
                state = SessionState.STARTING,
                startedAtEpochMs = System.currentTimeMillis(),
                occurrenceId = occurrence.id
            )
            sessionDao.insert(session.toEntity(occurrenceId = occurrence.id))

            val runtime = AlarmRuntime(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                isArmed = true,
                confirmationsCount = 0,
                waitingExit = false
            )
            runtimeDao.insert(runtime.toEntity())

            ActiveSessionDetails(
                destination = destination,
                alarm = alarm,
                session = session,
                runtime = runtime,
                occurrence = occurrence
            )
        }
    }

    override suspend fun getOccurrenceById(id: String): AlarmOccurrence? {
        return occurrenceDao.getById(id)?.toDomain()
    }

    override suspend fun getSessionByOccurrenceId(occurrenceId: String): ActiveSessionDetails? {
        val sessionEntity = sessionDao.getByOccurrenceId(occurrenceId) ?: return null
        return buildSessionDetails(sessionEntity)
    }

    override suspend fun finishSession(sessionId: String) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            sessionDao.updateSessionState(
                id = sessionId,
                state = SessionState.FINISHED,
                finishedAt = now
            )
            alertEventDao.cancelPendingAlertsForSession(sessionId, now)
            sessionDao.getById(sessionId)?.occurrenceId?.let { occurrenceId ->
                val currentOcc = occurrenceDao.getById(occurrenceId)
                if (currentOcc != null &&
                    currentOcc.status != com.destino.app.core.model.OccurrenceStatus.EXPIRED &&
                    currentOcc.status != com.destino.app.core.model.OccurrenceStatus.CANCELLED) {
                    occurrenceDao.updateStatus(
                        occurrenceId,
                        com.destino.app.core.model.OccurrenceStatus.COMPLETED,
                        "Monitoramento encerrado"
                    )
                }
            }
        }
    }

    override suspend fun markActiveSessionsInterrupted() {
        database.withTransaction {
            val sessions = sessionDao.getAllActiveSessions()
            sessionDao.markActiveSessionsInterrupted(System.currentTimeMillis())
            sessions.mapNotNull { it.occurrenceId }.forEach { occurrenceId ->
                occurrenceDao.updateStatus(
                    occurrenceId,
                    com.destino.app.core.model.OccurrenceStatus.AWAITING_PREREQUISITES,
                    "Monitoramento interrompido pelo sistema"
                )
            }
        }
    }

    override suspend fun markSessionInterrupted(sessionId: String) {
        database.withTransaction {
            sessionDao.updateSessionState(
                id = sessionId,
                state = SessionState.INTERRUPTED,
                finishedAt = System.currentTimeMillis()
            )
            sessionDao.getById(sessionId)?.occurrenceId?.let { occurrenceId ->
                occurrenceDao.updateStatus(
                    occurrenceId,
                    com.destino.app.core.model.OccurrenceStatus.AWAITING_PREREQUISITES,
                    "Monitoramento interrompido pelo sistema"
                )
            }
        }
    }

    override suspend fun markSessionFailed(sessionId: String) {
        database.withTransaction {
            sessionDao.updateSessionState(
                id = sessionId,
                state = SessionState.FAILED,
                finishedAt = System.currentTimeMillis()
            )
            sessionDao.getById(sessionId)?.occurrenceId?.let { occurrenceId ->
                occurrenceDao.updateStatus(
                    occurrenceId,
                    com.destino.app.core.model.OccurrenceStatus.AWAITING_PREREQUISITES,
                    "Falha ao iniciar o monitoramento"
                )
            }
        }
    }

    override suspend fun markSessionActive(sessionId: String) {
        database.withTransaction {
            sessionDao.markSessionActive(sessionId)
            sessionDao.getById(sessionId)?.occurrenceId?.let { occurrenceId ->
                occurrenceDao.updateStatus(
                    occurrenceId,
                    com.destino.app.core.model.OccurrenceStatus.MONITORING
                )
            }
        }
    }

    override suspend fun resumeSession(sessionId: String): ActiveSessionDetails? {
        return database.withTransaction {
            val sessionEntity = sessionDao.getById(sessionId) ?: return@withTransaction null
            val updated = sessionEntity.copy(
                state = SessionState.STARTING,
                finishedAtEpochMs = null
            )
            sessionDao.update(updated)
            updated.occurrenceId?.let { occurrenceId ->
                occurrenceDao.updateStatus(
                    occurrenceId,
                    com.destino.app.core.model.OccurrenceStatus.MONITORING
                )
            }
            buildSessionDetails(updated)
        }
    }

    override suspend fun consumeAndRecordArrival(
        sessionId: String,
        cycle: Int,
        destinationId: String,
        runtime: AlarmRuntime
    ): AlertEvent? {
        val disarmedRuntime = runtime.copy(isArmed = false)
        val result = database.withTransaction {
            // 1. Gravar desarmamento do runtime
            runtimeDao.update(disarmedRuntime.toEntity())

            // 2. Consumir a definição de alarme apenas se NÃO for rotina recorrente
            val session = sessionDao.getById(sessionId)
            val occurrenceId = session?.occurrenceId
            if (occurrenceId != null) {
                val occ = occurrenceDao.getById(occurrenceId)
                if (occ != null && occ.scheduledEndEpochMs > 0 && System.currentTimeMillis() > occ.scheduledEndEpochMs) {
                    occurrenceDao.updateStatus(
                        occurrenceId,
                        com.destino.app.core.model.OccurrenceStatus.EXPIRED,
                        "Janela de horário expirada antes da chegada"
                    )
                    return@withTransaction null
                }
            }

            val isRecurring = session?.alarmDefinitionId?.let { alarmId ->
                val rule = scheduleDao.getRuleByAlarmId(alarmId)
                rule?.type == ScheduleRuleType.RECURRING.name
            } ?: false

            if (!isRecurring) {
                alarmDao.consumeAlarmForDestination(destinationId)
            }
            if (occurrenceId != null) {
                occurrenceDao.updateStatus(
                    occurrenceId,
                    com.destino.app.core.model.OccurrenceStatus.COMPLETED,
                    "Destino alcançado"
                )
            }

            // 3. Gravar evento com estado PENDING
            val existing = alertEventDao.findBySessionCycleAndType(sessionId, cycle, AlertEventType.PROXIMITY.name)
            if (existing != null) {
                return@withTransaction existing.toDomain()
            }

            val eventId = UUID.randomUUID().toString()
            val alertEntity = AlertEventEntity(
                id = eventId,
                sessionId = sessionId,
                cycle = cycle,
                eventType = AlertEventType.PROXIMITY,
                state = AlertState.PENDING,
                createdAtEpochMs = System.currentTimeMillis()
            )
            val rowId = alertEventDao.insert(alertEntity)
            if (rowId > 0) alertEntity.toDomain() else null
        }
        if (result != null) {
            lastPersistedRuntimeBySession[sessionId] = disarmedRuntime
        }
        return result
    }

    override suspend fun recordPrecaution(
        sessionId: String,
        cycle: Int,
        message: String
    ): AlertEvent? {
        return database.withTransaction {
            val existing = alertEventDao.findBySessionCycleAndType(sessionId, cycle, AlertEventType.PRECAUTION.name)
            if (existing != null) {
                return@withTransaction existing.toDomain()
            }

            val eventId = UUID.randomUUID().toString()
            val alertEntity = AlertEventEntity(
                id = eventId,
                sessionId = sessionId,
                cycle = cycle,
                eventType = AlertEventType.PRECAUTION,
                state = AlertState.PENDING,
                createdAtEpochMs = System.currentTimeMillis(),
                failureReason = message
            )
            val rowId = alertEventDao.insert(alertEntity)
            if (rowId > 0) alertEntity.toDomain() else null
        }
    }

    override suspend fun getMaxPrecautionCycle(sessionId: String): Int {
        return alertEventDao.getMaxPrecautionCycle(sessionId) ?: 0
    }

    override suspend fun getAlertEventById(eventId: String): AlertEvent? {
        return alertEventDao.getById(eventId)?.toDomain()
    }

    override suspend fun updateAlertStatus(
        eventId: String,
        state: AlertState,
        deliveredAtEpochMs: Long?,
        failureReason: String?
    ) {
        alertEventDao.updateDeliveryStatus(eventId, state, deliveredAtEpochMs, failureReason)
    }

    override suspend fun recordDeliveryAttempt(
        eventId: String,
        channelsDelivered: String,
        failureReason: String?
    ) {
        deliveryAttemptDao.insert(
            AlertDeliveryAttemptEntity(
                id = UUID.randomUUID().toString(),
                eventId = eventId,
                channelsDelivered = channelsDelivered,
                failureReason = failureReason,
                attemptEpochMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun acknowledgeAlert(eventId: String) {
        alertEventDao.acknowledge(eventId, System.currentTimeMillis())
    }

    private val lastPersistedRuntimeBySession = mutableMapOf<String, AlarmRuntime>()

    override suspend fun updateRuntime(runtime: AlarmRuntime) {
        if (lastPersistedRuntimeBySession[runtime.sessionId] == runtime) return
        runtimeDao.update(runtime.toEntity())
        lastPersistedRuntimeBySession[runtime.sessionId] = runtime
    }

    override suspend fun updateOccurrenceArrivalCycle(occurrenceId: String, cycle: Int) {
        occurrenceDao.updateArrivalCycle(occurrenceId, cycle)
    }
}
