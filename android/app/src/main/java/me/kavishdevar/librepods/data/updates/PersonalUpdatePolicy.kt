/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.data.updates

import java.net.URI
import java.net.URISyntaxException

/** Pure release-contract checks shared by the Android updater and standalone verification. */
internal object PersonalUpdatePolicy {
    const val APK_FILE_NAME = "LibrePods-My.apk"
    const val METADATA_LIMIT_BYTES = 1L * 1024L * 1024L
    const val APK_LIMIT_BYTES = 150L * 1024L * 1024L

    private const val RELEASE_DOWNLOAD_BASE_URL =
        "https://github.com/ssongjay/librepods/releases/download"
    private val personalTag = Regex("my-v([1-9][0-9]*)")
    private val personalReleaseName = Regex(
        """LibrePods My ((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))""",
    )
    private val sha256Digest = Regex("sha256:([0-9a-fA-F]{64})")
    private val allowedAssetHosts = setOf(
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    @JvmStatic
    fun versionCode(tagName: String): Long? =
        personalTag.matchEntire(tagName)?.groupValues?.get(1)?.toLongOrNull()

    /** The release title supplies the display version; the tag remains the update counter. */
    @JvmStatic
    fun displayVersion(releaseName: String): String? =
        personalReleaseName.matchEntire(releaseName)?.groupValues?.get(1)

    @JvmStatic
    fun isStrictlyNewer(tagName: String, currentVersionCode: Long): Boolean =
        versionCode(tagName)?.let { it > currentVersionCode } == true

    @JvmStatic
    fun digestHex(digest: String): String? =
        sha256Digest.matchEntire(digest)?.groupValues?.get(1)?.lowercase()

    @JvmStatic
    fun isExpectedAsset(
        tagName: String,
        assetName: String,
        downloadUrl: String,
        size: Long,
    ): Boolean =
        versionCode(tagName) != null &&
            assetName == APK_FILE_NAME &&
            size in 1..APK_LIMIT_BYTES &&
            downloadUrl == expectedAssetUrl(tagName)

    @JvmStatic
    fun isAllowedAssetUrl(url: String, tagName: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null ||
            (uri.port != -1 && uri.port != 443)
        ) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return when (host) {
            "github.com" -> uri.toASCIIString() == expectedAssetUrl(tagName)
            in allowedAssetHosts -> true
            else -> false
        }
    }

    @JvmStatic
    fun expectedAssetUrl(tagName: String): String =
        "$RELEASE_DOWNLOAD_BASE_URL/$tagName/$APK_FILE_NAME"
}
