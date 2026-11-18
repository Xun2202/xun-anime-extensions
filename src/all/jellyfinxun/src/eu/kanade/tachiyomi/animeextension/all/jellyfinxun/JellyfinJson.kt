package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException

internal object JellyfinJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("[A-Za-z0-9_-]{1,128}")

    fun parse(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject ?: throw IOException("NAS 返回格式不正确")
    } catch (_: Exception) {
        // JSON decoding errors can include the input, including a login token.
        throw IOException("NAS 返回格式不正确")
    }

    fun id(value: String): String {
        require(idPattern.matches(value)) { "NAS 条目 ID 不正确" }
        return value
    }

    fun itemId(path: String): String {
        val match = Regex("/Items/([A-Za-z0-9_-]{1,128})").matchEntire(path)
            ?: throw IOException("条目地址不正确，请从 Jellyfin Xun 重新添加")
        return id(match.groupValues[1])
    }

    fun items(root: JsonObject): List<JsonObject> = root.array("Items").mapNotNull { it as? JsonObject }
    fun pageHasMore(root: JsonObject, start: Int, count: Int): Boolean = start + count < root.int("TotalRecordCount")
}

internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
internal fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
