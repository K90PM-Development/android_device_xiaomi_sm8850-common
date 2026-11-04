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
 *
 * The Settings.System accessors live in [EqSettings].
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

