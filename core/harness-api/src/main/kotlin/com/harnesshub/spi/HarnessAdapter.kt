package com.harnesshub.spi

import com.harnesshub.model.EventId
import com.harnesshub.model.HarnessId
import com.harnesshub.model.InstanceId
import com.harnesshub.model.SessionId

/**
 * The Harness SPI (the heart of the app). One implementation per supported
 * harness, registered through Hilt multibindings; adding a harness never
 * touches the feature modules.
 */
interface HarnessAdapter {
    /** Immutable metadata shown in the Hub and used for routing. */
    val descriptor: AdapterDescriptor

    /** Capabilities the adapter actually implements; others are hidden in the UI. */
    val capabilities: Set<Capability>

    /** Probes run during discovery, in priority order. */
    val probes: List<DiscoveryProbe>

    /**
     * Immutable per-adapter capability accessors; a subset of [capabilities]
     * is non-null. The UI receives the adapter and reads only what it exposes.
     */
    val chat: ChatApi?
    val sessions: SessionsApi?
    val approvals: ApprovalsApi?

    /** Open the connection for [instance]. Must not block; stream results instead. */
    suspend fun connect(
        instance: InstanceId,
        credentials: HarnessCredentials,
    ): ConnectionHandle
}

data class AdapterDescriptor(
    val id: HarnessId,
    val displayName: String,
    val docsUrl: String? = null,
    val accentColor: Long? = null, // 0xAARRGGBB
)

/** Auth material resolved from the Keystore vault, never passed in the clear. */
sealed interface HarnessCredentials {
    data object None : HarnessCredentials
    data class BearerToken(val token: String) : HarnessCredentials
}
