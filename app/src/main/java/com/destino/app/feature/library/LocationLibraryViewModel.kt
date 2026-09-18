package com.destino.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.Destination
import com.destino.app.core.model.Favorite
import com.destino.app.core.model.HistoryRecord
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.PlaceDetails
import com.destino.app.core.model.PlaceSuggestion
import com.destino.app.core.model.PlacesResult
import com.destino.app.core.model.PlacesSearchSource
import com.destino.app.data.local.AlarmRepository
import com.destino.app.data.local.FavoriteRepository
import com.destino.app.data.local.HistoryRepository
import com.destino.app.data.local.OccurrenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class LocationLibraryUiState(
    val favorites: List<Favorite> = emptyList(),
    val recentRecords: List<HistoryRecord> = emptyList(),
    val searchQuery: String = "",
    val isEditFavoriteDialogVisible: Boolean = false,
    val editingFavorite: Favorite? = null,
    val favoritePlaceQuery: String = "",
    val favoritePlaceSuggestions: List<PlaceSuggestion> = emptyList(),
    val isFavoritePlaceSearching: Boolean = false,
    val favoritePlaceSelection: PlaceDetails? = null,
    val favoritePlaceSearchError: String? = null,
    val userMessage: String? = null
)

@HiltViewModel
class LocationLibraryViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val historyRepository: HistoryRepository,
    private val alarmRepository: AlarmRepository,
    private val occurrenceRepository: OccurrenceRepository,
    private val placesSearchSource: PlacesSearchSource,
    private val coordinator: MonitoringCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationLibraryUiState())
    val uiState: StateFlow<LocationLibraryUiState> = _uiState.asStateFlow()
    private var allFavorites: List<Favorite> = emptyList()
    private var favoriteSearchJob: Job? = null
    private var favoritePlaceDetailsJob: Job? = null

    init {
        viewModelScope.launch {
            favoriteRepository.observeAll().collectLatest { favList ->
                allFavorites = favList
                applyFavoriteFilter()
            }
        }

        viewModelScope.launch {
            historyRepository.observeAll().collectLatest { history ->
                // Agrupa para mostrar locais recentes únicos
                val recents = history.distinctBy { it.destinationName }.take(10)
                _uiState.update { it.copy(recentRecords = recents) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFavoriteFilter()
    }

    private fun applyFavoriteFilter() {
        val query = _uiState.value.searchQuery.trim()
        val filtered = if (query.isBlank()) allFavorites else allFavorites.filter {
            it.nickname.contains(query, ignoreCase = true) || it.destinationName.contains(query, ignoreCase = true)
        }
        _uiState.update { it.copy(favorites = filtered) }
    }

    fun startJourneyImmediately(name: String, coordinates: Coordinates, radiusMeters: Double) {
        viewModelScope.launch {
            val result = coordinator.execute(
                MonitoringCommand.StartJourney(
                    commandId = UUID.randomUUID().toString(),
                    destinationName = name,
                    coordinates = coordinates,
                    radiusMeters = radiusMeters
                )
            )
            _uiState.update { it.copy(userMessage = commandMessage(result, "Viagem iniciada para $name")) }
        }
    }

    fun startRecent(record: HistoryRecord) {
        viewModelScope.launch {
            val resolved = resolveRecent(record) ?: return@launch
            startJourneyImmediately(resolved.first.name, resolved.first.coordinates, resolved.second.radiusMeters)
        }
    }

    fun favoriteRecent(record: HistoryRecord) {
        viewModelScope.launch {
            val resolved = resolveRecent(record) ?: return@launch
            saveFavorite(
                nickname = record.destinationName,
                destinationName = resolved.first.name,
                coordinates = resolved.first.coordinates,
                radiusMeters = resolved.second.radiusMeters,
                iconName = "place"
            )
        }
    }

    private suspend fun resolveRecent(record: HistoryRecord): Pair<Destination, com.destino.app.core.model.AlarmDefinition>? {
        val occurrence = record.occurrenceId?.let { occurrenceRepository.getById(it) }
        val alarm = occurrence?.let { alarmRepository.getAlarmById(it.alarmDefinitionId) }
        val destination = alarm?.let { alarmRepository.getDestinationById(it.destinationId) }
        if (alarm != null && destination != null) {
            val expiresAt = destination.expiresAtEpochMs
            if (expiresAt != null && expiresAt > 0 && expiresAt < System.currentTimeMillis()) {
                _uiState.update { it.copy(userMessage = "O destino desta viagem expirou.") }
                return null
            }
            return destination to alarm
        }

        // Fallback para destinos cujo alarme/ocorrência foram excluídos: recuperar coordenadas gravadas no details
        val details = record.details
        val geoMarker = "|geo:"
        if (details != null && details.contains(geoMarker)) {
            val geoIdx = details.indexOf(geoMarker)
            val geoPart = details.substring(geoIdx + geoMarker.length)
            val parts = geoPart.split(",")
            if (parts.size >= 2) {
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                val rad = if (parts.size >= 3) parts[2].toDoubleOrNull() ?: 500.0 else 500.0
                if (lat != null && lon != null) {
                    val fallbackDest = Destination(
                        id = UUID.randomUUID().toString(),
                        name = record.destinationName,
                        coordinates = Coordinates(lat, lon)
                    )
                    val fallbackAlarm = com.destino.app.core.model.AlarmDefinition(
                        id = UUID.randomUUID().toString(),
                        destinationId = fallbackDest.id,
                        radiusMeters = rad,
                        name = record.alarmName
                    )
                    return fallbackDest to fallbackAlarm
                }
            }
        }

        _uiState.update { it.copy(userMessage = "Este registro antigo não possui localização reutilizável.") }
        return null
    }

    private fun commandMessage(result: CommandResult, success: String): String = when (result) {
        is CommandResult.Success -> success
        is CommandResult.AlreadyActive -> "Este destino já está sendo monitorado"
        is CommandResult.Rejected -> result.reason
        is CommandResult.Error -> result.message
    }

    fun openAddFavoriteDialog() {
        cancelFavoritePlaceSearch()
        _uiState.update {
            it.copy(
                isEditFavoriteDialogVisible = true,
                editingFavorite = null,
                favoritePlaceQuery = "",
                favoritePlaceSuggestions = emptyList(),
                isFavoritePlaceSearching = false,
                favoritePlaceSelection = null,
                favoritePlaceSearchError = null
            )
        }
    }

    fun openEditFavoriteDialog(favorite: Favorite) {
        cancelFavoritePlaceSearch()
        _uiState.update {
            it.copy(
                isEditFavoriteDialogVisible = true,
                editingFavorite = favorite,
                favoritePlaceQuery = favorite.destinationName,
                favoritePlaceSuggestions = emptyList(),
                isFavoritePlaceSearching = false,
                favoritePlaceSelection = null,
                favoritePlaceSearchError = null
            )
        }
    }

    fun dismissFavoriteDialog() {
        cancelFavoritePlaceSearch()
        _uiState.update {
            it.copy(
                isEditFavoriteDialogVisible = false,
                editingFavorite = null,
                favoritePlaceQuery = "",
                favoritePlaceSuggestions = emptyList(),
                isFavoritePlaceSearching = false,
                favoritePlaceSelection = null,
                favoritePlaceSearchError = null
            )
        }
    }

    fun onFavoritePlaceQueryChange(query: String) {
        _uiState.update {
            it.copy(
                favoritePlaceQuery = query,
                favoritePlaceSelection = null,
                favoritePlaceSearchError = null
            )
        }
        favoriteSearchJob?.cancel()
        favoritePlaceDetailsJob?.cancel()

        val normalizedQuery = query.trim()
        if (normalizedQuery.length < 3) {
            _uiState.update {
                it.copy(
                    favoritePlaceSuggestions = emptyList(),
                    isFavoritePlaceSearching = false
                )
            }
            return
        }

        favoriteSearchJob = viewModelScope.launch {
            delay(300)
            if (_uiState.value.favoritePlaceQuery.trim() != normalizedQuery) return@launch
            _uiState.update { it.copy(isFavoritePlaceSearching = true) }

            when (val result = placesSearchSource.searchSuggestions(normalizedQuery)) {
                is PlacesResult.Success -> {
                    if (_uiState.value.favoritePlaceQuery.trim() == normalizedQuery) {
                        _uiState.update {
                            it.copy(
                                favoritePlaceSuggestions = result.value,
                                isFavoritePlaceSearching = false,
                                favoritePlaceSearchError = null
                            )
                        }
                    }
                }
                is PlacesResult.Failure -> {
                    if (_uiState.value.favoritePlaceQuery.trim() == normalizedQuery) {
                        _uiState.update {
                            it.copy(
                                favoritePlaceSuggestions = emptyList(),
                                isFavoritePlaceSearching = false,
                                favoritePlaceSearchError = result.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun onSelectFavoritePlaceSuggestion(suggestion: PlaceSuggestion) {
        favoriteSearchJob?.cancel()
        favoritePlaceDetailsJob?.cancel()
        favoritePlaceDetailsJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFavoritePlaceSearching = true,
                    favoritePlaceSearchError = null
                )
            }
            when (val result = placesSearchSource.fetchPlaceDetails(suggestion.placeId)) {
                is PlacesResult.Success -> {
                    _uiState.update {
                        it.copy(
                            favoritePlaceQuery = result.value.name,
                            favoritePlaceSuggestions = emptyList(),
                            isFavoritePlaceSearching = false,
                            favoritePlaceSelection = result.value,
                            favoritePlaceSearchError = null
                        )
                    }
                }
                is PlacesResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isFavoritePlaceSearching = false,
                            favoritePlaceSearchError = result.message
                        )
                    }
                }
            }
        }
    }

    fun onFavoriteMapCoordinatesSelected(coordinates: Coordinates) {
        favoriteSearchJob?.cancel()
        favoritePlaceDetailsJob?.cancel()
        val currentName = _uiState.value.favoritePlaceQuery.ifBlank { "Destino no mapa" }
        _uiState.update {
            it.copy(
                favoritePlaceQuery = currentName,
                favoritePlaceSuggestions = emptyList(),
                isFavoritePlaceSearching = false,
                favoritePlaceSelection = PlaceDetails(currentName, coordinates),
                favoritePlaceSearchError = null
            )
        }
        placesSearchSource.abandonSession()
    }

    private fun cancelFavoritePlaceSearch() {
        favoriteSearchJob?.cancel()
        favoritePlaceDetailsJob?.cancel()
        placesSearchSource.abandonSession()
    }

    fun saveFavorite(
        nickname: String,
        destinationName: String,
        coordinates: Coordinates,
        radiusMeters: Double,
        iconName: String
    ) {
        viewModelScope.launch {
            val favId = _uiState.value.editingFavorite?.id ?: UUID.randomUUID().toString()
            val destId = _uiState.value.editingFavorite?.destinationId ?: UUID.randomUUID().toString()

            // Deduplicação: verificar se já existe favorito muito próximo das coordenadas
            val isDuplicate = _uiState.value.favorites.any { existing ->
                existing.id != favId &&
                Math.abs(existing.coordinates.latitude - coordinates.latitude) < 0.0001 &&
                Math.abs(existing.coordinates.longitude - coordinates.longitude) < 0.0001
            }
            if (isDuplicate) {
                _uiState.update { it.copy(userMessage = "Já existe um favorito salvo neste mesmo local.") }
                return@launch
            }

            val favorite = Favorite(
                id = favId,
                nickname = nickname.ifBlank { destinationName },
                iconName = iconName,
                destinationId = destId,
                destinationName = destinationName,
                coordinates = coordinates,
                suggestedRadiusMeters = radiusMeters
            )

            val destination = Destination(
                id = destId,
                name = destinationName,
                coordinates = coordinates
            )

            try {
                favoriteRepository.saveFavorite(favorite, destination)
                dismissFavoriteDialog()
                _uiState.update { it.copy(userMessage = "Favorito salvo!") }
            } catch (e: Exception) {
                _uiState.update { it.copy(userMessage = e.message ?: "Não foi possível salvar o favorito") }
            }
        }
    }

    fun reorderFavorites(orderedIds: List<String>) {
        viewModelScope.launch {
            favoriteRepository.reorderFavorites(orderedIds)
        }
    }

    fun deleteFavorite(favoriteId: String) {
        viewModelScope.launch {
            favoriteRepository.deleteFavorite(favoriteId)
            _uiState.update { it.copy(userMessage = "Favorito removido") }
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
