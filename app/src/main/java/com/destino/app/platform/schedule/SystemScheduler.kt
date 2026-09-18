package com.destino.app.platform.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.destino.app.core.model.AlarmOccurrence
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.core.model.OccurrenceStatus
import com.destino.app.core.schedule.ScheduleEvaluator
import com.destino.app.core.schedule.ScheduleEvaluatorImpl
import com.destino.app.data.local.AlarmRepository
import com.destino.app.data.local.OccurrenceRepository
import com.destino.app.data.local.ScheduleRepository
import com.destino.app.platform.runtime.CapabilityProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

interface SystemScheduler {
    suspend fun reconcileSchedules(): Long?
    fun cancelScheduledBoundary()
}

@Singleton
class SystemSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmRepository: AlarmRepository,
    private val scheduleRepository: ScheduleRepository,
    private val occurrenceRepository: OccurrenceRepository,
    private val capabilityProvider: CapabilityProvider,
    private val coordinatorProvider: Provider<MonitoringCoordinator>
) : SystemScheduler {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val evaluator: ScheduleEvaluator = ScheduleEvaluatorImpl()

    companion object {
        const val SCHEDULE_REQUEST_CODE = 4001
        const val ACTION_SCHEDULE_BOUNDARY = "com.destino.app.ACTION_SCHEDULE_BOUNDARY"
    }

    override suspend fun reconcileSchedules(): Long? = withContext(Dispatchers.IO) {
        val enabledAlarms = alarmRepository.getAllEnabledAlarms()
        if (enabledAlarms.isEmpty()) {
            // Encerrar ocorrências agendadas que ainda estejam ativas
            val coordinator = coordinatorProvider.get()
            occurrenceRepository.getCurrentlyMonitoring()
                .filter { !it.isManual }
                .forEach { occurrence ->
                    coordinator.execute(
                        MonitoringCommand.StopOccurrence(
                            commandId = UUID.randomUUID().toString(),
                            occurrenceId = occurrence.id,
                            finalStatus = OccurrenceStatus.CANCELLED,
                            reason = "Todos os alarmes foram desativados"
                        )
                    )
                    occurrenceRepository.updateStatus(
                        occurrence.id,
                        OccurrenceStatus.CANCELLED,
                        "Alarme desativado"
                    )
                }
            cancelScheduledBoundary()
            return@withContext null
        }

        var earliestBoundaryEpochMs: Long? = null
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val eligibleOccurrenceIds = mutableSetOf<String>()
        val capabilities = capabilityProvider.getCapabilities()
        val coordinator = coordinatorProvider.get()

        for (alarm in enabledAlarms) {
            val rule = scheduleRepository.getRuleForAlarm(alarm.id) ?: continue
            val windows = scheduleRepository.getWindowsForRule(rule.id)
            val exceptions = scheduleRepository.getExceptions(rule.id)

            val evaluation = evaluator.evaluate(
                rule = rule,
                windows = windows,
                exceptions = exceptions,
                nowInstant = now,
                zoneId = zone
            )

            evaluation.nextBoundaryEpochMs?.let { boundary ->
                if (earliestBoundaryEpochMs == null || boundary < earliestBoundaryEpochMs!!) {
                    earliestBoundaryEpochMs = boundary
                }
            }

            // 1. Reconciliação imediata: se a janela estiver aberta neste exato instante, INICIAR PRIMEIRO
            val occurrenceDate = evaluation.activeOccurrenceDate
            if (evaluation.isEligibleNow && evaluation.activeWindowId != null && occurrenceDate != null) {
                val occurrenceId = AlarmOccurrence.buildDeterministicId(
                    alarmId = alarm.id,
                    windowId = evaluation.activeWindowId,
                    date = occurrenceDate
                )
                eligibleOccurrenceIds += occurrenceId
                var existingOccurrence = occurrenceRepository.getById(occurrenceId)
                if (existingOccurrence == null) {
                    val candidate = evaluator.materializeOccurrences(
                        alarmId = alarm.id,
                        rule = rule,
                        windows = windows,
                        exceptions = exceptions,
                        radiusMeters = alarm.radiusMeters,
                        isVibrationEnabled = alarm.isVibrationEnabled,
                        soundUri = alarm.soundSelection.uriString,
                        zoneId = zone
                    ).firstOrNull { it.id == occurrenceId }
                    if (candidate != null) {
                        existingOccurrence = candidate
                        occurrenceRepository.saveOccurrence(candidate)
                    }
                }

                if (existingOccurrence != null && (existingOccurrence.status == OccurrenceStatus.SCHEDULED || existingOccurrence.status == OccurrenceStatus.AWAITING_PREREQUISITES)) {
                    if (!capabilities.hasBackgroundLocation) {
                        val reason = "Autorize localização 'Permitir o tempo todo' para iniciar automaticamente com o app fechado"
                        android.util.Log.w("SystemScheduler", "Ocorrência $occurrenceId bloqueada: falta localização em segundo plano")
                        occurrenceRepository.updateStatus(
                            occurrenceId,
                            OccurrenceStatus.AWAITING_PREREQUISITES,
                            reason
                        )
                    } else {
                        val destination = alarmRepository.getDestinationById(alarm.destinationId)
                        if (destination != null) {
                            android.util.Log.i("SystemScheduler", "Disparando início da ocorrência $occurrenceId para destino ${destination.name}...")
                            val result = coordinator.execute(
                                MonitoringCommand.StartOccurrence(
                                    commandId = UUID.randomUUID().toString(),
                                    occurrence = existingOccurrence,
                                    destination = destination,
                                    alarm = alarm
                                )
                            )
                            when (result) {
                                is CommandResult.Success -> {
                                    android.util.Log.i("SystemScheduler", "Ocorrência $occurrenceId iniciada com sucesso")
                                }
                                is CommandResult.AlreadyActive -> {
                                    android.util.Log.i("SystemScheduler", "Ocorrência $occurrenceId já estava ativa")
                                }
                                is CommandResult.Rejected -> {
                                    android.util.Log.w("SystemScheduler", "Início de $occurrenceId recusado: ${result.reason}")
                                    occurrenceRepository.updateStatus(
                                        occurrenceId,
                                        OccurrenceStatus.AWAITING_PREREQUISITES,
                                        result.reason
                                    )
                                }
                                is CommandResult.Error -> {
                                    android.util.Log.e("SystemScheduler", "Erro ao iniciar ocorrência $occurrenceId: ${result.message}", result.cause)
                                    occurrenceRepository.updateStatus(
                                        occurrenceId,
                                        OccurrenceStatus.AWAITING_PREREQUISITES,
                                        "Falha ao iniciar: ${result.message}"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Materializar ocorrências dos próximos 14 dias para consulta do usuário
            val occurrences = evaluator.materializeOccurrences(
                alarmId = alarm.id,
                rule = rule,
                windows = windows,
                exceptions = exceptions,
                radiusMeters = alarm.radiusMeters,
                isVibrationEnabled = alarm.isVibrationEnabled,
                soundUri = alarm.soundSelection.uriString,
                zoneId = zone
            )
            occurrenceRepository.deleteFutureOccurrences(alarm.id, now.toEpochMilli())
            occurrenceRepository.saveOccurrences(occurrences)
        }

        // Encerrar ocorrências agendadas que expiraram fora da janela (preservando viagens manuais!)
        occurrenceRepository.getCurrentlyMonitoring()
            .filter { !it.isManual && it.id !in eligibleOccurrenceIds }
            .forEach { occurrence ->
                coordinator.execute(
                    MonitoringCommand.StopOccurrence(
                        commandId = UUID.randomUUID().toString(),
                        occurrenceId = occurrence.id,
                        finalStatus = OccurrenceStatus.EXPIRED,
                        reason = "Janela de horário encerrada"
                    )
                )
                occurrenceRepository.updateStatus(
                    occurrence.id,
                    OccurrenceStatus.EXPIRED,
                    "Janela de horário encerrada"
                )
            }

        earliestBoundaryEpochMs?.let { boundaryMs ->
            scheduleAlarmManager(boundaryMs)
        } ?: cancelScheduledBoundary()

        earliestBoundaryEpochMs
    }

    private fun scheduleAlarmManager(triggerEpochMs: Long) {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_SCHEDULE_BOUNDARY
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(context, SCHEDULE_REQUEST_CODE, intent, flags)

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        try {
            if (canExact) {
                val showIntent = Intent(context, com.destino.app.MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val showPendingIntent = PendingIntent.getActivity(
                    context,
                    SCHEDULE_REQUEST_CODE + 1,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerEpochMs, showPendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                android.util.Log.i("SystemScheduler", "Alarme agendado via setAlarmClock para epoch: $triggerEpochMs")
            } else {
                android.util.Log.w("SystemScheduler", "canScheduleExactAlarms=false. Usando setAndAllowWhileIdle para epoch: $triggerEpochMs")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpochMs, pendingIntent)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("SystemScheduler", "SecurityException ao tentar setAlarmClock, usando setExactAndAllowWhileIdle / setAndAllowWhileIdle", e)
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpochMs, pendingIntent)
            } catch (e2: Exception) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerEpochMs, pendingIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("SystemScheduler", "Erro inesperado ao agendar no AlarmManager", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerEpochMs, pendingIntent)
        }
    }

    override fun cancelScheduledBoundary() {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_SCHEDULE_BOUNDARY
        }
        val flags = PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(context, SCHEDULE_REQUEST_CODE, intent, flags)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
