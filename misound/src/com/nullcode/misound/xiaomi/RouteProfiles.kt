/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nullcode.misound.xiaomi

import android.content.ContentResolver
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.UserHandle
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_AUTO_SUSPEND
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_PROFILES
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_ROUTE_ACTIVE
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_ROUTE_EXTERNAL
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_ROUTE_KNOWN
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_ROUTE_NAME
import com.nullcode.misound.xiaomi.MiSoundConstants.Companion.SETTING_KEY_EQ_ROUTE_WEAK

/**
 * Per-output-device EQ profiles, ported from the EarpieceEQ app (v0.21).
 *
 * The built-in speaker profile keeps the flat `misound_eq_*` Settings.System
 * keys. Every external output (headphones, USB, Bluetooth ...) gets its own
 * profile stored in a single JSON blob under [SETTING_KEY_EQ_PROFILES], keyed
 * by a SHA-256 identity that survives reconnects (AudioDeviceInfo.id does
 * not). New devices start with processing off and a flat response.
 */
internal object RouteProfiles {

    /** Currently routed media output. */
    data class Route(
        val id: String = "",
        val name: String = "",
        val external: Boolean = false,
        val known: Boolean = true,
        val weakIdentity: Boolean = false,
    )

    /** Stable per-device id: never AudioDeviceInfo.id, it changes per connection. */
    fun identity(family: String, address: String, name: String): String {
        val raw = family + "|" + (address.trim().takeIf { it.isNotEmpty() } ?: "name:" + name.trim())
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
    }

    private val ID_PATTERN = Regex("[0-9a-f]{64}")

    // ---- active route ----------------------------------------------------

    fun currentId(resolver: ContentResolver): String =
        Settings.System.getStringForUser(
            resolver, SETTING_KEY_EQ_ROUTE_ACTIVE, UserHandle.USER_CURRENT,
        ).orEmpty()

    fun isExternal(resolver: ContentResolver): Boolean =
        Settings.System.getIntForUser(
            resolver, SETTING_KEY_EQ_ROUTE_EXTERNAL, 0, UserHandle.USER_CURRENT,
        ) == 1

    fun isKnown(resolver: ContentResolver): Boolean =
        Settings.System.getIntForUser(
            resolver, SETTING_KEY_EQ_ROUTE_KNOWN, 1, UserHandle.USER_CURRENT,
        ) == 1

    fun isWeak(resolver: ContentResolver): Boolean =
        Settings.System.getIntForUser(
            resolver, SETTING_KEY_EQ_ROUTE_WEAK, 0, UserHandle.USER_CURRENT,
        ) == 1

    fun activeName(resolver: ContentResolver): String =
        Settings.System.getStringForUser(
            resolver, SETTING_KEY_EQ_ROUTE_NAME, UserHandle.USER_CURRENT,
        ).orEmpty()

    /**
     * Pause processing for earphones. Only meaningful for external routes; it
     * lives in the built-in profile so it applies device-wide.
     */
    fun autoDisable(resolver: ContentResolver): Boolean =
        Settings.System.getIntForUser(
            resolver, SETTING_KEY_EQ_AUTO_SUSPEND, 1, UserHandle.USER_CURRENT,
        ) == 1

    /**
     * False when the output could not be identified, or when an external
     * output is connected and processing is paused for external outputs.
     */
    fun processingAllowed(resolver: ContentResolver): Boolean =
        isKnown(resolver) && (!isExternal(resolver) || !autoDisable(resolver))

    // ---- route detection -------------------------------------------------

    private val EXTERNAL_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_IP,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_DOCK_ANALOG,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_BUS,
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    )

    /** Connected external outputs (headphones, USB, BT ...). */
    fun external(context: Context): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in EXTERNAL_TYPES }

    /**
     * Queries the MEDIA routing rather than the list of connected devices:
     * a paired-but-unused Bluetooth headset must not make the built-in
     * profile look external. Never creates an AudioTrack.
     */
    fun route(context: Context): Route {
        val am = context.getSystemService(AudioManager::class.java) ?: return Route(known = false)
        val routed = try {
            am.getAudioDevicesForAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
        if (routed != null) {
            val ext = routed.filter { it.type in EXTERNAL_TYPES }.distinctBy { key(it) }
            if (ext.size == 1 && routed.all { it.type in EXTERNAL_TYPES }) {
                return info(context, ext[0])
            }
            if (ext.isEmpty() && routed.all {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
            ) {
                return Route()
            }
            return Route(external = true, known = false)
        }
        // No routing information: with nothing external connected the
        // built-in profile is unambiguous, otherwise pause rather than guess.
        return if (external(context).isEmpty()) Route() else Route(external = true, known = false)
    }

    private fun family(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        else -> "type$type"
    }

    private fun key(d: AudioDeviceInfo) =
        identity(family(d.type), d.address.orEmpty(), d.productName.toString())

    private fun info(context: Context, d: AudioDeviceInfo): Route {
        val name = d.productName.toString()
        val fam = family(d.type)
        if (fam == "usb") {
            // ALSA card numbers in AudioDeviceInfo.address change on
            // reconnection; prefer the USB serial, else the model identity.
            val usb = try {
                context.getSystemService(UsbManager::class.java)?.deviceList?.values
                    ?.filter { device ->
                        (0 until device.interfaceCount).any {
                            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
                        }
                    }.orEmpty()
            } catch (t: Throwable) {
                emptyList()
            }
            val device = usb.filter { it.productName == name }.singleOrNull() ?: usb.singleOrNull()
            if (device != null) {
                val serial = try {
                    device.serialNumber.orEmpty()
                } catch (t: SecurityException) {
                    ""
                }
                val usbFamily = "usb:${device.vendorId}:${device.productId}"
                return Route(
                    identity(usbFamily, serial, device.productName ?: name),
                    name, true, true, serial.isEmpty(),
                )
            }
            return Route(identity("usb", "", name), name, true, true, true)
        }
        val address = d.address.orEmpty().let {
            if (fam == "bluetooth") it.lowercase(Locale.ROOT) else it
        }
        return Route(identity(fam, address, name), name, true, true, address.isBlank() || fam == "wired")
    }

    // ---- profile selection -----------------------------------------------

    /** Re-detects the routed output and switches to its profile. True on change. */
    @Synchronized
    fun update(context: Context): Boolean = select(context, route(context))

    /**
     * Switches the active profile. Returns true when the active profile id,
     * the external flag or the known flag changed, i.e. when the running
     * processing has to be torn down and rebuilt.
     */
    @Synchronized
    fun select(context: Context, route: Route): Boolean {
        val resolver = context.contentResolver
        val id = if (route.external && route.known) route.id else ""
        if (id.isNotEmpty() && !ID_PATTERN.matches(id)) {
            return false
        }
        val changed = currentId(resolver) != id ||
            isExternal(resolver) != route.external ||
            isKnown(resolver) != route.known
        if (id.isNotEmpty()) {
            seedProfile(context, id, route.name)
        }
        if (changed ||
            activeName(resolver) != route.name ||
            isWeak(resolver) != route.weakIdentity
        ) {
            Settings.System.putStringForUser(
                resolver, SETTING_KEY_EQ_ROUTE_ACTIVE, id, UserHandle.USER_CURRENT,
            )
            Settings.System.putIntForUser(
                resolver, SETTING_KEY_EQ_ROUTE_EXTERNAL,
                if (route.external) 1 else 0, UserHandle.USER_CURRENT,
            )
            Settings.System.putIntForUser(
                resolver, SETTING_KEY_EQ_ROUTE_KNOWN,
                if (route.known) 1 else 0, UserHandle.USER_CURRENT,
            )
            Settings.System.putStringForUser(
                resolver, SETTING_KEY_EQ_ROUTE_NAME, route.name, UserHandle.USER_CURRENT,
            )
            Settings.System.putIntForUser(
                resolver, SETTING_KEY_EQ_ROUTE_WEAK,
                if (route.weakIdentity) 1 else 0, UserHandle.USER_CURRENT,
            )
        }
        return changed
    }

    /** Registers a new device and, the first time, gives it a safe flat profile. */
    private fun seedProfile(context: Context, id: String, name: String) {
        val resolver = context.contentResolver
        val store = readStore(resolver)
        val entry = store.optJSONObject(id)
        if (entry == null || entry.optString("name") != name) {
            val fresh = entry ?: JSONObject()
            fresh.put("name", name)
            if (!fresh.has("prefs")) {
                // Safe defaults for an unknown device: processing off, flat
                // response, both channels enabled. Same as the standalone app.
                fresh.put(
                    "prefs",
                    JSONObject().put("enabled", 0).put("apply_ear", 1).put("apply_bottom", 1),
                )
            }
            store.put(id, fresh)
            writeStore(resolver, store)
        }
    }

    // ---- per-profile preference store --------------------------------------

    private fun readStore(resolver: ContentResolver): JSONObject = try {
        JSONObject(
            Settings.System.getStringForUser(
                resolver, SETTING_KEY_EQ_PROFILES, UserHandle.USER_CURRENT,
            ) ?: "{}",
        )
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun writeStore(resolver: ContentResolver, store: JSONObject) {
        Settings.System.putStringForUser(
            resolver, SETTING_KEY_EQ_PROFILES, store.toString(), UserHandle.USER_CURRENT,
        )
    }

    private fun prefs(resolver: ContentResolver, id: String): JSONObject =
        readStore(resolver).optJSONObject(id)?.optJSONObject("prefs") ?: JSONObject()

    private fun putPref(resolver: ContentResolver, id: String, key: String, value: Any) {
        val store = readStore(resolver)
        val entry = store.optJSONObject(id) ?: JSONObject().put("name", "")
        val values = entry.optJSONObject("prefs") ?: JSONObject()
        values.put(key, value)
        entry.put("prefs", values)
        store.put(id, entry)
        writeStore(resolver, store)
    }

    fun prefInt(resolver: ContentResolver, id: String, key: String, def: Int): Int =
        prefs(resolver, id).optInt(key, def)

    fun prefFloat(resolver: ContentResolver, id: String, key: String, def: Float): Float =
        prefs(resolver, id).optDouble(key, def.toDouble()).toFloat()

    fun prefString(resolver: ContentResolver, id: String, key: String): String? =
        prefs(resolver, id).takeIf { it.has(key) }?.optString(key)

    fun putPrefInt(resolver: ContentResolver, id: String, key: String, value: Int) =
        putPref(resolver, id, key, value)

    fun putPrefFloat(resolver: ContentResolver, id: String, key: String, value: Float) =
        putPref(resolver, id, key, value.toDouble())

    fun putPrefString(resolver: ContentResolver, id: String, key: String, value: String) =
        putPref(resolver, id, key, value)

    /** Short label of the active output, for the settings UI. */
    fun label(context: Context): String {
        val resolver = context.contentResolver
        if (!isKnown(resolver)) {
            return context.getString(R.string.eq_route_unknown)
        }
        if (!isExternal(resolver)) {
            return context.getString(R.string.eq_route_builtin)
        }
        return activeName(resolver).ifEmpty { context.getString(R.string.eq_route_external) }
    }

    /** One-line explanation of the current routing state, for the settings UI. */
    fun hint(context: Context): String {
        val resolver = context.contentResolver
        return when {
            !isKnown(resolver) -> context.getString(R.string.eq_route_unknown_hint)
            !processingAllowed(resolver) -> context.getString(R.string.eq_route_paused_hint)
            else -> context.getString(R.string.eq_route_active_hint) +
                if (isWeak(resolver)) "\n" + context.getString(R.string.eq_route_weak_hint) else ""
        }
    }
}
