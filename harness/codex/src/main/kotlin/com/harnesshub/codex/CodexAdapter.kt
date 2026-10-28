package com.harnesshub.codex

import com.harnesshub.model.HarnessId
import com.harnesshub.model.InstanceId
import com.harnesshub.spi.AdapterDescriptor
import com.harnesshub.spi.Capability
import com.harnesshub.spi.ChatEvent
import com.harnesshub.spi.ConnectionHandle
import com.harnesshub.spi.DiscoveryProbe
import com.harnesshub.spi.HarnessAdapter
import com.harnesshub.spi.HarnessCredentials

/**
 * OpenAI Codex adapter — one turn = one `codex exec --json` run through the
 * bridge's `exec.run` (same shape as the Claude adapter).
 *
 * VERIFICATION STATUS (honest per Appendix F): the CLI is NOT installed on
 * this device yet, so the wire shapes come from the official schemas
 * (committed fixtures/, fetched 2026-10-28) — every event mapping in here is
 * snapshot-derived until a live turn is run on-device. Auth rides the
 * environment (OPENAI_API_KEY / auth.json), never argv, and is never logged.
 */
class CodexAdapter(
    private val codexBin: String = "codex",
) : HarnessAdapter {

    override val descriptor = AdapterDescriptor(
        id = HarnessId("codex"),
        displayName = "Codex",
    )

    override val capabilities = setOf(
        Capability.CHAT,
        Capability.TOOL_CALLS,
        Capability.USAGE_STATS,
        Capability.SESSION_RESUME, // via `codex exec resume <id>` (snapshot-derived)
        Capability.REASONING, // reasoning items in the stream (snapshot-derived)
    )

    override val probes: List<DiscoveryProbe> = emptyList()
    override val chat: com.harnesshub.spi.ChatApi? = null
    override val sessions: com.harnesshub.spi.SessionsApi? = null
    override val approvals: com.harnesshub.spi.ApprovalsApi? = null // approval flow at M8

    fun interface BridgeExecutor {
        suspend fun exec(command: List<String>, timeoutMs: Long): ExecOutcome
    }

    data class ExecOutcome(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * argv for one turn. `codex exec --json <prompt>` verified from the
     * official non-interactive docs; the resume variant (`exec resume <id>`)
     * also appears there but its exact `--json` ordering is unverified —
     * marked Partial until exercised on-device.
     */
    fun command(prompt: String, resumeThreadId: String? = null): List<String> = buildList {
        add(codexBin)
        add("exec")
        if (resumeThreadId != null) {
            add("resume")
            add(resumeThreadId)
        }
        add("--json")
        add(if (resumeThreadId != null) prompt else prompt)
    }

    override suspend fun connect(
        instance: InstanceId,
        credentials: HarnessCredentials,
    ): ConnectionHandle = throw UnsupportedOperationException(
        "codex turns run through the bridge executor (see turn()); no live connection handle",
    )

    /** One full turn; events reduced from the whole JSONL run. */
    suspend fun turn(
        executor: BridgeExecutor,
        prompt: String,
        resumeThreadId: String? = null,
        timeoutMs: Long = 60_000,
    ): TurnOutcome {
        val outcome = executor.exec(command(prompt, resumeThreadId), timeoutMs)
        val parser = CodexStreamParser()
        val events = outcome.stdout.split("\n").filter { it.isNotBlank() }
            .flatMap { runCatching { parser.mapLine(it) }.getOrDefault(emptyList()) }
        val threadId = events.firstNotNullOfOrNull { e ->
            (e as? ChatEvent.SessionStarted)?.sessionId
        }
        return TurnOutcome(threadId, events)
    }

    data class TurnOutcome(val threadId: String?, val events: List<ChatEvent>)
}
