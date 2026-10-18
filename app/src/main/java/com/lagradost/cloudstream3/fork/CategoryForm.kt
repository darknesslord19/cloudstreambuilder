package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * "Kendin yaz": a category written by the user (Ana sayfa > long press on a row title > Kategori ekle).
 * [done] gets (old name, new name): (null, name) for a new one, (name, null) when it was deleted.
 */
object CategoryForm {
    fun show(activity: Activity, api: String, old: GenreRows.Custom?, done: (Pair<String?, String?>?) -> Unit) {
        val dialog = BottomSheetDialog(activity)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        val text = activity.colorFromAttribute(R.attr.textColor)
        val gray = activity.colorFromAttribute(R.attr.grayTextColor)
        val primary = activity.colorFromAttribute(R.attr.colorPrimary)
        val box = activity.colorFromAttribute(R.attr.boxItemBackground)
        val dark = activity.colorFromAttribute(R.attr.primaryBlackBackground)
        val hasKey = GenreRows.canWork(activity)

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.toPx, 12.toPx, 16.toPx, 24.toPx)
        }
        fun title(res: Int, size: Float = 19f) = TextView(activity).apply {
            setText(res); textSize = size; setTypeface(typeface, Typeface.BOLD); setTextColor(text)
        }
        fun label(s: String) = TextView(activity).apply {
            this.text = s; textSize = 12.5f; setTextColor(gray); setPadding(2.toPx, 14.toPx, 0, 6.toPx)
        }
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(activity).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setSingleLine()
            textSize = 15f
            setTextColor(text)
            setHintTextColor(gray)
            setPadding(12.toPx, 10.toPx, 12.toPx, 10.toPx)
            background = GradientDrawable().apply { cornerRadius = 12.toPx.toFloat(); setColor(box); setStroke(1.toPx, gray and 0x00FFFFFF or 0x44000000) }
        }
        fun chip(label: String, checked: Boolean) = Chip(activity).apply {
            this.text = label
            isCheckable = true
            isChecked = checked
            isFocusable = true
            setTextColor(text)
            chipBackgroundColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(primary and 0x00FFFFFF or 0x55000000, box))
            chipStrokeWidth = 1.toPx.toFloat()
            chipStrokeColor = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(primary, gray))
        }
        fun button(res: Int, filled: Boolean, onClick: () -> Unit) = TextView(activity).apply {
            setText(res)
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (filled) dark else text)
            setPadding(0, 14.toPx, 0, 14.toPx)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = 14.toPx.toFloat()
                if (filled) setColor(primary) else setStroke(1.toPx, gray)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 12.toPx, 0, 0) }
            setOnClickListener { onClick() }
        }

        list.addView(title(if (old == null) R.string.cat_new else R.string.cat_edit))
        list.addView(TextView(activity).apply {
            setText(if (hasKey) R.string.cat_form_des else R.string.cat_form_nokey)
            textSize = 12.5f; setTextColor(gray); setPadding(0, 4.toPx, 0, 0)
        })

        list.addView(label(activity.getString(R.string.cat_name)))
        val name = field(activity.getString(R.string.cat_name_hint), old?.name ?: "")
        list.addView(name)

        list.addView(label(activity.getString(R.string.cat_words)))
        val words = field(activity.getString(R.string.cat_words_hint), old?.words?.joinToString(", ") ?: "")
        list.addView(words)

        val genreChips = ChipGroup(activity).apply { chipSpacingVertical = 2.toPx }
        val allChip = chip(activity.getString(R.string.cat_all_genres), old?.all == true)
        val from = field(activity.getString(R.string.cat_from), old?.from?.toString() ?: "", InputType.TYPE_CLASS_NUMBER)
        val to = field(activity.getString(R.string.cat_to), old?.to?.toString() ?: "", InputType.TYPE_CLASS_NUMBER)
        val rating = field(activity.getString(R.string.cat_rating), old?.rating?.toString()?.removeSuffix(".0") ?: "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val countryChips = ChipGroup(activity).apply { isSingleSelection = true; isSelectionRequired = true; chipSpacingVertical = 2.toPx }
        if (hasKey) {
            list.addView(label(activity.getString(R.string.cat_genres)))
            GenreRows.genres.forEach { g -> genreChips.addView(chip(g.name, g.name in (old?.genres ?: emptyList())).apply { tag = g.name }) }
            genreChips.addView(allChip)
            list.addView(genreChips)

            list.addView(label(activity.getString(R.string.cat_years)))
            list.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(from, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6.toPx })
                addView(to, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 6.toPx; marginEnd = 6.toPx })
                addView(rating, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 6.toPx })
            })

            list.addView(label(activity.getString(R.string.cat_country)))
            GenreRows.countries.forEach { (id, v) -> countryChips.addView(chip(v.first, id == (old?.country ?: "all")).apply { tag = id }) }
            list.addView(countryChips)
        }

        val adultChip = chip(activity.getString(R.string.cat_adult), old?.adult == true)
        if (Adult.visible(activity)) {
            list.addView(label(activity.getString(R.string.cat_adult_label)))
            list.addView(adultChip)
        }

        val count = TextView(activity).apply {
            textSize = 13f
            setTextColor(text)
            setPadding(12.toPx, 10.toPx, 12.toPx, 10.toPx)
            background = GradientDrawable().apply { cornerRadius = 12.toPx.toFloat(); setColor(box) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 14.toPx, 0, 0) }
        }
        list.addView(count)

        fun read(): GenreRows.Custom? {
            val n = name.text.toString().trim().take(40)
            if (n.isEmpty()) return null
            val g = (0 until genreChips.childCount).mapNotNull { genreChips.getChildAt(it) as? Chip }.filter { it.isChecked && it.tag != null }.map { it.tag.toString() }
            val c = (0 until countryChips.childCount).mapNotNull { countryChips.getChildAt(it) as? Chip }.firstOrNull { it.isChecked }?.tag?.toString() ?: "all"
            val r = rating.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.coerceAtMost(10.0)
            return GenreRows.Custom(
                n,
                words.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() },
                g, allChip.isChecked,
                from.text.toString().toIntOrNull()?.takeIf { it in 1900..2100 },
                to.text.toString().toIntOrNull()?.takeIf { it in 1900..2100 },
                r, c, adultChip.isChecked && Adult.visible(activity),
            )
        }

        fun recount() {
            val c = read()?.takeIf { it.words.isNotEmpty() || it.needsTmdb }
            count.text = when {
                c == null -> activity.getString(R.string.cat_count_none)
                c.adult -> activity.getString(R.string.cat_count_adult)
                else -> {
                    val (n, unknown) = GenreRows.count(c, RowOrder.lastItems)
                    activity.getString(if (unknown) R.string.cat_count_wait else R.string.cat_count, n)
                }
            }
        }
        val watch = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = recount()
        }
        listOf(name, words, from, to, rating).forEach { it.addTextChangedListener(watch) }
        (0 until genreChips.childCount).forEach { (genreChips.getChildAt(it) as? Chip)?.setOnCheckedChangeListener { _, _ -> recount() } }
        (0 until countryChips.childCount).forEach { (countryChips.getChildAt(it) as? Chip)?.setOnCheckedChangeListener { _, _ -> recount() } }
        adultChip.setOnCheckedChangeListener { _, _ -> recount() }
        recount()
        if (hasKey) GenreRows.prefetch(activity, RowOrder.lastItems) { if (dialog.isShowing) recount() }

        list.addView(button(if (old == null) R.string.cat_add else R.string.cat_save, true) {
            val c = read()
            if (c == null) {
                name.error = activity.getString(R.string.cat_name_needed)
                name.requestFocus()
                return@button
            }
            if (c.words.isEmpty() && !c.needsTmdb) {
                words.error = activity.getString(R.string.cat_rule_needed)
                words.requestFocus()
                return@button
            }
            if (old == null && GenreRows.genres.any { it.name.equals(c.name, true) }) {
                name.error = activity.getString(R.string.cat_name_taken)
                return@button
            }
            GenreRows.saveCustom(activity, c, old?.name)
            dialog.dismiss()
            done(old?.name to c.name)
        })
        if (old != null) {
            list.addView(button(R.string.cat_delete, false) {
                GenreRows.removeCustom(activity, old.name)
                dialog.dismiss()
                done(old.name to null)
            })
        }

        dialog.setContentView(ScrollView(activity).apply {
            addView(list)
            setBackgroundColor(activity.colorFromAttribute(R.attr.primaryGrayBackground))
        })
        dialog.show()
        name.requestFocus()
    }
}
