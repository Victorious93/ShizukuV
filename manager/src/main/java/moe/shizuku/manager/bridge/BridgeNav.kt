package moe.shizuku.manager.bridge

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomnavigation.BottomNavigationView
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.settings.SettingsActivity

/** Shared bottom navigation: Status, Apps, Activity, Diagnostics, Settings. Attached by each destination activity. */
object BridgeNav {
    /** Extra bottom space list screens should reserve so the last row is not hidden behind the bar. */
    const val BAR_HEIGHT_DP = 72

    fun attach(activity: Activity, selected: Int) {
        val root = activity.findViewById<ViewGroup>(R.id.root) ?: return
        val nav = BottomNavigationView(activity).apply {
            inflateMenu(R.menu.bridge_nav)
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            selectedItemId = selected
            layoutParams = CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM }
        }
        ViewCompat.setOnApplyWindowInsetsListener(nav) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selected) return@setOnItemSelectedListener true
            val target: Class<*> = when (item.itemId) {
                R.id.nav_status -> MainActivity::class.java
                R.id.nav_apps -> ApplicationManagementActivity::class.java
                R.id.nav_activity -> ActivityHistoryActivity::class.java
                R.id.nav_diagnostics -> DiagnosticsActivity::class.java
                else -> SettingsActivity::class.java
            }
            val i = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            activity.startActivity(i)
            activity.overridePendingTransition(0, 0)
            if (target != MainActivity::class.java && activity !is MainActivity) activity.finish()
            false
        }
        root.addView(nav)
    }
}
