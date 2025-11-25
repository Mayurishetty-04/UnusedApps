package com.example.unusedapps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var scanBtn: Button
    private lateinit var swipeLayout: SwipeRefreshLayout
    private lateinit var adapter: AppListAdapter
    private lateinit var rootView: View
    private lateinit var searchView: SearchView

    private val UNUSED_DAYS = 30L

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
        recycler.adapter = adapter

        scanBtn.setOnClickListener {
            if (!hasUsagePermission()) {
                openUsageSettings()
            } else {
                swipeLayout.isRefreshing = true
                scanApps()
                swipeLayout.isRefreshing = false
            }
        }

        swipeLayout.setOnRefreshListener {
            if (!hasUsagePermission()) {
                showSnackbar("Please enable Usage Access.")
                swipeLayout.isRefreshing = false
            } else {
                scanApps()
                swipeLayout.isRefreshing = false
            }
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filterList(newText ?: "")
                return true
            }
        })

        if (hasUsagePermission()) {
            scanApps()
        } else {
            showSnackbar("Tap SCAN and grant Usage Access.")
        }
    }

    private fun scanApps() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val cutoff = now - TimeUnit.DAYS.toMillis(UNUSED_DAYS)

        // 1) Foreground events reading
        val eventsStart = now - TimeUnit.DAYS.toMillis(365)
        val events = usm.queryEvents(eventsStart, now)
        val lastUsedEvent = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastUsedEvent[event.packageName] = event.timeStamp
            }
        }

        // 2) Aggregated stats fallback
        val agg = try {
            usm.queryAndAggregateUsageStats(eventsStart, now)
        } catch (e: Exception) {
            emptyMap<String, android.app.usage.UsageStats>()
        }

        // 3) Installed apps
        val installed = packageManager.getInstalledApplications(0)
        val unused = ArrayList<AppInfo>()

        for (app in installed) {
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

            val pkg = app.packageName
            val eventTime = lastUsedEvent[pkg] ?: 0L
            val aggTime = agg[pkg]?.lastTimeUsed ?: 0L
            val lastUsed = maxOf(eventTime, aggTime)

            if (lastUsed < cutoff) {
                val label = try {
                    packageManager.getApplicationLabel(app).toString()
                } catch (ex: Exception) {
                    pkg
                }

                unused.add(
                    AppInfo(
                        packageName = pkg,
                        appName = label,
                        lastUsed = lastUsed
                    )
                )
            }
        }

        unused.sortBy { it.lastUsed }
        adapter.updateList(unused)

        if (unused.isEmpty()) {
            showSnackbar("No unused apps found in last $UNUSED_DAYS days.")
        }
    }

    // --------------------------------------------
    //   PERMISSION FIX (THE IMPORTANT PART)
    // --------------------------------------------

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            "android:get_usage_stats",
            Process.myUid(),
            packageName
        )
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                checkUsageStatsFallback()
            else -> false
        }
    }

    private fun checkUsageStatsFallback(): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            System.currentTimeMillis() - 1000 * 1000,
            System.currentTimeMillis()
        )
        return stats != null && stats.isNotEmpty()
    }

    private fun openUsageSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        showSnackbar("Please grant Usage Access to continue.")
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(rootView, msg, Snackbar.LENGTH_LONG).show()
    }
}
