package com.destino.app.feature.journey

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.destino.app.core.model.QualityState
import com.destino.app.ui.theme.*

@Composable
fun RestModeScreen(
    viewModel: JourneyViewModel,
    onExitRestMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.operationalState.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }
    val destination = state.activeDestination?.name ?: "Destino"
    val radius = state.activeAlarm?.radiusMeters?.toInt() ?: 500
    val distance = state.currentDistanceMeters?.let {
        if (it >= 1000) String.format("%.1f km", it / 1000) else "${it.toInt()} m"
    } ?: "—"

    LaunchedEffect(state.activeSession) {
        if (state.activeSession == null) onExitRestMode()
    }

    Box(
        modifier.fillMaxSize().background(DestinoRestBackground)
            .statusBarsPadding().navigationBarsPadding().padding(24.dp)
    ) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DarkMode, null, tint = DestinoRestAccent)
            Spacer(Modifier.width(8.dp))
            Text("Modo descanso", color = DestinoRestText, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            FilledIconButton(
                onClick = onExitRestMode,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = DestinoRestSurface, contentColor = DestinoRestText)
            ) { Icon(Icons.Default.Close, "Sair do modo descanso") }
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(118.dp).background(DestinoRestAccent.copy(alpha = .08f), CircleShape))
                Box(Modifier.size(88.dp).background(DestinoRestAccent.copy(alpha = .15f), CircleShape))
                Box(Modifier.size(58.dp).background(DestinoRestSurface, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Place, null, Modifier.size(30.dp), tint = DestinoRestAccent)
                }
            }
            Text(destination, color = DestinoRestText, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(distance, color = DestinoRestAccent, fontSize = 64.sp, lineHeight = 70.sp, fontWeight = FontWeight.Bold)
            Text("em linha reta até o destino", color = DestinoRestText.copy(alpha = .68f))
            Surface(shape = RoundedCornerShape(24.dp), color = DestinoRestSurface) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(18.dp), tint = DestinoRestAccent)
                    Spacer(Modifier.width(8.dp))
                    Text("Avisar a $radius m", color = DestinoRestText)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (state.qualityState == QualityState.GOOD) DestinoRestAccent else DestinoAttention, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.qualityState == QualityState.GOOD) "Localização atualizada" else state.statusMessage,
                    color = DestinoRestText.copy(alpha = .8f)
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.isAlertActive) {
                Button(
                    onClick = viewModel::acknowledgeAlarm,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DestinoAttention, contentColor = DestinoRestBackground),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.NotificationsActive, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Parar alarme", style = MaterialTheme.typography.titleMedium)
                }
            }
            Button(
                onClick = onExitRestMode,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DestinoRestText, contentColor = DestinoRestBackground),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Sair do modo descanso", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = { confirmStop = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DestinoRestText),
                border = BorderStroke(1.dp, DestinoRestAccent.copy(alpha = .55f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Encerrar viagem")
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Encerrar viagem?") },
            text = { Text("O aviso de chegada desta viagem será desativado.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; viewModel.stopJourney() }) { Text("Encerrar") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continuar viagem") } }
        )
    }
}

