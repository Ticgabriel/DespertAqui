package com.destino.app.feature.journey

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destino.app.core.model.AlertEventType
import com.destino.app.core.model.QualityState
import com.destino.app.core.model.SessionState
import com.destino.app.feature.setup.map.InteractiveMapComponent
import com.destino.app.ui.components.*

@Composable
fun JourneyScreen(
    viewModel: JourneyViewModel,
    onNewJourneyRequested: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRestMode: () -> Unit = {}
) {
    val state by viewModel.operationalState.collectAsStateWithLifecycle()
    val feedback by viewModel.commandFeedback.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    val destination = state.activeDestination

    Scaffold(modifier, topBar = { DestinoTopBar("Acompanhamento", onNewJourneyRequested) }) { padding ->
        if (state.activeSession == null || destination == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(20.dp), contentAlignment = Alignment.Center) {
                EmptyDestination("Viagem encerrada", "Escolha seu próximo destino para iniciar outro acompanhamento.", Icons.Default.CheckCircle, "Voltar ao início", onNewJourneyRequested)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Text("Viagem em andamento", style = MaterialTheme.typography.headlineLarge) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(statusColor(state.qualityState), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(statusText(state.qualityState), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    InteractiveMapComponent(
                        destination.coordinates,
                        state.activeAlarm?.radiusMeters ?: 500.0,
                        destination.name,
                        {},
                        Modifier.height(240.dp)
                    )
                }
                item {
                    val distance = state.currentDistanceMeters
                    Text(
                        buildString {
                            append(distance?.let(::formatDistance) ?: "Calculando…")
                            append(" até ")
                            append(destination.name)
                        },
                        fontSize = 42.sp,
                        lineHeight = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("Distância em linha reta", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactJourneyCard(
                            Icons.Default.Notifications,
                            "Aviso a",
                            "${state.activeAlarm?.radiusMeters?.toInt() ?: 500} m",
                            Modifier.weight(1f)
                        )
                        CompactJourneyCard(
                            Icons.Default.VolumeUp,
                            "Som e vibração",
                            if (state.activeAlarm?.isVibrationEnabled == false) "Som ligado" else "Ligados",
                            Modifier.weight(1f)
                        )
                    }
                }
                state.waitingExitMessage?.let { message ->
                    item {
                        InfoCard("$message O alarme será armado depois que você sair da área.", attention = true)
                    }
                }
                if (state.activeAlertEventId != null) {
                    item {
                        DestinoCard(attention = true) {
                            Text(
                                if (state.activeAlertEventType == AlertEventType.PRECAUTION) "Aviso de localização" else "Seu destino está perto",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                if (state.activeAlertEventType == AlertEventType.PRECAUTION) "O sinal está instável. Confira sua localização." else "Prepare-se para descer."
                            )
                            PrimaryAction("Parar alarme", onClick = viewModel::acknowledgeAlarm)
                        }
                    }
                }
                if (state.sessionState == SessionState.INTERRUPTED) {
                    item { PrimaryAction("Retomar acompanhamento", icon = Icons.Default.PlayArrow, onClick = viewModel::resumeJourney) }
                } else {
                    item { PrimaryAction("Modo descanso", icon = Icons.Default.DarkMode, onClick = onOpenRestMode) }
                }
                feedback?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                item {
                    OutlinedButton(
                        onClick = { confirmStop = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Encerrar viagem")
                    }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Encerrar esta viagem?") },
            text = { Text("Você não receberá o aviso de chegada desta viagem. Suas rotinas serão mantidas.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; viewModel.stopJourney() }) { Text("Encerrar", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continuar viagem") } }
        )
    }
}

@Composable
private fun CompactJourneyCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, modifier: Modifier) {
    DestinoCard(modifier = modifier) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

private fun statusText(quality: QualityState): String = when (quality) {
    QualityState.GOOD -> "Localização atualizada"
    QualityState.ACQUIRING -> "Buscando localização"
    QualityState.DEGRADED -> "Sinal de GPS instável"
    QualityState.BLOCKED -> "Localização indisponível"
}

private fun statusColor(quality: QualityState) = when (quality) {
    QualityState.GOOD -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
    QualityState.ACQUIRING -> androidx.compose.ui.graphics.Color(0xFFE39A45)
    else -> androidx.compose.ui.graphics.Color(0xFFA33D28)
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format("%.1f km", meters / 1000) else "${meters.toInt()} m"
