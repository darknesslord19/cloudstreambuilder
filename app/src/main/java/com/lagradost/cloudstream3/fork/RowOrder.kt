package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import org.json.JSONArray

/**
 * The order of the rows on the home page, for every source on its own. Rows that are ticked
 * come first, in the order they were ticked (1, 2, 3 …); the others follow as the source gives them.
 */
object RowOrder {
    private const val PREFS = "fork_row_order"

    /** The rows of the source shown last, as the source gives them. */
    @Volatile
    var lastApi: String? = null

    @Volatile
    var lastRows: List<String> = emptyList()

    /** Told when the order changed, the home page draws its rows again. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun order(context: Context, api: String?): List<String> {
        api ?: return emptyList()
        return try {
            val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(api, "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun save(context: Context, api: String, names: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(api, JSONArray(names).toString()).apply()
    }

    /** [items] in the chosen order, [nameOf] gives the name of a row. */
    fun <T> apply(context: Context, api: String?, items: List<T>, nameOf: (T) -> String): List<T> {
        val wanted = order(context, api)
        if (wanted.isEmpty()) return items
        val first = wanted.mapNotNull { name -> items.firstOrNull { nameOf(it) == name } }
        return first + items.filter { it !in first }
    }

    fun show(activity: Activity) {
        val api = lastApi ?: return
        val rows = lastRows.distinct()
        if (rows.isEmpty()) return
        val chosen = order(activity, api).filter { it in rows }.toMutableList()
        val dialog = BottomSheetDialog(activity)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        val text = activity.colorFromAttribute(R.attr.textColor)
        val gray = activity.colorFromAttribute(R.attr.grayTextColor)
        val primary = activity.colorFromAttribute(R.attr.colorPrimary)
        val box = activity.colorFromAttribute(R.attr.boxItemBackground)

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.toPx, 10.toPx, 14.toPx, 24.toPx)
        }
        list.addView(TextView(activity).apply {
            setText(R.string.row_order_title)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(text)
        })
        list.addView(TextView(activity).apply {
            text = activity.getString(R.string.row_order_des, api)
            textSize = 12.5f
            setTextColor(gray)
            setPadding(0, 2.toPx, 0, 10.toPx)
        })
        val rowsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        list.addView(rowsBox)

        fun draw() {
            rowsBox.removeAllViews()
            // ticked ones first, in their order, then the rest
            val shown = chosen + rows.filter { it !in chosen }
            shown.forEach { name ->
                val number = chosen.indexOf(name) + 1
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(12.toPx, 10.toPx, 12.toPx, 10.toPx)
                    isFocusable = true
                    isClickable = true
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                            cornerRadius = 14.toPx.toFloat(); setColor(box); setStroke(2.toPx, text)
                        })
                        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = 14.toPx.toFloat(); setColor(box) })
                    }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 3.toPx, 0, 3.toPx)
                    }
                    setOnClickListener {
                        if (number > 0) chosen.remove(name) else chosen.add(name)
                        save(activity, api, chosen)
                        draw()
                        onChanged?.invoke()
                    }
                }
                row.addView(TextView(activity).apply {
                    this.text = if (number > 0) number.toString() else ""
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (number > 0) activity.colorFromAttribute(R.attr.primaryBlackBackground) else gray)
                    background = GradientDrawable().apply {
                        cornerRadius = 8.toPx.toFloat()
                        if (number > 0) setColor(primary) else setStroke(2.toPx, gray)
                    }
                }, LinearLayout.LayoutParams(28.toPx, 28.toPx).apply { marginEnd = 14.toPx })
                row.addView(TextView(activity).apply {
                    this.text = name
                    textSize = 15f
                    setTextColor(text)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                rowsBox.addView(row)
            }
        }
        draw()

        list.addView(TextView(activity).apply {
            setText(R.string.row_order_reset)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(text)
            setPadding(0, 14.toPx, 0, 14.toPx)
            isFocusable = true
            background = GradientDrawable().apply { cornerRadius = 14.toPx.toFloat(); setStroke(1.toPx, gray) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 14.toPx, 0, 0)
            }
            setOnClickListener {
                chosen.clear()
                save(activity, api, chosen)
                draw()
                onChanged?.invoke()
            }
        })
        dialog.setContentView(ScrollView(activity).apply {
            addView(list)
            setBackgroundColor(activity.colorFromAttribute(R.attr.primaryGrayBackground))
        })
        dialog.show()
        rowsBox.getChildAt(0)?.requestFocus()
    }
}
