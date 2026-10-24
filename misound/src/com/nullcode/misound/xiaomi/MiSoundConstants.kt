/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import android.util.Log

class MiSoundConstants {

    companion object {
        const val TAG = "XiaomiMiSound"

        // AudioEffect UUID for the proprietary MiSound AIDL effect.
        // See hardware/qcom-caf/sm8850/audio/primary-hal/configs/canoe/audio_effects_config.xml
        // and the lib at /vendor/lib64/soundfx/libmisoundfx_aosp_aidl.so (symlink to
        // /vendor/lib64/libmisoundfx_aosp_aidl_ext.so).
        val EFFECT_TYPE_MISOUND =
            java.util.UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b")

        // Reverse-engineered from libmisoundfx_aosp_aidl_ext.so. Each
        // MISOUND_PARAM_* id is the int the vendor reads out of the
        // AudioEffect.setParameter(int, int) byte payload (param id at the
        // first 4 bytes, value at the next 4, little-endian). The dispatch
        // table lives in EffectMiSoundContext::setParams(vector<uint8_t>) at
        // VA 0x5d8b4. Ids 3-8, 10, 12, 16-19, 21-22 are explicitly rejected
        // by the library ("unsupport parameters!") and are not listed here.
        const val MISOUND_PARAM_3DSURROUND = 20              // 3D surround
        const val MISOUND_PARAM_EFFECT_ENABLE = 25           // master on/off
        const val MISOUND_PARAM_SUPER_EFFECT_PROFILE = 31    // 0/1/2 off/intelligent/standard

        // Backward-compat alias: previously the controller always wrote
        // PROFILE_SURROUND (1). Now it writes whatever mode the user picked
        // from the XiaomiParts sub-page (0/1/2). The DEFAULT_MODE for the
        // sub-page is still 1 to preserve the prior force-on behavior.
        const val PROFILE_SURROUND = 1
        const val EFFECT_PRIORITY = 100

        // SharedPreferences key (legacy, used as a fallback for users who
        // already had the controller running before the Settings.System key
        // existed). The canonical state is stored under SETTING_KEY.
        const val PREF_ENABLE = "misound_enabled"

        // Settings.System key observed by the controller and written by the
        // XiaomiParts MiSound sub-page. Stores 0 (off) or 1 (on).
        const val SETTING_KEY = "misound_enabled"
        const val DEFAULT_ENABLED = 1

        // Settings.System key for the chosen super-effect profile (0/1/2).
        // Mirrors the value written to MISOUND_PARAM_SUPER_EFFECT_PROFILE.
        const val MODE_SETTING_KEY = "misound_mode"
        const val DEFAULT_MODE = PROFILE_SURROUND

        // Allowed values for the mode ListPreference and the values written
        // to MISOUND_PARAM_SUPER_EFFECT_PROFILE (param id 31). The vendor
        // disassembly only accepts 0/1/2; anything >=3 is treated like 0.
        //   0 = off / disabled (no effect applied)
        //   1 = intelligent (bass extraction for the bottom WSA884x)
        //   2 = standard   (same enable path as 1, different DSP param slot)
        const val MODE_OFF = 0
        const val MODE_INTELLIGENT = 1
        const val MODE_STANDARD = 2

        // Settings.System key for the 3D surround toggle exposed in the
        // XiaomiParts sub-page. Stores 0 (off) or 1 (on) and is written via
        // Settings.System.putIntForUser(..., UserHandle.USER_CURRENT). The
        // corresponding vendor param id is named next to the key.
        const val SETTING_KEY_3D_SURROUND = "misound_3d_surround"          // param 20

        // Default value for the toggle. 0 = off so a user who has never
        // opened the sub-page gets the stock MiSound behaviour.
        const val DEFAULT_3D_SURROUND = 0

        // ---- Earpiece EQ (DynamicsProcessing pre-EQ, ported from the
        // EarpieceEQ app in device/xiaomi/earpiece-eq-v0.12-source). ----

        // Master on/off for the earpiece EQ stage. On by default: the bundled
        // earpiece_eq_defaults.json is imported on first boot and the engine
        // is a no-op until a speaker profile is set, so a default-on switch
        // is safe.
        const val SETTING_KEY_EQ_ENABLED = "misound_eq_enabled"
        const val DEFAULT_EQ_ENABLED = 1

        // Which speaker channels the EQ is applied to.
        const val SETTING_KEY_EQ_APPLY_EAR = "misound_eq_apply_ear"
        const val SETTING_KEY_EQ_APPLY_BOTTOM = "misound_eq_apply_bottom"

        // Behaviour toggles.
        const val SETTING_KEY_EQ_AUTO_ROTATE = "misound_eq_auto_rotate"
        const val SETTING_KEY_EQ_AUTO_SUSPEND = "misound_eq_auto_suspend"

        // Per-speaker profiles: preamp (float), band count (int) and the gain
        // vector (comma-separated string, key carries the band count). Keys
        // for the bottom speaker mirror the earpiece ones with a _bottom
        // suffix; gains keys carry the count, e.g. misound_eq_gains_10 /
        // misound_eq_gains_bottom_32.
        const val SETTING_KEY_EQ_PREAMP = "misound_eq_preamp"
        const val SETTING_KEY_EQ_BAND_COUNT = "misound_eq_band_count"
        const val SETTING_KEY_EQ_GAINS_PREFIX = "misound_eq_gains_"
        const val SETTING_KEY_EQ_PREAMP_BOTTOM = "misound_eq_preamp_bottom"
        const val SETTING_KEY_EQ_BAND_COUNT_BOTTOM = "misound_eq_band_count_bottom"
        const val SETTING_KEY_EQ_GAINS_BOTTOM_PREFIX = "misound_eq_gains_bottom_"

        // First-boot marker: set to 1 after the bundled earpiece_eq_defaults
        // JSON has been written into the keys above, so the user's own edits
        // are never overwritten on later boots.
        const val SETTING_KEY_EQ_INITIALIZED = "misound_eq_initialized"

        // ---- Per-output-device profiles (ported from EarpieceEQ v0.21) ----
        //
        // The keys above describe the built-in speaker profile. Each external
        // output gets its own copy of the same keys inside the JSON blob under
        // SETTING_KEY_EQ_PROFILES, keyed by the route identity (SHA-256).
        const val SETTING_KEY_EQ_PROFILES = "misound_eq_profiles"
        const val SETTING_KEY_EQ_ROUTE_ACTIVE = "misound_eq_route_active"
        const val SETTING_KEY_EQ_ROUTE_EXTERNAL = "misound_eq_route_external"
        const val SETTING_KEY_EQ_ROUTE_KNOWN = "misound_eq_route_known"
        const val SETTING_KEY_EQ_ROUTE_NAME = "misound_eq_route_name"
        const val SETTING_KEY_EQ_ROUTE_WEAK = "misound_eq_route_weak"

        // ---- Earpiece virtual bass AIDL effect (libearpiecebass_aidl.so) ----
        //
        // Prebuilt blob from the EarpieceEQ companion module, registered in
        // configs/audio/audio_effects_config.xml. It is driven directly from
        // this app (platform_apis) through the hidden AudioEffect
        // setParameter/getParameter(byte[], byte[]) overloads, so neither the
        // upstream root daemon nor any Magisk module is needed.
        val EFFECT_TYPE_EARPIECE_BASS =
            java.util.UUID.fromString("29bc18a1-73e0-4a2b-91d2-3e47b8502c10")
        val EFFECT_UUID_EARPIECE_BASS =
            java.util.UUID.fromString("39bc18a1-73e0-4a2b-91d2-3e47b8502c10")

        // Param ids understood by EarpieceBass.cpp::setParameterSpecific. The
        // payload is a little-endian int32 array laid out as
        // [0, 4, vsize_bytes, param_id, ...values], which is exactly what the
        // legacy effect_param_t looks like after the AIDL conversion copies it
        // into DefaultExtension.bytes.
        const val EB_PARAM_VIRTUAL_BASS = 1000        // 2 values  (L/R strength %)
        const val EB_PARAM_BODY = 1001                // 4 values  (+body %)
        const val EB_PARAM_LIMITED = 1003             // 8 values  (+limiter + ceiling)
        const val EB_PARAM_ADVANCED = 1005            // 22 values (+band/auto/meters)

        // Readback keys, answered with [0, 4, size, key, driverVersion, ...].
        // The reply's value region starts with the driver version, so the
        // first int the app reads back is that version, not the status.
        const val EB_PARAM_READBACK_VB = 1002         // 32 B: strengths + body
        const val EB_PARAM_READBACK_LIMITED = 1004    // 64 B: + limiter/ceiling
        const val EB_PARAM_READBACK_ADVANCED = 1006   // 140 B: + all 22 controls
        const val EB_PARAM_READBACK_COMMON_EQ = 1008  // 144 B
        const val EB_PARAM_READBACK_COMMON_BASS = 1010 // 144 B

        // Additional stacked stages (independent of the virtual bass profile).
        const val EB_PARAM_COMMON_EQ = 1007           // 3+n values
        const val EB_PARAM_COMMON_BASS = 1009         // 23 values

        const val EB_PARAM_ADVANCED_FX = 1100         // 1 + 3*101 values
        const val EB_PARAM_ADVANCED_FX_READBACK = 1101
        const val EB_PARAM_LEVELER = 1200             // 3*9 values
        const val EB_PARAM_LEVELER_READBACK = 1201

        // Driver version reported by every readback; the module this tree
        // ships understood at least 21 at the time it was imported.
        const val EB_DRIVER_MIN_VERSION = 21

        fun dlog(tag: String, msg: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(tag, msg)
            }
        }
    }
}
