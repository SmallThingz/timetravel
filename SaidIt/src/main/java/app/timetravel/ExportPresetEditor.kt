package app.timetravel

import android.content.Context
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

object ExportPresetEditor {
    fun show(
        context: Context,
        presets: IntArray,
        onSave: (IntArray) -> Unit,
    ) {
        val dialogContext = ContextThemeWrapper(context, R.style.ThemeOverlay_TimeTravel_AlertDialog)
        val scrollView = ScrollView(dialogContext).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val content = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = dp(context, 24)
            setPadding(horizontal, dp(context, 12), horizontal, dp(context, 4))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scrollView.addView(content)

        content.addView(TextView(dialogContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            text = context.getString(R.string.preset_editor_message)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        val labels = listOf(
            R.string.preset_one_label,
            R.string.preset_two_label,
            R.string.preset_three_label,
            R.string.preset_four_label,
        )
        val layouts = mutableListOf<TextInputLayout>()
        val fields = mutableListOf<EditText>()

        labels.forEachIndexed { index, labelRes ->
            val layout = TextInputLayout(dialogContext).apply {
                hint = context.getString(labelRes)
                isErrorEnabled = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = if (index == 0) dp(context, 16) else dp(context, 12)
                }
            }
            val field = TextInputEditText(dialogContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                maxLines = 1
            }
            layout.addView(field)
            content.addView(layout)
            layouts += layout
            fields += field
        }

        presets.forEachIndexed { index, value ->
            fields[index].setText(formatDurationInput(value))
        }

        content.addView(buildActionRow(dialogContext).apply {
            val cancelButton = getChildAt(0) as MaterialButton
            val saveButton = getChildAt(1) as MaterialButton
            cancelButton.setOnClickListener { (parent as? ViewGroup)?.rootView?.let { } }
            saveButton.setOnClickListener { (parent as? ViewGroup)?.rootView?.let { } }
        })

        val dialog = AppCompatDialog(dialogContext).apply {
            setCancelable(true)
        }
        val cancelButton = (content.getChildAt(content.childCount - 1) as LinearLayout).getChildAt(0) as MaterialButton
        val saveButton = (content.getChildAt(content.childCount - 1) as LinearLayout).getChildAt(1) as MaterialButton
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
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
            if (!valid) return@setOnClickListener
            onSave(parsed)
            dialog.dismiss()
        }

        val surface = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            background = MaterialShapeDrawable(
                ShapeAppearanceModel.builder().setAllCornerSizes(dp(context, 18).toFloat()).build(),
            ).apply {
                fillColor = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(dialogContext, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0),
                )
            }
            addView(TextView(dialogContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(dp(context, 24), dp(context, 20), dp(context, 24), 0)
                text = context.getString(R.string.preset_editor_title)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            })
            addView(scrollView)
        }
        dialog.setContentView(surface)
        fields.firstOrNull()?.requestFocus()
        dialog.show()
    }

    private fun buildActionRow(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 12)
            }
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                text = context.getString(R.string.cancel)
            })
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                text = context.getString(R.string.save)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(context, 8)
                }
            })
        }
    }

    private fun dp(
        context: Context,
        value: Int,
    ): Int = (context.resources.displayMetrics.density * value).toInt()
}
