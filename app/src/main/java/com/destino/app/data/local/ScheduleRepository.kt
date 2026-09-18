package com.destino.app.data.local

import androidx.room.withTransaction
import com.destino.app.core.model.ScheduleException
import com.destino.app.core.model.ScheduleRule
import com.destino.app.core.model.ScheduleWindow
import com.destino.app.data.local.entities.ScheduleRuleDayEntity
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

interface ScheduleRepository {
    suspend fun getRuleForAlarm(alarmId: String): ScheduleRule?
    suspend fun getWindowsForRule(ruleId: String): List<ScheduleWindow>
    suspend fun saveSchedule(rule: ScheduleRule, windows: List<ScheduleWindow>)
    suspend fun addException(exception: ScheduleException)
    suspend fun getExceptions(ruleId: String): List<ScheduleException>
    suspend fun deleteScheduleForAlarm(alarmId: String)
}

@Singleton
class ScheduleRepositoryImpl @Inject constructor(
    private val database: DestinoDatabase
) : ScheduleRepository {

    private val scheduleDao = database.scheduleDao()

    override suspend fun getRuleForAlarm(alarmId: String): ScheduleRule? {
        val ruleEntity = scheduleDao.getRuleByAlarmId(alarmId) ?: return null
        val days = scheduleDao.getDaysByRuleId(ruleEntity.id).map { DayOfWeek.of(it.dayOfWeek) }.toSet()
        return ruleEntity.toDomain(days)
    }

    override suspend fun getWindowsForRule(ruleId: String): List<ScheduleWindow> {
        return scheduleDao.getWindowsByRuleId(ruleId).map { it.toDomain() }
    }

    override suspend fun saveSchedule(rule: ScheduleRule, windows: List<ScheduleWindow>) {
        database.withTransaction {
            scheduleDao.insertRule(rule.toEntity())
            scheduleDao.deleteDaysByRuleId(rule.id)
            val daysEntities = rule.daysOfWeek.map { ScheduleRuleDayEntity(rule.id, it.value) }
            if (daysEntities.isNotEmpty()) {
                scheduleDao.insertDays(daysEntities)
            }
            scheduleDao.deleteWindowsByRuleId(rule.id)
            val windowEntities = windows.map { it.toEntity() }
            if (windowEntities.isNotEmpty()) {
                scheduleDao.insertWindows(windowEntities)
            }
        }
    }

    override suspend fun addException(exception: ScheduleException) {
        scheduleDao.insertException(exception.toEntity())
    }

    override suspend fun getExceptions(ruleId: String): List<ScheduleException> {
        return scheduleDao.getExceptionsByRuleId(ruleId).map { it.toDomain() }
    }

    override suspend fun deleteScheduleForAlarm(alarmId: String) {
        scheduleDao.deleteRuleByAlarmId(alarmId)
    }
}
