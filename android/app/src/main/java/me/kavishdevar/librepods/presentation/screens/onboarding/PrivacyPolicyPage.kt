package me.kavishdevar.librepods.presentation.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.R

@Composable
fun PrivacyPolicyPage(
    onForward: () -> Unit
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier.background(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(42.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.ko_ui_privacy_last_updated),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_overview),
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_overview_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_local_data_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_software_updates),
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_software_updates_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_mac_handoff),
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_mac_handoff_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_third_party_services),
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_contact_methods_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_email),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_email_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_email_edit_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Discord",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_discord_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_discord_visibility_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_github_issues),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_github_prefill_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    stringResource(R.string.ko_ui_privacy_github_item_version),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.ko_ui_privacy_github_item_device),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.ko_ui_privacy_github_item_android),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.ko_ui_privacy_github_item_source),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = stringResource(R.string.ko_ui_privacy_github_submit_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_payments), style = MaterialTheme.typography.titleLarge
            )

            if (BuildConfig.PLAY_BUILD) {
                Text(
                    text = "Google Play", style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = stringResource(R.string.ko_ui_privacy_google_play_body),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = stringResource(R.string.ko_ui_privacy_google_play_details_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "GitHub Sponsors", style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = stringResource(R.string.ko_ui_privacy_github_sponsors_body),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = stringResource(R.string.ko_ui_privacy_github_sponsors_details_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = stringResource(R.string.ko_ui_privacy_contact), style = MaterialTheme.typography.titleLarge
            )

            Text(
                text = stringResource(R.string.ko_ui_privacy_contact_body),
                style = MaterialTheme.typography.bodyMedium
            )

            Button(
                onClick = onForward,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.i_agree),
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
