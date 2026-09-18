package com.destino.app.data.local

import com.destino.app.core.model.HistoryRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface HistoryRepository {
    fun observeAll(): Flow<List<HistoryRecord>>
    suspend fun recordEvent(record: HistoryRecord)
    suspend fun clearHistory()
    suspend fun purgeOldRecords(daysToKeep: Int)
}

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val database: DestinoDatabase
) : HistoryRepository {

    private val historyDao = database.historyDao()

    override fun observeAll(): Flow<List<HistoryRecord>> {
        return historyDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun recordEvent(record: HistoryRecord) {
        historyDao.insert(record.toEntity())
    }

    override suspend fun clearHistory() {
        historyDao.clearAll()
    }

    override suspend fun purgeOldRecords(daysToKeep: Int) {
        if (daysToKeep <= 0) return
        val cutoff = System.currentTimeMillis() - (daysToKeep.toLong() * 24 * 60 * 60 * 1000L)
        historyDao.deleteOlderThan(cutoff)
    }
}
