package com.destino.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.Favorite
import com.destino.app.core.model.HistoryRecord
import com.destino.app.core.model.PlaceDetails
import com.destino.app.core.model.PlaceSuggestion
import com.destino.app.feature.setup.map.InteractiveMapComponent
import com.destino.app.ui.components.*
import com.destino.app.ui.theme.DestinoAttention
import java.time.Instant
import java.time.ZoneId
import androidx.activity.compose.BackHandler
import java.time.format.DateTimeFormatter

@Composable
fun LocationLibraryScreen(
    viewModel: LocationLibraryViewModel,
    onCreateRoutineWithDestination: (destinationName: String, lat: Double, lon: Double, radius: Double) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToHome: () -> Unit = {}
) {
    BackHandler(onBack = onNavigateToHome)

    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var removal by remember { mutableStateOf<Favorite?>(null) }

    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { snackbar.showSnackbar(it); viewModel.clearUserMessage() }
    }

    Scaffold(
        modifier,
        topBar = {
            DestinoTopBar("Seus locais", action = {
                FilledIconButton(
                    onClick = viewModel::openAddFavoriteDialog,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Icon(Icons.Default.Add, "Adicionar lugar") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                state.searchQuery,
                viewModel::onSearchQueryChange,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Pesquisar lugares") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(12.dp))
            SegmentedPills(
                listOf(Icons.Default.Favorite to "Favoritos", Icons.Default.History to "Recentes"),
                selectedTab,
                { selectedTab = it },
                Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(12.dp))

            if (selectedTab == 0) {
                if (state.favorites.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        EmptyDestination("Seus lugares, sempre à mão", "Salve Casa, Trabalho ou qualquer lugar importante.", Icons.Default.Favorite, "Adicionar lugar", viewModel::openAddFavoriteDialog)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.favorites, key = { it.id }) { favorite ->
                            FavoriteCard(
                                favorite,
                                onStart = { viewModel.startJourneyImmediately(favorite.nickname, favorite.coordinates, favorite.suggestedRadiusMeters) },
                                onRoutine = {
                                    onCreateRoutineWithDestination(
                                        favorite.nickname,
                                        favorite.coordinates.latitude,
                                        favorite.coordinates.longitude,
                                        favorite.suggestedRadiusMeters
                                    )
                                },
                                onEdit = { viewModel.openEditFavoriteDialog(favorite) },
                                onDelete = { removal = favorite }
                            )
                        }
                    }
                }
            } else {
                if (state.recentRecords.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        EmptyDestination("Nenhum destino recente", "Suas viagens concluídas aparecerão aqui.", Icons.Default.History, "Ver favoritos") {
                            selectedTab = 0
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.recentRecords, key = { it.id }) { record ->
                            RecentCard(record, { viewModel.favoriteRecent(record) }, { viewModel.startRecent(record) })
                        }
                        item { InfoCard("Reutilize um destino da sua viagem para começar novamente ou salvá-lo como favorito.") }
                    }
                }
            }
        }
    }

    if (state.isEditFavoriteDialogVisible) {
        FavoriteEditor(
            favorite = state.editingFavorite,
            placeQuery = state.favoritePlaceQuery,
            suggestions = state.favoritePlaceSuggestions,
            isSearching = state.isFavoritePlaceSearching,
            selectedPlace = state.favoritePlaceSelection,
            searchError = state.favoritePlaceSearchError,
            onPlaceQueryChange = viewModel::onFavoritePlaceQueryChange,
            onSelectPlace = viewModel::onSelectFavoritePlaceSuggestion,
            onMapCoordinatesSelected = viewModel::onFavoriteMapCoordinatesSelected,
            onDismiss = viewModel::dismissFavoriteDialog
        ) { nick, destination, coordinates, radius, icon ->
            viewModel.saveFavorite(nick, destination, coordinates, radius, icon)
        }
    }

    removal?.let { favorite ->
        ConfirmRemoval(
            "Excluir favorito?",
            "O local será removido dos seus favoritos.",
            { removal = null },
            { viewModel.deleteFavorite(favorite.id); removal = null }
        )
    }
}

@Composable
private fun FavoriteCard(
    favorite: Favorite,
    onStart: () -> Unit,
    onRoutine: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    DestinoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(
                semanticFavoriteIcon(favorite),
                background = if (favorite.iconName.equals("work", true)) DestinoAttention else MaterialTheme.colorScheme.primaryContainer
            )
            Column(Modifier.weight(1f)) {
                Text(favorite.nickname, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${favorite.destinationName} • ${favorite.suggestedRadiusMeters.toInt()} m",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opções") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Editar") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                    DropdownMenuItem({ Text("Excluir", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Iniciar")
            }
            OutlinedButton(onClick = onRoutine, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Criar rotina")
            }
        }
    }
}

@Composable
private fun RecentCard(record: HistoryRecord, onFavorite: () -> Unit, onStart: () -> Unit) {
    DestinoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(placeIcon(record.destinationName))
            Column(Modifier.weight(1f)) {
                Text(record.destinationName, style = MaterialTheme.typography.titleMedium)
                val time = Instant.ofEpochMilli(record.timestampEpochMs).atZone(ZoneId.systemDefault())
                Text(
                    "${relativeDate(time.toLocalDate())} • ${record.outcomeDescription} • ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalIconButton(onClick = onFavorite) { Icon(Icons.Default.FavoriteBorder, "Favoritar") }
            FilledIconButton(onClick = onStart) { Icon(Icons.Default.PlayArrow, "Iniciar viagem") }
        }
    }
}

@Composable
private fun FavoriteEditor(
    favorite: Favorite?,
    placeQuery: String,
    suggestions: List<PlaceSuggestion>,
    isSearching: Boolean,
    selectedPlace: PlaceDetails?,
    searchError: String?,
    onPlaceQueryChange: (String) -> Unit,
    onSelectPlace: (PlaceSuggestion) -> Unit,
    onMapCoordinatesSelected: (Coordinates) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, Coordinates, Double, String) -> Unit
) {
    var nickname by rememberSaveable { mutableStateOf(favorite?.nickname.orEmpty()) }
    var latitude by rememberSaveable { mutableStateOf(favorite?.coordinates?.latitude) }
    var longitude by rememberSaveable { mutableStateOf(favorite?.coordinates?.longitude) }
    var radiusText by rememberSaveable { mutableStateOf(favorite?.suggestedRadiusMeters?.toInt()?.toString() ?: "500") }
    var iconName by rememberSaveable { mutableStateOf(favorite?.iconName ?: "place") }
    val coordinates = latitude?.let { lat -> longitude?.let { Coordinates(lat, it) } }
    val radius = radiusText.replace(',', '.').toDoubleOrNull()?.takeIf { it in 100.0..20000.0 }

    LaunchedEffect(selectedPlace) {
        selectedPlace?.let {
            latitude = it.coordinates.latitude
            longitude = it.coordinates.longitude
        }
    }

    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = { DestinoTopBar(if (favorite == null) "Adicionar favorito" else "Editar favorito", onDismiss) },
            bottomBar = {
                Surface {
                    Column(Modifier.padding(20.dp)) {
                        PrimaryAction("Salvar favorito", coordinates != null && radius != null && nickname.isNotBlank() && placeQuery.isNotBlank()) {
                            onSave(nickname.trim(), placeQuery.trim(), coordinates!!, radius!!, iconName)
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                OutlinedTextField(nickname, { nickname = it }, Modifier.fillMaxWidth(), label = { Text("Nome do favorito") }, placeholder = { Text("Casa, Trabalho…") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Text("Ícone", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("home" to Icons.Default.Home, "work" to Icons.Default.Work, "school" to Icons.Default.School, "place" to Icons.Default.Place).forEach { (name, icon) ->
                        FilterChip(
                            selected = iconName == name,
                            onClick = { iconName = name },
                            label = { Icon(icon, name, Modifier.size(22.dp)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)
                        )
                    }
                }
                OutlinedTextField(
                    value = placeQuery,
                    onValueChange = onPlaceQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (placeQuery.isNotBlank()) {
                            IconButton(onClick = { onPlaceQueryChange("") }) {
                                Icon(Icons.Default.Close, "Limpar busca")
                            }
                        }
                    },
                    label = { Text("Nome ou endereço do local") },
                    placeholder = { Text("Busque um endereço ou estabelecimento") },
                    supportingText = {
                        Text(searchError ?: "Digite pelo menos 3 caracteres e escolha uma sugestão")
                    },
                    isError = searchError != null,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                if (suggestions.isNotEmpty()) {
                    DestinoCard {
                        suggestions.forEachIndexed { index, suggestion ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPlace(suggestion) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(suggestion.primaryText, style = MaterialTheme.typography.titleMedium)
                                    if (suggestion.secondaryText.isNotBlank()) {
                                        Text(
                                            suggestion.secondaryText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                            if (index < suggestions.lastIndex) HorizontalDivider()
                        }
                    }
                }
                Text("Escolha o ponto no mapa", style = MaterialTheme.typography.titleLarge)
                InteractiveMapComponent(
                    coordinates,
                    radius ?: 500.0,
                    placeQuery,
                    {
                        latitude = it.latitude
                        longitude = it.longitude
                        onMapCoordinatesSelected(it)
                    },
                    Modifier.height(270.dp)
                )
                Text("Toque para marcar a entrada ou o ponto onde deseja receber o aviso.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    radiusText,
                    { radiusText = it },
                    Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Straighten, null) },
                    label = { Text("Raio sugerido em metros") },
                    supportingText = { Text("De 100 a 20.000 metros") },
                    isError = radius == null,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    }
}

private fun semanticFavoriteIcon(favorite: Favorite) = when (favorite.iconName.lowercase()) {
    "home" -> Icons.Default.Home
    "work" -> Icons.Default.Work
    "school" -> Icons.Default.School
    else -> placeIcon(favorite.nickname)
}

private fun relativeDate(date: java.time.LocalDate): String = when (date) {
    java.time.LocalDate.now() -> "Hoje"
    java.time.LocalDate.now().minusDays(1) -> "Ontem"
    else -> date.format(DateTimeFormatter.ofPattern("dd/MM"))
}
