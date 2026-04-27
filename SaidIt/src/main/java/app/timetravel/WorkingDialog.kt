package app.timetravel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class WorkingDialog : ThemedDialog() {
    var descriptionStringId: Int = R.string.work_default

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(DESCRIPTION_KEY, descriptionStringId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey(DESCRIPTION_KEY) == true) {
            descriptionStringId = savedInstanceState.getInt(DESCRIPTION_KEY)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.progress_dialog, container, false).also { root ->
            fixFonts(root)
            root.findViewById<TextView>(R.id.progress_description).setText(descriptionStringId)
        }
    }

    private companion object {
        const val DESCRIPTION_KEY = "working_dialog_description_id"
    }
}
