package com.destino.app.data.local

import com.destino.app.core.model.AlarmOccurrence
import com.destino.app.core.model.OccurrenceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface OccurrenceRepository {
    fun observeAll(): Flow<List<AlarmOccurrence>>
    fun observeActiveOccurrences(): Flow<List<AlarmOccurrence>>
    suspend fun getById(id: String): AlarmOccurrence?
    suspend fun getByAlarmId(alarmId: String): List<AlarmOccurrence>
    suspend fun saveOccurrence(occurrence: AlarmOccurrence)
    suspend fun saveOccurrences(occurrences: List<AlarmOccurrence>)
    suspend fun updateStatus(id: String, status: OccurrenceStatus, reason: String? = null)
    suspend fun updateArrivalCycle(id: String, cycle: Int)
    suspend fun deleteFutureOccurrences(alarmId: String, fromEpochMs: Long)
    suspend fun getCurrentlyMonitoring(): List<AlarmOccurrence>
}

@Singleton
class OccurrenceRepositoryImpl @Inject constructor(
    private val database: DestinoDatabase
) : OccurrenceRepository {

    private val occurrenceDao = database.occurrenceDao()

    override fun observeAll(): Flow<List<AlarmOccurrence>> {
        return occurrenceDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeActiveOccurrences(): Flow<List<AlarmOccurrence>> {
        return occurrenceDao.observeActiveOccurrences().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getById(id: String): AlarmOccurrence? {
        return occurrenceDao.getById(id)?.toDomain()
    }

    override suspend fun getByAlarmId(alarmId: String): List<AlarmOccurrence> {
        return occurrenceDao.getByAlarmId(alarmId).map { it.toDomain() }
    }

    override suspend fun saveOccurrence(occurrence: AlarmOccurrence) {
        occurrenceDao.insert(occurrence.toEntity())
    }

    override suspend fun saveOccurrences(occurrences: List<AlarmOccurrence>) {
        // Materializar novamente não pode reabrir ocorrências concluídas,
        // puladas ou canceladas.
        occurrenceDao.insertAllIfAbsent(occurrences.map { it.toEntity() })
    }

    override suspend fun updateStatus(id: String, status: OccurrenceStatus, reason: String?) {
        occurrenceDao.updateStatus(id, status, reason)
    }

    override suspend fun updateArrivalCycle(id: String, cycle: Int) {
        occurrenceDao.updateArrivalCycle(id, cycle)
    }

    override suspend fun deleteFutureOccurrences(alarmId: String, fromEpochMs: Long) {
        occurrenceDao.deleteFutureOccurrences(alarmId, fromEpochMs)
    }

    override suspend fun getCurrentlyMonitoring(): List<AlarmOccurrence> {
        return occurrenceDao.getCurrentlyMonitoringOccurrences().map { it.toDomain() }
    }
}
