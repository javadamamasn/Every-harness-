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
- [ ] M4: Hermes adapter + full chat (JSON-RPC/WS, parity with Hermes Pocket).
- [ ] M5: Claude Code adapter via bridge (stream-json, approvals, sessions).
- [ ] M6: sessions list, projects, task desk, notifications, foreground service.
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
