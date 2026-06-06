package com.example.androidbackupgui.ui

import android.view.View
import android.util.TypedValue
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
    private val onToggle: (String, Boolean) -> Unit,
    private val excludeDataFrom: Set<String> = emptySet(),
    private val onExcludeDataToggle: ((String, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<PackageListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val textView: TextView = view.findViewById(R.id.appName)
        val excludeToggle: TextView = view.findViewById(R.id.excludeToggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val res = ctx.resources
        val card = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, res.getDimensionPixelSize(R.dimen.card_margin_bottom)) }
            radius = res.getDimension(R.dimen.card_radius)
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, 0)
            )
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(res.getDimensionPixelSize(R.dimen.card_padding_horizontal), res.getDimensionPixelSize(R.dimen.card_padding_vertical), res.getDimensionPixelSize(R.dimen.card_padding_horizontal), res.getDimensionPixelSize(R.dimen.card_padding_vertical))
        }
        val cb = CheckBox(ctx).apply { id = R.id.checkbox }
        val tv = TextView(ctx).apply {
            id = R.id.appName
            setPadding(res.getDimensionPixelSize(R.dimen.card_padding_horizontal), 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.list_item_text_size))
            setTextColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0)
            )
        }
        val et = TextView(ctx).apply {
            id = R.id.excludeToggle
            visibility = if (onExcludeDataToggle != null) View.VISIBLE else View.GONE
            setPadding(res.getDimensionPixelSize(R.dimen.card_padding_horizontal), 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.list_item_text_size) * 0.75f)
            setTextColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
            )
            contentDescription = res.getString(R.string.exclude_data_toggle)
            isFocusable = true
            isClickable = true
        }
        layout.addView(cb)
        layout.addView(tv)
        layout.addView(et)
        card.addView(layout)

        val holder = ViewHolder(card)
        card.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val app = apps[pos]
            val newChecked = !holder.checkbox.isChecked
            // Temporarily suppress checkbox listener to avoid double-fire
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = newChecked
            holder.checkbox.setOnCheckedChangeListener { _, checked ->
                onToggle(app.packageName.value, checked)
            }
            onToggle(app.packageName.value, newChecked)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        val pkg = app.packageName.value
        // Prefer app name (label), fall back to package name
        holder.textView.text = app.label.ifEmpty { pkg }
        // Avoid re-triggering listener during bind
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = pkg in selected
        holder.checkbox.setOnCheckedChangeListener { _, checked ->
            onToggle(pkg, checked)
        }
        // Configure per-app data exclusion toggle
        val toggle = holder.excludeToggle
        val dataToggleCb = onExcludeDataToggle
        if (dataToggleCb != null) {
            toggle.visibility = View.VISIBLE
            val excluded = pkg in excludeDataFrom
            toggle.text = "数据"
            toggle.paintFlags = if (excluded) {
                toggle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                toggle.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            toggle.isSelected = excluded
            toggle.setOnClickListener {
                dataToggleCb(pkg, !excluded)
            }
        } else {
            toggle.visibility = View.GONE
            toggle.setOnClickListener(null)
        }
    }

    override fun getItemCount() = apps.size
}
