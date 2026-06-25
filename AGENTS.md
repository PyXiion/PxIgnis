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

## Testing quirks

`src/test/kotlin/ru/pyxiion/ignis/` — JUnit 5 via `kotlin-test-junit5`. Pure logic, no MC runtime.

- `BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns `Collection`, not `Map`.
  Use `childrenField.get(node) as Map<*, *>`.
- `MetaTableRegistryTest` must NOT call `MetaTableRegistry.init()` — that triggers MC bootstrap and crashes. Tests read
  pre-existing metatables directly.

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
- `mc.sleep(ticks)` / `mc.fetch(url)` coroutine-yielding async is NOT available in event handlers; use `mc.schedule(0, fn)`.

## Lua environment → see `agent_docs/lua.md`

Loaded libs, `package.path`, globals, lambda syntax, scheduler tick, built-in `require` libs (`format`, `simple`,
`chestgui`).

## API surface (site reference)

When writing scripts, prefer linking to docs over source code.
When updating the API, always ask user if he wants to update the documentation (site) & lua-types (<project>/lua-types/*.lua).

| Topic                     | File                                                                                             |
|---------------------------|--------------------------------------------------------------------------------------------------|
| All events (mc.on)        | [`PxIgnis.kt`](src/main/java/ru/pyxiion/ignis/PxIgnis.kt) (also `/reference/events` in site docs) |
| mc.\* API                 | [`LuaMcApi.kt`](src/main/java/ru/pyxiion/ignis/api/LuaMcApi.kt)                                  |
| register() syntax + types | [`CommandSyntax.kt`](src/main/java/ru/pyxiion/ignis/commands/CommandSyntax.kt)                   |
| **Full docs**             | **ignis.pyxiion.ru**                                                 |

`register("syntax", function(ctx))` does NOT have `ctx.args`. It uses positional args.
For `register("cmd <arg1:word> <arg2:player>", handler)` handler is `(ctx, arg1, arg2)`.
