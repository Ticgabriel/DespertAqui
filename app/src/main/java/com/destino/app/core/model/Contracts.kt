package com.destino.app.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MonitoringEngine {
    fun reduce(state: EngineState, event: EngineEvent): EngineDecision
}

interface LocationSource {
    fun observe(config: LocationConfig): Flow<LocationEvent>
}

interface AlertController {
    var onPlaybackError: ((eventId: String, playbackAttemptId: String, errorMsg: String) -> Unit)?
    fun setOnPlaybackFinishedListener(listener: ((eventId: String) -> Unit)?) {}
    suspend fun deliver(
        event: AlertEvent,
        destinationName: String = "Destino",
        isVibrationEnabled: Boolean = true
    ): DeliveryResult
    suspend fun deliverWithOptions(
        event: AlertEvent,
        destinationName: String = "Destino",
        isVibrationEnabled: Boolean = true,
        soundSelection: SoundSelection = SoundSelection(),
        vibrationPattern: VibrationPattern = VibrationPattern.STRONG,
        audioOutputPolicy: AudioOutputPolicy = AudioOutputPolicy.SYSTEM_DEFAULT,
        maxDurationMinutes: Int = 3,
        isGradualVolume: Boolean = false
    ): DeliveryResult = deliver(event, destinationName, isVibrationEnabled)
    suspend fun previewSound(soundSelection: SoundSelection): DeliveryResult = test()
    suspend fun stopPreview() {}
    suspend fun acknowledge(eventId: String)
    suspend fun test(): DeliveryResult
}

interface MonitoringCoordinator {
    val operationalState: StateFlow<OperationalState>
    suspend fun execute(command: MonitoringCommand): CommandResult
}

data class PlaceSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String
)

data class PlaceDetails(
    val name: String,
    val coordinates: Coordinates
)

sealed interface PlacesResult<out T> {
    data class Success<T>(val value: T) : PlacesResult<T>
    data class Failure(val message: String) : PlacesResult<Nothing>
}

interface PlacesSearchSource {
    suspend fun searchSuggestions(query: String): PlacesResult<List<PlaceSuggestion>>
    suspend fun fetchPlaceDetails(placeId: String): PlacesResult<PlaceDetails>
    fun abandonSession()
}
