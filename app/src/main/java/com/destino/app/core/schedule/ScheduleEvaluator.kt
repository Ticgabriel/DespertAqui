package com.destino.app.core.schedule

import com.destino.app.core.model.AlarmOccurrence
import com.destino.app.core.model.OccurrenceStatus
import com.destino.app.core.model.ScheduleException
import com.destino.app.core.model.ScheduleExceptionType
import com.destino.app.core.model.ScheduleRule
import com.destino.app.core.model.ScheduleRuleType
import com.destino.app.core.model.ScheduleWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class NormalizedWindow(
    val id: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int, // Can be > 1440 if crossing midnight (e.g. 23:00 to 02:00 = 1380 to 1560)
    val isAllDay: Boolean = false
)

data class ScheduleEvaluationResult(
    val isEligibleNow: Boolean,
    val activeWindowId: String? = null,
    val activeOccurrenceDate: LocalDate? = null,
    val nextBoundaryEpochMs: Long? = null,
    val statusDescription: String
)

interface ScheduleEvaluator {
    fun normalizeWindows(windows: List<ScheduleWindow>): List<NormalizedWindow>

    fun evaluate(
        rule: ScheduleRule,
        windows: List<ScheduleWindow>,
        exceptions: List<ScheduleException>,
        nowInstant: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ScheduleEvaluationResult

    fun materializeOccurrences(
        alarmId: String,
        rule: ScheduleRule,
        windows: List<ScheduleWindow>,
        exceptions: List<ScheduleException>,
        radiusMeters: Double,
        isVibrationEnabled: Boolean,
        soundUri: String?,
        fromDate: LocalDate = LocalDate.now(),
        daysAhead: Int = 14,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<AlarmOccurrence>
}

class ScheduleEvaluatorImpl : ScheduleEvaluator {

    override fun normalizeWindows(windows: List<ScheduleWindow>): List<NormalizedWindow> {
        if (windows.isEmpty()) return emptyList()

        // Se houver qualquer janela de dia inteiro, normaliza para dia inteiro completo
        if (windows.any { it.isAllDay }) {
            return listOf(
                NormalizedWindow(
                    id = windows.first { it.isAllDay }.id,
                    startMinuteOfDay = 0,
                    endMinuteOfDay = 1440,
                    isAllDay = true
                )
            )
        }

        // Converter cada janela em minutos
        val rawList = windows.map { w ->
            val startMin = w.startTime.toSecondOfDay() / 60
            val endMin = w.endTime.toSecondOfDay() / 60
            val effectiveEnd = if (endMin <= startMin) endMin + 1440 else endMin
            NormalizedWindow(w.id, startMin, effectiveEnd, false)
        }.sortedBy { it.startMinuteOfDay }

        // Mesclar janelas sobrepostas ou adjacentes
        val merged = mutableListOf<NormalizedWindow>()
        var current = rawList.first()

        for (i in 1 until rawList.size) {
            val next = rawList[i]
            if (next.startMinuteOfDay <= current.endMinuteOfDay) {
                // Sobreposta ou adjacente: expandir fim
                current = current.copy(endMinuteOfDay = maxOf(current.endMinuteOfDay, next.endMinuteOfDay))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    override fun evaluate(
        rule: ScheduleRule,
        windows: List<ScheduleWindow>,
        exceptions: List<ScheduleException>,
        nowInstant: Instant,
        zoneId: ZoneId
    ): ScheduleEvaluationResult {
        val nowZoned = nowInstant.atZone(zoneId)
        val today = nowZoned.toLocalDate()
        val yesterday = today.minusDays(1)
        val nowMinuteOfDay = nowZoned.hour * 60 + nowZoned.minute
        val nowEpochMs = nowInstant.toEpochMilli()

        val normalized = normalizeWindows(windows)
        if (normalized.isEmpty()) {
            return ScheduleEvaluationResult(
                isEligibleNow = false,
                statusDescription = "Sem janelas configuradas"
            )
        }

        var isEligible = false
        var activeWindowId: String? = null
        var activeOccurrenceDate: LocalDate? = null
        var nextBoundaryEpochMs: Long? = null

        // Verificar se hoje é aplicável
        fun isDateApplicable(date: LocalDate): Boolean {
            if (rule.type == ScheduleRuleType.ONE_OFF) {
                return rule.specificDate == date
            }
            return rule.daysOfWeek.contains(date.dayOfWeek)
        }

        // 1. Checar janela de ontem que possa ter cruzado a meia-noite
        if (isDateApplicable(yesterday)) {
            for (w in normalized) {
                if (w.endMinuteOfDay > 1440) {
                    val remainingEndMinuteToday = w.endMinuteOfDay - 1440
                    if (nowMinuteOfDay < remainingEndMinuteToday) {
                        val isSkipped = exceptions.any { it.occurrenceDate == yesterday && it.windowId == w.id && it.type == ScheduleExceptionType.SKIPPED }
                        if (!isSkipped) {
                            isEligible = true
                            activeWindowId = w.id
                            activeOccurrenceDate = yesterday
                            val endInstant = zonedAtMinute(yesterday, w.endMinuteOfDay, zoneId).toInstant()
                            nextBoundaryEpochMs = endInstant.toEpochMilli()
                            break
                        }
                    }
                }
            }
        }

        // 2. Checar janelas de hoje
        if (!isEligible && isDateApplicable(today)) {
            for (w in normalized) {
                val isSkipped = exceptions.any { it.occurrenceDate == today && it.windowId == w.id && it.type == ScheduleExceptionType.SKIPPED }
                if (!isSkipped) {
                    if (nowMinuteOfDay in w.startMinuteOfDay until w.endMinuteOfDay) {
                        isEligible = true
                        activeWindowId = w.id
                        activeOccurrenceDate = today
                        val endInstant = zonedAtMinute(today, w.endMinuteOfDay, zoneId).toInstant()
                        nextBoundaryEpochMs = endInstant.toEpochMilli()
                        break
                    } else if (nowMinuteOfDay < w.startMinuteOfDay) {
                        val startInstant = zonedAtMinute(today, w.startMinuteOfDay, zoneId).toInstant()
                        val startMs = startInstant.toEpochMilli()
                        if (nextBoundaryEpochMs == null || startMs < nextBoundaryEpochMs) {
                            nextBoundaryEpochMs = startMs
                        }
                    }
                }
            }
        }

        // 3. Se ainda não achou próxima fronteira, procurar nos próximos dias
        if (nextBoundaryEpochMs == null) {
            val searchDays = if (rule.type == ScheduleRuleType.ONE_OFF && rule.specificDate != null) {
                maxOf(14L, java.time.temporal.ChronoUnit.DAYS.between(today, rule.specificDate)).toInt()
            } else 14
            for (dayOffset in 1..searchDays) {
                val candidateDate = today.plusDays(dayOffset.toLong())
                if (isDateApplicable(candidateDate)) {
                    for (w in normalized) {
                        val isSkipped = exceptions.any { it.occurrenceDate == candidateDate && it.windowId == w.id && it.type == ScheduleExceptionType.SKIPPED }
                        if (!isSkipped) {
                            val startInstant = zonedAtMinute(candidateDate, w.startMinuteOfDay, zoneId).toInstant()
                            val startMs = startInstant.toEpochMilli()
                            if (startMs > nowEpochMs) {
                                if (nextBoundaryEpochMs == null || startMs < nextBoundaryEpochMs) {
                                    nextBoundaryEpochMs = startMs
                                }
                            }
                        }
                    }
                    if (nextBoundaryEpochMs != null) break
                }
            }
        }

        val statusDesc = when {
            isEligible -> "Monitorando na janela vigente"
            nextBoundaryEpochMs != null -> "Agendado"
            else -> "Sem ativações futuras"
        }

        return ScheduleEvaluationResult(
            isEligibleNow = isEligible,
            activeWindowId = activeWindowId,
            activeOccurrenceDate = activeOccurrenceDate,
            nextBoundaryEpochMs = nextBoundaryEpochMs,
            statusDescription = statusDesc
        )
    }

    override fun materializeOccurrences(
        alarmId: String,
        rule: ScheduleRule,
        windows: List<ScheduleWindow>,
        exceptions: List<ScheduleException>,
        radiusMeters: Double,
        isVibrationEnabled: Boolean,
        soundUri: String?,
        fromDate: LocalDate,
        daysAhead: Int,
        zoneId: ZoneId
    ): List<AlarmOccurrence> {
        val occurrences = mutableListOf<AlarmOccurrence>()
        val normalized = normalizeWindows(windows)
        if (normalized.isEmpty()) return emptyList()

        val dates = if (rule.type == ScheduleRuleType.ONE_OFF) {
            listOfNotNull(rule.specificDate).filter { !it.isBefore(fromDate) }
        } else {
            (0 until daysAhead).map { fromDate.plusDays(it.toLong()) }
        }

        for (date in dates) {

            val isApplicable = if (rule.type == ScheduleRuleType.ONE_OFF) {
                rule.specificDate == date
            } else {
                rule.daysOfWeek.contains(date.dayOfWeek)
            }

            if (!isApplicable) continue

            for (w in normalized) {
                val isSkipped = exceptions.any { it.occurrenceDate == date && it.windowId == w.id && it.type == ScheduleExceptionType.SKIPPED }
                val startZoned = zonedAtMinute(date, w.startMinuteOfDay, zoneId)
                val endZoned = zonedAtMinute(date, w.endMinuteOfDay, zoneId)

                val occurrenceId = AlarmOccurrence.buildDeterministicId(alarmId, w.id, date)

                occurrences.add(
                    AlarmOccurrence(
                        id = occurrenceId,
                        alarmDefinitionId = alarmId,
                        windowId = w.id,
                        occurrenceDate = date,
                        scheduledStartEpochMs = startZoned.toInstant().toEpochMilli(),
                        scheduledEndEpochMs = endZoned.toInstant().toEpochMilli(),
                        status = if (isSkipped) OccurrenceStatus.SKIPPED else OccurrenceStatus.SCHEDULED,
                        appliedRadiusMeters = radiusMeters,
                        appliedVibration = isVibrationEnabled,
                        appliedSoundUri = soundUri
                    )
                )
            }
        }

        return occurrences
    }

    private fun zonedAtMinute(date: LocalDate, minute: Int, zoneId: ZoneId): ZonedDateTime {
        val dayOffset = minute / 1440
        val minuteOfDay = minute % 1440
        return date.plusDays(dayOffset.toLong())
            .atTime(minuteOfDay / 60, minuteOfDay % 60)
            .atZone(zoneId)
    }
}
