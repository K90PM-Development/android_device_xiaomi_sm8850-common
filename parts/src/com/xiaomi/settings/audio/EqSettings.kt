/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.audio

import android.content.ContentResolver
import android.os.UserHandle
import android.provider.Settings
import org.json.JSONObject

/**
 * Settings.System access for the earpiece EQ, from the settings UI side.
 *
 * Mirrors the XiaomiMiSound app's EqSettings: the built-in speaker profile
 * lives in flat `misound_eq_*` keys, while every external output gets its own
 * profile inside the `misound_eq_profiles` JSON blob, keyed by the route
 * identity. [RouteProfilesUi] decides which one [active] returns.
 */
internal class EqSettings(
    private val resolver: ContentResolver,
    val profileId: String = "",
) {

    private val isGlobal = profileId.isEmpty()

    // ---- built-in (global) keys ----

    private fun getBool(key: String, default: Boolean) =
        Settings.System.getIntForUser(
            resolver, key, if (default) 1 else 0, UserHandle.USER_CURRENT,
        ) == 1

    private fun getFloat(key: String, default: Float) =
        Settings.System.getFloatForUser(resolver, key, default, UserHandle.USER_CURRENT)

    private fun getInt(key: String, default: Int) =
        Settings.System.getIntForUser(resolver, key, default, UserHandle.USER_CURRENT)

    // ---- profile (JSON blob) access ----

    private fun profileKey(key: String): String = when (key) {
        KEY_ENABLED -> "enabled"
        KEY_APPLY_EAR -> "apply_ear"
        KEY_APPLY_BOTTOM -> "apply_bottom"
        KEY_PREAMP -> "preamp"
        KEY_BAND_COUNT -> "band_count"
        KEY_PREAMP_BOTTOM -> "b_preamp"
        KEY_BAND_COUNT_BOTTOM -> "b_band_count"
        else -> key
    }

    private fun prefBool(key: String, default: Boolean) =
        RouteProfilesUi.prefInt(resolver, profileId, profileKey(key), if (default) 1 else 0) == 1

    private fun prefFloat(key: String, default: Float) =
        RouteProfilesUi.prefFloat(resolver, profileId, profileKey(key), default)

    private fun prefInt(key: String, default: Int) =
        RouteProfilesUi.prefInt(resolver, profileId, profileKey(key), default)

    private fun prefString(key: String): String? =
        RouteProfilesUi.prefString(resolver, profileId, profileKey(key))

    private fun putPrefString(key: String, value: String) {
        RouteProfilesUi.putPrefString(resolver, profileId, profileKey(key), value)
    }

    private fun putBool(key: String, value: Boolean) {
        if (isGlobal) {
            Settings.System.putIntForUser(
                resolver, key, if (value) 1 else 0, UserHandle.USER_CURRENT,
            )
        } else {
            RouteProfilesUi.putPrefInt(resolver, profileId, profileKey(key), if (value) 1 else 0)
        }
    }

    var enabled: Boolean
        get() = if (isGlobal) getBool(KEY_ENABLED, true) else prefBool(KEY_ENABLED, false)
        set(v) = putBool(KEY_ENABLED, v)

    var applyEarpiece: Boolean
        get() = if (isGlobal) getBool(KEY_APPLY_EAR, true) else prefBool(KEY_APPLY_EAR, true)
        set(v) = putBool(KEY_APPLY_EAR, v)

    var applyBottom: Boolean
        get() = if (isGlobal) getBool(KEY_APPLY_BOTTOM, true) else prefBool(KEY_APPLY_BOTTOM, true)
        set(v) = putBool(KEY_APPLY_BOTTOM, v)

    fun autoRotate(): Boolean = isGlobal && getBool(KEY_AUTO_ROTATE, true)

    fun autoSuspend(): Boolean = getBool(KEY_AUTO_SUSPEND, true)

    fun preampOf(spk: Int): Float = if (isGlobal) {
        getFloat(if (spk == 0) KEY_PREAMP else KEY_PREAMP_BOTTOM, 0f)
    } else {
        prefFloat(if (spk == 0) KEY_PREAMP else KEY_PREAMP_BOTTOM, 0f)
    }

    fun bandCountOf(spk: Int): Int = (if (isGlobal) {
        getInt(
            if (spk == 0) KEY_BAND_COUNT else KEY_BAND_COUNT_BOTTOM,
            EqBands.DEFAULT_COUNT,
        )
    } else {
        prefInt(
            if (spk == 0) KEY_BAND_COUNT else KEY_BAND_COUNT_BOTTOM,
            EqBands.DEFAULT_COUNT,
        )
    }).coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)

    fun gainsOf(spk: Int): FloatArray {
        val n = bandCountOf(spk)
        val key = if (spk == 0) "gains_$n" else "b_gains_$n"
        val raw = if (isGlobal) {
            Settings.System.getStringForUser(
                resolver,
                if (spk == 0) "misound_eq_gains_$n" else "misound_eq_gains_bottom_$n",
                UserHandle.USER_CURRENT,
            )
        } else {
            prefString(key)
        }
        val parsed = raw?.split(",")?.mapNotNull { it.toFloatOrNull() }
        return if (parsed != null && parsed.size == n) parsed.toFloatArray() else FloatArray(n)
    }

    fun setProfile(spk: Int, preamp: Float, gains: FloatArray) {
        val n = gains.size.coerceIn(EqBands.MIN_COUNT, EqBands.MAX_COUNT)
        val text = gains.take(n).joinToString(",")
        if (isGlobal) {
            val user = UserHandle.USER_CURRENT
            Settings.System.putFloatForUser(
                resolver, if (spk == 0) KEY_PREAMP else KEY_PREAMP_BOTTOM, preamp, user,
            )
            Settings.System.putIntForUser(
                resolver, if (spk == 0) KEY_BAND_COUNT else KEY_BAND_COUNT_BOTTOM, n, user,
            )
            Settings.System.putStringForUser(
                resolver,
                if (spk == 0) "misound_eq_gains_$n" else "misound_eq_gains_bottom_$n",
                text,
                user,
            )
        } else {
            RouteProfilesUi.putPrefFloat(
                resolver, profileId, profileKey(if (spk == 0) KEY_PREAMP else KEY_PREAMP_BOTTOM), preamp,
            )
            RouteProfilesUi.putPrefInt(
                resolver, profileId, profileKey(if (spk == 0) KEY_BAND_COUNT else KEY_BAND_COUNT_BOTTOM), n,
            )
            putPrefString(if (spk == 0) "gains_$n" else "b_gains_$n", text)
        }
    }

    companion object {
        private const val KEY_ENABLED = "misound_eq_enabled"
        private const val KEY_APPLY_EAR = "misound_eq_apply_ear"
        private const val KEY_APPLY_BOTTOM = "misound_eq_apply_bottom"
        private const val KEY_AUTO_ROTATE = "misound_eq_auto_rotate"
        private const val KEY_AUTO_SUSPEND = "misound_eq_auto_suspend"
        private const val KEY_PREAMP = "misound_eq_preamp"
        private const val KEY_BAND_COUNT = "misound_eq_band_count"
        private const val KEY_PREAMP_BOTTOM = "misound_eq_preamp_bottom"
        private const val KEY_BAND_COUNT_BOTTOM = "misound_eq_band_count_bottom"

        /** Settings for the output that is currently routed. */
        fun active(resolver: ContentResolver) =
            EqSettings(resolver, RouteProfilesUi.currentId(resolver))
    }
}

/**
 * Read side of the per-output profile store, for the settings UI.
 *
 * The blob is written by the controller in the XiaomiMiSound app; this app
 * only ever reads the route state and rewrites a single profile when the user
 * edits it from the settings page.
 */
internal object RouteProfilesUi {

    const val KEY_PROFILES = "misound_eq_profiles"
    const val KEY_ROUTE_ACTIVE = "misound_eq_route_active"
    const val KEY_ROUTE_EXTERNAL = "misound_eq_route_external"
    const val KEY_ROUTE_KNOWN = "misound_eq_route_known"
    const val KEY_ROUTE_NAME = "misound_eq_route_name"
    const val KEY_ROUTE_WEAK = "misound_eq_route_weak"

    private val ID_PATTERN = Regex("[0-9a-f]{64}")

    fun currentId(resolver: ContentResolver): String =
        Settings.System.getStringForUser(resolver, KEY_ROUTE_ACTIVE, UserHandle.USER_CURRENT)
            .orEmpty()
            .takeIf { ID_PATTERN.matches(it) }
            .orEmpty()

    fun isExternal(resolver: ContentResolver): Boolean =
        Settings.System.getIntForUser(
            resolver, KEY_ROUTE_EXTERNAL, 0, UserHandle.USER_CURRENT,
        ) == 1

    fun isKnown(resolver: ContentResolver): Boolean =
        Settings.System.getIntForUser(
            resolver, KEY_ROUTE_KNOWN, 1, UserHandle.USER_CURRENT,
        ) == 1

    fun isWeak(resolver: ContentResolver): Boolean =
        Settings.System.getIntForUser(
            resolver, KEY_ROUTE_WEAK, 0, UserHandle.USER_CURRENT,
        ) == 1

    fun activeName(resolver: ContentResolver): String =
        Settings.System.getStringForUser(resolver, KEY_ROUTE_NAME, UserHandle.USER_CURRENT)
            .orEmpty()

    fun readStore(resolver: ContentResolver): JSONObject = try {
        JSONObject(
            Settings.System.getStringForUser(resolver, KEY_PROFILES, UserHandle.USER_CURRENT)
                ?: "{}",
        )
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun writeStore(resolver: ContentResolver, store: JSONObject) {
        Settings.System.putStringForUser(
            resolver, KEY_PROFILES, store.toString(), UserHandle.USER_CURRENT,
        )
    }


    private fun prefs(resolver: ContentResolver, id: String): JSONObject =
        readStore(resolver).optJSONObject(id)?.optJSONObject("prefs") ?: JSONObject()

    fun prefInt(resolver: ContentResolver, id: String, key: String, def: Int): Int =
        prefs(resolver, id).optInt(key, def)

    fun prefFloat(resolver: ContentResolver, id: String, key: String, def: Float): Float =
        prefs(resolver, id).optDouble(key, def.toDouble()).toFloat()

    fun prefString(resolver: ContentResolver, id: String, key: String): String? =
        prefs(resolver, id).takeIf { it.has(key) }?.optString(key)

    private fun put(resolver: ContentResolver, id: String, key: String, value: Any) {
        val store = readStore(resolver)
        val entry = store.optJSONObject(id) ?: JSONObject()
        val values = entry.optJSONObject("prefs") ?: JSONObject()
        values.put(key, value)
        entry.put("prefs", values)
        store.put(id, entry)
        writeStore(resolver, store)
    }

    fun putPrefInt(resolver: ContentResolver, id: String, key: String, value: Int) =
        put(resolver, id, key, value)

    fun putPrefFloat(resolver: ContentResolver, id: String, key: String, value: Float) =
        put(resolver, id, key, value.toDouble())

    fun putPrefString(resolver: ContentResolver, id: String, key: String, value: String) =
        put(resolver, id, key, value)

    /** Drops a saved profile; the device is recreated flat and disabled next time. */
    fun forget(resolver: ContentResolver, id: String) {
        val store = readStore(resolver)
        if (!store.has(id)) return
        store.remove(id)
        writeStore(resolver, store)
    }
}
