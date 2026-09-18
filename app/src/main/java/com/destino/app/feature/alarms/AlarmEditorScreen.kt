package com.destino.app.feature.alarms

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.destino.app.core.model.AudioOutputPolicy
import com.destino.app.core.model.MonitoringProfile
import com.destino.app.core.model.SoundSelection
import com.destino.app.core.model.SoundSourceType
import com.destino.app.core.model.VibrationPattern
import com.destino.app.feature.setup.map.InteractiveMapComponent
import com.destino.app.ui.components.*
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private enum class EditorSubscreen { SOUND, MONITORING }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditorScreen(
    viewModel: AlarmEditorViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onJourneyStarted: (String) -> Unit = { onNavigateBack() },
    onSaveCompleted: () -> Unit = onNavigateBack
) {
    val state by viewModel.uiState.collectAsState()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var subscreen by rememberSaveable { mutableStateOf<EditorSubscreen?>(null) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri == null) viewModel.onSoundSelectionChange(SoundSelection(SoundSourceType.SILENT, null, "Silencioso"))
            else {
                val title = RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Toque do aparelho"
                viewModel.onSoundSelectionChange(SoundSelection(SoundSourceType.SYSTEM_RINGTONE, uri.toString(), title))
            }
        }
    }

    val goBack: () -> Unit = {
        when {
            subscreen != null -> subscreen = null
            step > 0 -> step--
            else -> showDiscard = true
        }
        Unit
    }
    BackHandler(onBack = goBack)

    LaunchedEffect(step) { listState.scrollToItem(0) }
    LaunchedEffect(state.errorMessage, localMessage) {
        (state.errorMessage ?: localMessage)?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
            localMessage = null
        }
    }
    LaunchedEffect(state.saveCompleted) {
        if (state.saveCompleted) state.startedSessionId?.let(onJourneyStarted) ?: onSaveCompleted()
    }

    if (subscreen == EditorSubscreen.SOUND) {
        AlarmSoundScreen(
            state = state,
            onBack = { subscreen = null },
            onApply = { subscreen = null },
            onUseDefault = { enabled ->
                if (enabled) {
                    viewModel.onSoundSelectionChange(null)
                    viewModel.onVibrationPatternChange(null)
                    viewModel.onAudioOutputPolicyChange(null)
                } else if (state.soundSelection == null) {
                    viewModel.onSoundSelectionChange(SoundSelection())
                }
            },
            onChooseRingtone = {
                ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                })
            },
            onVibration = viewModel::onVibrationToggle,
            onVibrationPattern = viewModel::onVibrationPatternChange,
            onAudioOutput = viewModel::onAudioOutputPolicyChange
        )
        return
    }
    if (subscreen == EditorSubscreen.MONITORING) {
        AlarmMonitoringScreen(
            state = state,
            onBack = { subscreen = null },
            onApply = { subscreen = null },
            onProfile = viewModel::onMonitoringProfileChange
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            DestinoTopBar(
                when (step) {
                    0 -> if (state.alarmId == null) "Novo alarme" else "Editar alarme"
                    1 -> "Configurar alarme"
                    else -> "Revisar alarme"
                },
                back = goBack
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Column(Modifier.imePadding().padding(20.dp)) {
                    PrimaryAction(
                        label = when {
                            step < 2 -> "Continuar"
                            state.isSaving -> "Salvando…"
                            state.modality == AlarmModality.NOW -> "Iniciar agora"
                            else -> "Salvar alarme"
                        },
                        enabled = !state.isSaving && canContinue(step, state),
                        icon = if (step == 2 && state.modality == AlarmModality.NOW) Icons.Default.PlayArrow else null
                    ) {
                        if (step < 2) step++ else viewModel.saveOrStart()
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Text("${step + 1} de 3 • ${listOf("Destino", "Quando avisar", "Confirmar")[step]}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (step) {
                0 -> destinationStep(state, viewModel, { localMessage = it })
                1 -> scheduleStep(state, viewModel)
                2 -> reviewStep(
                    state,
                    onDestination = { step = 0 },
                    onSchedule = { step = 1 },
                    onSound = { subscreen = EditorSubscreen.SOUND },
                    onMonitoring = { subscreen = EditorSubscreen.MONITORING }
                )
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Sair da edição?") },
            text = { Text("As alterações ainda não salvas serão descartadas.") },
            confirmButton = { TextButton(onClick = onNavigateBack) { Text("Descartar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Continuar editando") } }
        )
    }

    if (state.isFavoriteSheetVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.setFavoriteSheetVisible(false) },
            title = { Text("Favoritos") },
            text = {
                if (state.favorites.isEmpty()) Text("Você ainda não salvou locais favoritos.")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.favorites, key = { it.id }) { favorite ->
                        DetailRow(
                            favorite.nickname,
                            favorite.destinationName,
                            placeIcon(favorite.nickname),
                            { viewModel.onSelectFavorite(favorite) }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.setFavoriteSheetVisible(false) }) { Text("Fechar") } }
        )
    }
}

private fun LazyListScope.destinationStep(
    state: AlarmEditorUiState,
    viewModel: AlarmEditorViewModel,
    showMessage: (String) -> Unit
) {
    item { Text("Para onde vamos?", style = MaterialTheme.typography.headlineLarge) }
    item {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.isSearching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else if (state.searchQuery.isNotBlank()) IconButton(onClick = { viewModel.onSearchQueryChange("") }) { Icon(Icons.Default.Close, "Limpar busca") }
            },
            placeholder = { Text("Buscar endereço ou local") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp)
        )
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AssistChip(
                onClick = { viewModel.setFavoriteSheetVisible(true) },
                label = { Text("Favoritos") },
                leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = { showMessage("Destinos recentes ficam disponíveis na aba Locais.") },
                label = { Text("Recentes") },
                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) }
            )
        }
    }
    if (state.suggestions.isNotEmpty()) {
        item {
            DestinoCard {
                state.suggestions.forEach { suggestion ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.onSelectPlaceSuggestion(suggestion) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(suggestion.primaryText, style = MaterialTheme.typography.titleMedium)
                            Text(suggestion.secondaryText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    item {
        InteractiveMapComponent(
            state.coordinates,
            state.radiusMeters,
            state.destinationName,
            viewModel::onMapCoordinatesSelected,
            Modifier.height(270.dp)
        )
    }
    if (state.coordinates != null) {
        item {
            DestinoCard {
                DetailRow(
                    state.destinationName.ifBlank { "Ponto selecionado" },
                    "Ponto escolhido no mapa",
                    Icons.Default.Place
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nome do alarme") },
                    placeholder = { Text("Ex.: Volta para casa") },
                    trailingIcon = {
                        if (state.name.isNotBlank()) IconButton(onClick = { viewModel.onNameChange("") }) { Icon(Icons.Default.Close, "Limpar nome") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    }
}

private fun LazyListScope.scheduleStep(state: AlarmEditorUiState, viewModel: AlarmEditorViewModel) {
    item {
        InteractiveMapComponent(state.coordinates, state.radiusMeters, state.destinationName, {}, Modifier.height(155.dp))
    }
    item {
        DestinoCard {
            DetailRow(
                state.destinationName.ifBlank { "Destino selecionado" },
                "Ponto confirmado",
                Icons.Default.Place,
                onClick = null,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
            )
        }
    }
    item {
        DestinoCard {
            Text("Avisar quando estiver a", style = MaterialTheme.typography.titleLarge)
            Text(
                formatDistance(state.radiusMeters),
                fontSize = 56.sp,
                lineHeight = 62.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            val presets = listOf(100.0 to "100 m", 200.0 to "200 m", 500.0 to "500 m", 1000.0 to "1 km", 2000.0 to "2 km", 5000.0 to "5 km")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { (meters, label) ->
                    FilterChip(
                        selected = !state.isCustomRadius && state.radiusMeters == meters,
                        onClick = { viewModel.onRadiusPresetSelected(meters) },
                        label = { Text(label) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
            OutlinedTextField(
                state.customRadiusInput,
                viewModel::onCustomRadiusChange,
                Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Straighten, null) },
                placeholder = { Text("Digite a distância em metros") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
        }
    }
    item {
        Text("Quando ativar", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        SegmentedPills(
            listOf(null to "Agora", null to "Uma data", null to "Repetir"),
            state.modality.ordinal,
            { viewModel.onModalityChange(AlarmModality.entries[it]) }
        )
    }
    if (state.modality == AlarmModality.ONE_DATE) {
        item {
            val context = LocalContext.current
            DestinoCard {
                DetailRow(
                    "Data da viagem",
                    state.specificDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    Icons.Default.CalendarMonth,
                    onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day -> viewModel.onSpecificDateChange(java.time.LocalDate.of(year, month + 1, day)) },
                        state.specificDate.year,
                        state.specificDate.monthValue - 1,
                        state.specificDate.dayOfMonth
                    ).show()
                    }
                )
            }
        }
    }
    if (state.modality == AlarmModality.RECURRING) {
        item {
            DestinoCard {
                Text("Dias da semana", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DayOfWeek.entries.forEach { day ->
                        val selected = day in state.selectedDays
                        Surface(
                            Modifier.size(42.dp).clickable { viewModel.toggleDay(day) },
                            shape = CircleShape,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(dayLetter(day), fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ viewModel.setDaysShortcut(weekdays) }, { Text("Dias úteis") })
                    AssistChip({ viewModel.setDaysShortcut(DayOfWeek.entries.toSet()) }, { Text("Todos") })
                    AssistChip({ viewModel.setDaysShortcut(weekend) }, { Text("Fim de semana") })
                }
            }
        }
    }
    if (state.modality != AlarmModality.NOW) {
        item {
            DestinoCard {
                Text("Horários de acompanhamento", style = MaterialTheme.typography.titleLarge)
                Text("O monitoramento atua somente dentro destes horários.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.windows.forEachIndexed { index, window ->
                    if (index > 0) HorizontalDivider()
                    ScheduleWindowRow(window, index, state.windows.size, viewModel)
                }
                OutlinedButton(
                    onClick = viewModel::onAddWindow,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Adicionar horário")
                }
            }
        }
    }
}

@Composable
private fun ScheduleWindowRow(window: WindowUiModel, index: Int, count: Int, viewModel: AlarmEditorViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dia inteiro", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = window.isAllDay,
                onCheckedChange = { viewModel.onUpdateWindow(index, window.startTime, window.endTime, it) }
            )
            if (count > 1) IconButton(onClick = { viewModel.onRemoveWindow(index) }) {
                Icon(Icons.Default.Delete, "Remover horário", tint = MaterialTheme.colorScheme.error)
            }
        }
        if (!window.isAllDay) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeButton("Início", window.startTime, Modifier.weight(1f)) {
                    TimePickerDialog(context, { _, hour, minute ->
                        viewModel.onUpdateWindow(index, LocalTime.of(hour, minute), window.endTime, false)
                    }, window.startTime.hour, window.startTime.minute, true).show()
                }
                TimeButton("Fim", window.endTime, Modifier.weight(1f)) {
                    TimePickerDialog(context, { _, hour, minute ->
                        viewModel.onUpdateWindow(index, window.startTime, LocalTime.of(hour, minute), false)
                    }, window.endTime.hour, window.endTime.minute, true).show()
                }
            }
        }
    }
}

@Composable
private fun TimeButton(label: String, time: LocalTime, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
        Icon(Icons.Default.Schedule, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("$label: ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}", maxLines = 1)
    }
}

private fun LazyListScope.reviewStep(
    state: AlarmEditorUiState,
    onDestination: () -> Unit,
    onSchedule: () -> Unit,
    onSound: () -> Unit,
    onMonitoring: () -> Unit
) {
    item {
        Text(state.name.ifBlank { state.destinationName }, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
    }
    item {
        InteractiveMapComponent(state.coordinates, state.radiusMeters, state.destinationName, {}, Modifier.height(150.dp))
    }
    item { ReviewCard("Destino", state.destinationName, "Ponto selecionado", Icons.Default.Place, onDestination) }
    item { ReviewCard("Avisar a", formatDistance(state.radiusMeters), "Distância em linha reta até o ponto escolhido", Icons.Default.Radar, onSchedule) }
    item { ReviewCard("Quando", shortSchedule(state), state.summaryText, Icons.Default.Schedule, onSchedule) }
    item { ReviewCard("Som e vibração", state.soundSelection?.title ?: "Padrão do app", if (state.isVibrationEnabled) "Vibração ligada" else "Sem vibração", Icons.Default.VolumeUp, onSound) }
    item {
        ReviewCard(
            "Monitoramento",
            when (state.monitoringProfile) {
                MonitoringProfile.AUTOMATIC -> "Automático"
                MonitoringProfile.HIGH_PRECISION -> "Mais precisão"
                MonitoringProfile.BATTERY_SAVER -> "Economia"
                MonitoringProfile.CUSTOM -> "Personalizado"
                null -> "Padrão do app"
            },
            "Perfil de localização",
            Icons.Default.Settings,
            onMonitoring
        )
    }
}

@Composable
private fun ReviewCard(label: String, value: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    DestinoCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmSoundScreen(
    state: AlarmEditorUiState,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onUseDefault: (Boolean) -> Unit,
    onChooseRingtone: () -> Unit,
    onVibration: (Boolean) -> Unit,
    onVibrationPattern: (VibrationPattern?) -> Unit,
    onAudioOutput: (AudioOutputPolicy?) -> Unit
) {
    val useDefault = state.soundSelection == null && state.vibrationPattern == null && state.audioOutputPolicy == null
    Scaffold(
        topBar = { DestinoTopBar("Som deste alarme", onBack) },
        bottomBar = { Surface { Column(Modifier.padding(20.dp)) { PrimaryAction("Aplicar", onClick = onApply) } } }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { SettingSwitch("Usar padrão do app", "Herdar as configurações gerais", Icons.Default.AutoAwesome, useDefault, onUseDefault) }
            item {
                DestinoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Default.MusicNote)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.soundSelection?.title ?: "Toque padrão do app", style = MaterialTheme.typography.titleMedium)
                            Text("Toque do alarme", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = onChooseRingtone, shape = RoundedCornerShape(20.dp)) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Text("Ouvir")
                        }
                    }
                }
            }
            item {
                DestinoCard {
                    SettingSwitch("Vibração", "Vibrar junto com o aviso", Icons.Default.Vibration, state.isVibrationEnabled, onVibration)
                    if (state.isVibrationEnabled) {
                        Text("Padrão", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                VibrationPattern.SHORT to "Curta",
                                VibrationPattern.STRONG to "Forte",
                                VibrationPattern.INTERMITTENT to "Pulsante"
                            ).forEach { (pattern, label) ->
                                FilterChip(
                                    selected = state.vibrationPattern == pattern,
                                    onClick = { onVibrationPattern(pattern) },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text("Saída de áudio", style = MaterialTheme.typography.titleLarge)
            }
            items(
                listOf(
                    null to ("Padrão do Android" to "Respeita a saída escolhida pelo sistema"),
                    AudioOutputPolicy.PREFER_HEADPHONES to ("Preferir fones" to "Toca nos fones quando estiverem conectados"),
                    AudioOutputPolicy.PREFER_SPEAKER to ("Preferir alto-falante" to "Prioriza o alto-falante do aparelho"),
                    AudioOutputPolicy.HEADPHONES_VIBRATE_ONLY to ("Com fones, só vibrar" to "Evita tocar áudio nos fones")
                )
            ) { (policy, text) ->
                RadioCard(text.first, text.second, Icons.Default.Headphones, state.audioOutputPolicy == policy) { onAudioOutput(policy) }
            }
        }
    }
}

@Composable
private fun AlarmMonitoringScreen(
    state: AlarmEditorUiState,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onProfile: (MonitoringProfile?) -> Unit
) {
    Scaffold(
        topBar = { DestinoTopBar("Monitoramento deste alarme", onBack) },
        bottomBar = { Surface { Column(Modifier.padding(20.dp)) { PrimaryAction("Aplicar", onClick = onApply) } } }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingSwitch(
                    "Usar padrão do app",
                    "Herdar o perfil definido em Ajustes",
                    Icons.Default.AutoAwesome,
                    state.monitoringProfile == null
                ) { enabled -> onProfile(if (enabled) null else MonitoringProfile.AUTOMATIC) }
            }
            item { RadioCard("Automático", "Equilibra precisão e bateria", Icons.Default.Speed, state.monitoringProfile == MonitoringProfile.AUTOMATIC) { onProfile(MonitoringProfile.AUTOMATIC) } }
            item { RadioCard("Mais precisão", "Faz leituras mais frequentes durante o trajeto", Icons.Default.GpsFixed, state.monitoringProfile == MonitoringProfile.HIGH_PRECISION) { onProfile(MonitoringProfile.HIGH_PRECISION) } }
            item { RadioCard("Economia de bateria", "Reduz leituras quando você está distante", Icons.Default.BatterySaver, state.monitoringProfile == MonitoringProfile.BATTERY_SAVER) { onProfile(MonitoringProfile.BATTERY_SAVER) } }
            item { InfoCard("A frequência real também depende do aparelho, do sinal de GPS e das políticas de bateria do Android.") }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    DestinoCard(tinted = checked) {
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

@Composable
private fun RadioCard(title: String, subtitle: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
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

private fun canContinue(step: Int, state: AlarmEditorUiState): Boolean = when (step) {
    0 -> state.coordinates != null
    1 -> (!state.isCustomRadius || state.customRadiusInput.replace(',', '.').toDoubleOrNull()?.let { it in 100.0..20000.0 } == true) &&
        (state.modality != AlarmModality.RECURRING || state.selectedDays.isNotEmpty()) &&
        (state.modality == AlarmModality.NOW || state.windows.none { !it.isAllDay && it.startTime == it.endTime })
    else -> true
}

private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

private fun dayLetter(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "S"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "Q"
    DayOfWeek.THURSDAY -> "Q"
    DayOfWeek.FRIDAY -> "S"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "D"
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format("%.0f km", meters / 1000) else "${meters.toInt()} m"

private fun shortSchedule(state: AlarmEditorUiState): String = when (state.modality) {
    AlarmModality.NOW -> "Agora"
    AlarmModality.ONE_DATE -> state.specificDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    AlarmModality.RECURRING -> when {
        state.selectedDays == weekdays -> "Seg–sex"
        state.selectedDays.size == 7 -> "Todos os dias"
        state.selectedDays == weekend -> "Fim de semana"
        else -> state.selectedDays.sortedBy { it.value }.joinToString(", ") { dayLetter(it) }
    }
}
