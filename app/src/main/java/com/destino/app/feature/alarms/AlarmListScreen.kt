package com.destino.app.feature.alarms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.destino.app.ui.components.*
import com.destino.app.ui.theme.DestinoAttention

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    viewModel: AlarmListViewModel,
    onCreateAlarm: () -> Unit,
    onEditAlarm: (alarmId: String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToJourney: (String) -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    BackHandler(onBack = onNavigateToHome)

    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var sheetItem by remember { mutableStateOf<AlarmItemUi?>(null) }
    var removal by remember { mutableStateOf<AlarmItemUi?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { snackbar.showSnackbar(it); viewModel.clearUserMessage() }
    }

    LaunchedEffect(state.startedSessionId) {
        state.startedSessionId?.let { sessionId ->
            onNavigateToJourney(sessionId)
            viewModel.clearStartedSessionId()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            DestinoTopBar("Seus alarmes", action = {
                FilledIconButton(
                    onClick = onCreateAlarm,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Icon(Icons.Default.Add, "Criar alarme") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.alarms.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(20.dp), contentAlignment = Alignment.Center) {
                EmptyDestination("Nenhum alarme criado", "Crie um aviso para sua próxima viagem.", Icons.Default.Notifications, "Criar alarme", onCreateAlarm)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.alarms, key = { it.alarm.id }) { item ->
                    AlarmCard(
                        item = item,
                        onToggle = { viewModel.toggleAlarm(item.alarm.id, it) },
                        onEdit = { onEditAlarm(item.alarm.id) },
                        onStart = { viewModel.startImmediately(item) },
                        onMore = { sheetItem = item }
                    )
                }
            }
        }
    }

    sheetItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { sheetItem = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(
                        placeIcon(item.alarm.name.ifBlank { item.destination?.name.orEmpty() }),
                        background = if (item.rule == null) DestinoAttention else MaterialTheme.colorScheme.primaryContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(item.alarm.name.ifBlank { item.destination?.name ?: "Alarme" }, style = MaterialTheme.typography.titleLarge)
                        Text("Ações do alarme", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                SheetAction(Icons.Default.Edit, "Editar alarme") { sheetItem = null; onEditAlarm(item.alarm.id) }
                SheetAction(Icons.Default.PlayArrow, "Iniciar agora") { sheetItem = null; viewModel.startImmediately(item) }
                if (item.rule != null) {
                    SheetAction(Icons.Default.SkipNext, "Pular próxima ocorrência") { sheetItem = null; viewModel.skipNextOccurrence(item) }
                }
                SheetAction(Icons.Default.ContentCopy, "Duplicar") { sheetItem = null; viewModel.duplicateAlarm(item) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SheetAction(Icons.Default.Delete, "Excluir alarme", MaterialTheme.colorScheme.error) {
                    sheetItem = null
                    removal = item
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    Modifier.fillMaxWidth().clickable { sheetItem = null },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Fechar", Modifier.padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }

    removal?.let { item ->
        ConfirmRemoval(
            "Excluir alarme?",
            "A rotina será removida. Esta ação não pode ser desfeita.",
            { removal = null },
            { viewModel.deleteAlarm(item.alarm.id); removal = null }
        )
    }
}

@Composable
private fun AlarmCard(
    item: AlarmItemUi,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onStart: () -> Unit,
    onMore: () -> Unit
) {
    val manual = item.rule == null
    DestinoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(
                placeIcon(item.alarm.name.ifBlank { item.destination?.name.orEmpty() }),
                background = if (manual) DestinoAttention else MaterialTheme.colorScheme.primaryContainer
            )
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text(
                    item.alarm.name.ifBlank { item.destination?.name ?: "Alarme" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = if (manual) {
                    "Manual • Avisar a ${item.alarm.radiusMeters.toInt()} m"
                } else {
                    "${item.scheduleSummary} • ${item.alarm.radiusMeters.toInt()} m • ${item.statusBadge}"
                }
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.nextActivationDescription?.let { desc ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.statusBadge == "Requisitos pendentes") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!manual) Switch(checked = item.alarm.isEnabled, onCheckedChange = onToggle)
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "Ações do alarme") }
        }
        if (manual) {
            OutlinedButton(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Iniciar agora")
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), color = color, style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = color)
    }
}
