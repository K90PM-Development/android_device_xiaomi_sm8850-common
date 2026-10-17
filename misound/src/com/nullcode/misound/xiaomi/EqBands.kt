/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import kotlin.math.ln
import kotlin.math.sqrt

import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.DEFAULT_EQ_ENABLED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_APPLY_BOTTOM
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_APPLY_EAR
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_AUTO_ROTATE
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_AUTO_SUSPEND
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_BAND_COUNT
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_BAND_COUNT_BOTTOM
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_ENABLED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_GAINS_BOTTOM_PREFIX
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_GAINS_PREFIX
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_PREAMP
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_PREAMP_BOTTOM

/**
 * Band grid helpers for the earpiece EQ, ported from the EarpieceEQ app
 * (device/xiaomi/earpiece-eq-v0.12-source).
 *
 * The user edits N "slider" bands logarithmically spaced between 31 Hz and
 * 20 kHz; the gains are interpolated with monotone cubic (PCHIP) onto a
 * fixed internal grid of DynamicsProcessing pre-EQ bands wide enough that
 * every internal band has at least 20 Hz of bandwidth (finer bands would be
 * narrower than the FFT resolution and would silently do nothing).
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

    /**
     * Fixed internal DynamicsProcessing pre-EQ grid: 40 Hz .. 20 kHz with
     * each step >= 20 Hz of bandwidth.
     */
    val GRID: FloatArray by lazy { buildGrid() }

    private fun buildGrid(): FloatArray {
        val list = ArrayList<Float>()
        var f = 40.0
        list.add(f.toFloat())
        val ratio = Math.pow(500.0, 1.0 / 39.0)
        while (true) {
            val next = Math.max(f * ratio, f + 20.0)
            if (next >= 19000.0) break
            list.add(next.toFloat())
            f = next
        }
        list.add(20000f)
        return list.toFloatArray()
    }

    /** Representative frequency (geometric band mean) of each internal band. */
    private fun gridCenters(): DoubleArray {
        val g = GRID
        return DoubleArray(g.size) { k ->
            if (k == 0) g[0].toDouble() else sqrt(g[k - 1].toDouble() * g[k].toDouble())
        }
    }

    /**
     * Interpolates the [userGains] slider values onto the internal grid with
     * monotone cubic (PCHIP) interpolation over log-frequency: smooth between
     * control points, no overshoot, edges held flat.
     */
    fun interpolate(userGains: FloatArray): FloatArray {
        val q = gridCenters()
        val n = userGains.size
        if (n <= 1) {
            val v = if (n == 1) userGains[0] else 0f
            return FloatArray(q.size) { v }
        }
        val uf = freqs(n)
        val x = DoubleArray(n) { ln(uf[it].toDouble()) }
        val y = DoubleArray(n) { userGains[it].toDouble() }
        val out = EqInterp.pchip(x, y, DoubleArray(q.size) { ln(q[it]) })
        return FloatArray(out.size) { out[it].toFloat().coerceIn(MIN_DB, MAX_DB) }
    }
}

/** Monotone cubic Hermite interpolation (PCHIP) — no overshoot between control points. */
private object EqInterp {
    fun pchip(x: DoubleArray, y: DoubleArray, q: DoubleArray): DoubleArray {
        val n = x.size
        val h = DoubleArray(n - 1) { x[it + 1] - x[it] }
        val d = DoubleArray(n - 1) { (y[it + 1] - y[it]) / h[it] }
        val m = DoubleArray(n)
        if (n == 2) {
            m[0] = d[0]
            m[1] = d[0]
        } else {
            for (k in 1 until n - 1) {
                m[k] = if (d[k - 1] * d[k] <= 0.0) 0.0 else {
                    val w1 = 2 * h[k] + h[k - 1]
                    val w2 = h[k] + 2 * h[k - 1]
                    (w1 + w2) / (w1 / d[k - 1] + w2 / d[k])
                }
            }
            m[0] = edge(h[0], h[1], d[0], d[1])
            m[n - 1] = edge(h[n - 2], h[n - 3], d[n - 2], d[n - 3])
        }
        return DoubleArray(q.size) { j ->
            val xq = q[j]
            if (xq <= x[0]) {
                y[0]
            } else if (xq >= x[n - 1]) {
                y[n - 1]
            } else {
                var i = 0
                while (i < n - 2 && xq > x[i + 1]) i++
                val t = (xq - x[i]) / h[i]
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * y[i] + (t3 - 2 * t2 + t) * h[i] * m[i] +
                    (-2 * t3 + 3 * t2) * y[i + 1] + (t3 - t2) * h[i] * m[i + 1]
            }
        }
    }

    private fun edge(h0: Double, h1: Double, d0: Double, d1: Double): Double {
        var m = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        if (Math.signum(m) != Math.signum(d0)) {
            m = 0.0
        } else if (Math.signum(d0) != Math.signum(d1) && Math.abs(m) > 3 * Math.abs(d0)) {
            m = 3 * d0
        }
        return m
    }
}

/**
 * Per-channel earpiece EQ state, persisted in Settings.System so it survives
 * reboots and is editable from the XiaomiParts MiSound sub-page (and from
 * `adb shell settings put system ...`).
 *
 * Speaker 0 is the earpiece, speaker 1 the bottom speaker; each has its own
 * preamp, band count and gain vector. The "enable this channel", "auto swap
 * on rotation" and "suspend while headphones are connected" flags mirror the
 * corresponding prefs of the standalone app.
 */
internal class EqSettings(private val resolver: android.content.ContentResolver) {

    private fun getBool(key: String, default: Boolean) =
        android.provider.Settings.System.getIntForUser(
            resolver, key, if (default) 1 else 0, android.os.UserHandle.USER_CURRENT,
        ) == 1

    private fun putBool(key: String, value: Boolean) {
        android.provider.Settings.System.putIntForUser(
            resolver, key, if (value) 1 else 0, android.os.UserHandle.USER_CURRENT,
        )
    }

    private fun getFloat(key: String, default: Float) =
        android.provider.Settings.System.getFloatForUser(resolver, key, default, android.os.UserHandle.USER_CURRENT)

    private fun putFloat(key: String, value: Float) {
        android.provider.Settings.System.putFloatForUser(resolver, key, value, android.os.UserHandle.USER_CURRENT)
    }

    private fun getInt(key: String, default: Int) =
        android.provider.Settings.System.getIntForUser(resolver, key, default, android.os.UserHandle.USER_CURRENT)

    private fun putInt(key: String, value: Int) {
        android.provider.Settings.System.putIntForUser(resolver, key, value, android.os.UserHandle.USER_CURRENT)
    }

    var enabled: Boolean
        get() = getBool(SETTING_KEY_EQ_ENABLED, DEFAULT_EQ_ENABLED == 1)
        set(v) = putBool(SETTING_KEY_EQ_ENABLED, v)

    /** Apply EQ to the earpiece channel (speaker 0). */
    var applyEarpiece: Boolean
        get() = getBool(SETTING_KEY_EQ_APPLY_EAR, true)
        set(v) = putBool(SETTING_KEY_EQ_APPLY_EAR, v)

    /** Apply EQ to the bottom speaker channel (speaker 1). */
    var applyBottom: Boolean
        get() = getBool(SETTING_KEY_EQ_APPLY_BOTTOM, true)
        set(v) = putBool(SETTING_KEY_EQ_APPLY_BOTTOM, v)

    /** Auto-swap L/R so the earpiece stays the earpiece in reverse orientations. */
    var autoRotate: Boolean
        get() = getBool(SETTING_KEY_EQ_AUTO_ROTATE, true)
        set(v) = putBool(SETTING_KEY_EQ_AUTO_ROTATE, v)

    /** Suspend the EQ while headphones / USB / Bluetooth outputs are connected. */
    var autoSuspend: Boolean
        get() = getBool(SETTING_KEY_EQ_AUTO_SUSPEND, true)
        set(v) = putBool(SETTING_KEY_EQ_AUTO_SUSPEND, v)

    fun preampOf(spk: Int): Float =
        getFloat(if (spk == 0) SETTING_KEY_EQ_PREAMP else SETTING_KEY_EQ_PREAMP_BOTTOM, 0f)

    fun bandCountOf(spk: Int): Int =
        getInt(
            if (spk == 0) SETTING_KEY_EQ_BAND_COUNT else SETTING_KEY_EQ_BAND_COUNT_BOTTOM,
            EqBands.DEFAULT_COUNT,
        ).coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)

    /**
     * Gains of speaker [spk], stored as a comma-separated string under a key
     * carrying the band count (matching the standalone app's storage scheme).
     */
    fun gainsOf(spk: Int): FloatArray {
        val n = bandCountOf(spk)
        val key = if (spk == 0) "$SETTING_KEY_EQ_GAINS_PREFIX$n" else "$SETTING_KEY_EQ_GAINS_BOTTOM_PREFIX$n"
        val raw = android.provider.Settings.System.getStringForUser(
            resolver, key, android.os.UserHandle.USER_CURRENT,
        )
        val parsed = raw?.split(",")?.mapNotNull { it.toFloatOrNull() }
        return if (parsed != null && parsed.size == n) parsed.toFloatArray() else FloatArray(n)
    }

    fun setProfile(spk: Int, preamp: Float, gains: FloatArray) {
        val n = gains.size.coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)
        putFloat(if (spk == 0) SETTING_KEY_EQ_PREAMP else SETTING_KEY_EQ_PREAMP_BOTTOM, preamp)
        putInt(
            if (spk == 0) SETTING_KEY_EQ_BAND_COUNT else SETTING_KEY_EQ_BAND_COUNT_BOTTOM,
            n,
        )
        val key = if (spk == 0) "$SETTING_KEY_EQ_GAINS_PREFIX$n" else "$SETTING_KEY_EQ_GAINS_BOTTOM_PREFIX$n"
        putString(key, gains.take(n).joinToString(","))
    }

    private fun putString(key: String, value: String) {
        android.provider.Settings.System.putStringForUser(
            resolver, key, value, android.os.UserHandle.USER_CURRENT,
        )
    }
}
