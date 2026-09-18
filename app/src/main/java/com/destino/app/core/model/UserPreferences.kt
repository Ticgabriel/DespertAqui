package com.destino.app.core.model

data class UserPreferences(
    val defaultRadiusMeters: Double = 500.0,
    val isVibrationEnabled: Boolean = true,
    val theme: AppTheme = AppTheme.LIGHT,
    val draftDestinationName: String = "",
    val draftLatitude: String = "",
    val draftLongitude: String = "",
    val defaultSoundSelection: SoundSelection = SoundSelection(),
    val defaultVibrationPattern: VibrationPattern = VibrationPattern.STRONG,
    val defaultAudioOutputPolicy: AudioOutputPolicy = AudioOutputPolicy.SYSTEM_DEFAULT,
    val defaultHeadphoneDisconnectBehavior: HeadphoneDisconnectBehavior = HeadphoneDisconnectBehavior.CONTINUE_WITH_SPEAKER,
    val defaultMonitoringProfile: MonitoringProfile = MonitoringProfile.AUTOMATIC,
    val alarmAudioDurationMinutes: Int = 3,
    val isGradualVolumeEnabled: Boolean = false,
    val historyRetentionDays: Int = 30,
    val isWeakSignalWarningEnabled: Boolean = true,
    val allowNewEntrySameWindow: Boolean = false,
    val customDistantIntervalSeconds: Int = 30,
    val customApproachingIntervalSeconds: Int = 5
)
