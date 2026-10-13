package eu.kanade.tachiyomi.animeextension.all.rouxun

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerovalTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun listRequestRoundTrips() {
        val data = buildJsonObject {
            put("page", 1)
            put("order", "viewCount")
        }
        val decoded = roundTrip(data).jsonObject.getValue("data").jsonObject
        assertEquals(1, decoded.getValue("page").jsonPrimitive.int)
        assertEquals("viewCount", decoded.getValue("order").jsonPrimitive.content)
    }

    @Test
    fun searchRequestRoundTrips() {
        val data = buildJsonObject {
            put("q", "keyword")
            put("t", "")
            put("sort", "viewCount:desc")
            put("page", 2)
            put("tab", "series")
        }
        val decoded = roundTrip(data).jsonObject.getValue("data").jsonObject
        assertEquals("keyword", decoded.getValue("q").jsonPrimitive.content)
        assertEquals("", decoded.getValue("t").jsonPrimitive.content)
        assertEquals("viewCount:desc", decoded.getValue("sort").jsonPrimitive.content)
        assertEquals(2, decoded.getValue("page").jsonPrimitive.int)
        assertEquals("series", decoded.getValue("tab").jsonPrimitive.content)
    }

    @Test
    fun tagAndSeriesRequestsRoundTrip() {
        val tag = buildJsonObject {
            put("id", "自拍流出")
            put("page", 3)
            put("order", "createdAt")
        }
        val tagDecoded = roundTrip(tag).jsonObject.getValue("data").jsonObject
        assertEquals("自拍流出", tagDecoded.getValue("id").jsonPrimitive.content)
        assertEquals(3, tagDecoded.getValue("page").jsonPrimitive.int)
        assertEquals("createdAt", tagDecoded.getValue("order").jsonPrimitive.content)

        val series = buildJsonObject {
            put("f", "all")
            put("s", "updated")
            put("page", 1)
            put("tag", "")
        }
        val seriesDecoded = roundTrip(series).jsonObject.getValue("data").jsonObject
        assertEquals("all", seriesDecoded.getValue("f").jsonPrimitive.content)
        assertEquals("updated", seriesDecoded.getValue("s").jsonPrimitive.content)
        assertEquals("", seriesDecoded.getValue("tag").jsonPrimitive.content)
    }

    @Test
    fun idRequestAndNullPayloadRoundTrip() {
        val id = buildJsonObject { put("id", "clxxxxxxxxxxxxxxxxxxxxx") }
        val decoded = roundTrip(id).jsonObject.getValue("data").jsonObject
        assertEquals("clxxxxxxxxxxxxxxxxxxxxx", decoded.getValue("id").jsonPrimitive.content)

        val empty = roundTrip(null)
        assertTrue(empty is JsonObject)
        assertEquals(0, empty.jsonObject.size)
    }

    @Test
    fun payloadWrapperCarriesNoReferences() {
        val wrapped = json.parseToJsonElement(
            Seroval.encodePayload(buildJsonObject { put("page", 1) }),
        ).jsonObject
        assertEquals(0, wrapped.getValue("f").jsonPrimitive.int)
        assertEquals(0, wrapped.getValue("m").jsonArray.size)
        assertTrue(wrapped.containsKey("t"))
    }

    private fun roundTrip(data: JsonObject?): JsonElement {
        val wrapped = json.parseToJsonElement(Seroval.encodePayload(data)).jsonObject
        return Seroval.decode(wrapped.getValue("t"))
    }
}
