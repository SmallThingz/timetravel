package eu.mrogalski.saidit

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import eu.mrogalski.android.Views

internal object UiFonts {
    private const val BOLD_FONT = "RobotoCondensedBold.ttf"
    private const val REGULAR_FONT = "RobotoCondensed-Regular.ttf"

    private var boldTypefaceCache: Typeface? = null
    private var regularTypefaceCache: Typeface? = null

    private fun boldTypeface(context: Context): Typeface {
        return boldTypefaceCache ?: Typeface.createFromAsset(context.assets, BOLD_FONT).also {
            boldTypefaceCache = it
        }
    }

    private fun regularTypeface(context: Context): Typeface {
        return regularTypefaceCache ?: Typeface.createFromAsset(context.assets, REGULAR_FONT).also {
            regularTypefaceCache = it
        }
    }

    fun styleMainScreen(root: View, context: Context) {
        val boldTypeface = boldTypeface(context)
        val regularTypeface = regularTypeface(context)
        val shadowOffset = context.resources.displayMetrics.density * 2f
        styleHierarchy(root) { view ->
            when (view) {
                is Button -> {
                    view.typeface = boldTypeface
                    view.setShadowLayer(0.01f, 0f, shadowOffset, view.shadowColor)
                }

                is TextView -> view.typeface = regularTypeface
            }
        }
    }

    fun styleSettings(root: View, context: Context) {
        val boldTypeface = boldTypeface(context)
        val regularTypeface = regularTypeface(context)
        styleHierarchy(root) { view ->
            when (view) {
                is Button -> view.typeface = boldTypeface
                is TextView -> {
                    view.typeface = if (view.tag == "bold") {
                        boldTypeface
                    } else {
                        regularTypeface
                    }
                }
            }
        }
    }

    fun styleDialog(root: View, context: Context, titleShadowColorId: Int) {
        val boldTypeface = boldTypeface(context)
        val regularTypeface = regularTypeface(context)
        val shadowColor = ContextCompat.getColor(context, titleShadowColorId)
        val shadowOffset = context.resources.displayMetrics.density * 2f
        styleHierarchy(root) { view ->
            when (view) {
                is Button -> view.typeface = boldTypeface
                is TextView -> {
                    when (view.tag) {
                        "titleBar" -> {
                            view.typeface = boldTypeface
                            view.setShadowLayer(0.01f, 0f, shadowOffset, shadowColor)
                        }

                        "bold" -> view.typeface = boldTypeface
                        else -> view.typeface = regularTypeface
                    }
                }
            }
        }
    }

    private inline fun styleHierarchy(root: View, crossinline block: (View) -> Unit) {
        val rootGroup = root as? ViewGroup ?: return
        Views.search(rootGroup, object : Views.SearchViewCallback {
            override fun onView(view: View, parent: ViewGroup) {
                block(view)
            }
        })
        block(rootGroup)
    }
}
