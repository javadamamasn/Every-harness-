package com.harnesshub.transport

import kotlinx.serialization.Serializable

/** One probe row the bridge emits for discovery.scan (mirror of bridge.js). */
@Serializable
data class DiscoveryResult(
    val harnessId: String,
    val signals: List<String>,
    val confidence: Float,
)
