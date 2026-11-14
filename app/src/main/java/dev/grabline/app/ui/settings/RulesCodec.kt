package dev.grabline.app.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Per-type routing rules: type to "phone" or a PC id. Absent type means "use the default device". */
object RulesCodec {
    val TYPES = listOf("video", "music", "torrent", "other")

    fun decode(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return try {
            Json.parseToJsonElement(json).jsonObject
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun encode(rules: Map<String, String>): String =
        if (rules.isEmpty()) "" else Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { rules.forEach { (k, v) -> put(k, v) } },
        )

    fun with(json: String, type: String, target: String?): String {
        val m = decode(json).toMutableMap()
        if (target == null) m.remove(type) else m[type] = target
        return encode(m)
    }
}
