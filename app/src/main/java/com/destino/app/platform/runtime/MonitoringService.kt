package com.destino.app.platform.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.platform.alerts.AlertNotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : Service() {

    companion object {
        const val ACTION_START = "com.destino.app.ACTION_START_MONITORING"
        const val ACTION_STOP = "com.destino.app.ACTION_STOP_MONITORING"
    }

    @Inject
    lateinit var coordinator: MonitoringCoordinator

    @Inject
    lateinit var notificationHelper: AlertNotificationHelper

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Publicar imediatamente a notificação de acompanhamento inicial
        val initialNotification = notificationHelper.buildMonitoringNotification(
            destinationName = "Destino",
            distanceText = "Calculando...",
            statusText = "Iniciando acompanhamento..."
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    AlertNotificationHelper.NOTIFICATION_MONITORING_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AlertNotificationHelper.NOTIFICATION_MONITORING_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(AlertNotificationHelper.NOTIFICATION_MONITORING_ID, initialNotification)
            }

            // Notificar o coordenador de que o serviço está confirmado em primeiro plano
            (coordinator as? MonitoringCoordinatorImpl)?.onServiceForegroundConfirmed()

            // Manter a CPU ativa durante o monitoramento (tela apagada)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "despertaqui:monitoring")
                .apply { acquire() }
        } catch (e: Exception) {
            (coordinator as? MonitoringCoordinatorImpl)?.onServiceStartFailed(e)
            stopSelf()
            return START_NOT_STICKY
        }

        observeCoordinatorState()
        return START_NOT_STICKY
    }

    private fun observeCoordinatorState() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            coordinator.operationalState.collectLatest { state ->
                val travelsCount = state.activeTravelsCount
                val destName = state.activeDestination?.name ?: "Destino"
                val distanceText = state.currentDistanceMeters?.let { dist ->
                    if (dist >= 1000) {
                        String.format("%.1f km (em linha reta)", dist / 1000.0)
                    } else {
                        "${dist.toInt()} m (em linha reta)"
                    }
                } ?: "Calculando..."

                val titleText = if (travelsCount > 1) {
                    "Acompanhando $travelsCount viagens"
                } else {
                    destName
                }

                val subtitleText = if (travelsCount > 1) {
                    "Mais próxima: $destName · $distanceText"
                } else {
                    distanceText
                }

                val updatedNotification = notificationHelper.buildMonitoringNotification(
                    destinationName = titleText,
                    distanceText = subtitleText,
                    statusText = state.statusMessage
                )

                notificationManager.notify(
                    AlertNotificationHelper.NOTIFICATION_MONITORING_ID,
                    updatedNotification
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stateObserverJob?.cancel()
        (coordinator as? MonitoringCoordinatorImpl)?.onServiceDestroyed()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
