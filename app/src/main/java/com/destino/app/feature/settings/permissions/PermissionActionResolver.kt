package com.destino.app.feature.settings.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.destino.app.platform.runtime.ChecklistActionType

object PermissionActionResolver {

    fun executeAction(context: Context, actionType: ChecklistActionType) {
        val packageName = context.packageName
        val intent = when (actionType) {
            ChecklistActionType.OPEN_APP_SETTINGS -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            ChecklistActionType.OPEN_LOCATION_SETTINGS -> {
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
            ChecklistActionType.REQUEST_EXACT_ALARM_SETTING -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                }
            }
            ChecklistActionType.REQUEST_BATTERY_SETTING -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                }
            }
            ChecklistActionType.REQUEST_NOTIFICATION_SETTINGS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                }
            }
            ChecklistActionType.REQUEST_DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            ChecklistActionType.ADJUST_VOLUME -> {
                Intent(Settings.ACTION_SOUND_SETTINGS)
            }
            ChecklistActionType.NONE -> null
        }

        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(it)
            } catch (e: Exception) {
                // Fallback para tela de detalhes do app
                try {
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (ignored: Exception) {}
            }
        }
    }
}
