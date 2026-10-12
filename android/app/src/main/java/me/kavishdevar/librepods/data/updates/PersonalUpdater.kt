/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.data.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.BuildConfig
import org.json.JSONException
import org.json.JSONObject

internal data class PersonalRelease(
    val versionCode: Long,
    val versionName: String,
    val tagName: String,
    val downloadUrl: String,
)

internal enum class PersonalUpdateError {
    NO_RELEASE,
    RATE_LIMITED,
    OFFLINE,
    INVALID_RELEASE,
    BROWSER_UNAVAILABLE,
}

internal enum class PersonalUpdateRetry {
    CHECK,
    OPEN_BROWSER,
}

internal sealed interface PersonalUpdateState {
    data object Idle : PersonalUpdateState
    data object Checking : PersonalUpdateState
    data object UpToDate : PersonalUpdateState
    data class Available(val versionName: String) : PersonalUpdateState
    data class Error(
        val reason: PersonalUpdateError,
        val retry: PersonalUpdateRetry,
    ) : PersonalUpdateState
}

/**
 * Checks the personal GitHub release metadata after an explicit user action.
 *
 * APK downloading and installation stay with the browser and Android package installer. The app
 * only opens the exact canonical asset URL accepted by [PersonalUpdatePolicy].
 */
internal class PersonalUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()

    private val _state = MutableStateFlow<PersonalUpdateState>(PersonalUpdateState.Idle)
    val state: StateFlow<PersonalUpdateState> = _state.asStateFlow()

    private var availableRelease: PersonalRelease? = null

    init {
        cleanLegacyUpdaterFiles()
    }

    suspend fun checkForUpdate() = operationMutex.withLock {
        val currentState = _state.value
        if (currentState != PersonalUpdateState.Idle &&
            currentState != PersonalUpdateState.UpToDate &&
            (currentState !is PersonalUpdateState.Error ||
                currentState.retry != PersonalUpdateRetry.CHECK)
        ) {
            return@withLock
        }

        _state.value = PersonalUpdateState.Checking
        try {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            if (release == null) {
                availableRelease = null
                _state.value = PersonalUpdateState.UpToDate
            } else {
                availableRelease = release
                _state.value = PersonalUpdateState.Available(release.versionName)
            }
        } catch (cancelled: CancellationException) {
            _state.value = PersonalUpdateState.Idle
            throw cancelled
        } catch (failure: UpdateFailure) {
            _state.value = PersonalUpdateState.Error(failure.reason, PersonalUpdateRetry.CHECK)
        } catch (_: IOException) {
            _state.value = PersonalUpdateState.Error(
                PersonalUpdateError.OFFLINE,
                PersonalUpdateRetry.CHECK,
            )
        }
    }

    suspend fun openReleaseInBrowser() = operationMutex.withLock {
        val currentState = _state.value
        if (currentState !is PersonalUpdateState.Available &&
            (currentState !is PersonalUpdateState.Error ||
                currentState.retry != PersonalUpdateRetry.OPEN_BROWSER)
        ) {
            return@withLock
        }

        val release = availableRelease
        if (release == null ||
            !PersonalUpdatePolicy.isStrictlyNewer(
                release.tagName,
                BuildConfig.VERSION_CODE.toLong(),
            ) ||
            !PersonalUpdatePolicy.isAllowedAssetUrl(release.downloadUrl, release.tagName) ||
            release.downloadUrl != PersonalUpdatePolicy.expectedAssetUrl(release.tagName)
        ) {
            availableRelease = null
            _state.value = PersonalUpdateState.Error(
                PersonalUpdateError.INVALID_RELEASE,
                PersonalUpdateRetry.CHECK,
            )
            return@withLock
        }

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            appContext.startActivity(browserIntent)
            _state.value = PersonalUpdateState.Available(release.versionName)
        } catch (_: RuntimeException) {
            _state.value = PersonalUpdateState.Error(
                PersonalUpdateError.BROWSER_UNAVAILABLE,
                PersonalUpdateRetry.OPEN_BROWSER,
            )
        }
    }

    suspend fun retry() {
        when ((state.value as? PersonalUpdateState.Error)?.retry) {
            PersonalUpdateRetry.CHECK -> checkForUpdate()
            PersonalUpdateRetry.OPEN_BROWSER -> openReleaseInBrowser()
            null -> Unit
        }
    }

    private suspend fun fetchLatestRelease(): PersonalRelease? {
        val connection = openApiConnection()
        try {
            return connection.runCancellable {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> parseRelease(
                        connection.inputStream.readBytesBounded(
                            PersonalUpdatePolicy.METADATA_LIMIT_BYTES,
                        ),
                    )
                    HttpURLConnection.HTTP_NOT_FOUND -> throw UpdateFailure(
                        PersonalUpdateError.NO_RELEASE,
                    )
                    403, 429 -> throw UpdateFailure(PersonalUpdateError.RATE_LIMITED)
                    else -> throw UpdateFailure(PersonalUpdateError.OFFLINE)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(bytes: ByteArray): PersonalRelease? {
        try {
            val release = JSONObject(bytes.toString(Charsets.UTF_8))
            if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) {
                throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
            }

            val tagName = release.getString("tag_name")
            val versionCode = PersonalUpdatePolicy.versionCode(tagName)
                ?: throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
            if (!PersonalUpdatePolicy.isStrictlyNewer(
                    tagName,
                    BuildConfig.VERSION_CODE.toLong(),
                )
            ) return null

            val assets = release.getJSONArray("assets")
            val matchingAssets = buildList {
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    if (asset.optString("name") == PersonalUpdatePolicy.APK_FILE_NAME) add(asset)
                }
            }
            if (matchingAssets.size != 1) {
                throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
            }

            val asset = matchingAssets.single()
            val assetName = asset.getString("name")
            val downloadUrl = asset.getString("browser_download_url")
            val size = asset.getLong("size")
            if (!PersonalUpdatePolicy.isExpectedAsset(tagName, assetName, downloadUrl, size) ||
                !PersonalUpdatePolicy.isAllowedAssetUrl(downloadUrl, tagName) ||
                PersonalUpdatePolicy.digestHex(asset.getString("digest")) == null
            ) {
                throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
            }

            val versionName = PersonalUpdatePolicy.displayVersion(release.getString("name"))
                ?: throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
            return PersonalRelease(versionCode, versionName, tagName, downloadUrl)
        } catch (failure: UpdateFailure) {
            throw failure
        } catch (_: JSONException) {
            throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
        } catch (_: IllegalArgumentException) {
            throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
        }
    }

    private fun openApiConnection(): HttpsURLConnection {
        val url = URL(RELEASE_API_URL)
        return (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private suspend fun <T> HttpsURLConnection.runCancellable(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { disconnect() }
            try {
                continuation.resumeWith(Result.success(block()))
            } catch (failure: Throwable) {
                continuation.resumeWith(Result.failure(failure))
            }
        }

    private fun cleanLegacyUpdaterFiles() {
        val directory = File(appContext.filesDir, LEGACY_UPDATES_DIRECTORY)
        File(directory, LEGACY_APK_FILE_NAME).delete()
        File(directory, LEGACY_TEMP_APK_FILE_NAME).delete()
        File(directory, LEGACY_PENDING_RECORD_FILE_NAME).delete()
        directory.delete() // Only succeeds when no unrelated files remain.
    }

    private class UpdateFailure(val reason: PersonalUpdateError) : IOException()

    private companion object {
        const val RELEASE_API_URL =
            "https://api.github.com/repos/ssongjay/librepods/releases/latest"
        const val USER_AGENT = "LibrePods-My-Updater"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val LEGACY_UPDATES_DIRECTORY = "updates"
        const val LEGACY_APK_FILE_NAME = "LibrePods-My.apk"
        const val LEGACY_TEMP_APK_FILE_NAME = "LibrePods-My.tmp.apk"
        const val LEGACY_PENDING_RECORD_FILE_NAME = "pending.json"
    }
}
