package com.harnesshub.discovery

import com.harnesshub.model.HarnessLocation
import com.harnesshub.model.InstanceStatus
import com.harnesshub.transport.DiscoveryResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoveryRankerTest {

    private fun row(harnessId: String, vararg signals: String, confidence: Float = 0.9f) =
        DiscoveryResult(harnessId, signals.toList(), confidence)

    @Test
    fun `binary probe outranks footprint probe and version is read from path`() {
        val ranked = DiscoveryRanker.rank(
            listOf(
                row("claude", "dir:~/.claude", confidence = 0.7f),
                row("claude", "binary:claude", "path:/usr/bin/claude", confidence = 1.0f),
            ),
        )
        assertEquals(1, ranked.size)
        assertEquals(1.0f, ranked[0].confidence)
        assertEquals("/usr/bin/claude", ranked[0].executablePath)
        assertEquals(InstanceStatus.INSTALLED, ranked[0].status)
    }

    @Test
    fun `port signal marks instance reachable and sorts first`() {
        val ranked = DiscoveryRanker.rank(
            listOf(
                row("opencode", "binary:opencode", confidence = 0.9f),
                row("hermes", "binary:hermes", "port:8080", confidence = 0.9f),
            ),
        )
        assertEquals("hermes", ranked[0].harnessId.value)
        assertEquals(InstanceStatus.REACHABLE, ranked[0].status)
        assertEquals(InstanceStatus.INSTALLED, ranked[1].status)
    }

    @Test
    fun `merge folds a fresh port sighting into an existing row`() {
        val before = DiscoveryRanker.rank(listOf(row("opencode", "binary:opencode")))
        val after = DiscoveryRanker.merge(before, listOf(row("opencode", "port:4096", confidence = 0.95f)))
        assertEquals(InstanceStatus.REACHABLE, after[0].status)
        assertEquals(0.95f, after[0].confidence)
    }

    @Test
    fun `proot dir signal relocates the instance`() {
        val ranked = DiscoveryRanker.rank(
            listOf(row("codex", "dir:proot:~/.codex")),
        )
        assertEquals(HarnessLocation.PROOT_DISTRO, ranked[0].location)
    }

    @Test
    fun `port fingerprint is credential-free and honest`() {
        assertEquals(
            com.harnesshub.model.HarnessId("openai-compatible"),
            DiscoveryRanker.portFingerprint("GET /v1/models", """{"data":[]}"""),
        )
        assertNull(DiscoveryRanker.portFingerprint("GET /admin", "secret"))
    }

    @Test
    fun `empty result list produces empty hub`() {
        assertTrue(DiscoveryRanker.rank(emptyList()).isEmpty())
    }

    @Test
    fun `expect harness finds exactly that row`() {
        val found = DiscoveryRanker.expectHarness(
            listOf(row("hermes", "binary:hermes"), row("node", "binary:node")),
            "hermes",
        )
        assertNotNull(found)
        assertEquals("hermes", found!!.harnessId.value)
        assertNull(DiscoveryRanker.expectHarness(listOf(row("node", "binary:node")), "openclaw"))
    }
}
