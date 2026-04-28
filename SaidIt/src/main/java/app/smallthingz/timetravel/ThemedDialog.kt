package app.smallthingz.timetravel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

internal object ThemedDialog {
    data class Handle(
        val dialog: AppCompatDialog,
        val negativeButton: MaterialButton,
        val positiveButton: MaterialButton,
    )

    fun create(
        context: Context,
        title: CharSequence,
        content: View,
        positiveText: CharSequence,
        negativeText: CharSequence = context.getString(R.string.cancel),
    ): Handle {
        val dialogContext = ContextThemeWrapper(context, R.style.ThemeOverlay_TimeTravel_AlertDialog)
        val dialog = AppCompatDialog(dialogContext).apply {
            setCancelable(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val surface = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = MaterialShapeDrawable(
                ShapeAppearanceModel.builder().setAllCornerSizes(dp(dialogContext, 18).toFloat()).build(),
            ).apply {
                fillColor = ColorStateList.valueOf(
                    MaterialColors.getColor(dialogContext, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0),
                )
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(
                TextView(dialogContext).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(dp(dialogContext, 24), dp(dialogContext, 20), dp(dialogContext, 24), 0)
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                },
            )
            addView(content)
        }

        val actionRow = LinearLayout(dialogContext).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(dialogContext, 16), 0, dp(dialogContext, 16), dp(dialogContext, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(dialogContext, 12)
            }
        }
        val negativeButton = MaterialButton(
            ContextThemeWrapper(dialogContext, com.google.android.material.R.style.Widget_Material3_Button_TextButton),
        ).apply {
            text = negativeText
        }
        val positiveButton = MaterialButton(
            ContextThemeWrapper(dialogContext, com.google.android.material.R.style.Widget_Material3_Button_TextButton),
        ).apply {
            text = positiveText
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(dialogContext, 8)
            }
        }
        actionRow.addView(negativeButton)
        actionRow.addView(positiveButton)

        surface.addView(actionRow)
        dialog.setContentView(
            FrameLayout(dialogContext).apply {
                setPadding(dp(dialogContext, 12), dp(dialogContext, 12), dp(dialogContext, 12), dp(dialogContext, 12))
                addView(surface)
            },
        )
        return Handle(dialog, negativeButton, positiveButton)
    }

    private fun dp(
        context: Context,
        value: Int,
    ): Int = (context.resources.displayMetrics.density * value).toInt()
}
