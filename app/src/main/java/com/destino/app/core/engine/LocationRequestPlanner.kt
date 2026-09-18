package com.destino.app.core.engine

import com.destino.app.core.model.LocationConfig
import com.destino.app.core.model.MonitoringProfile
import javax.inject.Inject
import javax.inject.Singleton

data class DestinationMonitoringContext(
    val destinationId: String,
    val distanceMeters: Double?,
    val radiusMeters: Double,
    val uncertaintyMeters: Float? = null,
    val speedMps: Float? = null,
    val isWaitingExit: Boolean = false,
    val isConfirming: Boolean = false,
    val isDegraded: Boolean = false,
    val profile: MonitoringProfile = MonitoringProfile.AUTOMATIC
)

interface LocationRequestPlanner {
    fun planLocationRequest(
        contexts: List<DestinationMonitoringContext>,
        customProfileConfig: MonitoringProfileConfig? = null
    ): LocationConfig
}

@Singleton
class LocationRequestPlannerImpl @Inject constructor() : LocationRequestPlanner {

    override fun planLocationRequest(
        contexts: List<DestinationMonitoringContext>,
        customProfileConfig: MonitoringProfileConfig?
    ): LocationConfig {
        if (contexts.isEmpty()) {
            return LocationConfig(
                intervalMillis = 20_000L,
                minUpdateIntervalMillis = 1_000L,
                highAccuracy = true
            )
        }

        // Calcula o intervalo exigido por cada contexto e seleciona o menor (mais exigente)
        var minInterval = Long.MAX_VALUE
        var minFastestInterval = Long.MAX_VALUE
        var requiresHighAccuracy = false

        for (ctx in contexts) {
            val config = MonitoringPolicies.forProfile(ctx.profile, customProfileConfig)
            val urgency = calculateUrgency(ctx)
            val interval = config.getInterval(urgency)

            if (interval < minInterval) {
                minInterval = interval
            }
            if (config.minUpdateIntervalMillis < minFastestInterval) {
                minFastestInterval = config.minUpdateIntervalMillis
            }
            // Sempre usar HIGH_ACCURACY — BALANCED_POWER gera leituras imprecisas
            // (>50m) que são descartadas pelo motor, causando ciclo de perda de sinal
            requiresHighAccuracy = true
        }

        // Limites de segurança do produto (1s a 60s)
        val boundedInterval = minInterval.coerceIn(1_000L, 60_000L)
        val boundedFastest = minFastestInterval.coerceIn(500L, boundedInterval)

        return LocationConfig(
            intervalMillis = boundedInterval,
            minUpdateIntervalMillis = boundedFastest,
            highAccuracy = requiresHighAccuracy || boundedInterval <= 10_000L
        )
    }

    private fun calculateUrgency(ctx: DestinationMonitoringContext): UrgencyLevel {
        // Recuperação de sinal ou confirmação exige alta frequência
        if (ctx.isDegraded || ctx.isConfirming || ctx.isWaitingExit) {
            return UrgencyLevel.CONFIRMING_OR_CLOSE
        }

        val distance = ctx.distanceMeters ?: return UrgencyLevel.APPROACHING
        val uncertainty = ctx.uncertaintyMeters?.toDouble() ?: 20.0
        val radius = ctx.radiusMeters

        // Se já está próximo ou dentro do raio ampliado pela incerteza
        if (distance <= radius + uncertainty + 100.0) {
            return UrgencyLevel.CONFIRMING_OR_CLOSE
        }

        // Cálculo conservador de tempo até a borda: (distância - raio - incerteza) / velocidade
        val marginMeters = (distance - radius - uncertainty).coerceAtLeast(0.0)
        // Se a velocidade for desconhecida ou baixa, assume velocidade urbana razoável (15 m/s ≈ 54 km/h) para proteção
        val effectiveSpeedMps = ctx.speedMps?.toDouble()?.coerceAtLeast(5.0) ?: 15.0
        val timeToBorderSeconds = marginMeters / effectiveSpeedMps

        return when {
            timeToBorderSeconds <= 20.0 -> UrgencyLevel.CONFIRMING_OR_CLOSE
            timeToBorderSeconds <= 75.0 || distance <= radius * 2.5 -> UrgencyLevel.APPROACHING
            else -> UrgencyLevel.DISTANT
        }
    }
}
