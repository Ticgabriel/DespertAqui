package com.destino.app.feature.settings.monitoring

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.MonitoringProfile
import com.destino.app.core.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MonitoringSettingsUiState(
    val selectedProfile: MonitoringProfile = MonitoringProfile.AUTOMATIC,
    val isWeakSignalWarningEnabled: Boolean = true,
    val allowNewEntrySameWindow: Boolean = false,
    val defaultRadiusMeters: Double = 500.0,
    val customDistantIntervalSeconds: Int = 30,
    val customApproachingIntervalSeconds: Int = 5,
    val isSavedMessageVisible: Boolean = false
)

@HiltViewModel
class MonitoringSettingsViewModel @Inject constructor(
    private val preferencesDataStore: DataStore<UserPreferences>
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonitoringSettingsUiState())
    val uiState: StateFlow<MonitoringSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesDataStore.data.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        selectedProfile = prefs.defaultMonitoringProfile,
                        isWeakSignalWarningEnabled = prefs.isWeakSignalWarningEnabled,
                        allowNewEntrySameWindow = prefs.allowNewEntrySameWindow,
                        defaultRadiusMeters = prefs.defaultRadiusMeters,
                        customDistantIntervalSeconds = prefs.customDistantIntervalSeconds,
                        customApproachingIntervalSeconds = prefs.customApproachingIntervalSeconds
                    )
                }
            }
        }
    }

    fun selectProfile(profile: MonitoringProfile) {
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(defaultMonitoringProfile = profile) }
        }
    }

    fun toggleWeakSignalWarning(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(isWeakSignalWarningEnabled = enabled) }
        }
    }

    fun toggleAllowNewEntrySameWindow(enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(allowNewEntrySameWindow = enabled) }
        }
    }

    fun updateDefaultRadius(radiusMeters: Double) {
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(defaultRadiusMeters = radiusMeters) }
        }
    }

    fun updateCustomDistantInterval(seconds: Int) {
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(customDistantIntervalSeconds = seconds) }
        }
    }

    fun updateCustomApproachingInterval(seconds: Int) {
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(customApproachingIntervalSeconds = seconds) }
        }
    }
}
