package com.destino.app.platform.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class AlarmActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var coordinator: MonitoringCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(AlertNotificationHelper.EXTRA_ALERT_EVENT_ID) ?: ""
        if (eventId.isNotBlank()) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    coordinator.execute(
                        MonitoringCommand.AcknowledgeAlert(
                            commandId = UUID.randomUUID().toString(),
                            eventId = eventId
                        )
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
