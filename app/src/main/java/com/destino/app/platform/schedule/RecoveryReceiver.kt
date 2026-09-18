package com.destino.app.platform.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RecoveryReceiver : BroadcastReceiver() {

    @Inject
    lateinit var systemScheduler: SystemScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
             action == android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        ) {
            android.util.Log.d("RecoveryReceiver", "Recuperação acionada por ação: $action")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    systemScheduler.reconcileSchedules()
                    // Também avalia a janela vigente. Isso cobre reinício ou
                    // mudança de relógio ocorridos depois do início da janela.
                    context.sendBroadcast(
                        Intent(context, ScheduleReceiver::class.java).apply {
                            this.action = SystemSchedulerImpl.ACTION_SCHEDULE_BOUNDARY
                        }
                    )
                } catch (e: Exception) {
                    android.util.Log.e("RecoveryReceiver", "Falha durante recuperação de agendamentos", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
