package com.example.unusedapps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var scanBtn: Button
    private lateinit var swipeLayout: SwipeRefreshLayout
    private lateinit var rootView: View
    private lateinit var adapter: AppListAdapter
    private lateinit var searchView: SearchView
    private lateinit var infoText: TextView

    private val UNUSED_DAYS = 30L

    // Set to true to include apps with NO timestamp (may produce false positives).
    // Leave false for accurate "only apps with system-provided lastTimeUsed older than 30 days".
    private val AGGRESSIVE_MODE = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootView = findViewById(android.R.id.content)
        recycler = findViewById(R.id.recyclerView)
        scanBtn = findViewById(R.id.btnScan)
        swipeLayout = findViewById(R.id.swipeLayout)
        searchView = findViewById(R.id.searchView)
        infoText = findViewById(R.id.infoText)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(this)
        recycler.adapter = adapter

        scanBtn.setOnClickListener {
            if (!isUsageAccessGrantedViaAppOps()) {
                promptGrantUsageAccess()
            } else {
                swipeLayout.isRefreshing = true
                scanInstalledApps()
                swipeLayout.isRefreshing = false
            }
        }

        swipeLayout.setOnRefreshListener {
            if (!isUsageAccessGrantedViaAppOps()) {
                swipeLayout.isRefreshing = false
                promptGrantUsageAccess()
            } else {
                scanInstalledApps()
                swipeLayout.isRefreshing = false
            }
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                adapter.filterList(query ?: "")
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filterList(newText ?: "")
                return true
            }
        })

        if (isUsageAccessGrantedViaAppOps()) {
            scanInstalledApps()
        } else {
            infoText.visibility = View.VISIBLE
            infoText.text = "Tap SCAN and grant Usage Access when prompted."
        }
    }

    private fun promptGrantUsageAccess() {
        val sb = Snackbar.make(rootView, "Usage Access is required for accurate results.", Snackbar.LENGTH_INDEFINITE)
        sb.setAction("Open Settings") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        sb.show()
    }

    /**
     * Conservative scan:
     * - gather MOVE_TO_FOREGROUND events and aggregated stats in a long window (1 year),
     * - choose the latest timestamp for each package,
     * - include package only when chosen > 0 and chosen < cutoff (older than UNUSED_DAYS).
     * If AGGRESSIVE_MODE==true we also list packages with chosen==0 as "Never used".
     */
    private fun scanInstalledApps() {
        infoText.visibility = View.GONE

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val cutoff = now - TimeUnit.DAYS.toMillis(UNUSED_DAYS)

        // Gather events over 1 year to maximize chance of seeing last use
        val eventsWindowStart = now - TimeUnit.DAYS.toMillis(365)
        val lastUsedByEvent = mutableMapOf<String, Long>()
        try {
            val events = usm.queryEvents(eventsWindowStart, now)
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val pkg = ev.packageName ?: continue
                    val ts = ev.timeStamp
                    val prev = lastUsedByEvent[pkg] ?: 0L
                    if (ts > prev) lastUsedByEvent[pkg] = ts
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback aggregated stats
        val aggregated: Map<String, UsageStats>? = try {
            usm.queryAndAggregateUsageStats(eventsWindowStart, now)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        // If both sources empty -> likely permission/OEM restriction
        if ((lastUsedByEvent.isEmpty() || lastUsedByEvent.values.all { it == 0L }) &&
            (aggregated == null || aggregated.isEmpty())
        ) {
            if (!AGGRESSIVE_MODE) {
                infoText.visibility = View.VISIBLE
                infoText.text = "No usage data available. Grant Usage Access and allow background activity (some OEMs require reboot)."
                adapter.updateList(emptyList())
                return
            }
            // else: aggressive mode will still list apps (with chosen==0)
        }

        val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val unused = ArrayList<AppInfo>()

        for (app in installed) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (!app.enabled) continue
            if (app.packageName == packageName) continue

            val pkg = app.packageName
            val fromEvent = lastUsedByEvent[pkg] ?: 0L
            val fromAgg = aggregated?.get(pkg)?.lastTimeUsed ?: 0L
            val chosen = maxOf(fromEvent, fromAgg)

            val include = if (AGGRESSIVE_MODE) {
                (chosen > 0L && chosen < cutoff) || (chosen == 0L)
            } else {
                (chosen > 0L && chosen < cutoff)
            }

            if (include) {
                val label = try {
                    packageManager.getApplicationLabel(app).toString()
                } catch (ex: Exception) {
                    pkg
                }
                unused.add(AppInfo(label, pkg, chosen))
            }
        }

        // Sort: oldest used first; treat 0 (unknown) as newest if conservative, or last if aggressive
        unused.sortWith(compareBy<AppInfo> { if (it.lastUsed == 0L) Long.MAX_VALUE else it.lastUsed })

        adapter.updateList(unused)

        if (unused.isEmpty()) {
            infoText.visibility = View.VISIBLE
            infoText.text = "No apps unused for $UNUSED_DAYS days were found."
        }
    }

    private fun isUsageAccessGrantedViaAppOps(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                "android:get_usage_stats",
                Process.myUid(),
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

