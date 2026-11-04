/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.audio

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.xiaomi.settings.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Editor for one speaker's virtual bass stage: strength, body, limiter and
 * ceiling. The values are handed to [Listener], which writes them through
 * [BassSettings]; the misound controller then pushes them to the companion
 * AIDL effect.
 */
class BassEditDialog : DialogFragment() {

    interface Listener {
        fun onBassProfileEdited(speaker: Int, strength: Int, body: Int, limiter: Boolean, ceiling: Float)
    }

    companion object {
        private const val ARG_SPEAKER = "speaker"

        fun newInstance(speaker: Int): BassEditDialog =
            BassEditDialog().apply { arguments = Bundle().apply { putInt(ARG_SPEAKER, speaker) } }
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = (targetFragment as? Listener) ?: context as? Listener
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val speaker = requireArguments().getInt(ARG_SPEAKER)
        val context = requireContext()
        val s = BassSettings.active(context.contentResolver)
        val pad = (16 * resources.displayMetrics.density).roundToInt()

        var strength = s.strengthOf(speaker)
        var body = s.bodyOf(speaker)
        var limiter = s.limiterOf(speaker)
        var ceiling = s.ceilingOf(speaker)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        container.addView(
            seekBarRow(
                context, context.getString(R.string.bass_strength_title),
                strength.toFloat(), 0f, 100f, "%",
            ) { v -> strength = v.roundToInt() },
        )
        container.addView(
            seekBarRow(
                context, context.getString(R.string.bass_body_title),
                body.toFloat(), 0f, 100f, "%",
            ) { v -> body = v.roundToInt() },
        )
        container.addView(
            Switch(context).apply {
                text = getString(R.string.bass_limiter_title)
                isChecked = limiter
                setPadding(0, dp(12), 0, dp(4))
                setOnCheckedChangeListener { _, checked -> limiter = checked }
            },
        )
        container.addView(
            seekBarRow(
                context,
                context.getString(R.string.bass_ceiling_title),
                ceiling, BassSettings.CEILING_MIN_DB, BassSettings.CEILING_MAX_DB, " dBFS",
            ) { v -> ceiling = v },
        )

        val scroll = androidx.core.widget.NestedScrollView(context).apply { addView(container) }
        return AlertDialog.Builder(context)
            .setTitle(
                getString(
                    if (speaker == 0) R.string.bass_edit_earpiece else R.string.bass_edit_bottom,
                ),
            )
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                listener?.onBassProfileEdited(
                    speaker,
                    strength.coerceIn(0, 100),
                    body.coerceIn(0, 100),
                    limiter,
                    ceiling.coerceIn(BassSettings.CEILING_MIN_DB, BassSettings.CEILING_MAX_DB),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    /** One labeled seekbar row; [onChange] receives the raw value. */
    private fun seekBarRow(
        context: Context,
        title: String,
        initial: Float,
        lo: Float,
        hi: Float,
        unit: String,
        onChange: (Float) -> Unit,
    ): android.view.View {
        val label = TextView(context).apply {
            text = format(title, initial, unit)
            setPadding(0, dp(8), 0, dp(2))
        }
        val bar = SeekBar(context).apply {
            max = ((hi - lo) * 2f).roundToInt()
            progress = ((initial - lo) * 2f).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = value / 2f + lo
                    label.text = format(title, v, unit)
                    onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun format(title: String, v: Float, unit: String): String = getString(
        R.string.eq_gain_seekbar_label, title, String.format(Locale.US, "%.0f%s", v, unit),
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
