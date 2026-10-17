/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.DEFAULT_EQ_ENABLED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_APPLY_BOTTOM
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_APPLY_EAR
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_AUTO_ROTATE
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_AUTO_SUSPEND
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_BAND_COUNT
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_BAND_COUNT_BOTTOM
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_ENABLED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_INITIALIZED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_PREAMP
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_PREAMP_BOTTOM
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.dlog
import org.json.JSONObject

/**
 * Owns the earpiece EQ lifecycle on top of [MiSoundEqEngine]:
 *
 *  - on first boot, imports the bundled `earpiece_eq_defaults.json`
 *    (same format the standalone EarpieceEQ app exports) into
 *    Settings.System so the out-of-the-box tuning matches the backup config
 *    the user validated with the standalone app;
 *  - watches for connected external outputs (headphones/USB/BT) and
 *    suspends/resumes the EQ accordingly;
 *  - watches display rotation and re-maps earpiece/bottom speaker to
 *    physical L/R on the fly;
 *  - observes the misound_eq_* Settings.System keys so changes made from the
 *    XiaomiParts sub-page (or adb) apply live.
 */
internal class MiSoundEqController private constructor(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val handler = Handler(context.mainLooper)
    private var settingsObserver: ContentObserver? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var registered = false
    private var lastPhys = -1

    init {
        MiSoundEqEngine.init(context)
    }

    fun onBootCompleted() {
        importDefaultsIfNeeded()
        registerCallbacks()
        evaluateOutput()
        applyEq()
    }

    fun release() {
        unregisterCallbacks()
        MiSoundEqEngine.release()
    }

    /** Re-reads settings and pushes them to the engine. */
    private fun applyEq() {
        val s = EqSettings(context.contentResolver)
        dlog(
            TAG,
            "applyEq: enabled=${s.enabled} ear=${s.applyEarpiece} " +
                "bottom=${s.applyBottom} suspended=${MiSoundEqEngine.suspended}",
        )
        MiSoundEqEngine.apply(s)
    }

    private fun registerCallbacks() {
        if (registered) return
        val resolver = context.contentResolver

        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                applyEq()
            }
        }
        for (key in OBSERVED_KEYS) {
            resolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                observer,
                UserHandle.USER_CURRENT,
            )
        }
        settingsObserver = observer

        val devCb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = evaluateOutput()
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = evaluateOutput()
        }
        audioManager.registerAudioDeviceCallback(devCb, handler)
        deviceCallback = devCb

        // The session-0 effect can be dropped by the audio server across HAL
        // restarts or route changes; re-assert it on every playback start so
        // it never stays dead while the EQ is enabled.
        val playCb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                if (configs.any { it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED }) {
                    applyEq()
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(playCb, handler)
        playbackCallback = playCb

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val phys = MiSoundEqEngine.physicalChannel(context, EqSettings(resolver), 0)
                if (phys == lastPhys) return
                lastPhys = phys
                dlog(TAG, "rotation -> physical earpiece ch=$phys, refreshing EQ")
                MiSoundEqEngine.refresh(EqSettings(resolver))
            }
        }
        context.getSystemService(DisplayManager::class.java)?.registerDisplayListener(listener, handler)
        displayListener = listener

        lastPhys = MiSoundEqEngine.physicalChannel(context, EqSettings(resolver), 0)
        registered = true
        dlog(TAG, "callbacks registered")
    }

    private fun unregisterCallbacks() {
        if (!registered) return
        settingsObserver?.let { observer ->
            for (key in OBSERVED_KEYS) {
                context.contentResolver.unregisterContentObserver(observer)
            }
        }
        settingsObserver = null
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        playbackCallback?.let { audioManager.unregisterAudioPlaybackCallback(it) }
        playbackCallback = null
        displayListener?.let {
            context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it)
        }
        displayListener = null
        registered = false
    }

    /** External outputs currently connected (headphones, USB, BT ...). */
    private fun externalOutputs(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in EXTERNAL_TYPES }

    private fun evaluateOutput() {
        val s = EqSettings(context.contentResolver)
        val suspend = s.autoSuspend && externalOutputs().isNotEmpty()
        if (suspend != MiSoundEqEngine.suspended) {
            dlog(TAG, "external output -> suspend=$suspend")
            MiSoundEqEngine.setSuspended(suspend)
        }
        applyEq()
    }

    /**
     * On first boot (no `misound_eq_initialized` key yet), imports the bundled
     * earpiece-eq backup config into Settings.System. Later boots leave the
     * user's own values alone.
     */
    private fun importDefaultsIfNeeded() {
        val resolver = context.contentResolver
        val user = UserHandle.USER_CURRENT
        if (Settings.System.getIntForUser(resolver, SETTING_KEY_EQ_INITIALIZED, 0, user) == 1) {
            dlog(TAG, "eq defaults already imported")
            return
        }
        try {
            val json = context.resources
                .openRawResource(R.raw.earpiece_eq_defaults)
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val prefs = root.optJSONObject("prefs") ?: JSONObject()

            val enabled = root.optInt("enabled", DEFAULT_EQ_ENABLED)
            Settings.System.putIntForUser(resolver, SETTING_KEY_EQ_ENABLED, if (enabled == 1) 1 else 0, user)

            val applyEar = prefs.optBoolean("apply_ear", false)
            val applyBottom = prefs.optBoolean("apply_bottom", true)
            Settings.System.putIntForUser(resolver, SETTING_KEY_EQ_APPLY_EAR, if (applyEar) 1 else 0, user)
            Settings.System.putIntForUser(resolver, SETTING_KEY_EQ_APPLY_BOTTOM, if (applyBottom) 1 else 0, user)

            // Rotation-aware channel mapping and headphone suspend stay on;
            // the backup's legacy_mode flag is meaningless here because the
            // integrated engine always targets the global output mix.
            Settings.System.putIntForUser(resolver, SETTING_KEY_EQ_AUTO_ROTATE, 1, user)
            Settings.System.putIntForUser(resolver, SETTING_KEY_EQ_AUTO_SUSPEND, 1, user)

            // The backup config only tunes the bottom speaker: cut everything
            // below ~450 Hz on it (the earpiece cannot reproduce that range).
            // The earpiece channel keeps its default flat profile.
            val bCount = prefs.optInt("b_band_count", EqBands.DEFAULT_COUNT)
                .coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)
            val bPreamp = prefs.optDouble("b_preamp", 0.0).toFloat()
                .coerceIn(EqBands.PRE_MIN_DB, EqBands.PRE_MAX_DB)
            val eq = EqSettings(resolver)
            eq.setProfile(0, 0f, FloatArray(EqBands.DEFAULT_COUNT))
            eq.setProfile(1, bPreamp, parseGains(prefs.optString("b_gains_$bCount", ""), bCount))

            Settings.System.putIntForUser(resolver, SETTING_KEY_EQ_INITIALIZED, 1, user)
            dlog(TAG, "earpiece EQ defaults imported from bundled backup")
        } catch (t: Throwable) {
            // Never block boot on a bad resource; the initialized flag is not
            // set, so the next boot retries.
            Log.e(TAG, "failed to import earpiece EQ defaults", t)
        }
    }

    private fun parseGains(raw: String, n: Int): FloatArray {
        val parsed = raw.split(",").mapNotNull { it.toFloatOrNull() }
        return FloatArray(n) { i ->
            (if (i < parsed.size) parsed[i] else 0f).coerceIn(EqBands.MIN_DB, EqBands.MAX_DB)
        }
    }

    companion object {
        private const val TAG = "MiSoundEqController"

        private val EXTERNAL_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )

        // Gains keys are name-patterned (misound_eq_gains_<n>) so they cannot
        // be observed individually; setProfile() rewrites the preamp and
        // band-count keys on every profile write, which triggers this
        // observer anyway.
        private val OBSERVED_KEYS = arrayOf(
            SETTING_KEY_EQ_ENABLED,
            SETTING_KEY_EQ_APPLY_EAR,
            SETTING_KEY_EQ_APPLY_BOTTOM,
            SETTING_KEY_EQ_AUTO_ROTATE,
            SETTING_KEY_EQ_AUTO_SUSPEND,
            SETTING_KEY_EQ_PREAMP,
            SETTING_KEY_EQ_BAND_COUNT,
            SETTING_KEY_EQ_PREAMP_BOTTOM,
            SETTING_KEY_EQ_BAND_COUNT_BOTTOM,
        )

        @Volatile
        private var instance: MiSoundEqController? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: MiSoundEqController(context).also { instance = it }
            }
    }
}
