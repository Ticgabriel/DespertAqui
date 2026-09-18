package com.destino.app.core.schedule

import com.destino.app.core.model.ScheduleException
import com.destino.app.core.model.ScheduleExceptionType
import com.destino.app.core.model.ScheduleRule
import com.destino.app.core.model.ScheduleRuleType
import com.destino.app.core.model.ScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class ScheduleEvaluatorTest {

    private val evaluator: ScheduleEvaluator = ScheduleEvaluatorImpl()
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun testWeekdayMorningWindowEligibility() {
        val rule = ScheduleRule(
            id = "rule-1",
            alarmDefinitionId = "alarm-1",
            type = ScheduleRuleType.RECURRING,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        )
        val window = ScheduleWindow(
            id = "win-1",
            ruleId = "rule-1",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(9, 0)
        )

        // Segunda-feira às 08:00 -> dentro da janela
        val monday8am = LocalDateTime.of(2026, 9, 14, 8, 0).atZone(zone).toInstant()
        val evalInside = evaluator.evaluate(rule, listOf(window), emptyList(), monday8am, zone)

        assertTrue(evalInside.isEligibleNow)
        assertEquals("win-1", evalInside.activeWindowId)

        // Segunda-feira às 06:30 -> antes da janela; próxima fronteira deve ser 07:00
        val monday630am = LocalDateTime.of(2026, 9, 14, 6, 30).atZone(zone).toInstant()
        val evalBefore = evaluator.evaluate(rule, listOf(window), emptyList(), monday630am, zone)

        assertFalse(evalBefore.isEligibleNow)
        assertNotNull(evalBefore.nextBoundaryEpochMs)
        val expectedBoundary = LocalDateTime.of(2026, 9, 14, 7, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedBoundary, evalBefore.nextBoundaryEpochMs)
    }

    @Test
    fun testOvernightWindowCrossingMidnight() {
        // Janela de segunda das 23:00 às 02:00
        val rule = ScheduleRule(
            id = "rule-night",
            alarmDefinitionId = "alarm-1",
            type = ScheduleRuleType.RECURRING,
            daysOfWeek = setOf(DayOfWeek.MONDAY)
        )
        val window = ScheduleWindow(
            id = "win-night",
            ruleId = "rule-night",
            startTime = LocalTime.of(23, 0),
            endTime = LocalTime.of(2, 0)
        )

        // Segunda-feira às 23:30 -> elegível
        val monday2330 = LocalDateTime.of(2026, 9, 14, 23, 30).atZone(zone).toInstant()
        val evalMondayNight = evaluator.evaluate(rule, listOf(window), emptyList(), monday2330, zone)
        assertTrue(evalMondayNight.isEligibleNow)

        // Terça-feira às 01:30 -> ainda elegível (pertence à ocorrência iniciada na segunda)
        val tuesday0130 = LocalDateTime.of(2026, 9, 15, 1, 30).atZone(zone).toInstant()
        val evalTuesdayEarly = evaluator.evaluate(rule, listOf(window), emptyList(), tuesday0130, zone)
        assertTrue(evalTuesdayEarly.isEligibleNow)

        // Terça-feira às 02:30 -> fora da janela (terminou às 02:00)
        val tuesday0230 = LocalDateTime.of(2026, 9, 15, 2, 30).atZone(zone).toInstant()
        val evalTuesdayLate = evaluator.evaluate(rule, listOf(window), emptyList(), tuesday0230, zone)
        assertFalse(evalTuesdayLate.isEligibleNow)
    }

    @Test
    fun testAllDayWindowEligibility() {
        val rule = ScheduleRule(
            id = "rule-allday",
            alarmDefinitionId = "alarm-1",
            type = ScheduleRuleType.RECURRING,
            daysOfWeek = setOf(DayOfWeek.SATURDAY)
        )
        val window = ScheduleWindow(
            id = "win-allday",
            ruleId = "rule-allday",
            startTime = LocalTime.MIDNIGHT,
            endTime = LocalTime.MIDNIGHT,
            isAllDay = true
        )

        // Sábado ao meio-dia
        val saturdayNoon = LocalDateTime.of(2026, 9, 19, 12, 0).atZone(zone).toInstant()
        val eval = evaluator.evaluate(rule, listOf(window), emptyList(), saturdayNoon, zone)

        assertTrue(eval.isEligibleNow)
    }

    @Test
    fun testSkippedExceptionExcludesOccurrence() {
        val rule = ScheduleRule(
            id = "rule-skip",
            alarmDefinitionId = "alarm-1",
            type = ScheduleRuleType.RECURRING,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        )
        val window = ScheduleWindow(
            id = "win-1",
            ruleId = "rule-skip",
            startTime = LocalTime.of(7, 0),
            endTime = LocalTime.of(9, 0)
        )
        val mondayDate = LocalDate.of(2026, 9, 14)
        val exception = ScheduleException(
            id = UUID.randomUUID().toString(),
            ruleId = "rule-skip",
            occurrenceDate = mondayDate,
            windowId = "win-1",
            type = ScheduleExceptionType.SKIPPED
        )

        val monday8am = LocalDateTime.of(2026, 9, 14, 8, 0).atZone(zone).toInstant()
        val eval = evaluator.evaluate(rule, listOf(window), listOf(exception), monday8am, zone)

        // Como foi pulada, não pode ser elegível
        assertFalse(eval.isEligibleNow)
        // Próxima fronteira deve apontar para a terça-feira às 07:00
        val expectedTuesday = LocalDateTime.of(2026, 9, 15, 7, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedTuesday, eval.nextBoundaryEpochMs)
    }
}
