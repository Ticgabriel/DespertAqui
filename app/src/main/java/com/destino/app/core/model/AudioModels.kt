package com.destino.app.core.model

enum class SoundSourceType {
    SYSTEM_DEFAULT,
    SYSTEM_RINGTONE,
    USER_DOCUMENT,
    SILENT
}

data class SoundSelection(
    val type: SoundSourceType = SoundSourceType.SYSTEM_DEFAULT,
    val uriString: String? = null,
    val title: String = "Toque padrão do aparelho"
)

enum class VibrationPattern {
    SHORT,
    STRONG,
    INTERMITTENT,
    DISABLED
}

enum class AudioOutputPolicy {
    SYSTEM_DEFAULT,
    PREFER_HEADPHONES,
    PREFER_SPEAKER,
    HEADPHONES_VIBRATE_ONLY
}

enum class HeadphoneDisconnectBehavior {
    CONTINUE_WITH_SPEAKER,
    STOP_AUDIO_KEEP_VIBRATION
}

enum class MonitoringProfile {
    AUTOMATIC,
    HIGH_PRECISION,
    BATTERY_SAVER,
    CUSTOM
}
