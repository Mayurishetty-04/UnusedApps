package com.example.unusedapps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppListAdapter(
    private val context: Context,
    private var appList: MutableList<AppInfo>
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    private var fullList: List<AppInfo> = appList.toList()

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appName: TextView = itemView.findViewById(R.id.appName)
        val appLastUsed: TextView = itemView.findViewById(R.id.appLastUsed)
        val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        val btnUninstall: ImageButton = itemView.findViewById(R.id.btnUninstall)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun getItemCount(): Int = appList.size

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = appList[position]

        // Display app name and last-used
        holder.appName.text = app.appName
        holder.appLastUsed.text = formatLastUsed(app.lastUsed)

        // Load icon safely
        try {
            val icon = context.packageManager.getApplicationIcon(app.packageName)
            holder.appIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        // Icon tap -> pop animation then show uninstall dialog
        holder.appIcon.setOnClickListener {
            // pass the drawable to animation function
            val drawable = holder.appIcon.drawable
            animateIconThenOpenDialog(drawable, app)
        }

        // Trash icon or whole row -> open dialog immediately
        holder.btnUninstall.setOnClickListener { showUninstallDialog(app) }
        holder.itemView.setOnClickListener { showUninstallDialog(app) }
    }

    // Simple formatter
    private fun formatLastUsed(ts: Long): String {
        return if (ts <= 0L) {
            "Never used"
        } else {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            sdf.format(Date(ts))
        }
    }

    /**
     * Create a temporary ImageView overlay with the icon drawable, animate it (scale+fade),
     * then remove the overlay and open the uninstall dialog.
     *
     * This keeps animation simple and robust across devices (no precise translation).
     */
    private fun animateIconThenOpenDialog(drawable: Drawable?, app: AppInfo) {
        if (drawable == null) {
            // fallback: directly open dialog
            showUninstallDialog(app)
            return
        }

        // Root view to attach overlay
        val activity = context as? Activity
        if (activity == null) {
            showUninstallDialog(app)
            return
        }

        val root = activity.findViewById<ViewGroup>(android.R.id.content)

        // Create overlay ImageView
        val overlay = ImageView(context).apply {
            setImageDrawable(drawable)
            // start small and fully visible
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            // set a nice size in dp -> px
            val sizeDp = 96
            val density = context.resources.displayMetrics.density
            val px = (sizeDp * density).toInt()
            layoutParams = FrameLayout.LayoutParams(px, px, Gravity.CENTER)
            elevation = 50f
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }

        // Add overlay to root
        root.addView(overlay)

        // Animate: scale up then fade out
        overlay.animate()
            .scaleX(2.2f)
            .scaleY(2.2f)
            .alpha(0f)
            .setDuration(430L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                // remove overlay and open dialog
                root.removeView(overlay)
                showUninstallDialog(app)
            }
            .start()
    }

    private fun showUninstallDialog(app: AppInfo) {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_uninstall, null, false)

        val ivIcon = view.findViewById<ImageView>(R.id.dialogIcon)
        val tvTitle = view.findViewById<TextView>(R.id.dialogTitle)
        val tvPackage = view.findViewById<TextView>(R.id.dialogPackage)
        val tvLastUsed = view.findViewById<TextView>(R.id.dialogLastUsed)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelDialog)
        val btnUninstall = view.findViewById<Button>(R.id.btnUninstallDialog)

        tvTitle.text = app.appName
        tvPackage.text = app.packageName
        tvLastUsed.text = if (app.lastUsed <= 0L) "Last used: Never used"
        else "Last used: ${formatLastUsed(app.lastUsed)}"

        try {
            val icon = context.packageManager.getApplicationIcon(app.packageName)
            ivIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnUninstall.setOnClickListener {
            dialog.dismiss()
            try {
                val uninstall = Intent(Intent.ACTION_DELETE)
                uninstall.data = Uri.parse("package:${app.packageName}")
                uninstall.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(uninstall)
            } catch (ex: Exception) {
                try {
                    val uninstall = Intent(Intent.ACTION_DELETE)
                    uninstall.data = Uri.parse("package:${app.packageName}")
                    context.startActivity(uninstall)
                } catch (_: Exception) { /* ignore */ }
            }
        }

        dialog.show()
    }

    // Replace adapter list and refresh
    fun updateList(newList: List<AppInfo>) {
        appList = newList.toMutableList()
        fullList = appList.toList()
        notifyDataSetChanged()
    }

    // Filter for search
    fun filterList(query: String) {
        appList = if (query.isBlank()) {
            fullList.toMutableList()
        } else {
            fullList.filter {
                it.appName.contains(query, true) ||
                        it.packageName.contains(query, true)
            }.toMutableList()
        }
        notifyDataSetChanged()
    }
}
