package com.destino.app.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class ScheduleRuleType {
    ONE_OFF,
    RECURRING
}

enum class TimeZonePolicy {
    KEEP_LOCAL_WALL_CLOCK,
    FIXED_ZONE
}

enum class OccurrenceStatus {
    SCHEDULED,
    AWAITING_PREREQUISITES,
    MONITORING,
    WAITING_EXIT,
    COMPLETED,
    EXPIRED,
    CANCELLED,
    SKIPPED
}

enum class ScheduleExceptionType {
    SKIPPED,
    CANCELLED
}

data class ScheduleRule(
    val id: String,
    val alarmDefinitionId: String,
    val type: ScheduleRuleType,
    val specificDate: LocalDate? = null,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val timeZonePolicy: TimeZonePolicy = TimeZonePolicy.KEEP_LOCAL_WALL_CLOCK,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class ScheduleWindow(
    val id: String,
    val ruleId: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isAllDay: Boolean = false
) {
    init {
        if (!isAllDay) {
            require(startTime != endTime) { "Horário de início não pode ser igual ao fim em janela personalizada. Use Dia Inteiro." }
        }
    }
}

data class ScheduleException(
    val id: String,
    val ruleId: String,
    val occurrenceDate: LocalDate,
    val windowId: String,
    val type: ScheduleExceptionType,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class AlarmOccurrence(
    val id: String,
    val alarmDefinitionId: String,
    val windowId: String,
    val occurrenceDate: LocalDate,
    val scheduledStartEpochMs: Long,
    val scheduledEndEpochMs: Long,
    val status: OccurrenceStatus = OccurrenceStatus.SCHEDULED,
    val arrivalCycle: Int = 0,
    val appliedRadiusMeters: Double = 500.0,
    val appliedVibration: Boolean = true,
    val appliedSoundUri: String? = null,
    val completionReason: String? = null
) {
    val isManual: Boolean get() = windowId == MANUAL_WINDOW_ID

    companion object {
        const val MANUAL_WINDOW_ID = "manual"

        fun buildDeterministicId(alarmId: String, windowId: String, date: LocalDate): String {
            return "${alarmId}_${windowId}_$date"
        }
    }
}
