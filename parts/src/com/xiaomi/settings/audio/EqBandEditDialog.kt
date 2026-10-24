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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.xiaomi.settings.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Editor dialog for one speaker profile: a preamp seekbar plus one seekbar
 * per band, mirroring the standalone EarpieceEQ app's editor UI.
 *
 * On OK the values are reported to the host activity through [Listener],
 * which writes them to Settings.System; the misound controller's
 * ContentObserver picks the change up live.
 */
class EqBandEditDialog : DialogFragment() {

    interface Listener {
        fun onEqProfileEdited(speaker: Int, preamp: Float, gains: FloatArray)
    }

    companion object {
        private const val ARG_SPEAKER = "speaker"
        private const val ARG_PREAMP = "preamp"
        private const val ARG_GAINS = "gains"

        fun newInstance(speaker: Int, preamp: Float, gains: FloatArray): EqBandEditDialog {
            val args = Bundle().apply {
                putInt(ARG_SPEAKER, speaker)
                putFloat(ARG_PREAMP, preamp)
                putFloatArray(ARG_GAINS, gains)
            }
            return EqBandEditDialog().apply { arguments = args }
        }
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
        val args = requireArguments()
        val speaker = args.getInt(ARG_SPEAKER)
        val preamp = args.getFloat(ARG_PREAMP)
        val gains = requireNotNull(args.getFloatArray(ARG_GAINS))
        val freqs = EqBands.freqs(gains.size)
        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).roundToInt()

        // Seeded with the profile's current preamp so pressing OK without
        // touching that slider keeps it instead of overwriting it with 0 dB.
        var preampOut = preamp
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        container.addView(
            seekBarRow(
                context, context.getString(R.string.eq_preamp_title), preamp,
                EqBands.PRE_MIN_DB, EqBands.PRE_MAX_DB,
            ) { v -> preampOut = v },
        )
        val gainsOut = gains.copyOf()
        for (i in gains.indices) {
            container.addView(
                seekBarRow(
                    context, freqLabel(freqs[i]), gains[i],
                    EqBands.MIN_DB, EqBands.MAX_DB,
                ) { v -> gainsOut[i] = v },
            )
        }

        val scroll = androidx.core.widget.NestedScrollView(context).apply {
            addView(container)
        }
        return AlertDialog.Builder(context)
            .setTitle(
                context.getString(
                    if (speaker == 0) R.string.eq_edit_earpiece else R.string.eq_edit_bottom,
                ),
            )
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                listener?.onEqProfileEdited(
                    speaker,
                    preampOut.coerceIn(EqBands.PRE_MIN_DB, EqBands.PRE_MAX_DB),
                    FloatArray(gainsOut.size) {
                        gainsOut[it].coerceIn(EqBands.MIN_DB, EqBands.MAX_DB)
                    },
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    /** One labeled 0.5 dB-step seekbar row; [onChange] receives the value in dB. */
    private fun seekBarRow(
        context: Context,
        title: String,
        initial: Float,
        lo: Float,
        hi: Float,
        onChange: (Float) -> Unit,
    ): android.view.View {
        val label = TextView(context).apply {
            text = format(title, initial)
            setPadding(0, dp(8), 0, dp(2))
        }
        val bar = SeekBar(context).apply {
            max = ((hi - lo) * 2f).roundToInt()
            progress = ((initial - lo) * 2f).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = value / 2f + lo
                    label.text = format(title, v)
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

    private fun format(title: String, v: Float): String = getString(
        R.string.eq_gain_seekbar_label, title,
        getString(R.string.eq_gain_value, String.format(Locale.US, "%+.1f", v)),
    )

    private fun freqLabel(f: Float): String =
        if (f >= 1000f) {
            getString(R.string.eq_band_freq_khz, String.format(Locale.US, "%.1f", f / 1000f))
        } else {
            getString(R.string.eq_band_freq_hz, f.roundToInt())
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
