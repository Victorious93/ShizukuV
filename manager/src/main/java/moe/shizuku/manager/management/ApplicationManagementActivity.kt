package moe.shizuku.manager.management

import android.os.Bundle
import android.util.TypedValue
import android.view.MenuItem
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AppsActivityBinding
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import java.util.*

class ApplicationManagementActivity : AppBarActivity() {

    private val viewModel by appsViewModel()
    private val adapter = AppsAdapter()
    private var all: List<android.content.pm.PackageInfo> = emptyList()
    private var query: String = ""

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val pm = packageManager
        adapter.updateData(if (q.isEmpty()) all else all.filter {
            val ai = it.applicationInfo
            it.packageName.lowercase().contains(q) || ai?.loadLabel(pm)?.toString()?.lowercase()?.contains(q) == true
        })
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.apps_menu, menu)
        val sv = menu.findItem(R.id.action_search).actionView as androidx.appcompat.widget.SearchView
        sv.queryHint = getString(R.string.apps_search_hint)
        sv.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean { query = q.orEmpty(); applyFilter(); return true }
        })
        return true
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder()) {
            finish()
            return
        }

        val binding = AppsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel.packages.observe(this) {
            when (it.status) {
                Status.SUCCESS -> {
                    all = it.data ?: emptyList()
                    applyFilter()
                }
                Status.ERROR -> {
                    finish()
                    val tr = it.error
                    Toast.makeText(this, Objects.toString(tr, "unknown"), Toast.LENGTH_SHORT).show()
                    tr.printStackTrace()
                }
                Status.LOADING -> {

                }
            }
        }
        if (viewModel.packages.value == null) {
            viewModel.load()
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                viewModel.load(true)
            }
        })

        Shizuku.addBinderDeadListener(binderDeadListener)
        moe.shizuku.manager.bridge.BridgeNav.attach(this, R.id.nav_apps)
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }
}
