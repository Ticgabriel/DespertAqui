package com.destino.app.core.engine

import com.destino.app.core.model.MonitoringProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocationRequestPlannerTest {

    private lateinit var planner: LocationRequestPlanner

    @Before
    fun setUp() {
        planner = LocationRequestPlannerImpl()
    }

    @Test
    fun `empty contexts returns safe default interval`() {
        val config = planner.planLocationRequest(emptyList())
        assertEquals(20_000L, config.intervalMillis)
        assertEquals(1_000L, config.minUpdateIntervalMillis)
        assertTrue(config.highAccuracy)
    }

    @Test
    fun `distant context calculates distant interval in automatic profile`() {
        val distantContext = DestinationMonitoringContext(
            destinationId = "dest-1",
            distanceMeters = 30_000.0, // 30 km
            radiusMeters = 500.0,
            uncertaintyMeters = 15f,
            speedMps = 15f, // ~54 km/h -> timeToBorder > 1000s
            profile = MonitoringProfile.AUTOMATIC
        )

        val config = planner.planLocationRequest(listOf(distantContext))
        assertEquals(20_000L, config.intervalMillis)
        assertEquals(1_000L, config.minUpdateIntervalMillis)
    }

    @Test
    fun `approaching context reduces interval to approaching level`() {
        val approachingContext = DestinationMonitoringContext(
            destinationId = "dest-2",
            distanceMeters = 1_200.0,
            radiusMeters = 500.0,
            uncertaintyMeters = 20f,
            speedMps = 15f, // timeToBorder ~ (1200 - 500 - 20) / 15 = 45.3s (<= 75s)
            profile = MonitoringProfile.AUTOMATIC
        )

        val config = planner.planLocationRequest(listOf(approachingContext))
        assertEquals(4_000L, config.intervalMillis)
        assertEquals(1_000L, config.minUpdateIntervalMillis)
        assertTrue(config.highAccuracy)
    }

    @Test
    fun `near border or inside triggers confirming or close interval`() {
        val closeContext = DestinationMonitoringContext(
            destinationId = "dest-3",
            distanceMeters = 550.0,
            radiusMeters = 500.0,
            uncertaintyMeters = 20f,
            speedMps = 10f, // 550 <= 500 + 20 + 100 (620m) -> CONFIRMING_OR_CLOSE
            profile = MonitoringProfile.AUTOMATIC
        )

        val config = planner.planLocationRequest(listOf(closeContext))
        assertEquals(1_500L, config.intervalMillis)
        assertEquals(1_000L, config.minUpdateIntervalMillis)
        assertTrue(config.highAccuracy)
    }

    @Test
    fun `degraded signal or confirming flag forces maximum frequency`() {
        val degradedContext = DestinationMonitoringContext(
            destinationId = "dest-4",
            distanceMeters = 10_000.0,
            radiusMeters = 500.0,
            isDegraded = true,
            profile = MonitoringProfile.AUTOMATIC
        )

        val config = planner.planLocationRequest(listOf(degradedContext))
        assertEquals(1_500L, config.intervalMillis)
        assertTrue(config.highAccuracy)
    }

    @Test
    fun `multiple concurrent contexts selects the most demanding interval`() {
        val distantContext = DestinationMonitoringContext(
            destinationId = "dest-far",
            distanceMeters = 50_000.0,
            radiusMeters = 500.0,
            profile = MonitoringProfile.AUTOMATIC // wants 20s
        )
        val closeContext = DestinationMonitoringContext(
            destinationId = "dest-close",
            distanceMeters = 400.0,
            radiusMeters = 500.0,
            profile = MonitoringProfile.AUTOMATIC // wants 1.5s
        )

        val config = planner.planLocationRequest(listOf(distantContext, closeContext))
        // Must select the most demanding (1.5s), not 20s
        assertEquals(1_500L, config.intervalMillis)
        assertEquals(1_000L, config.minUpdateIntervalMillis)
        assertTrue(config.highAccuracy)
    }

    @Test
    fun `battery saver profile uses larger intervals`() {
        val context = DestinationMonitoringContext(
            destinationId = "dest-battery",
            distanceMeters = 50_000.0,
            radiusMeters = 500.0,
            profile = MonitoringProfile.BATTERY_SAVER
        )

        val config = planner.planLocationRequest(listOf(context))
        assertEquals(45_000L, config.intervalMillis)
        assertEquals(1_000L, config.minUpdateIntervalMillis)
    }

    @Test
    fun `high precision profile enforces fast updates even when distant`() {
        val context = DestinationMonitoringContext(
            destinationId = "dest-precision",
            distanceMeters = 50_000.0,
            radiusMeters = 500.0,
            profile = MonitoringProfile.HIGH_PRECISION
        )

        val config = planner.planLocationRequest(listOf(context))
        assertEquals(4_000L, config.intervalMillis)
        assertEquals(1_000L, config.minUpdateIntervalMillis)
        assertTrue(config.highAccuracy)
    }
}
