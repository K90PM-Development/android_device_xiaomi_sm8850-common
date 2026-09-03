/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.audio

import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.xiaomi.settings.R

class MiSoundSettingsFragment :
    PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {

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
    }

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
        }
        return true
    }
}
