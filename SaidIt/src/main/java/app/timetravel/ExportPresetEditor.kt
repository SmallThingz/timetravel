package app.timetravel

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object ExportPresetEditor {
    fun show(
        context: Context,
        presets: IntArray,
        onSave: (IntArray) -> Unit,
    ) {
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_edit_export_presets, null, false)
        val layouts = listOf(
            content.findViewById<TextInputLayout>(R.id.preset_1_layout),
            content.findViewById<TextInputLayout>(R.id.preset_2_layout),
            content.findViewById<TextInputLayout>(R.id.preset_3_layout),
            content.findViewById<TextInputLayout>(R.id.preset_4_layout),
        )
        val fields = listOf<EditText>(
            content.findViewById<TextInputEditText>(R.id.preset_1_value),
            content.findViewById<TextInputEditText>(R.id.preset_2_value),
            content.findViewById<TextInputEditText>(R.id.preset_3_value),
            content.findViewById<TextInputEditText>(R.id.preset_4_value),
        )

        fields.forEach {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            it.maxLines = 1
            it.setSelectAllOnFocus(true)
        }
        presets.forEachIndexed { index, value -> fields[index].setText(formatDurationInput(value)) }

        val handle = ThemedDialog.create(
            context = context,
            title = context.getString(R.string.preset_editor_title),
            content = content,
            positiveText = context.getString(R.string.save),
        )

        handle.negativeButton.setOnClickListener { handle.dialog.dismiss() }
        handle.positiveButton.setOnClickListener {
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
            handle.dialog.dismiss()
        }

        fields.firstOrNull()?.requestFocus()
        handle.dialog.show()
    }
}
