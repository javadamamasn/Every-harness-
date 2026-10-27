package com.harnesshub.discovery

import com.harnesshub.model.HarnessId
import com.harnesshub.model.HarnessInstance
import com.harnesshub.model.HarnessLocation
import com.harnesshub.model.InstanceId
import com.harnesshub.model.InstanceStatus
import com.harnesshub.transport.DiscoveryResult

/**
 * Pure discovery ranking (build-spec section 6): combine the bridge's probe rows
 * (binary / footprint / port signals), dedupe one instance per harness, rank by
 * confidence, and derive status from the strongest signal. Unit tested.
 */
object DiscoveryRanker {

    /** Signal prefixes the bridge emits; weights mirror probe strength. */
    const val BINARY = "binary:"
    const val PATH = "path:"
    const val DIR = "dir:"
    const val PORT = "port:"

    fun signalWeightOf(signal: String): Float = when {
        signal.startsWith(BINARY) -> 1.0f
        signal.startsWith(PORT) -> 0.9f
        signal.startsWith(DIR) -> 0.7f
        else -> 0f // "path:" is informational, travels with its binary: signal
    }

    fun rank(results: List<DiscoveryResult>): List<HarnessInstance> {
        val rows = results.filter { it.signals.isNotEmpty() }
        return rows
            .groupBy { it.harnessId }
            .map { (harnessId, group) ->
                val signals = group.flatMap { it.signals }.distinct()
                val best = group.maxOf { it.confidence }
                HarnessInstance(
                    id = InstanceId(harnessId),
                    harnessId = HarnessId(harnessId),
                    displayName = harnessId,
                    executablePath = signals.firstOrNull { it.startsWith(PATH) }?.removePrefix(PATH),
                    location = if (signals.any { it.startsWith(DIR) && it.contains("proot") }) {
                        HarnessLocation.PROOT_DISTRO
                    } else {
                        HarnessLocation.TERMUX
                    },
                    status = if (signals.any { it.startsWith(PORT) }) InstanceStatus.REACHABLE else InstanceStatus.INSTALLED,
                    confidence = best.coerceIn(0f, 1f),
                )
            }
            .sortedWith(compareByDescending<HarnessInstance> { it.confidence }.thenBy { it.displayName })
    }

    /** Folds a second scan (new port or path probe) into an existing list. */
    fun merge(previous: List<HarnessInstance>, fresh: List<DiscoveryResult>): List<HarnessInstance> {
        if (fresh.none()) return previous
        val merged = previous.toMutableList()
        for (row in fresh) {
            val idx = merged.indexOfFirst { it.harnessId.value == row.harnessId }
            val candidate = rank(listOf(row)).firstOrNull() ?: continue
            if (idx >= 0) {
                val old = merged[idx]
                merged[idx] = old.copy(
                    status = candidate.status,
                    executablePath = old.executablePath ?: candidate.executablePath,
                    confidence = maxOf(old.confidence, candidate.confidence),
                )
            } else {
                merged.add(candidate)
            }
        }
        return merged.sortedWith(compareByDescending<HarnessInstance> { it.confidence }.thenBy { it.displayName.value })
    }

    /** Spec Appendix C: safe port fingerprint — no credentials, short routes. */
    fun portFingerprint(route: String, body: String?): HarnessId? = when {
        route.endsWith("/v1/models") && body != null && body.contains("\"data\"") -> HarnessId("openai-compatible")
        route.startsWith("/v1") -> HarnessId("openai-compatible")
        else -> null
    }

    fun expectHarness(results: List<DiscoveryResult>, harnessId: String): HarnessInstance? =
        rank(results.filter { it.harnessId == harnessId }).firstOrNull()
}
