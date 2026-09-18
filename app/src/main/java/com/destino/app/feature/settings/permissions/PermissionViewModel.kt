package com.destino.app.feature.settings.permissions

import androidx.lifecycle.ViewModel
import com.destino.app.platform.runtime.CapabilityProvider
import com.destino.app.platform.runtime.CapabilityReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PermissionViewModel @Inject constructor(
    private val capabilityProvider: CapabilityProvider
) : ViewModel() {

    private val _report = MutableStateFlow(capabilityProvider.getDetailedReport())
    val report: StateFlow<CapabilityReport> = _report.asStateFlow()

    fun refresh() {
        _report.value = capabilityProvider.getDetailedReport()
    }
}
