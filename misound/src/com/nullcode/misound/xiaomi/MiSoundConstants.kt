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

        // MISOUND_PARAM_SUPER_EFFECT_PROFILE is the param id the vendor
        // disassembly accepts in the [0..2] range. 0 = off, 1 = intelligent
        // (bass extraction for the bottom WSA884x), 2 = standard (same enable
        // path as 1, different DSP param slot). Anything >=3 is treated
        // like 0 by the vendor's setSuperEffectProfile(). The corresponding
        // Java setter is `superEffectProfile` on `MiSoundAudioEffect`.
        const val MISOUND_PARAM_SUPER_EFFECT_PROFILE = 31

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

        fun dlog(tag: String, msg: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(tag, msg)
            }
        }
    }
}
