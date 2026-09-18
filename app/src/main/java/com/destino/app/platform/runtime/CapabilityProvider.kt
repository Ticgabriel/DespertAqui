package com.destino.app.platform.runtime

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.destino.app.platform.alerts.AlertNotificationHelper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class OperationPurpose {
    CONFIGURE,
    MANUAL_JOURNEY,
    AUTOMATIC_SCHEDULE,
    RECOVERY
}

enum class ChecklistStatus {
    PERMITTED,
    NEEDS_ATTENTION,
    OPTIONAL_ACTION,
    UNAVAILABLE
}

enum class ChecklistActionType {
    NONE,
    OPEN_APP_SETTINGS,
    OPEN_LOCATION_SETTINGS,
    REQUEST_EXACT_ALARM_SETTING,
    REQUEST_BATTERY_SETTING,
    REQUEST_NOTIFICATION_SETTINGS,
    REQUEST_DND_ACCESS,
    ADJUST_VOLUME
}

data class ChecklistItem(
    val id: String,
    val title: String,
    val status: ChecklistStatus,
    val description: String,
    val actionType: ChecklistActionType = ChecklistActionType.NONE,
    val actionLabel: String? = null
)

data class CapabilityReport(
    val canStartManualJourney: Boolean,
    val canScheduleAutomatic: Boolean,
    val manualImpediments: List<String>,
    val automaticImpediments: List<String>,
    val items: List<ChecklistItem>
)

data class SystemCapabilities(
    val hasFineLocationPermission: Boolean = false,
    val hasCoarseLocationPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val areNotificationsBlocked: Boolean = false,
    val isLocationProviderEnabled: Boolean = false,
    val isPlayServicesAvailable: Boolean = false,
    val hasBackgroundLocation: Boolean = false,
    val canScheduleExactAlarms: Boolean = true,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val isAlarmMuted: Boolean = false,
    val hasDoNotDisturbAccess: Boolean = false
) {
    val canStartMonitoring: Boolean
        get() = hasFineLocationPermission &&
                hasNotificationPermission &&
                !areNotificationsBlocked &&
                isLocationProviderEnabled &&
                isPlayServicesAvailable

    val missingCapabilityReason: String?
        get() = when {
            !hasFineLocationPermission -> "Permissão de localização precisa é obrigatória."
            !hasNotificationPermission -> "Permissão de notificação é obrigatória para manter o alerta visível."
            areNotificationsBlocked -> "As notificações do aplicativo ou os canais de alarme estão desativados nas configurações."
            !isLocationProviderEnabled -> "O serviço de localização (GPS) do aparelho está desligado."
            !isPlayServicesAvailable -> "Google Play Services não está disponível ou atualizado."
            else -> null
        }
}

@Singleton
open class CapabilityProvider protected constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") marker: Unit?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, null)

    constructor() : this(null, null)

    open fun getCapabilities(): SystemCapabilities {
        val ctx = context ?: return SystemCapabilities()
        val hasFine = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notificationsEnabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        var channelsBlocked = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null) {
                val monitoringChannel = notificationManager.getNotificationChannel(AlertNotificationHelper.CHANNEL_MONITORING)
                val alarmChannel = notificationManager.getNotificationChannel(AlertNotificationHelper.CHANNEL_ALARM)
                if (monitoringChannel != null && monitoringChannel.importance == NotificationManager.IMPORTANCE_NONE) {
                    channelsBlocked = true
                }
                if (alarmChannel != null && alarmChannel.importance == NotificationManager.IMPORTANCE_NONE) {
                    channelsBlocked = true
                }
            }
        }
        val areNotificationsBlocked = !notificationsEnabled || channelsBlocked

        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

        val playServicesCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)
        val isPlayServicesAvailable = (playServicesCode == ConnectionResult.SUCCESS)

        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        } else {
            true
        }

        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val alarmVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 1
        val isMuted = alarmVolume == 0
        val hasDndAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.isNotificationPolicyAccessGranted == true
        } else true

        return SystemCapabilities(
            hasFineLocationPermission = hasFine,
            hasCoarseLocationPermission = hasCoarse,
            hasNotificationPermission = hasNotif,
            areNotificationsBlocked = areNotificationsBlocked,
            isLocationProviderEnabled = isGpsEnabled,
            isPlayServicesAvailable = isPlayServicesAvailable,
            hasBackgroundLocation = hasBackground,
            canScheduleExactAlarms = canExact,
            isIgnoringBatteryOptimizations = isBatteryIgnored,
            isAlarmMuted = isMuted,
            hasDoNotDisturbAccess = hasDndAccess
        )
    }

    open fun getDetailedReport(): CapabilityReport {
        val caps = getCapabilities()
        val items = mutableListOf<ChecklistItem>()
        val manualImpediments = mutableListOf<String>()
        val automaticImpediments = mutableListOf<String>()

        // 1. Localização precisa
        if (caps.hasFineLocationPermission) {
            items.add(ChecklistItem("loc_fine", "Localização precisa", ChecklistStatus.PERMITTED, "Concedida para leitura exata de proximidade."))
        } else {
            val desc = if (caps.hasCoarseLocationPermission) "Permitida apenas aproximada. O DespertAqui exige localização precisa." else "Permissão não concedida."
            items.add(ChecklistItem("loc_fine", "Localização precisa", ChecklistStatus.NEEDS_ATTENTION, desc, ChecklistActionType.OPEN_APP_SETTINGS, "Permitir"))
            manualImpediments.add("Localização precisa")
            automaticImpediments.add("Localização precisa")
        }

        // 2. GPS do Aparelho
        if (caps.isLocationProviderEnabled) {
            items.add(ChecklistItem("gps", "GPS do aparelho", ChecklistStatus.PERMITTED, "Serviço de localização do sistema ativo."))
        } else {
            items.add(ChecklistItem("gps", "GPS do aparelho", ChecklistStatus.NEEDS_ATTENTION, "O sensor de localização está desligado no celular.", ChecklistActionType.OPEN_LOCATION_SETTINGS, "Ativar GPS"))
            manualImpediments.add("Sensor GPS desligado")
            automaticImpediments.add("Sensor GPS desligado")
        }

        // 3. Notificações do aplicativo
        if (caps.hasNotificationPermission && !caps.areNotificationsBlocked) {
            items.add(ChecklistItem("notif", "Notificações do app", ChecklistStatus.PERMITTED, "Notificações e canais de alerta ativos."))
        } else {
            items.add(ChecklistItem("notif", "Notificações do app", ChecklistStatus.NEEDS_ATTENTION, "Notificações bloqueadas ou canal de alarme desativado.", ChecklistActionType.REQUEST_NOTIFICATION_SETTINGS, "Configurar"))
            manualImpediments.add("Notificações desativadas")
            automaticImpediments.add("Notificações desativadas")
        }

        // 4. Localização em segundo plano (para rotinas automáticas)
        if (caps.hasBackgroundLocation) {
            items.add(ChecklistItem("loc_bg", "Localização em segundo plano", ChecklistStatus.PERMITTED, "Permite iniciar o acompanhamento automático quando o app estiver fechado."))
        } else {
            items.add(ChecklistItem("loc_bg", "Localização em segundo plano", ChecklistStatus.NEEDS_ATTENTION, "Necessário para ativação automática com o app em segundo plano.", ChecklistActionType.OPEN_APP_SETTINGS, "Ajustar"))
            automaticImpediments.add("Localização em segundo plano")
        }

        // 5. Alarmes e lembretes exatos
        if (caps.canScheduleExactAlarms) {
            items.add(ChecklistItem("exact_alarm", "Alarmes pontuais (AlarmManager)", ChecklistStatus.PERMITTED, "Autorizado a abrir janelas no horário exato configurado."))
        } else {
            items.add(ChecklistItem("exact_alarm", "Alarmes pontuais (AlarmManager)", ChecklistStatus.NEEDS_ATTENTION, "Sem permissão para alarmes exatos; o início da janela pode atrasar alguns minutos.", ChecklistActionType.REQUEST_EXACT_ALARM_SETTING, "Permitir"))
            automaticImpediments.add("Alarmes exatos")
        }

        // 6. Otimização de bateria
        if (caps.isIgnoringBatteryOptimizations) {
            items.add(ChecklistItem("battery", "Bateria sem restrições", ChecklistStatus.PERMITTED, "O aplicativo pode executar em segundo plano com menor risco de restrições pelo sistema."))
        } else {
            items.add(ChecklistItem("battery", "Otimização de bateria", ChecklistStatus.OPTIONAL_ACTION, "O modo de economia pode atrasar leituras com a tela apagada.", ChecklistActionType.REQUEST_BATTERY_SETTING, "Remover restrição"))
        }

        // 7. Volume do alarme
        if (!caps.isAlarmMuted) {
            items.add(ChecklistItem("volume", "Volume do alarme", ChecklistStatus.PERMITTED, "Volume do canal de alarme audível."))
        } else {
            items.add(ChecklistItem("volume", "Volume do alarme", ChecklistStatus.OPTIONAL_ACTION, "O volume de alarme está no mudo (zero).", ChecklistActionType.ADJUST_VOLUME, "Aumentar volume"))
        }

        if (caps.hasDoNotDisturbAccess) {
            items.add(ChecklistItem("dnd", "Não Perturbe", ChecklistStatus.PERMITTED, "O app pode respeitar e administrar a entrega durante o modo Não Perturbe."))
        } else {
            items.add(ChecklistItem("dnd", "Não Perturbe", ChecklistStatus.OPTIONAL_ACTION, "Autorize para reduzir o risco de o sistema silenciar o alarme.", ChecklistActionType.REQUEST_DND_ACCESS, "Configurar"))
        }

        // 8. Google Play Services
        if (caps.isPlayServicesAvailable) {
            items.add(ChecklistItem("play_services", "Google Play Services", ChecklistStatus.PERMITTED, "Infraestrutura de localização disponível."))
        } else {
            items.add(ChecklistItem("play_services", "Google Play Services", ChecklistStatus.NEEDS_ATTENTION, "Play Services desatualizado ou indisponível."))
            manualImpediments.add("Google Play Services")
            automaticImpediments.add("Google Play Services")
        }

        return CapabilityReport(
            canStartManualJourney = manualImpediments.isEmpty(),
            canScheduleAutomatic = automaticImpediments.isEmpty(),
            manualImpediments = manualImpediments,
            automaticImpediments = automaticImpediments,
            items = items
        )
    }
}
