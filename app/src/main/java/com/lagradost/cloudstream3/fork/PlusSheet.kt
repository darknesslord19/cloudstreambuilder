package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * The settings of one part of Plus, in a menu that comes up from the bottom, so the Plus page
 * stays short. Switches and choices are kept in the normal settings of the app.
 */
object PlusSheet {
    sealed class Item {
        /** A switch, [dependsOn] greys it out while that switch is off */
        data class Switch(
            val key: String,
            val title: String,
            val summary: String? = null,
            val default: Boolean = true,
            val dependsOn: String? = null,
            val icon: Int? = null,
        ) : Item()

        /** One of a few values */
        data class Choice(
            val key: String,
            val title: String,
            val names: List<String>,
            val values: List<String>,
            val default: String,
            val dependsOn: String? = null,
            val icon: Int? = null,
        ) : Item()

        /** Something to open */
        data class Action(
            val title: String,
            val summary: String? = null,
            val icon: Int? = null,
            val dependsOn: String? = null,
            val onClick: () -> Unit,
        ) : Item()

        /** Only to read */
        data class Info(val title: String, val summary: String?, val icon: Int? = null) : Item()

        /** A small title between the rows */
        data class Header(val title: String) : Item()
    }

    fun show(activity: Activity, title: String, subtitle: String?, items: List<Item>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val dialog = BottomSheetDialog(activity)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.toPx, 6.toPx, 12.toPx, 20.toPx)
        }
        list.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(36.toPx, 4.toPx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 4.toPx, 0, 12.toPx)
            }
            background = ContextCompat.getDrawable(activity, R.drawable.provider_letters_bg)
            alpha = 0.6f
        })
        list.addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(activity.colorFromAttribute(R.attr.textColor))
            setPadding(6.toPx, 0, 6.toPx, 2.toPx)
        })
        if (!subtitle.isNullOrBlank()) {
            list.addView(TextView(activity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(activity.colorFromAttribute(R.attr.grayTextColor))
                setPadding(6.toPx, 0, 6.toPx, 10.toPx)
            })
        }

        // Rows that follow a switch
        val followers = ArrayList<Pair<String, View>>()
        fun refresh() {
            followers.forEach { (key, row) ->
                val on = prefs.getBoolean(key, true)
                row.alpha = if (on) 1f else 0.4f
                row.isEnabled = on
                row.isFocusable = on
            }
        }

        for (item in items) {
            val row: View = when (item) {
                is Item.Header -> TextView(activity).apply {
                    text = item.title
                    textSize = 12.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(activity.colorFromAttribute(R.attr.colorPrimary))
                    setPadding(8.toPx, 14.toPx, 8.toPx, 4.toPx)
                }

                is Item.Switch -> {
                    val toggle = SwitchMaterial(activity).apply {
                        isChecked = prefs.getBoolean(item.key, item.default)
                        isClickable = false
                        isFocusable = false
                    }
                    row(activity, item.title, item.summary, item.icon, toggle).apply {
                        setOnClickListener {
                            if (!isEnabled) return@setOnClickListener
                            toggle.isChecked = !toggle.isChecked
                            prefs.edit { putBoolean(item.key, toggle.isChecked) }
                            refresh()
                        }
                    }
                }

                is Item.Choice -> {
                    fun current(): String {
                        val value = prefs.getString(item.key, item.default) ?: item.default
                        return item.names.getOrNull(item.values.indexOf(value)) ?: item.names.first()
                    }
                    val row = row(activity, item.title, current(), item.icon, null)
                    row.setOnClickListener {
                        if (!row.isEnabled) return@setOnClickListener
                        val value = prefs.getString(item.key, item.default) ?: item.default
                        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
                            .setTitle(item.title)
                            .setSingleChoiceItems(item.names.toTypedArray(), item.values.indexOf(value).coerceAtLeast(0)) { d, which ->
                                prefs.edit { putString(item.key, item.values[which]) }
                                row.findViewById<TextView>(android.R.id.text2)?.text = current()
                                d.dismiss()
                            }
                            .show()
                    }
                    row
                }

                is Item.Action -> row(activity, item.title, item.summary, item.icon, ImageView(activity).apply {
                    setImageResource(R.drawable.ic_baseline_arrow_forward_24)
                    setColorFilter(activity.colorFromAttribute(R.attr.grayTextColor))
                    layoutParams = LinearLayout.LayoutParams(20.toPx, 20.toPx)
                }).apply {
                    setOnClickListener {
                        if (!isEnabled) return@setOnClickListener
                        dialog.dismiss()
                        item.onClick()
                    }
                }

                is Item.Info -> row(activity, item.title, item.summary, item.icon, null).apply {
                    isFocusable = false
                    isClickable = false
                    background = null
                }
            }
            list.addView(row)
            val dep = when (item) {
                is Item.Switch -> item.dependsOn
                is Item.Choice -> item.dependsOn
                is Item.Action -> item.dependsOn
                else -> null
            }
            if (dep != null) followers.add(dep to row)
        }
        refresh()

        val scroll = ScrollView(activity).apply {
            addView(list)
            setBackgroundColor(activity.colorFromAttribute(R.attr.primaryGrayBackground))
        }
        dialog.setContentView(scroll)
        dialog.show()
        list.getChildAt(if (subtitle.isNullOrBlank()) 2 else 3)?.requestFocus()
    }

    private fun row(context: Context, title: String, summary: String?, icon: Int?, end: View?): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 56.toPx
            setPadding(12.toPx, 8.toPx, 12.toPx, 8.toPx)
            background = ContextCompat.getDrawable(context, R.drawable.fork_quick_button)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 3.toPx, 0, 3.toPx)
            }
            if (icon != null) {
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(context.colorFromAttribute(R.attr.textColor))
                    layoutParams = LinearLayout.LayoutParams(22.toPx, 22.toPx).apply { marginEnd = 14.toPx }
                })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    id = android.R.id.text1
                    text = title
                    textSize = 15f
                    setTextColor(context.colorFromAttribute(R.attr.textColor))
                })
                if (!summary.isNullOrBlank()) {
                    addView(TextView(context).apply {
                        id = android.R.id.text2
                        text = summary
                        textSize = 12.5f
                        setTextColor(context.colorFromAttribute(R.attr.grayTextColor))
                        setPadding(0, 2.toPx, 0, 0)
                    })
                }
            })
            if (end != null) {
                (end.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 10.toPx
                addView(end)
            }
        }
    }
}
