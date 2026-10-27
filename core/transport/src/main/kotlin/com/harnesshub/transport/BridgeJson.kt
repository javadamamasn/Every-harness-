package com.harnesshub.transport

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared JSON + tiny helpers used by the bridge client and its tests. */
object BridgeJson {
    val default: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    fun obj(vararg pairs: Pair<String, Any?>): JsonElement = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is String -> JsonPrimitive(v)
                null -> JsonNull
                else -> throw IllegalArgumentException("unsupported")
            }
        },
    )

    fun string(element: JsonElement?, key: String): String? =
        (element as? JsonObject)?.get(key)?.let { it as? JsonPrimitive }?.content

    fun int(element: JsonElement?, key: String): Int? =
        (element as? JsonObject)?.get(key)?.let { it as? JsonPrimitive }?.content?.toIntOrNull()

    fun long(element: JsonElement?, key: String): Long? =
        (element as? JsonObject)?.get(key)?.let { it as? JsonPrimitive }?.content?.toLongOrNull()

    fun array(element: JsonElement?, key: String): JsonArray? =
        (element as? JsonObject)?.get(key)?.let { it as? JsonArray }

    fun decodeResults(element: JsonElement?): List<DiscoveryResult> =
        (element as? JsonObject)?.get("results")?.let { raw ->
            default.decodeFromJsonElement(ListSerializer(DiscoveryResult.serializer()), raw)
        } ?: emptyList()
}
