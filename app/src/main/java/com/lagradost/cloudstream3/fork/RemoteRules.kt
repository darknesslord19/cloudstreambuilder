package com.lagradost.cloudstream3.fork

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Reading of the small JSON documents this build is steered with from outside: the
 * announcement and the access list. Nothing here touches Android, so it can be tested on its own.
 */
object RemoteRules {
    private val mapper = ObjectMapper()

    /** Parses [text], also when the document was saved as a text (with quotes around it). */
    fun parse(text: String?): JsonNode? {
        if (text.isNullOrBlank()) return null
        return try {
            val node = mapper.readTree(text)
            if (node != null && node.isTextual) {
                val inner = node.asText().trim()
                if (inner.startsWith("{")) mapper.readTree(inner) else node
            } else {
                node
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun JsonNode?.string(vararg names: String): String? {
        if (this == null || !this.isObject) return null
        for (name in names) {
            val value = this.get(name) ?: continue
            if (value.isNull || value.isContainerNode) continue
            val text = value.asText()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun JsonNode?.flag(default: Boolean, vararg names: String): Boolean {
        if (this == null || !this.isObject) return default
        for (name in names) {
            val value = this.get(name) ?: continue
            return when {
                value.isBoolean -> value.asBoolean()
                value.isNumber -> value.asInt() != 0
                value.isTextual -> when (value.asText().trim().lowercase(Locale.ROOT)) {
                    "true", "1", "yes", "evet", "aktif", "on" -> true
                    "false", "0", "no", "hayir", "hayır", "pasif", "off" -> false
                    else -> default
                }
                else -> default
            }
        }
        return default
    }

    /** The first of [names] that holds a text in [json]. */
    fun text(json: JsonNode?, vararg names: String): String? = json.string(*names)

    /** The first of [names] that holds a yes or no in [json], [default] when none does. */
    fun flagOf(json: JsonNode?, default: Boolean, vararg names: String): Boolean = json.flag(default, *names)

    // ---------------------------------------------------------------- announcement

    class Announcement(
        /** Changes when there is something new to say, a shown id is not shown again */
        val id: String,
        val title: String?,
        val message: String,
        val buttonText: String?,
        val buttonUrl: String?,
        /** false: shown at every start instead of once */
        val once: Boolean,
        /** A picture shown above the message */
        val image: String? = null,
        /** The text of the closing button */
        val okText: String? = null,
        /** A colour of its own for the card ("#RRGGBB"), otherwise the colour of the theme */
        val color: String? = null,
        /** The closing button is not shown, only the link button */
        val hideOk: Boolean = false,
    )

    /**
     * { "show": true, "id": 1, "title": "...", "message": "...",
     *   "button_text": "Telegram", "button_url": "https://t.me/...", "once": true,
     *   "image": "https://.../afis.jpg" }
     * @return null if there is nothing to show.
     */
    fun announcement(json: JsonNode?): Announcement? {
        if (json == null || !json.isObject) return null
        if (!json.flag(true, "show", "enabled", "active")) return null
        val message = json.string("message", "text", "body") ?: return null
        val title = json.string("title")
        val url = json.string("button_url", "buttonUrl", "url", "link")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("tg://") }
        return Announcement(
            id = json.string("id", "version", "version_code") ?: (title.orEmpty() + "|" + message).hashCode().toString(),
            title = title,
            message = message,
            buttonText = json.string("button_text", "buttonText", "button"),
            buttonUrl = url,
            once = json.flag(true, "once"),
            image = json.string("image", "image_url", "imageUrl", "resim")
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            okText = json.string("ok_text", "okText", "close_text"),
            color = json.string("color", "renk")?.takeIf { Regex("^#?[0-9a-fA-F]{6}$").matches(it) },
            hideOk = !json.flag(true, "show_ok", "ok"),
        )
    }

    // ---------------------------------------------------------------- features

    /** The additions of this build that can be switched off, with their names in the documents. */
    enum class Feature(val key: String) {
        STREMIO("stremio"),
        NUVIO("nuvio"),
        SKYSTREAM("skystream"),
        THEMES("themes"),
        FONTS("fonts"),
        ICONS("icons"),
        MOTION("animated_posters"),
        CATEGORIES("categories"),
        REPOLIST("repolist"),
        CATEGORY_EFFECTS("category_effects"),
        AUTO_SUBS("auto_subtitles"),
        HOME_TWEAKS("home_tweaks"),
        TMDB_POSTERS("tmdb_posters"),
        THEME_PACK("theme_pack"),
        PROFILE_ICONS("profile_icons"),
    }

    /**
     * What is switched on: the general document, e.g. { "stremio": true, "nuvio": false, ... },
     * with the "features" object of this device's entry in the access list on top of it.
     * Anything not named in either stays on.
     */
    fun features(general: JsonNode?, user: JsonNode?): Map<Feature, Boolean> {
        val own = user?.takeIf { it.isObject }?.get("features")
        return Feature.values().associateWith { feature ->
            val base = general.flag(true, feature.key)
            own.flag(base, feature.key)
        }
    }

    // ---------------------------------------------------------------- access list

    /** The "settings" part of the access document. */
    class AccessSettings(val enabled: Boolean, val telegram: String?, val note: String?)

    fun accessSettings(json: JsonNode?): AccessSettings {
        return AccessSettings(
            enabled = json.flag(false, "enabled", "active", "aktif"),
            telegram = json.string("telegram", "contact")
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("tg://") },
            note = json.string("message", "note"),
        )
    }

    enum class Denial {
        /** The device is not in the list */
        NOT_REGISTERED,

        /** The entry has a password and none was entered yet */
        PASSWORD_NEEDED,
        WRONG_PASSWORD,

        /** The entry was switched off */
        SUSPENDED,
        EXPIRED,
    }

    /**
     * @property plan what the account is called on the account page, e.g. "Premium Üyelik".
     * @property startsAt when the access began, used to show how much of it is left.
     */
    class User(val name: String?, val expiresAt: Long?, val plan: String? = null, val startsAt: Long? = null)

    sealed class Decision {
        class Granted(val user: User) : Decision()
        class Denied(val reason: Denial, val user: User?) : Decision()
    }

    /**
     * One entry of "users", as { "name": "...", "password": "...", "expires": "2027-01-31", "active": true }.
     * Without a password the device alone is enough, without "expires" the access does not end.
     * "password_sha256" can be given instead of "password", it is the SHA-256 of the password in hex.
     */
    fun decide(user: JsonNode?, enteredPassword: String?, now: Long): Decision {
        if (user == null || !user.isObject) return Decision.Denied(Denial.NOT_REGISTERED, null)
        val expiresText = user.string("expires", "expire", "end", "bitis", "until")
        val expiresAt = endOfDay(expiresText)
        val info = User(
            user.string("name", "ad"), expiresAt,
            plan = user.string("plan", "uyelik", "membership"),
            startsAt = endOfDay(user.string("start", "starts", "baslangic"))?.let { it - 86_399_999L },
        )

        if (!user.flag(true, "active", "enabled", "aktif")) return Decision.Denied(Denial.SUSPENDED, info)
        // A date that cannot be read must not open the door for good
        if (expiresText != null && expiresAt == null) return Decision.Denied(Denial.EXPIRED, info)
        if (expiresAt != null && now > expiresAt) return Decision.Denied(Denial.EXPIRED, info)

        val password = user.string("password", "sifre", "key")
        val passwordHash = user.string("password_sha256", "sha256")
        if (password != null || passwordHash != null) {
            val entered = enteredPassword?.trim()
            if (entered.isNullOrEmpty()) return Decision.Denied(Denial.PASSWORD_NEEDED, info)
            val matches = (password != null && password == entered) ||
                    (passwordHash != null && passwordHash.equals(sha256(entered), ignoreCase = true))
            if (!matches) return Decision.Denied(Denial.WRONG_PASSWORD, info)
        }
        return Decision.Granted(info)
    }

    fun sha256(text: String): String {
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * The last moment of the day [date] names, in the time zone of the device.
     * Understands 2027-01-31, 31.01.2027 and 31/01/2027, and a number of milliseconds.
     */
    fun endOfDay(date: String?, zone: TimeZone = TimeZone.getDefault()): Long? {
        val text = date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { number ->
            // Seconds are turned into milliseconds
            return if (number < 100_000_000_000L) number * 1000L else number
        }
        val day = text.take(10)
        for (pattern in listOf("yyyy-MM-dd", "dd.MM.yyyy", "dd/MM/yyyy", "dd-MM-yyyy")) {
            // The patterns only differ in what they need at fixed places, so a wrong one fails cleanly
            if (!matchesShape(day, pattern)) continue
            try {
                val format = SimpleDateFormat(pattern, Locale.ROOT)
                format.isLenient = false
                format.timeZone = zone
                val parsed = format.parse(day) ?: continue
                val calendar = Calendar.getInstance(zone)
                calendar.time = parsed
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                calendar.set(Calendar.MILLISECOND, 999)
                return calendar.timeInMillis
            } catch (t: Throwable) {
                continue
            }
        }
        return null
    }

    private fun matchesShape(text: String, pattern: String): Boolean {
        if (text.length != pattern.length) return false
        for (index in pattern.indices) {
            val expected = pattern[index]
            val actual = text[index]
            if (expected.isLetter()) {
                if (!actual.isDigit()) return false
            } else if (expected != actual) {
                return false
            }
        }
        return true
    }

    /**
     * The part of the access that is left, from 1 (all of it) to 0. Without a start the
     * access is measured against a year.
     */
    fun leftFraction(startsAt: Long?, expiresAt: Long?, now: Long): Double {
        if (expiresAt == null) return 1.0
        if (expiresAt <= now) return 0.0
        val total = if (startsAt != null && startsAt < expiresAt) expiresAt - startsAt else 365L * 86_400_000L
        return ((expiresAt - now).toDouble() / total).coerceIn(0.0, 1.0)
    }

    /** How much time is left until [expiresAt], as whole years, days, hours and minutes. */
    class Remaining(val years: Int, val days: Int, val hours: Int, val minutes: Int)

    fun remaining(expiresAt: Long, now: Long): Remaining {
        val minutesLeft = ((expiresAt - now).coerceAtLeast(0L)) / 60_000L
        val daysLeft = minutesLeft / (24 * 60)
        return Remaining(
            years = (daysLeft / 365).toInt(),
            days = (daysLeft % 365).toInt(),
            hours = ((minutesLeft / 60) % 24).toInt(),
            minutes = (minutesLeft % 60).toInt(),
        )
    }
}
