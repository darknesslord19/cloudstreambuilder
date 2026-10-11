package com.lagradost.cloudstream3.addons

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.mapper

/*
 * Add-on formats are loosely typed JSON written by many different authors, where a field can be
 * missing, null, a number in one add-on and a string in the next. Reading them as a tree with
 * forgiving accessors is far more robust than mapping them onto strict data classes.
 */

internal fun parseJsonTree(text: String?): JsonNode? {
    if (text.isNullOrBlank()) return null
    return try {
        mapper.readTree(text)
    } catch (_: Throwable) {
        null
    }
}

private fun JsonNode?.child(key: String): JsonNode? {
    if (this == null || !this.isObject) return null
    val value = this.get(key) ?: return null
    return if (value.isNull || value.isMissingNode) null else value
}

/** First of [keys] that holds a non blank text or number. */
internal fun JsonNode?.text(vararg keys: String): String? {
    for (key in keys) {
        val value = child(key) ?: continue
        if (!value.isValueNode) continue
        val text = value.asText()?.trim()
        if (!text.isNullOrEmpty()) return text
    }
    return null
}

/** First of [keys] that holds a number, or a text that starts with one ("2019–2021" gives 2019). */
internal fun JsonNode?.double(vararg keys: String): Double? {
    for (key in keys) {
        val value = child(key) ?: continue
        if (value.isNumber) return value.asDouble()
        if (value.isTextual) {
            val match = Regex("""-?\d+(\.\d+)?""").find(value.asText()) ?: continue
            match.value.toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

internal fun JsonNode?.int(vararg keys: String): Int? {
    val value = double(*keys) ?: return null
    if (value.isNaN() || value.isInfinite()) return null
    if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) return null
    return value.toInt()
}

internal fun JsonNode?.bool(key: String): Boolean? {
    val value = child(key) ?: return null
    return when {
        value.isBoolean -> value.asBoolean()
        value.isTextual -> when (value.asText().trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }

        value.isNumber -> value.asInt() != 0
        else -> null
    }
}

internal fun JsonNode?.obj(key: String): JsonNode? {
    val value = child(key) ?: return null
    return if (value.isObject) value else null
}

/** The elements of the array at [key], empty if it is missing or not an array. */
internal fun JsonNode?.list(key: String): List<JsonNode> {
    val value = child(key) ?: return emptyList()
    if (!value.isArray) return emptyList()
    return value.filter { !it.isNull }
}

/** The texts at [key], which may be an array of texts or a single text. */
internal fun JsonNode?.strings(key: String): List<String> {
    val value = child(key) ?: return emptyList()
    return when {
        value.isArray -> value.filter { it.isValueNode && !it.isNull }
            .map { it.asText().trim() }
            .filter { it.isNotEmpty() }

        value.isValueNode -> listOf(value.asText().trim()).filter { it.isNotEmpty() }
        else -> emptyList()
    }
}

/** The object at [key] as a map of its text and number values. */
internal fun JsonNode?.stringMap(key: String): Map<String, String> {
    val value = obj(key) ?: return emptyMap()
    val result = LinkedHashMap<String, String>()
    val fields = value.fields()
    while (fields.hasNext()) {
        val (name, node) = fields.next()
        if (node != null && node.isValueNode && !node.isNull) {
            result[name] = node.asText()
        }
    }
    return result
}

internal fun Any.toJsonText(): String = mapper.writeValueAsString(this)
