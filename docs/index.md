# Docs

Design documents for PxIgnis. These explain *why* things work the way they do — the tradeoffs and decisions behind the
code. Changelog entries describe what changed; these describe the reasoning.

## Documents

- [Event model](./events.md) — event tables, cancellation, priorities, fail-closed errors and the event name rule.
- [Dialogs](./dialogs.md) — raw vanilla API versus the Lua library, click routing and validation, the pause-menu pack.
- [Plugins and script packages](./plugins.md) — plugin lifecycle/`ext:` modules/LuaConvert, script-package manifests and load order, async suspend bridge (Kotlin ↔ Lua), and plugin integrations (`ignis-voice`, `ignis-mapcanvas`).
