/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.audio

import android.content.ContentResolver
import android.os.UserHandle
import android.provider.Settings

/**
 * Virtual-bass state for one output profile, from the settings UI side.
 *
 * Mirrors the XiaomiMiSound app's BassSettings, which turns these values into
 * the 22-int control block of the EarpieceEQ companion effect. The built-in
 * speaker profile lives in `misound_bass_*` Settings.System keys, every
 * external output in the `misound_eq_profiles` JSON blob, so the same
 * per-device isolation as the EQ applies.
 */
internal class BassSettings(
    private val resolver: ContentResolver,
    val profileId: String = "",
) {
    private val isGlobal = profileId.isEmpty()

    /** Per-channel key; speaker 0 is the earpiece, 1 the bottom speaker. */
    private fun k(base: String, spk: Int) = if (spk == 0) base else "b_$base"

    private fun getInt(key: String, def: Int): Int = if (isGlobal) {
        Settings.System.getIntForUser(resolver, GLOBAL_PREFIX + key, def, UserHandle.USER_CURRENT)
    } else {
        RouteProfilesUi.prefInt(resolver, profileId, key, def)
    }

    private fun putInt(key: String, value: Int) {
        if (isGlobal) {
            Settings.System.putIntForUser(
                resolver, GLOBAL_PREFIX + key, value, UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfilesUi.putPrefInt(resolver, profileId, key, value)
        }
    }

    private fun getFloat(key: String, def: Float): Float = if (isGlobal) {
        Settings.System.getFloatForUser(
            resolver, GLOBAL_PREFIX + key, def, UserHandle.USER_CURRENT,
        )
    } else {
        RouteProfilesUi.prefFloat(resolver, profileId, key, def)
    }

    private fun putFloat(key: String, value: Float) {
        if (isGlobal) {
            Settings.System.putFloatForUser(
                resolver, GLOBAL_PREFIX + key, value, UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfilesUi.putPrefFloat(resolver, profileId, key, value)
        }
    }

    /** Virtual bass strength in percent (0-100). */
    fun strengthOf(spk: Int): Int = getInt(k("vb_strength", spk), 0).coerceIn(0, 100)

    fun setStrength(spk: Int, value: Int) = putInt(k("vb_strength", spk), value.coerceIn(0, 100))

    /** Body / "thickness" in percent (0-100). */
    fun bodyOf(spk: Int): Int = getInt(k("bass_body", spk), 0).coerceIn(0, 100)

    fun setBody(spk: Int, value: Int) = putInt(k("bass_body", spk), value.coerceIn(0, 100))

    /** Output ceiling limiter, keeps the boosted bass from clipping. */
    fun limiterOf(spk: Int): Boolean = getInt(k("bass_limit_on", spk), 0) == 1

    fun setLimiter(spk: Int, on: Boolean) = putInt(k("bass_limit_on", spk), if (on) 1 else 0)

    /** Ceiling in dBFS (-36..-3). */
    fun ceilingOf(spk: Int): Float = getFloat(k("bass_ceiling", spk), -12f)
        .takeIf { it.isFinite() }?.coerceIn(CEILING_MIN_DB, CEILING_MAX_DB) ?: -12f

    fun setCeiling(spk: Int, db: Float) = putFloat(
        k("bass_ceiling", spk),
        if (db.isFinite()) db.coerceIn(CEILING_MIN_DB, CEILING_MAX_DB) else -12f,
    )

    companion object {
        const val CEILING_MIN_DB = -36f
        const val CEILING_MAX_DB = -3f

        /** Prefix the built-in profile uses in the shared Settings.System namespace. */
        private const val GLOBAL_PREFIX = "misound_bass_"

        /** Settings for the output that is currently routed. */
        fun active(resolver: ContentResolver) =
            BassSettings(resolver, RouteProfilesUi.currentId(resolver))

        /** Every key the built-in profile can write, for reference and adb. */
        fun globalKeys(spk: Int): List<String> {
            val prefix = GLOBAL_PREFIX + (if (spk == 0) "" else "b_")
            return listOf("vb_strength", "bass_body", "bass_limit_on", "bass_ceiling")
                .map { prefix + it }
        }
    }
}
