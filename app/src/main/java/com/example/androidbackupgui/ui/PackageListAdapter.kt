package com.example.androidbackupgui.ui

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.androidbackupgui.R
import com.example.androidbackupgui.backup.AppInfo
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * RecyclerView adapter showing app names (or package names as fallback) with checkboxes.
 * Used by both BackupFragment and RestoreFragment.
 */
class PackageListAdapter(
    private val apps: List<AppInfo>,
    private val selected: Set<String>,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<PackageListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val textView: TextView = view.findViewById(R.id.appName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
            radius = 12f
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, 0)
            )
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
        }
        val cb = CheckBox(ctx).apply { id = R.id.checkbox }
        val tv = TextView(ctx).apply {
            id = R.id.appName
            setPadding(16, 0, 0, 0)
            textSize = 15f
            setTextColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0)
            )
        }
        layout.addView(cb)
        layout.addView(tv)
        card.addView(layout)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        // Prefer app name (label), fall back to package name
        holder.textView.text = app.label.ifEmpty { app.packageName }
        // Avoid re-triggering listener during bind
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = app.packageName in selected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            onToggle(app.packageName, checked)
        }
    }

    override fun getItemCount() = apps.size
}
