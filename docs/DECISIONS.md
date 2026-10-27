# DECISIONS

Append-only log. Format: date — decision — alternatives — reason — verified vs assumed.

- 2026-10-27 — **Verified device recon (M0).** `command -v` + filesystem probing on the
  target phone: `hermes` (/data/data/com.termux/files/usr/lib/hermes-agent/bin/hermes),
  `claude`, `opencode`, `node`+`npm` present; `openclaw`, `codex`, `gemini`, `aider`,
  `goose` absent; no proot-distro rootfs installed. Alternatives: assume all harnesses
  installed — rejected. Verified: live probing. Assumed: none.

- 2026-10-27 — **Repos starter toolchain pinned to Hermes-Pocket's proven stack**
  (AGP 8.13.2, Kotlin 2.3.21, Compose BOM 2026.06.01, Gradle wrapper 8.14.3 copied).
  Alternatives: newest AGP — rejected, Hermes-Pocket's combo is known-CI-green.
  Verified: Hermes-Pocket builds this stack in its own CI; ours reproduces it. Assumed: none.

- 2026-10-27 — **Start with :app + core:model + core:harness-api (JVM).** Android-library
  modules and Hilt multibindings arrive when the first real adapter is added (M4).
  Alternatives: full 20-module skeleton day one — rejected, unverifiable locally and
  slows the first green build. Verified: modules compile and run unit tests in CI.
  Assumed: module extraction stays cheap (DECISIONS will log it if it does not).

- 2026-10-27 — **Hermes-Pocket reference study (M0).** Read repo at
  github.com/traveler3022/Hermes-Pocket (MIT): 472 Kotlin files under
  `app/src/main/java/com/hermes/android` with data repositories (DashboardApi,
  SessionRepository, ProfilesRepository…) and a Go-based `tsbridge` alternative we do
  not reuse (our bridge is Node.js per spec). Alternative: reuse their tsbridge —
  rejected (GO toolchain dependency, protocol is Hermes-specific). Verified: repo read.
  Assumed: their JSON-RPC/WS gateway protocol details to be re-verified against a live
  Hermes at M4 before adapter coding.

- 2026-10-27 — **CI = unit tests + assembleDebug only for M1.** detekt/ktlint/screenshot
  tests join at polish milestones (M10) to keep the first builds fast and stable.
  Verified: run 40551832176 green (test ✓, assembleDebug ✓, APK artifact uploaded).

- 2026-10-27 — **compileSdk 36 with targetSdk 35.** The pinned androidx stack
  (core-ktx 1.18.0, navigation 2.9.8, activity 1.13.0) refuses to compile against
  android-35 AAR-metadata (verified by the :app:checkDebugAarMetadata failure). The
  build spec asked for compileSdk 35 + targetSdk 35; compileSdk was raised to 36
  because targetSdk (runtime behavior, what the spec actually cares about) is
  unchanged. Alternatives: downgrade androidx — rejected (lied about versions).
  Verified: AAR metadata check passes.

- 2026-10-27 — **M3 landing.** `core:transport` (OkHttp WS client, hello-auth,
  req/res correlation, typed discovery.scan) and `core:discovery`
  (DiscoveryRanker, pure) power the live Hub via a connect-scan-close session —
  the pinned foreground connection lands with M6. The bridge token sits in
  DataStore until M6's Keystore vault; it is never logged and only embedded
  inside the WebSocket hello frame.  Verified: CI run 40563515659 green (transport contract tests vs a wire-level fake bridge; discovery ranker
  tests; full APK assemble); node-conformance run green. MockWebServer shutdown noise is tolerated in the
  after-hook (documented quirk).

- 2026-10-27 — **M4: Hermes direct gateway (verified against the live server).** The
  Hermes adapter speaks to the dashboard's own tui_gateway WebSocket server
  (`app/tui_gateway/ws.py` cross-checked) — `ws://127.0.0.1:9119/api/ws?token=…`,
  ND-JSON JSON-RPC 2.0, an immediate `gateway.ready` event with
  `params.type/payload` framing; streaming events ride the same envelope
  (`message.delta{ text, rendered }`, `reasoning.delta{ text }`,
  `thinking.delta` is a STATUS spinner line not reasoning, `tool.start{
  toolId, name, argsText }`, `tool.complete{ toolId, resultText, durationS }`,
  `message.complete{ text, reasoning, usage }`, `session.info`, `approval.request`).
  `session.create` params are `{ cwd?, source:"chat" }`, response `{ session_id,
  stored_session_id, info }`; `prompt.submit` params `{ text, session_id }`.
  Live probe evidence: a real `hermes dashboard` (already running on this
  device) answered handshake + `gateway.ready` + `session.create` →
  `session_id 740b5a01` (probe script kept out of the repo; token never
  committed). `message.complete` re-seals interim deltas, so the reducer keeps
  the streamed text and only steps the turn — no double render.
  Verified: CI run 40581188609 green (contract tests + mapper tests + APK);
  node-conformance green. Note: contract tests run on `runBlocking` (real IO) —
  `runTest`'s virtual clock stalls real-IO WS round-trips; same pattern as the
  transport suite.

- 2026-10-27 — **M5: Claude Code adapter (Kotlin-only surface; bridge stays JS).**
  User decision: everything app-side stays Kotlin; the Node bridge daemon is kept as-is — a Kotlin/JVM port was
  evaluated (Termux has openjdk-21) and deferred; protocol Appendix A is
  language-agnostic so it can be revisited at M9+ if ever needed.
  Verified live against claude 2.1.112: `--help` flags (stream-json needs
  `--verbose`; `--input/output-format stream-json`; `--resume <id>`;
  `--permission-mode`), and a real one-shot run's 4-line sample
  (system/init{session_id,tools} → system/status → assistant{content[],
  usage} → result{is_error,result,usage,total_cost_usd,duration_ms}).
  The adapter runs CLI through the bridge's `exec.run` (verified denial:
  NOT_ALLOWLISTED until `~/.harnesshub/allow-exec` gate exists in Settings).
  Live token streaming arrives with the bridge process subscription (M6).
