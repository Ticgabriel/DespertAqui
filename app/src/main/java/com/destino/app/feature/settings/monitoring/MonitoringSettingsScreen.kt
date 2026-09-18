package com.destino.app.feature.settings.monitoring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.destino.app.core.model.MonitoringProfile
import com.destino.app.ui.components.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitoringSettingsScreen(
    viewModel: MonitoringSettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(modifier, topBar = { DestinoTopBar("Monitoramento", onNavigateBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { ProfileCard("Automático", "Equilibra precisão e bateria", Icons.Default.Speed, state.selectedProfile == MonitoringProfile.AUTOMATIC) { viewModel.selectProfile(MonitoringProfile.AUTOMATIC) } }
            item { ProfileCard("Mais precisão", "Leituras mais frequentes", Icons.Default.GpsFixed, state.selectedProfile == MonitoringProfile.HIGH_PRECISION) { viewModel.selectProfile(MonitoringProfile.HIGH_PRECISION) } }
            item { ProfileCard("Economia de bateria", "Menos leituras quando distante", Icons.Default.BatterySaver, state.selectedProfile == MonitoringProfile.BATTERY_SAVER) { viewModel.selectProfile(MonitoringProfile.BATTERY_SAVER) } }
            item { ProfileCard("Personalizado", "Ajustar intervalos", Icons.Default.Tune, state.selectedProfile == MonitoringProfile.CUSTOM) { viewModel.selectProfile(MonitoringProfile.CUSTOM) } }

            if (state.selectedProfile == MonitoringProfile.CUSTOM) {
                item {
                    DestinoCard {
                        Text("Intervalo distante: ${state.customDistantIntervalSeconds} s", style = MaterialTheme.typography.titleMedium)
                        Slider(state.customDistantIntervalSeconds.toFloat(), { viewModel.updateCustomDistantInterval(it.toInt()) }, valueRange = 10f..60f, steps = 9)
                        Text("Intervalo ao aproximar: ${state.customApproachingIntervalSeconds} s", style = MaterialTheme.typography.titleMedium)
                        Slider(state.customApproachingIntervalSeconds.toFloat(), { viewModel.updateCustomApproachingInterval(it.toInt()) }, valueRange = 2f..15f, steps = 12)
                    }
                }
            }

            item {
                DestinoCard {
                    DetailRow(
                        "Raio padrão",
                        if (state.defaultRadiusMeters >= 1000) String.format("%.0f km", state.defaultRadiusMeters / 1000) else "${state.defaultRadiusMeters.toInt()} m",
                        Icons.Default.RadioButtonChecked,
                        trailing = { Icon(Icons.Default.ChevronRight, null) }
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(200.0 to "200 m", 500.0 to "500 m", 1000.0 to "1 km", 2000.0 to "2 km").forEach { (meters, label) ->
                            FilterChip(
                                selected = state.defaultRadiusMeters == meters,
                                onClick = { viewModel.updateDefaultRadius(meters) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                            )
                        }
                    }
                }
            }

            item { MonitoringToggle("Avisar sobre sinal fraco", "Receber um aviso se o sinal estiver fraco", Icons.Default.Notifications, state.isWeakSignalWarningEnabled, viewModel::toggleWeakSignalWarning) }
            item { MonitoringToggle("Repetir por nova entrada", "Ativar alarme ao entrar novamente", Icons.Default.Refresh, state.allowNewEntrySameWindow, viewModel::toggleAllowNewEntrySameWindow) }
            item { InfoCard("Nova entrada após sair da área e aguardar 5 min. A frequência real também depende do Android e do sinal disponível.") }
        }
    }
}

@Composable
private fun ProfileCard(title: String, subtitle: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    DestinoCard(modifier = Modifier.clickable(onClick = onClick), tinted = selected) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(
                icon,
                background = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected, onClick)
        }
    }
}

@Composable
private fun MonitoringToggle(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    DestinoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onChange)
        }
    }
}
