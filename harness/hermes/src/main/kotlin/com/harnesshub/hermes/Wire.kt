package com.harnesshub.hermes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Wire types of the Hermes gateway: JSON-RPC 2.0 framing plus the
 * `event` envelope. Field names mirror the live server (verified M4).
 */
object SharedWireJson {
    val default: Json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    fun string(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content

    fun stringFor(frame: GatewayWireFrame, key: String): String? = string(frame.payload, key)

    fun long(frame: GatewayWireFrame, key: String): Long? =
        (frame.payload[key] as? JsonPrimitive)?.content?.toLongOrNull()

    fun double(frame: GatewayWireFrame, key: String): Double? =
        (frame.payload[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    fun bool(frame: GatewayWireFrame, key: String): Boolean? =
        (frame.payload[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    fun strArr(frame: GatewayWireFrame, key: String): List<String> =
        (frame.payload[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

    fun buildRequest(id: Long, method: String, params: JsonObject?): String =
        default.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            },
        )
}

/** Decoded wire event for one `method:"event"` frame: (type, payloadJsonObject). */
data class GatewayWireFrame(val type: String, val payload: JsonObject)

/** The whole ND-JSON frame as it came from the wire (minimal typed shape). */
data class GatewayWireEnvelope(
    val id: Long? = null,
    val method: String? = null,
    val params: JsonObject? = null,
    val result: JsonObject? = null,
    val error: JsonObject? = null,
    val raw: JsonElement? = null,
)

/** Manual but exact frame decoder (newline-delimited JSON-RPC 2.0). */
object WireFrameCodec {
    fun parse(line: String): GatewayWireEnvelope? {
        val element = try {
            SharedWireJson.default.parseToJsonElement(line)
        } catch (_: Exception) {
            return null
        }
        val obj = element as? JsonObject ?: return null
        return GatewayWireEnvelope(
            id = (obj["id"] as? JsonPrimitive)?.contentOrNullNumber(),
            method = (obj["method"] as? JsonPrimitive)?.content,
            params = obj["params"] as? JsonObject,
            result = obj["result"] as? JsonObject,
            error = obj["error"] as? JsonObject,
            raw = element,
        )
    }

    private fun JsonPrimitive.contentOrNullNumber(): Long? = content.toLongOrNull()
}
