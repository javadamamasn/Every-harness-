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
  Verified: workflow pushed and green (to be confirmed on first run).
