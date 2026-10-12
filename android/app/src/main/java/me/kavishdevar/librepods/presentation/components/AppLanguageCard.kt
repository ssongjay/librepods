package me.kavishdevar.librepods.presentation.components

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R

@Composable
fun AppLanguageCard() {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    fun selectLanguage(tag: String) {
        if (language != tag) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(tag)
        }
    }

    StyledList(
        title = stringResource(R.string.ko_runtime_language_title),
        description = stringResource(R.string.ko_runtime_language_description)
    ) {
        StyledListItem(
            name = stringResource(R.string.ko_runtime_language_korean),
            onClick = { selectLanguage("ko") },
            trailingContent = {
                RadioButton(selected = language == "ko", onClick = { selectLanguage("ko") })
            }
        )
        StyledListItem(
            name = stringResource(R.string.ko_runtime_language_english),
            onClick = { selectLanguage("en") },
            trailingContent = {
                RadioButton(selected = language == "en", onClick = { selectLanguage("en") })
            }
        )
    }
}
