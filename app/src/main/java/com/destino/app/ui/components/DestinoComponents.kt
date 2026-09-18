package com.destino.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.destino.app.ui.theme.DestinoAttention

@Composable
fun DestinoBrand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Place, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
        Text("DespertAqui", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinoTopBar(title: String, back: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, style = if (back == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge) },
        navigationIcon = { if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
        actions = { action?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

@Composable
fun DestinoCard(
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    attention: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = when {
            attention -> DestinoAttention
            tinted -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun IconTile(
    icon: ImageVector,
    large: Boolean = false,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(shape = CircleShape, color = background) {
        Box(Modifier.size(if (large) 96.dp else 48.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(if (large) 46.dp else 25.dp), tint = tint)
        }
    }
}

fun placeIcon(name: String): ImageVector = when {
    name.contains("casa", true) || name.contains("lar", true) -> Icons.Default.Home
    name.contains("trabalho", true) || name.contains("empresa", true) -> Icons.Default.Work
    name.contains("faculdade", true) || name.contains("escola", true) || name.contains("universidade", true) -> Icons.Default.School
    name.contains("estação", true) || name.contains("terminal", true) -> Icons.Default.Train
    else -> Icons.Default.Place
}

@Composable
fun PrimaryAction(
    label: String,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun DetailRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector = Icons.Default.Place,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    iconBackground: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconTile(icon, background = iconBackground)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SegmentedPills(
    options: List<Pair<ImageVector?, String>>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { index, (icon, label) ->
                val selected = index == selectedIndex
                Surface(
                    Modifier.weight(1f).clickable { onSelected(index) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoCard(text: String, attention: Boolean = false) {
    DestinoCard(tinted = !attention, attention = attention) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyDestination(title: String, description: String, icon: ImageVector, action: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            repeat(8) { index ->
                Box(
                    Modifier
                        .offset(
                            x = when (index) { 0 -> 0.dp; 1 -> 34.dp; 2 -> 48.dp; 3 -> 34.dp; 4 -> 0.dp; 5 -> (-34).dp; 6 -> (-48).dp; else -> (-34).dp },
                            y = when (index) { 0 -> (-48).dp; 1 -> (-34).dp; 2 -> 0.dp; 3 -> 34.dp; 4 -> 48.dp; 5 -> 34.dp; 6 -> 0.dp; else -> (-34).dp }
                        )
                        .size(4.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .28f), CircleShape)
                )
            }
            IconTile(icon, large = true)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        PrimaryAction(action, icon = Icons.Default.Add, onClick = onClick)
    }
}

@Composable
fun ConfirmRemoval(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, null) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Excluir", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
