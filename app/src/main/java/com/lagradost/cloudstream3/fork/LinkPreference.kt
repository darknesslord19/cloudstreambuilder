package com.lagradost.cloudstream3.fork

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.Locale

/**
 * Which links are listed, and so tried, first: the ones with Turkish audio or the ones with
 * Turkish subtitles. Sources say this in the name of a link, there is no field for it.
 */
object LinkPreference {
    const val KEY = "fork_link_preference"
    const val NONE = 0
    const val TURKISH_DUB = 1
    const val TURKISH_SUB = 2

    /** Larger than any difference the quality and source priorities can make. */
    private const val BONUS = 1_000_000

    @Volatile
    private var mode = -1

    private val dubbed = Regex("""dublaj|(?<![a-z])dub(?![a-z])|(?<![a-z])tr[ ._-]?dub|t[uü]rk[cç]e ses""")
    private val subtitled = Regex("""altyaz|(?<![a-z])subs?(?![a-z])|(?<![a-z])tr[ ._-]?sub""")

    fun get(context: Context): Int {
        val value = PreferenceManager.getDefaultSharedPreferences(context).getInt(KEY, NONE)
        mode = value
        return value
    }

    fun set(context: Context, value: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(KEY, value).apply()
        mode = value
    }

    /** What is added to the priority of [link] when the list of links is sorted. */
    fun bonus(link: ExtractorLink?): Int {
        if (link == null) return 0
        var current = mode
        if (current < 0) {
            current = try {
                CloudStreamApp.context?.let { get(it) } ?: NONE
            } catch (t: Throwable) {
                NONE
            }
        }
        if (current == NONE) return 0
        val text = (link.name + " " + link.source).lowercase(Locale.ROOT)
        val isDubbed = dubbed.containsMatchIn(text)
        return when (current) {
            TURKISH_DUB -> if (isDubbed) BONUS else 0
            // A link that names both is a dubbed one that also has subtitles
            TURKISH_SUB -> if (!isDubbed && subtitled.containsMatchIn(text)) BONUS else 0
            else -> 0
        }
    }
}
