package com.destino.app.core.engine

import com.destino.app.core.model.AlarmDefinition
import com.destino.app.core.model.AlarmRuntime
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.Destination
import com.destino.app.core.model.EngineEffect
import com.destino.app.core.model.EngineEvent
import com.destino.app.core.model.EngineState
import com.destino.app.core.model.LocationEvent
import com.destino.app.core.model.MonitoringSession
import com.destino.app.core.model.QualityState
import com.destino.app.core.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MonitoringEngineTest {

    private lateinit var engine: MonitoringEngineImpl
    // Coordenadas de referência (Marco Zero SP)
    private val destinationCoords = Coordinates(-23.550520, -46.633308)
    private val destination = Destination("dest-1", "Centro SP", destinationCoords)
    private val alarmDefinition = AlarmDefinition("alarm-1", destination.id, radiusMeters = 500.0)
    private val session = MonitoringSession("session-1", destination.id, "alarm-1", SessionState.ACTIVE, 1000L)
    private val runtime = AlarmRuntime("runtime-1", session.id, isArmed = true)

    private lateinit var initialState: EngineState

    @Before
    fun setUp() {
        engine = MonitoringEngineImpl()
        initialState = EngineState(
            destination = destination,
            alarmDefinition = alarmDefinition,
            session = session,
            runtime = runtime
        )
    }

    @Test
    fun testValidPlausibleArrivalWithInjectedMonotonicClock() {
        // Leitura 1: Posição a ~560 metros do destino (fora do raio de 500 m)
        // Deslocamento pequeno: lat -23.555500, lon -46.633308 (~554m)
        val reading1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555500, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 1000L,
            speedMps = 12.0f,
            epochTimestampMs = 1000000L
        )
        val step1 = engine.reduce(initialState, EngineEvent.LocationReceived(reading1, monotonicTimeMs = 1500L))
        assertFalse(step1.newState.runtime.waitingExit)
        assertTrue(step1.effects.none { it is EngineEffect.TriggerArrivalAlert })

        // Leitura 2: 4 segundos depois, avançou ~100 metros para dentro da área (a ~450m do destino)
        // Deslocamento de ~100m em 4s = 25 m/s (plausível, dentro do limite de 100 m/s)
        val reading2 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.554500, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 5000L,
            speedMps = 15.0f,
            epochTimestampMs = 1004000L
        )
        val step2 = engine.reduce(step1.newState, EngineEvent.LocationReceived(reading2, monotonicTimeMs = 5500L))

        // Deve disparar alerta de chegada e consumir alarme
        assertTrue("Deve disparar TriggerArrivalAlert", step2.effects.any { it is EngineEffect.TriggerArrivalAlert })
        assertTrue("Deve disparar ConsumeAlarm", step2.effects.any { it is EngineEffect.ConsumeAlarm })
        assertFalse("Runtime deve ser desarmado", step2.newState.runtime.isArmed)
    }

    @Test
    fun testTwoInaccurateReadingsDoNotConfirmArrival() {
        // Leitura inicial fora da área, próxima à borda (~520 m)
        val outside = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555200, -46.633308), // ~520 m
            accuracyMeters = 10.0f,
            monotonicTimeMs = 1000L,
            speedMps = 10.0f
        )
        val step1 = engine.reduce(initialState, EngineEvent.LocationReceived(outside, 1200L))

        // Duas leituras dentro do raio de 500m (~440m e ~420m), com deslocamento plausível (~78m em 2s = 39 m/s)
        // Isso NÃO aciona o filtro de salto (100 m/s), isolando o teste de precisão.
        // Limite máximo permitido de precisão para raio de 500m é min(50, 250) = 50m.
        val inaccurate1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.554500, -46.633308), // ~442 m do destino, ~78 m de outside
            accuracyMeters = 300.0f, // IMPRECISA (> 50 m)
            monotonicTimeMs = 3000L,
            speedMps = 10.0f
        )
        val step2 = engine.reduce(step1.newState, EngineEvent.LocationReceived(inaccurate1, 3200L))
        assertTrue(step2.effects.none { it is EngineEffect.TriggerArrivalAlert })
        assertTrue(step2.newState.pendingConfirmationReading == null) // Não pode ser aceita nem como pendente

        val inaccurate2 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.554300, -46.633308), // ~420 m do destino, ~22 m de inaccurate1
            accuracyMeters = 250.0f, // IMPRECISA (> 50 m)
            monotonicTimeMs = 5000L,
            speedMps = 10.0f
        )
        val step3 = engine.reduce(step2.newState, EngineEvent.LocationReceived(inaccurate2, 5200L))

        // Duas leituras imprecisas NUNCA confirmam chegada
        assertTrue("Não pode disparar com leituras imprecisas", step3.effects.none { it is EngineEffect.TriggerArrivalAlert })
        assertTrue("Não pode consumir alarme com leituras imprecisas", step3.effects.none { it is EngineEffect.ConsumeAlarm })
        assertTrue("Runtime deve continuar armado", step3.newState.runtime.isArmed)
    }

    @Test
    fun testStaleOrDuplicateReadingRejection() {
        val reading1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555500, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 5000L,
            speedMps = 5.0f
        )
        val step1 = engine.reduce(initialState, EngineEvent.LocationReceived(reading1, 5200L))
        assertEquals(5000L, step1.newState.lastUsefulReadingMonotonicMs)

        // Leitura duplicada/fora de ordem no relógio monotônico
        val duplicate = reading1.copy(monotonicTimeMs = 4500L)
        val step2 = engine.reduce(step1.newState, EngineEvent.LocationReceived(duplicate, 6000L))
        assertEquals(5000L, step2.newState.lastUsefulReadingMonotonicMs)

        // Leitura antiga (> 10s de diferença entre tempo monotônico do evento e leitura)
        // Deslocamento plausível em relação a reading1 (~22 m em 1s = 22 m/s, sem salto)
        val staleReading = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555300, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 6000L, // gerada em 6s
            speedMps = 5.0f
        )
        // Recebida em 20s monotônico (idade = 14s > 10s max age)
        val step3 = engine.reduce(step2.newState, EngineEvent.LocationReceived(staleReading, 20000L))
        assertTrue("Amostra antiga não pode disparar alerta", step3.effects.none { it is EngineEffect.TriggerArrivalAlert })
    }

    @Test
    fun testInitialAcquisitionTimeoutUsesDurationNotBootTime() {
        // Aparelho ligado há 1 hora: relógio monotônico em 3.600.000 ms
        val initialTickTime = 3_600_000L
        val step1 = engine.reduce(initialState, EngineEvent.ClockTick(initialTickTime))
        assertEquals("Estado inicial deve ser ACQUIRING", QualityState.ACQUIRING, step1.newState.qualityState)
        assertEquals(initialTickTime, step1.newState.acquisitionStartedAtMonotonicMs)

        // 2 segundos depois (3.602.000 ms): aquisição durou apenas 2s, NÃO pode degradar!
        val step2 = engine.reduce(step1.newState, EngineEvent.ClockTick(initialTickTime + 2000L))
        assertEquals("Após 2s de aquisição não pode degradar", QualityState.ACQUIRING, step2.newState.qualityState)

        // No perfil automático, uma atualização distante pode levar 20s.
        // A margem de 15s evita declarar perda antes da próxima leitura esperada.
        val step3 = engine.reduce(step2.newState, EngineEvent.ClockTick(initialTickTime + 36_000L))
        assertEquals("Após >35s de aquisição sem sinal deve degradar", QualityState.DEGRADED, step3.newState.qualityState)
        assertTrue(step3.effects.any { it is EngineEffect.UpdateQuality && it.qualityState == QualityState.DEGRADED })
    }

    @Test
    fun testRecoverySequenceUsefulInvalidUseful() {
        // Leitura inicial boa
        val reading1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555200, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 1000L,
            speedMps = 10.0f
        )
        val step1 = engine.reduce(initialState, EngineEvent.LocationReceived(reading1, 1200L))
        assertEquals(QualityState.GOOD, step1.newState.qualityState)

        // Degradação após o intervalo distante do perfil automático (20s) + margem (15s)
        val step2 = engine.reduce(step1.newState, EngineEvent.ClockTick(40_000L))
        assertEquals(QualityState.DEGRADED, step2.newState.qualityState)

        // 1. Leitura útil: consecutiveUseful = 1 (ainda DEGRADED, precisa de 2)
        val recUseful1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555200, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 41_000L,
            speedMps = 10.0f
        )
        val step3 = engine.reduce(step2.newState, EngineEvent.LocationReceived(recUseful1, 41_200L))
        assertEquals(QualityState.DEGRADED, step3.newState.qualityState)
        assertEquals(1, step3.newState.consecutiveUsefulReadingsSinceLoss)

        // 2. Leitura inválida (ex: imprecisa com 300m): zera consecutiveUseful!
        val recInvalid = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555200, -46.633308),
            accuracyMeters = 300.0f, // IMPRECISA
            monotonicTimeMs = 43_000L,
            speedMps = 10.0f
        )
        val step4 = engine.reduce(step3.newState, EngineEvent.LocationReceived(recInvalid, 43_200L))
        assertEquals(QualityState.DEGRADED, step4.newState.qualityState)
        assertEquals("Leitura inválida deve zerar o contador", 0, step4.newState.consecutiveUsefulReadingsSinceLoss)

        // 3. Outra leitura útil: consecutiveUseful volta a 1 (ainda DEGRADED)
        val recUseful2 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555200, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 45_000L,
            speedMps = 10.0f
        )
        val step5 = engine.reduce(step4.newState, EngineEvent.LocationReceived(recUseful2, 45_200L))
        assertEquals(QualityState.DEGRADED, step5.newState.qualityState)
        assertEquals(1, step5.newState.consecutiveUsefulReadingsSinceLoss)

        // 4. Segunda leitura útil consecutiva: consecutiveUseful = 2 -> restaura GOOD!
        val recUseful3 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555100, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 47_000L,
            speedMps = 10.0f
        )
        val step6 = engine.reduce(step5.newState, EngineEvent.LocationReceived(recUseful3, 47_200L))
        assertEquals(QualityState.GOOD, step6.newState.qualityState)
        assertEquals(2, step6.newState.consecutiveUsefulReadingsSinceLoss)
    }

    @Test
    fun testCivilClockChangeDoesNotAlterDecisions() {
        // Relógio civil pula 3 horas para frente (ex: mudança de fuso / horário)
        val reading1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555500, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 1000L,
            epochTimestampMs = 1000000L
        )
        val step1 = engine.reduce(initialState, EngineEvent.LocationReceived(reading1, 1200L))

        val reading2 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.554500, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 3000L, // delta monotônico de 2s normal
            epochTimestampMs = 1000000L + (3 * 3600 * 1000L) // +3 horas civil
        )
        val step2 = engine.reduce(step1.newState, EngineEvent.LocationReceived(reading2, 3200L))

        // Decisão é baseada exclusivamente no tempo monotônico (Item 3 da avaliação)
        assertTrue(step2.effects.any { it is EngineEffect.TriggerArrivalAlert })
    }

    @Test
    fun testActivationInsideAreaRequiresTwoUsefulReadingsOutsideToArm() {
        // Leitura inicial já dentro do raio de 500 m (a ~100 m do destino)
        val insideReading = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.551000, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 1000L,
            speedMps = 0.0f
        )
        val step1 = engine.reduce(initialState, EngineEvent.LocationReceived(insideReading, 1200L))
        assertTrue(step1.newState.runtime.waitingExit)
        assertTrue(step1.effects.any { it is EngineEffect.EnterWaitingExit })
        assertTrue(step1.effects.none { it is EngineEffect.TriggerArrivalAlert })

        // Leitura 1 bem fora da área (a ~600 m): 30s depois (~18.5 m/s, plausível)
        val outside1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.556000, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 31000L,
            speedMps = 15.0f
        )
        val step2 = engine.reduce(step1.newState, EngineEvent.LocationReceived(outside1, 31200L))
        // Uma leitura só não basta para sair de WAITING_EXIT
        assertTrue(step2.newState.runtime.waitingExit)

        // Leitura 2 bem fora da área: 5s depois (~10 m/s, plausível)
        val outside2 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.556500, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 36000L,
            speedMps = 10.0f
        )
        val step3 = engine.reduce(step2.newState, EngineEvent.LocationReceived(outside2, 36200L))
        // Agora com duas leituras úteis fora, sai de WAITING_EXIT!
        assertFalse(step3.newState.runtime.waitingExit)
    }

    @Test
    fun testEdgeOscillationDoesNotFalseTrigger() {
        // Oscilação na borda: 505m, 495m (com incerteza grande ou sem confirmação), 505m
        val reading1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555060, -46.633308), // ~505 m (fora)
            accuracyMeters = 20.0f,
            monotonicTimeMs = 1000L
        )
        val step1 = engine.reduce(initialState, EngineEvent.LocationReceived(reading1, 1200L))

        // Entra levemente (495m), mas distância + incerteza = 495 + 20 = 515 > 500 (precisa de confirmação)
        val reading2 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.554970, -46.633308), // ~495 m
            accuracyMeters = 20.0f,
            monotonicTimeMs = 3000L
        )
        val step2 = engine.reduce(step1.newState, EngineEvent.LocationReceived(reading2, 3200L))
        assertTrue(step2.effects.none { it is EngineEffect.TriggerArrivalAlert })
        assertTrue(step2.newState.pendingConfirmationReading != null)

        // Volta a ficar fora (505m) -> deve limpar a confirmação pendente
        val reading3 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555060, -46.633308), // ~505 m
            accuracyMeters = 20.0f,
            monotonicTimeMs = 5000L
        )
        val step3 = engine.reduce(step2.newState, EngineEvent.LocationReceived(reading3, 5200L))
        assertTrue(step3.effects.none { it is EngineEffect.TriggerArrivalAlert })
        assertTrue(step3.newState.pendingConfirmationReading == null)
    }

    @Test
    fun testPrecautionAlertWithoutConsumingAlarmAndRecovery() {
        val outsideReading = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555200, -46.633308), // ~520m (perto da borda)
            accuracyMeters = 10.0f,
            monotonicTimeMs = 2000L,
            speedMps = 15.0f
        )
        val step1 = engine.reduce(initialState, EngineEvent.LocationReceived(outsideReading, 2200L))
        assertEquals(QualityState.GOOD, step1.newState.qualityState)

        // Passa o intervalo distante do perfil automático (20s) + margem de perda (15s)
        val step2 = engine.reduce(step1.newState, EngineEvent.ClockTick(40000L))

        assertEquals(QualityState.DEGRADED, step2.newState.qualityState)
        assertTrue("Deve emitir EmitPrecautionAlert", step2.effects.any { it is EngineEffect.EmitPrecautionAlert })
        assertTrue("Não pode consumir o alarme", step2.effects.none { it is EngineEffect.ConsumeAlarm })
        assertTrue("Runtime deve permanecer armado", step2.newState.runtime.isArmed)

        // Duas leituras úteis consecutivas encerram o episódio de perda de sinal e restauram GOOD
        val rec1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555200, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 41000L,
            speedMps = 15.0f
        )
        val step3 = engine.reduce(step2.newState, EngineEvent.LocationReceived(rec1, 41200L))
        assertEquals(QualityState.DEGRADED, step3.newState.qualityState) // Primeira ainda não restaura

        val rec2 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.555100, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 43000L,
            speedMps = 15.0f
        )
        val step4 = engine.reduce(step3.newState, EngineEvent.LocationReceived(rec2, 43200L))
        assertEquals(QualityState.GOOD, step4.newState.qualityState) // Segunda restaura GOOD!
        assertFalse(step4.newState.precautionEmittedForEpisode)
    }

    @Test
    fun testBorderlineArrivalWithStationarySpeedAndConfirmationWindow() {
        val alarm180 = AlarmDefinition("alarm-180", destination.id, radiusMeters = 180.0)
        val state180 = initialState.copy(alarmDefinition = alarm180)

        // Leitura 1: Fora da área (a ~220m do destino)
        val outside = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.552500, -46.633308),
            accuracyMeters = 8.0f,
            monotonicTimeMs = 1000L,
            speedMps = 0.0f
        )
        val step1 = engine.reduce(state180, EngineEvent.LocationReceived(outside, 1200L))
        assertTrue(step1.effects.none { it is EngineEffect.TriggerArrivalAlert })
        assertTrue(step1.newState.pendingConfirmationReading == null)

        // Leitura 2: Na borda da área (a ~179m do destino) com velocidade 0 e precisão boa
        val borderline1 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.552131, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 3000L,
            speedMps = 0.0f
        )
        val step2 = engine.reduce(step1.newState, EngineEvent.LocationReceived(borderline1, 3200L))
        assertTrue("Primeira leitura na borda não deve disparar de imediato devido à incerteza", step2.effects.none { it is EngineEffect.TriggerArrivalAlert })
        assertTrue("Deve colocar como confirmação pendente", step2.newState.pendingConfirmationReading != null)

        // Leitura 3: 7 segundos depois (delta = 7s > 5s antigo, mas <= 15s novo)
        val borderline2 = LocationEvent.NewLocation(
            coordinates = Coordinates(-23.552100, -46.633308),
            accuracyMeters = 10.0f,
            monotonicTimeMs = 10000L,
            speedMps = 0.0f
        )
        val step3 = engine.reduce(step2.newState, EngineEvent.LocationReceived(borderline2, 10200L))
        assertTrue("Segunda leitura na janela de 15s deve disparar alarme", step3.effects.any { it is EngineEffect.TriggerArrivalAlert })
        assertTrue("Deve consumir o alarme", step3.effects.any { it is EngineEffect.ConsumeAlarm })
        assertFalse("Runtime deve desarmar", step3.newState.runtime.isArmed)
    }
}
