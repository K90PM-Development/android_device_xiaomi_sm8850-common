/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import android.media.audiofx.AudioEffect
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_DRIVER_MIN_VERSION
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_PARAM_ADVANCED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_PARAM_BODY
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_PARAM_COMMON_EQ
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_PARAM_LIMITED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_PARAM_READBACK_ADVANCED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_PARAM_READBACK_LIMITED
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_PARAM_READBACK_VB
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EB_PARAM_VIRTUAL_BASS
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EFFECT_TYPE_EARPIECE_BASS
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.EFFECT_UUID_EARPIECE_BASS

/**
 * Drives the EarpieceEQ companion AIDL effect (`libearpiecebass_aidl.so`)
 * straight from the app.
 *
 * The upstream app needs a root daemon here only because an ordinary app
 * cannot call [AudioEffect.setParameter]. This app is a platform-signed
 * system app built against the platform API, so the hidden
 * `setParameter(byte[], byte[])` / `getParameter(byte[], byte[])` overloads
 * are callable directly.
 *
 * Wire format: the HAL copies the legacy `effect_param_t` verbatim into
 * `DefaultExtension.bytes`, so a parameter is a little-endian int32 array
 * `[status, psize=4, vsize_bytes, param_id, values...]` where `vsize_bytes`
 * is the byte count of the values that follow. The library checks
 * `words[1] == 4`, that `words[2]` matches the value length it expects for
 * that id, and dispatches on `words[3]`.
 */
internal class EarpieceBassEffect {

    companion object {
        private const val TAG = "MiSoundEarpieceBass"

        /**
         * True when the effect library is registered in
         * audio_effects_config.xml. The set of registered effects cannot
         * change while the audio server runs, so a positive answer is cached.
         */
        @Volatile
        private var knownAvailable = false

        fun available(): Boolean {
            if (knownAvailable) return true
            return try {
                AudioEffect.queryEffects().any { it.uuid == EFFECT_UUID_EARPIECE_BASS }
                    .also { if (it) knownAvailable = true }
            } catch (t: Throwable) {
                Log.w(TAG, "queryEffects failed", t)
                false
            }
        }
    }

    private var effect: AudioEffect? = null

    /** Last values written, so unchanged stages are not re-sent every callback. */
    private var lastVb: IntArray? = null
    private var lastCommonEq: IntArray? = null

    private fun open(): AudioEffect? {
        effect?.let { if (it.hasControl()) return it else close() }
        return try {
            AudioEffect(EFFECT_TYPE_EARPIECE_BASS, EFFECT_UUID_EARPIECE_BASS, 0, 0)
                .also {
                    effect = it
                    reopened = true
                }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to open Earpiece Virtual Bass effect", t)
            null
        }
    }

    /**
     * Set when a fresh AudioEffect instance is attached. AudioFlinger places
     * a new INSERT_LAST effect *before* the older ones, so the pre-EQ has to
     * be recreated after this happens to keep EQ -> bass ordering.
     */
    private var reopened = false

    /** True once after a new effect instance was attached; clears the flag. */
    @Synchronized
    fun consumeReopened(): Boolean {
        val value = reopened
        reopened = false
        return value
    }

    private fun close() {
        effect?.let {
            try {
                it.setEnabled(false)
            } catch (_: Throwable) {
            }
            try {
                it.release()
            } catch (_: Throwable) {
            }
        }
        effect = null
        lastVb = null
        lastCommonEq = null
        // Nothing is attached any more, so a stale flag from a previous
        // instance must not trigger a needless pre-EQ rebuild.
        reopened = false
    }

    private fun setParam(effect: AudioEffect, key: Int, values: IntArray): Boolean {
        val payload = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
        for (v in values) payload.putInt(v)
        return callSet(effect, key, payload.array())
    }

    private fun callSet(effect: AudioEffect, key: Int, payload: ByteArray): Boolean = try {
        effect.setParameter(intBytes(key), payload) == AudioEffect.SUCCESS
    } catch (t: Throwable) {
        Log.e(TAG, "setParameter($key, ${payload.size} B) failed", t)
        false
    }

    /**
     * Reads a value region of [size] bytes for [key].
     *
     * The library answers with a full `effect_param_t` (`[status, psize,
     * vsize, key, driverVersion, ...]`). `native_getParameter` copies
     * `p->vsize` bytes starting at `data + padded(psize)`, i.e. straight after
     * the key — so the returned buffer starts at the driver version, not at
     * the status word.
     */
    private fun callGet(effect: AudioEffect, key: Int, size: Int): ByteBuffer? = try {
        val reply = ByteArray(size)
        if (effect.getParameter(intBytes(key), reply) == size) {
            ByteBuffer.wrap(reply).order(ByteOrder.nativeOrder())
        } else {
            null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "getParameter($key) failed", t)
        null
    }

    private fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(value).array()

    /**
     * Applies one channel's virtual bass profile. `values` is the legacy 22
     * int control block in the same order the standalone app uses:
     * strength[0..1], body[2..3], limiter[4..5], ceiling10[6..7],
     * band[8..9], center[10..11], width10[12..13], auto[14..15],
     * target10[16..17], max10[18..19], meter[20..21].
     *
     * Only as many values as the selected stage needs are sent, and the
     * readback is verified so a silently-missing stage cannot leave the user
     * believing a setting took effect.
     */
    @Synchronized
    fun apply(controls: IntArray): Boolean {
        if (controls.size < 22) {
            // Always driven with the full control block: a block that is at
            // its neutral defaults is how the bass stage is switched off, and
            // it still has to be sent because [applyCommonEq] may be keeping
            // the effect alive with the previous bass settings in place.
            Log.w(TAG, "bass block too short: ${controls.size}")
            return false
        }
        val effect = open() ?: return false

        val limited = controls[4] != 0 || controls[5] != 0
        val body = controls[2] != 0 || controls[3] != 0
        val advanced = (8..9).any { controls[it] != 0 } ||
            (14..15).any { controls[it] != 0 } ||
            (20..21).any { controls[it] != 0 }
        val length = if (advanced) 22 else if (limited) 8 else if (body) 4 else 2
        val key = if (advanced) {
            EB_PARAM_ADVANCED
        } else if (limited) {
            EB_PARAM_LIMITED
        } else if (body) {
            EB_PARAM_BODY
        } else {
            EB_PARAM_VIRTUAL_BASS
        }

        val wire = controls.copyOf(length)
        if (wire.contentEquals(lastVb ?: IntArray(0))) return true
        if (!setParam(effect, key, wire)) {
            Log.w(TAG, "bass stage $key rejected")
            return false
        }
        effect.setEnabled(true)
        lastVb = wire

        // The library only offers three reply shapes: the full 22 controls,
        // the limiter-expanded 8, and the plain strengths+body 32-byte one.
        val readbackKey = when {
            advanced -> EB_PARAM_READBACK_ADVANCED
            limited -> EB_PARAM_READBACK_LIMITED
            else -> EB_PARAM_READBACK_VB
        }
        val readbackSize = when {
            advanced -> 140
            limited -> 64
            // 1002 answers 32 B of strengths+body, so a plain 2-value write is
            // verified only up to the 4 values that reply actually carries.
            else -> 32
        }
        val reply = callGet(effect, readbackKey, readbackSize) ?: return false
        val driver = reply.int
        if (driver < EB_DRIVER_MIN_VERSION) {
            Log.w(TAG, "bass driver $driver too old")
            return false
        }
        for (i in 0 until length) {
            if (reply.int != wire[i]) {
                Log.w(TAG, "bass readback mismatch at $i")
                return false
            }
        }
        return true
    }

    /**
     * Applies the shared ("common") EQ stage: `[on, bandCount, preampDb10,
     * gainsDb10...]`. Independent of the per-channel virtual bass profile.
     */
    @Synchronized
    fun applyCommonEq(enabled: Boolean, preamp: Float, gains: FloatArray): Boolean {
        val n = gains.size.coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)
        val values = IntArray(3 + n)
        values[0] = if (enabled) 1 else 0
        values[1] = n
        values[2] = Math.round(preamp * 10)
        for (i in 0 until n) {
            values[3 + i] = Math.round((gains.getOrElse(i) { 0f }).coerceIn(
                EqBands.MIN_DB, EqBands.MAX_DB,
            ) * 10)
        }

        if (values.contentEquals(lastCommonEq ?: IntArray(0))) return true
        if (values[0] == 0 && lastCommonEq == null) return true

        val effect = open() ?: return false
        if (!setParam(effect, EB_PARAM_COMMON_EQ, values)) {
            Log.w(TAG, "common EQ rejected")
            return false
        }
        // The effect instance is only enabled when a bass stage is written,
        // so a profile that uses the common EQ alone has to enable it here.
        effect.setEnabled(true)
        lastCommonEq = values
        return true
    }

    /** Releases the effect; the DSP stage is torn down on the next enable. */
    @Synchronized
    fun release() = close()
}
