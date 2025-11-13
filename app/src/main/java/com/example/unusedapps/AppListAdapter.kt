package com.example.unusedapps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat

class AppListAdapter(
    private val context: Context,
    private var appList: MutableList<AppInfo>
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val pm: PackageManager = context.packageManager
    // debugInfo: packageName -> debug string
    private var debugInfo: Map<String, String> = emptyMap()
    var showDebug: Boolean = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val lastUsed: TextView = view.findViewById(R.id.appLastUsed)
        val debug: TextView = view.findViewById(R.id.appDebug)
        val btnUninstall: ImageButton = view.findViewById(R.id.btnUninstall)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = appList[position]

        try {
            val iconDrawable = pm.getApplicationIcon(app.packageName)
            holder.icon.setImageDrawable(iconDrawable)
        } catch (e: Exception) {
            holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        holder.name.text = app.name
        holder.pkg.text = app.packageName

        holder.lastUsed.text = if (app.lastUsed <= 0L)
            "Never used"
        else {
            DateUtils.getRelativeTimeSpanString(app.lastUsed, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
        }

        // debug
        val dbg = debugInfo[app.packageName]
        if (showDebug && !dbg.isNullOrEmpty()) {
            holder.debug.visibility = View.VISIBLE
            holder.debug.text = dbg
        } else {
            holder.debug.visibility = View.GONE
        }

        holder.btnUninstall.setOnClickListener {
            val intent = Intent(Intent.ACTION_DELETE)
            intent.data = Uri.parse("package:${app.packageName}")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }

        holder.itemView.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(app.name)
                .setMessage("Package: ${app.packageName}\n${holder.lastUsed.text}" + (if (!dbg.isNullOrEmpty()) "\n\nDebug: $dbg" else ""))
                .setPositiveButton("Uninstall") { _, _ ->
                    val intent = Intent(Intent.ACTION_DELETE)
                    intent.data = Uri.parse("package:${app.packageName}")
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun getItemCount(): Int = appList.size

    fun filterList(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            // no-op — filter should be applied by MainActivity via adapter.updateList
            // You can implement incremental filtering here if desired.
            return
        }
        // simple in-place filter (not preserving full list here; MainActivity will call updateList)
        appList = appList.filter { it.name.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }.toMutableList()
        notifyDataSetChanged()
    }

    /** update list and optional debug info map */
    fun updateList(newList: List<AppInfo>, debugMap: Map<String, String> = emptyMap()) {
        appList = newList.toMutableList()
        debugInfo = debugMap
        notifyDataSetChanged()
    }
}
