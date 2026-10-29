package com.harnesshub.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** PluginJson unit tests (pure text transforms, no Android runtime). */
class PluginJsonTest {

    private val base = """
        {
          "model": "opus[1m]",
          "env": { "ANTHROPIC_BASE_URL": "https://example.invalid" },
          "enabledPlugins": { "superpowers@superpowers-dev": false, "claude-mem@thedotmack": true }
        }
    """.trimIndent()

    @Test
    fun `pluginEnabled reads verified shape`() {
        assertEquals(false, PluginJson.pluginEnabled(base, "superpowers@superpowers-dev"))
        assertEquals(true, PluginJson.pluginEnabled(base, "claude-mem@thedotmack"))
        assertNull(PluginJson.pluginEnabled(base, "absent@x"))
        assertNull(PluginJson.pluginEnabled("{}" + " ", "x"))
    }

    @Test
    fun `setPluginEnabled keeps env and model untouched`() {
        val next = PluginJson.setPluginEnabled(base, "superpowers@superpowers-dev", true)
        assertTrue(next.contains("ANTHROPIC_BASE_URL"))
        assertTrue(next.contains("opus[1m]"))
        assertEquals(true, PluginJson.pluginEnabled(next, "superpowers@superpowers-dev"))
        val back = PluginJson.setPluginEnabled(next, "superpowers@superpowers-dev", false)
        assertEquals(false, PluginJson.pluginEnabled(back, "superpowers@superpowers-dev"))
    }

    @Test
    fun `rules add remove default mode`() {
        val none = PluginJson.rules("{\"a\":1}", "allow")
        assertEquals(0, none.size)
        val added = PluginJson.addRule(base, "allow", "Bash(hh-probe *)")
        assertEquals(listOf("Bash(hh-probe *)"), PluginJson.rules(added, "allow"))
        assertEquals(0, PluginJson.rules(added, "deny").size)
        assertEquals(true, added.contains("ANTHROPIC_BASE_URL"))
        val removed = PluginJson.removeRule(added, "allow", "Bash(hh-probe *)")
        assertEquals(0, PluginJson.rules(removed, "allow").size)
        val mode = PluginJson.setDefaultMode(removed, "acceptEdits")
        assertEquals("acceptEdits", PluginJson.defaultMode(mode))
        assertFalse(PluginJson.rules(mode, "allow").contains("Bash(hh-probe *)"))
    }

    @Test
    fun `refusals`() {
        // duplicate rule is refused
        val added = PluginJson.addRule(base, "deny", "Bash(cola *)")
        assertThrows<IllegalArgumentException> { PluginJson.addRule(added, "deny", "Bash(cola *)") }
        assertThrows<IllegalArgumentException> { PluginJson.removeRule(base, "deny", "nope") }
        assertThrows<IllegalArgumentException> { PluginJson.setDefaultMode(base, "yolo") }
        assertThrows<IllegalArgumentException> { PluginJson.setPluginEnabled(base, "   ", true) }
    }
}
