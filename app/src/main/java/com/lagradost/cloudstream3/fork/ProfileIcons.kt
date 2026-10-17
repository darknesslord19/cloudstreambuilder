package com.lagradost.cloudstream3.fork

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * The profile picture is chosen from a grid instead of rolling through the pictures one by one.
 * The 20 icons of this build come after the 7 original pictures, switched off with "profile_icons".
 */
object ProfileIcons {
    private const val ORIGINAL = 7

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.PROFILE_ICONS, context)

    fun pick(context: Context, current: Int, onPick: (Int) -> Unit) {
        val count = if (available(context)) DataStoreHelper.profileImages.size else ORIGINAL
        val dialog = BottomSheetDialog(context)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        val text = context.colorFromAttribute(R.attr.textColor)
        val gray = context.colorFromAttribute(R.attr.grayTextColor)
        val primary = context.colorFromAttribute(R.attr.colorPrimary)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.toPx, 8.toPx, 14.toPx, 24.toPx)
        }
        list.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(36.toPx, 4.toPx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 2.toPx, 0, 12.toPx)
            }
            background = GradientDrawable().apply { setColor(gray); cornerRadius = 4.toPx.toFloat() }
            alpha = 0.5f
        })
        list.addView(TextView(context).apply {
            setText(R.string.profile_pick)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(text)
        })
        list.addView(TextView(context).apply {
            setText(R.string.profile_pick_des)
            textSize = 12.5f
            setTextColor(gray)
            setPadding(0, 2.toPx, 0, 10.toPx)
        })
        val columns = if (context.resources.configuration.screenWidthDp >= 600) 7 else 4
        var first: View? = null
        (0 until count).chunked(columns).forEach { row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until columns) {
                val index = row.getOrNull(i)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6.toPx, 6.toPx, 6.toPx, 6.toPx)
                }
                if (index == null) {
                    line.addView(View(context), lp)
                    continue
                }
                val selected = index == current
                val cell = CardView(context).apply {
                    radius = 999f
                    cardElevation = 0f
                    setCardBackgroundColor(if (selected) primary else 0x00000000)
                    setContentPadding(3.toPx, 3.toPx, 3.toPx, 3.toPx)
                    isFocusable = true
                    isClickable = true
                    foreground = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(3.toPx, text)
                        })
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        onPick(index)
                    }
                }
                val inner = CardView(context).apply {
                    radius = 999f
                    cardElevation = 0f
                    addView(ImageView(context).apply {
                        setImageResource(DataStoreHelper.profileImages[index])
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        adjustViewBounds = true
                    }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                cell.addView(inner)
                // square cells
                cell.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                    val w = r - l
                    if (w > 0 && v.layoutParams.height != w) v.post { v.layoutParams = v.layoutParams.apply { height = w } }
                }
                if (selected || first == null) first = cell
                line.addView(cell, lp)
            }
            list.addView(line)
        }
        dialog.setContentView(ScrollView(context).apply {
            addView(list)
            setBackgroundColor(context.colorFromAttribute(R.attr.primaryGrayBackground))
        })
        dialog.show()
        first?.requestFocus()
    }
}
