package com.destino.app.feature.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.destino.app.R
import com.destino.app.core.model.PlaceSuggestion
import com.destino.app.feature.setup.map.InteractiveMapComponent
import com.destino.app.ui.theme.DestinoAttention
import com.destino.app.ui.theme.DestinoOnPrimary
import com.destino.app.ui.theme.DestinoOnSurface
import com.destino.app.ui.theme.DestinoOutline
import com.destino.app.ui.theme.DestinoPrimary
import com.destino.app.ui.theme.DestinoSurfaceVariant

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onJourneyStarted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cabeçalho
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.setup_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.setup_subtitle),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                )
            }

            // Mensagem de erro de validação
            uiState.validationError?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(14.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Feedback de teste
            uiState.feedbackMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = DestinoAttention
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DestinoOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        color = DestinoOnSurface,
                        modifier = Modifier.padding(14.dp),
                        fontSize = 14.sp
                    )
                }
            }

            // 1. Barra de Busca de Endereço / Autocomplete
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    placeholder = { Text(stringResource(R.string.search_destination_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    trailingIcon = {
                        if (uiState.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (uiState.searchQuery.isNotBlank()) {
                            Text(
                                text = "✕",
                                modifier = Modifier
                                    .clickable { viewModel.clearSearch() }
                                    .padding(8.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                // Lista de sugestões de lugares
                AnimatedVisibility(
                    visible = uiState.searchSuggestions.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column {
                            uiState.searchSuggestions.forEachIndexed { index, suggestion ->
                                SuggestionItem(
                                    suggestion = suggestion,
                                    onClick = { viewModel.onSuggestionSelected(suggestion) }
                                )
                                if (index < uiState.searchSuggestions.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }

            // 2. Mapa Interativo com Marcador e Círculo do Raio
            Text(
                text = stringResource(R.string.map_hint_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            InteractiveMapComponent(
                selectedCoordinates = uiState.selectedCoordinates,
                radiusMeters = uiState.effectiveRadiusMeters ?: 500.0,
                destinationName = uiState.destinationName,
                onMapClick = viewModel::onMapClick,
                modifier = Modifier.fillMaxWidth()
            )

            // 3. Nome do Destino Selecionado
            OutlinedTextField(
                value = uiState.destinationName,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.destination_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                singleLine = true
            )

            // 4. Seleção de Raio (Presets com feedback visual imediato no mapa)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.radius_label),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(100.0 to "100 m", 300.0 to "300 m", 500.0 to "500 m", 1000.0 to "1 km").forEach { (meters, label) ->
                        val isSelected = !uiState.isCustomRadius && uiState.selectedPresetRadiusMeters == meters
                        RadiusPresetChip(
                            label = label,
                            isSelected = isSelected,
                            onClick = { viewModel.onPresetRadiusSelected(meters) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Raio customizado com seletor explícito de unidade
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.customRadiusInput,
                        onValueChange = viewModel::onCustomRadiusChange,
                        label = { Text(stringResource(R.string.custom_radius_label)) },
                        placeholder = { Text("Ex: 750 ou 1,5 km") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        singleLine = true
                    )

                    Box(
                        modifier = Modifier
                            .height(54.dp)
                            .background(
                                color = if (uiState.customRadiusUnit == RadiusUnit.METERS) DestinoPrimary else DestinoSurfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (uiState.customRadiusUnit == RadiusUnit.METERS) DestinoPrimary else DestinoOutline,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.onCustomUnitChange(RadiusUnit.METERS) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "m",
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.customRadiusUnit == RadiusUnit.METERS) DestinoOnPrimary else DestinoOnSurface
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(54.dp)
                            .background(
                                color = if (uiState.customRadiusUnit == RadiusUnit.KILOMETERS) DestinoPrimary else DestinoSurfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (uiState.customRadiusUnit == RadiusUnit.KILOMETERS) DestinoPrimary else DestinoOutline,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.onCustomUnitChange(RadiusUnit.KILOMETERS) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "km",
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.customRadiusUnit == RadiusUnit.KILOMETERS) DestinoOnPrimary else DestinoOnSurface
                        )
                    }
                }
            }

            // 5. Opção Secundária: Inserir Coordenadas Manualmente (Colapsável)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleManualCoordinates() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (uiState.isManualCoordinatesExpanded) {
                                stringResource(R.string.manual_coordinates_hide)
                            } else {
                                stringResource(R.string.manual_coordinates_toggle)
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (uiState.isManualCoordinatesExpanded) "▲" else "▼",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    AnimatedVisibility(visible = uiState.isManualCoordinatesExpanded) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = uiState.latitudeInput,
                                    onValueChange = viewModel::onLatitudeChange,
                                    label = { Text(stringResource(R.string.latitude_label)) },
                                    placeholder = { Text("-23.5505") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = uiState.longitudeInput,
                                    onValueChange = viewModel::onLongitudeChange,
                                    label = { Text(stringResource(R.string.longitude_label)) },
                                    placeholder = { Text("-46.6333") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 6. Botão Testar Alerta
            OutlinedButton(
                onClick = viewModel::testAlert,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.test_alert_button),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 7. Botão Iniciar Viagem
            Button(
                onClick = { viewModel.startJourney(onJourneyStarted) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !uiState.isStarting
            ) {
                Text(
                    text = if (uiState.isStarting) "Iniciando..." else stringResource(R.string.start_journey_button),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SuggestionItem(
    suggestion: PlaceSuggestion,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = suggestion.primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (suggestion.secondaryText.isNotBlank()) {
            Text(
                text = suggestion.secondaryText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RadiusPresetChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                color = if (isSelected) DestinoPrimary else DestinoSurfaceVariant
            )
            .border(
                width = 1.dp,
                color = if (isSelected) DestinoPrimary else DestinoOutline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) DestinoOnPrimary else DestinoOnSurface,
            fontSize = 14.sp
        )
    }
}
