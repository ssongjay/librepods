package me.kavishdevar.librepods.presentation.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.handoff.HandoffIssue
import me.kavishdevar.librepods.data.handoff.HandoffOwner
import me.kavishdevar.librepods.data.handoff.HandoffStatus
import me.kavishdevar.librepods.data.handoff.MacHandoffClient
import me.kavishdevar.librepods.data.handoff.AirPodsAssociation
import me.kavishdevar.librepods.data.handoff.AirPodsAssociationIssue
import me.kavishdevar.librepods.data.handoff.AirPodsAssociationUi
import me.kavishdevar.librepods.services.AirPodsService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun MacHandoffCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by MacHandoffClient.state.collectAsState()
    val association by AirPodsAssociation.state.collectAsState()
    var showForget by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    val transitionOutcome = state.transitionSucceeded?.let { succeeded ->
        if (succeeded) {
            stringResource(R.string.handoff_transition_success)
        } else {
            stringResource(R.string.handoff_transition_failure)
        }
    }
    val associationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        AirPodsAssociation.pickerReturned(context)
    }
    val associationUi = remember(associationLauncher) {
        AirPodsAssociationUi { intentSender ->
            associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    LaunchedEffect(context.applicationContext) {
        MacHandoffClient.initialize(context.applicationContext)
        AirPodsAssociation.refresh(context.applicationContext)
    }

    DisposableEffect(lifecycleOwner, context.applicationContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                AirPodsAssociation.refresh(context.applicationContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier) {
        StyledToggle(
            title = stringResource(R.string.handoff_title),
            label = stringResource(R.string.handoff_enable),
            description = stringResource(R.string.handoff_enable_description),
            checked = state.enabled,
            onCheckedChange = { checked ->
                if (checked) startAirPodsService(context)
                MacHandoffClient.setEnabled(context, checked)
            },
        )

        if (state.enabled) {
            StyledList(
                description = stringResource(R.string.handoff_privacy_description) + "\n" +
                    stringResource(R.string.handoff_detection_note),
            ) {
                StyledListItem(
                    name = stringResource(R.string.handoff_status),
                    description = statusText(state.status, state.issue),
                    onClick = null,
                )
                if (association.required) {
                    StyledListItem(
                        name = stringResource(R.string.handoff_airpods_control_title),
                        description = associationDescription(
                            associated = association.associated,
                            supported = association.supported,
                            pending = association.pending,
                            issue = association.issue,
                        ),
                        onClick = if (
                            association.supported && !association.associated && !association.pending
                        ) {
                            {
                                AirPodsAssociation.request(context, associationUi)
                            }
                        } else {
                            null
                        },
                    )
                } else {
                    StyledListItem(
                        name = stringResource(R.string.handoff_airpods_control_title),
                        description = stringResource(R.string.handoff_airpods_control_fallback),
                        onClick = null,
                    )
                }
                if (state.pairedName != null) {
                    StyledListItem(
                        name = stringResource(R.string.handoff_paired_mac),
                        description = state.pairedName,
                        onClick = null,
                    )
                }
                if (state.owner != HandoffOwner.NONE) {
                    StyledListItem(
                        name = stringResource(R.string.handoff_recent_playback_device),
                        description = ownerText(state.owner),
                        onClick = null,
                    )
                }
                if (state.transitionSucceeded != null) {
                    StyledListItem(
                        name = stringResource(R.string.handoff_transition_result),
                        description = buildString {
                            append(transitionOutcome.orEmpty())
                            state.transitionMessage?.takeIf { it.isNotBlank() }?.let {
                                append(": ")
                                append(it)
                            }
                        },
                        onClick = null,
                    )
                }
                if (state.pairingCode != null) {
                    StyledListItem(
                        name = stringResource(R.string.handoff_session_code),
                        description = state.pairingCode,
                        onClick = null,
                    )
                }
                StyledListItem(
                    name = stringResource(R.string.handoff_scan),
                    description = stringResource(R.string.handoff_scan_description),
                    onClick = {
                        startAirPodsService(context)
                        MacHandoffClient.scan(context)
                    },
                )
                state.devices.forEach { device ->
                    StyledListItem(
                        name = device.name,
                        description = stringResource(R.string.handoff_connect_description),
                        onClick = { MacHandoffClient.connect(context, device.id) },
                    )
                }
                if (state.paired) {
                    StyledListItem(
                        name = stringResource(R.string.handoff_forget),
                        description = stringResource(R.string.handoff_forget_description),
                        onClick = { showForget = true },
                    )
                }
                if (!state.paired || state.status == HandoffStatus.ERROR) {
                    StyledListItem(
                        name = stringResource(R.string.handoff_reset),
                        description = stringResource(R.string.handoff_reset_description),
                        onClick = { showReset = true },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (!state.enabled && (!state.paired || state.status == HandoffStatus.ERROR)) {
        StyledList(description = stringResource(R.string.handoff_reset_description)) {
            StyledListItem(
                name = stringResource(R.string.handoff_reset),
                onClick = { showReset = true },
            )
        }
    }

    if (state.status == HandoffStatus.PAIRING && state.pairingCode != null) {
        AlertDialog(
            onDismissRequest = { MacHandoffClient.rejectPairing() },
            title = { Text(stringResource(R.string.handoff_pairing_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.handoff_pairing_message,
                        state.pairingName ?: stringResource(R.string.handoff_mac_fallback),
                        state.pairingCode ?: "",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = MacHandoffClient::confirmPairing) {
                    Text(stringResource(R.string.handoff_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = MacHandoffClient::rejectPairing) {
                    Text(stringResource(R.string.handoff_reject))
                }
            },
        )
    }

    if (showForget) {
        AlertDialog(
            onDismissRequest = { showForget = false },
            title = { Text(stringResource(R.string.handoff_forget_title)) },
            text = { Text(stringResource(R.string.handoff_forget_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showForget = false
                    MacHandoffClient.forget(context)
                }) { Text(stringResource(R.string.handoff_forget_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showForget = false }) {
                    Text(stringResource(R.string.handoff_cancel))
                }
            },
        )
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(stringResource(R.string.handoff_reset_title)) },
            text = { Text(stringResource(R.string.handoff_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    MacHandoffClient.reset(context)
                }) { Text(stringResource(R.string.handoff_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) {
                    Text(stringResource(R.string.handoff_cancel))
                }
            },
        )
    }
}

@Composable
private fun statusText(status: HandoffStatus, issue: HandoffIssue?): String =
    if (status == HandoffStatus.ERROR && issue != null) {
        when (issue) {
            HandoffIssue.BLUETOOTH_UNAVAILABLE -> stringResource(R.string.handoff_error_bluetooth)
            HandoffIssue.PERMISSION_REQUIRED -> stringResource(R.string.handoff_error_permission)
            HandoffIssue.CONNECTION_FAILED -> stringResource(R.string.handoff_error_connection)
            HandoffIssue.HANDSHAKE_TIMED_OUT -> stringResource(R.string.handoff_error_timeout)
            HandoffIssue.PROTOCOL_ERROR -> stringResource(R.string.handoff_error_protocol)
            HandoffIssue.IDENTITY_UNAVAILABLE -> stringResource(R.string.handoff_error_identity)
        }
    } else {
        when (status) {
            HandoffStatus.DISABLED -> stringResource(R.string.handoff_status_disabled)
            HandoffStatus.DISCONNECTED -> stringResource(R.string.handoff_status_disconnected)
            HandoffStatus.SCANNING -> stringResource(R.string.handoff_status_scanning)
            HandoffStatus.CONNECTING -> stringResource(R.string.handoff_status_connecting)
            HandoffStatus.PAIRING -> stringResource(R.string.handoff_status_pairing)
            HandoffStatus.AUTHENTICATING -> stringResource(R.string.handoff_status_authenticating)
            HandoffStatus.READY -> stringResource(R.string.handoff_status_ready)
            HandoffStatus.ERROR -> stringResource(R.string.handoff_status_error)
        }
    }

@Composable
private fun ownerText(owner: HandoffOwner): String = when (owner) {
    HandoffOwner.NONE -> stringResource(R.string.handoff_owner_none)
    HandoffOwner.MAC -> stringResource(R.string.handoff_owner_mac)
    HandoffOwner.PHONE -> stringResource(R.string.handoff_owner_phone)
}

@Composable
private fun associationDescription(
    associated: Boolean,
    supported: Boolean,
    pending: Boolean,
    issue: AirPodsAssociationIssue?,
): String = when {
    associated -> stringResource(R.string.handoff_airpods_control_approved)
    pending -> stringResource(R.string.handoff_airpods_control_pending)
    !supported || issue == AirPodsAssociationIssue.FEATURE_UNAVAILABLE ->
        stringResource(R.string.handoff_airpods_control_unsupported)
    issue == AirPodsAssociationIssue.AIRPODS_NOT_SELECTED ->
        stringResource(R.string.handoff_airpods_control_no_selection)
    issue == AirPodsAssociationIssue.AIRPODS_NOT_BONDED ->
        stringResource(R.string.handoff_airpods_control_not_bonded)
    issue == AirPodsAssociationIssue.REQUEST_FAILED ->
        stringResource(R.string.handoff_airpods_control_failed)
    else -> stringResource(R.string.handoff_airpods_control_action)
}

private fun startAirPodsService(context: Context) {
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
        PackageManager.PERMISSION_GRANTED
    ) return
    try {
        context.startForegroundService(Intent(context, AirPodsService::class.java))
    } catch (_: RuntimeException) {
        // The handoff card still reports Bluetooth state if Android defers service startup.
    }
}
