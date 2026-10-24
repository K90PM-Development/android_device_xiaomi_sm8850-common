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
 * Earpiece EQ state for one output profile.
 *
 * With `profileId == ""` this is the built-in speaker profile, stored in the
 * flat `misound_eq_*` Settings.System keys so it stays reachable from
 * `adb shell settings put system ...`. With a route id it is one external
 * device's profile, stored in the [RouteProfiles] JSON blob under the same
 * preference names the standalone app uses ("enabled", "preamp",
 * "band_count", "gains_<n>", "apply_ear", "apply_bottom").
 *
 * Speaker 0 is the earpiece, speaker 1 the bottom speaker; each has its own
 * preamp, band count and gain vector.
 */
internal class EqSettings(
    private val resolver: android.content.ContentResolver,
    val profileId: String = "",
) {

    private val isGlobal = profileId.isEmpty()

    private fun getBool(key: String, default: Boolean) = if (isGlobal) {
        android.provider.Settings.System.getIntForUser(
            resolver, key, if (default) 1 else 0, android.os.UserHandle.USER_CURRENT,
        ) == 1
    } else {
        RouteProfiles.prefInt(resolver, profileId, profileKey(key), if (default) 1 else 0) == 1
    }

    private fun putBool(key: String, value: Boolean) {
        if (isGlobal) {
            android.provider.Settings.System.putIntForUser(
                resolver, key, if (value) 1 else 0, android.os.UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfiles.putPrefInt(resolver, profileId, profileKey(key), if (value) 1 else 0)
        }
    }

    private fun getFloat(key: String, default: Float) = if (isGlobal) {
        android.provider.Settings.System.getFloatForUser(
            resolver, key, default, android.os.UserHandle.USER_CURRENT,
        )
    } else {
        RouteProfiles.prefFloat(resolver, profileId, profileKey(key), default)
    }

    private fun putFloat(key: String, value: Float) {
        if (isGlobal) {
            android.provider.Settings.System.putFloatForUser(
                resolver, key, value, android.os.UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfiles.putPrefFloat(resolver, profileId, profileKey(key), value)
        }
    }

    private fun getInt(key: String, default: Int) = if (isGlobal) {
        android.provider.Settings.System.getIntForUser(
            resolver, key, default, android.os.UserHandle.USER_CURRENT,
        )
    } else {
        RouteProfiles.prefInt(resolver, profileId, profileKey(key), default)
    }

    private fun putInt(key: String, value: Int) {
        if (isGlobal) {
            android.provider.Settings.System.putIntForUser(
                resolver, key, value, android.os.UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfiles.putPrefInt(resolver, profileId, profileKey(key), value)
        }
    }

    private fun getString(key: String): String? = if (isGlobal) {
        android.provider.Settings.System.getStringForUser(
            resolver, key, android.os.UserHandle.USER_CURRENT,
        )
    } else {
        RouteProfiles.prefString(resolver, profileId, profileKey(key))
    }

    private fun putString(key: String, value: String) {
        if (isGlobal) {
            android.provider.Settings.System.putStringForUser(
                resolver, key, value, android.os.UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfiles.putPrefString(resolver, profileId, profileKey(key), value)
        }
    }

    /**
     * Maps a global Settings.System key to the short name used inside a
     * profile, matching the standalone app's preference names.
     */
    private fun profileKey(key: String): String = when (key) {
        SETTING_KEY_EQ_ENABLED -> "enabled"
        SETTING_KEY_EQ_APPLY_EAR -> "apply_ear"
        SETTING_KEY_EQ_APPLY_BOTTOM -> "apply_bottom"
        SETTING_KEY_EQ_PREAMP -> "preamp"
        SETTING_KEY_EQ_BAND_COUNT -> "band_count"
        SETTING_KEY_EQ_PREAMP_BOTTOM -> "b_preamp"
        SETTING_KEY_EQ_BAND_COUNT_BOTTOM -> "b_band_count"
        else -> when {
            key.startsWith(SETTING_KEY_EQ_GAINS_BOTTOM_PREFIX) ->
                "b_gains_" + key.removePrefix(SETTING_KEY_EQ_GAINS_BOTTOM_PREFIX)
            key.startsWith(SETTING_KEY_EQ_GAINS_PREFIX) ->
                "gains_" + key.removePrefix(SETTING_KEY_EQ_GAINS_PREFIX)
            else -> key
        }
    }

    var enabled: Boolean
        get() = getBool(
            SETTING_KEY_EQ_ENABLED,
            // External devices start with processing off: a profile written
            // for one headset must never surprise the next connection.
            if (isGlobal) DEFAULT_EQ_ENABLED == 1 else false,
        )
        set(v) = putBool(SETTING_KEY_EQ_ENABLED, v)

    /** Apply EQ to the earpiece channel (speaker 0). */
    var applyEarpiece: Boolean
        get() = getBool(SETTING_KEY_EQ_APPLY_EAR, true)
        set(v) = putBool(SETTING_KEY_EQ_APPLY_EAR, v)

    /** Apply EQ to the bottom speaker channel (speaker 1). */
    var applyBottom: Boolean
        get() = getBool(SETTING_KEY_EQ_APPLY_BOTTOM, true)
        set(v) = putBool(SETTING_KEY_EQ_APPLY_BOTTOM, v)

    /**
     * Auto-swap L/R so the earpiece stays the earpiece in reverse orientations.
     * Rotation only makes sense for the built-in speakers; external outputs
     * are a normal stereo pair and keep a fixed L/R, so the flag is forced off
     * per-profile by [physicalChannel] rather than written here.
     */
    var autoRotate: Boolean
        get() = isGlobal && getBool(SETTING_KEY_EQ_AUTO_ROTATE, true)
        // Device-wide like [autoSuspend]: always written to the global key,
        // never into an external device's profile.
        set(v) {
            android.provider.Settings.System.putIntForUser(
                resolver, SETTING_KEY_EQ_AUTO_ROTATE, if (v) 1 else 0,
                android.os.UserHandle.USER_CURRENT,
            )
        }

    /**
     * Suspend processing while an external output is connected. Device-wide:
     * it always lives in the built-in profile, whichever profile is active,
     * matching [RouteProfiles.autoDisable].
     */
    var autoSuspend: Boolean
        get() = android.provider.Settings.System.getIntForUser(
            resolver, SETTING_KEY_EQ_AUTO_SUSPEND, 1, android.os.UserHandle.USER_CURRENT,
        ) == 1
        set(v) {
            android.provider.Settings.System.putIntForUser(
                resolver, SETTING_KEY_EQ_AUTO_SUSPEND, if (v) 1 else 0,
                android.os.UserHandle.USER_CURRENT,
            )
        }

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
        val parsed = getString(key)?.split(",")?.mapNotNull { it.toFloatOrNull() }
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

    /** Settings for the profile of the output currently routed to. */
    companion object {
        fun active(resolver: android.content.ContentResolver) =
            EqSettings(resolver, RouteProfiles.currentId(resolver))
    }
}

/**
 * Virtual-bass / ceiling / common-EQ state for one output profile, stored next
 * to the EQ values and using the standalone app's preference names.
 *
 * Values are kept in the DSP's own integer units (percent, and dB x10) so the
 * wire block can be built without a round-trip through floats.
 */
internal class BassSettings(
    private val resolver: android.content.ContentResolver,
    val profileId: String = "",
) {
    private val isGlobal = profileId.isEmpty()

    /** Per-channel key; speaker 2 is the shared (both-channel) bass stage. */
    private fun k(base: String, spk: Int) = when (spk) {
        0 -> base
        1 -> "b_$base"
        else -> "cb_$base"
    }

    /**
     * Settings.System is a shared namespace, so the built-in profile prefixes
     * its keys; inside the per-device JSON blob the short names are private.
     */
    private fun gk(key: String) = if (isGlobal) GLOBAL_PREFIX + key else key

    private fun getInt(key: String, def: Int) = if (isGlobal) {
        android.provider.Settings.System.getIntForUser(
            resolver, gk(key), def, android.os.UserHandle.USER_CURRENT,
        )
    } else {
        RouteProfiles.prefInt(resolver, profileId, key, def)
    }

    private fun putInt(key: String, value: Int) {
        if (isGlobal) {
            android.provider.Settings.System.putIntForUser(
                resolver, gk(key), value, android.os.UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfiles.putPrefInt(resolver, profileId, key, value)
        }
    }

    private fun getFloat(key: String, def: Float) = if (isGlobal) {
        android.provider.Settings.System.getFloatForUser(
            resolver, gk(key), def, android.os.UserHandle.USER_CURRENT,
        )
    } else {
        RouteProfiles.prefFloat(resolver, profileId, key, def)
    }

    private fun putFloat(key: String, value: Float) {
        if (isGlobal) {
            android.provider.Settings.System.putFloatForUser(
                resolver, gk(key), value, android.os.UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfiles.putPrefFloat(resolver, profileId, key, value)
        }
    }

    fun strengthOf(spk: Int): Int = getInt(k("vb_strength", spk), 0).coerceIn(0, 100)

    fun setStrength(spk: Int, value: Int) = putInt(k("vb_strength", spk), value.coerceIn(0, 100))

    fun bodyOf(spk: Int): Int = getInt(k("bass_body", spk), 0).coerceIn(0, 100)

    fun setBody(spk: Int, value: Int) = putInt(k("bass_body", spk), value.coerceIn(0, 100))

    fun limiterOf(spk: Int): Boolean = getInt(k("bass_limit_on", spk), 0) == 1

    fun setLimiter(spk: Int, on: Boolean) = putInt(k("bass_limit_on", spk), if (on) 1 else 0)

    fun ceilingOf(spk: Int): Float = getFloat(k("bass_ceiling", spk), -12f)
        .takeIf { it.isFinite() }?.coerceIn(-36f, -3f) ?: -12f

    fun setCeiling(spk: Int, db: Float) = putFloat(
        k("bass_ceiling", spk),
        if (db.isFinite()) db.coerceIn(-36f, -3f) else -12f,
    )

    fun bandOf(spk: Int): Boolean = getInt(k("bass_band_on", spk), 0) == 1

    fun centerOf(spk: Int): Int = getInt(k("bass_center", spk), 120).coerceIn(50, 180)

    fun width10Of(spk: Int): Int = getInt(k("bass_width10", spk), 15).coerceIn(10, 25)

    fun autoOf(spk: Int): Boolean = getInt(k("bass_auto_on", spk), 0) == 1

    fun target10Of(spk: Int): Int = getInt(k("bass_target10", spk), -240).coerceIn(-360, -120)

    fun max10Of(spk: Int): Int = getInt(k("bass_max10", spk), 60).coerceIn(0, 120)

    /**
     * Builds the 22-int control block for the virtual bass stage, with the
     * given logical speaker mapped onto its physical channel.
     *
     * A speaker whose apply toggle is off keeps the neutral default for its
     * channel, matching the standalone app: the block is sent as a whole, so
     * "off" has to be expressed as neutral values rather than omitted.
     */
    fun buildControls(earPhys: Int, botPhys: Int, applyEar: Boolean, applyBottom: Boolean): IntArray {
        val v = intArrayOf(0, 0, 0, 0, 0, 0, -120, -120, 0, 0, 120, 120, 15, 15, 0, 0, -240, -240, 60, 60, 0, 0)
        for (spk in 0..1) {
            if (if (spk == 0) !applyEar else !applyBottom) continue
            // Logical speaker -> physical channel; the block is laid out as
            // 11 value pairs, one per channel.
            val ch = if (spk == 0) earPhys else botPhys
            v[ch] = strengthOf(spk)
            v[2 + ch] = bodyOf(spk)
            v[4 + ch] = if (limiterOf(spk)) 1 else 0
            v[6 + ch] = Math.round(ceilingOf(spk) * 10)
            v[8 + ch] = if (bandOf(spk)) 1 else 0
            v[10 + ch] = centerOf(spk)
            v[12 + ch] = width10Of(spk)
            v[14 + ch] = if (autoOf(spk)) 1 else 0
            v[16 + ch] = target10Of(spk)
            v[18 + ch] = max10Of(spk)
            v[20 + ch] = 0 // meters are not exposed by the settings UI
        }
        return v
    }

    /** True when any virtual bass stage is configured, i.e. worth opening the effect. */
    fun anyBass(): Boolean = (0..1).any {
        strengthOf(it) > 0 || bodyOf(it) > 0 || limiterOf(it) ||
            bandOf(it) || autoOf(it)
    }

    // ---- common EQ ----

    var commonEqEnabled: Boolean
        get() = getInt("common_eq_on", 0) == 1
        set(v) = putInt("common_eq_on", if (v) 1 else 0)

    var commonPreamp: Float
        get() = getFloat("c_preamp", 0f).takeIf { it.isFinite() }?.coerceIn(
            EqBands.PRE_MIN_DB, EqBands.PRE_MAX_DB,
        ) ?: 0f
        set(v) = putFloat("c_preamp", if (v.isFinite()) v.coerceIn(EqBands.PRE_MIN_DB, EqBands.PRE_MAX_DB) else 0f)

    var commonBandCount: Int
        get() = getInt("c_band_count", EqBands.DEFAULT_COUNT)
            .coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)
        set(v) = putInt("c_band_count", v.coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT))

    var commonGains: FloatArray
        get() {
            val n = commonBandCount
            val raw = if (isGlobal) {
                android.provider.Settings.System.getStringForUser(
                    resolver, GLOBAL_PREFIX + "common_gains_$n",
                    android.os.UserHandle.USER_CURRENT,
                )
            } else {
                RouteProfiles.prefString(resolver, profileId, "c_gains_$n")
            }
            val parsed = raw?.split(",")?.map { it.toFloatOrNull() ?: 0f }
            return if (parsed != null && parsed.size == n) {
                FloatArray(n) {
                    parsed[it].takeIf { g -> g.isFinite() }
                        ?.coerceIn(EqBands.MIN_DB, EqBands.MAX_DB) ?: 0f
                }
            } else {
                FloatArray(n)
            }
        }
        set(v) {
            val n = v.size.coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)
            putInt("c_band_count", n)
            val text = v.take(n).joinToString(",") {
                (it.takeIf { g -> g.isFinite() }
                    ?.coerceIn(EqBands.MIN_DB, EqBands.MAX_DB) ?: 0f).toString()
            }
            if (isGlobal) {
                android.provider.Settings.System.putStringForUser(
                    resolver, GLOBAL_PREFIX + "common_gains_$n", text,
                    android.os.UserHandle.USER_CURRENT,
                )
            } else {
                RouteProfiles.putPrefString(resolver, profileId, "c_gains_$n", text)
            }
        }

    fun setCommonProfile(preamp: Float, gains: FloatArray) {
        commonPreamp = preamp
        commonGains = gains
    }

    companion object {
        /** Prefix the built-in profile uses in the shared Settings.System namespace. */
        private const val GLOBAL_PREFIX = "misound_bass_"

        /** Every key the built-in profile can write, for the settings observer. */
        fun globalKeys(): List<String> {
            val keys = ArrayList<String>()
            for (base in arrayOf(
                "vb_strength", "bass_body", "bass_limit_on", "bass_ceiling", "bass_band_on",
                "bass_center", "bass_width10", "bass_auto_on", "bass_target10", "bass_max10",
            )) {
                keys.add(GLOBAL_PREFIX + base)
                keys.add(GLOBAL_PREFIX + "b_" + base)
            }
            keys.add(GLOBAL_PREFIX + "common_eq_on")
            keys.add(GLOBAL_PREFIX + "c_preamp")
            keys.add(GLOBAL_PREFIX + "c_band_count")
            return keys
        }

        fun active(resolver: android.content.ContentResolver) =
            BassSettings(resolver, RouteProfiles.currentId(resolver))
    }
}
