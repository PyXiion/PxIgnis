# PxIgnis — Agent instructions

Fabric mod — Lua scripting API for Minecraft server. Kotlin 2.3.21, Fabric Loom 1.16, Java 21.

## Build & test

```
./gradlew build -PtargetVersion=1.21.10   # build for 1.21.10
./gradlew build                           # build for 1.21.11 (default)
./gradlew test                            # unit tests only, no MC runtime
./gradlew runServer
```

- **Two builds**: `-PtargetVersion=1.21.10` / `1.21.11` switches MC version, Yarn mappings, Fabric API, and fabric-permissions-api (0.5.0 / 0.6.1). Version-specific code in `src/version-*/kotlin/` (only `Compat.kt`).
- Shadow relocates `org.luaj` → `ru.pyxiion.lib.luaj` and `me.lucko.fabric.api.permissions` → `ru.pyxiion.lib.fabric.api.permissions`.
- Build prints 5× `Cannot remap children/literals/command/requirement…` — cosmetic, ignore.
- Composite build: `includeBuild 'pxluanova'` in `settings.gradle`.
- CI: `.github/workflows/build.yml` — both versions on push/PR to `main`; auto-publishes to Modrinth on tag push.
- Access widener: `src/main/resources/pxignis.accesswidener`.
- `.luarc.json` disables `undefined-global` diagnostic — Lua globals (`mc`, `vec`, `register`, etc.) are injected at runtime.

## Testing quirks

`src/test/kotlin/ru/pyxiion/ignis/` — 6 files, JUnit 5 via `kotlin-test-junit5`. Pure logic, no MC runtime.

- `BrigadierTreeTest` reflects `CommandNode.children` field directly — `getChildren()` returns `Collection`, not `Map`. Use `childrenField.get(node) as Map<*, *>`.
- `MetaTableRegistryTest` must NOT call `MetaTableRegistry.init()` — that triggers MC bootstrap and crashes. Tests read pre-existing metatables directly.

## Architecture

**Entry point**: `PxIgnis.kt` (ModInitializer) → `IgnisRuntime.kt` (orchestrator).

```
PxIgnis.kt          lifecycle + event wiring (24 events, 10 cancellable — see site docs `/reference/events`)
IgnisRuntime.kt     reload(), owns EventBus, Scheduler, LuaCommandManager, ScriptEnvironment, LuaMcApi
```

| Directory | Contents |
|---|---|
| `runtime/` | `ScriptEnvironment.kt` (LuaState setup, globals), `ScriptLoader.kt` (reads `config/ignis/*.lua`) |
| `sandbox/` | `Vfs.kt` + `LuaRequire.kt` (custom `require`, restricts `package.path` to `config/ignis/`) |
| `commands/` | `CommandRegistrar.kt` (exposes `register()` to Lua), `CommandSyntax.kt` (parser), `LuaCommandManager.kt` (Brigadier tree), `ArgumentTypes.kt` |
| `api/` | `LuaMcApi.kt` (mc.\* surface), `MetaTableRegistry.kt` (12 shared metatables) |
| `api/wrapper/` | `EntityWrap`, `PlayerWrap`, `WorldWrap`, `MobWrap`, `HologramWrap`, `RegionWrap`, `ItemStackWrap`, `InvWrap`, `ContainerWrapper`, `SidebarWrapper`, `StructureWrap`, `PlayerListWrapper` |
| `api/manager/` | `Region.kt`, `MobAIManager`, `HologramManager`, `ContainerManager`, `SidebarManager` |
| `api/util/` | `ItemBuilder`, `ItemStackCodec`, `LuaGoal`, `MetaTableBuilder`, `Raycast` |
| `storage/` | `DataTable`, `DataBackend`, `JsonBackend`, `StorageManager` |
| `mixins/` | 7 Java mixins (CommandNode, Entity, LivingEntity, MinecraftServer, MobEntity, ScreenHandler, StructureTemplate) |

**EventBus** (`EventBus.kt`) — shared by `mc.on()` and `region:on()`. `on(event, callback, throttle?)` returns int ID. `off(id)` removes by ID. `fire()` error-isolates handlers. Throttle decremented via `tick()`.

**Shared metatables** — one `LuaTable` per wrapper type (12 total). `MetaTableRegistry.init()` creates fresh metatables in order: vec→entity→player→world→structure→item→inventory→container→sidebar→mob→hologram→region. Companion objects expose `initMeta(meta)` that sets up `__index`/`__newindex` via `rawset`.

`toLuaValue()` creates fresh `LuaTable`, `setmetatable(MetaTableRegistry.X)`, rawsets `__pxrp_type` + `__pxrp_object` (+ `__pxrp_data` for per-instance state).

**Wrapper inheritance** — `PlayerWrap.__index` falls through to `ENTITY.__index`. Same for `MobWrap` and `HologramWrapper`.

**Player wrapper cache** — `LuaMcApi` owns `mutableMapOf<UUID, LuaValue>()`. `mc.players` (property) and `world.players` share this cache via `PlayerListWrapper` (per-tick caching). Invalidated per-player on `DISCONNECT`.

**Tags/pos lazy-cached** — first `__index` access creates a proxy table and `rawset`s it; subsequent reads bypass `__index`.

## Conventions & gotchas

- `Utils.kt` (root) and `types/Utils.kt` are separate files with distinct helpers.
- `luaToNbt`/`nbtToLua` are in root `Utils.kt`. Do NOT duplicate.
- After `setmetatable()`, `LuaTable.set()` goes through `__newindex`. Always use `rawset` for new keys.
- `__index`/`__newindex` via Lua `:` syntax: arg(1) is `self`, actual params start at arg(2).
- `resolveBlockId` auto-prepends `minecraft:` if no namespace present.
- `ItemStackWrap.unwrap()` always calls `copy()` — never leak raw references.
- `__pxrp_data` pattern: per-instance state as userdata on the Lua table. Never use companion `object` statics — they survive reload.
- `PxLuaNova subproject`: `pxluanova/AGENTS.md` has its own agent instructions.

## Reload sequence

`IgnisRuntime.reload()`:
`storageManager.saveAll()` → fire `uninit` → clear command/event/scheduler/managers (`Container`, `Sidebar`, `MobAI`, `Hologram`, `Region`) → `ScriptEnvironment.rebuild()` (fresh globals including `vec`, `mc`, `register`) → load & execute `.lua` files → `MobAIManager.scanAndReapply()` → fire `init` → `LuaCommandManager.registerAll()`.

## Lua environment

- **Runtime**: PxLuaNova (Lua 5.2), composite build from `pxluanova/`
- **Config dir**: `config/ignis/` (`.lua` files sorted alphabetically). Falls back to `config/ignis.lua`. First run copies `demo.lua` from resources.
- **`package.path`**: `config/ignis/?.lua;config/ignis/?/init.lua;?.lua`
- **Loaded libs**: `math`, `string`, `table`, `bit32`, `package` (custom), `coroutine`, `nova`. **Not loaded**: `io`, `os`, `debug`.
- **Globals injected**: `mc` (table), `vec(x,y,z)`, `register(syntax, handler, permission?)`.
- **Built-in Lua libs** (loaded via `require`): `format`, `simple`, `chestgui`.
- **Scheduler**: ticked via `ServerTickEvents.END_SERVER_TICK`. Tasks cleared on reload and server stop.
- `mc.sleep(ticks)` / `mc.fetch(url)` — coroutine-yielding async. Not available in event handlers (use `mc.schedule(0, fn)`).

**Lambda syntax** (opt-in: `--# nova syntax` on line 1):
  - `\{ x, y -> x + y }` → `function(x, y) return x + y end`
  - `\{ return 42 }` — single-expression body, implicit return
  - Trailing block: `register("x") \{ ctx -> ... }` desugars to second arg. Trailing bodies are chunks (no implicit return).
  - `\{` is a two-char lexer token. Bare `\` outside string = syntax error.

## API surface (site reference)

| Topic | File |
|---|---|
| All events (mc.on) | [`PxIgnis.kt`](src/main/java/ru/pyxiion/ignis/PxIgnis.kt) (also `/reference/events` in site docs) |
| mc.\* API | [`LuaMcApi.kt`](src/main/java/ru/pyxiion/ignis/api/LuaMcApi.kt) |
| register() syntax + types | [`CommandSyntax.kt`](src/main/java/ru/pyxiion/ignis/commands/CommandSyntax.kt) |
| **Full docs** | **ignis.pyxiion.ru** — 18 reference pages |
