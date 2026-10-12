package me.kavishdevar.librepods.data.handoff

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.MacAddress
import android.os.Build
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AirPodsAssociationIssue {
    FEATURE_UNAVAILABLE,
    AIRPODS_NOT_SELECTED,
    AIRPODS_NOT_BONDED,
    REQUEST_FAILED,
}

data class AirPodsAssociationState(
    val required: Boolean = false,
    val supported: Boolean = true,
    val associated: Boolean = false,
    val pending: Boolean = false,
    val issue: AirPodsAssociationIssue? = null,
)

fun interface AirPodsAssociationUi {
    fun launch(intentSender: IntentSender)
}

/** Restricts the system association picker to the already-selected, bonded AirPods. */
@SuppressLint("MissingPermission")
object AirPodsAssociation {
    private val _state = MutableStateFlow(AirPodsAssociationState())
    val state: StateFlow<AirPodsAssociationState> = _state.asStateFlow()

    private var callback: CompanionDeviceManager.Callback? = null
    private var ui: WeakReference<AirPodsAssociationUi>? = null

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val required = Build.VERSION.SDK_INT >= 37 &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED) !=
            PackageManager.PERMISSION_GRANTED
        val supported = appContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_COMPANION_DEVICE_SETUP,
        )
        if (!required) {
            _state.value = AirPodsAssociationState(required = false, supported = supported)
            return
        }
        if (!supported) {
            _state.value = AirPodsAssociationState(
                required = true,
                supported = false,
                issue = AirPodsAssociationIssue.FEATURE_UNAVAILABLE,
            )
            return
        }
        val address = selectedAddress(appContext)
        _state.value = AirPodsAssociationState(
            required = true,
            supported = true,
            associated = address != null && isAssociated(appContext, address),
            pending = callback != null,
            issue = if (address == null) AirPodsAssociationIssue.AIRPODS_NOT_SELECTED else null,
        )
    }

    fun request(context: Context, ui: AirPodsAssociationUi) {
        val appContext = context.applicationContext
        refresh(appContext)
        if (!_state.value.required || !_state.value.supported || _state.value.associated ||
            callback != null
        ) return
        val address = selectedAddress(appContext) ?: return
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = try {
            adapter?.bondedDevices?.any { it.address.equals(address, ignoreCase = true) } == true
        } catch (_: SecurityException) {
            false
        }
        if (!bonded) {
            _state.value = _state.value.copy(issue = AirPodsAssociationIssue.AIRPODS_NOT_BONDED)
            return
        }

        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setAddress(address)
                    .build(),
            )
            .setSingleDevice(true)
            .build()
        this.ui = WeakReference(ui)
        val manager = appContext.getSystemService(CompanionDeviceManager::class.java)
        val associationCallback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                if (callback !== this) return
                val target = this@AirPodsAssociation.ui?.get()
                if (target == null) {
                    associationFailed()
                    return
                }
                _state.value = _state.value.copy(pending = true, issue = null)
                try {
                    target.launch(intentSender)
                } catch (_: RuntimeException) {
                    associationFailed()
                }
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                if (callback !== this) return
                clearPending()
                refresh(appContext)
            }

            override fun onFailure(error: CharSequence?) {
                associationFailed()
            }

            override fun onFailure(errorCode: Int, error: CharSequence?) {
                associationFailed()
            }

            private fun associationFailed() {
                if (callback !== this) return
                clearPending()
                _state.value = _state.value.copy(
                    pending = false,
                    issue = AirPodsAssociationIssue.REQUEST_FAILED,
                )
            }
        }
        callback = associationCallback
        _state.value = _state.value.copy(pending = true, issue = null)
        try {
            manager.associate(request, appContext.mainExecutor, associationCallback)
        } catch (_: RuntimeException) {
            clearPending()
            _state.value = _state.value.copy(
                pending = false,
                issue = AirPodsAssociationIssue.REQUEST_FAILED,
            )
        }
    }

    fun pickerReturned(context: Context) {
        clearPending()
        refresh(context.applicationContext)
    }

    private fun clearPending() {
        callback = null
        ui?.clear()
        ui = null
    }

    private fun selectedAddress(context: Context): String? {
        val value = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("mac_address", null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return try {
            MacAddress.fromString(value).toString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isAssociated(context: Context, address: String): Boolean {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        return try {
            manager.myAssociations.any {
                it.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
            }
        } catch (_: RuntimeException) {
            false
        }
    }
}
