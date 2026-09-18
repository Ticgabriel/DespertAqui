package com.destino.app.feature.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.destino.app.core.model.AlertEventType
import com.destino.app.ui.theme.DestinoAttention

@Composable
fun AlertDisplayScreen(
    viewModel: JourneyViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.operationalState.collectAsState()
    val feedback by viewModel.commandFeedback.collectAsState()
    var acknowledging by remember { mutableStateOf(false) }
    val precaution = state.activeAlertEventType == AlertEventType.PRECAUTION
    val distance = state.currentDistanceMeters?.let {
        if (it >= 1000) String.format("%.1f km", it / 1000) else "${it.toInt()} m"
    } ?: "—"
    val radius = state.activeAlarm?.radiusMeters?.toInt() ?: 500

    LaunchedEffect(state.activeAlertEventId, acknowledging) {
        if (acknowledging && state.activeAlertEventId == null) onDismiss()
    }

    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(.6f))
        RingingBell()
        Spacer(Modifier.height(28.dp))
        Text(
            if (precaution) "Aviso de localização" else "Seu destino está perto",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(distance, fontSize = 58.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(state.activeDestination?.name ?: "Seu destino", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            if (precaution) "Confira sua localização. Este aviso não confirma a chegada." else "Prepare-se para descer",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Aviso configurado a $radius m")
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                if (state.activeAlertEventId == null) onDismiss()
                else { acknowledging = true; viewModel.acknowledgeAlarm() }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
            shape = RoundedCornerShape(15.dp)
        ) {
            Text(if (state.activeAlertEventId == null) "Voltar" else "Parar alarme", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            feedback ?: "Encerra o alerta desta viagem.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun RingingBell() {
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(154.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(116.dp), shape = CircleShape, color = DestinoAttention) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.NotificationsActive, null, Modifier.size(58.dp), tint = color)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawArc(color, 210f, 120f, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            drawArc(color.copy(alpha = .42f), 210f, 120f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round), topLeft = androidx.compose.ui.geometry.Offset(12.dp.toPx(), 12.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - 24.dp.toPx(), size.height - 24.dp.toPx()))
        }
    }
}
