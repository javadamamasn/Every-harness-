package com.harnesshub.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read/modify helpers for Claude Code ~/.claude/settings.json (verified
 * on-device shape 2026-10-29: top-level env/model/enabledPlugins/...;
 * enabledPlugins[pluginId] = boolean; optional permissions{defaultMode,
 * allow[], deny[]}). Pure text-in/text-out so the app only rewrites through
 * bridge fs.write (timestamped backup). Malformed input throws
 * IllegalArgumentException instead of corrupting the file.
 */
object PluginJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val writer = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun strings(root: JsonObject, list: String): List<String> =
        ((root[list] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }).orEmpty()

    fun pluginEnabled(jsonText: String, pluginId: String): Boolean? =
        ((parse(jsonText)["enabledPlugins"] as? JsonObject)?.get(pluginId) as? JsonPrimitive)
            ?.content?.let { it == "true" }

    /** Writes an explicit boolean — absent means enabled by Claude, shown as true. */
    fun setPluginEnabled(jsonText: String, pluginId: String, on: Boolean): String {
        require(pluginId.isNotBlank()) { "plugin id required" }
        val root = parse(jsonText).toMutableMap()
        val current = (root["enabledPlugins"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        current[pluginId] = JsonPrimitive(on)
        root["enabledPlugins"] = JsonObject(current)
        return writer.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

    fun defaultMode(jsonText: String): String? =
        ((parse(jsonText)["permissions"] as? JsonObject)?.get("defaultMode") as? JsonPrimitive)?.content

    fun rules(jsonText: String, list: String): List<String> {
        require(list == "allow" || list == "deny") { "list must be allow|deny" }
        val perm = (parse(jsonText)["permissions"] as? JsonObject)
            ?: return emptyList()
        return strings(perm, list)
    }

    fun addRule(jsonText: String, list: String, rule: String): String {
        require(list == "allow" || list == "deny") { "list must be allow|deny" }
        require(rule.isNotBlank()) { "rule required" }
        val root = parse(jsonText).toMutableMap()
        val perm = (root["permissions"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val rules = strings(perm, list).toMutableList()
        require(!rules.contains(rule)) { "rule already present in $list" }
        rules.add(rule.trim())
        perm[list] = JsonArray(rules.map { JsonPrimitive(it) })
        root["permissions"] = JsonObject(perm)
        return writer.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

    fun removeRule(jsonText: String, list: String, rule: String): String {
        require(list == "allow" || list == "deny") { "list must be allow|deny" }
        val root = parse(jsonText).toMutableMap()
        val perm = (root["permissions"] as? JsonObject)?.toMutableMap()
            ?: throw IllegalArgumentException("no permissions block")
        val rules = strings(perm, list)
        require(rules.contains(rule)) { "rule not found in $list" }
        val next = rules - rule
        if (next.isEmpty()) perm.remove(list) else perm[list] = JsonArray(next.map { JsonPrimitive(it) })
        root["permissions"] = JsonObject(perm)
        return writer.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

    /** Real Claude values: default | acceptEdits | plan | bypassPermissions. */
    fun setDefaultMode(jsonText: String, mode: String): String {
        require(mode in setOf("default", "acceptEdits", "plan", "bypassPermissions")) {
            "mode must be default|acceptEdits|plan|bypassPermissions"
        }
        val root = parse(jsonText).toMutableMap()
        val perm = (root["permissions"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        perm["defaultMode"] = JsonPrimitive(mode)
        root["permissions"] = JsonObject(perm)
        return writer.encodeToString(JsonObject.serializer(), JsonObject(root))
    }
}
