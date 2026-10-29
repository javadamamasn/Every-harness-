package com.harnesshub.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Read/modify helpers for Claude Code `mcpServers` inside ~/.claude.json
 * (verified on-device shape: mcpServers[name] = {type:"stdio", command:"/path",
 * args:[...]}). Functions are pure text-in/text-out so the app only ever
 * rewrites the file through the bridge fs.write (which keeps a timestamped
 * backup). Malformed input throws IllegalArgumentException instead of
 * corrupting the real config.
 */
object McpJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val writer = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun mcpServers(root: JsonObject): JsonObject =
        (root["mcpServers"] as? JsonObject) ?: JsonObject(emptyMap())

    fun names(jsonText: String): List<String> = mcpServers(parse(jsonText)).keys.toList()

    /** "stdio :: /path/cmd" summaries — never secret material. */
    fun summaries(jsonText: String): List<Pair<String, String>> =
        mcpServers(parse(jsonText)).entries.map { (name, value) ->
            val obj = value.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: "stdio"
            val target = if (type == "stdio") {
                obj["command"]?.jsonPrimitive?.content ?: "?"
            } else {
                obj["url"]?.jsonPrimitive?.content ?: "?"
            }
            name to "$type :: $target"
        }

    fun addServer(jsonText: String, name: String, command: String, args: List<String>): String {
        require(name.isNotBlank()) { "server name required" }
        require(command.isNotBlank()) { "command required" }
        val root = parse(jsonText)
        val current = mcpServers(root).toMutableMap()
        require(!current.containsKey(name)) { "server '$name' already exists" }
        current[name] = buildJsonObject {
            put("type", "stdio")
            put("command", command.trim())
            if (args.isNotEmpty()) {
                putJsonArray("args") { args.forEach { add(JsonPrimitive(it)) } }
            }
        }
        return writer.encodeToString(JsonObject.serializer(), JsonObject(root.toMutableMap() + ("mcpServers" to JsonObject(current))))
    }

    fun removeServer(jsonText: String, name: String): String {
        val root = parse(jsonText)
        val current = mcpServers(root).toMutableMap()
        require(current.remove(name) != null) { "server '$name' not found" }
        return writer.encodeToString(JsonObject.serializer(), JsonObject(root.toMutableMap() + ("mcpServers" to JsonObject(current))))
    }
}
