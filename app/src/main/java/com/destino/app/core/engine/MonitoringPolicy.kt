package com.destino.app.core.engine

import com.destino.app.core.model.MonitoringProfile

enum class UrgencyLevel {
    DISTANT,
    APPROACHING,
    CONFIRMING_OR_CLOSE,
    RECOVERING
}

data class MonitoringProfileConfig(
    val distantIntervalMillis: Long,
    val approachingIntervalMillis: Long,
    val confirmingIntervalMillis: Long,
    val minUpdateIntervalMillis: Long = 1000L
) {
    fun getInterval(urgency: UrgencyLevel): Long = when (urgency) {
        UrgencyLevel.DISTANT -> distantIntervalMillis
        UrgencyLevel.APPROACHING -> approachingIntervalMillis
        UrgencyLevel.CONFIRMING_OR_CLOSE -> confirmingIntervalMillis
        UrgencyLevel.RECOVERING -> confirmingIntervalMillis
    }
}

object MonitoringPolicies {
    val AUTOMATIC = MonitoringProfileConfig(
        distantIntervalMillis = 20_000L,
        approachingIntervalMillis = 4_000L,
        confirmingIntervalMillis = 1_500L,
        minUpdateIntervalMillis = 1_000L
    )

    val HIGH_PRECISION = MonitoringProfileConfig(
        distantIntervalMillis = 4_000L,
        approachingIntervalMillis = 2_500L,
        confirmingIntervalMillis = 1_000L,
        minUpdateIntervalMillis = 1_000L
    )

    val BATTERY_SAVER = MonitoringProfileConfig(
        distantIntervalMillis = 45_000L,
        approachingIntervalMillis = 8_000L,
        confirmingIntervalMillis = 1_500L,
        minUpdateIntervalMillis = 1_000L
    )

    fun forProfile(profile: MonitoringProfile, customConfig: MonitoringProfileConfig? = null): MonitoringProfileConfig {
        return when (profile) {
            MonitoringProfile.AUTOMATIC -> AUTOMATIC
            MonitoringProfile.HIGH_PRECISION -> HIGH_PRECISION
            MonitoringProfile.BATTERY_SAVER -> BATTERY_SAVER
            MonitoringProfile.CUSTOM -> customConfig ?: AUTOMATIC
        }
    }
}
