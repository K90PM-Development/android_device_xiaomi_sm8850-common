/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * DynamicsProcessing pre-EQ stage attached to the global output mix
 * (session 0), ported from the EarpieceEQ app's legacy mode.
 *
 * Each physical channel (L/R) can carry its own EQ: the earpiece and the
 * bottom speaker are separate speakers on separate channels, so cutting or
 * boosting a band only on one of them is possible. Gains come from
 * [EqSettings] (Settings.System), interpolated onto the fixed internal band
 * grid by [EqBands.interpolate].
 *
 * All methods are guarded so a failure never tears down the MiSound
 * controller that hosts this engine.
 */
internal object MiSoundEqEngine {
    private const val TAG = "MiSoundEqEngine"
    private const val CHANNELS = 2

    /** Per-channel profile: preamp + interpolated gains on the internal grid. null = flat. */
    private class Prof(val preamp: Float, val gains: FloatArray)

    @Volatile
    private var chanProf: Array<Prof?> = arrayOfNulls(2)

    /** True while an external output (headphones/USB/BT) is connected. */
    @Volatile
    var suspended = false
        private set

    /** Called by [MiSoundEqController] when an external output connects/disconnects. */
    @Synchronized
    fun setSuspended(value: Boolean) {
        suspended = value
    }

    private var effect: DynamicsProcessing? = null

    /** Application context captured on first use (the engine is an object, not per-instance). */
    @Volatile
    private var appContext: Context? = null

    /**
     * Maps logical speaker (0 = earpiece, 1 = bottom) to physical channel.
     * Portrait/90° is 0 -> L, 1 -> R; 180°/270° swap L/R from the screen's
     * point of view, so the mapping flips to keep the earpiece the earpiece.
     *
     * While an external output is routed, the channels are an ordinary
     * stereo pair that does not follow the screen, so L/R stay fixed.
     */
    fun physicalChannel(context: Context?, s: EqSettings, base: Int = 0): Int {
        val b = if (base == 0) 0 else 1
        if (s.profileId.isNotEmpty() || !s.autoRotate) return b
        val rotation = try {
            (context ?: appContext)?.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } catch (t: Throwable) {
            Surface.ROTATION_0
        }
        return if (rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270) 1 - b else b
    }

    /** Recomputes the per-channel profiles from the current settings. */
    private fun updateTargets(s: EqSettings) {
        val earPhys = physicalChannel(null, s, 0)
        val botPhys = 1 - earPhys
        val arr = arrayOfNulls<Prof>(2)
        if (s.applyEarpiece) arr[earPhys] = Prof(s.preampOf(0), EqBands.interpolate(s.gainsOf(0)))
        if (s.applyBottom) arr[botPhys] = Prof(s.preampOf(1), EqBands.interpolate(s.gainsOf(1)))
        chanProf = arr
    }

    /**
     * Captures the application context. Must be called before [apply].
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Makes sure a live DynamicsProcessing instance is attached to session 0
     * with the current profiles, or releases it when the EQ is off, both
     * channels are flat or an external output is connected.
     */
    @Synchronized
    fun apply(s: EqSettings) {
        updateTargets(s)
        val active = s.enabled && !suspended && chanProf.any { it != null }
        if (!active) {
            release()
            return
        }
        val dp = effect?.takeIf { alive(it) } ?: try {
            DynamicsProcessing(0, 0, buildConfig()).also { effect = it }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to create DynamicsProcessing on session 0", t)
            effect = null
            return
        }
        try {
            applyTo(dp)
            dp.setEnabled(true)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to apply earpiece EQ", t)
            release()
        }
    }

    @Synchronized
    fun release() {
        effect?.let { dp ->
            try {
                dp.setEnabled(false)
            } catch (_: Throwable) {
            }
            try {
                dp.release()
            } catch (_: Throwable) {
            }
        }
        effect = null
    }

    private fun alive(dp: DynamicsProcessing): Boolean = try {
        dp.hasControl()
    } catch (t: IllegalStateException) {
        false
    }

    /** Re-applies the current profiles to a live effect (settings change / rotation). */
    @Synchronized
    fun refresh(s: EqSettings) {
        if (effect == null) {
            apply(s)
            return
        }
        updateTargets(s)
        val dp = effect ?: return
        try {
            applyTo(dp)
        } catch (t: Throwable) {
            Log.e(TAG, "refresh failed, recreating effect", t)
            release()
            apply(s)
        }
    }

    private fun inputGain(ch: Int): Float = chanProf[ch]?.preamp ?: 0f

    private fun makeEq(ch: Int): DynamicsProcessing.Eq {
        val p = chanProf[ch]
        val grid = EqBands.GRID
        val eq = DynamicsProcessing.Eq(true, p != null, grid.size)
        for (i in grid.indices) {
            eq.setBand(i, DynamicsProcessing.EqBand(true, grid[i], if (p != null) p.gains[i] else 0f))
        }
        return eq
    }

    private fun buildConfig(): DynamicsProcessing.Config {
        val b = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, EqBands.GRID.size, // pre-EQ only
            false, 0,                // MBC: unused
            false, 0,                // post-EQ: unused
            false,                   // limiter: unused
        )
        for (ch in 0 until CHANNELS) {
            b.setInputGainByChannelIndex(ch, inputGain(ch))
            b.setPreEqByChannelIndex(ch, makeEq(ch))
        }
        return b.build()
    }

    private fun applyTo(dp: DynamicsProcessing) {
        for (ch in 0 until CHANNELS) {
            dp.setInputGainbyChannel(ch, inputGain(ch))
            dp.setPreEqByChannelIndex(ch, makeEq(ch))
        }
    }
}
