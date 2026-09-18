package com.destino.app.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.destino.app.core.model.HistoryEventType
import com.destino.app.core.model.HistoryRecord
import com.destino.app.ui.components.*
import com.destino.app.ui.theme.DestinoAttention
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val zone = ZoneId.systemDefault()
    val grouped = state.records.groupBy { Instant.ofEpochMilli(it.timestampEpochMs).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { snackbar.showSnackbar(it); viewModel.clearUserMessage() }
    }

    Scaffold(
        modifier,
        topBar = {
            DestinoTopBar("Histórico de viagens", onNavigateBack) {
                IconButton(onClick = { viewModel.setClearConfirmationVisible(true) }) {
                    Icon(Icons.Default.Delete, "Limpar histórico", tint = MaterialTheme.colorScheme.error)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Guardar histórico por", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 15, 30, 90).forEach { days ->
                        FilterChip(
                            selected = state.retentionDays == days,
                            onClick = { viewModel.setRetentionDays(days) },
                            label = { Text("$days dias") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
            if (state.records.isEmpty()) {
                item {
                    EmptyDestination(
                        "Nenhuma viagem registrada",
                        "Os eventos das suas viagens aparecerão aqui.",
                        Icons.Default.History,
                        "Voltar",
                        onNavigateBack
                    )
                }
            } else {
                grouped.forEach { (date, records) ->
                    item { Text(sectionTitle(date), style = MaterialTheme.typography.titleLarge) }
                    items(records.size) { index ->
                        TimelineItem(records[index], index == records.lastIndex, zone)
                    }
                }
            }
        }
    }

    if (state.isClearConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.setClearConfirmationVisible(false) },
            title = { Text("Limpar histórico?") },
            text = { Text("Todos os registros de viagens salvos no aparelho serão excluídos permanentemente.") },
            confirmButton = { TextButton(onClick = viewModel::clearAllHistory) { Text("Limpar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { viewModel.setClearConfirmationVisible(false) }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun TimelineItem(record: HistoryRecord, last: Boolean, zone: ZoneId) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(52.dp).height(112.dp), contentAlignment = Alignment.TopCenter) {
            if (!last) Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer))
            Box(Modifier.padding(top = 4.dp).size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(placeIcon(record.alarmName.ifBlank { record.destinationName }), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(record.alarmName.ifBlank { record.destinationName }, style = MaterialTheme.typography.titleMedium)
            if (record.alarmName.isNotBlank() && record.alarmName != record.destinationName) {
                Text(record.destinationName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                Instant.ofEpochMilli(record.timestampEpochMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusTag(record.eventType, record.outcomeDescription)
        }
    }
}

@Composable
private fun StatusTag(type: HistoryEventType, text: String) {
    val background = when (type) {
        HistoryEventType.ARRIVAL -> Color(0xFFDDEDDD)
        HistoryEventType.START -> MaterialTheme.colorScheme.surfaceVariant
        HistoryEventType.INTERRUPTED, HistoryEventType.EXPIRED, HistoryEventType.DELIVERY_FAILED -> DestinoAttention
        HistoryEventType.PRECAUTION -> DestinoAttention
    }
    Surface(color = background, shape = RoundedCornerShape(12.dp)) {
        Text(
            when (type) {
                HistoryEventType.ARRIVAL -> "Alerta de proximidade"
                else -> text
            },
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun sectionTitle(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Hoje"
        today.minusDays(1) -> "Ontem"
        else -> date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }
}
