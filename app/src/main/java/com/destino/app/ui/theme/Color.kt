package com.destino.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Tokens "Leve e Acolhedor" ─────────────────────────────────
val DestinoBackground       = Color(0xFFFAF8F0)   // fundo principal
val DestinoSurface          = Color(0xFFFFFEFA)   // formulários e cartões claros
val DestinoSurfaceVariant   = Color(0xFFF0F1E5)   // favoritos e resumos suaves
val DestinoPrimary          = Color(0xFF153F2C)   // CTA, seleção e ícones principais
val DestinoOnPrimary        = Color(0xFFFFFFFF)   // texto sobre verde
val DestinoOnSurface        = Color(0xFF1D3026)   // texto principal
val DestinoSecondaryText    = Color(0xFF606A61)   // descrições e metadados
val DestinoOutline          = Color(0xFFDADDCF)   // bordas e separadores
val DestinoAttention        = Color(0xFFFFE5CA)   // avisos e proximidade
val DestinoError            = Color(0xFFA33D28)   // erro e ação destrutiva

// ── Modo Descanso ──────────────────────────────────────────────
val DestinoRestBackground   = Color(0xFF11271D)
val DestinoRestSurface      = Color(0xFF1A3528)
val DestinoRestText         = Color(0xFFF6F3E9)
val DestinoRestAccent       = Color(0xFF8DD6BC)

// ── Aliases legados — referenciados em vários arquivos ─────────
val AstraCreamBackground    = DestinoBackground
val AstraDeepGreen          = DestinoPrimary
val AstraSageSurface        = DestinoSurfaceVariant
val AstraApricotHighlight   = DestinoAttention
val AstraDarkBackground     = DestinoRestBackground
val AstraDarkText           = DestinoRestText
val AstraDarkAccent         = DestinoRestAccent
val AstraDarkSurface        = DestinoRestSurface
val AstraError              = DestinoError
val AstraCharcoalText       = DestinoOnSurface
val AstraMutedText          = DestinoSecondaryText
