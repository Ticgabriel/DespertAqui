package com.destino.app.core.model

enum class HistoryEventType {
    START,
    ARRIVAL,
    PRECAUTION,
    EXPIRED,
    INTERRUPTED,
    DELIVERY_FAILED
}

data class HistoryRecord(
    val id: String,
    val occurrenceId: String?,
    val alarmName: String,
    val destinationName: String,
    val eventType: HistoryEventType,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val details: String? = null
) {
    val outcomeDescription: String
        get() = when (eventType) {
            HistoryEventType.START -> "Viagem iniciada"
            HistoryEventType.ARRIVAL -> "Chegada confirmada"
            HistoryEventType.PRECAUTION -> "Aviso de precaução"
            HistoryEventType.EXPIRED -> "Janela expirada"
            HistoryEventType.INTERRUPTED -> "Viagem interrompida"
            HistoryEventType.DELIVERY_FAILED -> "Falha na entrega do som"
        }
}
