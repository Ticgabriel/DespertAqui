package com.destino.app

import android.os.Bundle
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.destino.app.platform.schedule.SystemScheduler
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.destino.app.core.model.UserPreferences
import com.destino.app.platform.alerts.AlertNotificationHelper
import com.destino.app.navigation.DestinoNavHost
import com.destino.app.ui.theme.DestinoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferencesDataStore: DataStore<UserPreferences>
    @Inject lateinit var systemScheduler: SystemScheduler
    @Inject lateinit var placesAccess: com.destino.app.platform.places.PlacesAccessStore
    private val alertOpenRequest = mutableIntStateOf(0)
    private val alertSessionId = mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        lifecycleScope.launch {
            systemScheduler.reconcileSchedules()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        requestStartupPermissions()

        setContent {
            val preferences by preferencesDataStore.data.collectAsState(initial = UserPreferences())
            DestinoTheme(appTheme = preferences.theme) {
                DestinoNavHost(
                    openAlertRequest = alertOpenRequest.intValue,
                    openAlertSessionId = alertSessionId.value
                )
                com.destino.app.feature.settings.PlacesKeyDialog(placesAccess)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            systemScheduler.reconcileSchedules()
        }
    }

    private fun requestStartupPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AlertNotificationHelper.EXTRA_OPEN_ALERT, false) == true) {
            val sessionId = intent.getStringExtra(AlertNotificationHelper.EXTRA_SESSION_ID)
                ?: intent.getStringExtra(AlertNotificationHelper.EXTRA_ALERT_EVENT_ID)
                ?: ""
            alertSessionId.value = sessionId
            alertOpenRequest.intValue += 1
            intent.removeExtra(AlertNotificationHelper.EXTRA_OPEN_ALERT)
        }
    }
}
