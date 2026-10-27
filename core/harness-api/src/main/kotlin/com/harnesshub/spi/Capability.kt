package com.harnesshub.spi

/**
 * What a harness can do, expressed so the UI may render a section only when the
 * active adapter declares it. Unsupported features are hidden, never faked.
 */
enum class Capability {
    CHAT,
    STREAMING,
    REASONING,
    REASONING_EFFORT,
    TOOL_CALLS,
    SUB_AGENTS,
    SESSIONS,
    SESSION_BRANCH,
    SESSION_RESUME,
    ATTACHMENTS,
    SLASH_COMMANDS,
    STEERING,
    SKILLS,
    SKILL_INSTALL,
    PLUGINS,
    MCP_SERVERS,
    HOOKS,
    PROVIDERS,
    MODEL_SWITCH,
    MODEL_FALLBACK,
    APPROVALS,
    CRON,
    MEMORY,
    IDENTITY_FILES,
    ENV_VARS,
    PROJECTS,
    FILES,
    TERMINAL,
    LOGS,
    USAGE_STATS,
}
