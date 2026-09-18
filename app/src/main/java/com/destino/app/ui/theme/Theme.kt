package com.destino.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.destino.app.core.model.AppTheme

// ── Tipografia centralizada ────────────────────────────────────
private val DestinoTypography = Typography(
    // Título de página: 26 sp Bold
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp
    ),
    // Título de cartão / seção: 18 sp SemiBold
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    // Corpo principal: 15 sp
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    // Metadados / labels: 13 sp
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

// ── Esquema claro ──────────────────────────────────────────────
private val LightColorScheme = lightColorScheme(
    primary             = DestinoPrimary,
    onPrimary           = DestinoOnPrimary,
    primaryContainer    = DestinoSurfaceVariant,
    onPrimaryContainer  = DestinoPrimary,
    secondary           = DestinoSecondaryText,
    onSecondary         = DestinoOnPrimary,
    secondaryContainer  = DestinoSurfaceVariant,
    onSecondaryContainer = DestinoOnSurface,
    background          = DestinoBackground,
    onBackground        = DestinoOnSurface,
    surface             = DestinoSurface,
    onSurface           = DestinoOnSurface,
    surfaceVariant      = DestinoSurfaceVariant,
    onSurfaceVariant    = DestinoSecondaryText,
    outline             = DestinoOutline,
    outlineVariant      = DestinoOutline,
    error               = DestinoError,
    onError             = DestinoOnPrimary
)

// ── Esquema descanso (escuro verde) — usado apenas em RestModeScreen ──
val RestColorScheme = darkColorScheme(
    primary             = DestinoRestAccent,
    onPrimary           = DestinoRestBackground,
    primaryContainer    = DestinoRestSurface,
    onPrimaryContainer  = DestinoRestText,
    background          = DestinoRestBackground,
    onBackground        = DestinoRestText,
    surface             = DestinoRestSurface,
    onSurface           = DestinoRestText,
    surfaceVariant      = DestinoRestSurface,
    onSurfaceVariant    = DestinoRestText.copy(alpha = 0.7f),
    outline             = DestinoRestAccent.copy(alpha = 0.3f),
    error               = DestinoAttention,
    onError             = DestinoRestBackground
)

@Composable
fun DestinoTheme(
    appTheme: AppTheme = AppTheme.LIGHT,
    content: @Composable () -> Unit
) {
    val darkTheme = when (appTheme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) RestColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DestinoTypography,
        content = content
    )
}
