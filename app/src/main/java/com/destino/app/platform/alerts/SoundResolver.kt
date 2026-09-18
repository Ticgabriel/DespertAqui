package com.destino.app.platform.alerts

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import com.destino.app.core.model.SoundSelection
import com.destino.app.core.model.SoundSourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface SoundResolver {
    suspend fun resolveSoundUri(selection: SoundSelection): Uri?
}

@Singleton
class SoundResolverImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SoundResolver {

    override suspend fun resolveSoundUri(selection: SoundSelection): Uri? {
        if (selection.type == SoundSourceType.SILENT) {
            return null
        }

        if (selection.type == SoundSourceType.USER_DOCUMENT && !selection.uriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(selection.uriString)
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    stream.close()
                    return uri
                }
            } catch (e: Exception) {
                // Fallback para toque padrão caso o arquivo próprio tenha sido apagado ou o acesso expirado
            }
        }

        if (selection.type == SoundSourceType.SYSTEM_RINGTONE && !selection.uriString.isNullOrBlank()) {
            try {
                val uri = Uri.parse(selection.uriString)
                val ringtone = RingtoneManager.getRingtone(context, uri)
                if (ringtone != null) {
                    return uri
                }
            } catch (e: Exception) {
                // Fallback
            }
        }

        // Tentar toque padrão do sistema Android (TYPE_ALARM)
        try {
            val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: Settings.System.DEFAULT_ALARM_ALERT_URI
            if (defaultAlarmUri != null) {
                val ringtone = RingtoneManager.getRingtone(context, defaultAlarmUri)
                if (ringtone != null) {
                    return defaultAlarmUri
                }
            }
        } catch (e: Exception) {
            // Fallback para som embarcado
        }

        // Fallback final: tom gerado embarcado do DespertAqui
        return try {
            val file: File = AudioToneGenerator.getOrCreateAlarmToneFile(context)
            Uri.fromFile(file)
        } catch (e: Exception) {
            null
        }
    }
}
