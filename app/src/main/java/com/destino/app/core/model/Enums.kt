package com.destino.app.core.model

/**
 * Dimensões de estado do DespertAqui:
 * - Sessão: STARTING, ACTIVE, FINISHED, INTERRUPTED, FAILED
 * - Qualidade: ACQUIRING, GOOD, DEGRADED, BLOCKED
 * - Alerta: PENDING, PLAYING, ACKNOWLEDGED, DELIVERY_FAILED
 */
enum class SessionState {
    STARTING,
    ACTIVE,
    FINISHED,
    INTERRUPTED,
    FAILED
}

enum class QualityState {
    ACQUIRING,
    GOOD,
    DEGRADED,
    BLOCKED
}

enum class AlertState {
    PENDING,
    PLAYING,
    ACKNOWLEDGED,
    DELIVERY_FAILED
}

enum class AlertEventType {
    PROXIMITY,
    PRECAUTION
}

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK
}
