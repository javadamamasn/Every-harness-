package com.harnesshub.claude

import com.harnesshub.model.HarnessId
import com.harnesshub.model.InstanceId
import com.harnesshub.model.SessionId
import com.harnesshub.spi.AdapterDescriptor
import com.harnesshub.spi.Capability
import com.harnesshub.spi.ChatEvent
import com.harnesshub.spi.ConnectionHandle
import com.harnesshub.spi.DiscoveryProbe
import com.harnesshub.spi.HarnessAdapter
import com.harnesshub.spi.HarnessCredentials

/**
 * Claude Code adapter. One **turn** is one cli run through the Termux bridge:
 *
 *   node …/@anthropic-ai/claude-code/cli.js -p <prompt> --output-format
 *   stream-json --include-partial-messages --verbose [--resume <id>]
 *
 * Flags verified against claude 2.1.112 --help on this device (M5); the
 * stream-json line shapes captured live are decoded by [ClaudeStreamParser].
 * The call goes over the bridge's `exec.run` (needs the allow-exec flag in
 * Settings; arbitrary exec is denied otherwise — verified, NOT_ALLOWLISTED).
 * Live partial streaming rides the bridge's process subscription and lands
 * with M6 (honest partial; until then each turn returns at completion).
 */
class ClaudeAdapter(
    private val claudeCli: String,
    private val nodeBin: String = "node",
) : HarnessAdapter {

    override val descriptor = AdapterDescriptor(
        id = HarnessId("claude"),
        displayName = "Claude Code",
    )

    override val capabilities = setOf(
        Capability.CHAT,
        Capability.TOOL_CALLS,
        Capability.USAGE_STATS,
        Capability.SESSION_RESUME,
    )

    override val probes: List<DiscoveryProbe> = emptyList()
    override val chat: com.harnesshub.spi.ChatApi? get() = null // turn-bound; see send()
    override val sessions: com.harnesshub.spi.SessionsApi? get() = null
    override val approvals: com.harnesshub.spi.ApprovalsApi? get() = null

    /** `exec.run` sender as injected by the app (keeps this module transport-free). */
    fun interface BridgeExecutor {
        suspend fun exec(command: List<String>, timeoutMs: Long): ExecOutcome
    }

    data class ExecOutcome(val exitCode: Int, val stdout: String, val stderr: String)

    /** Builds the CLI argv for one turn (pure; unit-tested). */
    internal fun command(prompt: String, resumeSessionId: String? = null): List<String> = buildList {
        add(nodeBin)
        add(claudeCli)
        add("-p")
        add(prompt)
        add("--output-format")
        add("stream-json")
        add("--include-partial-messages")
        add("--verbose")
        if (resumeSessionId != null) {
            add("--resume")
            add(resumeSessionId)
        }
    }

    /** Full turns are produced via [turn]; WS-style connect is not a claude-code concept. */
    override suspend fun connect(
        instance: InstanceId,
        credentials: HarnessCredentials,
    ): ConnectionHandle = throw UnsupportedOperationException(
        "claude-code turns run through the bridge executor (see turn()); no live connection handle",
    )

    /**
     * One full turn: executes claude through the bridge and returns the
     * reduced event list for the whole run (streaming glue at M6).
     */
    suspend fun turn(
        executor: BridgeExecutor,
        prompt: String,
        resumeSessionId: String? = null,
        timeoutMs: Long = 60_000,
    ): TurnOutcome {
        val outcome = executor.exec(command(prompt, resumeSessionId), timeoutMs)
        val events = ClaudeStreamParser().parseAll(outcome.stdout.split("\n"))
        val sessionId = events.firstNotNullOfOrNull { e ->
            (e as? ChatEvent.SessionStarted)?.sessionId
        }
        return TurnOutcome(sessionId, events)
    }

    data class TurnOutcome(val sessionId: String?, val events: List<ChatEvent>)
}
