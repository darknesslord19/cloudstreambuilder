package com.lagradost.cloudstream3.fork

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import kotlin.system.exitProcess

/**
 * What this build adds to the looks of the app: a font for the whole app, a choice of app
 * icons, and pickers that show what an option looks like before it is chosen.
 */
object Looks {
    // ---------------------------------------------------------------- fonts

    class Font(val key: String, val label: String, val style: Int, val fontRes: Int?, val family: String?)

    const val FONT_KEY = "fork_app_font"

    val fonts: List<Font> = listOf(
        Font("GoogleSans", "Google Sans", R.style.FontOverlayGoogleSans, R.font.google_sans, null),
        Font("Roboto", "Roboto", R.style.FontOverlayRoboto, null, "sans-serif"),
        Font("RobotoLight", "Roboto Light", R.style.FontOverlayRobotoLight, null, "sans-serif-light"),
        Font("RobotoMedium", "Roboto Medium", R.style.FontOverlayRobotoMedium, null, "sans-serif-medium"),
        Font("RobotoCondensed", "Roboto Condensed", R.style.FontOverlayRobotoCondensed, null, "sans-serif-condensed"),
        Font("Poppins", "Poppins", R.style.FontOverlayPoppins, R.font.poppins_regular, null),
        Font("OpenSans", "Open Sans", R.style.FontOverlayOpenSans, R.font.open_sans, null),
        Font("Ubuntu", "Ubuntu", R.style.FontOverlayUbuntu, R.font.ubuntu_regular, null),
        Font("NetflixSans", "Netflix Sans", R.style.FontOverlayNetflixSans, R.font.netflix_sans, null),
        Font("Futura", "Futura", R.style.FontOverlayFutura, R.font.futura, null),
        Font("Verdana", "Verdana", R.style.FontOverlayVerdana, R.font.verdana, null),
        Font("Trebuchet", "Trebuchet MS", R.style.FontOverlayTrebuchet, R.font.trebuchet_ms, null),
        Font("LucidaGrande", "Lucida Grande", R.style.FontOverlayLucidaGrande, R.font.lucida_grande, null),
        Font("Consolas", "Consolas", R.style.FontOverlayConsolas, R.font.consola, null),
        Font("Monospace", "Monospace", R.style.FontOverlayMonospace, null, "monospace"),
        Font("NotoSerif", "Noto Serif", R.style.FontOverlayNotoSerif, null, "serif"),
        Font("Stix", "STIX", R.style.FontOverlayStix, R.font.stix_general, null),
        Font("CutiveMono", "Cutive Mono", R.style.FontOverlayCutiveMono, null, "serif-monospace"),
        Font("ComingSoon", "Coming Soon", R.style.FontOverlayComingSoon, null, "casual"),
        Font("DancingScript", "Dancing Script", R.style.FontOverlayDancingScript, null, "cursive"),
    )

    fun currentFont(context: Context): Font {
        val key = PreferenceManager.getDefaultSharedPreferences(context).getString(FONT_KEY, null)
        return fonts.firstOrNull { it.key == key } ?: fonts.first()
    }

    /** The style that makes the chosen font the font of [context]'s theme. */
    fun fontOverlay(context: Context): Int = currentFont(context).style

    private fun typefaceOf(context: Context, font: Font): Typeface? {
        return try {
            if (font.fontRes != null) ResourcesCompat.getFont(context, font.fontRes)
            else Typeface.create(font.family, Typeface.NORMAL)
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- app icons

    /** @property alias name of the activity-alias in the manifest that carries the icon. */
    class AppIcon(val key: String, val label: Int, val alias: String, val icon: Int)

    const val ICON_KEY = "fork_app_icon"
    private const val ALIAS_PACKAGE = "com.lagradost.cloudstream3"

    val icons: List<AppIcon> = listOf(
        AppIcon("rose", R.string.icon_rose, "IconRose", R.mipmap.ic_fork_rose),
        AppIcon("blue", R.string.icon_blue, "IconBlue", R.mipmap.ic_fork_blue),
        AppIcon("red", R.string.icon_red, "IconRed", R.mipmap.ic_fork_red),
        AppIcon("orange", R.string.icon_orange, "IconOrange", R.mipmap.ic_fork_orange),
        AppIcon("gold", R.string.icon_gold, "IconGold", R.mipmap.ic_fork_gold),
        AppIcon("green", R.string.icon_green, "IconGreen", R.mipmap.ic_fork_green),
        AppIcon("teal", R.string.icon_teal, "IconTeal", R.mipmap.ic_fork_teal),
        AppIcon("purple", R.string.icon_purple, "IconPurple", R.mipmap.ic_fork_purple),
        AppIcon("pink", R.string.icon_pink, "IconPink", R.mipmap.ic_fork_pink),
        AppIcon("silver", R.string.icon_silver, "IconSilver", R.mipmap.ic_fork_silver),
    )

    fun currentIcon(context: Context): AppIcon {
        val key = PreferenceManager.getDefaultSharedPreferences(context).getString(ICON_KEY, null)
        return icons.firstOrNull { it.key == key } ?: icons.first()
    }

    /**
     * Makes [chosen] the icon of the app and closes the app. Launchers only pick a new icon up
     * reliably while the app is not running, and the system would stop the app anyway.
     */
    fun applyIcon(activity: Activity, chosen: AppIcon) {
        try {
            val manager = activity.packageManager
            // The new entry first, so there is never a moment without any launcher entry
            for (icon in listOf(chosen) + icons.filter { it.key != chosen.key }) {
                manager.setComponentEnabledSetting(
                    ComponentName(activity.packageName, "$ALIAS_PACKAGE.${icon.alias}"),
                    if (icon.key == chosen.key) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            PreferenceManager.getDefaultSharedPreferences(activity).edit().putString(ICON_KEY, chosen.key).commit()
        } catch (t: Throwable) {
            logError(t)
            return
        }
        try {
            activity.finishAndRemoveTask()
        } catch (t: Throwable) {
            logError(t)
        }
        Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 350)
    }

    // ---------------------------------------------------------------- theme previews

    /** The colours that tell a theme apart: background, surface, text and accent. */
    class Palette(val background: Int, val surface: Int, val text: Int, val accent: Int)

    /** Reads the colours [themeStyle] with [accentStyle] on top would give the app. */
    fun paletteOf(context: Context, themeStyle: Int, accentStyle: Int): Palette? {
        return try {
            val themed = ContextThemeWrapper(context.applicationContext, R.style.AppTheme)
            themed.theme.applyStyle(themeStyle, true)
            themed.theme.applyStyle(accentStyle, true)
            fun color(attr: Int): Int {
                val value = TypedValue()
                themed.theme.resolveAttribute(attr, value, true)
                return if (value.resourceId != 0) themed.resources.getColor(value.resourceId, themed.theme) else value.data
            }
            Palette(
                background = color(R.attr.primaryBlackBackground),
                surface = color(R.attr.primaryGrayBackground),
                text = color(R.attr.textColor),
                accent = color(android.R.attr.colorPrimary),
            )
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    /** A small picture of a theme: its background with a surface, a line of text and the accent. */
    private class Swatch(context: Context, private val palette: Palette) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h * 0.22f

            paint.style = Paint.Style.FILL
            paint.color = palette.background
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)

            // A card in the lower part
            paint.color = palette.surface
            rect.set(w * 0.08f, h * 0.50f, w * 0.92f, h * 0.86f)
            canvas.drawRoundRect(rect, radius * 0.5f, radius * 0.5f, paint)

            // A line of text
            paint.color = palette.text
            rect.set(w * 0.10f, h * 0.20f, w * 0.52f, h * 0.32f)
            canvas.drawRoundRect(rect, h * 0.06f, h * 0.06f, paint)

            // The accent colour
            paint.color = palette.accent
            canvas.drawCircle(w * 0.80f, h * 0.26f, h * 0.14f, paint)

            // An outline, so a dark theme is seen on a dark dialog
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * 0.03f
            paint.color = (palette.text and 0x00FFFFFF) or 0x40000000
            rect.set(paint.strokeWidth, paint.strokeWidth, w - paint.strokeWidth, h - paint.strokeWidth)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    // ---------------------------------------------------------------- pickers

    /** One option of [pick]: a name, and what shows how the option looks. */
    class Option(
        val label: String,
        val palette: Palette? = null,
        val icon: Int? = null,
        val typeface: Typeface? = null,
    )

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) context.resources.getColor(value.resourceId, context.theme) else value.data
        } else fallback
    }

    /** A list to choose one of [options] from, the current one is marked. */
    fun pick(activity: Activity, title: String, options: List<Option>, selected: Int, onPick: (Int) -> Unit) {
        val textColor = themeColor(activity, R.attr.textColor, 0xFFFFFFFF.toInt())
        val accentColor = themeColor(activity, android.R.attr.colorPrimary, textColor)

        val adapter = object : BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(position: Int) = options[position]
            override fun getItemId(position: Int) = position.toLong()

            // The rows differ too much to be worth recycling, and the list is short
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val option = options[position]
                val isSelected = position == selected
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(activity, 56f)
                    setPadding(dp(activity, 20f), dp(activity, 8f), dp(activity, 20f), dp(activity, 8f))
                }
                if (option.icon != null) {
                    row.addView(ImageView(activity).apply {
                        setImageResource(option.icon)
                    }, LinearLayout.LayoutParams(dp(activity, 48f), dp(activity, 48f)).apply {
                        marginEnd = dp(activity, 16f)
                    })
                }
                row.addView(TextView(activity).apply {
                    text = if (isSelected) option.label + "  ✓" else option.label
                    textSize = 16f
                    setTextColor(if (isSelected) accentColor else textColor)
                    option.typeface?.let { typeface = it }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (option.palette != null) {
                    row.addView(
                        Swatch(activity, option.palette),
                        LinearLayout.LayoutParams(dp(activity, 64f), dp(activity, 40f)).apply {
                            marginStart = dp(activity, 12f)
                        }
                    )
                }
                return row
            }
        }

        val list = ListView(activity).apply {
            divider = null
            this.adapter = adapter
            if (selected >= 0) setSelection(selected)
        }
        val dialog = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(title)
            .setView(list)
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onPick(position)
        }
        try {
            dialog.show()
        } catch (t: Throwable) {
            logError(t)
        }
    }

    fun fontOptions(context: Context): List<Option> = fonts.map { Option(it.label, typeface = typefaceOf(context, it)) }

    fun iconOptions(context: Context): List<Option> = icons.map { Option(context.getString(it.label), icon = it.icon) }
}
