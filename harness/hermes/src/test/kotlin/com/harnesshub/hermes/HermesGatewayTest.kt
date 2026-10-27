package com.harnesshub.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HermesGatewayTest {
    private val adapter = HermesAdapter()

    @Test
    fun `id and capabilities`() {
        assertEquals(com.harnesshub.model.HarnessId("hermes"), adapter.descriptor.id)
        assertTrue(adapter.capabilities.contains(Capability.STREAMING))
        assertTrue(adapter.capabilities.contains(Capability.APPROVALS))
        assertTrue(adapter.capabilities.contains(Capability.SESSION_RESUME))
    }
}
