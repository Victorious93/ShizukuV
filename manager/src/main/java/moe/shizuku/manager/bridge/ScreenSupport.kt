package moe.shizuku.manager.bridge

import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import com.google.android.material.button.MaterialButton

fun Context.actionButton(parent: ViewGroup, textRes: Int, onClick: () -> Unit): Button =
    MaterialButton(this).apply {
        setText(textRes)
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { onClick() }
        parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "${s / 3600}h ${(s % 3600) / 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}
