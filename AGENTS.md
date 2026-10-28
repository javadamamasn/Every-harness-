# AGENTS.md

Harness Hub — a native Android (Kotlin/Compose) control surface for every AI agent
harness installed on the same phone (Termux-first: Hermes Agent, Claude Code,
OpenCode, OpenClaw, Codex, Gemini CLI, generic ACP/OpenAI-compatible, PTY fallback).

## At a glance

- Language: Kotlin 2.x, Compose + Material 3, JVM targets 17.
- Modules: `app` (UI shell), `core:model` (pure domain), `core:harness-api`
  (Harness SPI: Capability enum, ChatEvent, ChatReducer) — harness adapter
  modules arrive with the adapters milestones.
- Build: `./gradlew :app:assembleDebug`; tests: `./gradlew test`.
- i18n: `app/src/main/res/values` (en) and `values-fa` (fa/RTL); no hard-coded UI strings.
- CI: `.github/workflows/ci.yml` — unit tests + debug APK artifact on every push to main.

## Module map

| Module | Purpose |
| --- | --- |
| `app` | MainActivity, navigation shell, feature screens (hub/chat/sessions/skills/models/settings) |
| `core:model` | Dependency-free domain types (Session, Message, ContentBlock, HarnessInstance…) |
| `core:harness-api` | Harness SPI: Capability, AdapterDescriptor, ChatEvent, ChatReducer (+ tests) |

 Planned (see docs/DECISIONS.md): core:transport, core:termux, core:discovery,
 core:data, core:designsystem, feature:*, harness:* adapters, `bridge/` Node.js daemon.

## Conventions

- Conventional Commits, one small focused commit per change.
- Rules for chat state live ONLY in `ChatReducer`; adapters emit only `ChatEvent`.
- Unsupported capabilities are hidden, never faked; verify flags/protocols from
  real `--help` output and live probing before coding them (docs/DECISIONS.md).
- No TODOs in shipped paths; docs/DECISIONS.md is append-only.

## Milestone checklist (build-spec sections 14–16)

- [x] M0 (partial): Hermes-Pocket studied; on-device recon done (hermes / claude /
      opencode / node present; openclaw, codex, gemini absent; no proot distros).
- [x] M1 (foundation): modules, Compose shell, nav, theme, en/fa strings, CI, SPI heart.
- [x] M2: Termux onboarding wizard (7 steps, live Termux/RUN_COMMAND detection),
  Node bridge daemon with full Appendix-A method set, WebSocket+HTTP transport,
  auth/throttle/path-confinement, install script, 29-case conformance suite (green
  locally; also run in CI), networkSecurityConfig for loopback cleartext.
  Partials, honest: process stdout/stderr stream to ring buffer + subscribers is
  poll-based; PTY method refuses with NOT_ALLOWLISTED (needs the native-pty
  milestone); external-apps flag detection is manual until M3.
- [x] M3: discovery engine (transport + ranker wired over the local bridge;
      Hub cards with status/location/confidence, bridge setup sheet, rescan) —
      partials: manifest import and QR/manual add deferred to M8; the Hub
      "start/stop/restart" actions wait for the foreground service (M6).
- [x] M4: Hermes adapter + full chat (direct tui_gateway WS JSON-RPC client;
      wire-event mapper into the shared ChatReducer; chat screen with
      streaming text, thinking lines, tool cards, usage, recovery states).
      Partials, honest: markdown rendering of assistant text stays plain-text
      until the polish milestone; settings-token sits in DataStore until the
      M6 Keystore vault; app-side approvals UI arrives with M5 (events land as
      ApprovalRequested chat events meanwhile); session browser is M5/M6.
- [x] M5: Claude Code adapter via the bridge.
  - `harness:claude` — ClaudeStreamParser (claude 2.1.112 stream-json shapes
    verified live: system/init → SessionStarted, assistant text/thinking/tool_use
    → deltas+tool calls, user tool_result → ToolResult, wstream_event partial
    deltas, result → UsageUpdate(+cost)/ErrorEvent(is_error)), command builder
    (`-p … --output-format stream-json --include-partial-messages --verbose
    [--resume id]` — flags verified from cli --help), BridgeExecutor port +
    turn() helper. Unit tests land with this commit.
  - Decision (user): the Node bridge stays JS — no Kotlin port; Kotlin-only
    surface is app-side (recorded in docs/DECISIONS.md).
  - Partials, honest: claude turns reach the executor as one-shot exec.run —
    live token streaming waits for the bridge process-subscription (M6);
    interactive approvals ask inside the CLI itself (no app-side approval UI
    yet); app wiring of the claude engine into ChatScreen waits for the M6
    engine work; exec.run stays denied until the Settings gate sets
    ~/.harnesshub/allow-exec (biometric, verified NOT_ALLOWLISTED otherwise).
- [~] M6 (in flight): resource-aware lifecycle (section 19) + Hermes zero-setup.
      Bridge now ships verified: system.resources (meminfo + per-harness RSS),
      harness.list/register/stop (SIGTERM tree -> short wait -> SIGKILL),
      bridge.resourceMode (exclusive|smart|manual persisted),
      hermes.setup (zero-setup: finds running `hermes dashboard`, reads its port
      from cmdline and HERMES_DASHBOARD_SESSION_TOKEN from its /proc env;
      starts it when absent) and bridge.execFlag (allow-exec gate). Conformance
      suite grown to 33 green cases (run on device + CI).
      M6b (app side, done): Keystore AES-GCM vault (secrets at rest), Hub card
      for Hermes (zero-setup verified), resource summary + mode switch +
      exec.run gate with confirmation dialog, chat engine picker (Hermes /
      Claude one-shot turn over bridge exec.run, live probed), harness.list
      now carries mode + execFlag, conformance 33/33. Remaining M6/M7:
      foreground service, sessions/project/task screens, Codex adapter
      (fixtures only — CLI not installed on device yet).

      OpenCode Tier-1 DIRECT adapter done (section 20): harness/opencode with
      OpenCodeClient (HTTP+SSE, OpenAPI-derived, no bridge in default path;
      Basic auth when OPENCODE_SERVER_PASSWORD set), OpenCodeEventMapper
      (part-delta diffing, tool parts, step-finish usage/cost, failure errors),
      OpenCodeAdapter implementing HarnessAdapter; 5 MockWebServer contract
      tests green; wired into the chat engine picker (third tab +
      port/password dialog; password sealed via Vault). Verification basis:
      live probes of opencode serve v1.19.3 on-device + committed OpenAPI
      snapshot; permission-reply endpoint remains snapshot-derived (Partial
      until exercised live).
- [ ] M7: skills, plugins, MCP, tools/permissions, providers/models, cron, memory, env.
- [ ] M8: OpenClaw, Codex, Gemini CLI, OpenCode, generic OpenAI-compatible, ACP, PTY.
- [ ] M9: files, terminal, logs/diagnostics, usage.
- [ ] M10: accessibility, perf, screenshot tests, docs, release build.

## Next steps

1. M2: implement the bridge daemon (Node, loopback-only, Appendix A protocol) and
   the Termux onboarding wizard; contract-test the bridge against a fake.
2. M3: port the discovery engine over the bridge probes (binary/footprint/port).
3. M4: extract the transport from Hermes-Pocket's `GatewayClient` ideas into
   `core:transport`, then implement `harness:hermes` and the chat reducer UI.
