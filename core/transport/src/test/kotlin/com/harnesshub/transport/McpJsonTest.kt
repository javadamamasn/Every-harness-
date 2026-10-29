package com.harnesshub.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** McpJson unit tests (pure text transforms, no Android runtime). */
class McpJsonTest {

    private val base = """
        {
          "installMethod": "global",
          "mcpServers": {
            "playwright": {
              "type": "stdio",
              "command": "/data/bin/playwright.sh"
            }
          },
          "tipsHistoryCount": 3
        }
    """.trimIndent()

    @Test
    fun `names reads verified on-device shape`() {
        assertEquals(listOf("playwright"), McpJson.names(base))
    }

    @Test
    fun `summaries expose type and command only`() {
        val rows = McpJson.summaries(base)
        assertEquals(1, rows.size)
        assertEquals("playwright", rows[0].first)
        assertTrue(rows[0].second.contains("stdio") && rows[0].second.contains("/data/bin/playwright.sh"))
    }

    @Test
    fun `add then remove round-trip preserving sibling keys`() {
        val added = McpJson.addServer(base, "hh-probe", "/bin/false", listOf("-x", "1"))
        assertEquals(setOf("playwright", "hh-probe"), McpJson.names(added).toSet())
        // sibling keys survive untouched
        assertTrue(added.contains("\"installMethod\""))
        assertTrue(added.contains("hh-probe"))
        // remove restores the original set
        val removed = McpJson.removeServer(added, "hh-probe")
        assertEquals(listOf("playwright"), McpJson.names(removed))
    }

    @Test
    fun `duplicate add and missing remove are refused`() {
        assertThrows<IllegalArgumentException> { McpJson.addServer(base, "playwright", "/bin/false", emptyList()) }
        assertThrows<IllegalArgumentException> { McpJson.removeServer(base, "nope") }
    }

    @Test
    fun `malformed json is refused`() {
        assertThrows<Exception> { McpJson.names("not json{") }
    }

    @Test
    fun `mcpServers absent parses empty and add creates it`() {
        assertEquals(0, McpJson.names("""{"a":1}""").size)
        assertEquals(1, McpJson.names(McpJson.addServer("""{"a":1}""", "s", "/c", emptyList())).size)
    }
}
