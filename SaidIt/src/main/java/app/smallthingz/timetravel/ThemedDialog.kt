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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.app.AppCompatDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

internal object ThemedDialog {
    data class Handle(
        val dialog: AppCompatDialog,
        val actionRow: LinearLayout,
        val closeButton: ImageButton,
        val negativeButton: com.google.android.material.button.MaterialButton,
        val positiveButton: com.google.android.material.button.MaterialButton,
    )

    fun createHeaderIconButton(
        context: Context,
        iconResId: Int,
        contentDescription: CharSequence,
        tintAttr: Int = com.google.android.material.R.attr.colorOnSurfaceVariant,
    ): AppCompatImageButton =
        AppCompatImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40))
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_circle_outlined)
            this.contentDescription = contentDescription
            setImageResource(iconResId)
            imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this, tintAttr))
            minimumWidth = 0
            minimumHeight = 0
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
        }

    fun create(
        context: Context,
        title: CharSequence,
        content: View,
        positiveText: CharSequence? = null,
        negativeText: CharSequence? = null,
        headerAccessory: View? = null,
        headerAccessoryGravity: Int = Gravity.CENTER,
        showCloseButton: Boolean = true,
        headerCloseSpacingDp: Int = 12,
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
                LinearLayout(dialogContext).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(dp(dialogContext, 24), dp(dialogContext, 14), dp(dialogContext, 14), 0)
                    addView(
                        TextView(dialogContext).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            text = title
                            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                        },
                    )
                    addView(
                        FrameLayout(dialogContext).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            )
                            headerAccessory?.let {
                                val accessoryLayoutParams =
                                    when (val params = it.layoutParams) {
                                        is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params)
                                        is ViewGroup.MarginLayoutParams -> FrameLayout.LayoutParams(params)
                                        null -> FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                        )
                                        else -> FrameLayout.LayoutParams(params.width, params.height)
                                    }.apply {
                                        gravity = headerAccessoryGravity
                                    }
                                addView(
                                    it,
                                    accessoryLayoutParams,
                                )
                            }
                        },
                    )
                    addView(
                        createHeaderIconButton(
                            context = dialogContext,
                            iconResId = R.drawable.ic_close,
                            contentDescription = dialogContext.getString(R.string.close),
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(dialogContext, 40), dp(dialogContext, 40)).apply {
                                marginStart = if (headerAccessory == null) 0 else dp(dialogContext, headerCloseSpacingDp)
                            }
                            visibility = if (showCloseButton) View.VISIBLE else View.GONE
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
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
        val closeButton = (surface.getChildAt(0) as LinearLayout).getChildAt(2) as ImageButton
        val negativeButton = com.google.android.material.button.MaterialButton(
            ContextThemeWrapper(dialogContext, com.google.android.material.R.style.Widget_Material3_Button_TextButton),
        ).apply {
            text = negativeText ?: ""
            visibility = if (negativeText == null) View.GONE else View.VISIBLE
        }
        val positiveButton = com.google.android.material.button.MaterialButton(
            ContextThemeWrapper(dialogContext, com.google.android.material.R.style.Widget_Material3_Button_TextButton),
        ).apply {
            text = positiveText ?: ""
            visibility = if (positiveText == null) View.GONE else View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = if (negativeText == null) 0 else dp(dialogContext, 8)
            }
        }
        actionRow.addView(negativeButton)
        actionRow.addView(positiveButton)
        if (negativeText == null && positiveText == null) {
            actionRow.visibility = View.GONE
        }

        surface.addView(actionRow)
        dialog.setContentView(
            FrameLayout(dialogContext).apply {
                setPadding(dp(dialogContext, 12), dp(dialogContext, 12), dp(dialogContext, 12), dp(dialogContext, 12))
                addView(surface)
            },
        )
        return Handle(dialog, actionRow, closeButton, negativeButton, positiveButton)
    }

    private fun dp(
        context: Context,
        value: Int,
    ): Int = (context.resources.displayMetrics.density * value).toInt()
}
