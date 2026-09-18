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
class ScheduleReceiver : BroadcastReceiver() {

    @Inject
    lateinit var systemScheduler: SystemScheduler

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("ScheduleReceiver", "Alarme disparado. Iniciando reconciliação de agendamentos...")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val boundary = systemScheduler.reconcileSchedules()
                android.util.Log.d("ScheduleReceiver", "Reconciliação concluída com sucesso. Próxima fronteira: $boundary")
            } catch (e: Exception) {
                android.util.Log.e("ScheduleReceiver", "Erro ao processar alarme em ScheduleReceiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
