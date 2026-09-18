package com.destino.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.destino.app.data.local.dao.AlarmDao
import com.destino.app.data.local.dao.AlarmRuntimeDao
import com.destino.app.data.local.dao.AlertDeliveryAttemptDao
import com.destino.app.data.local.dao.AlertEventDao
import com.destino.app.data.local.dao.DestinationDao
import com.destino.app.data.local.dao.FavoriteDao
import com.destino.app.data.local.dao.HistoryDao
import com.destino.app.data.local.dao.OccurrenceDao
import com.destino.app.data.local.dao.ScheduleDao
import com.destino.app.data.local.dao.SessionDao
import com.destino.app.data.local.entities.AlarmDefinitionEntity
import com.destino.app.data.local.entities.AlarmOccurrenceEntity
import com.destino.app.data.local.entities.AlarmRuntimeEntity
import com.destino.app.data.local.entities.AlertDeliveryAttemptEntity
import com.destino.app.data.local.entities.AlertEventEntity
import com.destino.app.data.local.entities.DestinationEntity
import com.destino.app.data.local.entities.FavoriteEntity
import com.destino.app.data.local.entities.HistoryRecordEntity
import com.destino.app.data.local.entities.MonitoringSessionEntity
import com.destino.app.data.local.entities.ScheduleExceptionEntity
import com.destino.app.data.local.entities.ScheduleRuleDayEntity
import com.destino.app.data.local.entities.ScheduleRuleEntity
import com.destino.app.data.local.entities.ScheduleWindowEntity

@Database(
    entities = [
        DestinationEntity::class,
        AlarmDefinitionEntity::class,
        MonitoringSessionEntity::class,
        AlarmRuntimeEntity::class,
        AlertEventEntity::class,
        ScheduleRuleEntity::class,
        ScheduleRuleDayEntity::class,
        ScheduleWindowEntity::class,
        ScheduleExceptionEntity::class,
        AlarmOccurrenceEntity::class,
        FavoriteEntity::class,
        AlertDeliveryAttemptEntity::class,
        HistoryRecordEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class DestinoDatabase : RoomDatabase() {
    abstract fun destinationDao(): DestinationDao
    abstract fun alarmDao(): AlarmDao
    abstract fun sessionDao(): SessionDao
    abstract fun alarmRuntimeDao(): AlarmRuntimeDao
    abstract fun alertEventDao(): AlertEventDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun occurrenceDao(): OccurrenceDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun alertDeliveryAttemptDao(): AlertDeliveryAttemptDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Alter destinations
                db.execSQL("ALTER TABLE destinations ADD COLUMN providerOrigin TEXT")
                db.execSQL("ALTER TABLE destinations ADD COLUMN placeId TEXT")
                db.execSQL("ALTER TABLE destinations ADD COLUMN expiresAtEpochMs INTEGER")

                // Alter alarm_definitions
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN name TEXT NOT NULL DEFAULT 'Alarme'")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN soundType TEXT NOT NULL DEFAULT 'SYSTEM_DEFAULT'")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN soundUri TEXT")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN soundTitle TEXT NOT NULL DEFAULT 'Toque padrão do aparelho'")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN vibrationPattern TEXT NOT NULL DEFAULT 'STRONG'")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN audioOutputPolicy TEXT NOT NULL DEFAULT 'SYSTEM_DEFAULT'")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN monitoringProfile TEXT NOT NULL DEFAULT 'AUTOMATIC'")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN allowNewEntrySameWindow INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN cooldownMinutes INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN version INTEGER NOT NULL DEFAULT 1")

                // Alter monitoring_sessions
                db.execSQL("ALTER TABLE monitoring_sessions ADD COLUMN occurrenceId TEXT")

                // Create new tables
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `schedule_rules` (
                        `id` TEXT NOT NULL,
                        `alarmDefinitionId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `specificDateIso` TEXT,
                        `timeZonePolicy` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`alarmDefinitionId`) REFERENCES `alarm_definitions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_rules_alarmDefinitionId` ON `schedule_rules` (`alarmDefinitionId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `schedule_rule_days` (
                        `ruleId` TEXT NOT NULL,
                        `dayOfWeek` INTEGER NOT NULL,
                        PRIMARY KEY(`ruleId`, `dayOfWeek`),
                        FOREIGN KEY(`ruleId`) REFERENCES `schedule_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_rule_days_ruleId` ON `schedule_rule_days` (`ruleId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `schedule_windows` (
                        `id` TEXT NOT NULL,
                        `ruleId` TEXT NOT NULL,
                        `startMinuteOfDay` INTEGER NOT NULL,
                        `endMinuteOfDay` INTEGER NOT NULL,
                        `isAllDay` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`ruleId`) REFERENCES `schedule_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_windows_ruleId` ON `schedule_windows` (`ruleId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `schedule_exceptions` (
                        `id` TEXT NOT NULL,
                        `ruleId` TEXT NOT NULL,
                        `occurrenceDateIso` TEXT NOT NULL,
                        `windowId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`ruleId`) REFERENCES `schedule_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_exceptions_ruleId` ON `schedule_exceptions` (`ruleId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `alarm_occurrences` (
                        `id` TEXT NOT NULL,
                        `alarmDefinitionId` TEXT NOT NULL,
                        `windowId` TEXT NOT NULL,
                        `occurrenceDateIso` TEXT NOT NULL,
                        `scheduledStartEpochMs` INTEGER NOT NULL,
                        `scheduledEndEpochMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `arrivalCycle` INTEGER NOT NULL,
                        `appliedRadiusMeters` REAL NOT NULL,
                        `appliedVibration` INTEGER NOT NULL,
                        `appliedSoundUri` TEXT,
                        `completionReason` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`alarmDefinitionId`) REFERENCES `alarm_definitions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_occurrences_alarmDefinitionId` ON `alarm_occurrences` (`alarmDefinitionId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `favorites` (
                        `id` TEXT NOT NULL,
                        `nickname` TEXT NOT NULL,
                        `iconName` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `destinationId` TEXT NOT NULL,
                        `suggestedRadiusMeters` REAL NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`destinationId`) REFERENCES `destinations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_destinationId` ON `favorites` (`destinationId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `alert_delivery_attempts` (
                        `id` TEXT NOT NULL,
                        `eventId` TEXT NOT NULL,
                        `channelsDelivered` TEXT NOT NULL,
                        `failureReason` TEXT,
                        `attemptEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`eventId`) REFERENCES `alert_events`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_delivery_attempts_eventId` ON `alert_delivery_attempts` (`eventId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `history_records` (
                        `id` TEXT NOT NULL,
                        `occurrenceId` TEXT,
                        `alarmName` TEXT NOT NULL,
                        `destinationName` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `timestampEpochMs` INTEGER NOT NULL,
                        `details` TEXT,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN usesDefaultSound INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN usesDefaultVibrationPattern INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN usesDefaultAudioOutputPolicy INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarm_definitions ADD COLUMN usesDefaultMonitoringProfile INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
