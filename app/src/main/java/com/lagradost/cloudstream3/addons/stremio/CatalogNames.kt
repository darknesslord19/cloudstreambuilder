package com.lagradost.cloudstream3.addons.stremio

/**
 * Add-ons name their catalogs in English ("Popular", type "series"). For the languages below
 * the common names are shown translated, as "Popüler Diziler" instead of "Popular - Series".
 */
internal object CatalogNames {
    private val turkishNames = mapOf(
        "popular" to "Popüler",
        "most popular" to "En Popüler",
        "new" to "Yeni",
        "new releases" to "Yeni Çıkan",
        "latest" to "En Yeni",
        "recent" to "Son Eklenen",
        "recently added" to "Son Eklenen",
        "featured" to "Öne Çıkan",
        "trending" to "Trend",
        "top" to "En İyi",
        "top rated" to "En Çok Beğenilen",
        "best" to "En İyi",
        "upcoming" to "Yakında",
        "coming soon" to "Yakında",
        "now playing" to "Vizyondaki",
        "in theaters" to "Vizyondaki",
        "on the air" to "Yayındaki",
        "airing today" to "Bugün Yayınlanan",
        "discover" to "Keşfet:",
        "recommended" to "Önerilen",
        "calendar" to "Takvim:",
        "last videos" to "Son Bölümler:",
    )

    private val turkishTypes = mapOf(
        "movie" to "Filmler",
        "movies" to "Filmler",
        "series" to "Diziler",
        "show" to "Diziler",
        "shows" to "Diziler",
        "anime" to "Animeler",
        "tv" to "TV Kanalları",
        "channel" to "Kanallar",
        "channels" to "Kanallar",
        "other" to "Diğer",
    )

    /**
     * @param name the catalog name of the add-on, e.g. "Popular".
     * @param type the Stremio content type of the catalog, e.g. "series".
     * @return null when [language] has no translations, the caller then uses its own format.
     */
    fun localized(name: String, type: String, language: String): String? {
        if (language != "tr") return null
        val cleanName = name.trim()
        val typeLabel = turkishTypes[type.trim().lowercase()] ?: type.replaceFirstChar { it.uppercase() }
        if (cleanName.isEmpty()) return typeLabel

        val translated = turkishNames[cleanName.lowercase()]
        if (translated != null) return "$translated $typeLabel"

        // "Popular Movies", "Top Series": a known name that already ends with the type
        val words = cleanName.split(' ').filter { it.isNotBlank() }
        if (words.size >= 2 && turkishTypes.containsKey(words.last().lowercase())) {
            val head = turkishNames[words.dropLast(1).joinToString(" ").lowercase()]
            if (head != null) return head + " " + turkishTypes.getValue(words.last().lowercase())
            // An unknown name that mentions the type by itself is left as the add-on wrote it
            return cleanName
        }
        return "$cleanName - $typeLabel"
    }
}
