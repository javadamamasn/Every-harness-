# Harness Hub

Mission control for the AI agent harnesses installed on your own phone — Hermes
Agent, Claude Code, OpenCode and any harness you add later, in one native Android
app (Kotlin + Jetpack Compose, Material 3, Persian/RTL + English).

Status: **M1 — foundation** (see `AGENTS.md` for the milestone checklist).

## What works today

- Compose app shell with six top-level screens (Hub, Chat, Sessions, Skills,
  Models, Settings), custom theme, edge-to-edge, full en/fa strings with RTL.
- The Harness SPI: `Capability`, `ChatEvent`, pure `ChatReducer` — unit tested.
- CI (GitHub Actions) building a debug APK artifact on every push to `main`.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew test            # core:harness-api tests
```

Minimum: JDK 17, Android SDK 35. The APK lands in `app/build/outputs/apk/debug/`.

## Roadmap

Milestones M2–M10 in `AGENTS.md`. Architecture notes in
`docs/ARCHITECTURE.md`, verified protocol facts in `docs/DECISIONS.md`.

## License

MIT. See `LICENSE` and `NOTICE` (attribution for reused MIT code arrives with the
milestone that reuses it).
