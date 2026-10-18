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

    /** The cards of the source shown last (not the +18 ones), for the count of a written category. */
    @Volatile
    var lastItems: List<com.lagradost.cloudstream3.SearchResponse> = emptyList()

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

    fun hidden(context: Context, api: String?): Set<String> {
        api ?: return emptySet()
        return try {
            val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("hidden_$api", "[]"))
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    private fun saveHidden(context: Context, api: String, names: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("hidden_$api", JSONArray(names.toList()).toString()).apply()
    }

    /** [items] in the chosen order without the hidden ones, [nameOf] gives the name of a row. */
    fun <T> apply(context: Context, api: String?, items: List<T>, nameOf: (T) -> String): List<T> {
        val hide = hidden(context, api)
        val wanted = order(context, api)
        val all = if (wanted.isEmpty()) items else {
            val first = wanted.mapNotNull { name -> items.firstOrNull { nameOf(it) == name } }
            first + items.filter { it !in first }
        }
        return if (hide.isEmpty()) all else all.filter { nameOf(it) !in hide }
    }

    fun show(activity: Activity) {
        val api = lastApi ?: return
        val rows = lastRows.distinct()
        if (rows.isEmpty()) return
        val chosen = order(activity, api).filter { it in rows }.toMutableList()
        val hide = hidden(activity, api).toMutableSet()
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
            this.text = activity.getString(R.string.row_order_des, api)
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
                val off = name in hide
                (row.getChildAt(1) as? TextView)?.alpha = if (off) 0.45f else 1f
                row.addView(android.widget.ImageView(activity).apply {
                    setImageResource(if (off) R.drawable.ic_baseline_visibility_off_24 else R.drawable.ic_baseline_remove_red_eye_24)
                    setColorFilter(if (off) gray else primary)
                    setPadding(8.toPx, 8.toPx, 8.toPx, 8.toPx)
                    isFocusable = true
                    isClickable = true
                    contentDescription = activity.getString(R.string.row_order_eye)
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                            cornerRadius = 10.toPx.toFloat(); setStroke(2.toPx, text)
                        })
                        addState(intArrayOf(), GradientDrawable().apply {
                            cornerRadius = 10.toPx.toFloat(); setStroke(1.toPx, if (off) gray else primary)
                        })
                    }
                    setOnClickListener {
                        if (off) hide.remove(name) else hide.add(name)
                        saveHidden(activity, api, hide)
                        draw()
                        onChanged?.invoke()
                    }
                }, LinearLayout.LayoutParams(40.toPx, 40.toPx).apply { marginStart = 10.toPx })
                rowsBox.addView(row)
            }
        }
        draw()

        // ---- extra categories, made from what the source shows
        list.addView(TextView(activity).apply {
            setText(R.string.row_order_add)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(text)
            setPadding(0, 20.toPx, 0, 2.toPx)
        })
        list.addView(TextView(activity).apply {
            this.text = activity.getString(
                if (GenreRows.canWork(activity)) R.string.row_order_add_des else R.string.row_order_add_nokey
            )
            textSize = 12.5f
            setTextColor(gray)
            setPadding(0, 0, 0, 8.toPx)
        })
        val picked = GenreRows.chosen(activity, api).toMutableList()
        val chips = com.google.android.material.chip.ChipGroup(activity).apply { chipSpacingVertical = 2.toPx }
        val adultColor = 0xFFFF6B8A.toInt()

        fun chip(label: String, id: String, accent: Int, onLong: (() -> Unit)? = null) =
            com.google.android.material.chip.Chip(activity).apply {
                this.text = label
                isCheckable = true
                isChecked = id in picked
                isFocusable = true
                setTextColor(if (accent == primary) text else accent)
                chipBackgroundColor = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent and 0x00FFFFFF or 0x55000000, box)
                )
                chipStrokeWidth = 1.toPx.toFloat()
                chipStrokeColor = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, if (accent == primary) gray else accent)
                )
                setOnCheckedChangeListener { _, on ->
                    if (on) { if (id !in picked) picked.add(id) } else picked.remove(id)
                    GenreRows.setChosen(activity, api, picked)
                    onChanged?.invoke()
                }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }

        fun drawChips() {
            chips.removeAllViews()
            if (GenreRows.canWork(activity)) GenreRows.genres.forEach { g -> chips.addView(chip(g.name, g.name, primary)) }
            val showAdult = Adult.visible(activity)
            GenreRows.customs(activity).filter { !it.adult || showAdult }.forEach { c ->
                chips.addView(chip("✎ " + c.name, c.name, primary) {
                    CategoryForm.show(activity, api, c) { removedOrRenamed ->
                        removedOrRenamed?.let { (old, now) ->
                            val at = picked.indexOf(old)
                            if (at >= 0) { if (now == null) picked.removeAt(at) else picked[at] = now }
                            GenreRows.setChosen(activity, api, picked)
                        }
                        drawChips(); onChanged?.invoke()
                    }
                })
            }
            if (showAdult) Adult.cats.filter { it.name in Adult.chosenCats(activity) }.forEach { cat ->
                chips.addView(chip("18 · " + cat.name, GenreRows.ADULT_PREFIX + cat.name, adultColor))
            }
            chips.addView(com.google.android.material.chip.Chip(activity).apply {
                setText(R.string.cat_write)
                isFocusable = true
                setTextColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(primary)
                setOnClickListener {
                    CategoryForm.show(activity, api, null) { made ->
                        made?.second?.let { name -> if (name !in picked) { picked.add(name); GenreRows.setChosen(activity, api, picked) } }
                        drawChips(); onChanged?.invoke()
                    }
                }
            })
        }
        drawChips()
        list.addView(chips)
        list.addView(TextView(activity).apply {
            setText(R.string.cat_long_press)
            textSize = 11.5f
            setTextColor(gray)
            setPadding(0, 4.toPx, 0, 0)
        })

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
                hide.clear()
                saveHidden(activity, api, hide)
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
