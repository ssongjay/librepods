/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.presentation.components

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.updates.PersonalUpdateError
import me.kavishdevar.librepods.data.updates.PersonalUpdateRetry
import me.kavishdevar.librepods.data.updates.PersonalUpdateState
import me.kavishdevar.librepods.data.updates.PersonalUpdater

@Composable
fun PersonalUpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updater = remember(context.applicationContext) {
        PersonalUpdater(context.applicationContext)
    }
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val actionInFlight = remember { mutableStateOf(false) }

    fun launchOnce(action: suspend () -> Unit) {
        if (actionInFlight.value) return
        actionInFlight.value = true
        scope.launch {
            try {
                action()
            } finally {
                actionInFlight.value = false
            }
        }
    }

    val action: (() -> Unit)? = when (state) {
        PersonalUpdateState.Idle,
        PersonalUpdateState.UpToDate,
        -> {
            { launchOnce { updater.checkForUpdate() } }
        }
        is PersonalUpdateState.Available -> {
            { launchOnce { updater.openReleaseInBrowser() } }
        }
        is PersonalUpdateState.Error -> {
            { launchOnce { updater.retry() } }
        }
        PersonalUpdateState.Checking -> null
    }

    val title = when (state) {
        PersonalUpdateState.Idle -> stringResource(R.string.personal_update_current_version)
        PersonalUpdateState.Checking -> stringResource(R.string.personal_update_checking)
        PersonalUpdateState.UpToDate -> stringResource(R.string.personal_update_up_to_date)
        is PersonalUpdateState.Available -> stringResource(R.string.personal_update_available)
        is PersonalUpdateState.Error -> stringResource(R.string.personal_update_failed)
    }

    val description = when (val currentState = state) {
        PersonalUpdateState.Idle -> stringResource(
            R.string.personal_update_idle_description,
            BuildConfig.VERSION_NAME,
        )
        PersonalUpdateState.Checking -> stringResource(R.string.personal_update_checking_description)
        PersonalUpdateState.UpToDate -> stringResource(
            R.string.personal_update_up_to_date_description,
            BuildConfig.VERSION_NAME,
        )
        is PersonalUpdateState.Available -> stringResource(
            R.string.personal_update_available_description,
            currentState.versionCode,
        )
        is PersonalUpdateState.Error -> errorDescription(currentState.reason)
    }

    val actionLabel = when (val currentState = state) {
        PersonalUpdateState.Idle,
        PersonalUpdateState.UpToDate,
        -> stringResource(R.string.personal_update_check_button)
        is PersonalUpdateState.Available -> stringResource(R.string.personal_update_browser_button)
        is PersonalUpdateState.Error -> when (currentState.retry) {
            PersonalUpdateRetry.CHECK -> stringResource(R.string.personal_update_retry_check_button)
            PersonalUpdateRetry.OPEN_BROWSER -> stringResource(
                R.string.personal_update_retry_browser_button,
            )
        }
        PersonalUpdateState.Checking -> null
    }

    StyledList(
        modifier = modifier,
        title = stringResource(R.string.personal_update_section_title),
        description = stringResource(R.string.personal_update_manual_description),
    ) {
        StyledListItem(
            name = title,
            description = description,
            onClick = action,
            enabled = action != null,
            trailingContent = {
                if (actionLabel != null) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            },
        )
    }
}

@Composable
private fun errorDescription(error: PersonalUpdateError): String = when (error) {
    PersonalUpdateError.NO_RELEASE -> stringResource(R.string.personal_update_error_no_release)
    PersonalUpdateError.RATE_LIMITED -> stringResource(R.string.personal_update_error_rate_limited)
    PersonalUpdateError.OFFLINE -> stringResource(R.string.personal_update_error_offline)
    PersonalUpdateError.INVALID_RELEASE -> stringResource(R.string.personal_update_error_invalid_release)
    PersonalUpdateError.BROWSER_UNAVAILABLE -> stringResource(
        R.string.personal_update_error_browser,
    )
}
