package com.destino.app.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.destino.app.core.model.AlertEventType
import com.destino.app.core.model.Favorite
import com.destino.app.ui.components.*

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onCreateAlarm: () -> Unit,
    onNavigateToAlarms: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onOpenRestMode: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPermissions: () -> Unit = {},
    onFavoriteChosen: (Favorite) -> Unit = { onNavigateToFavorites() }
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { snackbar.showSnackbar(it); viewModel.clearUserMessage() }
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item {
                DestinoBrand()
                Spacer(Modifier.height(24.dp))
                Text("Para onde vamos?", style = MaterialTheme.typography.headlineLarge)
            }
            item {
                Surface(
                    Modifier.fillMaxWidth().clickable(onClick = onCreateAlarm),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Buscar destino", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (state.operationalState.isAlertActive) {
                item {
                    DestinoCard(attention = true) {
                        Text(
                            if (state.operationalState.activeAlertEventType == AlertEventType.PRECAUTION) "Aviso de localização" else "Seu destino está perto",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(state.operationalState.activeDestination?.name.orEmpty())
                        PrimaryAction("Parar alarme") {
                            state.operationalState.activeAlertEventId?.let(viewModel::acknowledgeActiveAlert)
                        }
                    }
                }
            }

            items(state.operationalState.activeOccurrences.values.toList(), key = { it.session.id }) { trip ->
                DestinoCard(tinted = true) {
                    DetailRow(
                        trip.destination.name,
                        trip.currentDistanceMeters?.let { formatDistance(it) + " em linha reta" } ?: "Calculando distância…",
                        Icons.Default.Navigation
                    )
                    PrimaryAction("Abrir acompanhamento") { onOpenRestMode(trip.session.id) }
                }
            }

            if (state.quickFavorites.isNotEmpty()) {
                item {
                    Text("Acessos rápidos", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.quickFavorites, key = { it.id }) { favorite ->
                            Surface(
                                Modifier.width(112.dp).clickable { onFavoriteChosen(favorite) },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    Modifier.padding(vertical = 18.dp, horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    IconTile(
                                        placeIcon(favorite.nickname),
                                        background = MaterialTheme.colorScheme.surface,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(favorite.nickname, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            if (state.nextActivations.isNotEmpty()) {
                item {
                    val next = state.nextActivations.first()
                    DestinoCard(tinted = true) {
                        Text("Próxima rotina", style = MaterialTheme.typography.titleMedium)
                        DestinoCard {
                            DetailRow(
                                next.alarmName,
                                next.destinationName + "\n" + next.nextActivationTimeText,
                                placeIcon(next.alarmName)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Notifications, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Avisar a ${formatDistance(next.radiusMeters)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            TextButton(onClick = onNavigateToAlarms, contentPadding = PaddingValues(0.dp)) {
                                Text("Ver rotina")
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            if (state.quickFavorites.isEmpty() && state.nextActivations.isEmpty() && state.operationalState.activeTravelsCount == 0) {
                item {
                    EmptyDestination(
                        "Seu primeiro destino",
                        "Escolha um lugar e receba um aviso ao se aproximar.",
                        Icons.Default.Place,
                        "Criar alarme",
                        onCreateAlarm
                    )
                }
            } else {
                item { PrimaryAction("Criar alarme", icon = Icons.Default.Add, onClick = onCreateAlarm) }
            }

            item {
                DestinoCard(attention = true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(
                            Icons.Default.PhoneAndroid,
                            background = MaterialTheme.colorScheme.surface,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Prepare seu aparelho", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onPermissions, contentPadding = PaddingValues(0.dp)) {
                                Text("Verificar funcionamento")
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format("%.1f km", meters / 1000) else "${meters.toInt()} m"
