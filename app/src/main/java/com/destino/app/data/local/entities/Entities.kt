package com.destino.app.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.destino.app.core.model.AlertEventType
import com.destino.app.core.model.AlertState
import com.destino.app.core.model.HistoryEventType
import com.destino.app.core.model.OccurrenceStatus
import com.destino.app.core.model.SessionState

@Entity(tableName = "destinations")
data class DestinationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val providerOrigin: String? = null,
    val placeId: String? = null,
    val expiresAtEpochMs: Long? = null
)

@Entity(
    tableName = "alarm_definitions",
    foreignKeys = [
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("destinationId")]
)
data class AlarmDefinitionEntity(
    @PrimaryKey val id: String,
    val destinationId: String,
    val radiusMeters: Double,
    val isVibrationEnabled: Boolean = true,
    val isConsumed: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val name: String = "Alarme",
    val isEnabled: Boolean = true,
    val soundType: String = "SYSTEM_DEFAULT",
    val soundUri: String? = null,
    val soundTitle: String = "Toque padrão do aparelho",
    val usesDefaultSound: Boolean = false,
    val vibrationPattern: String = "STRONG",
    val usesDefaultVibrationPattern: Boolean = false,
    val audioOutputPolicy: String = "SYSTEM_DEFAULT",
    val usesDefaultAudioOutputPolicy: Boolean = false,
    val monitoringProfile: String = "AUTOMATIC",
    val usesDefaultMonitoringProfile: Boolean = false,
    val allowNewEntrySameWindow: Boolean = false,
    val cooldownMinutes: Int = 5,
    val version: Int = 1
)

@Entity(
    tableName = "monitoring_sessions",
    foreignKeys = [
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AlarmDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["alarmDefinitionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("destinationId"), Index("alarmDefinitionId")]
)
data class MonitoringSessionEntity(
    @PrimaryKey val id: String,
    val destinationId: String,
    val alarmDefinitionId: String,
    val state: SessionState,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val occurrenceId: String? = null
)

@Entity(
    tableName = "alarm_runtimes",
    foreignKeys = [
        ForeignKey(
            entity = MonitoringSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class AlarmRuntimeEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val isArmed: Boolean = true,
    val confirmationsCount: Int = 0,
    val waitingExit: Boolean = false,
    val cooldownUntilMonotonicMs: Long = 0L
)

@Entity(
    tableName = "alert_events",
    foreignKeys = [
        ForeignKey(
            entity = MonitoringSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "cycle", "eventType"], unique = true)
    ]
)
data class AlertEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val cycle: Int,
    val eventType: AlertEventType,
    val state: AlertState,
    val createdAtEpochMs: Long,
    val deliveredAtEpochMs: Long? = null,
    val acknowledgedAtEpochMs: Long? = null,
    val failureReason: String? = null
)

@Entity(
    tableName = "schedule_rules",
    foreignKeys = [
        ForeignKey(
            entity = AlarmDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["alarmDefinitionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("alarmDefinitionId")]
)
data class ScheduleRuleEntity(
    @PrimaryKey val id: String,
    val alarmDefinitionId: String,
    val type: String, // ONE_OFF, RECURRING
    val specificDateIso: String? = null,
    val timeZonePolicy: String = "KEEP_LOCAL_WALL_CLOCK",
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "schedule_rule_days",
    primaryKeys = ["ruleId", "dayOfWeek"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduleRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ruleId")]
)
data class ScheduleRuleDayEntity(
    val ruleId: String,
    val dayOfWeek: Int // 1..7 (DayOfWeek.value)
)

@Entity(
    tableName = "schedule_windows",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ruleId")]
)
data class ScheduleWindowEntity(
    @PrimaryKey val id: String,
    val ruleId: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val isAllDay: Boolean = false
)

@Entity(
    tableName = "schedule_exceptions",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ruleId")]
)
data class ScheduleExceptionEntity(
    @PrimaryKey val id: String,
    val ruleId: String,
    val occurrenceDateIso: String,
    val windowId: String,
    val type: String, // SKIPPED, CANCELLED
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "alarm_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = AlarmDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["alarmDefinitionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("alarmDefinitionId")]
)
data class AlarmOccurrenceEntity(
    @PrimaryKey val id: String,
    val alarmDefinitionId: String,
    val windowId: String,
    val occurrenceDateIso: String,
    val scheduledStartEpochMs: Long,
    val scheduledEndEpochMs: Long,
    val status: OccurrenceStatus,
    val arrivalCycle: Int = 0,
    val appliedRadiusMeters: Double = 500.0,
    val appliedVibration: Boolean = true,
    val appliedSoundUri: String? = null,
    val completionReason: String? = null
)

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("destinationId")]
)
data class FavoriteEntity(
    @PrimaryKey val id: String,
    val nickname: String,
    val iconName: String = "place",
    val sortOrder: Int = 0,
    val destinationId: String,
    val suggestedRadiusMeters: Double = 500.0,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "alert_delivery_attempts",
    foreignKeys = [
        ForeignKey(
            entity = AlertEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("eventId")]
)
data class AlertDeliveryAttemptEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val channelsDelivered: String,
    val failureReason: String? = null,
    val attemptEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "history_records")
data class HistoryRecordEntity(
    @PrimaryKey val id: String,
    val occurrenceId: String? = null,
    val alarmName: String,
    val destinationName: String,
    val eventType: HistoryEventType,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val details: String? = null
)
