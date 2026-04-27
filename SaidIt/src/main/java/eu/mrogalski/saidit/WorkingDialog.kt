package eu.mrogalski.saidit

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class WorkingDialog : ThemedDialog() {
    var descriptionStringId: Int = R.string.work_default

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(descriptionKey, descriptionStringId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey(descriptionKey) == true) {
            descriptionStringId = savedInstanceState.getInt(descriptionKey)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.progress_dialog, container, false)
        fixFonts(root)
        setDescriptionOnView(root)
        return root
    }

    private fun setDescriptionOnView(root: View) {
        root.findViewById<TextView>(R.id.progress_description).setText(descriptionStringId)
    }

    private val descriptionKey: String
        get() = "${WorkingDialog::class.java.name}_description_id"
}
