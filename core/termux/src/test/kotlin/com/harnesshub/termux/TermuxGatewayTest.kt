package com.harnesshub.termux

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TermuxGatewayTest {
    private val ready = TermuxGateway.TermuxState(
        termuxInstalled = true,
        runCommandPermission = true,
        externalAppsEnabled = true,
        bridgeReachable = true,
    )

    @Test
    fun `all steps done in ready state`() {
        val steps = TermuxGateway.computeSteps(ready)
        assertEquals(TermuxGateway.STEP_NEEDED, steps.size)
        assertTrue(steps.all { it.status == TermuxGateway.Status.DONE })
    }

    @Test
    fun `uninstalled termux marks first steps as action`() {
        val steps = TermuxGateway.computeSteps(TermuxGateway.TermuxState(termuxInstalled = false))
        assertEquals(TermuxGateway.Status.ACTION, steps[0].status)
        assertEquals(TermuxGateway.Status.PENDING, steps[2].status)
    }

    @Test
    fun `permission missing blocks external apps step`() {
        val state = ready.copy(termuxInstalled = true, runCommandPermission = false)
        val steps = TermuxGateway.computeSteps(state)
        assertEquals(TermuxGateway.Status.ACTION, steps[1].status)
    }

    @Test
    fun `unknown external apps flag stays pending`() {
        val state = ready.copy(externalAppsEnabled = null)
        val steps = TermuxGateway.computeSteps(state)
        assertEquals(TermuxGateway.Status.PENDING, steps[2].status)
        assertEquals(TermuxGateway.Status.PENDING, steps[3].status)
    }

    @Test
    fun `bridge down shows actionable start step with command`() {
        val state = ready.copy(bridgeReachable = false)
        val steps = TermuxGateway.computeSteps(state)
        assertEquals(TermuxGateway.Status.ACTION, steps[4].status)
        assertEquals("$HOME/bin/harness-hub-bridge-start", steps[4].command)
    }

    @Test
    fun `run command extras carry exact documented keys`() {
        val extras = TermuxGateway.runCommandExtras(listOf("~/.harnesshub/install-bridge.sh"))
        assertEquals("/data/data/com.termux/files/usr/bin/bash", extras["com.termux.RUN_COMMAND_PATH"])
        assertEquals("~/.harnesshub/install-bridge.sh", extras["com.termux.RUN_COMMAND_ARGUMENTS"])
        assertEquals("true", extras["com.termux.RUN_COMMAND_BACKGROUND"])
    }

    @Test
    fun `on failure list names the blockers`() {
        val blockers = TermuxGateway.onFailure(TermuxGateway.TermuxState(termuxInstalled = false, bridgeReachable = false))
        assertEquals(listOf("termux_missing", "bridge_down"), blockers)
    }
}
