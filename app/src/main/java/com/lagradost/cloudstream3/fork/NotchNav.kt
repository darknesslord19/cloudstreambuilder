package com.lagradost.cloudstream3.fork

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The bottom bar of the phone with a round notch: the chosen tab sits in a coloured bubble and the
 * notch slides to it. The bar itself stays the BottomNavigationView of the app (its buttons, focus
 * and remote/keyboard moves are untouched); only its background is drawn here and the bubble is a
 * picture above it that takes no touches and no focus. Colours come from the theme.
 * Plus > Görünüm > "Çukurlu alt menü" turns it off.
 */
object NotchNav {
    const val PREF = "fork_notch_nav"

    fun on(activity: Activity): Boolean =
        PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF, true)

    private class Bar(private val density: Float) : Drawable() {
        var cx = -1f
        var bottomInset = 0
        var barColor = Color.DKGRAY
        var edgeColor = 0x14FFFFFF
        var haloColor = 0x12FFFFFF
        val side = 14 * density
        val under = 6 * density
        val bubble get() = min(54 * density, max(40 * density, (bounds.height() - bottomInset - under) * 0.86f))
        val gap = 6 * density
        val radius get() = bubble / 2 + gap
        val top get() = bounds.top.toFloat()
        val centerY get() = top + bubble * 0.06f

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val oval = RectF()

        private fun arc(x: Float, y: Float, r: Float, from: Float, sweep: Float) {
            oval.set(x - r, y - r, x + r, y + r)
            path.arcTo(oval, from, sweep, false)
        }

        private fun deg(y: Float, x: Float) = Math.toDegrees(atan2(y.toDouble(), x.toDouble())).toFloat()

        private fun build() {
            path.reset()
            val l = bounds.left + side
            val r = bounds.right - side
            val t = top
            val b = bounds.bottom - bottomInset - under
            if (b - t < 10 * density) return
            val corner = min((b - t) * 0.42f, 28 * density)
            val nr = radius
            val cy = centerY
            val x = cx.coerceIn(l + nr, r - nr)

            // the shoulders: as round as there is room for next to the corners
            var f = 26 * density
            fun reach(f: Float): Float {
                val dy = (t + f) - cy
                return sqrt(max(0f, (nr + f) * (nr + f) - dy * dy))
            }
            val room = min(x - l, r - x)
            while (f > 3 * density && reach(f) > room - 6 * density) f -= density
            val d = reach(f)
            val dy = (t + f) - cy
            val ux = d / (nr + f)
            val uy = -dy / (nr + f)
            val cl = min(corner, max(0f, x - d - l))
            val cr = min(corner, max(0f, r - (x + d)))

            path.moveTo(l + cl, t)
            path.lineTo(x - d, t)
            // left shoulder, the notch under the bubble, right shoulder
            val a1 = deg(uy, ux)
            arc(x - d, t + f, f, -90f, a1 + 90f)
            val n1 = deg(-uy, -ux)
            val n2 = deg(-uy, ux)
            arc(x, cy, nr, n1, n2 - n1)
            val a2 = deg(uy, -ux)
            arc(x + d, t + f, f, a2, -90f - a2)
            path.lineTo(r - cr, t)
            if (cr > 0) arc(r - cr, t + cr, cr, -90f, 90f)
            path.lineTo(r, b - corner)
            arc(r - corner, b - corner, corner, 0f, 90f)
            path.lineTo(l + corner, b)
            arc(l + corner, b - corner, corner, 90f, 90f)
            path.lineTo(l, t + cl)
            if (cl > 0) arc(l + cl, t + cl, cl, 180f, 90f)
            path.close()
        }

        override fun draw(canvas: Canvas) {
            if (cx < 0) return
            build()
            fill.color = barColor
            canvas.drawPath(path, fill)
            // a faint light inside the bar around the notch
            val save = canvas.save()
            canvas.clipPath(path)
            halo.shader = RadialGradient(cx, centerY, radius * 2.1f, haloColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, centerY, radius * 2.1f, halo)
            canvas.restoreToCount(save)
            stroke.color = edgeColor
            canvas.drawPath(path, stroke)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** settles like a spring without bouncing: the same speed and no wobble every time */
    private val settle = android.view.animation.Interpolator { t ->
        val w = 8f
        val v = 1f - (1f + w * t) * exp(-w * t)
        v / (1f - (1f + w) * exp(-w))
    }

    fun attach(activity: Activity, nav: BottomNavigationView) {
        if (!on(activity)) return
        val parent = nav.parent as? ViewGroup ?: return
        val density = activity.resources.displayMetrics.density
        val primary = activity.colorFromAttribute(R.attr.colorPrimary)
        val text = activity.colorFromAttribute(R.attr.textColor)
        val onPrimary = activity.colorFromAttribute(R.attr.primaryBlackBackground)
        val barColor = activity.colorFromAttribute(R.attr.primaryGrayBackground)

        val bar = Bar(density).apply {
            this.barColor = barColor
            edgeColor = ColorUtils.setAlphaComponent(text, 20)
            haloColor = ColorUtils.setAlphaComponent(text, 18)
        }
        nav.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_UNLABELED
        nav.isItemActiveIndicatorEnabled = false
        nav.background = bar
        nav.elevation = 0f
        nav.itemRippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(text, 24))
        nav.itemIconTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(text, 180))
        )
        // keep the tabs inside the bar
        (nav.getChildAt(0) as? View)?.let { menu ->
            (menu.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = (bar.side + 8 * density).toInt()
                lp.rightMargin = (bar.side + 8 * density).toInt()
                menu.layoutParams = lp
            }
        }

        val size = bar.bubble.toInt().coerceAtLeast(1)
        val icon = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(onPrimary, PorterDuff.Mode.SRC_IN)
        }
        val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke((2 * density).toInt(), text) }
        val bubble = FrameLayout(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(ColorUtils.blendARGB(primary, Color.WHITE, 0.22f), primary)
            ).apply { shape = GradientDrawable.OVAL }
            elevation = 8 * density
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineSpotShadowColor = primary
                outlineAmbientShadowColor = Color.BLACK
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(icon, FrameLayout.LayoutParams((26 * density).toInt(), (26 * density).toInt(), Gravity.CENTER))
        }
        parent.addView(bubble, ViewGroup.LayoutParams(size, size))

        var shownId = -1
        var x = -1f
        var anim: ValueAnimator? = null

        fun itemView(id: Int): View? = nav.findViewById(id)

        fun centerOf(id: Int): Float? {
            val v = itemView(id) ?: return null
            if (v.width == 0) return null
            val r = android.graphics.Rect()
            v.getDrawingRect(r)
            nav.offsetDescendantRectToMyCoords(v, r)
            return r.exactCenterX()
        }

        fun follow() {
            if (x < 0) return
            val b = bar.bubble
            val tx = nav.left + nav.translationX + x - b / 2
            val ty = nav.top + nav.translationY + bar.centerY - b / 2
            if (bubble.translationX != tx) bubble.translationX = tx
            if (bubble.translationY != ty) bubble.translationY = ty
        }

        fun place(at: Float) {
            x = at
            bar.cx = at
            bar.bottomInset = nav.paddingBottom
            bar.invalidateSelf()
            val b = bar.bubble.toInt()
            if (bubble.layoutParams.width != b) {
                bubble.layoutParams = bubble.layoutParams.apply { width = b; height = b }
            }
            follow()
        }

        fun showIcon(id: Int) {
            val item = nav.menu.findItem(id) ?: return
            val d = item.icon?.constantState?.newDrawable(activity.resources)?.mutate() ?: item.icon
            icon.setImageDrawable(d)
            icon.setImageState(intArrayOf(android.R.attr.state_checked), true)
        }

        fun checkedId(): Int {
            for (i in 0 until nav.menu.size()) {
                val it = nav.menu.getItem(i)
                if (it.isChecked && it.isVisible) return it.itemId
            }
            return nav.selectedItemId
        }

        fun update(animate: Boolean) {
            val id = checkedId()
            val target = centerOf(id) ?: return
            if (id != shownId) {
                shownId = id
                showIcon(id)
            } else if (anim?.isRunning != true && kotlin.math.abs(target - x) < 0.5f) {
                place(target)
                return
            }
            anim?.cancel()
            if (!animate || x < 0) {
                place(target)
                return
            }
            val from = x
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                interpolator = settle
                addUpdateListener { place(from + (target - from) * (it.animatedValue as Float)) }
                start()
            }
        }

        nav.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oRight, ob ->
            // a new size or place (rotation, the system bar): the notch goes to the tab at once
            if (r - l != oRight - ol || b - t != ob - ot || t != ot) nav.post { anim?.cancel(); x = -1f; update(false) }
        }
        nav.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val show = nav.isShown && nav.alpha > 0f
                val vis = if (show) View.VISIBLE else View.GONE
                if (bubble.visibility != vis) bubble.visibility = vis
                if (show) {
                    bubble.alpha = nav.alpha
                    if (checkedId() != shownId) update(true) else follow()
                }
                return true
            }
        })
        // the remote / keyboard is on the chosen tab: a ring around the bubble shows it
        nav.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            bubble.foreground = if (now != null && now === itemView(shownId)) ring else null
        }
        nav.post { update(false) }
    }
}
