/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.audio

/**
 * Band constants for the earpiece EQ editor, mirroring the engine in the
 * XiaomiMiSound app (com.nullcode.misound.xiaomi.EqBands). Only the values
 * the settings UI needs are duplicated here; the gain interpolation onto the
 * internal DynamicsProcessing grid lives entirely in the controller.
 */
internal object EqBands {
    const val MIN_COUNT = 1
    const val MAX_COUNT = 32
    const val DEFAULT_COUNT = 10

    /** Gain range per band (dB). */
    const val MIN_DB = -50f
    const val MAX_DB = 50f

    /** Preamp range (dB). */
    const val PRE_MIN_DB = -12f
    const val PRE_MAX_DB = 12f

    /** Upper cutoff of each of the [n] slider bands, log-spaced 31 Hz..20 kHz. */
    fun freqs(n: Int): FloatArray {
        if (n <= 1) return floatArrayOf(20000f)
        val lo = 31.0
        val hi = 20000.0
        return FloatArray(n) { i -> (lo * Math.pow(hi / lo, i.toDouble() / (n - 1))).toFloat() }
    }
}

/**
 * Read/write helpers for the earpiece EQ keys in Settings.System, mirroring
 * the engine's EqSettings. The controller observes these keys and applies
 * them live.
 */
internal class EqSettings(private val resolver: android.content.ContentResolver) {

    private fun getBool(key: String, default: Boolean) =
        android.provider.Settings.System.getIntForUser(
            resolver, key, if (default) 1 else 0, android.os.UserHandle.USER_CURRENT,
        ) == 1

    private fun getFloat(key: String, default: Float) =
        android.provider.Settings.System.getFloatForUser(
            resolver, key, default, android.os.UserHandle.USER_CURRENT,
        )

    private fun getInt(key: String, default: Int) =
        android.provider.Settings.System.getIntForUser(
            resolver, key, default, android.os.UserHandle.USER_CURRENT,
        )

    var enabled: Boolean
        get() = getBool("misound_eq_enabled", true)
        set(v) {
            android.provider.Settings.System.putIntForUser(
                resolver, "misound_eq_enabled", if (v) 1 else 0, android.os.UserHandle.USER_CURRENT,
            )
        }

    fun applyEarpiece(): Boolean = getBool("misound_eq_apply_ear", true)

    fun applyBottom(): Boolean = getBool("misound_eq_apply_bottom", true)

    fun autoRotate(): Boolean = getBool("misound_eq_auto_rotate", true)

    fun autoSuspend(): Boolean = getBool("misound_eq_auto_suspend", true)

    fun preampOf(spk: Int): Float =
        getFloat(if (spk == 0) "misound_eq_preamp" else "misound_eq_preamp_bottom", 0f)

    fun bandCountOf(spk: Int): Int =
        getInt(
            if (spk == 0) "misound_eq_band_count" else "misound_eq_band_count_bottom",
            EqBands.DEFAULT_COUNT,
        ).coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)

    fun gainsOf(spk: Int): FloatArray {
        val n = bandCountOf(spk)
        val key = if (spk == 0) "misound_eq_gains_$n" else "misound_eq_gains_bottom_$n"
        val raw = android.provider.Settings.System.getStringForUser(
            resolver, key, android.os.UserHandle.USER_CURRENT,
        )
        val parsed = raw?.split(",")?.mapNotNull { it.toFloatOrNull() }
        return if (parsed != null && parsed.size == n) parsed.toFloatArray() else FloatArray(n)
    }

    fun setProfile(spk: Int, preamp: Float, gains: FloatArray) {
        val n = gains.size.coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)
        val user = android.os.UserHandle.USER_CURRENT
        android.provider.Settings.System.putFloatForUser(
            resolver,
            if (spk == 0) "misound_eq_preamp" else "misound_eq_preamp_bottom",
            preamp,
            user,
        )
        android.provider.Settings.System.putIntForUser(
            resolver,
            if (spk == 0) "misound_eq_band_count" else "misound_eq_band_count_bottom",
            n,
            user,
        )
        android.provider.Settings.System.putStringForUser(
            resolver,
            if (spk == 0) "misound_eq_gains_$n" else "misound_eq_gains_bottom_$n",
            gains.take(n).joinToString(","),
            user,
        )
    }
}
