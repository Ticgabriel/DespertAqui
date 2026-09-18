package com.destino.app.feature.settings.audio

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.AlertController
import com.destino.app.core.model.AudioOutputPolicy
import com.destino.app.core.model.HeadphoneDisconnectBehavior
import com.destino.app.core.model.SoundSelection
import com.destino.app.core.model.SoundSourceType
import com.destino.app.core.model.UserPreferences
import com.destino.app.core.model.VibrationPattern
import com.destino.app.platform.audio.AudioRouteObserver
import com.destino.app.platform.audio.AudioRouteState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AudioSettingsUiState(
    val soundSelection: SoundSelection = SoundSelection(),
    val isVibrationEnabled: Boolean = true,
    val vibrationPattern: VibrationPattern = VibrationPattern.STRONG,
    val audioOutputPolicy: AudioOutputPolicy = AudioOutputPolicy.SYSTEM_DEFAULT,
    val disconnectBehavior: HeadphoneDisconnectBehavior = HeadphoneDisconnectBehavior.CONTINUE_WITH_SPEAKER,
    val alarmDurationMinutes: Int = 3,
    val isGradualVolumeEnabled: Boolean = false,
    val isPreviewPlaying: Boolean = false,
    val previewMessage: String? = null
)

@HiltViewModel
class AudioSettingsViewModel @Inject constructor(
    private val preferencesDataStore: DataStore<UserPreferences>,
    private val alertController: AlertController,
    private val audioRouteObserver: AudioRouteObserver
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioSettingsUiState())
    val uiState: StateFlow<AudioSettingsUiState> = _uiState.asStateFlow()

    val routeState: StateFlow<AudioRouteState> = audioRouteObserver.routeState

    init {
        viewModelScope.launch {
            val prefs = preferencesDataStore.data.first()
            _uiState.update {
                it.copy(
                    soundSelection = prefs.defaultSoundSelection,
                    isVibrationEnabled = prefs.isVibrationEnabled,
                    vibrationPattern = prefs.defaultVibrationPattern,
                    audioOutputPolicy = prefs.defaultAudioOutputPolicy,
                    disconnectBehavior = prefs.defaultHeadphoneDisconnectBehavior,
                    alarmDurationMinutes = prefs.alarmAudioDurationMinutes,
                    isGradualVolumeEnabled = prefs.isGradualVolumeEnabled
                )
            }
        }
    }

    fun setSoundSelection(selection: SoundSelection) {
        _uiState.update { it.copy(soundSelection = selection) }
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(defaultSoundSelection = selection) }
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isVibrationEnabled = enabled) }
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(isVibrationEnabled = enabled) }
        }
    }

    fun setVibrationPattern(pattern: VibrationPattern) {
        _uiState.update { it.copy(vibrationPattern = pattern) }
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(defaultVibrationPattern = pattern) }
        }
    }

    fun setAudioOutputPolicy(policy: AudioOutputPolicy) {
        _uiState.update { it.copy(audioOutputPolicy = policy) }
        audioRouteObserver.setPreferredPolicy(policy)
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(defaultAudioOutputPolicy = policy) }
        }
    }

    fun setDisconnectBehavior(behavior: HeadphoneDisconnectBehavior) {
        _uiState.update { it.copy(disconnectBehavior = behavior) }
        audioRouteObserver.setDisconnectBehavior(behavior)
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(defaultHeadphoneDisconnectBehavior = behavior) }
        }
    }

    fun setAlarmDurationMinutes(minutes: Int) {
        _uiState.update { it.copy(alarmDurationMinutes = minutes) }
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(alarmAudioDurationMinutes = minutes) }
        }
    }

    fun setGradualVolumeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isGradualVolumeEnabled = enabled) }
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(isGradualVolumeEnabled = enabled) }
        }
    }

    fun togglePreview() {
        if (_uiState.value.isPreviewPlaying) {
            viewModelScope.launch {
                alertController.stopPreview()
                _uiState.update { it.copy(isPreviewPlaying = false, previewMessage = null) }
            }
        } else {
            viewModelScope.launch {
                _uiState.update { it.copy(isPreviewPlaying = true, previewMessage = "Reproduzindo prévia (4s)...") }
                alertController.previewSound(_uiState.value.soundSelection)
                // A prévia desliga automaticamente após 4s no controller
                kotlinx.coroutines.delay(4100L)
                _uiState.update { it.copy(isPreviewPlaying = false, previewMessage = null) }
            }
        }
    }
}
