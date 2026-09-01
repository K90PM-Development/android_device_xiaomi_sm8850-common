/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioPlaybackCallback
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.util.Log
import androidx.preference.PreferenceManager
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EFFECT_PRIORITY
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.PREF_ENABLE
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.PROFILE_SURROUND
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.dlog

/**
 * Singleton controller that owns the global [MiSoundAudioEffect] instance
 * (audioSession = 0, the global output mix) and re-applies the surround
 * profile on every music playback start and on every audio device change.
 *
 * Modeled on [co.aospa.dolby.xiaomi.DolbyController]; trimmed down because
 * the MiSound effect is parameter-only (no preset/IEQ/HP virt/... sub-params
 * that the user would tweak through a UI).
 */
internal class MiSoundController private constructor(
    private val context: Context
) {
    private var miSoundEffect = MiSoundAudioEffect(EFFECT_PRIORITY, audioSession = 0)
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val handler = Handler(context.mainLooper)

    // Re-apply the surround profile on every media session.
    private val playbackCallback = object : AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val isPlaying = configs.any {
                it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
            }
            dlog(TAG, "onPlaybackConfigChanged: isPlaying=$isPlaying")
            if (isPlaying) {
                applyCurrentProfile()
            }
        }
    }

    // Re-apply the surround profile whenever a speaker/headphone comes or goes
    // so the AIDL effect re-binds to the new output device. Without this, the
    // effect can remain attached to the previous device and stop processing
    // audio on the new one (e.g. after a wired headset plug)
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesAdded")
            applyCurrentProfile()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesRemoved")
            applyCurrentProfile()
        }
    }

    private var registerCallbacks = false
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setRegisterCallbacks($value)")
            if (value) {
                audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            } else {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        }

    init {
        dlog(TAG, "initialized")
    }

    fun onBootCompleted() {
        dlog(TAG, "onBootCompleted")

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = prefs.getBoolean(PREF_ENABLE, true)
        dlog(TAG, "onBootCompleted: enabled=$enabled")
        if (enabled) {
            applyCurrentProfile()
            registerCallbacks = true
        } else {
            release()
        }
    }

    /**
     * Recreates the effect if we lost control of it (e.g. another higher-priority
     * app or the system re-claimed the audio session)
     */
    private fun checkEffect() {
        if (!miSoundEffect.hasControl()) {
            Log.w(TAG, "lost control, recreating effect")
            miSoundEffect.release()
            miSoundEffect = MiSoundAudioEffect(EFFECT_PRIORITY, audioSession = 0)
        }
    }

    private fun applyCurrentProfile() {
        dlog(TAG, "applyCurrentProfile")
        try {
            checkEffect()
            miSoundEffect.superEffectProfile = PROFILE_SURROUND
        } catch (t: Throwable) {
            // Don't let a transient audio HAL hiccup crash the boot receiver.
            Log.e(TAG, "applyCurrentProfile failed", t)
        }
    }

    fun setEnabledAndPersist(enabled: Boolean) {
        dlog(TAG, "setEnabledAndPersist($enabled)")
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_ENABLE, enabled)
            .apply()
        if (enabled) {
            applyCurrentProfile()
            registerCallbacks = true
        } else {
            registerCallbacks = false
            release()
        }
    }

    private fun release() {
        try {
            miSoundEffect.release()
        } catch (t: Throwable) {
            // Effect was already released; nothing to do.
        }
    }

    companion object {
        private const val TAG = "MiSoundController"

        @Volatile
        private var instance: MiSoundController? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: MiSoundController(context).also { instance = it }
            }
    }
}
