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

## M6b (2026-10-28)
- Vault = Android Keystore AES-GCM; DataStore holds `v1:<b64(iv|ct)>`; legacy plaintext read transparently then re-sealed. Verified at rest via migration path in unit review (CI 40630586382).
- `hermes.setup` result shape verified live vs `hermes dashboard` pid (port 9119). Token never logged; settles in HermesSettingsStore via Vault.
- resourceMode result nests `{mode:{...}}` in harness.list response — app parses both shapes (verified live with mode exclusive→switch→persist).
- OpenCode `serve --port/--hostname` verified live on device v1.19.3: default port is 0 (dynamic), docs' 4096 claim corrected; /global/health healthy; /doc OpenAPI 3.1 snapshot committed under harness/opencode/fixtures/.
- Claude in-app engine = one-shot exec.run turn (60s cap) — full streaming via process channel arrives at M7.

## M6.3 OpenCode Tier-1 direct (2026-10-28)
- Direct default path = app -> opencode serve (HTTP+SSE), no bridge. Contract tests fake the server (MockWebServer); live basis = device probes (health/session/prompt/event verified).
- Parts: full-value part updates diffed via partID bookkeeping (message.part.updated).
- spi: ChatEvent.Other added (collapsible raw block rule, Appendix B); reducer ignores for now.
- CI run 40634252679 green (app wiring + contract tests).

## M6.4 tether + sessions (2026-10-28)
- Foreground service `BridgeConnectionService` (dataSync, START_STICKY) started from Hub; notification channel + POST_NOTIFICATIONS runtime request. Honest partial: process-keepalive first, pinned bridge transport moves in at M7.
- Session directory (SessionStore) records one row per engine/session; Sessions screen lists + opens (resume via SessionOpen handoff: Hermes reuses id, Claude/Opencode one-shot/turn-based resume works) + delete. CI run 40635833471 green.

## M6.5 projects (2026-10-28)
- Projects register (ProjectsStore, DataStore) + screen; active path applied as exec.run cwd for Claude turns (bridge breach-check verified: containedPath). Hermes/OpenCode workspace selection remains partial until their APIs expose it (opencode session create has no directory field in v1.19.3). CI run 40637030965 green.

## M6.6 tasks desk (2026-10-28)
- process.list response extended with the allowlist ({name, command, args}) — verified live on the restarted bridge (demo-echo spec visible). TasksScreen: start/stop of allowlisted specs, live rows id/pid/exit, fa/en strings; ring-buffer output streaming deferred to M9 (honest note on screen). CI 40638011260 green.

## M6.7 codex module + English-only app (2026-10-28)
- harness/codex: CodexStreamParser + CodexAdapter over `codex exec --json` (<prompt> / `exec resume <id>` — resume argv ordering unverified). Event shapes snapshot-derived from the official openai/codex SDK sources fetched 2026-10-28 and committed under fixtures/ (CLI not installed on this device — turns error 'codex unavailable' honestly). 8 fixture tests.
- App: engine picker now HERMES/CLAUDE/OPENCODE/CODEX (4 tabs); codex cwd = active project.
- Language: per user directive the app is English-only — values-fa removed, androidResources.localeFilters=[en], supportsRtl=false. Chinese/Persian reply convention unchanged (chat only).
- CI run 40641113530 green.

## M7.1 skills + models (2026-10-28)
- Bridge `skills.list`: scans ~/.claude/skills and ~/.opencode/skill SKILL.md frontmatter (name/description/path/source) — live-verified on-device with real claude skills dir (bridge restarted, then probed).
- Models screen: OpenCode direct /config/providers (live-verified shape: providers + models map, masked keys); per-session switching at M7b.
- CI 40643528308 green.

## M7.1 skills + models (2026-10-28)
- Bridge `skills.list`: scans ~/.claude/skills and ~/.opencode/skill SKILL.md frontmatter (name/description/path/source) — live-verified on-device.
- Models screen: OpenCode direct /config/providers (live-verified shape: providers + models map, masked keys); per-session switching at M7b.
- CI 40643528308 green.

## M7.2 preferred model (2026-10-28)
- OpenCode model primitives live-verified: POST /api/session/{id}/model body {model:{providerID,id}} → 204 (first attempt without the wrapper key rejected: Missing key model). POST /session with {model:{...}} echoes model on create.
- App: preferred model stored in OpenCodeSettingsStore (plain prefs, not a secret); ModelsScreen tap-select with ✓ marker; new OpenCode sessions are created with the preferred model; existing sessions unchanged.
- CI 40644977855 green.

## M7.3 skill install (2026-10-28)
- Live roundtrip on-device via bridge: fs.write (atomic tmp+rename, parent auto-mkdir, backup on overwrite) → fs.read back → fs.delete of the probe dir (cleanup verified, no leftovers). Probe initially exposed a client-side wsSend masking bug only for frames >125 bytes (irrelevant in production APIs).
- App: install dialog on the Skills screen writes ~/.claude/skills/<name>/SKILL.md via fs.write; home resolved dynamically from system.info. CI 40646538042 green.

## M7.4 skill edit/delete (2026-10-28)
- Skills screen rows gained Edit (fs.read → multiline SKILL.md editor → fs.write, bridge keeps timestamped backup) and Delete (confirm dialog → recursive fs.delete, verified recursive in bridge).
- skills vm hooks are plain fun with internal scope.launch; bridgeRequest shared transport helper.
- CI 40648742084 green.

## M7.5 tether service pinned (2026-10-28)
- BridgeConnectionService now owns a PINNED bridge WebSocket: jittered-backoff reconnect loop (1s→30s), START_STICKY, foreground notification; companion bridgeState StateFlow (WaitingForSetup/Connecting/Ready/Reconnecting) surfaced as a tether chip on the Hub.
- Per-connection Hermes gateway pinning (shared connection registry so chat screens reuse the service socket) NOT in this increment — chat still opens its own transports; honest partial.
- CI 40651223313 green.

## M7.6 cron (2026-10-29)
- Bridge cron (section 21): `cron.list/create/delete/run-now` + minute scheduler. Jobs store in ~/.harnesshub/cron.json (0600, atomic tmp+rename) and reference an allow-listed launch spec by name + optional argument array — the earlier shell-string plan (`shellAllowlisted(command)`) was discarded before landing: Appendix F forbids shell concatenation, so a cron job can only launch what process.start could.
- Spec validator: 5 fields (m h dom mon dow), lists/ranges/steps/dow names (sun-sat, 0-7 mod 7, wrapped ranges), classic dom/dow OR rule; refusal code BAD_CRON_SPEC. cron.list computes nextRunAt per job (local bridge clock); scheduler ticks every 20 s, fires each parsed matching spec once per minute (lastCronMinute dedupe), records runs newest-first (cap 20) with exit code/signal captured on child exit; cron.run-now fires immediately; audit entries cron.create/delete/fire/fire.denied/fire.error.
- Verification: conformance now 44/44 (bad/out-of-range/injection specs, off-allowlist refusal, create→list nextRunAt, run-now spawn + history, unknown NOT_FOUND, delete, empty-after-delete) and a live minute-tick test: `* * * * *` job fired exactly once on the tick, no double-fire within the same minute, cleaned up after.
- Honest partials: presets/human-readable builder are UI-side chips (raw spec mode + four presets), not a full natural-language builder; run history is 20-entry per job, not a paginated log; scheduler fires only while the bridge process is alive (Doze/SOUL of the tether service is the M7.5 keep-alive story, not a guarantee).
- App: CronScreen (list, create dialog with presets + raw spec, run-now, delete confirm) + Hub "Cron…" entry; CronViewModel mirrors the Skills transport helper. Next: plugins, MCP, tools/permissions, memory/identity, env.

## M7.7 memory/identity (2026-10-29)
- MemoryScreen (section 13 row) over existing bridge primitives — no new bridge surface needed: fs.stat (present/missing honestly), fs.read, fs.write (Atomic tmp+rename, timestamped backup). Candidate set fixed to the on-device verified layout: ~/.hermes/SOUL.md, ~/.hermes/memories/MEMORY.md + USER.md, ~/CLAUDE.md, ~/.claude/CLAUDE.md, ~/.codex/AGENTS.md — cross-checked by live probe (3 present, 3 missing, SOUL.md head read back).
- Honest partials: no diff-before-save, no backup restore/choose-backup list, no biometric reveal (memory files may contain no secrets — confirmed at candidate level only); missing files are shown, not auto-created.
- CI latest green (cron + conformance 44/44 on f9e26f6). Next: plugins, MCP, tools/permissions, env.

## M7.8 env (2026-10-29)
- Bridge `env.list/set/delete` (section 13 row) over structured ~/.hermes/.env: KEY=value parse, comments/blank lines preserved, key regex [A-Za-z_][A-Za-z0-9_]* (BAD_ENV_KEY), 0600 atomic write (tmp+rename), audit env.set/delete WITHOUT values. Masking: non-safe keys are never transmitted raw — `***(hides <len>B)`; safe set PATH/SHELL/HOME/LANG/TZ/TERM/TMPDIR/PREFIX/PWD/HOSTNAME shown raw. No unmasked read method exists by design (bridge cannot reveal secrets; app-side biometric reveal comes with the vault milestone).
- ~/.bashrc exports deliberately not parsed in this increment (structured .env first; honest partial). shellAllowlisted shell-string cron plan (see M7.6) precedent: rejected before landing, this one never drafted at all.
- Verification: conformance 57/57 (empty list, BAD_ENV_KEY x2, secret set, safe key kept raw, mask/no-leak, NOT_FOUND delete, clean deletes, file mode 0600) + live on-device probe: 2 real keys masked, HH_TMP_TEST_KEY set→masked(8B)→no leak→deleted→real keys intact.
- App: EnvScreen (list/add/delete, masked values) + Hub Env… entry. M7 remaining: plugins, MCP, tools/permissions.

## M7.9 mcp + plugins (2026-10-29)
- McpScreen (section 13 row, Claude Code): mcpServers edits inside ~/.claude.json by pure text transforms (core:transport McpJson: parse —> mutate —> pretty 2-space write; sibling keys preserved; malformed/duplicate/missing refused). Bridge unchanged — reads go through fs.read with a 2 MB window plus a size guard (refuse rewrite if returned content < reported size); writes via fs.write (timestamped backup). Sibling keys verified by unit test (McpJsonTest: names/summaries/add/remove roundtrip/refusals/absent-mcpServers).
- On-device verified shape: mcpServers count 1 (playwright, stdio script), 2 installed plugins (claude-mem@thedotmack v13.35.0, superpowers@superpowers-dev), 3 known marketplaces.
- PluginsScreen read-only over installed_plugins.json + known_marketplaces.json (nothing mutates; install/disable engine later). No test-connection and no per-server tool listing yet (needs exec/claude mcp list — honest partial).
- App still English-only; Hub actions row switched to FlowRow (8 buttons now wrap). Next M7 residual: tools/permissions dedupe with Hub rows, OpenCode-side MCP/plugin config.

## M7.10 tools/permissions + plugin toggles (2026-10-29)
- ToolsScreen (section 13 row): execFlag Switch (bridge.execFlag, same write path Hub used), Claude permission rules add/remove (permissions.allow/deny in ~/.claude/settings.json — block created if absent, empty lists removed on cleanup), defaultMode read-only display (real mode names default/acceptEdits/plan/bypassPermissions; switching UI deferred with the approvals engine instead of exposing one-tap bypassPermissions).
- PluginsScreen upgrade: enable/disable Switches via enabledPlugins in settings.json (PluginJson transforms + JUnit; install/uninstall still deferred).
- Pure text transforms core:transport PluginJson/PluginJsonTest (mode whitelist, duplicate/missing refusals, sibling keys preserved). Size guard + fs.write backup same as McpJson path.
- Live on-device roundtrip: rule Bash(hh-probe *) added → present → removed (env/model/enabledPlugins byte-identical semantics), plugin superpowers@superpowers-dev false→true→false, final file matches original shape (no permissions block; probe left an empty allow[] which the cleanup removed — cf. PluginJson.removeRule deleting empty lists).
- M7 residual after this: OpenCode-side MCP/plugin config, MCP test-connection, per-session tool toggles in Chat. Everything else in the M7 list is landed and CI-green.
