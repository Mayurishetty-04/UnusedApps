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

class AppListAdapter(
    private val context: Context
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val pm: PackageManager = context.packageManager
    private var fullList: MutableList<AppInfo> = mutableListOf()
    private var visibleList: MutableList<AppInfo> = mutableListOf()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val lastUsed: TextView = view.findViewById(R.id.appLastUsed)
        val btnUninstall: ImageButton = view.findViewById(R.id.btnUninstall)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = visibleList[position]

        try {
            holder.icon.setImageDrawable(pm.getApplicationIcon(app.packageName))
        } catch (e: Exception) {
            holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        holder.name.text = app.name
        holder.pkg.text = app.packageName

        holder.lastUsed.text = when {
            app.lastUsed <= 0L -> "Never used"
            else -> DateUtils.getRelativeTimeSpanString(app.lastUsed, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
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
                .setMessage("Package: ${app.packageName}\nLast used: ${holder.lastUsed.text}")
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

    override fun getItemCount(): Int = visibleList.size

    fun updateList(newList: List<AppInfo>) {
        fullList = newList.toMutableList()
        visibleList = newList.toMutableList()
        notifyDataSetChanged()
    }

    fun filterList(query: String) {
        val q = query.trim()
        visibleList = if (q.isEmpty()) {
            fullList.toMutableList()
        } else {
            fullList.filter {
                it.name.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
            }.toMutableList()
        }
        notifyDataSetChanged()
    }
}
