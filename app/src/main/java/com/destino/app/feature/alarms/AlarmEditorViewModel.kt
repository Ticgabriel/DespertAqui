package com.destino.app.feature.alarms

import androidx.datastore.core.DataStore
import androidx.room.withTransaction
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.AlarmDefinition
import com.destino.app.core.model.AudioOutputPolicy
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.CommandResult
import com.destino.app.core.model.Destination
import com.destino.app.core.model.Favorite
import com.destino.app.core.model.MonitoringCommand
import com.destino.app.core.model.MonitoringCoordinator
import com.destino.app.core.model.MonitoringProfile
import com.destino.app.core.model.PlaceSuggestion
import com.destino.app.core.model.PlacesResult
import com.destino.app.core.model.PlacesSearchSource
import com.destino.app.core.model.ScheduleRule
import com.destino.app.core.model.ScheduleRuleType
import com.destino.app.core.model.ScheduleWindow
import com.destino.app.core.model.SoundSelection
import com.destino.app.core.model.UserPreferences
import com.destino.app.core.model.VibrationPattern
import com.destino.app.data.local.AlarmRepository
import com.destino.app.data.local.DestinoDatabase
import com.destino.app.data.local.FavoriteRepository
import com.destino.app.data.local.ScheduleRepository
import com.destino.app.platform.schedule.SystemScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

enum class AlarmModality {
    NOW,        // "Agora" - viagem imediata
    ONE_DATE,   // "Uma data" - execução única futura
    RECURRING   // "Repetir" - rotina em dias da semana
}

data class WindowUiModel(
    val id: String = UUID.randomUUID().toString(),
    val startTime: LocalTime = LocalTime.of(7, 0),
    val endTime: LocalTime = LocalTime.of(9, 0),
    val isAllDay: Boolean = false
)

data class AlarmEditorUiState(
    val alarmId: String? = null,
    val destinationId: String? = null,
    val scheduleRuleId: String? = null,
    val name: String = "",
    val destinationName: String = "",
    val coordinates: Coordinates? = null,
    val placeId: String? = null,
    val radiusMeters: Double = 500.0,
    val customRadiusInput: String = "",
    val isCustomRadius: Boolean = false,
    val modality: AlarmModality = AlarmModality.RECURRING,
    val specificDate: LocalDate = LocalDate.now().plusDays(1),
    val selectedDays: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    ),
    val windows: List<WindowUiModel> = listOf(WindowUiModel()),
    // Busca e favoritos
    val searchQuery: String = "",
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val isSearching: Boolean = false,
    val favorites: List<Favorite> = emptyList(),
    val isFavoriteSheetVisible: Boolean = false,
    // Opções avançadas recolhidas
    val isAdvancedExpanded: Boolean = false,
    val soundSelection: SoundSelection? = null, // null = herdar padrão
    val isVibrationEnabled: Boolean = true,
    val vibrationPattern: VibrationPattern? = null,
    val audioOutputPolicy: AudioOutputPolicy? = null,
    val monitoringProfile: MonitoringProfile? = null,
    // Validação e feedback
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val saveCompleted: Boolean = false,
    val startedSessionId: String? = null,
    val initialIsEnabled: Boolean? = null
) {
    val summaryText: String
        get() {
            val daysStr = when (modality) {
                AlarmModality.NOW -> "Agora (imediato)"
                AlarmModality.ONE_DATE -> specificDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                AlarmModality.RECURRING -> {
                    when {
                        selectedDays.size == 7 -> "Todos os dias"
                        selectedDays == setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) -> "Seg a sex"
                        selectedDays == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Fim de semana"
                        selectedDays.isEmpty() -> "Nenhum dia selecionado"
                        else -> selectedDays.sortedBy { it.value }.joinToString(", ") {
                            when (it) {
                                DayOfWeek.MONDAY -> "Seg"
                                DayOfWeek.TUESDAY -> "Ter"
                                DayOfWeek.WEDNESDAY -> "Qua"
                                DayOfWeek.THURSDAY -> "Qui"
                                DayOfWeek.FRIDAY -> "Sex"
                                DayOfWeek.SATURDAY -> "Sáb"
                                DayOfWeek.SUNDAY -> "Dom"
                            }
                        }
                    }
                }
            }

            val windowStr = when (modality) {
                AlarmModality.NOW -> ""
                else -> {
                    if (windows.any { it.isAllDay }) {
                        " · Dia inteiro"
                    } else {
                        " · " + windows.joinToString(" e ") {
                            "${it.startTime.format(DateTimeFormatter.ofPattern("HH:mm"))}–${it.endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                        }
                    }
                }
            }

            val radiusStr = if (radiusMeters >= 1000) {
                "${String.format("%.1f", radiusMeters / 1000)} km"
            } else {
                "${radiusMeters.toInt()} m"
            }

            val soundStr = soundSelection?.title ?: "Padrão do app"

            return "$daysStr$windowStr · $radiusStr · $soundStr"
        }
}

@HiltViewModel
class AlarmEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val alarmRepository: AlarmRepository,
    private val scheduleRepository: ScheduleRepository,
    private val favoriteRepository: FavoriteRepository,
    private val placesSearchSource: PlacesSearchSource,
    private val coordinator: MonitoringCoordinator,
    private val systemScheduler: SystemScheduler,
    private val preferencesDataStore: DataStore<UserPreferences>,
    private val database: DestinoDatabase
) : ViewModel() {

    private val alarmIdParam: String? = savedStateHandle.get<String>("alarmId")
    private val initialDestinationName: String = savedStateHandle.get<String>("destinationName").orEmpty()
    private val initialCoordinates: Coordinates? = run {
        val lat = savedStateHandle.get<String>("lat")?.toDoubleOrNull()
        val lon = savedStateHandle.get<String>("lon")?.toDoubleOrNull()
        if (lat != null && lon != null) runCatching { Coordinates(lat, lon) }.getOrNull() else null
    }
    private val initialRadius: Double? = savedStateHandle.get<String>("radius")?.toDoubleOrNull()

    private val _uiState = MutableStateFlow(
        AlarmEditorUiState(
            alarmId = alarmIdParam,
            destinationName = initialDestinationName,
            coordinates = initialCoordinates,
            radiusMeters = initialRadius ?: 500.0
        )
    )
    val uiState: StateFlow<AlarmEditorUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var placeDetailsJob: Job? = null

    init {
        // Carrega favoritos
        viewModelScope.launch {
            favoriteRepository.observeAll().collect { favList ->
                _uiState.update { it.copy(favorites = favList) }
            }
        }

        // Se estiver editando um alarme existente, carrega dados
        if (!alarmIdParam.isNullOrBlank()) {
            loadExistingAlarm(alarmIdParam)
        } else {
            // Carrega preferências globais padrão
            viewModelScope.launch {
                val prefs = preferencesDataStore.data.first()
                _uiState.update {
                    it.copy(
                        radiusMeters = initialRadius ?: prefs.defaultRadiusMeters,
                        isVibrationEnabled = prefs.isVibrationEnabled
                    )
                }
            }
        }
    }

    private fun loadExistingAlarm(id: String) {
        viewModelScope.launch {
            val alarm = alarmRepository.getAlarmById(id) ?: return@launch
            val dest = alarmRepository.getDestinationById(alarm.destinationId)
            val rule = scheduleRepository.getRuleForAlarm(id)
            val windows = if (rule != null) scheduleRepository.getWindowsForRule(rule.id) else emptyList()

            val modality = when {
                rule == null -> AlarmModality.NOW
                rule.type == ScheduleRuleType.ONE_OFF -> AlarmModality.ONE_DATE
                else -> AlarmModality.RECURRING
            }

            val windowModels = if (windows.isNotEmpty()) {
                windows.map { WindowUiModel(id = it.id, startTime = it.startTime, endTime = it.endTime, isAllDay = it.isAllDay) }
            } else {
                listOf(WindowUiModel())
            }

            _uiState.update {
                it.copy(
                    alarmId = alarm.id,
                    destinationId = dest?.id,
                    scheduleRuleId = rule?.id,
                    name = alarm.name,
                    destinationName = dest?.name ?: "",
                    coordinates = dest?.coordinates,
                    placeId = dest?.placeId,
                    radiusMeters = alarm.radiusMeters,
                    modality = modality,
                    specificDate = rule?.specificDate ?: LocalDate.now().plusDays(1),
                    selectedDays = rule?.daysOfWeek ?: setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                    windows = windowModels,
                    soundSelection = if (alarm.usesDefaultSound) null else alarm.soundSelection,
                    isVibrationEnabled = alarm.isVibrationEnabled,
                    vibrationPattern = if (alarm.usesDefaultVibrationPattern) null else alarm.vibrationPattern,
                    audioOutputPolicy = if (alarm.usesDefaultAudioOutputPolicy) null else alarm.audioOutputPolicy,
                    monitoringProfile = if (alarm.usesDefaultMonitoringProfile) null else alarm.monitoringProfile,
                    initialIsEnabled = alarm.isEnabled
                )
            }
        }
    }

    fun onNameChange(newName: String) {
        _uiState.update { it.copy(name = newName) }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        placeDetailsJob?.cancel()
        if (query.trim().length < 3) {
            _uiState.update { it.copy(suggestions = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            if (_uiState.value.searchQuery.trim() != query.trim()) return@launch
            _uiState.update { it.copy(isSearching = true) }
            val result = placesSearchSource.searchSuggestions(query.trim())
            if (_uiState.value.searchQuery.trim() != query.trim()) return@launch
            when (result) {
                is PlacesResult.Success -> {
                    _uiState.update { it.copy(suggestions = result.value, isSearching = false) }
                }
                is PlacesResult.Failure -> {
                    _uiState.update { it.copy(suggestions = emptyList(), isSearching = false, errorMessage = result.message) }
                }
            }
        }
    }

    fun onSelectPlaceSuggestion(suggestion: PlaceSuggestion) {
        searchJob?.cancel()
        placeDetailsJob?.cancel()
        placeDetailsJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            val details = placesSearchSource.fetchPlaceDetails(suggestion.placeId)
            when (details) {
                is PlacesResult.Success -> {
                    _uiState.update {
                        it.copy(
                            destinationName = details.value.name,
                            coordinates = details.value.coordinates,
                            destinationId = null,
                            placeId = suggestion.placeId,
                            searchQuery = "",
                            suggestions = emptyList(),
                            isSearching = false
                        )
                    }
                }
                is PlacesResult.Failure -> {
                    _uiState.update { it.copy(isSearching = false, errorMessage = details.message) }
                }
            }
        }
    }

    fun onMapCoordinatesSelected(coords: Coordinates) {
        searchJob?.cancel()
        placeDetailsJob?.cancel()
        _uiState.update {
            it.copy(
                coordinates = coords,
                destinationId = null,
                placeId = null,
                searchQuery = "",
                suggestions = emptyList(),
                isSearching = false,
                destinationName = it.destinationName.ifBlank { "Destino no mapa" }
            )
        }
    }

    fun onSelectFavorite(favorite: Favorite) {
        searchJob?.cancel()
        placeDetailsJob?.cancel()
        _uiState.update {
            it.copy(
                destinationName = favorite.nickname.ifBlank { favorite.destinationName },
                coordinates = favorite.coordinates,
                destinationId = null,
                placeId = null,
                searchQuery = "",
                suggestions = emptyList(),
                isSearching = false,
                radiusMeters = favorite.suggestedRadiusMeters,
                isFavoriteSheetVisible = false
            )
        }
    }

    fun setFavoriteSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(isFavoriteSheetVisible = visible) }
    }

    fun onRadiusPresetSelected(meters: Double) {
        _uiState.update {
            it.copy(
                radiusMeters = meters,
                isCustomRadius = false,
                customRadiusInput = ""
            )
        }
    }

    fun onCustomRadiusChange(input: String) {
        val num = input.replace(',', '.').toDoubleOrNull()
        _uiState.update {
            it.copy(
                customRadiusInput = input,
                isCustomRadius = true,
                radiusMeters = if (num != null && num in 100.0..20000.0) num else it.radiusMeters
            )
        }
    }

    fun onModalityChange(modality: AlarmModality) {
        _uiState.update { it.copy(modality = modality) }
    }

    fun onSpecificDateChange(date: LocalDate) {
        _uiState.update { it.copy(specificDate = date) }
    }

    fun toggleDay(day: DayOfWeek) {
        _uiState.update {
            val current = it.selectedDays.toMutableSet()
            if (current.contains(day)) current.remove(day) else current.add(day)
            it.copy(selectedDays = current)
        }
    }

    fun setDaysShortcut(days: Set<DayOfWeek>) {
        _uiState.update { it.copy(selectedDays = days) }
    }

    fun onUpdateWindow(index: Int, startTime: LocalTime, endTime: LocalTime, isAllDay: Boolean) {
        _uiState.update {
            val updated = it.windows.toMutableList()
            if (index in updated.indices) {
                updated[index] = updated[index].copy(startTime = startTime, endTime = endTime, isAllDay = isAllDay)
            }
            it.copy(windows = updated)
        }
    }

    fun onAddWindow() {
        _uiState.update {
            it.copy(windows = it.windows + WindowUiModel(startTime = LocalTime.of(17, 0), endTime = LocalTime.of(19, 0)))
        }
    }

    fun onRemoveWindow(index: Int) {
        _uiState.update {
            if (it.windows.size > 1 && index in it.windows.indices) {
                val updated = it.windows.toMutableList()
                updated.removeAt(index)
                it.copy(windows = updated)
            } else it
        }
    }

    fun toggleAdvanced() {
        _uiState.update { it.copy(isAdvancedExpanded = !it.isAdvancedExpanded) }
    }

    fun onSoundSelectionChange(sound: SoundSelection?) {
        _uiState.update { it.copy(soundSelection = sound) }
    }

    fun onVibrationToggle(enabled: Boolean) {
        _uiState.update { it.copy(isVibrationEnabled = enabled) }
    }

    fun onVibrationPatternChange(pattern: VibrationPattern?) {
        _uiState.update { it.copy(vibrationPattern = pattern) }
    }

    fun onAudioOutputPolicyChange(policy: AudioOutputPolicy?) {
        _uiState.update { it.copy(audioOutputPolicy = policy) }
    }

    fun onMonitoringProfileChange(profile: MonitoringProfile?) {
        _uiState.update { it.copy(monitoringProfile = profile) }
    }

    fun saveOrStart() {
        val state = _uiState.value
        if (state.isSearching) {
            _uiState.update { it.copy(errorMessage = "Aguarde a conclusão da busca do local antes de salvar.") }
            return
        }
        val coords = state.coordinates
        if (coords == null) {
            _uiState.update { it.copy(errorMessage = "Por favor, selecione um destino no mapa ou pela busca.") }
            return
        }
        if (state.radiusMeters !in 100.0..20000.0) {
            _uiState.update { it.copy(errorMessage = "O raio de aproximação deve estar entre 100 m e 20 km.") }
            return
        }
        if (state.isCustomRadius && state.customRadiusInput.replace(',', '.').toDoubleOrNull()?.let { it in 100.0..20000.0 } != true) {
            _uiState.update { it.copy(errorMessage = "Informe um raio personalizado entre 100 m e 20 km.") }
            return
        }
        if (state.modality != AlarmModality.NOW && state.windows.any { !it.isAllDay && it.startTime == it.endTime }) {
            _uiState.update { it.copy(errorMessage = "O início e o fim da janela não podem ser iguais. Use Dia inteiro.") }
            return
        }
        if (state.modality == AlarmModality.RECURRING && state.selectedDays.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Selecione ao menos um dia da semana para a rotina.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
            val preferences = preferencesDataStore.data.first()
            val destination = Destination(
                id = state.destinationId ?: UUID.randomUUID().toString(),
                name = state.destinationName.ifBlank { "Destino" },
                coordinates = coords,
                providerOrigin = if (state.placeId != null) "GOOGLE_PLACES" else "USER",
                placeId = state.placeId
            )

            val alarmId = state.alarmId ?: UUID.randomUUID().toString()
            val alarm = AlarmDefinition(
                id = alarmId,
                destinationId = destination.id,
                radiusMeters = state.radiusMeters,
                isVibrationEnabled = state.isVibrationEnabled,
                name = state.name.ifBlank { state.destinationName.ifBlank { "Meu Alarme" } },
                isEnabled = state.initialIsEnabled ?: true,
                soundSelection = state.soundSelection ?: preferences.defaultSoundSelection,
                usesDefaultSound = state.soundSelection == null,
                vibrationPattern = state.vibrationPattern ?: preferences.defaultVibrationPattern,
                usesDefaultVibrationPattern = state.vibrationPattern == null,
                audioOutputPolicy = state.audioOutputPolicy ?: preferences.defaultAudioOutputPolicy,
                usesDefaultAudioOutputPolicy = state.audioOutputPolicy == null,
                monitoringProfile = state.monitoringProfile ?: preferences.defaultMonitoringProfile,
                usesDefaultMonitoringProfile = state.monitoringProfile == null,
                allowNewEntrySameWindow = preferences.allowNewEntrySameWindow
            )

            if (state.modality == AlarmModality.NOW) {
                database.withTransaction {
                    alarmRepository.saveDestinationAndAlarm(destination, alarm)
                    scheduleRepository.deleteScheduleForAlarm(alarmId)
                }
                // Inicia viagem imediata
                val startResult = coordinator.execute(
                    MonitoringCommand.StartJourney(
                        commandId = UUID.randomUUID().toString(),
                        destinationName = alarm.name,
                        coordinates = coords,
                        radiusMeters = state.radiusMeters,
                        isVibrationEnabled = state.isVibrationEnabled,
                        destinationId = destination.id,
                        alarmId = alarm.id,
                        alarmName = alarm.name,
                        soundSelection = alarm.soundSelection,
                        vibrationPattern = alarm.vibrationPattern,
                        audioOutputPolicy = alarm.audioOutputPolicy,
                        monitoringProfile = alarm.monitoringProfile,
                        allowNewEntrySameWindow = alarm.allowNewEntrySameWindow
                    )
                )
                when (startResult) {
                    is CommandResult.Success -> {
                        val session = coordinator.operationalState.value.activeOccurrences.values
                            .firstOrNull { occurrence -> occurrence.alarm.id == alarm.id }?.session?.id
                            ?: alarmRepository.getActiveSessionsForAlarm(alarm.id).firstOrNull()?.session?.id
                            ?: coordinator.operationalState.value.activeSession?.id
                        _uiState.update { it.copy(startedSessionId = session) }
                    }
                    is CommandResult.AlreadyActive -> _uiState.update { it.copy(startedSessionId = startResult.sessionId) }
                    is CommandResult.Rejected -> {
                        _uiState.update { it.copy(isSaving = false, errorMessage = startResult.reason) }
                        return@launch
                    }
                    is CommandResult.Error -> {
                        _uiState.update { it.copy(isSaving = false, errorMessage = startResult.message) }
                        return@launch
                    }
                }
            } else {
                // Salva agenda (Data única ou recorrente)
                val ruleId = state.scheduleRuleId ?: UUID.randomUUID().toString()
                val rule = ScheduleRule(
                    id = ruleId,
                    alarmDefinitionId = alarmId,
                    type = if (state.modality == AlarmModality.ONE_DATE) ScheduleRuleType.ONE_OFF else ScheduleRuleType.RECURRING,
                    specificDate = if (state.modality == AlarmModality.ONE_DATE) state.specificDate else null,
                    daysOfWeek = if (state.modality == AlarmModality.RECURRING) state.selectedDays else emptySet()
                )

                val scheduleWindows = state.windows.map { w ->
                    ScheduleWindow(
                        id = w.id,
                        ruleId = ruleId,
                        startTime = if (w.isAllDay) LocalTime.MIDNIGHT else w.startTime,
                        endTime = if (w.isAllDay) LocalTime.MIDNIGHT else w.endTime,
                        isAllDay = w.isAllDay
                    )
                }

                database.withTransaction {
                    alarmRepository.saveDestinationAndAlarm(destination, alarm)
                    scheduleRepository.saveSchedule(rule, scheduleWindows)
                }
                systemScheduler.reconcileSchedules()
            }

            _uiState.update { it.copy(isSaving = false, saveCompleted = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = e.message ?: "Não foi possível salvar o alarme.")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
