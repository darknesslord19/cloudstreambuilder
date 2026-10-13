package com.lagradost.cloudstream3.fork

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R

/**
 * The card look of the settings: rows grouped on rounded cards, each with its icon on a
 * small square in the accent colour of the theme.
 */
object SettingsLook {
    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return try {
            if (!context.theme.resolveAttribute(attr, value, true)) return fallback
            if (value.resourceId != 0) context.resources.getColor(value.resourceId, context.theme) else value.data
        } catch (t: Throwable) {
            fallback
        }
    }

    fun accent(context: Context): Int =
        themeColor(context, android.R.attr.colorPrimary, themeColor(context, R.attr.textColor, 0xFFFFFFFF.toInt()))

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** The accent square behind a settings icon. */
    fun styleIcon(view: AppCompatImageView) {
        val context = view.context
        val accent = accent(context)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 14f)
            setColor(withAlpha(accent, 0x2E))
        }
        view.imageTintList = ColorStateList.valueOf(accent)
        view.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val pad = dp(context, 11f).toInt()
        view.setPadding(pad, pad, pad, pad)
    }

    /** The coloured ring around the profile picture. */
    fun styleRing(view: View) {
        val context = view.context
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(context, 3f).toInt(), accent(context))
        }
    }

    /** Lets the cards of [root] come in one after another, as the screen opens. */
    fun riseIn(cards: List<View>) {
        cards.forEachIndexed { index, card ->
            if (!card.isVisible) return@forEachIndexed
            card.alpha = 0f
            card.translationY = dp(card.context, 18f)
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(40L * index)
                .setDuration(320L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /** Clips the rows of every card to its rounded corners. */
    fun clipCards(cards: List<View>) {
        for (card in cards) card.clipToOutline = true
    }

    /**
     * Draws the rows of a settings list on rounded cards: the rows between two categories
     * share one card, with a thin line between them.
     */
    class CardDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val margin = dp(context, 16f).toInt()
        private val radius = dp(context, 22f)
        private val gapAfter = dp(context, 4f).toInt()
        private val lineInset = dp(context, 78f)
        private val plainInset = dp(context, 20f)
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themeColor(context, R.attr.primaryGrayBackground, 0xFF2B2C30.toInt())
        }
        private val linePaint = Paint().apply {
            color = themeColor(context, R.attr.primaryBlackBackground, 0xFF111111.toInt())
            strokeWidth = dp(context, 1f).coerceAtLeast(1f)
        }
        private val path = Path()
        private val rect = RectF()

        @SuppressLint("RestrictedApi")
        private fun isCategory(parent: RecyclerView, position: Int): Boolean {
            val adapter = parent.adapter as? PreferenceGroupAdapter ?: return false
            if (position < 0 || position >= adapter.itemCount) return true
            return try {
                adapter.getItem(position) is PreferenceCategory
            } catch (t: Throwable) {
                false
            }
        }

        private fun place(parent: RecyclerView, position: Int): Pair<Boolean, Boolean>? {
            if (position == RecyclerView.NO_POSITION || isCategory(parent, position)) return null
            val count = parent.adapter?.itemCount ?: 0
            val first = position == 0 || isCategory(parent, position - 1)
            val last = position == count - 1 || isCategory(parent, position + 1)
            return first to last
        }

        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val (_, last) = place(parent, parent.getChildAdapterPosition(view)) ?: return
            outRect.left = margin
            outRect.right = margin
            if (last) outRect.bottom = gapAfter
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                val (first, last) = place(parent, parent.getChildAdapterPosition(child)) ?: continue
                val top = child.top + child.translationY
                val bottom = child.bottom + child.translationY
                rect.set(child.left.toFloat(), top, child.right.toFloat(), bottom)
                val t = if (first) radius else 0f
                val b = if (last) radius else 0f
                path.reset()
                path.addRoundRect(rect, floatArrayOf(t, t, t, t, b, b, b, b), Path.Direction.CW)
                cardPaint.alpha = (255 * child.alpha).toInt()
                c.drawPath(path, cardPaint)
                if (!first) {
                    val hasIcon = child.findViewById<View>(androidx.preference.R.id.icon_frame)?.isVisible == true
                    val start = child.left + if (hasIcon) lineInset else plainInset
                    c.drawLine(start, top, child.right - plainInset, top, linePaint)
                }
            }
        }
    }

    /** Puts [CardDecoration] on a settings list once. */
    fun decorate(list: RecyclerView?) {
        list ?: return
        for (i in 0 until list.itemDecorationCount) {
            if (list.getItemDecorationAt(i) is CardDecoration) return
        }
        list.addItemDecoration(CardDecoration(list.context))
        (list.parent as? ViewGroup)?.clipChildren = true
    }
}

/** A settings icon on its accent square. */
class IconBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    init {
        SettingsLook.styleIcon(this)
    }
}
