# Docs

Design documents for PxIgnis. These explain *why* things work the way they do — the tradeoffs and decisions behind the
code. Changelog entries describe what changed; these describe the reasoning.

## Documents

- [Async suspend bridge (Kotlin ↔ Lua)](./async-suspend-bridge.md) — how Lua coroutines call Kotlin `suspend` blocks
  without blocking the server thread.
- [Event model](./events.md) — event tables, cancellation, priorities, fail-closed errors and how the old
  positional event names are kept working.
