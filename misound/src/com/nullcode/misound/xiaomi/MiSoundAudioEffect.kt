/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import android.media.audiofx.AudioEffect
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EFFECT_TYPE_MISOUND
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.MISOUND_PARAM_SUPER_EFFECT_PROFILE
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.dlog

/**
 * AudioEffect subclass for the proprietary MiSound AIDL effect
 * (`libmisoundfx_aosp_aidl_ext.so`).
 *
 * The AIDL effect implements stereo -> quad upmix with bass extraction
 * (see `EffectMiSound::upmixStereoToQuad` and `EffectMiSoundContext::setParams`
 * in libmisoundfx_aosp_aidl_ext.so). It is inert until
 * [setSuperEffectProfile] is called with a profile value, at which point the
 * surround mode is engaged and the bottom speaker (WSA884x CH2) starts
 * reproducing the bass content.
 */
class MiSoundAudioEffect(priority: Int, audioSession: Int) : AudioEffect(
    EFFECT_TYPE_NULL,
    EFFECT_TYPE_MISOUND,
    priority,
    audioSession
) {

    /**
     * Sets the MiSound "super effect" profile. Stock log line proves the call:
     *
     *   [AudioEffect.setParameter(int, int)] Effect: MiSound
     *     -> Param: MISOUND_PARAM_SUPER_EFFECT_PROFILE (31 / 0x1f)
     *     -> Value: 0 (0x0)
     *
     * The vendor library treats param 31 as the super-effect profile selector
     * and value 0 as "surround" (the mode that does the bass extraction for the
     * bottom speaker).
     */
    var superEffectProfile: Int
        get() = getIntParam(MISOUND_PARAM_SUPER_EFFECT_PROFILE)
        set(value) {
            dlog(TAG, "setSuperEffectProfile($value)")
            setIntParam(MISOUND_PARAM_SUPER_EFFECT_PROFILE, value)
        }

    /**
     * Sends a 4-byte int param to the effect. The vendor's AIDL dispatcher
     * sees a [paramId, value] pair (paramId at the first 4 bytes of the wire
     * payload, value at the next 4, little-endian). See
     * `EffectMiSoundContext::setParams(vector<uint8_t>)` in
     * `libmisoundfx_aosp_aidl_ext.so` for the per-id handling.
     */
    fun setIntParam(param: Int, value: Int) {
        dlog(TAG, "setIntParam(param=$param, value=$value)")
        checkStatus(setParameter(param, value))
    }

    /**
     * Convenience wrapper that writes a boolean as 0/1 to [param]. All of the
     * MiSound toggles (3D surround, SoundID, EQ compensation, master effect
     * enable) use this 0/1 convention on the vendor side.
     */
    fun setBooleanParam(param: Int, enabled: Boolean) {
        setIntParam(param, if (enabled) 1 else 0)
    }

    private fun getIntParam(param: Int): Int {
        val buf = ByteArray(4)
        checkStatus(getParameter(param, buf))
        return ((buf[3].toInt() and 0xff) shl 24) or
            ((buf[2].toInt() and 0xff) shl 16) or
            ((buf[1].toInt() and 0xff) shl 8) or
            (buf[0].toInt() and 0xff)
    }

    companion object {
        private const val TAG = "MiSoundAudioEffect"
    }
}
