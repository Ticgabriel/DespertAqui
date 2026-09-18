package com.destino.app.platform.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.destino.app.core.model.AudioOutputPolicy
import com.destino.app.core.model.HeadphoneDisconnectBehavior
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.datastore.core.DataStore
import com.destino.app.core.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class AudioRouteState(
    val isHeadphonesConnected: Boolean = false,
    val connectedDevices: List<String> = emptyList(),
    val isHeadsetWired: Boolean = false,
    val isBluetoothAudio: Boolean = false,
    val isUsbHeadset: Boolean = false,
    val preferredOutputPolicy: AudioOutputPolicy = AudioOutputPolicy.SYSTEM_DEFAULT,
    val disconnectBehavior: HeadphoneDisconnectBehavior = HeadphoneDisconnectBehavior.CONTINUE_WITH_SPEAKER
)

interface AudioRouteObserver {
    val routeState: StateFlow<AudioRouteState>
    fun setPreferredPolicy(policy: AudioOutputPolicy)
    fun setDisconnectBehavior(behavior: HeadphoneDisconnectBehavior)
    fun refreshDevices()
    var onBecomingNoisy: (() -> Unit)?
}

@Singleton
class AudioRouteObserverImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataStore: DataStore<UserPreferences>
) : AudioRouteObserver {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _routeState = MutableStateFlow(AudioRouteState())
    override val routeState: StateFlow<AudioRouteState> = _routeState.asStateFlow()

    override var onBecomingNoisy: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                refreshDevices()
                onBecomingNoisy?.invoke()
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
        }
    }

    init {
        scope.launch {
            preferencesDataStore.data.collectLatest { preferences ->
                _routeState.update {
                    it.copy(
                        preferredOutputPolicy = preferences.defaultAudioOutputPolicy,
                        disconnectBehavior = preferences.defaultHeadphoneDisconnectBehavior
                    )
                }
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(noisyReceiver, filter)
        }
        refreshDevices()
    }

    override fun setPreferredPolicy(policy: AudioOutputPolicy) {
        _routeState.update { it.copy(preferredOutputPolicy = policy) }
    }

    override fun setDisconnectBehavior(behavior: HeadphoneDisconnectBehavior) {
        _routeState.update { it.copy(disconnectBehavior = behavior) }
    }

    override fun refreshDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var wired = false
        var bt = false
        var usb = false
        val deviceNames = mutableListOf<String>()

        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                    wired = true
                    deviceNames.add("Fone com fio")
                }
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> {
                    usb = true
                    deviceNames.add("Fone USB")
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID,
                26, // TYPE_BLE_HEADSET
                27  // TYPE_BLE_SPEAKER
                -> {
                    bt = true
                    deviceNames.add(device.productName?.toString()?.ifBlank { "Fone Bluetooth" } ?: "Fone Bluetooth")
                }
            }
        }

        val hasHeadphones = wired || bt || usb
        _routeState.update {
            it.copy(
                isHeadphonesConnected = hasHeadphones,
                connectedDevices = deviceNames,
                isHeadsetWired = wired,
                isBluetoothAudio = bt,
                isUsbHeadset = usb
            )
        }
    }
}
