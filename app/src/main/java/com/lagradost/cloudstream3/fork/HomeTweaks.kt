package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.fixVisual
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * The home screen settings: small changes to CloudStream's own home screen, each one can be
 * switched on and off, all of them together with the main switch, and from outside with
 * "home_tweaks".
 */
object HomeTweaks {
    const val PREF_ON = "fork_home_tweaks"

    enum class Tweak(val key: String, val title: Int, val summary: Int) {
        HERO("fork_ht_hero", R.string.ht_hero, R.string.ht_hero_des),
        PROGRESS("fork_ht_progress", R.string.ht_progress, R.string.ht_progress_des),
        TITLES("fork_ht_titles", R.string.ht_titles, R.string.ht_titles_des),
        RATING("fork_ht_rating", R.string.ht_rating, R.string.ht_rating_des),
        FILTER("fork_ht_filter", R.string.ht_filter, R.string.ht_filter_des),
        NAV("fork_ht_nav", R.string.ht_nav, R.string.ht_nav_des),
        HEADERS("fork_ht_headers", R.string.ht_headers, R.string.ht_headers_des),
        NEW("fork_ht_new", R.string.ht_new, R.string.ht_new_des),
        QUICK("fork_ht_quick", R.string.ht_quick, R.string.ht_quick_des),
    }

    private val state = HashMap<String, Boolean>()

    @Volatile
    private var loaded = false

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == PREF_ON || Tweak.entries.any { it.key == key }) read(prefs)
    }

    private fun read(prefs: SharedPreferences) {
        state[PREF_ON] = prefs.getBoolean(PREF_ON, true)
        Tweak.entries.forEach { state[it.key] = prefs.getBoolean(it.key, true) }
    }

    private fun ensure(context: Context) {
        if (loaded) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        read(prefs)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        loaded = true
    }

    /** The home screen settings may be used at all */
    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.HOME_TWEAKS, context)

    fun on(context: Context?, tweak: Tweak): Boolean {
        context ?: return false
        if (!available(context)) return false
        ensure(context)
        return state[PREF_ON] == true && state[tweak.key] == true
    }

    // ---------------------------------------------------------------- posters

    private const val TAG_BAR = "fork_ht_bar"
    private const val TAG_BADGE = "fork_ht_badge"
    private val newRow = Regex("yeni|new|son eklenen|latest|recent|güncel", RegexOption.IGNORE_CASE)
    private val seriesRow = Regex("dizi|series|bölüm|episode|anime", RegexOption.IGNORE_CASE)

    /** "Türkçe - English" names: the first part is enough on a card. */
    fun cleanName(name: String): String {
        val parts = name.split(" - ", " – ")
        return if (parts.size > 1 && parts[0].isNotBlank()) parts[0].trim() else name
    }

    private fun yearOf(card: SearchResponse): Int? = when (card) {
        is MovieSearchResponse -> card.year
        is TvSeriesSearchResponse -> card.year
        is AnimeSearchResponse -> card.year
        else -> null
    }

    private fun typeName(context: Context, type: TvType?): String? = when (type) {
        null -> null
        TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> context.getString(R.string.ht_type_movie)
        TvType.Anime, TvType.OVA -> context.getString(R.string.ht_type_anime)
        TvType.Live -> context.getString(R.string.ht_type_live)
        else -> context.getString(R.string.ht_type_series)
    }

    /**
     * Changes a poster of a home row after CloudStream filled it: the name, the bar of what is
     * watched, the colour of the rating and the "new" label.
     */
    fun decoratePoster(itemView: View, card: SearchResponse, position: Int, rowName: String?, width: Int) {
        val context = itemView.context
        if (!available(context)) {
            clearExtras(itemView)
            return
        }
        val title: TextView? = itemView.findViewById(R.id.imageText)
        val rating: TextView? = itemView.findViewById(R.id.text_rating)
        val cardBox: FrameLayout? = itemView.findViewById(R.id.background_card)
        val resume = card as? DataStoreHelper.ResumeWatchingResult

        // 3 the name on one line, under it the year and kind (or how much is left)
        val titles = on(context, Tweak.TITLES)
        val progress = on(context, Tweak.PROGRESS) && resume != null
        if (title != null && title.isVisible && (titles || progress)) {
            val name = if (titles) cleanName(card.name) else card.name
            val meta = when {
                progress -> remaining(context, resume)
                titles -> listOfNotNull(yearOf(card)?.toString(), typeName(context, card.type)).joinToString(" · ")
                else -> null
            }
            val avail = (width - 10.toPx).coerceAtLeast(40.toPx).toFloat()
            val shown = TextUtils.ellipsize(name, title.paint, avail, TextUtils.TruncateAt.END)
            val text = SpannableStringBuilder(shown)
            text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!meta.isNullOrBlank()) {
                val start = text.length
                text.append('\n').append(meta)
                val color = if (progress) context.colorFromAttribute(R.attr.colorPrimary)
                else context.colorFromAttribute(R.attr.grayTextColor)
                text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(RelativeSizeSpan(0.85f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            title.text = text
            title.gravity = if (titles) Gravity.START else Gravity.CENTER
            title.maxLines = 2
        } else if (title != null) {
            title.gravity = Gravity.CENTER
        }

        // 2 a thin bar of what is watched at the bottom of the poster
        val bar = cardBox?.findViewWithTag<ProgressBar>(TAG_BAR)
        val pos = resume?.watchPos?.fixVisual()
        if (progress && pos != null && pos.duration > 0 && cardBox != null) {
            val b = bar ?: ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = TAG_BAR
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4.toPx, Gravity.BOTTOM).apply {
                    setMargins(6.toPx, 0, 6.toPx, 6.toPx)
                }
                progressTintList = ColorStateList.valueOf(context.colorFromAttribute(R.attr.colorPrimary))
                progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
                cardBox.addView(this)
            }
            b.max = (pos.duration / 1000).toInt().coerceAtLeast(1)
            b.progress = (pos.position / 1000).toInt()
            b.isVisible = true
        } else {
            bar?.isVisible = false
        }

        // 4 the rating in the colour of how good it is
        if (rating != null && rating.isVisible) {
            if (on(context, Tweak.RATING)) {
                val value = Regex("""\d+(?:[.,]\d+)?""").find(rating.text)?.value?.replace(',', '.')?.toDoubleOrNull()
                val color = when {
                    value == null -> context.colorFromAttribute(R.attr.textColor)
                    value >= 7.0 -> Color.parseColor("#5FD38A")
                    value >= 6.0 -> Color.parseColor("#F5C518")
                    else -> Color.parseColor("#FF7A6B")
                }
                rating.setTextColor(color)
                rating.compoundDrawablesRelative.forEach { it?.mutate()?.setTint(color) }
            }
        }

        // 8 "new" on the first posters of rows of new things
        val badge = cardBox?.findViewWithTag<TextView>(TAG_BADGE)
        if (on(context, Tweak.NEW) && cardBox != null && position < 4 && rowName != null && newRow.containsMatchIn(rowName)) {
            val series = seriesRow.containsMatchIn(rowName) || (card.type != null && card.type?.isMovieType() == false)
            val b = badge ?: TextView(context).apply {
                tag = TAG_BADGE
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(6.toPx, 2.toPx, 6.toPx, 2.toPx)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
                ).apply { setMargins(6.toPx, 0, 0, 8.toPx) }
                background = GradientDrawable().apply {
                    cornerRadius = 6.toPx.toFloat()
                    setColor(context.colorFromAttribute(R.attr.colorPrimary))
                }
                setTextColor(context.colorFromAttribute(R.attr.primaryBlackBackground))
                cardBox.addView(this)
            }
            b.setText(if (series) R.string.ht_new_episode else R.string.ht_new_badge)
            b.isVisible = true
        } else {
            badge?.isVisible = false
        }
    }

    private fun clearExtras(itemView: View) {
        itemView.findViewWithTag<View>(TAG_BAR)?.isVisible = false
        itemView.findViewWithTag<View>(TAG_BADGE)?.isVisible = false
    }

    private fun remaining(context: Context, resume: DataStoreHelper.ResumeWatchingResult?): String? {
        val pos = resume?.watchPos?.fixVisual() ?: return null
        val left = ((pos.duration - pos.position) / 60_000L).coerceAtLeast(1)
        val time = if (left >= 60) context.getString(R.string.ht_left_hours, left / 60, left % 60)
        else context.getString(R.string.ht_left_minutes, left)
        val episode = if (resume.type?.isMovieType() == false && resume.episode != null) {
            if (resume.season != null) "S${resume.season} B${resume.episode} · " else "B${resume.episode} · "
        } else ""
        return episode + time
    }

    // ---------------------------------------------------------------- row headers

    /** Draws a short text, used for "All ›" at the end of a row header. */
    private class TextDrawable(private val text: String, color: Int, size: Float) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        private val width = paint.measureText(text).toInt()
        private val height = (paint.descent() - paint.ascent()).toInt()
        override fun draw(canvas: android.graphics.Canvas) {
            canvas.drawText(text, bounds.left.toFloat(), bounds.top - paint.ascent(), paint)
        }
        override fun getIntrinsicWidth() = width
        override fun getIntrinsicHeight() = height
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** "Name  24" with "All ›" at the end instead of the arrow. */
    fun styleRowHeader(header: TextView, name: String, count: Int, hasMore: Boolean) {
        val context = header.context
        if (!on(context, Tweak.HEADERS)) {
            if (header.getTag(R.id.fork_motion_tag) == "ht") {
                header.setTag(R.id.fork_motion_tag, null)
                val arrow = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_baseline_arrow_forward_24)?.mutate()
                arrow?.setTint(context.colorFromAttribute(R.attr.white))
                header.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
            }
            header.text = name
            return
        }
        header.setTag(R.id.fork_motion_tag, "ht")
        val text = SpannableStringBuilder(name)
        if (count > 0) {
            val start = text.length
            text.append("  ").append(count.toString()).append(if (hasMore) "+" else "")
            text.setSpan(ForegroundColorSpan(context.colorFromAttribute(R.attr.grayTextColor)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.7f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        header.text = text
        val all = TextDrawable(context.getString(R.string.ht_all), context.colorFromAttribute(R.attr.colorPrimary), 14f * context.resources.displayMetrics.scaledDensity)
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, all, null)
    }

    // ---------------------------------------------------------------- the big poster on top

    /** The year, duration and score above the tags of the big poster. */
    fun heroTags(context: Context, item: LoadResponse): CharSequence? {
        if (!on(context, Tweak.HERO)) return null
        val parts = ArrayList<String>()
        item.score?.toStringNull(0.1, 10, 1, false)?.let { parts.add("IMDb $it") }
        item.year?.let { parts.add(it.toString()) }
        item.duration?.takeIf { it > 0 }?.let { parts.add(context.getString(R.string.duration_format, it)) }
        if (parts.isEmpty()) return null
        val text = SpannableStringBuilder(parts.joinToString("  ·  "))
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        item.tags?.takeIf { it.isNotEmpty() }?.let { text.append('\n').append(it.joinToString(" • ")) }
        return text
    }

    /** Round glass buttons under the big poster, "Listem" instead of "Yok". */
    fun styleHeroButtons(bookmark: TextView?, info: TextView?, play: View?, watchType: WatchType) {
        val context = (bookmark ?: info ?: play)?.context ?: return
        if (!on(context, Tweak.HERO)) return
        if (watchType == WatchType.NONE) bookmark?.setText(R.string.ht_my_list)
        for (button in listOfNotNull(bookmark, info)) {
            if (button.getTag(R.id.fork_motion_tag) == "ht") continue
            button.setTag(R.id.fork_motion_tag, "ht")
            button.background = GradientDrawable().apply {
                cornerRadius = 22.toPx.toFloat()
                setColor(Color.argb(46, 255, 255, 255))
            }
            button.setPadding(10.toPx, 6.toPx, 10.toPx, 6.toPx)
        }
        (play as? com.google.android.material.button.MaterialButton)?.cornerRadius = 24.toPx
    }

    /** Small dots under the big poster that show which one is on screen. */
    fun heroDots(holder: ViewGroup?, count: Int, current: Int, bottom: Int = 6.toPx) {
        holder ?: return
        val context = holder.context
        var dots = holder.findViewWithTag<LinearLayout>("fork_ht_dots")
        if (!on(context, Tweak.HERO) || count < 2) {
            dots?.isVisible = false
            return
        }
        if (dots == null) {
            dots = LinearLayout(context).apply {
                tag = "fork_ht_dots"
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = bottom }
            }
            holder.addView(dots)
        }
        dots.isVisible = true
        val shown = count.coerceAtMost(8)
        if (dots.childCount != shown) {
            dots.removeAllViews()
            repeat(shown) {
                dots.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(6.toPx, 6.toPx).apply { setMargins(3.toPx, 0, 3.toPx, 0) }
                })
            }
        }
        val active = current % shown
        for (i in 0 until shown) {
            val dot = dots.getChildAt(i)
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply { width = if (i == active) 18.toPx else 6.toPx }
            dot.background = GradientDrawable().apply {
                cornerRadius = 3.toPx.toFloat()
                setColor(if (i == active) Color.WHITE else Color.argb(80, 255, 255, 255))
            }
        }
    }

    // ---------------------------------------------------------------- the quick menu

    /** Holding a poster: watch, add to the list, mark as watched, details, without opening it. */
    fun quickMenu(activity: Activity, card: SearchResponse): Boolean {
        if (!on(activity, Tweak.QUICK)) return false
        val sheet = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.fork_quick_menu, null, false)
        view.findViewById<ImageView>(R.id.quick_poster)?.loadImage(card.posterUrl, card.posterHeaders)
        view.findViewById<TextView>(R.id.quick_title)?.text = cleanName(card.name)
        view.findViewById<TextView>(R.id.quick_meta)?.text =
            listOfNotNull(yearOf(card)?.toString(), typeName(activity, card.type), card.apiName).joinToString(" · ")

        fun mark(type: WatchType, message: Int) {
            val response = ResultViewModel2.LoadResponseFromSearch(
                name = card.name,
                url = card.url,
                apiName = card.apiName,
                type = card.type ?: TvType.Others,
                posterUrl = card.posterUrl,
                id = card.id,
            ).apply { posterHeaders = card.posterHeaders }
            ResultViewModel2().updateWatchStatus(type, activity, response) { changed ->
                if (changed) showToast(message, Toast.LENGTH_SHORT)
            }
        }
        view.findViewById<View>(R.id.quick_play)?.setOnClickListener {
            sheet.dismiss()
            activity.loadSearchResult(card, START_ACTION_RESUME_LATEST)
        }
        view.findViewById<View>(R.id.quick_list)?.setOnClickListener {
            sheet.dismiss()
            mark(WatchType.PLANTOWATCH, R.string.ht_added_list)
        }
        view.findViewById<View>(R.id.quick_watched)?.setOnClickListener {
            sheet.dismiss()
            mark(WatchType.COMPLETED, R.string.ht_marked_watched)
        }
        view.findViewById<View>(R.id.quick_info)?.setOnClickListener {
            sheet.dismiss()
            activity.loadSearchResult(card)
        }
        sheet.setContentView(view)
        sheet.show()
        view.findViewById<View>(R.id.quick_play)?.requestFocus()
        return true
    }
}
