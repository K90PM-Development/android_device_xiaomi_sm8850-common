/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.audio

import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.xiaomi.settings.R
import java.util.Locale

class MiSoundSettingsFragment :
    PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener,
    EqBandEditDialog.Listener, BassEditDialog.Listener {

    companion object {
        private const val TAG = "MiSoundSettingsFragment"
        private const val SETTING_KEY = "misound_enabled"
        private const val MODE_SETTING_KEY = "misound_mode"
        private const val SETTING_KEY_3D_SURROUND = "misound_3d_surround"

        private const val DEFAULT_ENABLED = 1
        private const val DEFAULT_MODE = 1 // PROFILE_SURROUND (intelligent)
        private const val DEFAULT_TOGGLE = 0

        // Earpiece EQ keys (read/written by the XiaomiMiSound controller).
        private const val EQ_ENABLED_KEY = "misound_eq_enabled"
        private const val EQ_APPLY_EAR_KEY = "misound_eq_apply_ear"
        private const val EQ_APPLY_BOTTOM_KEY = "misound_eq_apply_bottom"
        private const val EQ_AUTO_ROTATE_KEY = "misound_eq_auto_rotate"
        private const val EQ_AUTO_SUSPEND_KEY = "misound_eq_auto_suspend"
        private const val EQ_EDIT_EARPIECE_KEY = "misound_eq_edit_earpiece"
        private const val EQ_EDIT_BOTTOM_KEY = "misound_eq_edit_bottom"
        private const val EQ_RESET_EARPIECE_KEY = "misound_eq_reset_earpiece"
        private const val EQ_RESET_BOTTOM_KEY = "misound_eq_reset_bottom"
        private const val EQ_OUTPUT_KEY = "misound_eq_output"
        private const val EQ_FORGET_OUTPUT_KEY = "misound_eq_forget_output"
        private const val BASS_EARPIECE_KEY = "misound_bass_edit_earpiece"
        private const val BASS_BOTTOM_KEY = "misound_bass_edit_bottom"
    }

    /**
     * Settings of the output that is currently routed: the built-in speaker
     * profile, or the connected device's own profile. Every read below goes
     * through this, so editing headphones never touches the speaker tuning.
     */
    private val eqSettings get() = EqSettings.active(requireContext().contentResolver)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        Log.d(TAG, "onCreatePreferences")
        setPreferencesFromResource(R.xml.settings_misound, rootKey)

        val isEnabled = Settings.System.getIntForUser(
            requireContext().contentResolver,
            SETTING_KEY,
            DEFAULT_ENABLED,
            UserHandle.USER_CURRENT,
        ) == 1
        val currentMode = Settings.System.getIntForUser(
            requireContext().contentResolver,
            MODE_SETTING_KEY,
            DEFAULT_MODE,
            UserHandle.USER_CURRENT,
        )

        findPreference<SwitchPreferenceCompat>(SETTING_KEY)?.apply {
            isChecked = isEnabled
            onPreferenceChangeListener = this@MiSoundSettingsFragment
        }

        val modePref = findPreference<ListPreference>(MODE_SETTING_KEY)
        modePref?.apply {
            value = currentMode.toString()
            summary = entry
            onPreferenceChangeListener = this@MiSoundSettingsFragment
        }

        bindToggle(SETTING_KEY_3D_SURROUND)

        bindEqToggle(EQ_ENABLED_KEY)
        bindEqToggle(EQ_APPLY_EAR_KEY)
        bindEqToggle(EQ_APPLY_BOTTOM_KEY)
        bindEqToggle(EQ_AUTO_ROTATE_KEY)
        bindEqToggle(EQ_AUTO_SUSPEND_KEY)

        findPreference<Preference>(EQ_EDIT_EARPIECE_KEY)?.apply {
            summary = eqSummary(0)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                showEqEditor(0)
                true
            }
        }
        findPreference<Preference>(EQ_EDIT_BOTTOM_KEY)?.apply {
            summary = eqSummary(1)
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                showEqEditor(1)
                true
            }
        }
        findPreference<Preference>(EQ_RESET_EARPIECE_KEY)?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                confirmEqReset(0)
                true
            }
        }
        findPreference<Preference>(EQ_RESET_BOTTOM_KEY)?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                confirmEqReset(1)
                true
            }
        }
        findPreference<Preference>(EQ_FORGET_OUTPUT_KEY)?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                confirmForgetOutput()
                true
            }
        }
        for ((key, spk) in listOf(BASS_EARPIECE_KEY to 0, BASS_BOTTOM_KEY to 1)) {
            findPreference<Preference>(key)?.apply {
                summary = bassSummary(spk)
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    showBassEditor(spk)
                    true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOutputState()
    }

    /**
     * Shows which output the EQ currently applies to, explains when processing
     * is paused, and disables the controls that do not apply to that output.
     */
    private fun refreshOutputState() {
        val context = context ?: return
        val resolver = context.contentResolver
        val known = RouteProfilesUi.isKnown(resolver)
        val external = RouteProfilesUi.isExternal(resolver)
        val name = RouteProfilesUi.activeName(resolver)
        val weak = RouteProfilesUi.isWeak(resolver)

        findPreference<Preference>(EQ_OUTPUT_KEY)?.apply {
            val label = when {
                !known -> getString(R.string.eq_route_unknown)
                !external -> getString(R.string.eq_route_builtin)
                else -> name.ifEmpty { getString(R.string.eq_route_external) }
            }
            val hint = when {
                !known -> getString(R.string.eq_route_unknown_hint)
                external && EqSettings(resolver).autoSuspend() ->
                    getString(R.string.eq_route_paused_hint)
                external -> getString(R.string.eq_route_active_hint) +
                    // Some devices (passive wired headphones, USB audio with
                    // no serial) have no unique address, so their profile is
                    // keyed by type and name and may be shared.
                    if (weak) "\n" + getString(R.string.eq_route_weak_hint) else ""
                else -> ""
            }
            summary = if (hint.isEmpty()) label else "$label\n$hint"
        }

        // Following the screen rotation only makes sense for the built-in
        // speakers; an unidentified output pauses processing altogether.
        findPreference<SwitchPreferenceCompat>(EQ_AUTO_ROTATE_KEY)?.isEnabled = !external
        for (key in listOf(
            EQ_ENABLED_KEY, EQ_APPLY_EAR_KEY, EQ_APPLY_BOTTOM_KEY,
            EQ_EDIT_EARPIECE_KEY, EQ_EDIT_BOTTOM_KEY,
            EQ_RESET_EARPIECE_KEY, EQ_RESET_BOTTOM_KEY,
            BASS_EARPIECE_KEY, BASS_BOTTOM_KEY,
        )) {
            findPreference<Preference>(key)?.isEnabled = known
        }
        findPreference<Preference>(EQ_FORGET_OUTPUT_KEY)?.isVisible = external && known

        // The toggles show the active profile's values, not the global ones.
        bindEqToggle(EQ_ENABLED_KEY)
        bindEqToggle(EQ_APPLY_EAR_KEY)
        bindEqToggle(EQ_APPLY_BOTTOM_KEY)
        bindEqToggle(EQ_AUTO_ROTATE_KEY)
        bindEqToggle(EQ_AUTO_SUSPEND_KEY)
        findPreference<Preference>(EQ_EDIT_EARPIECE_KEY)?.summary = eqSummary(0)
        findPreference<Preference>(EQ_EDIT_BOTTOM_KEY)?.summary = eqSummary(1)
        findPreference<Preference>(BASS_EARPIECE_KEY)?.summary = bassSummary(0)
        findPreference<Preference>(BASS_BOTTOM_KEY)?.summary = bassSummary(1)
    }

    /** One-line summary of a speaker's virtual bass stage. */
    private fun bassSummary(spk: Int): String {
        val b = BassSettings.active(requireContext().contentResolver)
        return getString(
            R.string.bass_bands_message,
            b.strengthOf(spk),
            b.bodyOf(spk),
            getString(
                if (b.limiterOf(spk)) R.string.bass_limiter_on else R.string.bass_limiter_off,
            ),
        )
    }

    private fun showBassEditor(spk: Int) {
        val dialog = BassEditDialog.newInstance(spk)
        dialog.setTargetFragment(this, 0)
        dialog.show(parentFragmentManager, "bass_edit_$spk")
    }

    override fun onBassProfileEdited(
        speaker: Int,
        strength: Int,
        body: Int,
        limiter: Boolean,
        ceiling: Float,
    ) {
        val b = BassSettings.active(requireContext().contentResolver)
        val hadBass = b.strengthOf(speaker) > 0 || b.bodyOf(speaker) > 0 || b.limiterOf(speaker)
        b.setStrength(speaker, strength)
        b.setBody(speaker, body)
        b.setLimiter(speaker, limiter)
        b.setCeiling(speaker, ceiling)
        // The bass stage only runs while the profile's EQ is enabled; turning
        // it on here keeps the common case (bass without a custom EQ curve)
        // from looking like the edit did nothing.
        val nowBass = strength > 0 || body > 0 || limiter
        if (!hadBass && nowBass) {
            val s = eqSettings
            if (!s.enabled) s.enabled = true
        }
        refreshOutputState()
    }

    private fun bindToggle(key: String) {
        val checked = Settings.System.getIntForUser(
            requireContext().contentResolver,
            key,
            DEFAULT_TOGGLE,
            UserHandle.USER_CURRENT,
        ) == 1
        findPreference<SwitchPreferenceCompat>(key)?.apply {
            isChecked = checked
            onPreferenceChangeListener = this@MiSoundSettingsFragment
        }
    }

    /** Like [bindToggle], but the checked state comes from [EqSettings]. */
    private fun bindEqToggle(key: String) {
        val s = eqSettings
        val checked = when (key) {
            EQ_ENABLED_KEY -> s.enabled
            EQ_APPLY_EAR_KEY -> s.applyEarpiece
            EQ_APPLY_BOTTOM_KEY -> s.applyBottom
            EQ_AUTO_ROTATE_KEY -> s.autoRotate()
            EQ_AUTO_SUSPEND_KEY -> s.autoSuspend()
            else -> return
        }
        findPreference<SwitchPreferenceCompat>(key)?.apply {
            isChecked = checked
            onPreferenceChangeListener = this@MiSoundSettingsFragment
        }
    }

    /** One-line summary of a speaker profile: preamp and how many bands are set. */
    private fun eqSummary(spk: Int): String {
        val s = eqSettings
        val gains = s.gainsOf(spk)
        val active = gains.count { it != 0f }
        return getString(
            R.string.eq_bands_message,
            String.format(Locale.US, "%+.1f", s.preampOf(spk)),
            active,
        )
    }

    /** Deletes the connected device's saved profile so it starts flat next time. */
    private fun confirmForgetOutput() {
        val resolver = requireContext().contentResolver
        val id = RouteProfilesUi.currentId(resolver)
        if (id.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.eq_output_clear_title)
            .setMessage(R.string.eq_output_clear_message)
            .setPositiveButton(R.string.eq_output_clear_ok) { _, _ ->
                RouteProfilesUi.forget(resolver, id)
                Toast.makeText(requireContext(), R.string.eq_output_cleared, Toast.LENGTH_SHORT).show()
                refreshOutputState()
            }
            .setNegativeButton(R.string.eq_reset_cancel, null)
            .show()
    }

    private fun showEqEditor(spk: Int) {
        val s = eqSettings
        val dialog = EqBandEditDialog.newInstance(spk, s.preampOf(spk), s.gainsOf(spk))
        dialog.setTargetFragment(this, 0)
        dialog.show(parentFragmentManager, "eq_edit_$spk")
    }

    private fun confirmEqReset(spk: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.eq_reset_title)
            .setMessage(R.string.eq_reset_message)
            .setPositiveButton(R.string.eq_reset_ok) { _, _ ->
                onEqProfileEdited(spk, 0f, FloatArray(eqSettings.bandCountOf(spk)))
            }
            .setNegativeButton(R.string.eq_reset_cancel, null)
            .show()
    }

    override fun onEqProfileEdited(speaker: Int, preamp: Float, gains: FloatArray) {
        eqSettings.setProfile(speaker, preamp, gains)
        refreshOutputState()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference.key) {
            SETTING_KEY -> {
                val isChecked = newValue as Boolean
                Log.d(TAG, "misound_enabled -> $isChecked")
                Settings.System.putIntForUser(
                    requireContext().contentResolver,
                    SETTING_KEY,
                    if (isChecked) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            }
            MODE_SETTING_KEY -> {
                val mode = (newValue as String).toInt()
                Log.d(TAG, "misound_mode -> $mode")
                Settings.System.putIntForUser(
                    requireContext().contentResolver,
                    MODE_SETTING_KEY,
                    mode,
                    UserHandle.USER_CURRENT,
                )
                // Update the visible summary to the new entry label.
                val listPref = preference as ListPreference
                listPref.summary = listPref.entries[listPref.findIndexOfValue(newValue)]
            }
            SETTING_KEY_3D_SURROUND -> {
                val isChecked = newValue as Boolean
                Log.d(TAG, "${preference.key} -> $isChecked")
                Settings.System.putIntForUser(
                    requireContext().contentResolver,
                    preference.key,
                    if (isChecked) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            }
            EQ_ENABLED_KEY -> {
                val s = eqSettings
                s.enabled = newValue as Boolean
                Log.d(TAG, "$EQ_ENABLED_KEY -> $newValue (profile=${s.profileId})")
            }
            EQ_APPLY_EAR_KEY -> {
                val s = eqSettings
                s.applyEarpiece = newValue as Boolean
                Log.d(TAG, "$EQ_APPLY_EAR_KEY -> $newValue (profile=${s.profileId})")
            }
            EQ_APPLY_BOTTOM_KEY -> {
                val s = eqSettings
                s.applyBottom = newValue as Boolean
                Log.d(TAG, "$EQ_APPLY_BOTTOM_KEY -> $newValue (profile=${s.profileId})")
            }
            EQ_AUTO_ROTATE_KEY, EQ_AUTO_SUSPEND_KEY -> {
                // Device-wide controls, always written to the global keys.
                Settings.System.putIntForUser(
                    requireContext().contentResolver,
                    preference.key,
                    if (newValue as Boolean) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            }
        }
        return true
    }
}
