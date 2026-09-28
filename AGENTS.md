# PxIgnis — Agent instructions

Fabric mod — Lua scripting API for Minecraft server. Kotlin 2.3.21, Fabric Loom 1.16, Java 21.
Entry points: `PxIgnis.kt` (ModInitializer) → `IgnisRuntime.kt` (orchestrator).
Subproject `pxluanova/` has its own `AGENTS.md`.

## Bumping version

Update `mod_version` in `gradle.properties`, the badge in `README.md:3`, add changelog entry in
`site/src/content/docs/changelog.md`, commit & tag `v<version>`.

**Do NOT commit version bump or changelog without user review first.** Stage the files and let the user decide when to
commit and tag.

## Build & test

```
./gradlew build -PtargetVersion=1.21.10   # build for 1.21.10
./gradlew build                           # build for 1.21.11 (default)
./gradlew test                            # unit tests only, no MC runtime
./gradlew runServer
```

Two MC versions (`-PtargetVersion=1.21.10` / `1.21.11`); version-specific code lives in `src/version-*/kotlin/`.
CI: `.github/workflows/build.yml` — both versions on push/PR to `main`; auto-publishes to Modrinth on tag push.

## Testing quirks → see `agent_docs/testing.md`

JUnit 5 via `kotlin-test-junit5`. Pure logic, no MC runtime. Two tests have quirks: `BrigadierTreeTest` (reflection on
`CommandNode.children`) and `MetaTableRegistryTest` (must NOT call `init()`).

## Conventions & gotchas

- `Utils.kt` (root) and `types/Utils.kt` are separate files with distinct helpers.
- After `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Always use `rawset` for new keys.
- `__index`/`__newindex` via Lua `:` syntax: arg(1) is `self`, actual params start at arg(2).
- `resolveBlockId` auto-prepends `minecraft:` if no namespace present.
- `ItemStackWrap`: `wrap()` stores the raw `ItemStack` as `__pxrp_object`; property setters (`item.count = 5`) mutate it in
  place. `ItemStackWrap.unwrap()` (and the `:copy()` method) call `.copy()` — so a Lua-side assignment like
  `player2.mainhand = player1.mainhand` is safe, but `unwrap()` consumers must not assume the returned stack is
  the same instance as the source.
- Per-instance wrapper state (e.g. `WorldWrap`'s `InstanceData` with `playerCache` + `tickProvider`) lives on
  `__pxrp_data` userdata, not on Kotlin `companion object` fields. The shared `BUILT` metatable template on
  `companion object` IS the right place for shared/constant data — it must survive reload.
- EventBus runs `LuaClosure` handlers through a `LuaThread` (`EventBus.kt` `invokeCallback`), so coroutine-yielding
  async (`async.sleep`/`async.fetch`) and suspend functions work inside event handlers — they did not before.
- `luaSuspendFunction(scope, block)` / `luaSuspendFunctionNil` (`Utils.kt`) return Lua functions that yield and resume
  the coroutine when the suspend block completes. Requirements: must be called inside a coroutine (not main thread) and
  the thread must have a `LuaThread.resumeHandler`. The main thread handler is set in `LuaMcApi.init`. `future.handle`
  must be registered BEFORE `scope.launch` (fast-completion race). Design rationale: `docs/async-suspend-bridge.md`.
- Lua states are single-threaded. `async` tasks on the `threadpool` executor (`AsyncExecutor.isolated`) run in a
  fresh worker state (`ScriptEnvironment.newWorkerState`: sandbox + `async`, no `mc`); `AsyncLib.Isolation` copies
  the function, upvalues, args and results across with PxLuaNova's `LuaTransfer` (userdata rules:
  `StateTransferPolicy`). One `AsyncLib` per state, created in `ScriptEnvironment.rebuild`.
- `EventBus` requires a `stateProvider: () -> LuaState?` for `LuaClosure` handlers; without it they throw. Regions use
  `RegionManager.sharedStateProvider` (set in `LuaMcApi.init`).

## Lua environment → see `agent_docs/lua.md`

Loaded libs, `package.path`, globals, lambda syntax, scheduler tick, built-in `require` libs (`format`, `simple`,
`chestgui`).

## Design docs → see `docs/`

Changelog (`site/src/content/docs/changelog.md`) says WHAT changed; `docs/` (e.g. `async-suspend-bridge.md`) documents
WHY — design decisions, tradeoffs, deferred work. Point there before re-deriving rationale.

## API surface → see `agent_docs/api.md`

Topics: all events (`mc.on`), `mc.*` API, `register()` syntax + types. `register("syntax", function(ctx))` does NOT
have `ctx.args` — it uses positional args.
