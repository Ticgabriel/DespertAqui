package com.destino.app.platform.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.destino.app.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val CHANNEL_MONITORING = "destino_monitoring_channel"
        const val CHANNEL_ALARM = "destino_alarm_channel"

        const val NOTIFICATION_MONITORING_ID = 1001
        const val NOTIFICATION_ALARM_ID = 1002
        const val NOTIFICATION_PRECAUTION_ID = 1003

        const val ACTION_STOP_ALARM = "com.destino.app.ACTION_STOP_ALARM"
        const val ACTION_STOP_PRECAUTION = "com.destino.app.ACTION_STOP_PRECAUTION"
        const val EXTRA_ALERT_EVENT_ID = "extra_alert_event_id"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_OPEN_ALERT = "extra_open_alert"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitoringChannel = NotificationChannel(
                CHANNEL_MONITORING,
                "Acompanhamento da viagem",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação contínua durante o trajeto até o destino"
                setShowBadge(false)
            }

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM,
                "Alerta de chegada e precaução",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificação exibida ao se aproximar do destino ou em perda de sinal"
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
            }

            notificationManager.createNotificationChannel(monitoringChannel)
            notificationManager.createNotificationChannel(alarmChannel)
        }
    }

    fun buildMonitoringNotification(
        destinationName: String,
        distanceText: String,
        statusText: String
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setContentTitle("Destino: $destinationName")
            .setContentText("$distanceText • $statusText")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun showAlarmNotification(
        destinationName: String,
        alertEventId: String,
        sessionId: String? = null
    ) {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ALERT, true)
            putExtra(EXTRA_ALERT_EVENT_ID, alertEventId)
            if (sessionId != null) {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            alertEventId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(ACTION_STOP_ALARM).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ALERT_EVENT_ID, alertEventId)
            if (sessionId != null) {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            alertEventId.hashCode() + 1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setContentTitle("Seu destino está perto!")
            .setContentText("Aproximando-se de: $destinationName")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar alarme", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        notificationManager.notify(NOTIFICATION_ALARM_ID, notification)
    }

    fun showPrecautionNotification(
        destinationName: String,
        alertEventId: String,
        message: String,
        sessionId: String? = null
    ) {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ALERT, true)
            putExtra(EXTRA_ALERT_EVENT_ID, alertEventId)
            if (sessionId != null) {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            alertEventId.hashCode() + 2,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ackIntent = Intent(ACTION_STOP_PRECAUTION).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ALERT_EVENT_ID, alertEventId)
            if (sessionId != null) {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }
        val ackPendingIntent = PendingIntent.getBroadcast(
            context,
            alertEventId.hashCode() + 3,
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setContentTitle("Aviso de precaução — $destinationName")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(false)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Entendido", ackPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        notificationManager.notify(NOTIFICATION_PRECAUTION_ID, notification)
    }

    fun cancelAlarmNotification(alertEventId: String? = null) {
        notificationManager.cancel(NOTIFICATION_ALARM_ID)
        if (alertEventId != null) {
            notificationManager.cancel(NOTIFICATION_ALARM_ID + (alertEventId.hashCode() and 0x7FFF))
        }
    }

    fun cancelPrecautionNotification(alertEventId: String? = null) {
        notificationManager.cancel(NOTIFICATION_PRECAUTION_ID)
        if (alertEventId != null) {
            notificationManager.cancel(NOTIFICATION_PRECAUTION_ID + (alertEventId.hashCode() and 0x7FFF))
        }
    }
}
