package moe.shizuku.manager.settings

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.*
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.KEEP_START_ON_BOOT
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.receiver.BootCompleteReceiver
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.widget.borderview.BorderRecyclerView

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var startupPreference: PreferenceCategory

    private val pickProfile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) moe.shizuku.manager.bridge.ProfileFlow.import(requireContext(), uri)
    }
    private val saveSample = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { requireContext().contentResolver.openOutputStream(uri)?.use { it.write(moe.shizuku.manager.bridge.ProfileFlow.SAMPLE.toByteArray()) } }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        startOnBootPreference = findPreference(KEEP_START_ON_BOOT)!!
        startupPreference = findPreference("startup")!!

        val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)

        startOnBootPreference.isChecked = context.packageManager.isComponentEnabled(componentName)
        startOnBootPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (newValue is Boolean) {
                    context.packageManager.setComponentEnabled(componentName, newValue)
                    context.packageManager.isComponentEnabled(componentName) == newValue
                } else false
            }
        findPreference<ListPreference>("backend_policy")!!.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
            val p = ai.victorious.bridge.core.BackendRequirement.fromId(v as? String)
            Thread { runCatching { moe.shizuku.manager.bridge.BridgeAdmin.setBackendPolicy(p) } }.start()
            true
        }
        findPreference<Preference>("profile_import")!!.setOnPreferenceClickListener { pickProfile.launch(arrayOf("application/json", "text/plain", "*/*")); true }
        findPreference<Preference>("profile_undo")!!.setOnPreferenceClickListener { moe.shizuku.manager.bridge.ProfileFlow.undoLast(context); true }
        findPreference<Preference>("profile_export_sample")!!.setOnPreferenceClickListener { saveSample.launch("bridge-profile.json"); true }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState) as BorderRecyclerView
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(bottom = 88f, unit = TypedValue.COMPLEX_UNIT_DIP)

        val lp = recyclerView.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.rightMargin = recyclerView.context.resources.getDimension(R.dimen.rd_activity_horizontal_margin).toInt()
            lp.leftMargin = lp.rightMargin
        }

        return recyclerView
    }
}
