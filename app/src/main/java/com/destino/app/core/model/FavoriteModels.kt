package com.destino.app.core.model

data class Favorite(
    val id: String,
    val nickname: String,
    val iconName: String = "place",
    val sortOrder: Int = 0,
    val destinationId: String,
    val destinationName: String,
    val coordinates: Coordinates,
    val suggestedRadiusMeters: Double = 500.0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
