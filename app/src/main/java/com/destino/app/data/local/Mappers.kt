package com.destino.app.data.local

import com.destino.app.core.model.AlarmDefinition
import com.destino.app.core.model.AlarmOccurrence
import com.destino.app.core.model.AlarmRuntime
import com.destino.app.core.model.AlertEvent
import com.destino.app.core.model.AudioOutputPolicy
import com.destino.app.core.model.Coordinates
import com.destino.app.core.model.Destination
import com.destino.app.core.model.Favorite
import com.destino.app.core.model.HistoryRecord
import com.destino.app.core.model.MonitoringProfile
import com.destino.app.core.model.MonitoringSession
import com.destino.app.core.model.ScheduleException
import com.destino.app.core.model.ScheduleExceptionType
import com.destino.app.core.model.ScheduleRule
import com.destino.app.core.model.ScheduleRuleType
import com.destino.app.core.model.ScheduleWindow
import com.destino.app.core.model.SoundSelection
import com.destino.app.core.model.SoundSourceType
import com.destino.app.core.model.TimeZonePolicy
import com.destino.app.core.model.VibrationPattern
import com.destino.app.data.local.entities.AlarmDefinitionEntity
import com.destino.app.data.local.entities.AlarmOccurrenceEntity
import com.destino.app.data.local.entities.AlarmRuntimeEntity
import com.destino.app.data.local.entities.AlertEventEntity
import com.destino.app.data.local.entities.DestinationEntity
import com.destino.app.data.local.entities.FavoriteEntity
import com.destino.app.data.local.entities.HistoryRecordEntity
import com.destino.app.data.local.entities.MonitoringSessionEntity
import com.destino.app.data.local.entities.ScheduleExceptionEntity
import com.destino.app.data.local.entities.ScheduleRuleEntity
import com.destino.app.data.local.entities.ScheduleWindowEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

fun DestinationEntity.toDomain(): Destination = Destination(
    id = id,
    name = name,
    coordinates = Coordinates(latitude, longitude),
    providerOrigin = providerOrigin,
    placeId = placeId,
    expiresAtEpochMs = expiresAtEpochMs
)

fun Destination.toEntity(): DestinationEntity = DestinationEntity(
    id = id,
    name = name,
    latitude = coordinates.latitude,
    longitude = coordinates.longitude,
    providerOrigin = providerOrigin,
    placeId = placeId,
    expiresAtEpochMs = expiresAtEpochMs
)

fun AlarmDefinitionEntity.toDomain(): AlarmDefinition = AlarmDefinition(
    id = id,
    destinationId = destinationId,
    radiusMeters = radiusMeters,
    isVibrationEnabled = isVibrationEnabled,
    name = name,
    isEnabled = isEnabled,
    soundSelection = SoundSelection(
        type = try { SoundSourceType.valueOf(soundType) } catch (e: Exception) { SoundSourceType.SYSTEM_DEFAULT },
        uriString = soundUri,
        title = soundTitle
    ),
    usesDefaultSound = usesDefaultSound,
    vibrationPattern = try { VibrationPattern.valueOf(vibrationPattern) } catch (e: Exception) { VibrationPattern.STRONG },
    usesDefaultVibrationPattern = usesDefaultVibrationPattern,
    audioOutputPolicy = try { AudioOutputPolicy.valueOf(audioOutputPolicy) } catch (e: Exception) { AudioOutputPolicy.SYSTEM_DEFAULT },
    usesDefaultAudioOutputPolicy = usesDefaultAudioOutputPolicy,
    monitoringProfile = try { MonitoringProfile.valueOf(monitoringProfile) } catch (e: Exception) { MonitoringProfile.AUTOMATIC },
    usesDefaultMonitoringProfile = usesDefaultMonitoringProfile,
    allowNewEntrySameWindow = allowNewEntrySameWindow,
    cooldownMinutes = cooldownMinutes,
    version = version
)

fun AlarmDefinition.toEntity(isConsumed: Boolean = false): AlarmDefinitionEntity = AlarmDefinitionEntity(
    id = id,
    destinationId = destinationId,
    radiusMeters = radiusMeters,
    isVibrationEnabled = isVibrationEnabled,
    isConsumed = isConsumed,
    name = name,
    isEnabled = isEnabled,
    soundType = soundSelection.type.name,
    soundUri = soundSelection.uriString,
    soundTitle = soundSelection.title,
    usesDefaultSound = usesDefaultSound,
    vibrationPattern = vibrationPattern.name,
    usesDefaultVibrationPattern = usesDefaultVibrationPattern,
    audioOutputPolicy = audioOutputPolicy.name,
    usesDefaultAudioOutputPolicy = usesDefaultAudioOutputPolicy,
    monitoringProfile = monitoringProfile.name,
    usesDefaultMonitoringProfile = usesDefaultMonitoringProfile,
    allowNewEntrySameWindow = allowNewEntrySameWindow,
    cooldownMinutes = cooldownMinutes,
    version = version
)

fun MonitoringSessionEntity.toDomain(): MonitoringSession = MonitoringSession(
    id = id,
    destinationId = destinationId,
    alarmDefinitionId = alarmDefinitionId,
    state = state,
    startedAtEpochMs = startedAtEpochMs,
    finishedAtEpochMs = finishedAtEpochMs,
    occurrenceId = occurrenceId
)

fun MonitoringSession.toEntity(occurrenceId: String? = this.occurrenceId): MonitoringSessionEntity = MonitoringSessionEntity(
    id = id,
    destinationId = destinationId,
    alarmDefinitionId = alarmDefinitionId,
    state = state,
    startedAtEpochMs = startedAtEpochMs,
    finishedAtEpochMs = finishedAtEpochMs,
    occurrenceId = occurrenceId ?: this.occurrenceId
)

fun AlarmRuntimeEntity.toDomain(): AlarmRuntime = AlarmRuntime(
    id = id,
    sessionId = sessionId,
    isArmed = isArmed,
    confirmationsCount = confirmationsCount,
    waitingExit = waitingExit,
    cooldownUntilMonotonicMs = cooldownUntilMonotonicMs
)

fun AlarmRuntime.toEntity(): AlarmRuntimeEntity = AlarmRuntimeEntity(
    id = id,
    sessionId = sessionId,
    isArmed = isArmed,
    confirmationsCount = confirmationsCount,
    waitingExit = waitingExit,
    cooldownUntilMonotonicMs = cooldownUntilMonotonicMs
)

fun AlertEventEntity.toDomain(): AlertEvent = AlertEvent(
    id = id,
    sessionId = sessionId,
    cycle = cycle,
    eventType = eventType,
    state = state,
    createdAtEpochMs = createdAtEpochMs,
    deliveredAtEpochMs = deliveredAtEpochMs,
    acknowledgedAtEpochMs = acknowledgedAtEpochMs,
    failureReason = failureReason
)

fun AlertEvent.toEntity(): AlertEventEntity = AlertEventEntity(
    id = id,
    sessionId = sessionId,
    cycle = cycle,
    eventType = eventType,
    state = state,
    createdAtEpochMs = createdAtEpochMs,
    deliveredAtEpochMs = deliveredAtEpochMs,
    acknowledgedAtEpochMs = acknowledgedAtEpochMs,
    failureReason = failureReason
)

fun ScheduleRuleEntity.toDomain(daysOfWeek: Set<DayOfWeek> = emptySet()): ScheduleRule = ScheduleRule(
    id = id,
    alarmDefinitionId = alarmDefinitionId,
    type = try { ScheduleRuleType.valueOf(type) } catch (e: Exception) { ScheduleRuleType.RECURRING },
    specificDate = specificDateIso?.let { LocalDate.parse(it) },
    daysOfWeek = daysOfWeek,
    timeZonePolicy = try { TimeZonePolicy.valueOf(timeZonePolicy) } catch (e: Exception) { TimeZonePolicy.KEEP_LOCAL_WALL_CLOCK },
    createdAtEpochMs = createdAtEpochMs
)

fun ScheduleRule.toEntity(): ScheduleRuleEntity = ScheduleRuleEntity(
    id = id,
    alarmDefinitionId = alarmDefinitionId,
    type = type.name,
    specificDateIso = specificDate?.toString(),
    timeZonePolicy = timeZonePolicy.name,
    createdAtEpochMs = createdAtEpochMs
)

fun ScheduleWindowEntity.toDomain(): ScheduleWindow = ScheduleWindow(
    id = id,
    ruleId = ruleId,
    startTime = LocalTime.ofSecondOfDay(startMinuteOfDay * 60L),
    endTime = LocalTime.ofSecondOfDay(endMinuteOfDay * 60L),
    isAllDay = isAllDay
)

fun ScheduleWindow.toEntity(): ScheduleWindowEntity = ScheduleWindowEntity(
    id = id,
    ruleId = ruleId,
    startMinuteOfDay = startTime.toSecondOfDay() / 60,
    endMinuteOfDay = endTime.toSecondOfDay() / 60,
    isAllDay = isAllDay
)

fun ScheduleExceptionEntity.toDomain(): ScheduleException = ScheduleException(
    id = id,
    ruleId = ruleId,
    occurrenceDate = LocalDate.parse(occurrenceDateIso),
    windowId = windowId,
    type = try { ScheduleExceptionType.valueOf(type) } catch (e: Exception) { ScheduleExceptionType.SKIPPED },
    createdAtEpochMs = createdAtEpochMs
)

fun ScheduleException.toEntity(): ScheduleExceptionEntity = ScheduleExceptionEntity(
    id = id,
    ruleId = ruleId,
    occurrenceDateIso = occurrenceDate.toString(),
    windowId = windowId,
    type = type.name,
    createdAtEpochMs = createdAtEpochMs
)

fun AlarmOccurrenceEntity.toDomain(): AlarmOccurrence = AlarmOccurrence(
    id = id,
    alarmDefinitionId = alarmDefinitionId,
    windowId = windowId,
    occurrenceDate = LocalDate.parse(occurrenceDateIso),
    scheduledStartEpochMs = scheduledStartEpochMs,
    scheduledEndEpochMs = scheduledEndEpochMs,
    status = status,
    arrivalCycle = arrivalCycle,
    appliedRadiusMeters = appliedRadiusMeters,
    appliedVibration = appliedVibration,
    appliedSoundUri = appliedSoundUri,
    completionReason = completionReason
)

fun AlarmOccurrence.toEntity(): AlarmOccurrenceEntity = AlarmOccurrenceEntity(
    id = id,
    alarmDefinitionId = alarmDefinitionId,
    windowId = windowId,
    occurrenceDateIso = occurrenceDate.toString(),
    scheduledStartEpochMs = scheduledStartEpochMs,
    scheduledEndEpochMs = scheduledEndEpochMs,
    status = status,
    arrivalCycle = arrivalCycle,
    appliedRadiusMeters = appliedRadiusMeters,
    appliedVibration = appliedVibration,
    appliedSoundUri = appliedSoundUri,
    completionReason = completionReason
)

fun FavoriteEntity.toDomain(destination: Destination? = null): Favorite = Favorite(
    id = id,
    nickname = nickname,
    iconName = iconName,
    sortOrder = sortOrder,
    destinationId = destinationId,
    destinationName = destination?.name ?: nickname,
    coordinates = destination?.coordinates ?: Coordinates(0.0, 0.0),
    suggestedRadiusMeters = suggestedRadiusMeters,
    createdAtEpochMs = createdAtEpochMs
)

fun Favorite.toEntity(): FavoriteEntity = FavoriteEntity(
    id = id,
    nickname = nickname,
    iconName = iconName,
    sortOrder = sortOrder,
    destinationId = destinationId,
    suggestedRadiusMeters = suggestedRadiusMeters,
    createdAtEpochMs = createdAtEpochMs
)

fun HistoryRecordEntity.toDomain(): HistoryRecord = HistoryRecord(
    id = id,
    occurrenceId = occurrenceId,
    alarmName = alarmName,
    destinationName = destinationName,
    eventType = eventType,
    timestampEpochMs = timestampEpochMs,
    details = details
)

fun HistoryRecord.toEntity(): HistoryRecordEntity = HistoryRecordEntity(
    id = id,
    occurrenceId = occurrenceId,
    alarmName = alarmName,
    destinationName = destinationName,
    eventType = eventType,
    timestampEpochMs = timestampEpochMs,
    details = details
)
