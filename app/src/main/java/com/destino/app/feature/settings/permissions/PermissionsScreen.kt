package com.destino.app.feature.settings.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destino.app.platform.runtime.*
import com.destino.app.ui.components.*
import com.destino.app.ui.theme.DestinoAttention

@Composable
fun PermissionsScreen(viewModel: PermissionViewModel, onNavigateBack: () -> Unit) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var batteryExpanded by rememberSaveableCompat { mutableStateOf(false) }
    var servicesExpanded by rememberSaveableCompat { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    fun perform(item: ChecklistItem) {
        when (item.id) {
            "loc_fine" -> permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            "loc_bg" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PermissionActionResolver.executeAction(context, ChecklistActionType.OPEN_APP_SETTINGS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            "notif" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else PermissionActionResolver.executeAction(context, item.actionType)
            else -> PermissionActionResolver.executeAction(context, item.actionType)
        }
    }

    val batteryIds = setOf("battery", "volume", "dnd")
    val serviceIds = setOf("gps", "play_services")
    val primaryItems = report.items.filterNot { it.id in batteryIds || it.id in serviceIds }

    Scaffold(topBar = { DestinoTopBar("Funcionamento e permissões", onNavigateBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CapabilityCard(
                        "Viagem manual",
                        report.canStartManualJourney,
                        Icons.Default.DirectionsCar,
                        Modifier.weight(1f)
                    )
                    CapabilityCard(
                        "Rotinas",
                        report.canScheduleAutomatic,
                        Icons.Default.Schedule,
                        Modifier.weight(1f)
                    )
                }
            }
            item { Text("Permissões e condições do aparelho", style = MaterialTheme.typography.titleLarge) }
            items(primaryItems.size) { index ->
                val item = primaryItems[index]
                ChecklistCard(item, { perform(item) })
            }
            item {
                AccordionCard(
                    "Bateria e som",
                    Icons.Default.BatteryChargingFull,
                    batteryExpanded,
                    { batteryExpanded = !batteryExpanded },
                    report.items.filter { it.id in batteryIds },
                    ::perform
                )
            }
            item {
                AccordionCard(
                    "Serviços do aparelho",
                    Icons.Default.PhoneAndroid,
                    servicesExpanded,
                    { servicesExpanded = !servicesExpanded },
                    report.items.filter { it.id in serviceIds },
                    ::perform
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(title: String, ready: Boolean, icon: ImageVector, modifier: Modifier) {
    DestinoCard(modifier = modifier, tinted = ready, attention = !ready) {
        IconTile(icon, background = MaterialTheme.colorScheme.surface)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
                null,
                Modifier.size(18.dp),
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(6.dp))
            Text(if (ready) "Disponível" else "Precisa de ajuste", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChecklistCard(item: ChecklistItem, onAction: () -> Unit) {
    DestinoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(statusIcon(item.status))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(statusLabel(item.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.actionType != ChecklistActionType.NONE) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = DestinoAttention, contentColor = MaterialTheme.colorScheme.onSurface),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                ) { Text(item.actionLabel ?: if (item.status == ChecklistStatus.NEEDS_ATTENTION) "Permitir" else "Ajustar") }
            }
        }
    }
}

@Composable
private fun AccordionCard(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    items: List<ChecklistItem>,
    onAction: (ChecklistItem) -> Unit
) {
    DestinoCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) {
            HorizontalDivider()
            items.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(statusIcon(item.status), null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.actionType != ChecklistActionType.NONE) TextButton(onClick = { onAction(item) }) { Text(item.actionLabel ?: "Ajustar") }
                }
            }
        }
    }
}

private fun statusIcon(status: ChecklistStatus): ImageVector = when (status) {
    ChecklistStatus.PERMITTED -> Icons.Default.CheckCircle
    ChecklistStatus.NEEDS_ATTENTION -> Icons.Default.Warning
    ChecklistStatus.OPTIONAL_ACTION -> Icons.Default.Info
    ChecklistStatus.UNAVAILABLE -> Icons.Default.Block
}

private fun statusLabel(status: ChecklistStatus): String = when (status) {
    ChecklistStatus.PERMITTED -> "Permitido"
    ChecklistStatus.NEEDS_ATTENTION -> "Precisa de ajuste"
    ChecklistStatus.OPTIONAL_ACTION -> "Recomendado"
    ChecklistStatus.UNAVAILABLE -> "Indisponível"
}

@Composable
private fun <T> rememberSaveableCompat(init: () -> MutableState<T>): MutableState<T> =
    androidx.compose.runtime.saveable.rememberSaveable { init() }
