package com.lagradost.cloudstream3.fork

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog

/**
 * Movement on the posters of the home screen. Each effect can be switched off on its own in the
 * layout settings, and all of them from outside with the "animated_posters" feature.
 */
object PosterMotion {
    enum class Effect(val key: String, val label: Int) {
        ZOOM("fork_motion_zoom", R.string.motion_zoom),
        AUTO("fork_motion_auto", R.string.motion_auto),
        SHINE("fork_motion_shine", R.string.motion_shine),
        PARALLAX("fork_motion_parallax", R.string.motion_parallax),
        PRESS("fork_motion_press", R.string.motion_press),
    }

    /** The setting in the layout settings */
    const val PREF_KEY = "fork_animated_posters"

    /** How long the big poster stays before the next one comes */
    const val AUTO_DELAY_MS = 7000L
    private const val ZOOM_MS = 9000L

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.MOTION, context)

    private fun animationsAllowed(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
    }

    fun isOn(context: Context?, effect: Effect): Boolean {
        context ?: return false
        if (!available(context) || !animationsAllowed()) return false
        return try {
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(effect.key, true)
        } catch (t: Throwable) {
            false
        }
    }

    /** What is switched on, for the line under the setting. */
    fun summary(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val on = Effect.values().filter { prefs.getBoolean(it.key, true) }
        if (on.isEmpty()) return context.getString(R.string.motion_off)
        return on.joinToString(", ") { context.getString(it.label) }
    }

    /** The list to switch the effects on and off. */
    fun pick(activity: Activity, onDone: () -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val effects = Effect.values().toList()
        val selected = effects.indices.filter { prefs.getBoolean(effects[it].key, true) }
        activity.showMultiDialog(
            effects.map { activity.getString(it.label) },
            selected,
            activity.getString(R.string.animated_posters),
            {}
        ) { chosen ->
            prefs.edit {
                effects.forEachIndexed { index, effect -> putBoolean(effect.key, chosen.contains(index)) }
            }
            onDone()
        }
    }

    // ---------------------------------------------------------------- slow zoom

    /** Lets the picture of the big poster slowly come closer and drift, like in a trailer. */
    fun startZoom(image: View?, odd: Boolean) {
        image ?: return
        stopZoom(image)
        if (!isOn(image.context, Effect.ZOOM)) return
        val shift = image.width * 0.02f
        val fromScale = if (odd) 1.02f else 1.14f
        val toScale = if (odd) 1.14f else 1.03f
        image.scaleX = fromScale
        image.scaleY = fromScale
        image.translationX = if (odd) -shift else shift
        image.animate()
            .scaleX(toScale)
            .scaleY(toScale)
            .translationX(if (odd) shift else -shift)
            .setDuration(ZOOM_MS)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .start()
    }

    fun stopZoom(image: View?) {
        image ?: return
        image.animate().cancel()
        image.scaleX = 1f
        image.scaleY = 1f
        image.translationX = 0f
    }

    // ---------------------------------------------------------------- scroll depth

    /** Lets the big poster move slower than the page while the home screen is scrolled. */
    fun parallax(list: RecyclerView) {
        val header = list.findViewHolderForAdapterPosition(0)?.itemView ?: return
        val pager = header.findViewById<View>(R.id.home_preview_viewpager) ?: return
        val shift = if (isOn(list.context, Effect.PARALLAX)) (-header.top * 0.45f).coerceAtLeast(0f) else 0f
        if (pager.translationY != shift) pager.translationY = shift
    }

    // ---------------------------------------------------------------- cards

    /**
     * Gives a home card its passing light and its growing on touch, once. [index] spreads the
     * light over the cards of a row.
     */
    fun decorateCard(card: FrameLayout?, index: Int) {
        card ?: return
        val context = card.context
        val shine = isOn(context, Effect.SHINE)
        val existing = card.getTag(R.id.fork_motion_tag) as? ShineDrawable
        if (shine) {
            if (existing == null) {
                val drawable = ShineDrawable()
                val original = card.foreground
                card.foreground = if (original != null) LayerDrawable(arrayOf(original, drawable)) else drawable
                card.setTag(R.id.fork_motion_tag, drawable)
                drawable.offset = index * 350L
            } else {
                existing.offset = index * 350L
                existing.enabled = true
            }
        } else {
            existing?.enabled = false
        }

        val press = isOn(context, Effect.PRESS)
        val hasPress = card.getTag(R.id.fork_motion_press_tag) == true
        if (press && !hasPress) {
            card.stateListAnimator = pressAnimator(card)
            card.setTag(R.id.fork_motion_press_tag, true)
        } else if (!press && hasPress) {
            card.stateListAnimator = null
            card.scaleX = 1f
            card.scaleY = 1f
            card.setTag(R.id.fork_motion_press_tag, false)
        }
    }

    private fun pressAnimator(view: View): StateListAnimator {
        fun scaleTo(value: Float): AnimatorSet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, value),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, value),
            )
            duration = 160L
            interpolator = DecelerateInterpolator()
        }
        return StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), scaleTo(1.06f))
            addState(intArrayOf(), scaleTo(1f))
        }
    }

    /** A soft band of light that passes over a card every few seconds. */
    class ShineDrawable : Drawable(), Runnable {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()
        private var shaderWidth = -1
        var offset = 0L
        var enabled = true
            set(value) {
                field = value
                invalidateSelf()
            }

        private val cycle = 5500L
        private val sweep = 1200L

        override fun draw(canvas: Canvas) {
            if (!enabled) return
            val bounds = bounds
            val width = bounds.width()
            if (width <= 0 || bounds.height() <= 0) return
            if (shaderWidth != width) {
                shaderWidth = width
                paint.shader = LinearGradient(
                    0f, 0f, width * 0.5f, width * 0.18f,
                    intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            val now = SystemClock.uptimeMillis()
            val phase = (now + offset) % cycle
            if (phase < sweep) {
                val progress = phase.toFloat() / sweep
                matrix.setTranslate(bounds.left - width * 0.6f + progress * width * 1.7f, bounds.top.toFloat())
                paint.shader?.setLocalMatrix(matrix)
                canvas.save()
                canvas.clipRect(bounds)
                canvas.drawRect(bounds, paint)
                canvas.restore()
                // The next frame of the sweep
                invalidateSelf()
            } else {
                unscheduleSelf(this)
                scheduleSelf(this, now + (cycle - phase))
            }
        }

        override fun run() = invalidateSelf()

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            if (!visible) unscheduleSelf(this)
            return super.setVisible(visible, restart)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
