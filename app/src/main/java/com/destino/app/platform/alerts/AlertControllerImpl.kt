package com.destino.app.platform.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.datastore.core.DataStore
import com.destino.app.core.model.AlertController
import com.destino.app.core.model.AlertEvent
import com.destino.app.core.model.AlertEventType
import com.destino.app.core.model.AudioOutputPolicy
import com.destino.app.core.model.DeliveryResult
import com.destino.app.core.model.HeadphoneDisconnectBehavior
import com.destino.app.core.model.SoundSelection
import com.destino.app.core.model.VibrationPattern
import com.destino.app.core.model.UserPreferences
import com.destino.app.platform.audio.AudioRouteObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationHelper: AlertNotificationHelper,
    private val soundResolver: SoundResolver,
    private val audioRouteObserver: AudioRouteObserver,
    private val preferencesDataStore: DataStore<UserPreferences>
) : AlertController {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoStopJob: Job? = null
    private var volumeRampJob: Job? = null

    private var activeEventId: String? = null
    private var activeEventType: AlertEventType? = null
    private var activePlaybackAttemptId: String? = null
    private var isTesting = false
    private var isPreviewing = false
    private var configuredDurationMinutes = 3
    private var configuredGradualVolume = false
    private var activeAudioOutputPolicy: AudioOutputPolicy = AudioOutputPolicy.SYSTEM_DEFAULT

    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override var onPlaybackError: ((eventId: String, playbackAttemptId: String, errorMsg: String) -> Unit)? = null
    private var onPlaybackFinished: ((eventId: String) -> Unit)? = null

    override fun setOnPlaybackFinishedListener(listener: ((eventId: String) -> Unit)?) {
        onPlaybackFinished = listener
    }

    init {
        scope.launch {
            preferencesDataStore.data.collectLatest { preferences ->
                configuredDurationMinutes = preferences.alarmAudioDurationMinutes
                configuredGradualVolume = preferences.isGradualVolumeEnabled
                audioRouteObserver.setPreferredPolicy(preferences.defaultAudioOutputPolicy)
                audioRouteObserver.setDisconnectBehavior(preferences.defaultHeadphoneDisconnectBehavior)
            }
        }
        scope.launch {
            audioRouteObserver.routeState.collectLatest { routeState ->
                synchronized(lock) {
                    if (mediaPlayer?.isPlaying == true && activeAudioOutputPolicy == AudioOutputPolicy.HEADPHONES_VIBRATE_ONLY) {
                        if (routeState.isHeadphonesConnected) {
                            val currentEventId = activeEventId
                            releasePlayerOnly()
                            abandonAudioFocus()
                            if (currentEventId != null) {
                                onPlaybackFinished?.invoke(currentEventId)
                            }
                        }
                    }
                }
            }
        }
        audioRouteObserver.onBecomingNoisy = {
            synchronized(lock) {
                if (mediaPlayer?.isPlaying == true) {
                    val behavior = audioRouteObserver.routeState.value.disconnectBehavior
                    if (behavior == HeadphoneDisconnectBehavior.STOP_AUDIO_KEEP_VIBRATION) {
                        val currentEventId = activeEventId
                        releasePlayerOnly()
                        abandonAudioFocus()
                        if (currentEventId != null) {
                            onPlaybackFinished?.invoke(currentEventId)
                        }
                    }
                }
            }
        }
    }

    override suspend fun deliver(
        event: AlertEvent,
        destinationName: String,
        isVibrationEnabled: Boolean
    ): DeliveryResult {
        return deliverWithOptions(
            event = event,
            destinationName = destinationName,
            isVibrationEnabled = isVibrationEnabled,
            soundSelection = SoundSelection(),
            vibrationPattern = VibrationPattern.STRONG,
            audioOutputPolicy = AudioOutputPolicy.SYSTEM_DEFAULT,
            maxDurationMinutes = 3,
            isGradualVolume = false
        )
    }

    override suspend fun deliverWithOptions(
        event: AlertEvent,
        destinationName: String,
        isVibrationEnabled: Boolean,
        soundSelection: SoundSelection,
        vibrationPattern: VibrationPattern,
        audioOutputPolicy: AudioOutputPolicy,
        maxDurationMinutes: Int,
        isGradualVolume: Boolean
    ): DeliveryResult = withContext(Dispatchers.Main) {
        synchronized(lock) {
            // Alerta real interrompe prévias e testes imediatamente
            if (isTesting || isPreviewing) {
                stopPlayingInternal()
                isTesting = false
                isPreviewing = false
                autoStopJob?.cancel()
            }

            // Alarme de chegada interrompe aviso de precaução
            if (event.eventType == AlertEventType.PROXIMITY && activeEventType == AlertEventType.PRECAUTION) {
                stopPlayingInternal()
                notificationHelper.cancelPrecautionNotification()
            } else if (event.eventType == AlertEventType.PRECAUTION && activeEventType == AlertEventType.PROXIMITY) {
                // Alarme de proximidade tem prioridade máxima. Aviso de precaução não rebaixa nem silencia chegada.
                return@withContext DeliveryResult.Failed("Alarme de proximidade já está ativo. Aviso de precaução ignorado.", recoverable = false)
            }

            activeEventId = event.id
            activeEventType = event.eventType
            activeAudioOutputPolicy = audioOutputPolicy
        }

        val isPrecaution = (event.eventType == AlertEventType.PRECAUTION)

        // Verificar se política de fones silencia áudio ("Com fones, usar só vibração")
        val headphonesConnected = audioRouteObserver.routeState.value.isHeadphonesConnected
        val shouldSilenceDueToHeadphones = (audioOutputPolicy == AudioOutputPolicy.HEADPHONES_VIBRATE_ONLY && headphonesConnected)

        // Resolver e iniciar áudio fora da UI thread
        var audioSuccess = false
        if (!shouldSilenceDueToHeadphones) {
            try {
                val soundUri = soundResolver.resolveSoundUri(soundSelection)
                if (soundUri != null) {
                    audioSuccess = withContext(Dispatchers.IO) {
                        startAlarmPlayback(soundUri, isLooping = !isPrecaution, audioOutputPolicy = audioOutputPolicy, isGradualVolume = configuredGradualVolume || isGradualVolume)
                    }
                }
            } catch (e: Exception) {
                audioSuccess = false
            }
        }

        // Iniciar vibração
        val canVibrate = isVibrationEnabled && vibrator.hasVibrator() && vibrationPattern != VibrationPattern.DISABLED
        val isVibrating = if (canVibrate) {
            startVibration(pattern = vibrationPattern, isLooping = !isPrecaution, isPrecaution = isPrecaution)
        } else {
            false
        }

        // Notificação no sistema
        if (isPrecaution) {
            notificationHelper.showPrecautionNotification(
                destinationName = destinationName,
                alertEventId = event.id,
                message = event.failureReason ?: "Sinal de localização perdido perto do destino.",
                sessionId = event.sessionId
            )
            autoStopJob?.cancel()
            autoStopJob = scope.launch {
                delay(4000L) // Precaução toca 4s
                synchronized(lock) {
                    if (activeEventId == event.id && activeEventType == AlertEventType.PRECAUTION) {
                        stopPlayingInternal()
                        onPlaybackFinished?.invoke(event.id)
                    }
                }
            }
        } else {
            notificationHelper.showAlarmNotification(
                destinationName = destinationName,
                alertEventId = event.id,
                sessionId = event.sessionId
            )
            // Duração configurada do alarme (ex: 1, 3, 5 minutos)
            val durationToUse = if (maxDurationMinutes > 0) maxDurationMinutes else configuredDurationMinutes
            if (durationToUse > 0) {
                autoStopJob?.cancel()
                autoStopJob = scope.launch {
                    delay(durationToUse * 60_000L)
                    synchronized(lock) {
                        if (activeEventId == event.id && activeEventType == AlertEventType.PROXIMITY) {
                            // Encerra som e vibração mantendo o evento e a notificação visíveis até reconhecimento
                            stopPlayingInternal()
                            onPlaybackFinished?.invoke(event.id)
                        }
                    }
                }
            }
        }

        val intentionallySilent = soundSelection.type == com.destino.app.core.model.SoundSourceType.SILENT
        if (audioSuccess || shouldSilenceDueToHeadphones || intentionallySilent) {
            DeliveryResult.Success(
                isAudioPlaying = audioSuccess,
                isNotificationPosted = true,
                isVibrating = isVibrating
            )
        } else {
            DeliveryResult.Failed(
                reason = "Áudio indisponível; notificação ativa (vibração: $isVibrating).",
                recoverable = true,
                isNotificationPosted = true,
                isVibrating = isVibrating
            )
        }
    }

    override suspend fun previewSound(soundSelection: SoundSelection): DeliveryResult = withContext(Dispatchers.Main) {
        synchronized(lock) {
            if (activeEventId != null && activeEventType == AlertEventType.PROXIMITY) {
                return@withContext DeliveryResult.Failed("Alarme ativo em andamento. Prévia recusada.")
            }
            stopPlayingInternal()
            isPreviewing = true
        }

        val intentionallySilent = soundSelection.type == com.destino.app.core.model.SoundSourceType.SILENT
        val soundUri = soundResolver.resolveSoundUri(soundSelection)
        val success = if (soundUri != null) {
            withContext(Dispatchers.IO) {
                startAlarmPlayback(soundUri, isLooping = true, audioOutputPolicy = AudioOutputPolicy.SYSTEM_DEFAULT, isGradualVolume = false)
            }
        } else intentionallySilent

        val canVibrate = vibrator.hasVibrator()
        val isVibrating = if (canVibrate) startVibration(pattern = VibrationPattern.SHORT, isLooping = false, isPrecaution = false) else false

        autoStopJob?.cancel()
        autoStopJob = scope.launch {
            delay(4000L) // Prévia de 4 segundos
            synchronized(lock) {
                if (isPreviewing) {
                    stopPlayingInternal()
                    isPreviewing = false
                }
            }
        }

        if (success) {
            DeliveryResult.Success(isAudioPlaying = !intentionallySilent, isNotificationPosted = false, isVibrating = isVibrating)
        } else {
            DeliveryResult.Failed(reason = "Não foi possível reproduzir o som selecionado", recoverable = true)
        }
    }

    override suspend fun test(): DeliveryResult = withContext(Dispatchers.Main) {
        previewSound(SoundSelection())
    }

    override suspend fun stopPreview() = withContext(Dispatchers.Main) {
        synchronized(lock) {
            if (isPreviewing || isTesting) {
                isPreviewing = false
                isTesting = false
                autoStopJob?.cancel()
                volumeRampJob?.cancel()
                stopPlayingInternal()
            }
        }
    }

    override suspend fun acknowledge(eventId: String) = withContext(Dispatchers.Main) {
        synchronized(lock) {
            if (activeEventId == eventId || eventId.isEmpty()) {
                activeEventId = null
                activeEventType = null
                isTesting = false
                isPreviewing = false
                autoStopJob?.cancel()
                volumeRampJob?.cancel()
                stopPlayingInternal()
                notificationHelper.cancelAlarmNotification(eventId.ifEmpty { null })
                notificationHelper.cancelPrecautionNotification(eventId.ifEmpty { null })
            }
        }
    }

    private fun startAlarmPlayback(
        uri: Uri,
        isLooping: Boolean,
        audioOutputPolicy: AudioOutputPolicy,
        isGradualVolume: Boolean
    ): Boolean {
        acquireWakeLock()
        requestAudioFocus()

        return try {
            releasePlayerOnly()
            val player = MediaPlayer()
            val currentAttemptId = java.util.UUID.randomUUID().toString()
            val targetEventId = activeEventId ?: ""
            synchronized(lock) {
                activePlaybackAttemptId = currentAttemptId
            }

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(context, uri)
            player.isLooping = isLooping

            // MediaPlayer.setPreferredDevice só existe a partir do Android 9 (API 28).
            // Em Android 8, o sistema mantém o roteamento de áudio padrão.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                if (audioOutputPolicy == AudioOutputPolicy.PREFER_SPEAKER) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let {
                        player.preferredDevice = it
                    }
                } else if (audioOutputPolicy == AudioOutputPolicy.PREFER_HEADPHONES) {
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }?.let {
                        player.preferredDevice = it
                    }
                }
            }

            player.setOnErrorListener { mp, what, extra ->
                val belongsToActivePlayback = synchronized(lock) {
                    val isActive = mediaPlayer == mp &&
                        activeEventId == targetEventId &&
                        activePlaybackAttemptId == currentAttemptId
                    if (isActive) {
                        mediaPlayer = null
                        activePlaybackAttemptId = null
                    }
                    isActive
                }
                try {
                    mp.release()
                } catch (e: Exception) {}
                if (belongsToActivePlayback) {
                    onPlaybackError?.invoke(
                        targetEventId,
                        currentAttemptId,
                        "Falha na reprodução do alerta (what=$what, extra=$extra)"
                    )
                }
                true
            }

            if (!isLooping) {
                player.setOnCompletionListener { mp ->
                    synchronized(lock) {
                        if (mediaPlayer == mp && activeEventId == targetEventId) {
                            stopPlayingInternal()
                            onPlaybackFinished?.invoke(targetEventId)
                        }
                    }
                }
            }

            player.prepare()

            if (isGradualVolume) {
                player.setVolume(0.1f, 0.1f)
                volumeRampJob?.cancel()
                volumeRampJob = scope.launch {
                    var currentGain = 0.1f
                    while (isActive && currentGain < 1.0f) {
                        delay(500L)
                        currentGain = minOf(1.0f, currentGain + 0.1f)
                        synchronized(lock) {
                            mediaPlayer?.setVolume(currentGain, currentGain)
                        }
                    }
                }
            } else {
                player.setVolume(1.0f, 1.0f)
            }

            synchronized(lock) {
                mediaPlayer = player
            }
            player.start()
            true
        } catch (e: Exception) {
            releasePlayerOnly()
            false
        }
    }

    private fun startVibration(pattern: VibrationPattern, isLooping: Boolean, isPrecaution: Boolean): Boolean {
        if (!vibrator.hasVibrator()) return false

        val timings: LongArray
        val amplitudes: IntArray

        if (isPrecaution) {
            timings = longArrayOf(0, 200, 150, 200)
            amplitudes = intArrayOf(0, 180, 0, 180)
        } else {
            when (pattern) {
                VibrationPattern.SHORT -> {
                    timings = longArrayOf(0, 250, 200, 250)
                    amplitudes = intArrayOf(0, 200, 0, 200)
                }
                VibrationPattern.STRONG -> {
                    timings = longArrayOf(0, 500, 250, 500, 250)
                    amplitudes = intArrayOf(0, 255, 0, 255, 0)
                }
                VibrationPattern.INTERMITTENT -> {
                    timings = longArrayOf(0, 300, 500, 300, 500)
                    amplitudes = intArrayOf(0, 220, 0, 220, 0)
                }
                VibrationPattern.DISABLED -> return false
            }
        }

        val repeatIndex = if (isLooping) 0 else -1

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, repeatIndex)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, repeatIndex)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun stopPlayingInternal() {
        releasePlayerOnly()
        activePlaybackAttemptId = null
        volumeRampJob?.cancel()
        try {
            vibrator.cancel()
        } catch (e: Exception) {}
        abandonAudioFocus()
        releaseWakeLock()
    }

    private fun releasePlayerOnly() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {}
        mediaPlayer = null
    }

    private fun requestAudioFocus(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        synchronized(lock) {
                            if (activeEventType == AlertEventType.PRECAUTION || isTesting || isPreviewing) {
                                val currentEventId = activeEventId
                                stopPlayingInternal()
                                if (currentEventId != null) {
                                    onPlaybackFinished?.invoke(currentEventId)
                                }
                            }
                        }
                    }
                }
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "destino:alert_wake_lock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(30_000L)
        } catch (e: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {}
    }
}
