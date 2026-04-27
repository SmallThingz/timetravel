package app.timetravel

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

object ExportPresetEditor {
    fun show(
        context: Context,
        presets: IntArray,
        onSave: (IntArray) -> Unit,
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_export_presets, null)
        val layouts = listOf(
            dialogView.findViewById<TextInputLayout>(R.id.preset_1_layout),
            dialogView.findViewById<TextInputLayout>(R.id.preset_2_layout),
            dialogView.findViewById<TextInputLayout>(R.id.preset_3_layout),
            dialogView.findViewById<TextInputLayout>(R.id.preset_4_layout),
        )
        val fields = listOf(
            dialogView.findViewById<EditText>(R.id.preset_1_value),
            dialogView.findViewById<EditText>(R.id.preset_2_value),
            dialogView.findViewById<EditText>(R.id.preset_3_value),
            dialogView.findViewById<EditText>(R.id.preset_4_value),
        )

        presets.forEachIndexed { index, value ->
            fields[index].setText(formatDurationInput(value))
        }

        val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_TimeTravel_AlertDialog)
            .setTitle(R.string.preset_editor_title)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = IntArray(fields.size)
                var valid = true

                fields.forEachIndexed { index, field ->
                    val seconds = parseDurationInput(field.text?.toString().orEmpty())
                    if (seconds == null || seconds <= 0) {
                        layouts[index].error = context.getString(R.string.preset_invalid)
                        valid = false
                    } else {
                        layouts[index].error = null
                        parsed[index] = seconds
                    }
                }

                if (!valid) {
                    return@setOnClickListener
                }

                onSave(parsed)
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
