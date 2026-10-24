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
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_PROFILES
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_ROUTE_ACTIVE
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.dlog
import org.json.JSONObject

/**
 * Owns the earpiece EQ lifecycle on top of [MiSoundEqEngine]:
 *
 *  - on first boot, imports the bundled `earpiece_eq_defaults.json`
 *    (same format the standalone EarpieceEQ app exports) into
 *    Settings.System so the out-of-the-box tuning matches the backup config
 *    the user validated with the standalone app;
 *  - watches the routed media output and switches to that device's own
 *    profile, pausing processing for unidentified or paused external
 *    outputs (ported from EarpieceEQ v0.21);
 *  - watches display rotation and re-maps earpiece/bottom speaker to
 *    physical L/R on the fly (built-in speakers only);
 *  - drives the EarpieceEQ companion AIDL effect directly with the active
 *    profile's virtual bass / ceiling / common EQ values;
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
    private var lastProfile: String? = null

    private val bassEffect = EarpieceBassEffect()

    init {
        MiSoundEqEngine.init(context)
    }

    fun onBootCompleted() {
        importDefaultsIfNeeded()
        registerCallbacks()
        evaluateOutput()
    }

    fun release() {
        unregisterCallbacks()
        MiSoundEqEngine.release()
        bassEffect.release()
    }

    /** Re-reads settings and pushes them to the engine for the active profile. */
    private fun applyEq() {
        val resolver = context.contentResolver
        val s = EqSettings.active(resolver)
        dlog(
            TAG,
            "applyEq: profile=${s.profileId.ifEmpty { "builtin" }} enabled=${s.enabled} " +
                "ear=${s.applyEarpiece} bottom=${s.applyBottom} " +
                "suspended=${MiSoundEqEngine.suspended}",
        )
        // Order matters: AudioFlinger inserts a new EFFECT_FLAG_INSERT_LAST
        // effect *before* an existing one, so whichever is created last
        // processes first. Creating the bass stage before DynamicsProcessing
        // makes the chain EQ -> bass -> ceiling, so the EQ shapes the signal
        // instead of boosting it after the ceiling has already limited it.
        applyBass(s)
        // A freshly attached bass effect jumps ahead of the existing pre-EQ in
        // the chain, so rebuild DynamicsProcessing afterwards to restore the
        // EQ -> bass order.
        if (bassEffect.consumeReopened()) {
            MiSoundEqEngine.release()
        }
        MiSoundEqEngine.apply(s)
    }

    /**
     * Pushes the virtual bass / common EQ stages of the active profile to the
     * EarpieceEQ companion effect, or releases it when nothing is configured.
     *
     * Must run before [MiSoundEqEngine.apply] when both effects are being
     * created, so that the pre-EQ ends up earlier in the effect chain; see
     * the call site for the insertion-order rule.
     */
    private fun applyBass(s: EqSettings) {
        val b = BassSettings(context.contentResolver, s.profileId)
        val active = s.enabled && !MiSoundEqEngine.suspended &&
            (b.anyBass() || b.commonEqEnabled)
        if (!active) {
            bassEffect.release()
            return
        }
        if (!EarpieceBassEffect.available()) {
            dlog(TAG, "earpiece bass effect not registered, skipping")
            return
        }
        val earPhys = MiSoundEqEngine.physicalChannel(context, s, 0)
        // Unconditional: with no bass configured the block is the neutral
        // default, which is exactly how a previously active bass stage is
        // switched off while the common EQ keeps the effect running.
        bassEffect.apply(
            b.buildControls(earPhys, 1 - earPhys, s.applyEarpiece, s.applyBottom),
        )
        // Always sent so that turning the common EQ off reaches the DSP; the
        // driver skips the call when the values are unchanged.
        bassEffect.applyCommonEq(b.commonEqEnabled, b.commonPreamp, b.commonGains)
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
                    // Re-detect the route too: the routed output can change
                    // (e.g. an explicit routing request) without any device
                    // being added or removed.
                    evaluateOutput()
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(playCb, handler)
        playbackCallback = playCb

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                val s = EqSettings.active(resolver)
                val phys = MiSoundEqEngine.physicalChannel(context, s, 0)
                if (phys == lastPhys) return
                lastPhys = phys
                dlog(TAG, "rotation -> physical earpiece ch=$phys, refreshing EQ")
                MiSoundEqEngine.refresh(s)
            }
        }
        context.getSystemService(DisplayManager::class.java)?.registerDisplayListener(listener, handler)
        displayListener = listener

        lastPhys = MiSoundEqEngine.physicalChannel(context, EqSettings.active(resolver), 0)
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

    /**
     * Re-detects the routed output, switches to that device's profile and
     * pauses processing when required.
     *
     * Processing runs only when the output could be identified and, for an
     * external output, when "pause processing for earphones" is off. On any
     * profile change the effect is torn down first: the new device may be
     * stereo where the last one was not, and a stale instance would keep the
     * previous channels.
     */
    private fun evaluateOutput() {
        val resolver = context.contentResolver
        RouteProfiles.update(context)
        val profile = RouteProfiles.currentId(resolver)
        val suspend = !RouteProfiles.processingAllowed(resolver)
        if (profile == lastProfile && suspend == MiSoundEqEngine.suspended) {
            applyEq()
            return
        }
        lastProfile = profile
        MiSoundEqEngine.setSuspended(suspend)
        dlog(TAG, "output profile -> ${RouteProfiles.label(context)} suspend=$suspend")
        MiSoundEqEngine.release()
        bassEffect.release()
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

        // Gains and per-device profiles are name-patterned / JSON blobs, so
        // they cannot be observed individually. Every write through
        // EqSettings.setProfile() also rewrites the preamp and band-count keys
        // and every route switch rewrites the route keys, which trigger this
        // observer anyway.
        private val OBSERVED_KEYS = arrayOf(
            SETTING_KEY_EQ_ENABLED,
            *BassSettings.globalKeys().toTypedArray(),
            SETTING_KEY_EQ_APPLY_EAR,
            SETTING_KEY_EQ_APPLY_BOTTOM,
            SETTING_KEY_EQ_AUTO_ROTATE,
            SETTING_KEY_EQ_AUTO_SUSPEND,
            SETTING_KEY_EQ_PREAMP,
            SETTING_KEY_EQ_BAND_COUNT,
            SETTING_KEY_EQ_PREAMP_BOTTOM,
            SETTING_KEY_EQ_BAND_COUNT_BOTTOM,
            // Route switches (and every per-device profile write) land here.
            SETTING_KEY_EQ_ROUTE_ACTIVE,
            SETTING_KEY_EQ_PROFILES,
        )

        @Volatile
        private var instance: MiSoundEqController? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: MiSoundEqController(context).also { instance = it }
            }
    }
}
