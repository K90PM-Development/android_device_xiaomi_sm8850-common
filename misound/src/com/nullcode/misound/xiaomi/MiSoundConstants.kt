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

        // The stock AudioEffectCenter logs "MISOUND_PARAM_SUPER_EFFECT_PROFILE (31 / 0x1f) 
        // -> Value: 0 (0x0)" when surround mode is engaged.
        const val MISOUND_PARAM_SUPER_EFFECT_PROFILE = 31

        const val PROFILE_SURROUND = 0
        const val EFFECT_PRIORITY = 100
        const val PREF_ENABLE = "misound_enabled"

        fun dlog(tag: String, msg: String) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(tag, msg)
            }
        }
    }
}
