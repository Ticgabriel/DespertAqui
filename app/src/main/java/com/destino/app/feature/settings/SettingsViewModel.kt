package com.destino.app.feature.settings

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.AppTheme
import com.destino.app.core.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsMainUiState(
    val currentTheme: AppTheme = AppTheme.LIGHT,
    val soundSummary: String = "Toque do sistema",
    val monitoringSummary: String = "Automático",
    val historyRetentionDays: Int = 30
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesDataStore: DataStore<UserPreferences>,
    private val placesAccess: com.destino.app.platform.places.PlacesAccessStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsMainUiState())
    val uiState: StateFlow<SettingsMainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesDataStore.data.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        currentTheme = prefs.theme,
                        soundSummary = prefs.defaultSoundSelection.title,
                        monitoringSummary = when (prefs.defaultMonitoringProfile) {
                            com.destino.app.core.model.MonitoringProfile.AUTOMATIC -> "Automático"
                            com.destino.app.core.model.MonitoringProfile.HIGH_PRECISION -> "Mais precisão"
                            com.destino.app.core.model.MonitoringProfile.BATTERY_SAVER -> "Economia"
                            com.destino.app.core.model.MonitoringProfile.CUSTOM -> "Personalizado"
                        },
                        historyRetentionDays = prefs.historyRetentionDays
                    )
                }
            }
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(theme = theme) }
        }
    }

    fun openPlacesKey() = placesAccess.openSettings()
}
