package com.example.unusedapps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.DateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var scanBtn: Button
    private lateinit var swipeLayout: SwipeRefreshLayout
    private lateinit var rootView: View
    private lateinit var adapter: AppListAdapter
    private lateinit var searchView: SearchView

    private val UNUSED_DAYS = 30L
    // Turn on to show per-app event/agg raw values on screen
    private val DEBUG_DIAG = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootView = findViewById(android.R.id.content)
        recycler = findViewById(R.id.recyclerView)
        scanBtn = findViewById(R.id.btnScan)
        swipeLayout = findViewById(R.id.swipeLayout)
        searchView = findViewById(R.id.searchView)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(this, mutableListOf())
        adapter.showDebug = DEBUG_DIAG
        recycler.adapter = adapter

        scanBtn.setOnClickListener {
            if (!isUsageAccessGranted()) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                showSnackbar("Please grant Usage Access to this app in Settings, then press SCAN.")
            } else {
                swipeLayout.isRefreshing = true
                scanInstalledApps()
                swipeLayout.isRefreshing = false
            }
        }

        swipeLayout.setOnRefreshListener {
            if (!isUsageAccessGranted()) {
                swipeLayout.isRefreshing = false
                showSnackbar("Usage Access missing. Tap SCAN to enable it.")
            } else {
                scanInstalledApps()
                swipeLayout.isRefreshing = false
            }
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                // quick local filter (for small lists)
                if (!query.isNullOrBlank()) {
                    val filtered = adapter.run {
                        // ask adapter to show only those matching — simplest approach:
                        // we rebuild the list from adapter's current items (adapter doesn't expose full)
                        // Better: re-run scan and filter the result list — but for now call filterList
                        filterList(query)
                    }
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filterList(newText ?: "")
                return true
            }
        })

        // initial scan if permitted
        if (isUsageAccessGranted()) {
            scanInstalledApps()
        } else {
            showSnackbar("Tap 'SCAN' and grant Usage Access when prompted.")
        }
    }

    private fun scanInstalledApps() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val thirtyMillis = TimeUnit.DAYS.toMillis(UNUSED_DAYS)
        val cutoff = now - thirtyMillis

        // --- 1) Build event-based lastUsed (MOVE_TO_FOREGROUND) over a long window
        val eventsWindowStart = now - TimeUnit.DAYS.toMillis(365) // 1 year
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

        // --- 2) Get aggregated usage stats (fallback)
        val aggregated: Map<String, UsageStats>? = try {
            usm.queryAndAggregateUsageStats(eventsWindowStart, now)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        // If both empty — prompt user
        if ((lastUsedByEvent.isEmpty() || lastUsedByEvent.values.all { it == 0L }) &&
            (aggregated == null || aggregated.isEmpty())
        ) {
            // Also detect if AppOps denies us
            if (!isUsageAccessGrantedViaAppOps()) {
                showSnackbar("Usage access is not granted to this app. Please enable it in Settings → Usage access.")
            } else {
                showSnackbar("Usage data unavailable. Some OEMs restrict usage access; check device settings.")
            }
            // Update list as empty
            adapter.updateList(emptyList(), emptyMap())
            return
        }

        // --- 3) Build unused list and debug map
        val installed = packageManager.getInstalledApplications(0)
        val unused = ArrayList<AppInfo>()
        val debugMap = mutableMapOf<String, String>()

        for (app in installed) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val pkg = app.packageName
            val fromEvent = lastUsedByEvent[pkg] ?: 0L
            val fromAgg = aggregated?.get(pkg)?.lastTimeUsed ?: 0L
            val chosen = maxOf(fromEvent, fromAgg)

            // build debug text
            val eventStr = if (fromEvent <= 0L) "0" else DateFormat.getDateTimeInstance().format(fromEvent)
            val aggStr = if (fromAgg <= 0L) "0" else DateFormat.getDateTimeInstance().format(fromAgg)
            val chosenStr = if (chosen <= 0L) "0" else DateFormat.getDateTimeInstance().format(chosen)
            val dbg = "event: $eventStr | agg: $aggStr | chosen: $chosenStr"
            debugMap[pkg] = dbg

            if (chosen < cutoff) {
                val label = try {
                    packageManager.getApplicationLabel(app).toString()
                } catch (ex: Exception) {
                    pkg
                }
                unused.add(AppInfo(label, pkg, chosen))
            }
        }

        unused.sortBy { it.lastUsed }
        adapter.updateList(unused, debugMap)

        if (unused.isEmpty()) {
            showSnackbar("No apps unused for $UNUSED_DAYS days were found.")
        }
    }

    private fun isUsageAccessGranted(): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val since = System.currentTimeMillis() - 1000 * 1000
        val stats = try {
            usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                since,
                System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
        return stats != null && stats.isNotEmpty()
    }

    // stronger check using AppOps to verify GET_USAGE_STATS permission
    private fun isUsageAccessGrantedViaAppOps(): Boolean {
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                "android:get_usage_stats",
                Process.myUid(),
                packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show()
    }
}
