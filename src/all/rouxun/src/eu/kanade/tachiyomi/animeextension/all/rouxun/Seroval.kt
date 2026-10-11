package eu.kanade.tachiyomi.animeextension.all.rouxun

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Minimal codec for the seroval JSON tree that TanStack Start uses for server-function
 * payloads and for responses flagged `x-tss-serialized: true`.
 *
 * Only the node kinds rou.video actually emits are handled; anything else decodes to null.
 * Encoding assigns reference ids in pre-order, which is all the server needs to rebuild the
 * value (ids only have to be unique).
 */
internal object Seroval {
    private const val NUMBER = 0
    private const val STRING = 1
    private const val CONSTANT = 2
    private const val REFERENCE = 4
    private const val DATE = 5
    private const val ARRAY = 9
    private const val OBJECT = 10
    private const val NULL_PROTOTYPE_OBJECT = 11

    private const val CONST_NULL = 0
    private const val CONST_TRUE = 2
    private const val CONST_FALSE = 3

    /** Wraps [data] the way `createServerFn().handler()` expects: `{ data: … }` as the root argument. */
    fun encodePayload(data: JsonObject?): String {
        var nextId = 0

        fun encode(value: JsonElement): JsonObject = when (value) {
            is JsonNull -> buildJsonObject {
                put("t", CONSTANT)
                put("s", CONST_NULL)
            }
            is JsonPrimitive -> when {
                value.isString -> buildJsonObject {
                    put("t", STRING)
                    put("s", value.content)
                }
                value.booleanOrNull != null -> buildJsonObject {
                    put("t", CONSTANT)
                    put("s", if (value.booleanOrNull == true) CONST_TRUE else CONST_FALSE)
                }
                else -> buildJsonObject {
                    put("t", NUMBER)
                    put("s", value)
                }
            }
            is JsonArray -> {
                val id = nextId++
                buildJsonObject {
                    put("t", ARRAY)
                    put("i", id)
                    put("a", JsonArray(value.map(::encode)))
                    put("o", 0)
                }
            }
            is JsonObject -> {
                val id = nextId++
                buildJsonObject {
                    put("t", OBJECT)
                    put("i", id)
                    put(
                        "p",
                        buildJsonObject {
                            put("k", JsonArray(value.keys.map(::JsonPrimitive)))
                            put("v", JsonArray(value.values.map(::encode)))
                        },
                    )
                    put("o", 0)
                }
            }
        }

        val root = buildJsonObject { if (data != null) put("data", data) }
        return buildJsonObject {
            put("t", encode(root))
            put("f", 0)
            put("m", JsonArray(emptyList()))
        }.toString()
    }

    fun decode(root: JsonElement): JsonElement {
        val refs = HashMap<Int, JsonElement>()

        fun decode(node: JsonElement): JsonElement {
            val obj = node as? JsonObject ?: return JsonNull
            val id = obj["i"]?.jsonPrimitive?.intOrNull
            return when (obj["t"]?.jsonPrimitive?.intOrNull) {
                NUMBER, STRING, DATE -> obj["s"] ?: JsonNull
                CONSTANT -> when (obj["s"]?.jsonPrimitive?.intOrNull) {
                    CONST_TRUE -> JsonPrimitive(true)
                    CONST_FALSE -> JsonPrimitive(false)
                    else -> JsonNull
                }
                REFERENCE -> id?.let(refs::get) ?: JsonNull
                ARRAY -> {
                    val items = (obj["a"] as? JsonArray)?.map(::decode).orEmpty()
                    JsonArray(items).also { if (id != null) refs[id] = it }
                }
                OBJECT, NULL_PROTOTYPE_OBJECT -> {
                    val props = obj["p"] as? JsonObject
                    val keys = (props?.get("k") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                    val values = (props?.get("v") as? JsonArray)?.map(::decode).orEmpty()
                    JsonObject(keys.zip(values).toMap()).also { if (id != null) refs[id] = it }
                }
                else -> JsonNull
            }
        }

        return decode(root)
    }
}
