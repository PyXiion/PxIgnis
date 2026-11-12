# Testing

`src/test/kotlin/ru/pyxiion/ignis/` — JUnit 5 via `kotlin-test-junit5`. Pure logic, no MC runtime.

- `BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns `Collection`, not `Map`.
  Use `childrenField.get(node) as Map<*, *>`.
- `MetaTableRegistryTest` must NOT call `MetaTableRegistry.init()` — that triggers MC bootstrap and crashes. Tests read
  pre-existing metatables directly.

## Smoke script

`smoke/ignis-smoke.lua` — drop into `config/ignis/` of an empty server (no other scripts) and boot it. Every check
prints `[SMOKE] <name>: OK|FAIL <reason>`, the run ends with `[SMOKE] done`. Anything touching Minecraft classes is
verified this way on both targets; extend the script when you fix something that unit tests cannot reach.
