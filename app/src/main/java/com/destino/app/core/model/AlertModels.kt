package com.destino.app.core.model

sealed interface DeliveryResult {
    data class Success(
        val isAudioPlaying: Boolean = true,
        val isNotificationPosted: Boolean = true,
        val isVibrating: Boolean = true
    ) : DeliveryResult

    data class Failed(
        val reason: String,
        val recoverable: Boolean = true,
        val isNotificationPosted: Boolean = false,
        val isVibrating: Boolean = false
    ) : DeliveryResult
}
