package com.destino.app.feature.history

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.destino.app.core.model.HistoryRecord
import com.destino.app.core.model.UserPreferences
import com.destino.app.data.local.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val records: List<HistoryRecord> = emptyList(),
    val retentionDays: Int = 30,
    val isClearConfirmationVisible: Boolean = false,
    val userMessage: String? = null
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val preferencesDataStore: DataStore<UserPreferences>
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            historyRepository.observeAll().collectLatest { list ->
                _uiState.update { it.copy(records = list) }
            }
        }

        viewModelScope.launch {
            preferencesDataStore.data.collectLatest { prefs ->
                _uiState.update { it.copy(retentionDays = prefs.historyRetentionDays) }
                // Purga registros antigos automaticamente
                historyRepository.purgeOldRecords(prefs.historyRetentionDays)
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
            _uiState.update { it.copy(isClearConfirmationVisible = false, userMessage = "Histórico limpo!") }
        }
    }

    fun setRetentionDays(days: Int) {
        viewModelScope.launch {
            preferencesDataStore.updateData { it.copy(historyRetentionDays = days) }
            historyRepository.purgeOldRecords(days)
        }
    }

    fun setClearConfirmationVisible(visible: Boolean) {
        _uiState.update { it.copy(isClearConfirmationVisible = visible) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
