package com.destino.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import com.destino.app.core.model.AppTheme
import com.destino.app.ui.components.*

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToAudio: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToMonitoring: () -> Unit,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToHome: () -> Unit = {}
) {
    BackHandler(onBack = onNavigateToHome)

    val state by viewModel.uiState.collectAsState()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            DestinoBrand()
            Spacer(Modifier.height(24.dp))
            Text("Ajustes", style = MaterialTheme.typography.headlineLarge)
        }
        item { DestinoCard(tinted = true) { DetailRow("Som e vibração", "${state.soundSummary} • Vibração ligada", Icons.Default.VolumeUp, onNavigateToAudio) } }
        item { DestinoCard(tinted = true) { DetailRow("Funcionamento e permissões", "Verificar aparelho", Icons.Default.Settings, onNavigateToPermissions) } }
        item { DestinoCard(tinted = true) { DetailRow("Monitoramento", state.monitoringSummary, Icons.Default.GpsFixed, onNavigateToMonitoring) } }
        item { DestinoCard(tinted = true) { DetailRow("Chave de busca", "Cadastrar chave própria e consultar tutorial", Icons.Default.Key, viewModel::openPlacesKey) } }
        item { DestinoCard(tinted = true) { DetailRow("Histórico de viagens", "Guardar por ${state.historyRetentionDays} dias", Icons.Default.History, onNavigateToHistory) } }
        item {
            DestinoCard {
                Text("Aparência", style = MaterialTheme.typography.titleLarge)
                SegmentedPills(
                    listOf(
                        Icons.Default.Settings to "Automático",
                        Icons.Default.LightMode to "Claro",
                        Icons.Default.DarkMode to "Escuro"
                    ),
                    when (state.currentTheme) {
                        AppTheme.SYSTEM -> 0
                        AppTheme.LIGHT -> 1
                        AppTheme.DARK -> 2
                    },
                    { index -> viewModel.setTheme(listOf(AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK)[index]) }
                )
            }
        }
        item {
            Spacer(Modifier.height(14.dp))
            Text("DespertAqui", style = MaterialTheme.typography.titleMedium)
            Text("Histórico e rotinas salvos no aparelho", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
