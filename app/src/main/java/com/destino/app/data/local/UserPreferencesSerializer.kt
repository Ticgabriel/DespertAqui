package com.destino.app.data.local

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.destino.app.core.model.AppTheme
import com.destino.app.core.model.UserPreferences
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

object UserPreferencesSerializer : Serializer<UserPreferences> {
    override val defaultValue: UserPreferences = UserPreferences(
        defaultRadiusMeters = 500.0,
        isVibrationEnabled = true,
        theme = AppTheme.LIGHT
    )

    override suspend fun readFrom(input: InputStream): UserPreferences {
        try {
            val jsonString = input.bufferedReader().use { it.readText() }
            if (jsonString.isBlank()) return defaultValue
            val json = JSONObject(jsonString)
            return UserPreferences(
                defaultRadiusMeters = json.optDouble("defaultRadiusMeters", 500.0),
                isVibrationEnabled = json.optBoolean("isVibrationEnabled", true),
                theme = try {
                    AppTheme.valueOf(json.optString("theme", AppTheme.LIGHT.name))
                } catch (e: Exception) {
                    AppTheme.LIGHT
                },
                draftDestinationName = json.optString("draftDestinationName", ""),
                draftLatitude = json.optString("draftLatitude", ""),
                draftLongitude = json.optString("draftLongitude", ""),
                defaultSoundSelection = com.destino.app.core.model.SoundSelection(
                    type = try {
                        com.destino.app.core.model.SoundSourceType.valueOf(
                            json.optString("soundType", com.destino.app.core.model.SoundSourceType.SYSTEM_DEFAULT.name)
                        )
                    } catch (e: Exception) {
                        com.destino.app.core.model.SoundSourceType.SYSTEM_DEFAULT
                    },
                    uriString = if (json.has("soundUri") && !json.isNull("soundUri")) json.getString("soundUri") else null,
                    title = json.optString("soundTitle", "Toque padrão do aparelho")
                ),
                defaultVibrationPattern = try {
                    com.destino.app.core.model.VibrationPattern.valueOf(
                        json.optString("vibrationPattern", com.destino.app.core.model.VibrationPattern.STRONG.name)
                    )
                } catch (e: Exception) {
                    com.destino.app.core.model.VibrationPattern.STRONG
                },
                defaultAudioOutputPolicy = try {
                    com.destino.app.core.model.AudioOutputPolicy.valueOf(
                        json.optString("audioOutputPolicy", com.destino.app.core.model.AudioOutputPolicy.SYSTEM_DEFAULT.name)
                    )
                } catch (e: Exception) {
                    com.destino.app.core.model.AudioOutputPolicy.SYSTEM_DEFAULT
                },
                defaultHeadphoneDisconnectBehavior = try {
                    com.destino.app.core.model.HeadphoneDisconnectBehavior.valueOf(
                        json.optString("headphoneDisconnectBehavior", com.destino.app.core.model.HeadphoneDisconnectBehavior.CONTINUE_WITH_SPEAKER.name)
                    )
                } catch (e: Exception) {
                    com.destino.app.core.model.HeadphoneDisconnectBehavior.CONTINUE_WITH_SPEAKER
                },
                defaultMonitoringProfile = try {
                    com.destino.app.core.model.MonitoringProfile.valueOf(
                        json.optString("monitoringProfile", com.destino.app.core.model.MonitoringProfile.AUTOMATIC.name)
                    )
                } catch (e: Exception) {
                    com.destino.app.core.model.MonitoringProfile.AUTOMATIC
                },
                alarmAudioDurationMinutes = json.optInt("alarmAudioDurationMinutes", 3),
                isGradualVolumeEnabled = json.optBoolean("isGradualVolumeEnabled", false),
                historyRetentionDays = json.optInt("historyRetentionDays", 30),
                isWeakSignalWarningEnabled = json.optBoolean("isWeakSignalWarningEnabled", true),
                allowNewEntrySameWindow = json.optBoolean("allowNewEntrySameWindow", false),
                customDistantIntervalSeconds = json.optInt("customDistantIntervalSeconds", 30),
                customApproachingIntervalSeconds = json.optInt("customApproachingIntervalSeconds", 5)
            )
        } catch (e: Exception) {
            throw CorruptionException("Não foi possível ler as preferências do usuário", e)
        }
    }

    override suspend fun writeTo(t: UserPreferences, output: OutputStream) {
        val json = JSONObject().apply {
            put("defaultRadiusMeters", t.defaultRadiusMeters)
            put("isVibrationEnabled", t.isVibrationEnabled)
            put("theme", t.theme.name)
            put("draftDestinationName", t.draftDestinationName)
            put("draftLatitude", t.draftLatitude)
            put("draftLongitude", t.draftLongitude)
            put("soundType", t.defaultSoundSelection.type.name)
            put("soundUri", t.defaultSoundSelection.uriString ?: JSONObject.NULL)
            put("soundTitle", t.defaultSoundSelection.title)
            put("vibrationPattern", t.defaultVibrationPattern.name)
            put("audioOutputPolicy", t.defaultAudioOutputPolicy.name)
            put("headphoneDisconnectBehavior", t.defaultHeadphoneDisconnectBehavior.name)
            put("monitoringProfile", t.defaultMonitoringProfile.name)
            put("alarmAudioDurationMinutes", t.alarmAudioDurationMinutes)
            put("isGradualVolumeEnabled", t.isGradualVolumeEnabled)
            put("historyRetentionDays", t.historyRetentionDays)
            put("isWeakSignalWarningEnabled", t.isWeakSignalWarningEnabled)
            put("allowNewEntrySameWindow", t.allowNewEntrySameWindow)
            put("customDistantIntervalSeconds", t.customDistantIntervalSeconds)
            put("customApproachingIntervalSeconds", t.customApproachingIntervalSeconds)
        }
        output.write(json.toString().toByteArray(Charsets.UTF_8))
    }
}
