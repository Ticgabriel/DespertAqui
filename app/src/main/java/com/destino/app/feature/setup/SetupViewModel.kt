package com.destino.app.feature.setup

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.core.model.PlaceSuggestion
import com.destino.app.core.model.PlacesSearchSource
import com.destino.app.core.model.PlacesResult
import com.destino.app.core.model.UserPreferences
import com.destino.app.data.local.AlarmRepository
import com.destino.app.platform.runtime.CapabilityProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class RadiusUnit {
    METERS,
    KILOMETERS
}

data class SetupUiState(
    val destinationName: String = "",
    val latitudeInput: String = "",
    val longitudeInput: String = "",
    val searchQuery: String = "",
    val searchSuggestions: List<PlaceSuggestion> = emptyList(),
    val isSearching: Boolean = false,
    val isManualCoordinatesExpanded: Boolean = false,
    val selectedPresetRadiusMeters: Double? = 500.0,
    val customRadiusInput: String = "",
    val customRadiusUnit: RadiusUnit = RadiusUnit.METERS,
    val isCustomRadius: Boolean = false,
    val validationError: String? = null,
    val feedbackMessage: String? = null,
    val isStarting: Boolean = false
) {
    val selectedCoordinates: Coordinates?
        get() {
            val lat = latitudeInput.trim().replace(',', '.').toDoubleOrNull() ?: return null
            val lon = longitudeInput.trim().replace(',', '.').toDoubleOrNull() ?: return null
            return if (lat in -90.0..90.0 && lon in -180.0..180.0) Coordinates(lat, lon) else null
        }

    val effectiveRadiusMeters: Double?
        get() = if (isCustomRadius) {
            parseExplicitRadius(customRadiusInput, customRadiusUnit)
        } else {
            selectedPresetRadiusMeters
        }

    private fun parseExplicitRadius(input: String, defaultUnit: RadiusUnit): Double? {
        val lower = input.trim().lowercase().replace(',', '.')
        if (lower.isEmpty()) return null

        return when {
            lower.endsWith("km") -> {
                val numPart = lower.removeSuffix("km").trim()
                numPart.toDoubleOrNull()?.let { it * 1000.0 }
            }
            lower.endsWith("m") -> {
                val numPart = lower.removeSuffix("m").trim()
                numPart.toDoubleOrNull()
            }
            else -> {
                val num = lower.toDoubleOrNull() ?: return null
                if (defaultUnit == RadiusUnit.KILOMETERS) num * 1000.0 else num
            }
        }
    }
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val coordinator: MonitoringCoordinator,
    private val capabilityProvider: CapabilityProvider,
    private val repository: AlarmRepository,
    private val preferencesDataStore: DataStore<UserPreferences>,
    private val placesSearchSource: PlacesSearchSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var placeDetailsJob: Job? = null
    private var searchGeneration = 0L

    init {
        viewModelScope.launch {
            // Carregar preferências e rascunho do DataStore
            try {
                val prefs = preferencesDataStore.data.first()
                _uiState.update { current ->
                    current.copy(
                        selectedPresetRadiusMeters = prefs.defaultRadiusMeters,
                        destinationName = if (prefs.draftDestinationName.isNotBlank()) prefs.draftDestinationName else current.destinationName,
                        latitudeInput = if (prefs.draftLatitude.isNotBlank()) prefs.draftLatitude else current.latitudeInput,
                        longitudeInput = if (prefs.draftLongitude.isNotBlank()) prefs.draftLongitude else current.longitudeInput
                    )
                }
            } catch (e: Exception) {}

            // Se o rascunho do DataStore estava vazio, restaurar último destino salvo no Room como fallback
            try {
                if (_uiState.value.destinationName.isBlank() && _uiState.value.latitudeInput.isBlank()) {
                    val lastDest = repository.getLatestDestination()
                    if (lastDest != null) {
                        _uiState.update { current ->
                            current.copy(
                                destinationName = lastDest.name,
                                latitudeInput = lastDest.coordinates.latitude.toString(),
                                longitudeInput = lastDest.coordinates.longitude.toString()
                            )
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun onSearchQueryChange(query: String) {
        searchGeneration++
        placeDetailsJob?.cancel()
        _uiState.update { it.copy(searchQuery = query, validationError = null) }
        searchJob?.cancel()

        if (query.trim().length < 2) {
            placesSearchSource.abandonSession()
            _uiState.update { it.copy(searchSuggestions = emptyList(), isSearching = false) }
            return
        }

        val generation = searchGeneration
        searchJob = viewModelScope.launch {
            delay(300L) // Debounce 300ms
            _uiState.update { it.copy(isSearching = true) }
            when (val result = placesSearchSource.searchSuggestions(query.trim())) {
                is PlacesResult.Success -> if (generation == searchGeneration) {
                    _uiState.update {
                        it.copy(searchSuggestions = result.value, isSearching = false, validationError = null)
                    }
                }
                is PlacesResult.Failure -> if (generation == searchGeneration) {
                    _uiState.update {
                        it.copy(searchSuggestions = emptyList(), isSearching = false, validationError = result.message)
                    }
                }
            }
        }
    }

    fun onSuggestionSelected(suggestion: PlaceSuggestion) {
        searchJob?.cancel()
        placeDetailsJob?.cancel()
        val generation = ++searchGeneration
        placeDetailsJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchSuggestions = emptyList(), searchQuery = suggestion.primaryText) }
            when (val result = placesSearchSource.fetchPlaceDetails(suggestion.placeId)) {
                is PlacesResult.Success -> if (generation == searchGeneration) {
                    val details = result.value
                    _uiState.update { current ->
                        current.copy(
                            destinationName = details.name,
                            latitudeInput = details.coordinates.latitude.toString(),
                            longitudeInput = details.coordinates.longitude.toString(),
                            isSearching = false,
                            validationError = null
                        )
                    }
                    saveDraft(details.name, details.coordinates.latitude.toString(), details.coordinates.longitude.toString())
                }
                is PlacesResult.Failure -> if (generation == searchGeneration) {
                    _uiState.update { it.copy(isSearching = false, validationError = result.message) }
                }
            }
        }
    }

    fun onMapClick(coordinates: Coordinates) {
        searchGeneration++
        searchJob?.cancel()
        placeDetailsJob?.cancel()
        placesSearchSource.abandonSession()
        val latStr = String.format(java.util.Locale.US, "%.6f", coordinates.latitude)
        val lonStr = String.format(java.util.Locale.US, "%.6f", coordinates.longitude)
        val currentName = _uiState.value.destinationName
        val newName = if (currentName.isBlank() || currentName == "Destino") "Destino Selecionado" else currentName

        _uiState.update { current ->
            current.copy(
                destinationName = newName,
                latitudeInput = latStr,
                longitudeInput = lonStr,
                validationError = null
            )
        }
        saveDraft(newName, latStr, lonStr)
    }

    fun toggleManualCoordinates() {
        _uiState.update { it.copy(isManualCoordinatesExpanded = !it.isManualCoordinatesExpanded) }
    }

    fun clearSearch() {
        searchGeneration++
        searchJob?.cancel()
        placeDetailsJob?.cancel()
        placesSearchSource.abandonSession()
        _uiState.update { it.copy(searchQuery = "", searchSuggestions = emptyList(), isSearching = false) }
    }

    fun onNameChange(name: String) {
        _uiState.update { it.copy(destinationName = name, validationError = null) }
        saveDraft(name, _uiState.value.latitudeInput, _uiState.value.longitudeInput)
    }

    fun onLatitudeChange(lat: String) {
        _uiState.update { it.copy(latitudeInput = lat, validationError = null) }
        saveDraft(_uiState.value.destinationName, lat, _uiState.value.longitudeInput)
    }

    fun onLongitudeChange(lon: String) {
        _uiState.update { it.copy(longitudeInput = lon, validationError = null) }
        saveDraft(_uiState.value.destinationName, _uiState.value.latitudeInput, lon)
    }

    private fun saveDraft(name: String, lat: String, lon: String) {
        viewModelScope.launch {
            try {
                preferencesDataStore.updateData {
                    it.copy(
                        draftDestinationName = name,
                        draftLatitude = lat,
                        draftLongitude = lon
                    )
                }
            } catch (e: Exception) {}
        }
    }

    fun onPresetRadiusSelected(radiusMeters: Double) {
        _uiState.update {
            it.copy(
                selectedPresetRadiusMeters = radiusMeters,
                isCustomRadius = false,
                validationError = null
            )
        }
        viewModelScope.launch {
            try {
                preferencesDataStore.updateData { it.copy(defaultRadiusMeters = radiusMeters) }
            } catch (e: Exception) {}
        }
    }

    fun onCustomRadiusChange(custom: String) {
        _uiState.update {
            it.copy(
                customRadiusInput = custom,
                isCustomRadius = true,
                selectedPresetRadiusMeters = null,
                validationError = null
            )
        }
    }

    fun onCustomUnitChange(unit: RadiusUnit) {
        _uiState.update {
            it.copy(customRadiusUnit = unit, validationError = null)
        }
    }

    fun testAlert() {
        viewModelScope.launch {
            _uiState.update { it.copy(feedbackMessage = "Iniciando teste de som e vibração (3 segundos)...") }
            val result = coordinator.execute(
                MonitoringCommand.TestAlert(
                    commandId = UUID.randomUUID().toString()
                )
            )
            when (result) {
                is CommandResult.Success -> {
                    _uiState.update { it.copy(feedbackMessage = result.message) }
                }
                is CommandResult.Rejected -> {
                    _uiState.update { it.copy(feedbackMessage = null, validationError = result.reason) }
                }
                is CommandResult.Error -> {
                    _uiState.update { it.copy(feedbackMessage = null, validationError = result.message) }
                }
                is CommandResult.AlreadyActive -> {}
            }
        }
    }

    fun startJourney(onStarted: () -> Unit) {
        val state = _uiState.value

        if (state.isSearching) {
            _uiState.update { it.copy(validationError = "Aguarde a confirmação do endereço selecionado.") }
            return
        }

        val name = state.destinationName.trim().ifBlank { "Destino" }

        val latClean = state.latitudeInput.trim().replace(',', '.')
        val lat = latClean.toDoubleOrNull()
        if (lat == null || !lat.isFinite() || lat !in -90.0..90.0) {
            _uiState.update { it.copy(validationError = "Defina o destino tocando no mapa, buscando endereço ou digitando latitude.") }
            return
        }

        val lonClean = state.longitudeInput.trim().replace(',', '.')
        val lon = lonClean.toDoubleOrNull()
        if (lon == null || !lon.isFinite() || lon !in -180.0..180.0) {
            _uiState.update { it.copy(validationError = "Defina o destino tocando no mapa, buscando endereço ou digitando longitude.") }
            return
        }

        val radius = state.effectiveRadiusMeters
        if (radius == null || !radius.isFinite() || radius < 100.0 || radius > 20_000.0) {
            _uiState.update { it.copy(validationError = "Raio inválido. Escolha entre 100 m e 20 km (ex: 500 m ou 1,5 km).") }
            return
        }

        val caps = capabilityProvider.getCapabilities()
        if (!caps.canStartMonitoring) {
            _uiState.update { it.copy(validationError = caps.missingCapabilityReason) }
            return
        }

        _uiState.update { it.copy(isStarting = true, validationError = null) }

        viewModelScope.launch {
            try {
                val prefs = preferencesDataStore.data.first()
                val result = coordinator.execute(
                    MonitoringCommand.StartJourney(
                        commandId = UUID.randomUUID().toString(),
                        destinationName = name,
                        coordinates = Coordinates(lat, lon),
                        radiusMeters = radius,
                        isVibrationEnabled = prefs.isVibrationEnabled
                    )
                )

                when (result) {
                    is CommandResult.Success,
                    is CommandResult.AlreadyActive -> {
                        onStarted()
                    }
                    is CommandResult.Rejected -> {
                        _uiState.update { it.copy(validationError = result.reason) }
                    }
                    is CommandResult.Error -> {
                        _uiState.update { it.copy(validationError = result.message) }
                    }
                }
            } finally {
                _uiState.update { it.copy(isStarting = false) }
            }
        }
    }
}
