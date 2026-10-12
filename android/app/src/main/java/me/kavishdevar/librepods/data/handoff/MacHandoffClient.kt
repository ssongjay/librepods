package me.kavishdevar.librepods.data.handoff

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

@SuppressLint("MissingPermission")
object MacHandoffClient {
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var paired: Boolean = false
        private set

    @Volatile
    var owner: HandoffOwner = HandoffOwner.NONE
        private set

    @Volatile
    var currentRevision: Long = -1L
        private set

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(MacHandoffState())
    val state: StateFlow<MacHandoffState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var initialized = false
    private var identityFailed = false
    private var listener: HandoffListener? = null
    private var identity: HandoffIdentity? = null
    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var generation = 0L
    private var mtu = 23
    private var phase = Phase.CLOSED
    private data class DiscoveredDevice(val device: BluetoothDevice, val advertisedName: String)
    private val discovered = linkedMapOf<String, DiscoveredDevice>()
    private data class OutboundChunk(val bytes: ByteArray, val finalChunk: Boolean, val frameBytes: Int)
    private val writeQueue = ArrayDeque<OutboundChunk>()
    private var outboundFrames = 0
    private var outboundBytes = 0
    private var inFlightChunk: OutboundChunk? = null
    private var writeInFlight = false
    private val frameDecoder = HandoffFrameDecoder()
    private var phoneNonce: ByteArray? = null
    private var remoteNonce: ByteArray? = null
    private var remoteDer: ByteArray? = null
    private var remoteName: String? = null
    private var sessionKeys: HandoffSessionKeys? = null
    private var sendSequence = 1L
    private var receiveSequence = 1L
    private var localApproved = false
    private var remoteReady = false
    private var remoteReadyOwner = HandoffOwner.NONE
    private var remoteReadyRevision = -1L
    private var connectedDeviceId: String? = null
    private var reconnectAttempt = 0
    private var backgroundReconnect = false
    private var transitionMessage: String? = null
    private var transitionSucceeded: Boolean? = null
    private var offlineAttemptCounter = 0L
    private var handshakeToken = 0L
    private var writeToken = 0L
    private var scanGeneration = -1L
    private val scanTimeout = Runnable {
        synchronized(lock) {
            if (generation != scanGeneration) return@synchronized
            stopScanLocked()
            if (!ready && enabled) {
                updateState(HandoffStatus.DISCONNECTED)
                scheduleReconnectLocked()
            }
        }
    }
    private val reconnectRunnable = Runnable {
        synchronized(lock) {
            if (enabled && listener != null && !ready) scanLocked(userInitiated = false)
        }
    }

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            if (appContext == null) appContext = context.applicationContext
            val currentContext = appContext ?: return
            val prefs = preferences(currentContext)
            enabled = prefs.getBoolean(KEY_ENABLED, false)
            initialized = true
            try {
                if (identity == null) identity = HandoffCrypto.identity()
                identityFailed = false
                val pinned = pinnedDerLocked()
                if (pinExistsLocked() && pinned == null) {
                    paired = false
                    updateState(HandoffStatus.ERROR, HandoffIssue.PROTOCOL_ERROR)
                    return
                }
                paired = pinned != null
                updateState(if (enabled) HandoffStatus.DISCONNECTED else HandoffStatus.DISABLED)
            } catch (_: Exception) {
                identityFailed = true
                paired = false
                updateState(HandoffStatus.ERROR, HandoffIssue.IDENTITY_UNAVAILABLE)
            }
        }
    }

    fun attach(context: Context, listener: HandoffListener) {
        initialize(context)
        synchronized(lock) {
            this.listener = listener
            if (enabled && gatt == null && scannerCallback == null && !ready) {
                scanLocked(userInitiated = false)
            }
        }
    }

    fun detach(listener: HandoffListener) {
        synchronized(lock) {
            if (this.listener !== listener) return
            this.listener = null
            shutdownNetworkingLocked()
            updateState(if (enabled) HandoffStatus.DISCONNECTED else HandoffStatus.DISABLED)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        synchronized(lock) {
            if (this.enabled == enabled) {
                if (enabled && !ready && gatt == null && scannerCallback == null && !identityFailed) {
                    scanLocked(userInitiated = true)
                }
                return
            }
            this.enabled = enabled
            preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (!enabled) {
                shutdownNetworkingLocked()
                updateState(HandoffStatus.DISABLED)
            } else {
                if (identityFailed) {
                    updateState(HandoffStatus.ERROR, HandoffIssue.IDENTITY_UNAVAILABLE)
                    return
                }
                reconnectAttempt = 0
                backgroundReconnect = false
                updateState(HandoffStatus.DISCONNECTED)
                scanLocked(userInitiated = true)
            }
        }
    }

    fun scan(context: Context) {
        initialize(context)
        synchronized(lock) {
            if (!enabled || ready || identityFailed) return
            reconnectAttempt = 0
            backgroundReconnect = false
            scanLocked(userInitiated = true)
        }
    }

    fun connect(context: Context, deviceId: String) {
        initialize(context)
        synchronized(lock) {
            if (!enabled || ready || identityFailed) return
            val device = discovered[deviceId]?.device ?: return
            connectLocked(device, deviceId)
        }
    }

    fun confirmPairing() {
        synchronized(lock) {
            if (phase != Phase.WAITING_APPROVAL || sessionKeys == null) return
            localApproved = true
            phase = Phase.WAITING_READY
            updateState(
                HandoffStatus.AUTHENTICATING,
                pairingCode = sessionKeys?.sas,
                pairingName = remoteName,
            )
            sendSecureLocked(JSONObject().put("type", "ready"))
            if (remoteReady) finishAuthenticationLocked()
        }
    }

    fun rejectPairing() {
        synchronized(lock) {
            if (phase != Phase.WAITING_APPROVAL) return
            sendPlainLocked(JSONObject().put("type", "rejection").put("reason", "user_rejected"))
            failSession(null, reconnect = false)
        }
    }

    fun forget(context: Context) {
        initialize(context)
        synchronized(lock) {
            preferences(context).edit()
                .remove(KEY_PINNED_DER)
                .remove(KEY_PINNED_DEVICE)
                .remove(KEY_PINNED_NAME)
                .apply()
            paired = false
            shutdownNetworkingLocked()
            reconnectAttempt = 0
            backgroundReconnect = false
            updateState(if (enabled) HandoffStatus.DISCONNECTED else HandoffStatus.DISABLED)
        }
    }

    fun reset(context: Context) {
        initialize(context)
        synchronized(lock) {
            shutdownNetworkingLocked()
            preferences(context).edit()
                .remove(KEY_PINNED_DER)
                .remove(KEY_PINNED_DEVICE)
                .remove(KEY_PINNED_NAME)
                .apply()
            paired = false
            if (identityFailed) {
                try {
                    HandoffCrypto.resetIdentity()
                    identity = HandoffCrypto.identity()
                    identityFailed = false
                } catch (_: Exception) {
                    identity = null
                    identityFailed = true
                    updateState(HandoffStatus.ERROR, HandoffIssue.IDENTITY_UNAVAILABLE)
                    return
                }
            }
            transitionMessage = null
            transitionSucceeded = null
            reconnectAttempt = 0
            backgroundReconnect = false
            updateState(if (enabled) HandoffStatus.DISCONNECTED else HandoffStatus.DISABLED)
            if (enabled) scanLocked(userInitiated = true)
        }
    }

    fun notifyPlaybackStarted() {
        synchronized(lock) {
            if (!enabled) return
            if (ready) {
                sendSecureLocked(JSONObject().put("type", "playback_started"))
            } else if (hasPinLocked()) {
                offlineAttemptCounter = if (offlineAttemptCounter == Long.MAX_VALUE) {
                    1L
                } else {
                    offlineAttemptCounter + 1L
                }
                val localRevision = -offlineAttemptCounter
                owner = HandoffOwner.PHONE
                currentRevision = localRevision
                transitionMessage = null
                transitionSucceeded = null
                updateState(HandoffStatus.DISCONNECTED)
                dispatchClaim(HandoffOwner.PHONE, localRevision)
            }
        }
    }

    fun reportResult(revision: Long, success: Boolean, message: String) {
        synchronized(lock) {
            if (revision < 0L && revision == currentRevision && enabled && paired && !ready &&
                owner == HandoffOwner.PHONE
            ) {
                transitionMessage = sanitizeMessage(message)
                transitionSucceeded = success
                updateState(HandoffStatus.DISCONNECTED)
                return
            }
            if (!ready || revision != currentRevision || revision < 0) return
            val type = when (owner) {
                HandoffOwner.MAC -> "yielded"
                HandoffOwner.PHONE -> "result"
                HandoffOwner.NONE -> return
            }
            val safeMessage = sanitizeMessage(message)
            transitionMessage = safeMessage
            transitionSucceeded = success
            updateState(
                HandoffStatus.READY,
                pairingCode = sessionKeys?.sas,
                pairingName = remoteName,
            )
            sendSecureLocked(
                JSONObject()
                    .put("type", type)
                    .put("revision", revision)
                    .put("success", success)
                    .put("message", safeMessage),
            )
        }
    }

    private fun scanLocked(userInitiated: Boolean) {
        val context = appContext ?: return
        if (!hasPermissions(context)) {
            updateState(HandoffStatus.ERROR, HandoffIssue.PERMISSION_REQUIRED)
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            updateState(HandoffStatus.ERROR, HandoffIssue.BLUETOOTH_UNAVAILABLE)
            return
        }
        if (gatt != null) {
            closeGattLocked()
            resetSessionLocked()
        }
        stopScanLocked()
        mainHandler.removeCallbacks(reconnectRunnable)
        if (userInitiated) discovered.clear()
        val currentScanGeneration = ++generation
        scanGeneration = currentScanGeneration
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(lock) {
                    if (generation != currentScanGeneration || scannerCallback !== this) return
                    val device = result.device
                    val id = device.address ?: return
                    val pinnedDevice = preferences(context).getString(KEY_PINNED_DEVICE, null)
                    val advertisedName = sanitizeName(result.scanRecord?.deviceName ?: "Mac")
                    val expectedHint = expectedDiscoveryHintLocked()
                    val reconnectCandidate = pinnedDevice == id ||
                        (expectedHint != null && advertisedName == expectedHint)
                    if (reconnectCandidate && !discovered.containsKey(id) &&
                        discovered.size >= MAX_DISCOVERED_DEVICES
                    ) {
                        discovered.keys.firstOrNull { it != pinnedDevice }?.let(discovered::remove)
                    }
                    if (discovered.containsKey(id) || discovered.size < MAX_DISCOVERED_DEVICES ||
                        reconnectCandidate
                    ) {
                        discovered[id] = DiscoveredDevice(device, advertisedName)
                    }
                    publishDevicesLocked()
                    if (reconnectCandidate && listener != null) connectLocked(device, id)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                synchronized(lock) {
                    if (generation != currentScanGeneration || scannerCallback !== this) return
                    scannerCallback = null
                    updateState(HandoffStatus.ERROR, HandoffIssue.CONNECTION_FAILED)
                    scheduleReconnectLocked()
                }
            }
        }
        scannerCallback = callback
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (!userInitiated && backgroundReconnect) {
                    ScanSettings.SCAN_MODE_LOW_POWER
                } else {
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                },
            )
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            updateState(HandoffStatus.SCANNING)
            mainHandler.removeCallbacks(scanTimeout)
            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (_: SecurityException) {
            scannerCallback = null
            updateState(HandoffStatus.ERROR, HandoffIssue.PERMISSION_REQUIRED)
        }
    }

    private fun connectLocked(device: BluetoothDevice, deviceId: String) {
        val context = appContext ?: return
        stopScanLocked()
        closeGattLocked()
        resetSessionLocked()
        connectedDeviceId = deviceId
        val connectionGeneration = ++generation
        phase = Phase.WAITING_HELLO
        updateState(HandoffStatus.CONNECTING)
        scheduleHandshakeTimeoutLocked(connectionGeneration)
        val callback = callback(connectionGeneration)
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            failSession(HandoffIssue.PERMISSION_REQUIRED, reconnect = false)
        }
    }

    private fun callback(callbackGeneration: Long) = object : BluetoothGattCallback() {
        private fun current(callbackGatt: BluetoothGatt): Boolean = synchronized(lock) {
            generation == callbackGeneration && gatt === callbackGatt
        }

        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(lock) {
                if (!current(callbackGatt)) {
                    callbackGatt.close()
                    return
                }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!callbackGatt.discoverServices()) {
                        failSession(HandoffIssue.CONNECTION_FAILED, reconnect = true)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    failSession(HandoffIssue.CONNECTION_FAILED, reconnect = true)
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            synchronized(lock) {
                if (!current(callbackGatt)) return
                val service = callbackGatt.getService(SERVICE_UUID)
                val rxCharacteristic = service?.getCharacteristic(RX_UUID)
                val txCharacteristic = service?.getCharacteristic(TX_UUID)
                val cccd = txCharacteristic?.getDescriptor(CCCD_UUID)
                rx = rxCharacteristic
                tx = txCharacteristic
                if (status != BluetoothGatt.GATT_SUCCESS || rxCharacteristic == null ||
                    txCharacteristic == null || cccd == null ||
                    !callbackGatt.setCharacteristicNotification(txCharacteristic, true) ||
                    callbackGatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) !=
                    BluetoothStatusCodes.SUCCESS
                ) {
                    failSession(HandoffIssue.PROTOCOL_ERROR, reconnect = true)
                }
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            synchronized(lock) {
                if (!current(callbackGatt)) return
                if (status != BluetoothGatt.GATT_SUCCESS || descriptor.uuid != CCCD_UUID) {
                    failSession(HandoffIssue.PROTOCOL_ERROR, reconnect = true)
                    return
                }
                callbackGatt.requestMtu(517)
                sendHelloLocked()
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, newMtu: Int, status: Int) {
            synchronized(lock) {
                if (current(callbackGatt) && status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            synchronized(lock) {
                if (!current(callbackGatt) || characteristic.uuid != TX_UUID) return
                try {
                    frameDecoder.append(value).forEach(::handleFrameLocked)
                } catch (_: Exception) {
                    failSession(HandoffIssue.PROTOCOL_ERROR, reconnect = true)
                }
            }
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(lock) {
                if (!current(callbackGatt) || characteristic.uuid != RX_UUID) return
                writeInFlight = false
                writeToken++
                val completedChunk = inFlightChunk
                inFlightChunk = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failSession(HandoffIssue.CONNECTION_FAILED, reconnect = true)
                } else {
                    if (completedChunk?.finalChunk == true) {
                        outboundFrames--
                        outboundBytes -= completedChunk.frameBytes
                    }
                    writeNextLocked()
                }
            }
        }
    }

    private fun sendHelloLocked() {
        val localIdentity = identity ?: return failSession(HandoffIssue.PROTOCOL_ERROR, false)
        val context = appContext ?: return failSession(HandoffIssue.PROTOCOL_ERROR, false)
        val nonce = HandoffCrypto.nonce()
        phoneNonce = nonce
        val name = Settings.Global.getString(context.contentResolver, "device_name")
            ?.take(64)
            ?.ifBlank { null }
            ?: "Android phone"
        sendPlainLocked(
            JSONObject()
                .put("type", "hello")
                .put("v", 1)
                .put("publicKey", Base64.encodeToString(localIdentity.publicDer, Base64.NO_WRAP))
                .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
                .put("name", name),
        )
    }

    private fun handleFrameLocked(json: String) {
        val envelope = JSONObject(json)
        if (envelope.optString("type") == "secure") {
            handleSecureLocked(envelope)
            return
        }
        if (phase != Phase.WAITING_HELLO && phase != Phase.WAITING_APPROVAL) {
            return failSession(HandoffIssue.PROTOCOL_ERROR, true)
        }
        when (envelope.optString("type")) {
            "hello" -> handleHelloLocked(envelope)
            "rejection" -> failSession(null, reconnect = false)
            else -> failSession(HandoffIssue.PROTOCOL_ERROR, reconnect = true)
        }
    }

    private fun handleHelloLocked(hello: JSONObject) {
        if (phase != Phase.WAITING_HELLO || hello.getInt("v") != 1) {
            return failSession(HandoffIssue.PROTOCOL_ERROR, true)
        }
        val localIdentity = identity ?: return failSession(HandoffIssue.PROTOCOL_ERROR, false)
        val localNonce = phoneNonce ?: return failSession(HandoffIssue.PROTOCOL_ERROR, true)
        val der = HandoffCrypto.decodePublicDer(hello.getString("publicKey"))
        val nonce = Base64.decode(hello.getString("nonce"), Base64.NO_WRAP)
        require(nonce.size == 32)
        val name = sanitizeName(hello.optString("name", "Mac"))
        val pinned = pinnedDerLocked()
        if (pinExistsLocked() && pinned == null) {
            sendPlainLocked(JSONObject().put("type", "rejection").put("reason", "invalid_pin"))
            return failSession(HandoffIssue.PROTOCOL_ERROR, false)
        }
        if (pinned != null && !pinned.contentEquals(der)) {
            sendPlainLocked(JSONObject().put("type", "rejection").put("reason", "pinned_peer_mismatch"))
            return failSession(HandoffIssue.PROTOCOL_ERROR, false)
        }
        remoteDer = der
        remoteNonce = nonce
        remoteName = name
        sessionKeys = HandoffCrypto.derive(localIdentity, der, nonce, localNonce)
        if (pinned != null) {
            localApproved = true
            phase = Phase.WAITING_READY
            updateState(HandoffStatus.AUTHENTICATING, pairingCode = sessionKeys?.sas, pairingName = name)
            sendSecureLocked(JSONObject().put("type", "ready"))
        } else {
            phase = Phase.WAITING_APPROVAL
            updateState(
                HandoffStatus.PAIRING,
                pairingCode = sessionKeys?.sas,
                pairingName = name,
            )
        }
    }

    private fun handleSecureLocked(envelope: JSONObject) {
        val keys = sessionKeys ?: return failSession(HandoffIssue.PROTOCOL_ERROR, true)
        val payload = HandoffCrypto.decrypt(keys.macToPhone, receiveSequence, envelope)
        receiveSequence++
        val type = payload.optString("type")
        if (!remoteReady) {
            if (type != "ready") return failSession(HandoffIssue.PROTOCOL_ERROR, true)
            val revision = payload.getLong("revision")
            require(revision >= 0)
            remoteReadyOwner = parseOwner(payload.getString("owner"), allowNone = true)
            remoteReadyRevision = revision
            remoteReady = true
            if (localApproved) finishAuthenticationLocked()
            return
        }
        if (!ready) return failSession(HandoffIssue.PROTOCOL_ERROR, true)
        when (type) {
            "claim" -> {
                val revision = payload.getLong("revision")
                if (revision <= currentRevision) return failSession(HandoffIssue.PROTOCOL_ERROR, true)
                val nextOwner = parseOwner(payload.getString("owner"), allowNone = false)
                currentRevision = revision
                owner = nextOwner
                transitionMessage = null
                transitionSucceeded = null
                updateState(
                    HandoffStatus.READY,
                    pairingCode = sessionKeys?.sas,
                    pairingName = remoteName,
                )
                dispatchClaim(nextOwner, revision)
            }
            "status" -> {
                val revision = payload.getLong("revision")
                if (revision < currentRevision) return failSession(HandoffIssue.PROTOCOL_ERROR, true)
                currentRevision = revision
                owner = parseOwner(payload.getString("owner"), allowNone = true)
                transitionSucceeded = payload.getBoolean("success")
                transitionMessage = sanitizeMessage(payload.optString("message", ""))
                updateState(
                    HandoffStatus.READY,
                    pairingCode = sessionKeys?.sas,
                    pairingName = remoteName,
                )
            }
            else -> failSession(HandoffIssue.PROTOCOL_ERROR, true)
        }
    }

    private fun finishAuthenticationLocked() {
        val context = appContext ?: return
        val der = remoteDer ?: return failSession(HandoffIssue.PROTOCOL_ERROR, true)
        if (!hasPinLocked()) {
            preferences(context).edit()
                .putString(KEY_PINNED_DER, Base64.encodeToString(der, Base64.NO_WRAP))
                .putString(KEY_PINNED_DEVICE, connectedDeviceId)
                .putString(KEY_PINNED_NAME, remoteName)
                .apply()
        } else {
            preferences(context).edit()
                .putString(KEY_PINNED_DEVICE, connectedDeviceId)
                .putString(KEY_PINNED_NAME, remoteName)
                .apply()
        }
        paired = true
        phase = Phase.READY
        ready = true
        owner = remoteReadyOwner
        currentRevision = remoteReadyRevision
        reconnectAttempt = 0
        backgroundReconnect = false
        handshakeToken++
        updateState(HandoffStatus.READY, pairingCode = sessionKeys?.sas, pairingName = remoteName)
    }

    private fun sendSecureLocked(payload: JSONObject) {
        val keys = sessionKeys ?: return
        if (sendSequence <= 0 || sendSequence == Long.MAX_VALUE) {
            return failSession(HandoffIssue.PROTOCOL_ERROR, true)
        }
        val envelope = HandoffCrypto.encrypt(keys.phoneToMac, sendSequence, payload)
        sendSequence++
        sendPlainLocked(envelope)
    }

    private fun sendPlainLocked(json: JSONObject) {
        val frame = HandoffFrameDecoder.encode(json.toString())
        if (outboundFrames >= MAX_OUTBOUND_FRAMES ||
            outboundBytes + frame.size > MAX_OUTBOUND_BYTES
        ) {
            return failSession(HandoffIssue.PROTOCOL_ERROR, reconnect = true)
        }
        outboundFrames++
        outboundBytes += frame.size
        val chunkSize = (mtu - 3).coerceIn(20, 514)
        var offset = 0
        while (offset < frame.size) {
            val end = (offset + chunkSize).coerceAtMost(frame.size)
            writeQueue.addLast(
                OutboundChunk(
                    bytes = frame.copyOfRange(offset, end),
                    finalChunk = end == frame.size,
                    frameBytes = frame.size,
                ),
            )
            offset = end
        }
        writeNextLocked()
    }

    private fun writeNextLocked() {
        if (writeInFlight) return
        val callbackGatt = gatt ?: return
        val characteristic = rx ?: return
        val chunk = writeQueue.removeFirstOrNull() ?: return
        writeInFlight = true
        inFlightChunk = chunk
        val result = callbackGatt.writeCharacteristic(
            characteristic,
            chunk.bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (result != BluetoothStatusCodes.SUCCESS) {
            writeInFlight = false
            inFlightChunk = null
            failSession(HandoffIssue.CONNECTION_FAILED, reconnect = true)
        } else {
            scheduleWriteTimeoutLocked()
        }
    }

    private fun dispatchClaim(nextOwner: HandoffOwner, revision: Long) {
        val target = listener ?: return
        val sessionGeneration = generation
        mainHandler.post {
            val current = synchronized(lock) {
                listener === target && if (revision < 0) {
                    generation == sessionGeneration && enabled && paired && !ready &&
                        currentRevision == revision && owner == nextOwner
                } else {
                    generation == sessionGeneration && ready &&
                        currentRevision == revision && owner == nextOwner
                }
            }
            if (current) target.onClaim(nextOwner, revision)
        }
    }

    private fun updateState(
        status: HandoffStatus,
        issue: HandoffIssue? = null,
        pairingCode: String? = null,
        pairingName: String? = null,
    ) {
        val context = appContext
        _state.value = MacHandoffState(
            status = status,
            enabled = enabled,
            paired = paired,
            pairedName = context?.let { preferences(it).getString(KEY_PINNED_NAME, null) },
            savedConnection = pinExistsLocked(),
            owner = owner,
            revision = currentRevision,
            devices = discovered.map { (id, discoveredDevice) ->
                HandoffDevice(id, discoveredDevice.advertisedName)
            },
            pairingCode = pairingCode,
            pairingName = pairingName,
            issue = issue,
            transitionMessage = transitionMessage,
            transitionSucceeded = transitionSucceeded,
        )
    }

    private fun publishDevicesLocked() {
        val current = _state.value
        updateState(current.status, current.issue, current.pairingCode, current.pairingName)
    }

    private fun failSession(issue: HandoffIssue?, reconnect: Boolean) {
        closeGattLocked()
        resetSessionLocked()
        updateState(if (issue == null) HandoffStatus.DISCONNECTED else HandoffStatus.ERROR, issue)
        if (reconnect) scheduleReconnectLocked()
    }

    private fun shutdownNetworkingLocked() {
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.removeCallbacks(reconnectRunnable)
        stopScanLocked()
        closeGattLocked()
        resetSessionLocked()
        generation++
    }

    private fun closeGattLocked() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
            // The object is discarded below even if permission changed while connected.
        }
        gatt = null
        rx = null
        tx = null
    }

    private fun resetSessionLocked() {
        ready = false
        owner = HandoffOwner.NONE
        currentRevision = -1L
        phase = Phase.CLOSED
        mtu = 23
        writeQueue.clear()
        writeInFlight = false
        inFlightChunk = null
        outboundFrames = 0
        outboundBytes = 0
        frameDecoder.reset()
        phoneNonce?.fill(0)
        remoteNonce?.fill(0)
        phoneNonce = null
        remoteNonce = null
        remoteDer = null
        remoteName = null
        sessionKeys?.macToPhone?.fill(0)
        sessionKeys?.phoneToMac?.fill(0)
        sessionKeys?.pairing?.fill(0)
        sessionKeys = null
        sendSequence = 1L
        receiveSequence = 1L
        localApproved = false
        remoteReady = false
        remoteReadyOwner = HandoffOwner.NONE
        remoteReadyRevision = -1L
        handshakeToken++
        writeToken++
    }

    private fun stopScanLocked() {
        mainHandler.removeCallbacks(scanTimeout)
        val context = appContext
        val callback = scannerCallback
        scannerCallback = null
        if (context != null && callback != null) {
            try {
                context.getSystemService(BluetoothManager::class.java)
                    ?.adapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: SecurityException) {
                // Stopping is best effort after permission revocation.
            }
        }
    }

    private fun scheduleReconnectLocked() {
        if (!enabled || listener == null) return
        val delay = if (reconnectAttempt < FAST_RECONNECT_ATTEMPTS) {
            backgroundReconnect = false
            (1L shl reconnectAttempt.coerceAtMost(4)) * 1_000L
        } else {
            if (!paired) return
            backgroundReconnect = true
            BACKGROUND_RECONNECT_MS
        }
        if (reconnectAttempt < FAST_RECONNECT_ATTEMPTS) reconnectAttempt++
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun expectedDiscoveryHintLocked(): String? =
        pinnedDerLocked()?.let(HandoffCrypto::discoveryHint)

    private fun scheduleHandshakeTimeoutLocked(sessionGeneration: Long) {
        val token = ++handshakeToken
        mainHandler.postDelayed({
            synchronized(lock) {
                if (token == handshakeToken && generation == sessionGeneration &&
                    !ready && phase != Phase.CLOSED
                ) {
                    failSession(HandoffIssue.HANDSHAKE_TIMED_OUT, reconnect = true)
                }
            }
        }, HANDSHAKE_TIMEOUT_MS)
    }

    private fun scheduleWriteTimeoutLocked() {
        val token = ++writeToken
        val sessionGeneration = generation
        mainHandler.postDelayed({
            synchronized(lock) {
                if (token == writeToken && generation == sessionGeneration && writeInFlight) {
                    failSession(HandoffIssue.CONNECTION_FAILED, reconnect = true)
                }
            }
        }, WRITE_TIMEOUT_MS)
    }

    private fun hasPinLocked(): Boolean = pinnedDerLocked() != null

    private fun pinExistsLocked(): Boolean {
        val context = appContext ?: return false
        return preferences(context).contains(KEY_PINNED_DER)
    }

    private fun pinnedDerLocked(): ByteArray? {
        val context = appContext ?: return null
        val value = preferences(context).getString(KEY_PINNED_DER, null) ?: return null
        return try {
            HandoffCrypto.decodePublicDer(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun hasPermissions(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun parseOwner(value: String, allowNone: Boolean): HandoffOwner = when (value) {
        "mac" -> HandoffOwner.MAC
        "phone" -> HandoffOwner.PHONE
        "none" -> if (allowNone) HandoffOwner.NONE else error("invalid owner")
        else -> error("invalid owner")
    }

    private fun sanitizeName(value: String): String =
        value.filter { it >= ' ' && it != '\u007f' }.take(64).ifBlank { "Mac" }

    private fun sanitizeMessage(value: String): String =
        value.filter { it >= ' ' && it != '\u007f' }.take(120)

    private enum class Phase { CLOSED, WAITING_HELLO, WAITING_APPROVAL, WAITING_READY, READY }

    private const val PREFERENCES = "mac_handoff_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PINNED_DER = "pinned_der"
    private const val KEY_PINNED_DEVICE = "pinned_device"
    private const val KEY_PINNED_NAME = "pinned_name"
    private const val HANDSHAKE_TIMEOUT_MS = 30_000L
    private const val SCAN_TIMEOUT_MS = 12_000L
    private const val FAST_RECONNECT_ATTEMPTS = 5
    private const val BACKGROUND_RECONNECT_MS = 60_000L
    private const val MAX_DISCOVERED_DEVICES = 32
    private const val MAX_OUTBOUND_FRAMES = 16
    private const val MAX_OUTBOUND_BYTES = 64 * 1024
    private const val WRITE_TIMEOUT_MS = 5_000L
    private val SERVICE_UUID: UUID = UUID.fromString("739d1a20-6c32-4b18-a8e5-bdc4010a2000")
    private val RX_UUID: UUID = UUID.fromString("739d1a20-6c32-4b18-a8e5-bdc4010a2001")
    private val TX_UUID: UUID = UUID.fromString("739d1a20-6c32-4b18-a8e5-bdc4010a2002")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
