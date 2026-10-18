package com.lagradost.cloudstream3.fork

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fork.RemoteRules.Feature
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Plus > +18 Kategoriler. Off at first: the +18 rows of the plugins (their name says +18, erotik,
 * yetişkin, adult …, or the plugin is an NSFW one) are not shown on the home page nor in search.
 * When it is on, the chosen +18 categories can be added on the home page like the genres, and
 * every +18 row of a plugin can be turned off on its own. A PIN can lock it (asked once per run).
 * Switched off from outside with "adult".
 */
object Adult {
    private const val PREFS = "fork_adult"

    /** A +18 category: [test] gets the TMDB details (null without a TMDB key) and whether it is a film. */
    class Cat(val name: String, val des: String, val needsTmdb: Boolean, val test: (TmdbPosters.Info?, Boolean?) -> Boolean)

    private val asia = setOf("ko", "ja", "zh", "cn", "th", "tl", "id", "vi")
    private val europe = setOf("fr", "it", "de", "es", "pt", "nl", "sv", "da", "no", "fi", "pl", "cs", "hu", "el", "ro", "ru")

    val cats = listOf(
        Cat("Erotik", "Bütün +18 içerikler", false) { _, _ -> true },
        Cat("Yetişkin Film", "Yetişkinlere yönelik filmler", false) { i, m -> (i?.movie ?: m) == true },
        Cat("Yetişkin Dizi", "Yetişkinlere yönelik diziler", false) { i, m -> (i?.movie ?: m) == false },
        Cat("Erotik Gerilim", "Gerilim, gizem ve suç", true) { i, _ -> i != null && i.genres.any { it in setOf(53, 9648, 80) } },
        Cat("Erotik Dram", "Dram", true) { i, _ -> i != null && 18 in i.genres },
        Cat("Romantik +18", "Romantik", true) { i, _ -> i != null && 10749 in i.genres },
        Cat("Yerli +18", "Türk yapımları", true) { i, _ -> i != null && (i.lang == "tr" || "TR" in i.countries) },
        Cat("Asya +18", "Kore, Japon, Çin yapımları", true) { i, _ -> i != null && i.lang in asia },
        Cat("Avrupa +18", "Avrupa yapımları", true) { i, _ -> i != null && i.lang in europe },
        Cat("Klasik +18", "1990 öncesi", true) { i, _ -> i?.year?.let { it < 1990 } == true },
        Cat("Yetişkin Animasyon", "Animasyon", true) { i, _ -> i != null && 16 in i.genres },
    )

    private val words = listOf(
        "+18", "18+", "(18)", "[18]", "erotik", "erotic", "yetişkin", "yetiskin", "adult", "xxx", "porn", "porno",
        "seks", "sex ", "sexy", "nsfw", "hentai", "18 yaş", "18 yas"
    )

    private fun ctx(context: Context?): Context? = context ?: CloudStreamApp.context
    private fun store(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun available(context: Context?): Boolean = ForkFeatures.isOn(Feature.ADULT, context)

    fun on(context: Context?): Boolean {
        val c = ctx(context) ?: return false
        return available(c) && store(c).getBoolean("on", false)
    }

    @Volatile
    var unlocked = false

    fun hasPin(context: Context): Boolean = store(context).getString("pin", null) != null

    /** On, and not locked: +18 rows and categories may be shown. */
    fun visible(context: Context?): Boolean {
        val c = ctx(context) ?: return false
        return on(c) && (!hasPin(c) || unlocked)
    }

    fun setOn(context: Context, on: Boolean) {
        store(context).edit().putBoolean("on", on).apply()
        changed()
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("cs-adult|" + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun setPin(context: Context, pin: String?) {
        if (pin.isNullOrBlank()) store(context).edit().remove("pin").apply() else store(context).edit().putString("pin", hash(pin)).apply()
        unlocked = pin != null
        changed()
    }

    fun unlock(context: Context, pin: String): Boolean {
        val ok = store(context).getString("pin", null).let { it == null || it == hash(pin) }
        if (ok) {
            unlocked = true
            changed()
        }
        return ok
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The home page draws its rows again (on the main thread). */
    fun redraw() {
        main.post { try { RowOrder.onChanged?.invoke() } catch (t: Throwable) {} }
    }

    private fun changed() {
        GenreRows.forget()
        redraw()
    }

    // ---------------------------------------------------------------- what counts as +18

    fun isAdultName(name: String?): Boolean {
        val n = " " + (name ?: return false).lowercase(Locale("tr")) + " "
        return words.any { n.contains(it) }
    }

    /** A plugin that says it is NSFW: all its rows are +18. */
    fun isAdultSource(api: String?): Boolean = try {
        api != null && APIHolder.getApiFromNameNull(api)?.supportedTypes?.contains(TvType.NSFW) == true
    } catch (t: Throwable) {
        false
    }

    /** urls of the cards seen in +18 rows, so they can be left out of search and the other categories */
    private val adultUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isAdultCard(card: SearchResponse): Boolean = card.type == TvType.NSFW || card.url in adultUrls

    // ---------------------------------------------------------------- the +18 rows of the plugins

    class Seen(val api: String, val name: String, val count: Int)

    fun seen(context: Context): List<Seen> = try {
        val a = JSONArray(store(context).getString("seen", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Seen(it.optString("a"), it.optString("n"), it.optInt("c")) }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun remember(context: Context, found: List<Seen>) {
        if (found.isEmpty()) return
        val old = seen(context)
        val all = (found + old.filter { o -> found.none { it.api == o.api && it.name == o.name } }).take(80)
        if (old.size == all.size && old.zip(all).all { (a, b) -> a.api == b.api && a.name == b.name && a.count == b.count }) return
        store(context).edit().putString("seen", JSONArray(all.map { JSONObject().put("a", it.api).put("n", it.name).put("c", it.count) }).toString()).apply()
    }

    fun rowOn(context: Context, api: String, name: String): Boolean =
        "$api|$name" !in (store(context).getStringSet("off", emptySet()) ?: emptySet())

    fun setRow(context: Context, api: String, name: String, on: Boolean) {
        val set = (store(context).getStringSet("off", emptySet()) ?: emptySet()).toMutableSet()
        if (on) set.remove("$api|$name") else set.add("$api|$name")
        store(context).edit().putStringSet("off", set).apply()
        changed()
    }

    fun chosenCats(context: Context): Set<String> = store(context).getStringSet("cats", emptySet()) ?: emptySet()

    fun setCat(context: Context, name: String, on: Boolean) {
        val set = chosenCats(context).toMutableSet()
        if (on) set.add(name) else set.remove(name)
        store(context).edit().putStringSet("cats", set).apply()
        changed()
    }

    /**
     * The rows of a source split in two: the ordinary ones (shown) and the +18 ones (their cards
     * are used for the +18 categories). The +18 rows stay on the home page only when it is on and
     * that row is not turned off.
     */
    fun <T> split(context: Context, api: String?, rows: List<T>, nameOf: (T) -> String, cardsOf: (T) -> List<SearchResponse>): Pair<List<T>, List<SearchResponse>> {
        if (!available(context)) return rows to emptyList()
        val wholeSource = isAdultSource(api)
        val adult = rows.filter { r -> wholeSource || isAdultName(nameOf(r)) || cardsOf(r).let { it.isNotEmpty() && it.all { c -> c.type == TvType.NSFW } } }
        val cards = adult.flatMap(cardsOf)
        cards.forEach { adultUrls.add(it.url) }
        if (api != null) remember(context, adult.map { Seen(api, nameOf(it), cardsOf(it).size) })
        val show = visible(context)
        val kept = rows.filter { r -> r !in adult || (show && api != null && rowOn(context, api, nameOf(r))) }
        return kept to cards
    }

    /** Search: +18 results are left out while it is off (or locked). */
    fun filterSearch(items: List<SearchResponse>): List<SearchResponse> {
        val c = ctx(null) ?: return items
        if (!available(c) || visible(c)) return items
        return items.filter { !isAdultCard(it) }
    }

    fun searchAllowed(api: String): Boolean {
        val c = ctx(null) ?: return true
        if (!available(c) || visible(c)) return true
        return !isAdultSource(api)
    }
}
