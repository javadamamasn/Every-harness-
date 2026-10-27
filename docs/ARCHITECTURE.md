# Architecture

Full design brief lives in the build prompt; this file records how the codebase
realizes it, milestone by milestone.

## Layers

```
app (Compose UI, navigation)
  └── core:harness-api (SPI the UI consumes)
        └── core:model (pure domain)
harness:* adapters (implement the SPI, registered via DI multibindings)
  └── core:transport (WS JSON-RPC, HTTP+SSE, stdio-over-bridge, PTY)
        └── core:termux (bridge client, RUN_COMMAND bootstrap)
core:discovery (binary / footprint / port probes from every adapter)
core:data (Room cache, DataStore, Keystore vault)
```

## Invariants

1. The UI knows nothing about any harness's native protocol. Everything arrives as
   the unified `ChatEvent` stream and is reduced to display state by `ChatReducer`,
   the single place with chat-rendering rules (idempotence, partial-JSON tolerance,
   thinking/text interleaving, cancel-keeps-content, retry-as-sibling, raw-block
   preservation are all unit-tested contract rules).
2. Adding a harness = adding one `harness:<name>` module (or a JSON manifest) plus a
   DI multibinding. Feature modules are never edited for a new harness.
3. Unsupported capabilities are hidden (Capability-gated rendering), never faked.
4. The harness is the source of truth; Room is a read-through cache; secrets live
   only in the Keystore vault.
