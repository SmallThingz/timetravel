package app.smallthingz.timetravel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val forwardedIntent =
            Intent(context, TimeTravelService::class.java).apply {
                action = intent.action
                intent.extras?.let { putExtras(it) }
                setPackage(context.packageName)
            }
        ContextCompat.startForegroundService(context, forwardedIntent)
    }
}
