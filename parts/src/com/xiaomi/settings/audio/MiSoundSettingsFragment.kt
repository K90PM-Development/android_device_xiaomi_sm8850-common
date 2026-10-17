/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.audio

import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.xiaomi.settings.R
import java.util.Locale

class MiSoundSettingsFragment :
    PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener,
    EqBandEditDialog.Listener {

    companion object {
        private const val TAG = "MiSoundSettingsFragment"
        private const val SETTING_KEY = "misound_enabled"
        private const val MODE_SETTING_KEY = "misound_mode"
        private const val SETTING_KEY_3D_SURROUND = "misound_3d_surround"
        private const val SETTING_KEY_SOUND_ID = "misound_sound_id"
        private const val SETTING_KEY_EQ_COMPENSATION = "misound_eq_compensation"

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
    }

    private val eqSettings by lazy { EqSettings(requireContext().contentResolver) }

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
        bindToggle(SETTING_KEY_SOUND_ID)
        bindToggle(SETTING_KEY_EQ_COMPENSATION)

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
        val checked = when (key) {
            EQ_ENABLED_KEY -> eqSettings.enabled
            EQ_APPLY_EAR_KEY -> eqSettings.applyEarpiece()
            EQ_APPLY_BOTTOM_KEY -> eqSettings.applyBottom()
            EQ_AUTO_ROTATE_KEY -> eqSettings.autoRotate()
            EQ_AUTO_SUSPEND_KEY -> eqSettings.autoSuspend()
            else -> return
        }
        findPreference<SwitchPreferenceCompat>(key)?.apply {
            isChecked = checked
            onPreferenceChangeListener = this@MiSoundSettingsFragment
        }
    }

    /** One-line summary of a speaker profile: preamp and how many bands are set. */
    private fun eqSummary(spk: Int): String {
        val gains = eqSettings.gainsOf(spk)
        val active = gains.count { it != 0f }
        return getString(
            R.string.eq_bands_message,
            String.format(Locale.US, "%+.1f", eqSettings.preampOf(spk)),
            active,
        )
    }

    private fun showEqEditor(spk: Int) {
        val dialog = EqBandEditDialog.newInstance(
            spk,
            eqSettings.preampOf(spk),
            eqSettings.gainsOf(spk),
        )
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
        // Refresh the editor-row summaries.
        findPreference<Preference>(EQ_EDIT_EARPIECE_KEY)?.summary = eqSummary(0)
        findPreference<Preference>(EQ_EDIT_BOTTOM_KEY)?.summary = eqSummary(1)
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
            SETTING_KEY_3D_SURROUND,
            SETTING_KEY_SOUND_ID,
            SETTING_KEY_EQ_COMPENSATION -> {
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
                eqSettings.enabled = newValue as Boolean
                Log.d(TAG, "$EQ_ENABLED_KEY -> $newValue")
            }
            EQ_APPLY_EAR_KEY -> {
                Settings.System.putIntForUser(
                    requireContext().contentResolver,
                    EQ_APPLY_EAR_KEY,
                    if (newValue as Boolean) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            }
            EQ_APPLY_BOTTOM_KEY -> {
                Settings.System.putIntForUser(
                    requireContext().contentResolver,
                    EQ_APPLY_BOTTOM_KEY,
                    if (newValue as Boolean) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            }
            EQ_AUTO_ROTATE_KEY -> {
                Settings.System.putIntForUser(
                    requireContext().contentResolver,
                    EQ_AUTO_ROTATE_KEY,
                    if (newValue as Boolean) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            }
            EQ_AUTO_SUSPEND_KEY -> {
                Settings.System.putIntForUser(
                    requireContext().contentResolver,
                    EQ_AUTO_SUSPEND_KEY,
                    if (newValue as Boolean) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            }
        }
        return true
    }
}
