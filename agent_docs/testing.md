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
- `PXIGNIS_SMOKE_MODS="a.jar b.jar"` copies extra mod jars into `mods/`, e.g. a plugin under test. `smoke/packages/` is
  copied to `config/ignis/packages/` on every run (two packages with a dependency, a `pkg:` require and a bare
  `require` inside a package; checked at the top of `ignis-smoke.lua`). `smoke_beta` calls `ext:smoketest`'s
  `file()` when such a plugin is installed. The plugin API was verified with a throwaway Gradle subproject (a Kotlin
  `IgnisPlugin` registering a module, `onClose` counters, `extend`, `emit`, `file`) built per target and passed via
  `PXIGNIS_SMOKE_MODS` with a script that asserts `closes == envs - 1` after the smoke run's `/reload`.
- Pure plugin/package logic is covered by `ModuleNamesTest` (names, path sandbox), `PackagePlanTest` (manifest, order,
  cycles, cascade), `LuaRequireTest` (namespaces, package envs) and `PluginTypesTest`.
- `plugins/voice/smoke/run.sh [target]` wraps `smoke/run.sh` (via `PXIGNIS_SMOKE_MODS` / `PXIGNIS_SMOKE_EXTRA`) and
  boots with and without Simple Voice Chat.

Anything touching Minecraft classes is verified this way on both targets; extend `smoke/ignis-smoke.lua` when you fix
something that unit tests cannot reach. Do not use `./gradlew runServer` for this: it shares `run/` and its world
between targets, and switching targets leaves stale build output.
