# Testing

`src/test/kotlin/ru/pyxiion/ignis/` — JUnit 5 via `kotlin-test-junit5`. Pure logic, no MC runtime.

- `BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns `Collection`, not `Map`.
  Use `childrenField.get(node) as Map<*, *>`.
- `MetaTableRegistryTest` must NOT call `MetaTableRegistry.init()` — that triggers MC bootstrap and crashes. Tests read
  pre-existing metatables directly.

## Smoke suite

```
smoke/run.sh [all|1.21.11|26.3]
```

Boots a real Fabric server per target with the production jar (`build/libs/PxIgnis-<v>-fabric-<t>.jar`, rebuilt only
when a source file is newer) and `smoke/ignis-smoke.lua`. It tests the same artifact users run, so mixins and the
remapped jar are exercised. Every check prints `[SMOKE] <name>: OK|FAIL <reason>`; a run passes when `all done` is
reached with no FAIL line and no `ERROR`/`FATAL` log line. Exit status is non-zero otherwise; the log is kept in
`build/smoke/<target>.log`.

- Isolation: each run uses a throwaway server dir in `$TMPDIR` (own world, own port, deleted on exit), so `run/` is
  untouched and targets cannot clash over a world.
- Disk: downloads (Fabric launcher, vanilla server, libraries, Fabric API, Fabric Language Kotlin) are cached once per
  target in `~/.cache/pxignis-smoke` (`PXIGNIS_SMOKE_CACHE`), about 160 MB per target. Nothing is recompiled or
  redownloaded on later runs.
- Needs the EULA accepted: `eula=true` in `run/eula.txt` or `MINECRAFT_EULA=true`.
- `PXIGNIS_SMOKE_EXTRA="a.lua b.lua"` drops extra scripts next to the smoke script for one-off checks.

Anything touching Minecraft classes is verified this way on both targets; extend `smoke/ignis-smoke.lua` when you fix
something that unit tests cannot reach. Do not use `./gradlew runServer` for this: it shares `run/` and its world
between targets, and switching targets leaves stale build output.
