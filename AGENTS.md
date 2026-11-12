# PxIgnis — Agent instructions

Fabric server mod: Lua scripting API for Minecraft. Kotlin, two MC targets (1.21.11 and 26.3).
Entry points: `PxIgnis.kt` (ModInitializer) → `IgnisRuntime.kt`.
`pxluanova/` is a luaj fork built as an included build; it has its own `AGENTS.md`. Read its diffs, not the whole VM.

## Project status: unstable

PxIgnis is pre-1.0 and promises no compatibility. Breaking changes to the Lua API, event arguments, storage format,
config and internals are allowed and expected whenever they make the design simpler or more correct.

- Do not add compatibility shims, deprecated aliases or migration layers. Remove the old path.
- Change every caller in the same commit: built-in Lua modules (`src/main/resources/*.lua`), demo scripts, tests,
  `agent_docs/`, site docs.
- List each break under "Breaking" in `site/src/content/docs/changelog.md`.

## Build & test

```
./gradlew build                           # 1.21.11 (default)
./gradlew build -PtargetVersion=26.3      # 26.3
./gradlew test                            # unit tests, no MC runtime
```

- Run Gradle on JDK 25. Targets are defined by `gradle/versions/<v>.properties`.
- Artifact: `build/libs/PxIgnis-<mod_version>-fabric-<v>.jar`.
- Every change must build for both targets.
- Unit tests cover pure logic only. Anything touching Minecraft classes is verified by `smoke/run.sh` (boots a real
  Fabric server per target with the production jar). A green build does not show that mixins apply or that the mod
  starts.
- Test quirks: `agent_docs/testing.md`.

## Source layout

- Source uses Mojang mappings on both targets (Loom remaps 1.21.11; 26.x is unobfuscated).
- Shared: `src/main/java` (Kotlin and Java). There is no client source set: the mod is common/server code only.
- Per version: `src/version-<v>/{kotlin,java,resources}` — a `Compat` object with the same API on each target,
  mixins whose target signatures differ, and `pxignis.accesswidener`.
- Anything that differs between MC versions goes behind `Compat`. No version checks in shared code.
- New target: add a properties file and a `src/version-<v>` directory.

## Gotchas

- `Registry.getId(x)` is the numeric id. The identifier is `getKey(x)`.
- `BuiltInRegistries.BLOCK`, `ITEM` and `ENTITY_TYPE` are defaulted: `getValue` never returns null, an unknown id
  yields air or pig. Use `getOptional`.
- Fabric API differs between targets (mod id `fabric-api` on 26.x, renamed networking and client-command classes).
  Check the actual jar with `javap` instead of assuming.
- After `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Use `rawset` for new keys.
- In `__index` / `__newindex` and `method { }` bodies, `arg(1)` is `self`; real parameters start at `arg(2)`.
- Wrapper metatables (`BUILT`) are shared and survive script reload. Per-object state lives on the wrapper table,
  never in a `companion object`.
- The Lua state is rebuilt on every script reload. Never capture a `LuaState`; take it from the provider at call time.
- `luaj` numbers: `LuaString.isint()` / `isnumber()` are true for numeric strings, and `LuaNumber.isstring()` is true.
  Branch on `type()` when the distinction matters.
- `ItemStackWrap.wrap()` holds the live stack; `ItemStackWrap.unwrap()` returns a copy.
- Events: built-in names live in `events/EventCatalog.kt` (`GlobalEvents`, `RegionEvents`); Minecraft callbacks
  post them through `events/GameEvents.kt` (mixins call its `@JvmStatic` functions). Handlers get one event table
  `e` (fields set with `rawset`, `e:cancel()` from a shared metatable). `EventBus.on`/`emit` accept a built-in name of
  the bus's catalog or a namespaced custom name `ns:name` and throw on anything else. Adding an event = `EventDef` +
  a `GameEvents` function + `lua-types/19-events.lua` + `site/.../reference/events.md`. Design: `docs/events.md`.
- Script errors/warnings go through `runtime/ScriptErrors` (log + chat to `px.ignis.prompt_errors`, rate-limited),
  not `logger` directly.

## Releases

Bump `mod_version` in `gradle.properties` and the badge in `README.md`, add the changelog entry, tag `v<version>`.
**Never commit a version bump or changelog entry without user review.** Stage the files and stop.
CI (`.github/workflows/build.yml`) builds both targets and publishes to Modrinth on tag push.

## Tutorial

`site/src/content/docs/tutorial/` is a step-by-step arena minigame; its code is in `site/tutorial/arena/`
(one checkpoint file per chapter) and is checked by `site/tutorial/test.sh` (snippets match checkpoints,
LuaLS against `lua-types/`, a fake-API game run). API changes that touch it must keep that script green.

## More

- Lua environment, globals, built-in modules: `agent_docs/lua.md`
- Events, `mc.*`, command syntax: `agent_docs/api.md`
- Design rationale (why, not what): `docs/`
