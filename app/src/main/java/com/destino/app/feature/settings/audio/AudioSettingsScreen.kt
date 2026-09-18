package com.destino.app.feature.settings.audio

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destino.app.core.model.*
import com.destino.app.ui.components.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioSettingsScreen(viewModel: AudioSettingsViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val route by viewModel.routeState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= 33) result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (uri == null) viewModel.setSoundSelection(SoundSelection(SoundSourceType.SILENT, null, "Som desativado"))
            else viewModel.setSoundSelection(SoundSelection(SoundSourceType.SYSTEM_RINGTONE, uri.toString(), RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Toque do aparelho"))
        }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.setSoundSelection(SoundSelection(SoundSourceType.USER_DOCUMENT, uri.toString(), uri.lastPathSegment ?: "Arquivo próprio"))
        }
    }

    Scaffold(topBar = { DestinoTopBar("Som e vibração", onNavigateBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                SegmentedPills(
                    listOf(Icons.Default.VolumeUp to "Alerta", Icons.Default.Headphones to "Fones"),
                    tab,
                    { tab = it }
                )
            }
            if (tab == 0) {
                item {
                    DestinoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Default.MusicNote)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(state.soundSelection.title, style = MaterialTheme.typography.titleMedium)
                                Text("Som usado nos avisos de chegada", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledTonalButton(onClick = viewModel::togglePreview, shape = RoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Icon(if (state.isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (state.isPreviewPlaying) "Parar" else "Ouvir")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                    })
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.FormatListBulleted, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Toques do sistema")
                            }
                            OutlinedButton(onClick = { documentPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Arquivo próprio")
                            }
                        }
                    }
                }
                item {
                    AudioToggle(
                        "Som",
                        "Reproduzir som no alarme",
                        Icons.Default.VolumeUp,
                        state.soundSelection.type != SoundSourceType.SILENT
                    ) { enabled ->
                        viewModel.setSoundSelection(if (enabled) SoundSelection() else SoundSelection(SoundSourceType.SILENT, null, "Som desativado"))
                    }
                }
                item {
                    DestinoCard {
                        Text("Duração do alerta", style = MaterialTheme.typography.titleMedium)
                        Text("Por quanto tempo tocar", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "1 min", 3 to "3 min", 5 to "5 min", 0 to "Até parar").forEach { (minutes, label) ->
                                FilterChip(
                                    selected = state.alarmDurationMinutes == minutes,
                                    onClick = { viewModel.setAlarmDurationMinutes(minutes) },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                                )
                            }
                        }
                    }
                }
                item { AudioToggle("Som crescente", "Aumenta o volume gradualmente", Icons.Default.SignalCellularAlt, state.isGradualVolumeEnabled, viewModel::setGradualVolumeEnabled) }
                item { AudioToggle("Vibração", "Vibrar junto com o aviso", Icons.Default.Vibration, state.isVibrationEnabled, viewModel::setVibrationEnabled) }
                if (state.isVibrationEnabled) {
                    item {
                        DestinoCard {
                            Text("Padrão de vibração", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(VibrationPattern.SHORT to "Curta", VibrationPattern.STRONG to "Forte", VibrationPattern.INTERMITTENT to "Pulsante").forEach { (pattern, label) ->
                                    FilterChip(
                                        selected = state.vibrationPattern == pattern,
                                        onClick = { viewModel.setVibrationPattern(pattern) },
                                        label = { Text(label) },
                                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    DestinoCard(tinted = true) {
                        DetailRow(
                            if (route.isHeadphonesConnected) "Fones conectados" else "Nenhum fone conectado",
                            route.connectedDevices.joinToString().ifBlank { "Conecte seus fones para testar a saída" },
                            Icons.Default.Headphones
                        )
                    }
                }
                item { Text("Onde tocar", style = MaterialTheme.typography.titleLarge) }
                items(
                    listOf(
                        AudioOutputPolicy.SYSTEM_DEFAULT to ("Padrão do Android" to "Respeita a rota escolhida pelo sistema"),
                        AudioOutputPolicy.PREFER_HEADPHONES to ("Preferir fones conectados" to "Toca nos fones sempre que possível"),
                        AudioOutputPolicy.PREFER_SPEAKER to ("Preferir alto-falante" to "Prioriza o alto-falante do aparelho"),
                        AudioOutputPolicy.HEADPHONES_VIBRATE_ONLY to ("Com fones, só vibrar e notificar" to "Não reproduz o alarme nos fones")
                    )
                ) { (policy, text) ->
                    AudioRadio(text.first, text.second, state.audioOutputPolicy == policy) { viewModel.setAudioOutputPolicy(policy) }
                }
                if (state.audioOutputPolicy == AudioOutputPolicy.PREFER_HEADPHONES) {
                    item { Text("Se os fones desconectarem", style = MaterialTheme.typography.titleLarge) }
                    items(
                        listOf(
                            HeadphoneDisconnectBehavior.CONTINUE_WITH_SPEAKER to ("Continuar no alto-falante" to "Mantém o aviso sonoro no aparelho"),
                            HeadphoneDisconnectBehavior.STOP_AUDIO_KEEP_VIBRATION to ("Silenciar e manter vibração" to "Interrompe apenas o áudio")
                        )
                    ) { (behavior, text) ->
                        AudioRadio(text.first, text.second, state.disconnectBehavior == behavior) { viewModel.setDisconnectBehavior(behavior) }
                    }
                }
                item { InfoCard("A saída de som também depende das políticas do Android e do aparelho. Teste antes da viagem.") }
            }
            item { Text("Alterações salvas automaticamente", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun AudioToggle(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
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

@Composable
private fun AudioRadio(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    DestinoCard(modifier = Modifier.clickable(onClick = onClick), tinted = selected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
