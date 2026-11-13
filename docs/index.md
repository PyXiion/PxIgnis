# Docs

Design documents for PxIgnis. These explain *why* things work the way they do — the tradeoffs and decisions behind the
code. Changelog entries describe what changed; these describe the reasoning.

## Documents

- [Async suspend bridge (Kotlin ↔ Lua)](./async-suspend-bridge.md) — how Lua coroutines call Kotlin `suspend` blocks
  without blocking the server thread.
- [Event model](./events.md) — event tables, cancellation, priorities, fail-closed errors and the event name rule.
- [Dialogs](./dialogs.md) — raw vanilla API versus the Lua library, click routing and validation, the pause-menu pack.
- [Plugins and script packages](./plugins.md) — the `pxignis` entrypoint, `ext:` modules, LuaConvert, `ignis.json`
  manifests and load order.
- [ignis-voice](./voice.md) — custom audio through Simple Voice Chat (Plasmo Voice later): emitters, decoding,
  the backend abstraction.
