package me.kavishdevar.librepods.utils

import android.media.AudioAttributes
import android.media.AudioPlaybackConfiguration

/** Tracks new active media players without claiming audio on startup or playback stop. */
internal class HandoffPlaybackObserver(private val onStart: () -> Unit) {
    private var previous = emptySet<AudioPlaybackConfiguration>()
    private var seeded = false

    fun seed(configurations: List<AudioPlaybackConfiguration>) {
        previous = mediaPlayers(configurations)
        seeded = true
    }

    fun observe(
        configurations: List<AudioPlaybackConfiguration>,
        automaticallyResumed: Set<AudioPlaybackConfiguration>,
    ) {
        val current = mediaPlayers(configurations)
        val newlyActive = current - previous - automaticallyResumed
        val wasSeeded = seeded
        previous = current
        seeded = true
        if (!wasSeeded || newlyActive.isEmpty()) return
        onStart()
    }

    private fun mediaPlayers(configurations: List<AudioPlaybackConfiguration>) =
        configurations.filter { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }.toSet()
}
