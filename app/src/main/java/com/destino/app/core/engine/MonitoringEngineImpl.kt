package com.destino.app.core.engine

import com.destino.app.core.model.EngineDecision
import com.destino.app.core.model.EngineEffect
import com.destino.app.core.model.EngineEvent
import com.destino.app.core.model.EngineState
import com.destino.app.core.model.LocationEvent
import com.destino.app.core.model.MonitoringEngine
import com.destino.app.core.model.QualityState

class MonitoringEngineImpl : MonitoringEngine {

    companion object {
        const val MAX_SAMPLE_AGE_MS = 10_000L
        const val MAX_CONFIRMATION_WINDOW_MS = 15_000L
        const val DEGRADED_TIMEOUT_MS = 15_000L
        const val DEFAULT_CONSERVATIVE_SPEED_MPS = 5.0f
        const val MAX_PLAUSIBLE_SPEED_MPS = 100.0
        const val EXIT_HYSTERESIS_METERS = 50.0
    }

    override fun reduce(state: EngineState, event: EngineEvent): EngineDecision {
        val baseState = if (state.acquisitionStartedAtMonotonicMs == 0L) {
            val startMs = when (event) {
                is EngineEvent.LocationReceived -> event.monotonicTimeMs
                is EngineEvent.ClockTick -> event.monotonicTimeMs
                is EngineEvent.UserAcknowledged -> event.monotonicTimeMs
            }
            state.copy(acquisitionStartedAtMonotonicMs = startMs)
        } else {
            state
        }
        return when (event) {
            is EngineEvent.LocationReceived -> {
                val stateWithContinuity = if (event.continuityBroken) {
                    baseState.copy(
                        pendingConfirmationReading = null,
                        pendingConfirmationSinceMonotonicMs = 0L,
                        consecutiveUsefulReadingsSinceLoss = 0
                    )
                } else {
                    baseState
                }
                handleLocation(stateWithContinuity, event.reading, event.monotonicTimeMs)
            }
            is EngineEvent.ClockTick -> handleClockTick(baseState, event.monotonicTimeMs)
            is EngineEvent.UserAcknowledged -> handleAcknowledge(baseState, event.monotonicTimeMs)
        }
    }

    private fun handleLocation(
        state: EngineState,
        reading: LocationEvent.NewLocation,
        currentMonotonicMs: Long
    ): EngineDecision {
        val effects = mutableListOf<EngineEffect>()

        // 1. Rejeitar coordenadas não finitas ou fora dos limites globais
        val lat = reading.coordinates.latitude
        val lon = reading.coordinates.longitude
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return EngineDecision(state.copy(lastReceivedReading = reading, consecutiveUsefulReadingsSinceLoss = 0))
        }

        // 2. Rejeitar precisão inválida
        if (reading.accuracyMeters <= 0f || !reading.accuracyMeters.isFinite()) {
            return EngineDecision(state.copy(lastReceivedReading = reading, consecutiveUsefulReadingsSinceLoss = 0))
        }

        // 3. Ignorar amostras repetidas ou fora de ordem cronológica monotônica
        val prevReading = state.lastUsefulReading
        if (prevReading != null && reading.monotonicTimeMs <= prevReading.monotonicTimeMs) {
            return EngineDecision(state.copy(lastReceivedReading = reading))
        }

        // 4. Calcular idade exclusivamente por relógio monotônico
        val sampleAgeMs = maxOf(0L, currentMonotonicMs - reading.monotonicTimeMs)
        val isFreshSample = sampleAgeMs <= MAX_SAMPLE_AGE_MS

        // 5. Verificar salto implausível (> 100 m/s descontando incertezas de ambas as leituras)
        if (prevReading != null) {
            val deltaSec = (reading.monotonicTimeMs - prevReading.monotonicTimeMs) / 1000.0
            if (deltaSec > 0) {
                val distBetween = Haversine.distanceMeters(
                    reading.coordinates.latitude, reading.coordinates.longitude,
                    prevReading.coordinates.latitude, prevReading.coordinates.longitude
                )
                val effectiveDistance = distBetween - reading.accuracyMeters - prevReading.accuracyMeters
                val jumpSpeed = effectiveDistance / deltaSec
                if (jumpSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
                    // Salto implausível: limpar confirmação pendente e não atualizar leitura útil
                    val updatedState = state.copy(
                        lastReceivedReading = reading,
                        pendingConfirmationReading = null,
                        pendingConfirmationSinceMonotonicMs = 0L
                    )
                    return EngineDecision(updatedState)
                }
            }
        }

        // Calcular distância ao destino
        val dest = state.destination.coordinates
        val distanceMeters = Haversine.distanceMeters(lat, lon, dest.latitude, dest.longitude)
        val radius = state.alarmDefinition.radiusMeters

        // Calcular velocidade conservadora e incerteza
        val conservativeSpeed = if (reading.speedMps != null && reading.speedMps == 0.0f && isFreshSample) {
            0.0f
        } else {
            maxOf(DEFAULT_CONSERVATIVE_SPEED_MPS, reading.speedMps ?: 0.0f)
        }
        val ageSeconds = sampleAgeMs / 1000.0
        val uncertainty = reading.accuracyMeters + (conservativeSpeed * ageSeconds)

        // Precisão exigida proporcional à distância ao destino:
        // - Distante (>3x raio): aceita até 200m (evita rejeitar leituras de rede)
        // - Aproximando (>1.5x raio): aceita até 100m
        // - Perto: mantém rigoroso em min(50m, raio/2)
        val maxAllowedAccuracy = when {
            distanceMeters > radius * 3 -> minOf(200.0, radius)
            distanceMeters > radius * 1.5 -> minOf(100.0, radius / 2.0)
            else -> minOf(50.0, radius / 2.0)
        }
        val hasGoodAccuracy = reading.accuracyMeters <= maxAllowedAccuracy

        // Definição de leitura útil: precisa e atual
        val isUsefulReading = hasGoodAccuracy && isFreshSample

        // Se a leitura for rejeitada por não ser útil (ex: antiga ou imprecisa):
        // Ela NÃO atualiza lastUsefulReading nem confirma chegada
        if (!isUsefulReading) {
            // Leitura não útil serve apenas como avaliação de risco, não confirmação,
            // e zera a contagem de leituras úteis consecutivas de recuperação
            val updatedState = state.copy(
                lastReceivedReading = reading,
                consecutiveUsefulReadingsSinceLoss = 0
            )
            return EngineDecision(updatedState)
        }

        // A partir daqui, a leitura é comprovadamente ÚTIL e FRESCA
        // Tratar restauração de qualidade: exige 2 leituras úteis consecutivas após degradação
        val newConsecutiveUseful = state.consecutiveUsefulReadingsSinceLoss + 1
        val newQuality = if (newConsecutiveUseful >= 2 || state.qualityState != QualityState.DEGRADED) {
            QualityState.GOOD
        } else {
            state.qualityState
        }

        if (state.qualityState != newQuality) {
            effects.add(EngineEffect.UpdateQuality(newQuality))
        }

        // Cenário A: Iniciar já dentro da área -> WAITING_EXIT
        if (state.lastUsefulReading == null && distanceMeters <= radius) {
            effects.add(EngineEffect.EnterWaitingExit("Você já está na área do aviso."))
            val updatedState = state.copy(
                runtime = state.runtime.copy(waitingExit = true),
                lastReceivedReading = reading,
                lastUsefulReading = reading,
                lastUsefulReadingMonotonicMs = reading.monotonicTimeMs,
                lastDistanceMeters = distanceMeters,
                qualityState = newQuality,
                consecutiveOutsideUsefulReadings = 0,
                consecutiveUsefulReadingsSinceLoss = newConsecutiveUseful
            )
            return EngineDecision(updatedState, effects)
        }

        // Cenário B: Em WAITING_EXIT -> aguardar término de cooldown e 2 leituras úteis fora com: distância - incerteza > raio + 50 m
        if (state.runtime.waitingExit) {
            val inCooldown = currentMonotonicMs < state.runtime.cooldownUntilMonotonicMs
            val isWellOutside = !inCooldown && (distanceMeters - uncertainty) > (radius + EXIT_HYSTERESIS_METERS)
            val newOutsideCount = if (isWellOutside) state.consecutiveOutsideUsefulReadings + 1 else 0

            return if (newOutsideCount >= 2) {
                // Saiu da área com duas leituras úteis comprovadas e cooldown superado! Rearmar!
                val updatedState = state.copy(
                    runtime = state.runtime.copy(waitingExit = false, isArmed = true),
                    lastReceivedReading = reading,
                    lastUsefulReading = reading,
                    lastUsefulReadingMonotonicMs = reading.monotonicTimeMs,
                    lastDistanceMeters = distanceMeters,
                    qualityState = newQuality,
                    consecutiveOutsideUsefulReadings = 0,
                    consecutiveUsefulReadingsSinceLoss = newConsecutiveUseful
                )
                EngineDecision(updatedState, effects)
            } else {
                val updatedState = state.copy(
                    lastReceivedReading = reading,
                    lastUsefulReading = reading,
                    lastUsefulReadingMonotonicMs = reading.monotonicTimeMs,
                    lastDistanceMeters = distanceMeters,
                    qualityState = newQuality,
                    consecutiveOutsideUsefulReadings = newOutsideCount,
                    consecutiveUsefulReadingsSinceLoss = newConsecutiveUseful
                )
                EngineDecision(updatedState, effects)
            }
        }

        // Cenário C: Monitoramento ativo regular
        var newRuntime = state.runtime
        var newPendingReading = state.pendingConfirmationReading
        var newPendingSinceMs = state.pendingConfirmationSinceMonotonicMs

        if (state.runtime.isArmed) {
            val isImmediateTrigger = (distanceMeters + uncertainty) <= radius
            val currentCycle = state.runtime.confirmationsCount + 1

            if (isImmediateTrigger) {
                // Confirmação imediata
                effects.add(
                    EngineEffect.TriggerArrivalAlert(
                        sessionId = state.session.id,
                        cycle = currentCycle,
                        destinationId = state.destination.id,
                        distanceMeters = distanceMeters
                    )
                )
                effects.add(
                    EngineEffect.ConsumeAlarm(
                        destinationId = state.destination.id,
                        sessionId = state.session.id
                    )
                )
                newRuntime = newRuntime.copy(isArmed = false, confirmationsCount = currentCycle)
                newPendingReading = null
                newPendingSinceMs = 0L
            } else if (distanceMeters <= radius) {
                // Leitura útil dentro do raio que não satisfez a margem de incerteza imediata
                if (newPendingReading == null) {
                    newPendingReading = reading
                    newPendingSinceMs = reading.monotonicTimeMs
                } else {
                    // Segunda leitura útil dentro do raio recebida! Verificar janela de até 5 segundos
                    val elapsedSinceFirst = reading.monotonicTimeMs - newPendingSinceMs
                    if (elapsedSinceFirst in 1..MAX_CONFIRMATION_WINDOW_MS) {
                        // Confirmado com 2 leituras comprovadamente úteis em até 5 segundos
                        effects.add(
                            EngineEffect.TriggerArrivalAlert(
                                sessionId = state.session.id,
                                cycle = currentCycle,
                                destinationId = state.destination.id,
                                distanceMeters = distanceMeters
                            )
                        )
                        effects.add(
                            EngineEffect.ConsumeAlarm(
                                destinationId = state.destination.id,
                                sessionId = state.session.id
                            )
                        )
                        newRuntime = newRuntime.copy(isArmed = false, confirmationsCount = currentCycle)
                        newPendingReading = null
                        newPendingSinceMs = 0L
                    } else {
                        // Janela expirada, reiniciar pendência com a leitura atual útil
                        newPendingReading = reading
                        newPendingSinceMs = reading.monotonicTimeMs
                    }
                }
            } else {
                // Leitura útil fora do raio (distância > raio) limpa confirmação pendente
                newPendingReading = null
                newPendingSinceMs = 0L
            }
        }

        val updatedState = state.copy(
            runtime = newRuntime,
            qualityState = newQuality,
            lastReceivedReading = reading,
            lastUsefulReading = reading,
            lastUsefulReadingMonotonicMs = reading.monotonicTimeMs,
            lastDistanceMeters = distanceMeters,
            pendingConfirmationReading = newPendingReading,
            pendingConfirmationSinceMonotonicMs = newPendingSinceMs,
            consecutiveUsefulReadingsSinceLoss = newConsecutiveUseful,
            precautionEmittedForEpisode = if (newConsecutiveUseful >= 2) false else state.precautionEmittedForEpisode
        )

        return EngineDecision(updatedState, effects)
    }

    private fun handleClockTick(state: EngineState, monotonicTimeMs: Long): EngineDecision {
        val effects = mutableListOf<EngineEffect>()
        var updatedQuality = state.qualityState
        var precautionEmitted = state.precautionEmittedForEpisode
        var precautionEpisodes = state.precautionEpisodeCount
        var pendingReading = state.pendingConfirmationReading
        var pendingSinceMs = state.pendingConfirmationSinceMonotonicMs
        var consecutiveUseful = state.consecutiveUsefulReadingsSinceLoss

        // 1. Verificar confirmação pendente expirada (> 5s)
        if (pendingReading != null && (monotonicTimeMs - pendingSinceMs) > MAX_CONFIRMATION_WINDOW_MS) {
            val lastDist = state.lastDistanceMeters
            val radius = state.alarmDefinition.radiusMeters
            if (lastDist != null && lastDist <= radius && !precautionEmitted) {
                precautionEpisodes++
                effects.add(
                    EngineEffect.EmitPrecautionAlert(
                        sessionId = state.session.id,
                        cycle = precautionEpisodes,
                        message = "Possível aproximação do destino sem confirmação de sinal."
                    )
                )
                precautionEmitted = true
            }
            pendingReading = null
            pendingSinceMs = 0L
        }

        // 2. Verificar perda de sinal contextual com base no perfil de monitoramento
        val degradedTimeout = getDegradedTimeoutMs(state)
        if (state.lastUsefulReadingMonotonicMs > 0) {
            val timeSinceLastUseful = monotonicTimeMs - state.lastUsefulReadingMonotonicMs
            if (timeSinceLastUseful > degradedTimeout) {
                consecutiveUseful = 0
                if (updatedQuality != QualityState.DEGRADED) {
                    updatedQuality = QualityState.DEGRADED
                    effects.add(EngineEffect.UpdateQuality(QualityState.DEGRADED))
                }

                // Emitir precaução se o deslocamento possível alcançar a área do aviso
                val lastDist = state.lastDistanceMeters
                val radius = state.alarmDefinition.radiusMeters
                if (lastDist != null && state.runtime.isArmed && !precautionEmitted) {
                    val conservativeSpeed = maxOf(
                        DEFAULT_CONSERVATIVE_SPEED_MPS,
                        state.lastUsefulReading?.speedMps ?: 0.0f
                    )
                    val possibleDisplacement = conservativeSpeed * (timeSinceLastUseful / 1000.0)
                    if ((lastDist - possibleDisplacement) <= radius) {
                        precautionEpisodes++
                        effects.add(
                            EngineEffect.EmitPrecautionAlert(
                                sessionId = state.session.id,
                                cycle = precautionEpisodes,
                                message = "O sinal foi perdido perto da área do aviso. Confira sua localização."
                            )
                        )
                        precautionEmitted = true
                    }
                }
            }
        } else if (state.qualityState == QualityState.ACQUIRING) {
            val acquisitionDuration = if (state.acquisitionStartedAtMonotonicMs > 0L) {
                monotonicTimeMs - state.acquisitionStartedAtMonotonicMs
            } else 0L
            if (acquisitionDuration > degradedTimeout) {
                // Aquisição inicial sem leitura útil por mais tempo que o limite contextual
                if (updatedQuality != QualityState.DEGRADED) {
                    updatedQuality = QualityState.DEGRADED
                    effects.add(EngineEffect.UpdateQuality(QualityState.DEGRADED))
                }
            }
        }

        val updatedState = state.copy(
            qualityState = updatedQuality,
            precautionEmittedForEpisode = precautionEmitted,
            precautionEpisodeCount = precautionEpisodes,
            pendingConfirmationReading = pendingReading,
            pendingConfirmationSinceMonotonicMs = pendingSinceMs,
            consecutiveUsefulReadingsSinceLoss = consecutiveUseful
        )

        return EngineDecision(updatedState, effects)
    }

    private fun getDegradedTimeoutMs(state: EngineState): Long {
        val profileConfig = MonitoringPolicies.forProfile(state.alarmDefinition.monitoringProfile)
        return maxOf(DEGRADED_TIMEOUT_MS, profileConfig.distantIntervalMillis + 15_000L)
    }

    private fun handleAcknowledge(state: EngineState, monotonicTimeMs: Long): EngineDecision {
        val cooldownMs = state.alarmDefinition.cooldownMinutes.coerceAtLeast(1) * 60_000L
        val updatedRuntime = state.runtime.copy(
            waitingExit = state.alarmDefinition.allowNewEntrySameWindow,
            isArmed = !state.alarmDefinition.allowNewEntrySameWindow,
            cooldownUntilMonotonicMs = monotonicTimeMs + cooldownMs
        )
        return EngineDecision(state.copy(runtime = updatedRuntime))
    }
}
