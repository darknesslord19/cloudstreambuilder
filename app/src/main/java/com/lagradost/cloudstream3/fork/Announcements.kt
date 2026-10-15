package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/**
 * A message from the developer, shown when the app starts. It is read from [URL]:
 *
 * { "show": true, "id": 1, "title": "Duyuru !", "message": "...", "image": "https://.../resim.jpg",
 *   "button_text": "Telegram", "button_url": "https://t.me/...", "ok_text": "Tamam",
 *   "show_ok": true, "color": "#C9A45C", "once": true }
 * The card takes the colours of the theme, "color" gives it one of its own.
 *
 * A message is shown once, give it a new "id" to show something again. With "once": false it
 * is shown at every start for as long as "show" is true.
 */
object Announcements {
    private const val URL =
        "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Cloudstreamduyuru.json"
    private const val PREFS = "fork_announcements"
    private const val KEY_SEEN = "seen"

    @Volatile
    private var shownThisStart = false

    // ---------------------------------------------------------------- the card

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * t + ((b shr shift) and 0xFF) * (1 - t)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun isLight(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160
    }

    /** The lower layer: rounded on top, a point at the bottom, a thin line of the accent along it. */
    private class PointView(context: Context, private val fill: Int, private val edge: Int, private val accent: Int, private val accent2: Int) :
        android.view.View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val path = android.graphics.Path()
        override fun onDraw(canvas: android.graphics.Canvas) {
            val d = resources.displayMetrics.density
            val w = width.toFloat()
            val h = height.toFloat()
            fun x(v: Float) = v / 300f * w
            fun yb(v: Float) = h - (470f - v) * d // from the bottom, in dp of the 300 x 470 drawing
            val top = 18f * d
            path.reset()
            path.moveTo(x(10f), top + 22f * d)
            path.quadTo(x(10f), top, x(32f), top)
            path.lineTo(x(268f), top)
            path.quadTo(x(290f), top, x(290f), top + 22f * d)
            path.lineTo(x(290f), yb(356f))
            path.quadTo(x(290f), yb(378f), x(272f), yb(391f))
            path.lineTo(x(168f), yb(458f))
            path.quadTo(x(150f), yb(470f), x(132f), yb(458f))
            path.lineTo(x(28f), yb(391f))
            path.quadTo(x(10f), yb(378f), x(10f), yb(356f))
            path.close()
            paint.style = android.graphics.Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, mixOf(fill, 0xFFFFFFFF.toInt(), 0.04f), fill, android.graphics.Shader.TileMode.CLAMP)
            paint.setShadowLayer(22f * d, 0f, 14f * d, 0xAA000000.toInt())
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
            paint.shader = null
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 1f * d
            paint.color = edge
            canvas.drawPath(path, paint)
            // the line along the point
            path.reset()
            path.moveTo(x(26f), yb(336f))
            path.lineTo(x(26f), yb(354f))
            path.quadTo(x(26f), yb(370f), x(41f), yb(381f))
            path.lineTo(x(141f), yb(446f))
            path.quadTo(x(150f), yb(452f), x(159f), yb(446f))
            path.lineTo(x(259f), yb(381f))
            path.quadTo(x(274f), yb(370f), x(274f), yb(354f))
            path.lineTo(x(274f), yb(336f))
            paint.strokeWidth = 1.6f * d
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.shader = android.graphics.LinearGradient(0f, 0f, w, h, accent, accent2, android.graphics.Shader.TileMode.MIRROR)
            canvas.drawPath(path, paint)
            paint.shader = null
        }

        private fun mixOf(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((b shr shift) and 0xFF) * t + ((a shr shift) and 0xFF) * (1 - t)).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /** The upper card: rounded on top, a soft curve at the bottom. */
    private class CardShape(private val top: Int, private val bottom: Int, private val d: Float) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val path = android.graphics.Path()
        override fun draw(canvas: android.graphics.Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val r = 30f * d
            val curve = 46f * d
            path.reset()
            path.moveTo(0f, r)
            path.quadTo(0f, 0f, r, 0f)
            path.lineTo(w - r, 0f)
            path.quadTo(w, 0f, w, r)
            path.lineTo(w, h - curve)
            path.cubicTo(w, h - curve * 0.2f, w * 0.78f, h, w / 2f, h)
            path.cubicTo(w * 0.22f, h, 0f, h - curve * 0.2f, 0f, h - curve)
            path.close()
            paint.style = android.graphics.Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(0f, 0f, w * 0.2f, h, top, bottom, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = d
            paint.color = 0x1FFFFFFF
            canvas.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun showCard(activity: Activity, a: RemoteRules.Announcement) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val c = { attr: Int, fb: Int -> SettingsLook.themeColor(activity, attr, fb) }
        val panel = c(R.attr.primaryGrayBackground, 0xFF2A2A2F.toInt())
        val bg = c(R.attr.primaryBlackBackground, 0xFF111111.toInt())
        val text = c(R.attr.textColor, 0xFFFFFFFF.toInt())
        val gray = c(R.attr.grayTextColor, 0xFFAAAAAA.toInt())
        val accent = a.color?.let {
            try { android.graphics.Color.parseColor(if (it.startsWith("#")) it else "#$it") } catch (t: Throwable) { null }
        } ?: c(androidx.appcompat.R.attr.colorPrimary, 0xFFC9A45C.toInt())
        val accentLight = mix(accent, 0xFFFFFFFF.toInt(), 0.72f)
        val onAccent = if (isLight(accent)) 0xFF15110A.toInt() else 0xFFFFFFFF.toInt()
        val light = isLight(bg)
        val lower = if (light) mix(panel, bg, 0.6f) else mix(bg, panel, 0.55f)
        val upperTop = if (light) panel else mix(panel, 0xFFFFFFFF.toInt(), 0.92f)

        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val width = minOf(dp(300), activity.resources.displayMetrics.widthPixels - dp(40))
        val point = PointView(activity, lower, 0x10FFFFFF, accent, accentLight)
        // As high as its content: the point is drawn behind it in exactly that size
        val shell = object : android.widget.FrameLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val content = getChildAt(1) ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val w = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY)
                content.measure(w, android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                val h = content.measuredHeight
                point.measure(w, android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
                setMeasuredDimension(width, h)
            }
        }
        shell.addView(point, android.widget.FrameLayout.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

        val column = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        // upper card
        val upper = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = CardShape(upperTop, panel, d)
            setPadding(dp(20), dp(22), dp(20), dp(40))
            elevation = 12f * d
        }
        a.image?.let { url ->
            val picture = android.widget.ImageView(activity).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f * d
                    setColor(bg)
                }
            }
            upper.addView(picture, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply { bottomMargin = dp(12) })
            try { picture.loadImage(url) } catch (t: Throwable) { logError(t) }
        }
        val titleText = a.title ?: activity.getString(R.string.announcement_title)
        val title = android.text.SpannableString(titleText).apply {
            // The marks at the end in the colour of the theme, like "Duyuru !"
            val end = titleText.trimEnd()
            var start = end.length
            while (start > 0 && end[start - 1] in "!?.") start--
            if (start < end.length) setSpan(android.text.style.ForegroundColorSpan(accentLight), start, end.length, 0)
        }
        upper.addView(android.widget.TextView(activity).apply {
            this.text = title
            textSize = 30f
            setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC))
            setTextColor(text)
            letterSpacing = -0.02f
        })
        upper.addView(object : android.widget.ScrollView(activity) {
            // The text scrolls instead of making the card higher than a third of the screen
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = activity.resources.displayMetrics.heightPixels / 3
                super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(max, android.view.View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            addView(android.widget.TextView(activity).apply {
                this.text = a.message
                textSize = 13.5f
                setLineSpacing(0f, 1.25f)
                setTextColor(if (light) gray else mix(text, gray, 0.55f))
                setPadding(0, dp(8), 0, 0)
            })
        }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(upper, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (width * 14 / 300f).toInt(); rightMargin = leftMargin
        })

        // buttons
        fun button(label: String, filled: Boolean, onClick: () -> Unit) = android.widget.TextView(activity).apply {
            this.text = label
            textSize = 13.5f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (filled) onAccent else accentLight)
            setPadding(dp(8), dp(11), dp(8), dp(11))
            isFocusable = true
            isClickable = true
            fun shape(focused: Boolean) = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f * d
                if (filled) colors = intArrayOf(accentLight, accent) else setColor(0x08FFFFFF)
                if (filled) orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(if (focused) dp(2) else dp(1), if (focused) text else if (filled) accent else (accent and 0x00FFFFFF) or 0x80000000.toInt())
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(true))
                addState(intArrayOf(), shape(false))
            }
            setOnClickListener { onClick() }
        }
        val buttons = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val bp = { android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5); rightMargin = dp(5) } }
        var ok: android.view.View? = null
        a.buttonUrl?.let { url ->
            buttons.addView(button(a.buttonText ?: activity.getString(R.string.announcement_open), false) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) {
                    logError(t)
                    showToast(R.string.error, Toast.LENGTH_SHORT)
                }
            }, bp())
        }
        if (!a.hideOk || a.buttonUrl == null) {
            ok = button(a.okText ?: activity.getString(R.string.ok), true) { dialog.dismiss() }
            buttons.addView(ok, bp())
        }
        column.addView(buttons, android.widget.LinearLayout.LayoutParams((width * 246 / 300f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        // the loudspeaker at the point
        column.addView(android.widget.ImageView(activity).apply {
            setImageResource(R.drawable.ic_fork_campaign_24)
            setColorFilter(mix(text, lower, 0.75f))
        }, android.widget.LinearLayout.LayoutParams(dp(30), dp(30)).apply { topMargin = dp(62); bottomMargin = dp(46) })

        shell.addView(column, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(shell, android.view.ViewGroup.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
            setDimAmount(0.7f)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply { blurBehindRadius = dp(6) }
            }
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        dialog.show()
        (ok ?: buttons.getChildAt(0))?.requestFocus()
    }

    fun check(activity: Activity) {
        if (shownThisStart) return
        ioSafe {
            val announcement = try {
                RemoteRules.announcement(RemoteRules.parse(app.get(URL).text))
            } catch (t: Throwable) {
                null
            } ?: return@ioSafe

            val prefs = activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (announcement.once && prefs.getString(KEY_SEEN, null) == announcement.id) return@ioSafe

            activity.runOnUiThread {
                if (shownThisStart || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                // Not on top of the login page, it is shown at the next start instead
                if (!AccessGate.state(activity).granted) return@runOnUiThread
                try {
                    showCard(activity, announcement)
                    shownThisStart = true
                    prefs.edit().putString(KEY_SEEN, announcement.id).apply()
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        }
    }
}
