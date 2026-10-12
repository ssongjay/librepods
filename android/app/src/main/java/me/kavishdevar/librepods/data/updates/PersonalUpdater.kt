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
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val tagName: String,
    val digest: ByteArray,
    val downloadUrl: URL,
    val assetSize: Long,
)

internal enum class PersonalUpdateError {
    NO_RELEASE,
    RATE_LIMITED,
    OFFLINE,
    INVALID_RELEASE,
    DOWNLOAD_FAILED,
    VERIFICATION_FAILED,
    INSTALLER_UNAVAILABLE,
}

internal enum class PersonalUpdateRetry {
    CHECK,
    DOWNLOAD,
    INSTALL,
}

internal sealed interface PersonalUpdateState {
    data object Idle : PersonalUpdateState
    data object Checking : PersonalUpdateState
    data object UpToDate : PersonalUpdateState
    data class Available(val versionCode: Long) : PersonalUpdateState
    data class Downloading(val versionCode: Long, val percent: Int) : PersonalUpdateState
    data class Verifying(val versionCode: Long) : PersonalUpdateState
    data class Ready(val versionCode: Long) : PersonalUpdateState
    data class PermissionRequired(val versionCode: Long) : PersonalUpdateState
    data class Error(
        val reason: PersonalUpdateError,
        val retry: PersonalUpdateRetry,
    ) : PersonalUpdateState
}

/**
 * A deliberately small updater for the personal LibrePods build.
 *
 * It performs network work only after an explicit user action. A release is accepted only when
 * its tag, asset name, digest, package name, version code, and signing certificate all match the
 * personal-build contract.
 */
internal class PersonalUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val operationMutex = Mutex()
    private val updatesDirectory = File(appContext.filesDir, UPDATES_DIRECTORY)
    private val downloadedApk = File(updatesDirectory, APK_FILE_NAME)
    private val temporaryApk = File(updatesDirectory, TEMP_APK_FILE_NAME)
    private val pendingRecord = File(updatesDirectory, PENDING_RECORD_FILE_NAME)

    private val _state = MutableStateFlow<PersonalUpdateState>(PersonalUpdateState.Idle)
    val state: StateFlow<PersonalUpdateState> = _state.asStateFlow()

    private var availableRelease: PersonalRelease? = null

    suspend fun restorePendingUpdate() = operationMutex.withLock {
        if (_state.value != PersonalUpdateState.Idle) return@withLock

        withContext(Dispatchers.IO) {
            temporaryApk.delete()
            val hadPendingFiles = pendingRecord.exists() || downloadedApk.exists()
            val release = readPendingRecord()
            if (release == null) {
                if (hadPendingFiles) clearPendingFiles()
                return@withContext
            }
            if (verifyApk(downloadedApk, release)) {
                availableRelease = release
                _state.value = PersonalUpdateState.Ready(release.versionCode)
            } else {
                clearPendingFiles()
            }
        }
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
                withContext(Dispatchers.IO) { clearPendingFiles() }
                _state.value = PersonalUpdateState.UpToDate
            } else {
                availableRelease = release
                _state.value = PersonalUpdateState.Available(release.versionCode)
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

    suspend fun downloadUpdate() = operationMutex.withLock {
        val currentState = _state.value
        if (currentState !is PersonalUpdateState.Available &&
            (currentState !is PersonalUpdateState.Error ||
                currentState.retry != PersonalUpdateRetry.DOWNLOAD)
        ) {
            return@withLock
        }
        val release = availableRelease ?: run {
            _state.value = PersonalUpdateState.Error(
                PersonalUpdateError.INVALID_RELEASE,
                PersonalUpdateRetry.CHECK,
            )
            return@withLock
        }

        try {
            withContext(Dispatchers.IO) { downloadAndVerify(release) }
            _state.value = PersonalUpdateState.Ready(release.versionCode)
        } catch (cancelled: CancellationException) {
            clearPendingFiles()
            _state.value = PersonalUpdateState.Available(release.versionCode)
            throw cancelled
        } catch (failure: UpdateFailure) {
            clearPendingFiles()
            _state.value = PersonalUpdateState.Error(failure.reason, PersonalUpdateRetry.DOWNLOAD)
        } catch (_: IOException) {
            clearPendingFiles()
            _state.value = PersonalUpdateState.Error(
                PersonalUpdateError.DOWNLOAD_FAILED,
                PersonalUpdateRetry.DOWNLOAD,
            )
        }
    }

    suspend fun installUpdate() = operationMutex.withLock {
        val currentState = _state.value
        if (currentState !is PersonalUpdateState.Ready &&
            currentState !is PersonalUpdateState.PermissionRequired &&
            (currentState !is PersonalUpdateState.Error ||
                currentState.retry != PersonalUpdateRetry.INSTALL)
        ) {
            return@withLock
        }
        val release = availableRelease ?: withContext(Dispatchers.IO) { readPendingRecord() }
        if (release == null) {
            _state.value = PersonalUpdateState.Error(
                PersonalUpdateError.VERIFICATION_FAILED,
                PersonalUpdateRetry.CHECK,
            )
            return@withLock
        }

        _state.value = PersonalUpdateState.Verifying(release.versionCode)
        val verified = withContext(Dispatchers.IO) { verifyApk(downloadedApk, release) }
        if (!verified) {
            withContext(Dispatchers.IO) { clearPendingFiles() }
            _state.value = PersonalUpdateState.Error(
                PersonalUpdateError.VERIFICATION_FAILED,
                PersonalUpdateRetry.CHECK,
            )
            return@withLock
        }

        availableRelease = release
        if (!packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                appContext.startActivity(permissionIntent)
                _state.value = PersonalUpdateState.PermissionRequired(release.versionCode)
            } catch (_: RuntimeException) {
                _state.value = PersonalUpdateState.Error(
                    PersonalUpdateError.INSTALLER_UNAVAILABLE,
                    PersonalUpdateRetry.INSTALL,
                )
            }
            return@withLock
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.provider",
                downloadedApk,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(installIntent)
            _state.value = PersonalUpdateState.Ready(release.versionCode)
        } catch (_: RuntimeException) {
            _state.value = PersonalUpdateState.Error(
                PersonalUpdateError.INSTALLER_UNAVAILABLE,
                PersonalUpdateRetry.INSTALL,
            )
        }
    }

    suspend fun retry() {
        when ((state.value as? PersonalUpdateState.Error)?.retry) {
            PersonalUpdateRetry.CHECK -> checkForUpdate()
            PersonalUpdateRetry.DOWNLOAD -> downloadUpdate()
            PersonalUpdateRetry.INSTALL -> installUpdate()
            null -> Unit
        }
    }

    private suspend fun fetchLatestRelease(): PersonalRelease? {
        val connection = openApiConnection()
        try {
            return connection.runCancellable {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> parseRelease(
                        connection.inputStream.readBytesBounded(METADATA_LIMIT_BYTES),
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
            val size = asset.getLong("size")
            val assetName = asset.getString("name")
            val downloadUrl = asset.getString("browser_download_url")
            if (!PersonalUpdatePolicy.isExpectedAsset(tagName, assetName, downloadUrl, size)) {
                throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
            }
            val digestHex = PersonalUpdatePolicy.digestHex(asset.getString("digest"))
                ?: throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
            val digest = digestHex.hexToBytes()
            val expectedUrl = expectedAssetUrl(tagName)
            val actualUrl = URL(downloadUrl)
            if (actualUrl.toExternalForm() != expectedUrl.toExternalForm()) {
                throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
            }

            return PersonalRelease(versionCode, tagName, digest, actualUrl, size)
        } catch (failure: UpdateFailure) {
            throw failure
        } catch (_: JSONException) {
            throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
        } catch (_: IllegalArgumentException) {
            throw UpdateFailure(PersonalUpdateError.INVALID_RELEASE)
        }
    }

    private suspend fun downloadAndVerify(release: PersonalRelease) {
        updatesDirectory.mkdirs()
        temporaryApk.delete()

        val connection = openAssetConnection(release)
        try {
            val coroutineContext = currentCoroutineContext()
            connection.runCancellable {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw UpdateFailure(PersonalUpdateError.DOWNLOAD_FAILED)
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > APK_LIMIT_BYTES ||
                    (contentLength >= 0 && contentLength != release.assetSize)
                ) {
                    throw UpdateFailure(PersonalUpdateError.DOWNLOAD_FAILED)
                }

                val messageDigest = MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L
                connection.inputStream.buffered().use { input ->
                    FileOutputStream(temporaryApk).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            if (totalBytes > APK_LIMIT_BYTES || totalBytes > release.assetSize) {
                                throw UpdateFailure(PersonalUpdateError.DOWNLOAD_FAILED)
                            }
                            messageDigest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val percent = ((totalBytes * 100L) / release.assetSize)
                                .toInt()
                                .coerceIn(0, 100)
                            _state.value = PersonalUpdateState.Downloading(
                                release.versionCode,
                                percent,
                            )
                        }
                    }
                }

                if (totalBytes != release.assetSize ||
                    !MessageDigest.isEqual(messageDigest.digest(), release.digest)
                ) {
                    throw UpdateFailure(PersonalUpdateError.VERIFICATION_FAILED)
                }
                _state.value = PersonalUpdateState.Verifying(release.versionCode)
                if (!verifyApkMetadata(temporaryApk, release.versionCode)) {
                    throw UpdateFailure(PersonalUpdateError.VERIFICATION_FAILED)
                }

                Files.move(
                    temporaryApk.toPath(),
                    downloadedApk.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                writePendingRecord(release)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyApk(file: File, release: PersonalRelease): Boolean {
        if (!file.isFile || file.length() != release.assetSize || file.length() > APK_LIMIT_BYTES) {
            return false
        }
        val actualDigest = file.inputStream().buffered().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest()
        }
        return MessageDigest.isEqual(actualDigest, release.digest) &&
            verifyApkMetadata(file, release.versionCode)
    }

    private fun verifyApkMetadata(file: File, expectedVersionCode: Long): Boolean {
        val flags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
        )
        val archiveInfo = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return false
        if (archiveInfo.packageName != appContext.packageName ||
            archiveInfo.longVersionCode != expectedVersionCode ||
            archiveInfo.longVersionCode <= BuildConfig.VERSION_CODE.toLong()
        ) {
            return false
        }

        val installedInfo = try {
            packageManager.getPackageInfo(appContext.packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        val archiveSigners = signerDigests(archiveInfo)
        return archiveSigners.isNotEmpty() && archiveSigners == signerDigests(installedInfo)
    }

    private fun signerDigests(packageInfo: PackageInfo): Set<String> =
        packageInfo.signingInfo?.apkContentsSigners
            ?.map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .toHex()
            }
            ?.toSet()
            .orEmpty()

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

    private suspend fun openAssetConnection(release: PersonalRelease): HttpsURLConnection {
        var url = release.downloadUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            validateAssetUrl(url, release)
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val responseCode = connection.runCancellable { connection.responseCode }
            if (responseCode !in REDIRECT_CODES) return connection

            if (redirectCount == MAX_REDIRECTS) {
                connection.disconnect()
                throw UpdateFailure(PersonalUpdateError.DOWNLOAD_FAILED)
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                throw UpdateFailure(PersonalUpdateError.DOWNLOAD_FAILED)
            }
            url = URL(url, location)
        }
        throw UpdateFailure(PersonalUpdateError.DOWNLOAD_FAILED)
    }

    private fun validateAssetUrl(url: URL, release: PersonalRelease) {
        if (!PersonalUpdatePolicy.isAllowedAssetUrl(url.toExternalForm(), release.tagName)) {
            throw UpdateFailure(PersonalUpdateError.DOWNLOAD_FAILED)
        }
    }

    private fun readPendingRecord(): PersonalRelease? {
        if (!pendingRecord.isFile || pendingRecord.length() !in 1..PENDING_RECORD_LIMIT_BYTES) {
            return null
        }
        return try {
            val json = JSONObject(pendingRecord.readText(Charsets.UTF_8))
            val tagName = json.getString("tagName")
            val versionCode = PersonalUpdatePolicy.versionCode(tagName) ?: return null
            val storedVersionCode = json.getLong("versionCode")
            if (versionCode != storedVersionCode || versionCode <= BuildConfig.VERSION_CODE.toLong()) {
                return null
            }
            val digestMatch = HEX_DIGEST.matchEntire(json.getString("digest")) ?: return null
            if (!downloadedApk.isFile || downloadedApk.length() !in 1..APK_LIMIT_BYTES) return null
            PersonalRelease(
                versionCode = versionCode,
                tagName = tagName,
                digest = digestMatch.value.hexToBytes(),
                downloadUrl = expectedAssetUrl(tagName),
                assetSize = downloadedApk.length(),
            )
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    private fun writePendingRecord(release: PersonalRelease) {
        updatesDirectory.mkdirs()
        val json = JSONObject()
            .put("versionCode", release.versionCode)
            .put("tagName", release.tagName)
            .put("digest", release.digest.toHex())
        pendingRecord.writeText(json.toString(), Charsets.UTF_8)
    }

    private fun clearPendingFiles() {
        temporaryApk.delete()
        downloadedApk.delete()
        pendingRecord.delete()
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

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray()

    private class UpdateFailure(val reason: PersonalUpdateError) : IOException()

    private companion object {
        const val RELEASE_API_URL =
            "https://api.github.com/repos/ssongjay/librepods/releases/latest"
        const val APK_FILE_NAME = PersonalUpdatePolicy.APK_FILE_NAME
        const val TEMP_APK_FILE_NAME = "LibrePods-My.tmp.apk"
        const val PENDING_RECORD_FILE_NAME = "pending.json"
        const val UPDATES_DIRECTORY = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val USER_AGENT = "LibrePods-My-Updater"
        const val METADATA_LIMIT_BYTES = PersonalUpdatePolicy.METADATA_LIMIT_BYTES
        const val APK_LIMIT_BYTES = PersonalUpdatePolicy.APK_LIMIT_BYTES
        const val PENDING_RECORD_LIMIT_BYTES = 16L * 1024L
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_REDIRECTS = 5

        val HEX_DIGEST = Regex("[0-9a-fA-F]{64}")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun expectedAssetUrl(tagName: String): URL =
            URL(PersonalUpdatePolicy.expectedAssetUrl(tagName))
    }
}
