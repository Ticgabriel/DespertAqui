package com.destino.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.destino.app.core.model.AlertState
import com.destino.app.core.model.OccurrenceStatus
import com.destino.app.core.model.SessionState
import com.destino.app.data.local.entities.AlarmDefinitionEntity
import com.destino.app.data.local.entities.AlarmOccurrenceEntity
import com.destino.app.data.local.entities.AlarmRuntimeEntity
import com.destino.app.data.local.entities.AlertDeliveryAttemptEntity
import com.destino.app.data.local.entities.AlertEventEntity
import com.destino.app.data.local.entities.DestinationEntity
import com.destino.app.data.local.entities.FavoriteEntity
import com.destino.app.data.local.entities.HistoryRecordEntity
import com.destino.app.data.local.entities.MonitoringSessionEntity
import com.destino.app.data.local.entities.ScheduleExceptionEntity
import com.destino.app.data.local.entities.ScheduleRuleDayEntity
import com.destino.app.data.local.entities.ScheduleRuleEntity
import com.destino.app.data.local.entities.ScheduleWindowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DestinationDao {
    @Upsert
    suspend fun insert(destination: DestinationEntity)

    @Query("SELECT * FROM destinations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DestinationEntity?

    @Query("SELECT * FROM destinations ORDER BY createdAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<DestinationEntity?>

    @Query("SELECT * FROM destinations ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun getLatest(): DestinationEntity?

    @Query("SELECT * FROM destinations ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<DestinationEntity>>

    @Query("SELECT * FROM destinations WHERE name LIKE '%' || :query || '%' ORDER BY createdAtEpochMs DESC")
    suspend fun search(query: String): List<DestinationEntity>

    @Query("DELETE FROM destinations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AlarmDao {
    @Upsert
    suspend fun insert(alarm: AlarmDefinitionEntity)

    @Update
    suspend fun update(alarm: AlarmDefinitionEntity)

    @Query("SELECT * FROM alarm_definitions WHERE destinationId = :destinationId AND isConsumed = 0 LIMIT 1")
    suspend fun getActiveForDestination(destinationId: String): AlarmDefinitionEntity?

    @Query("UPDATE alarm_definitions SET isConsumed = 1 WHERE id = :id")
    suspend fun consumeAlarmById(id: String)

    @Query("UPDATE alarm_definitions SET isConsumed = 1 WHERE destinationId = :destinationId")
    suspend fun consumeAlarmForDestination(destinationId: String)

    @Query("SELECT * FROM alarm_definitions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AlarmDefinitionEntity?

    @Query("SELECT * FROM alarm_definitions ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<AlarmDefinitionEntity>>

    @Query("SELECT * FROM alarm_definitions WHERE isEnabled = 1")
    suspend fun getAllEnabled(): List<AlarmDefinitionEntity>

    @Query("UPDATE alarm_definitions SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setEnabled(id: String, isEnabled: Boolean)

    @Query("DELETE FROM alarm_definitions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: MonitoringSessionEntity)

    @Update
    suspend fun update(session: MonitoringSessionEntity)

    @Query("SELECT * FROM monitoring_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MonitoringSessionEntity?

    @Query("SELECT * FROM monitoring_sessions WHERE occurrenceId = :occurrenceId ORDER BY CASE WHEN state IN ('STARTING', 'ACTIVE') THEN 0 ELSE 1 END, startedAtEpochMs DESC LIMIT 1")
    suspend fun getByOccurrenceId(occurrenceId: String): MonitoringSessionEntity?

    @Query("SELECT * FROM monitoring_sessions WHERE state = 'ACTIVE' LIMIT 1")
    suspend fun getActiveSession(): MonitoringSessionEntity?

    @Query("SELECT * FROM monitoring_sessions WHERE state IN ('STARTING', 'ACTIVE')")
    suspend fun getAllActiveSessions(): List<MonitoringSessionEntity>

    @Query("SELECT * FROM monitoring_sessions WHERE state IN ('STARTING', 'ACTIVE') ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getUnfinishedSession(): MonitoringSessionEntity?

    @Query("SELECT * FROM monitoring_sessions WHERE state = 'INTERRUPTED' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestInterruptedSession(): MonitoringSessionEntity?

    @Query("SELECT * FROM monitoring_sessions WHERE state IN ('STARTING', 'ACTIVE', 'INTERRUPTED') AND finishedAtEpochMs IS NULL")
    suspend fun getRecoverableSessions(): List<MonitoringSessionEntity>

    @Query("SELECT * FROM monitoring_sessions WHERE state IN ('STARTING', 'ACTIVE') LIMIT 1")
    fun observeActiveSession(): Flow<MonitoringSessionEntity?>

    @Query("SELECT * FROM monitoring_sessions WHERE state IN ('STARTING', 'ACTIVE') ORDER BY startedAtEpochMs DESC")
    fun observeAllActiveSessions(): Flow<List<MonitoringSessionEntity>>

    @Query("UPDATE monitoring_sessions SET state = 'ACTIVE' WHERE id = :id")
    suspend fun markSessionActive(id: String)

    @Query("UPDATE monitoring_sessions SET state = 'INTERRUPTED', finishedAtEpochMs = :now WHERE state = 'ACTIVE' OR state = 'STARTING'")
    suspend fun markActiveSessionsInterrupted(now: Long)

    @Query("UPDATE monitoring_sessions SET state = :state, finishedAtEpochMs = :finishedAt WHERE id = :id")
    suspend fun updateSessionState(id: String, state: SessionState, finishedAt: Long?)
}

@Dao
interface AlarmRuntimeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(runtime: AlarmRuntimeEntity)

    @Update
    suspend fun update(runtime: AlarmRuntimeEntity)

    @Query("SELECT * FROM alarm_runtimes WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: String): AlarmRuntimeEntity?
}

@Dao
interface AlertEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(alertEvent: AlertEventEntity): Long

    @Update
    suspend fun update(alertEvent: AlertEventEntity)

    @Query("SELECT * FROM alert_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AlertEventEntity?

    @Query("SELECT * FROM alert_events WHERE sessionId = :sessionId AND cycle = :cycle AND eventType = :eventType LIMIT 1")
    suspend fun findBySessionCycleAndType(sessionId: String, cycle: Int, eventType: String): AlertEventEntity?

    @Query("SELECT * FROM alert_events WHERE sessionId = :sessionId AND state IN ('PENDING', 'PLAYING', 'DELIVERY_FAILED') ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun getActiveAlertForSession(sessionId: String): AlertEventEntity?

    @Query("SELECT * FROM alert_events WHERE state IN ('PENDING', 'PLAYING', 'DELIVERY_FAILED') ORDER BY createdAtEpochMs ASC")
    fun observePendingOrPlaying(): Flow<List<AlertEventEntity>>

    @Query("UPDATE alert_events SET state = :state, deliveredAtEpochMs = :deliveredAt, failureReason = :reason WHERE id = :id")
    suspend fun updateDeliveryStatus(id: String, state: AlertState, deliveredAt: Long?, reason: String?)

    @Query("UPDATE alert_events SET state = 'ACKNOWLEDGED', acknowledgedAtEpochMs = :acknowledgedAt WHERE id = :id")
    suspend fun acknowledge(id: String, acknowledgedAt: Long)

    @Query("UPDATE alert_events SET state = 'ACKNOWLEDGED', acknowledgedAtEpochMs = :now WHERE sessionId = :sessionId AND state IN ('PENDING', 'PLAYING', 'DELIVERY_FAILED')")
    suspend fun cancelPendingAlertsForSession(sessionId: String, now: Long)

    @Query("SELECT MAX(cycle) FROM alert_events WHERE sessionId = :sessionId AND eventType = 'PRECAUTION'")
    suspend fun getMaxPrecautionCycle(sessionId: String): Int?
}

@Dao
interface ScheduleDao {
    @Upsert
    suspend fun insertRule(rule: ScheduleRuleEntity)

    @Upsert
    suspend fun insertDays(days: List<ScheduleRuleDayEntity>)

    @Upsert
    suspend fun insertWindows(windows: List<ScheduleWindowEntity>)

    @Upsert
    suspend fun insertException(exception: ScheduleExceptionEntity)

    @Query("SELECT * FROM schedule_rules WHERE alarmDefinitionId = :alarmId LIMIT 1")
    suspend fun getRuleByAlarmId(alarmId: String): ScheduleRuleEntity?

    @Query("SELECT * FROM schedule_rule_days WHERE ruleId = :ruleId")
    suspend fun getDaysByRuleId(ruleId: String): List<ScheduleRuleDayEntity>

    @Query("SELECT * FROM schedule_windows WHERE ruleId = :ruleId")
    suspend fun getWindowsByRuleId(ruleId: String): List<ScheduleWindowEntity>

    @Query("SELECT * FROM schedule_windows WHERE ruleId = :ruleId")
    fun observeWindowsByRuleId(ruleId: String): Flow<List<ScheduleWindowEntity>>

    @Query("SELECT * FROM schedule_exceptions WHERE ruleId = :ruleId")
    suspend fun getExceptionsByRuleId(ruleId: String): List<ScheduleExceptionEntity>

    @Query("DELETE FROM schedule_rule_days WHERE ruleId = :ruleId")
    suspend fun deleteDaysByRuleId(ruleId: String)

    @Query("DELETE FROM schedule_windows WHERE ruleId = :ruleId")
    suspend fun deleteWindowsByRuleId(ruleId: String)

    @Query("DELETE FROM schedule_rules WHERE alarmDefinitionId = :alarmId")
    suspend fun deleteRuleByAlarmId(alarmId: String)
}

@Dao
interface OccurrenceDao {
    @Upsert
    suspend fun insert(occurrence: AlarmOccurrenceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(occurrences: List<AlarmOccurrenceEntity>)

    @Update
    suspend fun update(occurrence: AlarmOccurrenceEntity)

    @Query("SELECT * FROM alarm_occurrences WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AlarmOccurrenceEntity?

    @Query("SELECT * FROM alarm_occurrences WHERE alarmDefinitionId = :alarmId ORDER BY scheduledStartEpochMs ASC")
    suspend fun getByAlarmId(alarmId: String): List<AlarmOccurrenceEntity>

    @Query("SELECT * FROM alarm_occurrences ORDER BY scheduledStartEpochMs ASC")
    fun observeAll(): Flow<List<AlarmOccurrenceEntity>>

    @Query("SELECT * FROM alarm_occurrences WHERE status IN ('SCHEDULED', 'AWAITING_PREREQUISITES', 'MONITORING', 'WAITING_EXIT') ORDER BY scheduledStartEpochMs ASC")
    fun observeActiveOccurrences(): Flow<List<AlarmOccurrenceEntity>>

    @Query("SELECT * FROM alarm_occurrences WHERE status = 'MONITORING' OR status = 'WAITING_EXIT'")
    suspend fun getCurrentlyMonitoringOccurrences(): List<AlarmOccurrenceEntity>

    @Query("UPDATE alarm_occurrences SET status = :status, completionReason = :reason WHERE id = :id")
    suspend fun updateStatus(id: String, status: OccurrenceStatus, reason: String? = null)

    @Query("UPDATE alarm_occurrences SET arrivalCycle = :cycle WHERE id = :id")
    suspend fun updateArrivalCycle(id: String, cycle: Int)

    @Query("DELETE FROM alarm_occurrences WHERE alarmDefinitionId = :alarmId AND scheduledEndEpochMs >= :fromEpochMs AND status IN ('SCHEDULED', 'AWAITING_PREREQUISITES', 'SKIPPED')")
    suspend fun deleteFutureOccurrences(alarmId: String, fromEpochMs: Long)

    @Query("DELETE FROM alarm_occurrences WHERE alarmDefinitionId = :alarmId")
    suspend fun deleteAllForAlarm(alarmId: String)
}

@Dao
interface FavoriteDao {
    @Upsert
    suspend fun insert(favorite: FavoriteEntity)

    @Update
    suspend fun update(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM favorites WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FavoriteEntity?

    @Query("SELECT * FROM favorites ORDER BY sortOrder ASC, createdAtEpochMs ASC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE nickname LIKE '%' || :query || '%' ORDER BY sortOrder ASC")
    fun search(query: String): Flow<List<FavoriteEntity>>
}

@Dao
interface HistoryDao {
    @Upsert
    suspend fun insert(record: HistoryRecordEntity)

    @Query("SELECT * FROM history_records ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<HistoryRecordEntity>>

    @Query("DELETE FROM history_records")
    suspend fun clearAll()

    @Query("DELETE FROM history_records WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long)
}

@Dao
interface AlertDeliveryAttemptDao {
    @Upsert
    suspend fun insert(attempt: AlertDeliveryAttemptEntity)

    @Query("SELECT * FROM alert_delivery_attempts WHERE eventId = :eventId ORDER BY attemptEpochMs ASC")
    suspend fun getByEventId(eventId: String): List<AlertDeliveryAttemptEntity>
}
